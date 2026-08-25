package mekicalc.timeline.ir;

import java.util.Map;

/**
 * 스킬/마스터리의 개별 효과 1건(충실 파싱본, raw 값 보존).
 *
 * <p>role별로 쓰는 필드가 달라 전부 nullable로 담는다. 해석(단위 나눗셈·성장 반영)은 스케줄러 단계에서.
 * <p>단위 관습: coeff·value는 1000분율(천분율). 예: coeff 2900 = 2.90배, value 100 = 10%.
 *
 * @param role     statUp / deal / debuff / skillBoost / cc
 * @param stat     statUp·debuff 대상 스탯명(원본 문자열)
 * @param value    statUp·debuff·skillBoost 값(‰)
 * @param coeff    deal 계수(‰)
 * @param hits     deal 타수(없으면 1)
 * @param targetCount deal 최대 대상 수(마리수 별개)
 * @param growthSlot  성장 슬롯(12/21/22/23) — 없으면 성장 없음
 * @param preReflected 선반영(true=프리셋에 이미 녹음) 여부
 * @param durationMs   지속시간(버프/도트/디버프)
 * @param tickMs       도트 틱 간격
 * @param form         "dot"이면 지속피해
 * @param mode         "stun" 등 cc 종류(공퍼 post는 폐지 — 스킬 공%는 기본이 곱)
 * @param unit         "flat"(정수 그대로 더함=합)·"pct"(천분율 비율=곱). 시간계열·공/곱 예외 판별
 * @param target       debuff 대상("enemy") / skillBoost 대상 스킬 idx는 {@link #targetSkill}
 * @param type         skillBoost 종류("finalDmg"/"targetCount")
 * @param targetSkill  skillBoost가 가리키는 스킬 idx(문자열→int)
 */
public record EffectIR(
        String role,
        String stat,
        Double value,
        Double coeff,
        Integer hits,
        Integer targetCount,
        Integer growthSlot,
        Boolean preReflected,
        Integer durationMs,
        Integer tickMs,
        String form,
        String mode,
        String unit,
        String target,
        String type,
        Integer targetSkill
) {
    static EffectIR from(Map<String, Object> m) {
        // skillBoost의 target은 스킬 idx(문자열 숫자), debuff의 target은 "enemy"(문자열)
        String tgtRaw = IRRead.str(m, "target");
        Integer tgtSkill = null;
        if (tgtRaw != null) {
            try { tgtSkill = Integer.parseInt(tgtRaw.trim()); } catch (NumberFormatException ignore) { }
        }
        return new EffectIR(
                IRRead.str(m, "role"),
                IRRead.str(m, "stat"),
                IRRead.dbl(m, "value"),
                IRRead.dbl(m, "coeff"),
                IRRead.intg(m, "hits"),
                IRRead.intg(m, "targetCount"),
                IRRead.intg(m, "growthSlot"),
                IRRead.bool(m, "preReflected"),
                IRRead.intg(m, "durationMs"),
                IRRead.intg(m, "tickMs"),
                IRRead.str(m, "form"),
                IRRead.str(m, "mode"),
                IRRead.str(m, "unit"),
                tgtSkill == null ? tgtRaw : null,   // enemy 등 비숫자 target만 유지
                IRRead.str(m, "type"),
                tgtSkill
        );
    }

    public boolean isDot() { return "dot".equals(form); }
    /** 추가피해(강화스킬·마스터리) — 공격/타격 시 프록의 '트리거 대상'이 아님(딜은 하되 다른 프록을 안 건드림). */
    public boolean isAdditional() { return "additional".equals(form); }
    public int hitsOr1()   { return hits == null ? 1 : hits; }
}
