package mekicalc.engine.sim;

/**
 * 스킬 발동 방식(clt TriggerType). 스케줄러가 이벤트를 서로 다르게 스케줄하는 기준.
 * <ul>
 *   <li>{@code ACTIVE}          : 유저/자동 발동, 재사용 대기시간마다(clt None). 평타도 여기(쿨 0, 초당 여러 회).</li>
 *   <li>{@code PASSIVE}         : 전투 시작부터 상시(clt OnStart). 버프 항상 활성.</li>
 *   <li>{@code ON_TIMER}        : 주기 발동(clt OnTimer). periodSec마다, fastByAtkSpeed면 공속 비례 단축.</li>
 *   <li>{@code ON_ATTACK}       : 공격/타격 시 procPct 확률(clt OnAttack·OnHit). 파이널 어택 등.</li>
 *   <li>{@code ON_DAMAGE}       : 피격 시 procPct 확률(clt OnDamage). 페이크 반격 등.</li>
 *   <li>{@code ON_ACTIVE_SKILL} : 액티브 스킬 사용 시(clt OnActiveSkill). 컨센트레이션 스택 등.</li>
 *   <li>{@code ON_STATE}        : 특정 상태일 때(clt OnState). 조건부.</li>
 * </ul>
 */
public enum TriggerKind {
    ACTIVE, PASSIVE, ON_TIMER, ON_ATTACK, ON_DAMAGE, ON_ACTIVE_SKILL, ON_STATE
}
