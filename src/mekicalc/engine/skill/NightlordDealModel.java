package mekicalc.engine.skill;

import java.util.Set;
import mekicalc.engine.DamageModel;
import mekicalc.engine.DealModel;
import mekicalc.model.StatBlock;

/**
 * 나이트로드 딜 모델(종합 DPS 기반). 큐브 추천·평가의 출력값을 1타가 아닌 {@link NightlordDps#totalDps}로 준다.
 * 스탯 합성(combine/uncombine)은 기존 {@link DamageModel} 규칙을 그대로 위임한다.
 *
 * <p>그래서 공격력·크리·스킬레벨(모스렙/n스렙)·쿨감·공속까지 전부 종합 DPS로 일관 환산된다.
 * DPS로 못 매기는 스탯(방관=몹 방어력 필요, 대상수=보스 단일 무의미, 버프/소환 지속)은 계산 불가로 둔다.
 */
public final class NightlordDealModel implements DealModel {

    /** 종합 DPS로 환산하지 않는(계산 불가) 스탯. 버프 지속%는 가동률로 환산되므로 제외. */
    private static final Set<String> QUALITATIVE = Set.of(
            "DEF_PEN", "BASIC_ATK_TARGETS", "SUMMON_DURATION");

    private final NightlordDps nd;
    private final DamageModel base;   // 스탯 합성 위임용
    private final boolean boss;

    public NightlordDealModel(NightlordDps nd, DamageModel base, boolean boss) {
        this.nd = nd;
        this.base = base;
        this.boss = boss;
    }

    @Override public double output(StatBlock b) { return nd.totalDps(b, boss); }
    @Override public StatBlock combine(StatBlock current, StatBlock delta) { return base.combine(current, delta); }
    @Override public StatBlock uncombine(StatBlock r, StatBlock delta) { return base.uncombine(r, delta); }

    @Override public boolean hasQualitative(StatBlock delta) {
        for (String id : delta.asMap().keySet()) if (QUALITATIVE.contains(id)) return true;
        return false;
    }
}
