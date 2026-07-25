package mekicalc.engine;

import mekicalc.model.StatBlock;

/**
 * 공용(직업 무관) 딜 모델. 기존 1타 데미지 × 공속 배수를 출력값으로 쓰되,
 * 보스/일반몹 모드에 따라 보스몹/일반몹 데미지를 선택한다. 합성은 {@link DamageModel}에 위임.
 *
 * <p><b>방어 관통</b>은 대상 방어력({@code targetDef})이 주어지면 방어배수 {@code f=5000/(6000+방어력×(1−방관%))}로
 * 수치 환산한다(나이트로드 모델과 동일 공식). 몬스터 방어력을 알게 되어 방관은 더 이상 정성적 스탯이 아니다.
 */
public final class CommonDealModel implements DealModel {

    private static final String DEF_PEN = "DEF_PEN";

    private final DamageModel m;
    private final boolean boss;
    private final Double targetDef;   // 대상 몹 방어력(서버 전용). null이면 방어배수 1(방관 효과 없음).

    public CommonDealModel(DamageModel m, boolean boss) {
        this(m, boss, null);
    }

    public CommonDealModel(DamageModel m, boolean boss, Double targetDef) {
        this.m = m;
        this.boss = boss;
        this.targetDef = targetDef;
    }

    /** 방어배수 f = 5000/(6000 + 방어력 × max(0, 1 − 방관%/100)). 대상 없으면 1. */
    private double defFactor(StatBlock b) {
        if (targetDef == null) return 1.0;
        double penPct = b.get(DEF_PEN);
        return 5000.0 / (6000.0 + targetDef * Math.max(0, 1 - penPct / 100.0));
    }

    @Override public double output(StatBlock b) {
        return (boss ? m.finalDamageBoss(b) : m.finalDamageNormal(b)) * m.attackSpeedFactor(b) * defFactor(b);
    }

    @Override public StatBlock combine(StatBlock current, StatBlock delta) { return m.combine(current, delta); }
    @Override public StatBlock uncombine(StatBlock r, StatBlock delta) { return m.uncombine(r, delta); }
    @Override public double aggregate(String statId, double a, double b) { return m.aggregate(statId, a, b); }

    /** 방관은 방어배수로 수치 환산되므로 정성 판정에서 제외한다(나머지는 base 규칙에 위임). */
    @Override public boolean hasQualitative(StatBlock delta) {
        StatBlock filtered = new StatBlock();
        for (java.util.Map.Entry<String, Double> e : delta.asMap().entrySet()) {
            if (!DEF_PEN.equals(e.getKey())) filtered.set(e.getKey(), e.getValue());
        }
        return m.hasQualitative(filtered);
    }
}
