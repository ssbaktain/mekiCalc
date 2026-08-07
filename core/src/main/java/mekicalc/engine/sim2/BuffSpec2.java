package mekicalc.engine.sim2;

import java.util.Map;

/**
 * 버프/디버프 효과 정의. 스택·상호배타를 1급 데이터로 담는다.
 *
 * @param target         적용 대상 타임라인.
 * @param effects        스탯 id → 수치. stackState가 있으면 "스택 1개당" 수치.
 * @param durationSec    지속(초). 상시는 +∞. 스택형이면 "스택 1개의 수명".
 * @param stackState     스택형이면 State 이름(SerpentStone 등). 부여 시 +1 스택, 유효값 = 수치×스택수.
 * @param stackMax       스택 상한(stackState != null일 때). 마스터리 stackPlus 반영 완료본.
 * @param exclusiveGroup 같은 그룹 내 스펙 중 1개만 발동(럭키 다이스). null이면 항상 적용.
 *                       엔진은 그룹 내에서 결정론 라운드로빈으로 선택(장기 평균 = 균등 기댓값).
 * @param grantsState    비스택 버프가 게임 State를 켜는 경우 그 이름(HolyCharge 등). ON_STATE 발동·
 *                       requireState 게이팅이 이 State 존재에 연동된다. 스택형은 stackState가 대신한다.
 * @param sourceSkill    부여 스킬 id(가동률 리포트 키·재발동 갱신 식별).
 * @param sourceName     부여 스킬 표시명.
 */
public record BuffSpec2(Target2 target, Map<String, Double> effects, double durationSec,
                        String stackState, int stackMax, String exclusiveGroup, String grantsState,
                        String sourceSkill, String sourceName) {

    public BuffSpec2 {
        effects = (effects == null) ? Map.of() : Map.copyOf(effects);
    }

    /** 가동률 리포트/갱신 식별 키. */
    public String key() {
        return sourceName + ":" + String.join("+", effects.keySet());
    }
}
