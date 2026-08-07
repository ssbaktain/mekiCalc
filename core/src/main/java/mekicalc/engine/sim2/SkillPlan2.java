package mekicalc.engine.sim2;

import java.util.List;

/**
 * 스킬 엔진 산출 완성본(쿨감·성장·마스터리 반영). 시뮬레이터는 재계산 없이 소비만 한다.
 *
 * @param id          클뜯 SkillIndex.
 * @param name        표시 이름.
 * @param kind        평타/액티브.
 * @param cooldownSec 실효 쿨(쿨감 반영). proc이면 내부쿨.
 * @param trigger     발동 스펙(조건 포함).
 * @param cast        타격 스펙. 순수 버프면 null.
 * @param grants      부여 효과 목록.
 */
public record SkillPlan2(String id, String name, Kind2 kind, double cooldownSec,
                         Trigger2 trigger, Cast2 cast, List<BuffSpec2> grants) {

    public SkillPlan2 {
        grants = (grants == null) ? List.of() : List.copyOf(grants);
    }
}
