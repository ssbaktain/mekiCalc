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
 * Groq(OpenAI 호환 chat/completions)의 비전 모델로 이미지에서 구조화 JSON을 뽑는다. JDK HttpClient만 사용(무의존).
 *
 * <p>키/모델은 {@link OcrConfig}(data/ocr.json). Gemini보다 무료 일일 한도가 높아 기본 제공자로 쓴다.
 * response_format=json_object + 프롬프트로 우리 스키마 형태를 유도하고, 검증은 호출부(CalcService)가 한다.
 */
public final class GroqOcr implements VisionOcr {

    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";

    private final OcrConfig cfg;
    private final HttpClient http = HttpClient.newHttpClient();

    public GroqOcr(OcrConfig cfg) { this.cfg = cfg; }

    @Override public boolean enabled() { return cfg.groqEnabled; }

    @Override
    public Map<String, Object> extract(String base64, String mimeType, String prompt, Object responseSchema)
            throws IOException, InterruptedException {
        String mt = (mimeType == null || mimeType.isEmpty()) ? "image/jpeg" : mimeType;
        String dataUrl = "data:" + mt + ";base64," + base64;

        String fullPrompt = prompt + (responseSchema != null
                ? "\n\n반드시 다음 JSON 스키마 형태에 맞춰 출력하세요:\n" + Json.write(responseSchema) : "");

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text"); textPart.put("text", fullPrompt);
        Map<String, Object> imgUrl = new LinkedHashMap<>(); imgUrl.put("url", dataUrl);
        Map<String, Object> imgPart = new LinkedHashMap<>();
        imgPart.put("type", "image_url"); imgPart.put("image_url", imgUrl);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user"); msg.put("content", List.of(textPart, imgPart));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.groqModel);
        body.put("messages", List.of(msg));
        body.put("temperature", 0);
        Map<String, Object> rf = new LinkedHashMap<>(); rf.put("type", "json_object");
        body.put("response_format", rf);

        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.groqApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();

        int[] backoffMs = {600, 1500, 3000};
        HttpResponse<String> resp;
        for (int attempt = 0; ; attempt++) {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int sc = resp.statusCode();
            if (sc / 100 == 2) break;
            if ((sc == 503 || sc == 500) && attempt < backoffMs.length) { Thread.sleep(backoffMs[attempt]); continue; }
            if (sc == 429) throw new IOException("Groq 무료 사용량(쿼터)을 초과했습니다.");   // 호출부가 폴백 판단
            if (sc == 503) throw new IOException("Groq 모델이 잠시 혼잡합니다.");
            throw new IOException("Groq API 오류(" + sc + "): " + clip(resp.body()));
        }
        String text = firstContent(Json.asMap(Json.parse(resp.body())));
        if (text == null || text.isBlank()) throw new IOException("인식 결과가 비어 있습니다.");
        Object parsed = Json.parse(text);
        if (!(parsed instanceof Map)) throw new IOException("인식 결과 형식 오류");
        return Json.asMap(parsed);
    }

    /** choices[0].message.content(방어적). 없으면 null. */
    private static String firstContent(Map<String, Object> root) {
        Object choices = root.get("choices");
        if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) return null;
        Object c0 = ((List<?>) choices).get(0);
        if (!(c0 instanceof Map)) return null;
        Object message = ((Map<?, ?>) c0).get("message");
        if (!(message instanceof Map)) return null;
        Object content = ((Map<?, ?>) message).get("content");
        return content == null ? null : content.toString();
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
