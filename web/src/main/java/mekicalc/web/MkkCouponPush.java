package mekicalc.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mekicalc.json.Json;

/**
 * 쿠폰 신규/갱신 시 mkk 쿠폰판으로 push (요약 후, best-effort).
 *
 * <p>★공지 push({@link MkkNoticePush}, 크롤 감지=요약 <b>전</b>)와 <b>타이밍이 다름</b> — 쿠폰은 AI 요약으로
 * 본문에서 추출된 뒤에야 push한다(크롤→요약→쿠폰정보 수집). mkk는 code로 dedup+갱신(재추출 반영).
 *
 * <p>크롤러 틱을 막지 않도록 단일 데몬 스레드 fire-and-forget POST. mkk가 꺼져 있거나 실패해도 크롤/요약/카톡
 * 발송에는 영향 없음. 설정·활성 여부 = {@link MkkConfig}(data/mkk.json, 공지와 공유). 설정 없으면 완전 no-op.
 */
public final class MkkCouponPush {

    private final boolean enabled;
    private final String ingestUrl;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService exec =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mkk-coupon-push");
                t.setDaemon(true);
                return t;
            });

    public MkkCouponPush(MkkConfig cfg) {
        this.enabled = cfg.enabled && !cfg.secret.isBlank() && !cfg.ingestUrl.isBlank();
        this.ingestUrl = cfg.couponIngestUrl();
        this.secret = cfg.secret;
        if (enabled) System.out.println(" mkk 쿠폰 푸시: 활성 (" + ingestUrl + ")");
    }

    /**
     * 쿠폰 1건을 mkk로 전달. 비활성이면 즉시 반환. 네트워크/오류는 삼키고 로그만(크롤러 영향 0).
     * @param code 쿠폰 코드  @param rewards 보상 목록('아이템명 수량')  @param expiresYmd 만료일 YYYY-MM-DD(없으면 null)
     * @param sourceTitle 출처 공지 제목  @param sourceUrl 출처 공지 URL  @param postedAtEpoch 게시 epoch sec(0=생략)
     */
    public void push(String code, List<String> rewards, String expiresYmd,
                     String sourceTitle, String sourceUrl, long postedAtEpoch) {
        if (!enabled || code == null || code.isBlank()) return;
        exec.submit(() -> {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", code);
                body.put("rewards", rewards == null ? List.of() : rewards);
                body.put("expiresAt", expiresYmd == null || expiresYmd.isBlank() ? null : expiresYmd);
                body.put("sourceTitle", sourceTitle);
                body.put("sourceUrl", sourceUrl);
                body.put("postedAt", postedAtEpoch > 0 ? Instant.ofEpochSecond(postedAtEpoch).toString() : null);
                HttpRequest req = HttpRequest.newBuilder(URI.create(ingestUrl))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json")
                        .header("X-Internal-Secret", secret)
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() / 100 != 2) {
                    System.err.println("mkk 쿠폰 푸시 실패 HTTP " + resp.statusCode() + " code=" + code);
                }
            } catch (Exception e) {
                System.err.println("mkk 쿠폰 푸시 오류(무시): " + e.getMessage());
            }
        });
    }
}
