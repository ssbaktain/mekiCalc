package mekicalc.engine;

import mekicalc.model.StatBlock;

/**
 * "어떤 수단으로 스탯 1건을 확보한다"는 하나의 선택지에 대한 평가 결과.
 *
 * <p>세 가지 기능이 모두 이 한 객체에서 나온다.
 * <ul>
 *   <li>기능1(단순 비교) : {@link #damageIncrease}</li>
 *   <li>기능2(기댓값 포함): {@link #expectedAttempts}, {@link #expectedCost}</li>
 *   <li>기능3(우선순위)  : {@link #efficiency()} 로 정렬</li>
 * </ul>
 */
public final class AcquisitionOption {

    public final String sourceId;
    public final String sourceName;
    public final String label;        // 예: "보스 데미지% +9"
    public final StatBlock delta;     // 이 선택지로 얻는 스탯 변화량

    public final double expectedAttempts;   // 목표 1건을 얻기까지 평균 시도 횟수
    public final double expectedCost;        // 정규화된 기대 비용 (재화가치 반영)
    public final String costDescription;     // 사람이 읽는 비용 설명

    public double damageIncrease;            // 최종 데미지 증가율 (DamageModel이 채움)
    public boolean qualitative;              // 공속/방관 등 수치 미산정(정성적 고효율 후보)

    public AcquisitionOption(String sourceId, String sourceName, String label,
                             StatBlock delta, double expectedAttempts,
                             double expectedCost, String costDescription) {
        this.sourceId = sourceId;
        this.sourceName = sourceName;
        this.label = label;
        this.delta = delta;
        this.expectedAttempts = expectedAttempts;
        this.expectedCost = expectedCost;
        this.costDescription = costDescription;
    }

    /** 효율 = 기대비용 1단위당 데미지 증가율. 클수록 우선순위가 높다. */
    public double efficiency() {
        if (expectedCost <= 0) return 0;
        return damageIncrease / expectedCost;
    }
}
