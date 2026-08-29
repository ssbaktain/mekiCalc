package mekicalc.timeline.ir;

import java.util.Map;

/**
 * 동료(서포터) 스킬의 개별 효과 1건(supporter_manual.json 충실 파싱본).
 *
 * <p>규약=data/skills_calc/md/_clt2_SUPPORTER_TAGS.md. 단위: coeff·value=천분율(÷10=표시%, FinalDamage ÷1000=배율).
 * <p>동료 특유: {@link #target}=player(소환주)/companion(동료자기)/enemy(적) · {@link #cond}=조건(기본 상시 가정) ·
 * {@link #used}=false면 딜무관(회복·클렌즈 등) · {@link #action}=cleanse 등 비스탯 동작.
 *
 * @param role     deal / statUp / debuff / utility / cc
 * @param stat     대상 스탯명(clt 원본, 예 MaxDamageRatio·FinalDamage·CriticalPower·ATK_PCT)
 * @param value    statUp·debuff 값(‰)
 * @param coeff    deal 계수(‰)
 * @param hits     deal 타수(없으면 1)
 * @param targetCount deal 대상 수(자식 maxHitCount 통합값)
 * @param target   player / companion / enemy (없으면 문맥 기본)
 * @param durationMs 버프/도트 지속(ms)
 * @param tickMs   도트 틱 간격(ms)
 * @param form     "dot"이면 지속피해
 * @param cond     조건 문서(예 "hpRatio>=30") — 엔진 기본 상시 가정(적용)
 * @param used     false=딜무관(딜 계산 제외, 보존만)
 * @param action   비스탯 동작(예 "cleanse")
 * @param perHpRatio 현재HP 비례 스탯
 * @param assumeMax  현재HP 비례를 HP100% 최대로 계산(value가 이미 최대값)
 * @param maxStacks  램프업 스택 상한(periodic 스택 버프, 예 최종뎀 1초마다 +1.5% 최대 15중첩)
 */
public record SupporterEffectIR(
        String role,
        String stat,
        Double value,
        Double coeff,
        Integer hits,
        Integer targetCount,
        String target,
        Integer durationMs,
        Integer tickMs,
        String form,
        String cond,
        Boolean used,
        String action,
        Boolean perHpRatio,
        Boolean assumeMax,
        Integer maxStacks
) {
    static SupporterEffectIR from(Map<String, Object> m) {
        return new SupporterEffectIR(
                IRRead.str(m, "role"),
                IRRead.str(m, "stat"),
                IRRead.dbl(m, "value"),
                IRRead.dbl(m, "coeff"),
                IRRead.intg(m, "hits"),
                IRRead.intg(m, "targetCount"),
                IRRead.str(m, "target"),
                IRRead.intg(m, "durationMs"),
                IRRead.intg(m, "tickMs"),
                IRRead.str(m, "form"),
                IRRead.str(m, "cond"),
                IRRead.bool(m, "used"),
                IRRead.str(m, "action"),
                IRRead.bool(m, "perHpRatio"),
                IRRead.bool(m, "assumeMax"),
                IRRead.intg(m, "maxStacks")
        );
    }

    /** 딜 무관(used:false)이면 true — 딜 계산에서 제외. */
    public boolean unused()      { return Boolean.FALSE.equals(used); }
    public boolean isDeal()      { return "deal".equals(role) && !unused(); }
    public boolean isDot()       { return "dot".equals(form); }
    public int     hitsOr1()     { return hits == null ? 1 : hits; }
    public int     targetsOr1()  { return targetCount == null ? 1 : targetCount; }
    /** 대상=플레이어(소환주)인가. target 미지정 시 문맥 기본은 호출부에서 판단. "both"=플레이어+동료 양쪽. */
    public boolean toPlayer()    { return "player".equals(target) || "both".equals(target); }
    public boolean toCompanion() { return "companion".equals(target) || "both".equals(target); }
    public boolean toEnemy()     { return "enemy".equals(target); }
    public int     maxStacksOr1(){ return maxStacks == null ? 1 : maxStacks; }

    /**
     * 컨텐츠 조건(boss/nonBoss) 게이팅 — 이 effect가 현재 컨텐츠에서 활성인가.
     * ★정책(_conventions.cond): boss/nonBoss만 실제 boss 플래그로 게이팅, 나머지 cond(hpRatio 등)는 상시 충족 가정.
     */
    public boolean condActive(boolean boss) {
        if ("boss".equals(cond)) return boss;
        if ("nonBoss".equals(cond)) return !boss;
        return true;
    }
}
