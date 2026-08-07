package mekicalc.engine.sim;

/**
 * 컨텐츠별 전투 조건. 데미지 엔진이 넥슨 공식에 주입한다(클뜯 데이터 기반).
 *
 * @param type            컨텐츠 종류.
 * @param enemyDefense    대상 방어력(방관 적용 후 5000/(방어력+6000)에 사용).
 * @param enemyHp         대상 최대 HP(TTK·최대HP비례 피해용). 0이면 미사용.
 * @param enemyRecvAmp    대상 받는 피해 증가%(디버프 외 컨텐츠 고정분). 없으면 0.
 * @param enemyRecvReduce 대상 받는 피해 감소%. 없으면 0.
 * @param adjust          스킬 계수 직전 피해 조정(NONE/SQRT/CBRT).
 * @param levelPenalty    레벨 보정 배수(1.0 = 없음. 아레나/콜로 레벨차 감소).
 * @param pvpConstant     PVP 피해량 보정 상수(0 = 비PVP. 아레나 7.206e9 등).
 * @param maxTargets      동시 타격 가능 대상 수(보스 1, 챕터 사냥 다수). 스킬 대상 수와 min.
 * @param targetInCc      대상이 상태이상(빙결/기절/무력화)인지 — true면 상태이상 데미지(STATUS_DMG) 적용.
 */
public record ContentProfile(
        Content type,
        double enemyDefense,
        double enemyHp,
        double enemyRecvAmp,
        double enemyRecvReduce,
        Adjust adjust,
        double levelPenalty,
        double pvpConstant,
        int maxTargets,
        boolean targetInCc) {

    /** 챕터 사냥·보스 등 일반(비PVP·무보정) 프로파일 간편 생성(단일 대상). */
    public static ContentProfile plain(Content type, double defense, double hp) {
        return new ContentProfile(type, defense, hp, 0, 0, Adjust.NONE, 1.0, 0, 1, false);
    }

    /** 다수 대상(챕터 사냥 등) 프로파일. */
    public static ContentProfile multi(Content type, double defense, double hp, int maxTargets) {
        return new ContentProfile(type, defense, hp, 0, 0, Adjust.NONE, 1.0, 0, maxTargets, false);
    }
}
