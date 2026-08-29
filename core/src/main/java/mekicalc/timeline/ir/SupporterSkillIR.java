package mekicalc.timeline.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 동료 스킬 1개(supporter_manual.json 충실 파싱본).
 *
 * @param idx        스킬 idx(문자열 숫자)
 * @param name       이름
 * @param role       용도 태그(deal/buff/utility/debuff)
 * @param type       basicAttack 계열=active / passive / enter(소환시전) / active
 * @param trigger    basicAttack / active / onStart / periodic / onAttack / enter
 * @param cooldownMs 쿨다운(ms) — active만, 없으면 null
 * @param openLevel  해금 동료레벨(≤동료레벨이면 사용) — 없으면 1
 * @param chance     onAttack proc 발동 확률(‰)
 * @param triggerBy  onAttack 편승 소스("companionAttack"/"companionBasicAttack")
 * @param periodMs   periodic 주기(ms)
 * @param effects    효과 목록
 */
public record SupporterSkillIR(
        int idx,
        String name,
        List<String> role,
        String type,
        String trigger,
        Integer cooldownMs,
        int openLevel,
        Integer chance,
        String triggerBy,
        Integer periodMs,
        List<SupporterEffectIR> effects
) {
    static SupporterSkillIR from(Map<String, Object> m) {
        List<SupporterEffectIR> effs = new ArrayList<>();
        for (Map<String, Object> e : IRRead.mapList(m, "effects")) effs.add(SupporterEffectIR.from(e));
        Integer open = IRRead.intg(m, "openLevel");
        return new SupporterSkillIR(
                IRRead.strInt(m, "idx"),
                IRRead.str(m, "name"),
                IRRead.strList(m, "role"),
                IRRead.str(m, "type"),
                IRRead.str(m, "trigger"),
                IRRead.intg(m, "cooldown"),
                open == null ? 1 : open,
                IRRead.intg(m, "chance"),
                IRRead.str(m, "triggerBy"),
                IRRead.intg(m, "periodMs"),
                List.copyOf(effs)
        );
    }

    public boolean isBasicAttack() { return "basicAttack".equals(trigger); }
    public boolean isActive()      { return "active".equals(trigger); }
    public boolean isEnter()       { return "enter".equals(trigger); }
    public boolean isPeriodic()    { return "periodic".equals(trigger); }
    public boolean isOnAttack()    { return "onAttack".equals(trigger); }

    /** 딜 효과(used:false 제외)가 하나라도 있으면 true. */
    public boolean hasDeal() {
        for (SupporterEffectIR e : effects) if (e.isDeal()) return true;
        return false;
    }

    /** 동료레벨 lv에서 이 스킬이 해금됐는가. */
    public boolean unlockedAt(int lv) { return lv >= openLevel; }
}
