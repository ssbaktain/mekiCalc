package mekicalc.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import mekicalc.json.Json;

/**
 * Gemini 비전 OCR 설정(시크릿). 파일: data/gemini.json (개인/비밀 — gitignore·빌드 제외).
 *
 * <pre>
 * {
 *   "apiKey": "AIza...",              // Google AI Studio 무료 키(카드 불필요)
 *   "model": "gemini-2.5-flash"       // (선택) 미지정 시 기본 모델
 * }
 * </pre>
 *
 * <p>파일이 없거나 키가 비면 OCR 기능 OFF({@link #enabled}=false) — 빌드/개발 환경 안전(기존 기능 영향 없음).
 */
public final class GeminiConfig {

    private static final String DEFAULT_MODEL = "gemini-2.5-flash";

    public final boolean enabled;
    public final String apiKey;
    public final String model;

    private GeminiConfig(boolean enabled, String apiKey, String model) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
    }

    private static GeminiConfig disabled() { return new GeminiConfig(false, "", DEFAULT_MODEL); }

    public static GeminiConfig load(Path dataDir) {
        Path f = dataDir.resolve("gemini.json");
        if (!Files.exists(f)) return disabled();
        try {
            Map<String, Object> m = Json.asMap(Json.parse(Files.readString(f)));
            String key = s(m, "apiKey");
            if (key.isEmpty()) return disabled();
            String model = s(m, "model");
            return new GeminiConfig(true, key, model.isEmpty() ? DEFAULT_MODEL : model);
        } catch (Exception e) {
            return disabled();
        }
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }
}
