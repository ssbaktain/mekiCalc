package mekicalc.engine.sim2;

/**
 * 1회 시전의 타격 스펙(계수 성장·마스터리 반영 완료본).
 *
 * @param coeffFrac     스킬 계수 분율(3.5 = 350%).
 * @param hitCount      타수.
 * @param targetCount   스킬 최대 대상 수(컨텐츠 마리수와 min은 데미지 계산에서).
 * @param tag           평타/스킬 데미지% 구분.
 * @param dmgMasteryPct 피해량 마스터리%(공식: 기본공격뎀/스킬뎀에 합산).
 * @param bossBonus     스킬 전용 보스몹 데미지% 합산분.
 * @param normalBonus   스킬 전용 일반몹 데미지% 합산분.
 * @param dot           지속 피해면 스펙, 즉발이면 null.
 */
public record Cast2(double coeffFrac, int hitCount, int targetCount, DmgTag2 tag,
                    double dmgMasteryPct, double bossBonus, double normalBonus, Dot2 dot) {}
