package mekicalc.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 여러 비전 OCR 제공자를 순서대로 시도하는 폴백 체인. 앞 제공자가 실패(쿼터 초과·오류·타임아웃)하면
 * 다음 제공자로 넘어간다. 예: Groq(기본) → Mistral(2차). 하나라도 enabled면 이 체인도 enabled.
 */
public final class FallbackOcr implements VisionOcr {

    private final List<VisionOcr> providers;

    public FallbackOcr(List<VisionOcr> providers) {
        this.providers = providers;
    }

    @Override
    public boolean enabled() {
        for (VisionOcr p : providers) if (p != null && p.enabled()) return true;
        return false;
    }

    @Override
    public Map<String, Object> extract(String base64, String mimeType, String prompt, Object responseSchema)
            throws Exception {
        Exception last = null;
        boolean any = false;
        for (VisionOcr p : providers) {
            if (p == null || !p.enabled()) continue;
            any = true;
            try {
                return p.extract(base64, mimeType, prompt, responseSchema);
            } catch (Exception e) {
                last = e;   // 다음 제공자로 폴백
            }
        }
        if (!any) throw new IllegalStateException("OCR이 설정되지 않았습니다.");
        throw (last != null ? last : new IllegalStateException("모든 OCR 제공자가 실패했습니다."));
    }

    /** null·비활성 제공자를 제거해 유효한 제공자만 담은 체인을 만든다(전부 없으면 빈 체인=비활성). */
    public static FallbackOcr of(VisionOcr... candidates) {
        List<VisionOcr> list = new ArrayList<>();
        for (VisionOcr c : candidates) if (c != null && c.enabled()) list.add(c);
        return new FallbackOcr(list);
    }
}
