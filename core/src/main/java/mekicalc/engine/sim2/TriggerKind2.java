package mekicalc.engine.sim2;

/**
 * 발동 방식(clt TriggerType). sim2에서는 전부 타임라인 실이벤트로 처리된다(정적 근사 없음).
 * <ul>
 *   <li>ACTIVE: 유저/자동(쿨마다). 평타 포함.</li>
 *   <li>PASSIVE: OnStart 상시.</li>
 *   <li>ON_TIMER: 주기 발동.</li>
 *   <li>ON_ATTACK: 공격 시 확률 발동(기본: 평타 타격에 후킹 — 홀리 차지 원문 "기본 공격 시").</li>
 *   <li>ON_DAMAGE: 피격 시 확률 발동(피격 빈도는 SimConfig 명시 가정).</li>
 *   <li>ON_ACTIVE_SKILL: 액티브 사용 시.</li>
 *   <li>ON_STATE: 특정 State 진입 시(StateStore 콜백).</li>
 * </ul>
 */
public enum TriggerKind2 { ACTIVE, PASSIVE, ON_TIMER, ON_ATTACK, ON_DAMAGE, ON_ACTIVE_SKILL, ON_STATE }
