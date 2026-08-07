package mekicalc.engine.sim;

import java.util.List;

/**
 * 스킬 엔진이 산출한 "시뮬 완성본". 쿨감·계수 성장·평타 대상 수·버프 지속시간을
 * 모두 반영한 뒤 스케줄러에 넘긴다(스케줄러는 이 값을 그대로 소비, 재계산 없음).
 *
 * @param id          스킬 식별자(클뜯 SkillIndex).
 * @param name        표시 이름.
 * @param kind        평타/액티브.
 * @param cooldownSec 쿨감(비율→고정, 7초 특수규칙) 반영 후 재사용 대기시간. 0이면 평타/상시.
 * @param trigger     발동 방식(액티브 쿨/주기/proc/반격/상시). 스케줄러의 이벤트 스케줄 기준.
 * @param cast        타격 스펙. 순수 버프 스킬이면 null.
 * @param grants      이 스킬이 부여하는 버프/디버프 목록(불변 복사).
 */
public record SkillPlan(
        String id,
        String name,
        Kind kind,
        double cooldownSec,
        Trigger trigger,
        Cast cast,
        List<BuffSpec> grants) {

    public SkillPlan {
        grants = (grants == null) ? List.of() : List.copyOf(grants);
    }
}
