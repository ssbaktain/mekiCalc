package mekicalc.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mekicalc.json.Json;

/**
 * 넥슨 공지 신규 감지 시 mkk 공지판으로 제목+원문링크 푸시(요약 전, best-effort).
 *
 * <p>크롤러 틱을 막지 않도록 단일 데몬 스레드에서 fire-and-forget POST. mkk가 꺼져 있거나 실패해도
 * 크롤/요약/카톡 발송에는 영향 없음(공지 전달 실패는 다음 신규 공지에서 자연 회복, mkk는 url로 dedup).
 * 설정·활성 여부 = {@link MkkConfig}(data/mkk.json). 설정 없으면 완전 no-op.
 */
public final class MkkNoticePush {

    private final boolean enabled;
    private final String ingestUrl;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService exec =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mkk-notice-push");
                t.setDaemon(true);
                return t;
            });

    public MkkNoticePush(MkkConfig cfg) {
        this.enabled = cfg.enabled && !cfg.secret.isBlank() && !cfg.ingestUrl.isBlank();
        this.ingestUrl = cfg.ingestUrl;
        this.secret = cfg.secret;
        if (enabled) System.out.println(" mkk 공지 푸시: 활성 (" + ingestUrl + ")");
    }

    /**
     * 신규 공지 1건을 mkk로 전달. 비활성이면 즉시 반환. 네트워크/오류는 삼키고 로그만(크롤러 영향 0).
     * @param title 원문 제목(그대로)  @param url 넥슨 원문 URL  @param category 분류 라벨(공지사항/패치노트/…)
     * @param postedAtEpoch 게시 epoch sec(0이면 생략)
     */
    public void push(String title, String url, String category, long postedAtEpoch) {
        if (!enabled || title == null || url == null) return;
        exec.submit(() -> {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("title", title);
                body.put("url", url);
                body.put("category", category);
                body.put("postedAt", postedAtEpoch > 0 ? Instant.ofEpochSecond(postedAtEpoch).toString() : null);
                HttpRequest req = HttpRequest.newBuilder(URI.create(ingestUrl))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json")
                        .header("X-Internal-Secret", secret)
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() / 100 != 2) {
                    System.err.println("mkk 공지 푸시 실패 HTTP " + resp.statusCode() + " title=" + title);
                }
            } catch (Exception e) {
                System.err.println("mkk 공지 푸시 오류(무시): " + e.getMessage());
            }
        });
    }
}
