package mekicalc.engine;

import mekicalc.model.StatBlock;

/**
 * 공용(직업 무관) 딜 모델. 기존 1타 데미지 × 공속 배수를 출력값으로 쓰되,
 * 보스/일반몹 모드에 따라 보스몹/일반몹 데미지를 선택한다. 합성·정성적 판정은 {@link DamageModel}에 위임.
 */
public final class CommonDealModel implements DealModel {

    private final DamageModel m;
    private final boolean boss;

    public CommonDealModel(DamageModel m, boolean boss) {
        this.m = m;
        this.boss = boss;
    }

    @Override public double output(StatBlock b) {
        return (boss ? m.finalDamageBoss(b) : m.finalDamageNormal(b)) * m.attackSpeedFactor(b);
    }

    @Override public StatBlock combine(StatBlock current, StatBlock delta) { return m.combine(current, delta); }
    @Override public StatBlock uncombine(StatBlock r, StatBlock delta) { return m.uncombine(r, delta); }
    @Override public boolean hasQualitative(StatBlock delta) { return m.hasQualitative(delta); }
}
