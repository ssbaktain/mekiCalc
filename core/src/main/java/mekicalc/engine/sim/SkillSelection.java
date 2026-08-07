package mekicalc.engine.sim;

import java.util.Set;

/**
 * 유저의 스킬 사용 선택. 스킬 엔진 입력.
 *
 * <p>어떤 액티브 스킬을 쓸지·어느 평타를 쓸지만 담는다. 스킬 사용 <b>순서/타이밍</b>(로테이션)은
 * 스케줄러의 몫이며, 스킬 빌드 OFF면 스케줄러가 쿨마다 자동 발동한다.
 *
 * @param activeSkillIds 켜진 액티브 스킬 id 집합(불변 복사). 비어 있으면 평타만.
 * @param basicSkillId   선택한 평타(기본 공격) 스킬 id. 차수당 1개, 중복 불가.
 */
public record SkillSelection(Set<String> activeSkillIds, String basicSkillId) {

    public SkillSelection {
        activeSkillIds = (activeSkillIds == null) ? Set.of() : Set.copyOf(activeSkillIds);
    }
}
