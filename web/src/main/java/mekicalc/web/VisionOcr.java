package mekicalc.web;

import java.util.Map;

/**
 * 이미지에서 구조화 JSON을 뽑는 비전 OCR 제공자 추상화. 장비 비교 OCR이 이 인터페이스만 의존해
 * 제공자(Gemini/Groq 등)를 갈아끼운다. 구현체는 무의존(JDK HttpClient) 또는 로컬 실행.
 */
public interface VisionOcr {

    /** 키/설정이 갖춰져 사용 가능한지. false면 이 제공자는 건너뛴다. */
    boolean enabled();

    /**
     * 이미지(base64) + 프롬프트(+선택 스키마)로 질의해 모델이 반환한 JSON을 Map으로 돌려준다.
     * @throws Exception API 오류·빈 응답·타임아웃 등(호출부가 폴백 판단)
     */
    Map<String, Object> extract(String base64, String mimeType, String prompt, Object responseSchema) throws Exception;
}
