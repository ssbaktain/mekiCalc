package mekicalc.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 쿠폰 사이드카(헤드리스 Chromium) 호출 클라이언트. 같은 미니PC에서 파이썬 FastAPI 사이드카가
 * localhost로 뜨고, 넥슨 쿠폰 사이트를 실제 브라우저로 열어 Cloudflare를 통과한 뒤 조회/사용 API를
 * 대신 호출한다(서버 직접 호출은 Cloudflare 403). JDK HttpClient만 사용(무의존).
 *
 * <p>{@code /validate} = 회원코드 유효성(조회), {@code /redeem} = 실제 쿠폰 사용.
 * 응답은 {valid|ok, code, message}. 사이드카가 죽어 있으면 IOException으로 전파된다.
 */
public final class CouponSidecarClient {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public CouponSidecarClient(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public boolean enabled() { return !baseUrl.isEmpty(); }

    /** 회원코드 검증(조회). probePin=이미 존재하는 쿠폰코드. 반환 {valid, code, message}. */
    public Map<String, Object> validate(String npaCode, String probePin) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("npaCode", npaCode);
        body.put("probePin", probePin);
        return post("/validate", body);
    }

    /** 실제 쿠폰 사용(지급). 반환 {ok, code, message}. */
    public Map<String, Object> redeem(String npaCode, String pin) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("npaCode", npaCode);
        body.put("pin", pin);
        return post("/redeem", body);
    }

    /** 사이드카 생존/세션 상태. 실패 시 IOException. */
    public Map<String, Object> health() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new IOException("쿠폰 사이드카 health 오류(" + resp.statusCode() + ")");
        return Json.asMap(Json.parse(resp.body()));
    }

    private Map<String, Object> post(String path, Map<String, Object> body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(90))   // 내부에서 브라우저 네비게이션/재시도까지 포함
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int sc = resp.statusCode();
        if (sc / 100 != 2) throw new IOException("쿠폰 사이드카 오류(" + sc + "): " + clip(resp.body()));
        Object parsed = Json.parse(resp.body());
        if (!(parsed instanceof Map)) throw new IOException("쿠폰 사이드카 결과 형식 오류");
        return Json.asMap(parsed);
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
