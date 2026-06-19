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

    /** delta 가 이 모델로 딜 환산이 안 되는(계산 불가) 스탯을 포함하는지. */
    boolean hasQualitative(StatBlock delta);

    /** delta 만큼 더했을 때의 출력 증가율(0.05 == +5%). */
    default double increase(StatBlock current, StatBlock delta) {
        double before = output(current);
        if (before <= 0) return 0;
        return output(combine(current, delta)) / before - 1.0;
    }
}
