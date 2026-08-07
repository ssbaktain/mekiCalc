package mekicalc.engine.sim2;

/**
 * 컨텐츠 전투 조건(기존 ContentProfile과 동일 필드 계약 — sim2 자립용).
 *
 * @param boss            보스 취급(보스뎀 항). false면 일반몹뎀.
 * @param enemyDefense    대상 방어력.
 * @param enemyRecvAmp    컨텐츠 고정 받피증%.
 * @param enemyRecvReduce 받피감%(95 상한).
 * @param sqrtAdjust      0=없음, 1=제곱근, 2=세제곱근(콘텐츠 보정).
 * @param levelPenalty    레벨 보정 배수(1=없음).
 * @param maxTargets      동시 타격 가능 마리수.
 * @param targetInCc      상태이상 대상 여부(STATUS_DMG 적용).
 */
public record Content2(boolean boss, double enemyDefense, double enemyRecvAmp, double enemyRecvReduce,
                       int sqrtAdjust, double levelPenalty, int maxTargets, boolean targetInCc) {

    public static Content2 boss1(double defense) {
        return new Content2(true, defense, 0, 0, 0, 1.0, 1, false);
    }
}
