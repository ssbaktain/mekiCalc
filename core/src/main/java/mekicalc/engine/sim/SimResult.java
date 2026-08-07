package mekicalc.engine.sim;

import java.util.Map;

/**
 * 시뮬레이션 결과.
 *
 * @param totalDamage 전투시간 동안 누적 총 데미지.
 * @param dps         초당 데미지(= totalDamage / durationSec).
 * @param perSkill    스킬명 → 기여 데미지(도넛·기여% 표시용, 불변 복사).
 * @param ttkSec      대상 처치시간(초, = 대상 HP / dps). HP 미지정이면 0.
 */
public record SimResult(double totalDamage, double dps, Map<String, Double> perSkill, double ttkSec) {

    public SimResult {
        perSkill = (perSkill == null) ? Map.of() : Map.copyOf(perSkill);
    }
}
