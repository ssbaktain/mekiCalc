package mekicalc.engine;

import mekicalc.model.StatBlock;

/**
 * 큐브 추천·평가가 쓰는 '딜 평가' 추상화. 공용은 {@link DamageModel}(평타 1타 × 공속),
 * 나이트로드는 종합 DPS 모델이 구현한다. 평가기는 이 인터페이스만 의존해 직업별 모델을 갈아끼운다.
 */
public interface DealModel {

    /** 비교 기준 출력값(공용=1타×공속, 나이트로드=종합 DPS). 상대 비교용. */
    double output(StatBlock b);

    /** current 에 delta 를 각 스탯 규칙대로 합친 StatBlock. */
    StatBlock combine(StatBlock current, StatBlock delta);

    /** combine 의 역연산(부위 옵션 제거). */
    StatBlock uncombine(StatBlock r, StatBlock delta);

    /**
     * 같은 스탯의 옵션값 여러 개를 하나의 델타값으로 모을 때의 규칙(큐브 한 부위 내 여러 줄 집계용).
     * 방어관통·공격속도처럼 체감(DIMINISHING)인 스탯은 슬롯끼리도 곱연산으로 합쳐야 인게임과 맞다
     * (예: 방관 12% + 12% → 22.56%, 단순 24% 아님). 그 외(합산·최종뎀)는 종전처럼 단순 합산.
     * 기본 구현은 합산 — 스탯 규칙을 아는 {@link DamageModel}이 override 한다.
     */
    default double aggregate(String statId, double a, double b) { return a + b; }

    /** delta 가 이 모델로 딜 환산이 안 되는(계산 불가) 스탯을 포함하는지. */
    boolean hasQualitative(StatBlock delta);

    /** delta 만큼 더했을 때의 출력 증가율(0.05 == +5%). */
    default double increase(StatBlock current, StatBlock delta) {
        double before = output(current);
        if (before <= 0) return 0;
        return output(combine(current, delta)) / before - 1.0;
    }
}
