package mekicalc.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 장비 비교 OCR 설정(시크릿). 파일: data/ocr.json (개인/비밀 — gitignore·빌드 제외).
 *
 * <pre>
 * {
 *   "groqApiKey": "gsk_...",                                    // console.groq.com 무료 키(기본 제공자)
 *   "groqModel": "meta-llama/llama-4-scout-17b-16e-instruct",   // (선택) Groq 비전 모델
 *   "mistralApiKey": "...",                                     // console.mistral.ai 무료 키(2차 폴백)
 *   "mistralModel": "pixtral-12b-2409",                         // (선택) Mistral 비전 모델
 *   "tesseractCmd": "C:/.../tesseract.exe"                      // (선택, 미사용) 포터블 tesseract 경로
 * }
 * </pre>
 *
 * <p>키가 있는 제공자만 켜진다. Groq→Mistral 순으로 폴백. 둘 다 없으면 OCR 기능 자체가 OFF.
 */
public final class OcrConfig {

    private static final String DEFAULT_GROQ_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct";
    private static final String DEFAULT_MISTRAL_MODEL = "mistral-medium-latest";

    public final boolean groqEnabled;
    public final String groqApiKey;
    public final String groqModel;
    public final boolean mistralEnabled;
    public final String mistralApiKey;
    public final String mistralModel;
    /** 폴백 tesseract 실행 경로(없으면 null → 폴백 미사용). 현재 미사용. */
    public final String tesseractCmd;

    private OcrConfig(boolean groqEnabled, String groqApiKey, String groqModel,
                      boolean mistralEnabled, String mistralApiKey, String mistralModel,
                      String tesseractCmd) {
        this.groqEnabled = groqEnabled;
        this.groqApiKey = groqApiKey;
        this.groqModel = groqModel;
        this.mistralEnabled = mistralEnabled;
        this.mistralApiKey = mistralApiKey;
        this.mistralModel = mistralModel;
        this.tesseractCmd = tesseractCmd;
    }

    public static OcrConfig load(Path dataDir) {
        Path f = dataDir.resolve("ocr.json");
        if (!Files.exists(f)) {
            return new OcrConfig(false, "", DEFAULT_GROQ_MODEL, false, "", DEFAULT_MISTRAL_MODEL, null);
        }
        try {
            Map<String, Object> m = Json.asMap(Json.parse(Files.readString(f)));
            String gKey = s(m, "groqApiKey");
            String gModel = s(m, "groqModel");
            String mKey = s(m, "mistralApiKey");
            String mModel = s(m, "mistralModel");
            String tess = s(m, "tesseractCmd");
            return new OcrConfig(
                    !gKey.isEmpty(), gKey, gModel.isEmpty() ? DEFAULT_GROQ_MODEL : gModel,
                    !mKey.isEmpty(), mKey, mModel.isEmpty() ? DEFAULT_MISTRAL_MODEL : mModel,
                    tess.isEmpty() ? null : tess);
        } catch (Exception e) {
            return new OcrConfig(false, "", DEFAULT_GROQ_MODEL, false, "", DEFAULT_MISTRAL_MODEL, null);
        }
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }
}
