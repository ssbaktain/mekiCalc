package mekicalc.model;

import java.util.Map;
import mekicalc.json.Json;

/**
 * 데미지 공식의 미확정 상수 모음(profile.json).
 *
 * <p>기준 시나리오: <b>필드에서 일반 몬스터 대상 + 기본 공격</b>.
 * 그래서 일반몹/보스몹 데미지는 가중 합산하지 않고 각각 따로 계산하며,
 * 스킬 데미지%는 적용하지 않는다(기본 공격이므로 기본 공격 데미지%만 적용).
 */
public final class Profile {

    /** 적 방어력(0~1). 방어관통력의 데미지 환산에 사용(현재 방관 미환산이라 미사용). ※확인필요 */
    public final double enemyDefense;
    /** 기본 공격 계수 = skillLevelBase × (1 + skillLevelPerLevel × 스킬레벨). base 기본값 2.9. */
    public final double skillLevelBase;
    /** 스킬 레벨 1당 계수 증가율(분수). 사용자 제공: 0.004 (=0.4%). */
    public final double skillLevelPerLevel;

    /** true면 데미지 옵션 카테고리를 합연산(mode B), false면 카테고리끼리 곱연산(mode A). */
    public final boolean additiveDamage;

    public Profile(double enemyDefense, double skillLevelBase,
                   double skillLevelPerLevel, boolean additiveDamage) {
        this.enemyDefense = enemyDefense;
        this.skillLevelBase = skillLevelBase;
        this.skillLevelPerLevel = skillLevelPerLevel;
        this.additiveDamage = additiveDamage;
    }

    public static Profile fromJson(Map<String, Object> m) {
        return new Profile(
                Json.num(m, "enemyDefense", 0.0),
                Json.num(m, "skillLevelBase", 2.9),
                Json.num(m, "skillLevelPerLevel", 0.004),
                Json.bool(m, "additiveDamage", false));
    }

    /** 기본값(profile.json 부재 시·sim2 데모/테스트용). fromJson 기본값과 동일. */
    public static Profile defaults() {
        return new Profile(0.0, 2.9, 0.004, false);
    }
}
