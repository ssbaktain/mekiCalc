package mekicalc.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * Gemini 비전 모델로 이미지에서 구조화된 JSON을 뽑는다(장비 비교 OCR). JDK 내장 {@link HttpClient}만 사용(무의존).
 *
 * <p>키/모델은 {@link GeminiConfig}(data/gemini.json). 키가 없으면 {@link #enabled()}=false로 기능 OFF.
 * responseMimeType=application/json + responseSchema로 모델이 우리 스키마대로만 답하게 강제한다.
 */
public final class GeminiOcr implements VisionOcr {

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private final GeminiConfig cfg;
    private final HttpClient http = HttpClient.newHttpClient();

    public GeminiOcr(GeminiConfig cfg) { this.cfg = cfg; }

    public boolean enabled() { return cfg.enabled; }

    /**
     * 이미지(base64) + 프롬프트 + responseSchema → 모델이 반환한 JSON을 Map으로 파싱해 돌려준다.
     * @throws IOException API 오류·빈 응답·타임아웃 등
     */
    public Map<String, Object> extract(String base64, String mimeType, String prompt, Object responseSchema)
            throws IOException, InterruptedException {
        Map<String, Object> inlineData = new LinkedHashMap<>();
        inlineData.put("mimeType", mimeType == null || mimeType.isEmpty() ? "image/png" : mimeType);
        inlineData.put("data", base64);
        Map<String, Object> imgPart = new LinkedHashMap<>();
        imgPart.put("inlineData", inlineData);
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", prompt);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("parts", List.of(imgPart, textPart));

        Map<String, Object> genCfg = new LinkedHashMap<>();
        genCfg.put("temperature", 0);
        genCfg.put("responseMimeType", "application/json");
        if (responseSchema != null) genCfg.put("responseSchema", responseSchema);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(content));
        body.put("generationConfig", genCfg);

        HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(ENDPOINT, cfg.model, cfg.apiKey)))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();

        // 503(과부하)·500은 일시적이라 백오프 재시도. 429(쿼터 초과)는 재시도해도 쿼터만 더 소모하므로 즉시 안내.
        int[] backoffMs = {600, 1500, 3000};
        HttpResponse<String> resp;
        for (int attempt = 0; ; attempt++) {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int sc = resp.statusCode();
            if (sc / 100 == 2) break;
            if ((sc == 503 || sc == 500) && attempt < backoffMs.length) {
                Thread.sleep(backoffMs[attempt]);
                continue;
            }
            if (sc == 429) throw new IOException("무료 사용량(쿼터)을 초과했습니다. 잠시 후(분당 한도는 약 1분, 일일 한도는 리셋 후) 다시 시도해 주세요.");
            if (sc == 503) throw new IOException("Gemini 모델이 잠시 혼잡합니다. 잠시 후 다시 시도해 주세요.");
            throw new IOException("Gemini API 오류(" + sc + "): " + clip(resp.body()));
        }
        String text = firstText(Json.asMap(Json.parse(resp.body())));
        if (text == null || text.isBlank()) throw new IOException("인식 결과가 비어 있습니다.");
        Object parsed = Json.parse(text);
        if (!(parsed instanceof Map)) throw new IOException("인식 결과 형식 오류");
        return Json.asMap(parsed);
    }

    /** candidates[0].content.parts[*].text 이어붙이기(방어적). 없으면 null. */
    private static String firstText(Map<String, Object> root) {
        Object cands = root.get("candidates");
        if (!(cands instanceof List) || ((List<?>) cands).isEmpty()) return null;
        Object c0 = ((List<?>) cands).get(0);
        if (!(c0 instanceof Map)) return null;
        Object contentObj = ((Map<?, ?>) c0).get("content");
        if (!(contentObj instanceof Map)) return null;
        Object parts = ((Map<?, ?>) contentObj).get("parts");
        if (!(parts instanceof List)) return null;
        StringBuilder sb = new StringBuilder();
        for (Object p : (List<?>) parts) {
            if (p instanceof Map) {
                Object t = ((Map<?, ?>) p).get("text");
                if (t != null) sb.append(t);
            }
        }
        return sb.toString();
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
