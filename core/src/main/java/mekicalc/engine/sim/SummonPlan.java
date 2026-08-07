package mekicalc.engine.sim;

import java.util.List;

/**
 * 동료 소환 계획. 스케줄러가 {@code atSec}에 SUMMON 이벤트로 처리한다.
 *
 * <p>처리 순서: 그 시점 플레이어 버프 타임라인 스냅샷 × {@code inheritRatioPct}(공격력만)
 * → 동료 기준 스탯 고정. 이후 본체 전용 버프 변화는 동료에 미반영(적 디버프만 공유).
 * 동료 {@code skills}의 grants는 companion/enemy 타임라인에 예약된다.
 *
 * @param companionId     동료 식별자.
 * @param atSec           첫 소환 시각(초).
 * @param inheritRatioPct 공격력 계승 비율%(상한 90). 전직 기준점 미달 감소는 스킬 엔진이 미리 반영.
 * @param durSec          소환 지속시간(초). 이 창 동안만 동료 스킬 발동.
 * @param resummonCdSec   재소환 주기(초). atSec + n×resummonCdSec마다 재소환. 0이면 1회.
 * @param skills          동료 스킬(계수 불변 — 그대로 데미지 엔진으로).
 */
public record SummonPlan(
        String companionId,
        double atSec,
        double inheritRatioPct,
        double durSec,
        double resummonCdSec,
        List<SkillPlan> skills) {

    public SummonPlan {
        skills = (skills == null) ? List.of() : List.copyOf(skills);
    }
}
