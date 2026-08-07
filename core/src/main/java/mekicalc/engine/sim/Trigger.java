package mekicalc.engine.sim;

/**
 * 스킬 발동 스펙. 스킬 엔진이 clt trigger 정보를 정규화해 채운다.
 *
 * @param kind          발동 방식.
 * @param procPct       발동 확률%(ON_ATTACK/ON_DAMAGE). 아니면 100.
 * @param periodSec     발동 주기(초, ON_TIMER). 아니면 0.
 * @param fastByAtkSpeed true면 주기가 공속에 비례해 단축(ON_TIMER).
 */
public record Trigger(TriggerKind kind, double procPct, double periodSec, boolean fastByAtkSpeed) {

    /** 유저/자동 발동(쿨 기반)·평타용 기본 트리거. */
    public static final Trigger ACTIVE = new Trigger(TriggerKind.ACTIVE, 100, 0, false);
}
