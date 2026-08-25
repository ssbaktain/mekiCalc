package mekicalc.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import mekicalc.json.Json;

/**
 * 디스코드 발송기. {@link BotOutbox}를 카톡과 <b>독립된 소비자</b>로 폴링해, 전체 공지·종료 임박 리마인더를
 * 등록된 웹훅 채널들에 발송한다. 크롤러/카톡 경로는 건드리지 않는다 — outbox가 이미 모든 발신의 단일 관문이라,
 * 여기서 {@code pollForDiscord → 발송 → markDiscordSent}만 하면 카톡과 동일한 내용이 디스코드에도 나간다.
 *
 * <p><b>발송 정책</b>
 * <ul>
 *   <li>대상: {@code targetRooms==null}(전체 공지·리마인더)만. 개인 공지/테스트는 디스코드 제외(outbox가 걸러 줌).</li>
 *   <li>웹훅 0개: 보낼 곳이 없으므로 소진(드롭). 최초 도입 시 '과거 공지 폭탄'을 자연히 막는다
 *       (웹훅 등록 이전 공지는 안 보냄 — 카톡이 notify 방 없으면 안 가는 것과 같은 철학).</li>
 *   <li>웹훅 ≥1개: 각 메시지를 enabled 웹훅 전부에 발송. <b>최소 하나 성공</b>하면 확정(중복 방지).
 *       전부 실패(네트워크 등)면 확정하지 않아 다음 틱에 재시도 → 유실 방지.</li>
 *   <li>2000자 제한: 넘으면 줄 경계로 분할해 순차 발송.</li>
 * </ul>
 */
public final class DiscordNotifier {

    private static final int POLL_BATCH = 20;       // 틱당 처리 메시지 상한
    private static final int MAX_LEN = 1900;         // 디스코드 content 2000자 제한 여유
    private static final String USERNAME = "메키칼크 공지";
    private static final long RATE_GAP_MS = 350;     // 웹훅 rate limit 완화용 발송 간 간격

    private final BotOutbox outbox;
    private final DiscordStore store;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "discord-notifier");
                t.setDaemon(true);
                return t;
            });

    public DiscordNotifier(BotOutbox outbox, DiscordStore store) {
        this.outbox = outbox;
        this.store = store;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::safeTick, 10, 30, TimeUnit.SECONDS);
        System.out.println(" 디스코드 발송기: 30초 폴링 시작");
    }

    public void stop() { scheduler.shutdownNow(); }

    private void safeTick() {
        try { tick(); }
        catch (Throwable t) { System.err.println("디스코드 발송 틱 오류(다음 주기 재시도): " + t); }
    }

    private void tick() {
        List<Map<String, Object>> pending = outbox.pollForDiscord(POLL_BATCH);
        if (pending.isEmpty()) return;

        List<DiscordStore.Hook> hooks = store.enabledHooks();
        if (hooks.isEmpty()) {
            // 보낼 웹훅이 없다 → 소진(드롭). 웹훅 등록 전 공지는 소급 발송하지 않는다.
            List<Object> ids = new ArrayList<>();
            for (Map<String, Object> m : pending) ids.add(m.get("id"));
            outbox.markDiscordSent(ids);
            return;
        }

        List<Object> done = new ArrayList<>();
        for (Map<String, Object> m : pending) {
            String text = (String) m.get("text");
            if (text == null || text.isBlank()) { done.add(m.get("id")); continue; }
            int ok = 0;
            for (DiscordStore.Hook h : hooks) {
                try {
                    sendAll(h.url, text);
                    store.markOk(h.id);
                    ok++;
                } catch (Exception ex) {
                    store.markError(h.id, ex.getMessage());
                    System.err.println("디스코드 발송 실패(hook=" + h.id + ", " + DiscordStore.mask(h.url) + "): " + ex.getMessage());
                }
            }
            if (ok > 0) done.add(m.get("id"));   // 최소 하나 성공 → 소진. 전부 실패면 다음 틱 재시도.
        }
        if (!done.isEmpty()) {
            int n = outbox.markDiscordSent(done);
            if (n > 0) System.out.println("[디스코드 발송] " + n + "건 x " + hooks.size() + "채널");
        }
    }

    /** 관리자 테스트 발송(지정 웹훅에 즉시 1건). 성공 시 무반환, 실패 시 예외. store 상태도 갱신. */
    public void sendTest(DiscordStore.Hook hook, String text) throws Exception {
        try {
            sendAll(hook.url, text);
            store.markOk(hook.id);
        } catch (Exception ex) {
            store.markError(hook.id, ex.getMessage());
            throw ex;
        }
    }

    /** 한 웹훅에 메시지 발송(2000자 초과 시 분할 순차). 하나라도 실패하면 예외 → 그 웹훅 실패로 처리. */
    private void sendAll(String url, String text) throws Exception {
        List<String> chunks = split(text);
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) Thread.sleep(RATE_GAP_MS);   // 연속 발송 rate limit 완화
            post(url, chunks.get(i));
        }
    }

    /** 웹훅 POST. 2xx면 성공, 아니면 예외(상태코드·응답 일부 포함). */
    private void post(String url, String content) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("username", USERNAME);
        byte[] body = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int sc = resp.statusCode();
        if (sc / 100 != 2) {
            String b = resp.body();
            if (b != null && b.length() > 200) b = b.substring(0, 200);
            throw new Exception("HTTP " + sc + (b == null || b.isBlank() ? "" : " " + b));
        }
    }

    /** 텍스트를 디스코드 한도 이하 청크로 분할(줄 경계 우선, 한 줄이 너무 길면 강제 컷). */
    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text.length() <= MAX_LEN) { out.add(text); return out; }
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            while (line.length() > MAX_LEN) {   // 초장문 한 줄: 강제 분할
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                out.add(line.substring(0, MAX_LEN));
                line = line.substring(MAX_LEN);
            }
            int add = line.length() + (cur.length() > 0 ? 1 : 0);
            if (cur.length() + add > MAX_LEN) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append('\n');
            cur.append(line);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
