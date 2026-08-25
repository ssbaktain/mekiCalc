package mekicalc.timeline.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 마스터리 1개(충실 파싱본). 특정 스킬을 강화하거나(피해/타수/대상/쿨/보스뎀 등) 전역 스탯을 올린다.
 *
 * @param lv          해금 캐릭터 레벨
 * @param masteryName 이름
 * @param masteryIdx  마스터리 idx
 * @param type        skillDamage/stat/skillStat/hit/targetCount/cooldown/duration/probability/tick (없을 수도)
 * @param value       값(단위는 unit)
 * @param skill       강화 대상 스킬 idx — 전역 스탯이면 null
 * @param unit        pct / count 등
 * @param stat        type=stat/skillStat 대상 스탯명
 * @param role        statUp / debuff (전역 버프/디버프일 때)
 * @param target      "enemy" 등
 * @param gatedBy     조건 스킬 idx(베놈약화 → 93060)
 * @param durationMs  지속(있으면 윈도우)
 * @param effects     복합 마스터리의 내부 효과(4건)
 */
public record MasteryIR(
        int lv,
        String masteryName,
        int masteryIdx,
        String type,
        Double value,
        Integer skill,
        String unit,
        String stat,
        String role,
        String target,
        Integer gatedBy,
        Integer durationMs,
        List<EffectIR> effects
) {
    static MasteryIR from(Map<String, Object> m) {
        List<EffectIR> effs = new ArrayList<>();
        for (Map<String, Object> e : IRRead.mapList(m, "effects")) effs.add(EffectIR.from(e));
        Integer lv = IRRead.intg(m, "lv");
        return new MasteryIR(
                lv == null ? 1 : lv,
                IRRead.str(m, "masteryName"),
                IRRead.strInt(m, "masteryIdx"),
                IRRead.str(m, "type"),
                IRRead.dbl(m, "value"),
                IRRead.strInt(m, "skill"),
                IRRead.str(m, "unit"),
                IRRead.str(m, "stat"),
                IRRead.str(m, "role"),
                IRRead.str(m, "target"),
                IRRead.strInt(m, "gatedBy"),
                IRRead.intg(m, "durationMs"),
                List.copyOf(effs)
        );
    }
}
