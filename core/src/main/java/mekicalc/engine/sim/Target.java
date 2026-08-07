package mekicalc.engine.sim;

/**
 * 버프/디버프·타격의 적용 대상. 스케줄러 버프 타임라인 분리 기준.
 * <ul>
 *   <li>{@code PLAYER}    : 본체에게 적용(동료는 소환 시점 스냅샷만 계승, 이후 변화 미반영).</li>
 *   <li>{@code COMPANION} : 동료 자신에게만 적용(동료 지속 동안).</li>
 *   <li>{@code ENEMY}     : 적에게 적용(디버프) — 본체·동료가 공유하는 타임라인.</li>
 * </ul>
 */
public enum Target { PLAYER, COMPANION, ENEMY }
