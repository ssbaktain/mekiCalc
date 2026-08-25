package mekicalc.timeline.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 스킬 1개(충실 파싱본).
 *
 * @param idx          스킬 idx
 * @param name         이름
 * @param desc         설명
 * @param role         용도 태그(buff/deal/utility/skillBoost/debuff)
 * @param type         active / passive
 * @param trigger      onStart / active / basicAttack / onAttack / periodic / onActiveSkill
 * @param requireLevel 습득 레벨
 * @param cooldownMs   쿨다운(ms) — 없으면 null
 * @param triggerBy    onAttack 등의 발동 계기("anyHit")
 * @param chance       발동 확률(‰) — onAttack 프록 등
 * @param refresh      버프 재적용 시 갱신 여부(false=중첩/카운트)
 * @param periodMs     periodic 주기(ms)
 * @param count        누적 카운트 상한(아드레날린 등)
 * @param gatedBy      이 스킬 효과가 걸리는 조건 스킬 idx — 없으면 null
 * @param chargeMs     충전형 onAttack: 충전이 이 주기(ms) 절대 격자로 재생성되고, 각 창의 첫 공격이 소모해 발동(마크 등)
 * @param effects      효과 목록
 */
public record SkillIR(
        int idx,
        String name,
        String desc,
        List<String> role,
        String type,
        String trigger,
        int requireLevel,
        Integer cooldownMs,
        String triggerBy,
        Integer chance,
        Boolean refresh,
        Integer periodMs,
        Integer count,
        Integer gatedBy,
        Integer chargeMs,
        boolean summon,
        List<EffectIR> effects
) {
    static SkillIR from(Map<String, Object> m) {
        List<EffectIR> effs = new ArrayList<>();
        for (Map<String, Object> e : IRRead.mapList(m, "effects")) effs.add(EffectIR.from(e));
        Integer req = IRRead.intg(m, "requireLevel");
        return new SkillIR(
                IRRead.strInt(m, "idx"),
                IRRead.str(m, "name"),
                IRRead.str(m, "desc"),
                IRRead.strList(m, "role"),
                IRRead.str(m, "type"),
                IRRead.str(m, "trigger"),
                req == null ? 1 : req,
                IRRead.intg(m, "cooldown"),
                IRRead.str(m, "triggerBy"),
                IRRead.intg(m, "chance"),
                IRRead.bool(m, "refresh"),
                IRRead.intg(m, "periodMs"),
                IRRead.intg(m, "count"),
                IRRead.strInt(m, "gatedBy"),
                IRRead.intg(m, "chargeMs"),
                Boolean.TRUE.equals(IRRead.bool(m, "summon")),   // 설치기(다크플레어·퍼지) — 벞지 미적용
                List.copyOf(effs)
        );
    }

    /** 충전형 onAttack 발동(충전이 chargeMs 격자로 재생성, 공격 시 소모)인가 — 마크류. */
    public boolean isCharged() { return "onAttack".equals(trigger) && chargeMs != null && chargeMs > 0 && hasDeal(); }

    public boolean hasDeal() {
        for (EffectIR e : effects) if ("deal".equals(e.role())) return true;
        return false;
    }

    /** 습득 차수(1~4). 스킬 idx 9Txxx의 T. 판별 불가(9000 등)는 4로. 성장 유효레벨 계산에 쓰인다. */
    public int tier() {
        int t = (idx / 1000) % 10;
        return (t >= 1 && t <= 4) ? t : 4;
    }
}
