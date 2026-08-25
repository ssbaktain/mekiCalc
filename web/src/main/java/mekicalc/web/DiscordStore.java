package mekicalc.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 디스코드 웹훅 레지스트리(파일: data/discord_webhooks.json). 관리자가 디스코드 채널에서 발급한
 * 웹훅 URL을 등록하면, {@link DiscordNotifier}가 신규 공지·종료 임박 리마인더를 그 채널들에 발송한다.
 *
 * <p>카톡의 {@link RoomStore}와 같은 역할이지만 디스코드는 웹훅 URL 하나로 채널 발송이 끝나므로
 * 방 자동인식·권한 매트릭스가 필요 없다(등록 = 발송 동의). {@code enabled}로 개별 on/off만 둔다.
 * 단일 머신이라 파일+synchronized로 단순하게.
 */
public final class DiscordStore {

    public static final class Hook {
        public long id;
        public String url;        // 디스코드 채널 웹훅 URL
        public String label;      // 관리자 표시명(서버/채널 이름 등, 선택)
        public boolean enabled = true;
        public long addedAt;      // 등록 epoch sec
        public long lastOkAt;     // 마지막 발송 성공 epoch sec
        public String lastError;  // 마지막 실패 사유(성공하면 null로 지움)

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", (double) id);
            m.put("url", url);
            m.put("label", label);
            m.put("enabled", enabled);
            m.put("addedAt", (double) addedAt);
            m.put("lastOkAt", (double) lastOkAt);
            m.put("lastError", lastError);
            return m;
        }
        static Hook fromMap(Map<String, Object> m) {
            Hook h = new Hook();
            h.id = (long) Json.num(m, "id", 0);
            h.url = str(m, "url");
            h.label = str(m, "label");
            h.enabled = m.get("enabled") == null || Boolean.TRUE.equals(m.get("enabled"));   // 기본 on
            h.addedAt = (long) Json.num(m, "addedAt", 0);
            h.lastOkAt = (long) Json.num(m, "lastOkAt", 0);
            h.lastError = str(m, "lastError");
            return h;
        }
        /** URL은 관리 화면에 그대로 노출하지 않는다(토큰 노출 방지). 뒤 6자만 마스킹 표기. */
        public Map<String, Object> toSafeMap() {
            Map<String, Object> m = toMap();
            m.put("url", mask(url));
            return m;
        }
        private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : v.toString(); }
    }

    private final Path file;
    private final Map<Long, Hook> hooks = new LinkedHashMap<>();   // id → Hook (등록 순서 유지)
    private long seq = 0;

    public DiscordStore(Path dataDir) {
        this.file = dataDir.resolve("discord_webhooks.json");
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            for (Object o : Json.asList(Json.parse(Files.readString(file, StandardCharsets.UTF_8)))) {
                Hook h = Hook.fromMap(Json.asMap(o));
                if (h.url == null || h.url.isBlank()) continue;
                hooks.put(h.id, h);
                if (h.id > seq) seq = h.id;
            }
        } catch (Exception e) { System.err.println("discord_webhooks.json 읽기 실패(무시): " + e); }
    }

    private synchronized void save() {
        try {
            List<Object> arr = new ArrayList<>(hooks.size());
            for (Hook h : hooks.values()) arr.add(h.toMap());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(arr), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { System.err.println("discord_webhooks.json 저장 실패: " + e); }
    }

    /** 전체 웹훅 목록(URL 마스킹된 안전본 — 관리 화면용). */
    public synchronized List<Map<String, Object>> listSafe() {
        List<Map<String, Object>> out = new ArrayList<>(hooks.size());
        for (Hook h : hooks.values()) out.add(h.toSafeMap());
        return out;
    }

    /** 발송 대상: enabled 웹훅 스냅샷(발송 중 변경과 무관하게 복사본 반환). */
    public synchronized List<Hook> enabledHooks() {
        List<Hook> out = new ArrayList<>();
        for (Hook h : hooks.values()) if (h.enabled && h.url != null && !h.url.isBlank()) out.add(h);
        return out;
    }

    public synchronized Hook get(long id) { return hooks.get(id); }

    /** 등록(디스코드 웹훅 URL 형식만 최소 검증). 성공 시 새 Hook. */
    public synchronized Hook add(String url, String label) {
        if (!isWebhookUrl(url)) throw new IllegalArgumentException("올바른 디스코드 웹훅 URL이 아닙니다.");
        Hook h = new Hook();
        h.id = ++seq;
        h.url = url.trim();
        h.label = (label == null || label.isBlank()) ? null : label.trim();
        h.enabled = true;
        h.addedAt = System.currentTimeMillis() / 1000;
        hooks.put(h.id, h);
        save();
        return h;
    }

    /** 라벨/활성 수정(label=null이면 이름 미변경, 빈 문자열이면 지움). 존재하지 않으면 null. */
    public synchronized Hook update(long id, String label, boolean enabled) {
        Hook h = hooks.get(id);
        if (h == null) return null;
        if (label != null) h.label = label.isBlank() ? null : label.trim();
        h.enabled = enabled;
        save();
        return h;
    }

    /** 삭제. 삭제됐으면 true. */
    public synchronized boolean remove(long id) {
        boolean removed = hooks.remove(id) != null;
        if (removed) save();
        return removed;
    }

    /** 발송 성공 기록(마지막 성공 시각 갱신 + 오류 지움). */
    public synchronized void markOk(long id) {
        Hook h = hooks.get(id);
        if (h == null) return;
        h.lastOkAt = System.currentTimeMillis() / 1000;
        h.lastError = null;
        save();
    }

    /** 발송 실패 기록(사유 저장 — 관리 화면에서 원인 파악용). */
    public synchronized void markError(long id, String reason) {
        Hook h = hooks.get(id);
        if (h == null) return;
        h.lastError = reason;
        save();
    }

    /** 디스코드 웹훅 URL 형식 검증(discord.com/discordapp.com 의 /api/webhooks/ 경로). */
    public static boolean isWebhookUrl(String url) {
        if (url == null) return false;
        String u = url.trim();
        return u.matches("^https://(?:\\w+\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/\\d+/[\\w-]+$");
    }

    /** URL 토큰 마스킹: 웹훅 id는 남기고 토큰은 뒤 4자만 노출(…abcd). */
    static String mask(String url) {
        if (url == null) return null;
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) return "***";
        String head = url.substring(0, slash + 1);   // …/webhooks/{id}/
        String tok = url.substring(slash + 1);
        String tail = tok.length() <= 4 ? tok : tok.substring(tok.length() - 4);
        return head + "…" + tail;
    }
}
