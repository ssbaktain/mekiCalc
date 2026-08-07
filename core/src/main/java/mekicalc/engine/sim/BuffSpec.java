package mekicalc.engine.sim;

import java.util.Map;
import mekicalc.model.StatDef.Combine;

/**
 * 스킬/동료가 거는 효과 정의(버프 또는 디버프). 타임라인에 깔릴 원본.
 *
 * @param target      적용 대상(PLAYER/COMPANION/ENEMY) — 타임라인 분리 기준.
 * @param effects     스탯 id → 수치. 적 받피증(Weakness) 등 디버프도 여기에 담는다(불변 복사).
 * @param durationSec 지속시간(초). 상시 효과는 {@link Double#POSITIVE_INFINITY}.
 * @param combine     합산 규칙 override(SUM/DIMINISHING/MULT_PCT). null이면 각 스탯 기본 규칙.
 * @param stack       스택형이면 스펙, 무스택이면 null.
 */
public record BuffSpec(
        Target target,
        Map<String, Double> effects,
        double durationSec,
        Combine combine,
        Stack stack) {

    public BuffSpec {
        effects = (effects == null) ? Map.of() : Map.copyOf(effects);
    }
}
