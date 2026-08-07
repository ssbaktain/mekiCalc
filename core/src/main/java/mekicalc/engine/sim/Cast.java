package mekicalc.engine.sim;

/**
 * 1회 시전의 타격 스펙. 스킬 엔진이 계수 성장·평타 대상 수를 반영해 채운다.
 *
 * @param coeffFrac   스킬 계수 분율(3.5 = 350%). 성장률 반영 완료.
 * @param hitCount    타수(연타 수).
 * @param targetCount 동시 타격 대상 수(평타 대상 수·스킬 최대 대상 반영).
 * @param tag         평타/스킬 데미지% 적용 구분.
 * @param dmgMasteryPct 피해량 마스터리%(넥슨 공식: 기본공격뎀%/스킬뎀%에 합산. 별도 곱 아님). 없으면 0.
 * @param bossBonus   이 타격에 합산될 보스몹 데미지%(스킬 전용 마스터리 등). 없으면 0.
 * @param normalBonus 이 타격에 합산될 일반몹 데미지%. 없으면 0.
 * @param dot         지속 피해면 스펙, 즉발이면 null.
 */
public record Cast(
        double coeffFrac,
        int hitCount,
        int targetCount,
        DmgTag tag,
        double dmgMasteryPct,
        double bossBonus,
        double normalBonus,
        Dot dot) {}
