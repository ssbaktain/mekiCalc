package mekicalc.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mekicalc.json.Json;

/**
 * 봇 발신 큐(Outbox). 서버가 "카톡 방들에 보낼 메시지"(신규 공지 · 종료 임박 리마인더 등)를 여기 쌓고,
 * 봇 브릿지가 {@code poll}로 가져가 발송한 뒤 {@code ack}로 확정한다(at-least-once, 유실 0).
 *
 * <p>파일 기반(data/bot_outbox.json). {@code dedupKey}로 같은 메시지 중복 적재를 막는다(공지ID·리마인더 날짜 등).
 * 단일 머신(봇=서버 같은 미니PC)이라 로직은 단순하게: poll은 pending을 반환하며 lease 시각만 갱신,
 * ack가 와야 sent로 확정 → 봇이 발송 직후 죽어도 "재발송"이지 "유실"이 아니다.
 */
public final class BotOutbox {

    /** poll로 가져간 뒤 이 시간(초) 안에 ack 없으면 다시 발송 대상. */
    private static final long LEASE_SECONDS = 120;
    private static final int KEEP_SENT = 500;   // sent 메시지 보관 상한(오래된 것부터 정리)

    public static final class Msg {
        public long id;
        public String text;
        public String dedupKey;
        public String status = "pending";   // pending | sent
        public long leasedAt, createdAt, sentAt;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", (double) id);
            m.put("text", text);
            m.put("dedupKey", dedupKey);
            m.put("status", status);
            m.put("leasedAt", (double) leasedAt);
            m.put("createdAt", (double) createdAt);
            m.put("sentAt", (double) sentAt);
            return m;
        }
        static Msg fromMap(Map<String, Object> m) {
            Msg x = new Msg();
            x.id = (long) Json.num(m, "id", 0);
            x.text = str(m, "text");
            x.dedupKey = str(m, "dedupKey");
            x.status = m.get("status") == null ? "pending" : m.get("status").toString();
            x.leasedAt = (long) Json.num(m, "leasedAt", 0);
            x.createdAt = (long) Json.num(m, "createdAt", 0);
            x.sentAt = (long) Json.num(m, "sentAt", 0);
            return x;
        }
        private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : v.toString(); }
    }

    private final Path file;
    private final List<Msg> msgs = new ArrayList<>();
    private final Set<String> dedup = new HashSet<>();
    private long seq = 0;

    public BotOutbox(Path dataDir) {
        this.file = dataDir.resolve("bot_outbox.json");
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            for (Object o : Json.asList(Json.parse(Files.readString(file, StandardCharsets.UTF_8)))) {
                Msg x = Msg.fromMap(Json.asMap(o));
                msgs.add(x);
                if (x.dedupKey != null) dedup.add(x.dedupKey);
                if (x.id > seq) seq = x.id;
            }
        } catch (Exception e) { System.err.println("bot_outbox.json 읽기 실패(무시): " + e); }
    }

    private synchronized void save() {
        try {
            List<Object> arr = new ArrayList<>(msgs.size());
            for (Msg x : msgs) arr.add(x.toMap());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(arr), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { System.err.println("bot_outbox.json 저장 실패: " + e); }
    }

    /** 큐에 적재. dedupKey가 이미 있으면 무시(-1 반환). 성공 시 새 id. */
    public synchronized long enqueue(String text, String dedupKey) {
        if (text == null || text.isBlank()) return -1;
        if (dedupKey != null && dedup.contains(dedupKey)) return -1;
        Msg x = new Msg();
        x.id = ++seq;
        x.text = text;
        x.dedupKey = dedupKey;
        x.createdAt = System.currentTimeMillis() / 1000;
        msgs.add(x);
        if (dedupKey != null) dedup.add(dedupKey);
        save();
        return x.id;
    }

    /** 미발송(또는 lease 만료) 메시지 최대 limit건 반환 + lease 갱신. */
    public synchronized List<Map<String, Object>> poll(int limit) {
        long now = System.currentTimeMillis() / 1000;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Msg x : msgs) {
            if (!"pending".equals(x.status)) continue;
            if (x.leasedAt != 0 && (now - x.leasedAt) < LEASE_SECONDS) continue;
            x.leasedAt = now;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", (double) x.id);
            m.put("text", x.text);
            out.add(m);
            if (out.size() >= limit) break;
        }
        if (!out.isEmpty()) save();
        return out;
    }

    /** 발송 완료 확정. */
    public synchronized int ack(List<Object> ids) {
        Set<Long> set = new HashSet<>();
        for (Object o : ids) { if (o instanceof Number) set.add(((Number) o).longValue()); }
        long now = System.currentTimeMillis() / 1000;
        int n = 0;
        for (Msg x : msgs) {
            if (set.contains(x.id) && "pending".equals(x.status)) { x.status = "sent"; x.sentAt = now; n++; }
        }
        // sent 보관 상한 정리: 오래된 sent부터 제거(dedupKey는 유지 → 재적재 방지).
        int sent = 0; for (Msg x : msgs) if ("sent".equals(x.status)) sent++;
        int toRemove = sent - KEEP_SENT;
        if (toRemove > 0) {
            java.util.Iterator<Msg> it = msgs.iterator();
            while (it.hasNext() && toRemove > 0) {
                if ("sent".equals(it.next().status)) { it.remove(); toRemove--; }
            }
        }
        if (n > 0) save();
        return n;
    }

    public synchronized Map<String, Object> health() {
        int pending = 0; for (Msg x : msgs) if ("pending".equals(x.status)) pending++;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pending", (double) pending);
        m.put("total", (double) msgs.size());
        return m;
    }
}
