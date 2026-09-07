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
 * 로컬 PaddleOCR 사이드카(무료·무제한·오프라인) 제공자. 같은 미니PC에서 파이썬 FastAPI 사이드카가
 * localhost로 뜨고, 이미지(base64)를 보내면 {leftPart,rightPart,left,right} 원문 JSON을 돌려준다.
 * 이 원문을 그대로 반환하면 {@link CalcService}가 statId 매핑·주/부 분리·부위 정규화를 수행한다.
 *
 * <p>클라우드 제공자보다 앞에 두어 1차로 쓰고, 사이드카가 죽어 있으면(연결 거부·타임아웃) 즉시
 * 예외로 폴백된다. prompt/schema는 무시(파서가 고정). JDK HttpClient만 사용(무의존).
 */
public final class LocalOcr implements VisionOcr {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public LocalOcr(OcrConfig cfg) {
        this.baseUrl = cfg.localOcrUrl == null ? "" : cfg.localOcrUrl.trim();
    }

    @Override public boolean enabled() { return !baseUrl.isEmpty(); }

    @Override
    public Map<String, Object> extract(String base64, String mimeType, String prompt, Object responseSchema)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("image", base64);
        body.put("mimeType", (mimeType == null || mimeType.isEmpty()) ? "image/png" : mimeType);

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/ocr"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int sc = resp.statusCode();
        if (sc / 100 != 2) throw new IOException("로컬 OCR 오류(" + sc + "): " + clip(resp.body()));
        Object parsed = Json.parse(resp.body());
        if (!(parsed instanceof Map)) throw new IOException("로컬 OCR 결과 형식 오류");
        return Json.asMap(parsed);
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
