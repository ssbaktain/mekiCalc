package mekicalc.engine.skill;

/**
 * 나이트로드 DPS 모델의 핵심 계산 함수들(순수 함수 위주).
 *
 * <p>이 단위에서는 검산 가능한 기초 계산만 담는다: 기본 스킬레벨, 현재 계수, 쿨감 적용, 가동률.
 * 종합 DPS 합산(평타+스킬, 보스/일반몹, G 연결)은 다음 단위에서 추가한다.
 * 규칙·검산값은 메모리 스펙(mekicalc-dps-model-spec) 참조.
 */
public final class SkillDpsModel {

    private final SkillData data;

    public SkillDpsModel(SkillData data) { this.data = data; }

    public SkillData data() { return data; }

    /**
     * 차수의 기본 스킬 레벨 = (min(캐릭레벨, 다음 전직레벨) − 자기 전직레벨) × 3.
     * 캐릭터 레벨이 전직레벨 미만이면 0(미개방).
     */
    public int baseSkillLevel(int tier, int charLevel) {
        Integer a = data.advanceLevels().get(tier);
        if (a == null) throw new IllegalArgumentException("알 수 없는 차수: " + tier);
        if (charLevel < a) return 0;
        Integer next = data.advanceLevels().get(tier + 1);
        int cap = (next == null) ? charLevel : Math.min(charLevel, next);
        return (cap - a) * 3;
    }

    /** 총 스킬 레벨 = 기본 스킬레벨 + 모스렙(모든 스킬) + n스렙(그 차수). */
    public int totalSkillLevel(int tier, int charLevel, int allSkillLevel, int tierSkillLevel) {
        return baseSkillLevel(tier, charLevel) + allSkillLevel + tierSkillLevel;
    }

    /** 현재 계수 = base × (1 + 상승비%/100 × 총 스킬레벨). */
    public static double coefficient(double base, double growthPct, int skillLevel) {
        return base * (1 + growthPct / 100.0 * skillLevel);
    }

    /**
     * 쿨타임 적용: ① 비율(쿨감퍼, 0~100) 먼저 → ② 고정(쿨감초) 나중.
     * 고정 감소로 7초 미만 구간에 들어가면 1초당 0.5초만 감소, 하한 4초.
     * (쿨감퍼 자체의 다중 소스 합성은 StatBlock의 DIMINISHING으로 미리 합쳐진 값을 받는다.)
     */
    public static double cooldown(double rawCd, double cdrPct, double cdrFlat) {
        double cd = rawCd * (1 - Math.min(cdrPct, 100.0) / 100.0);
        if (cdrFlat > 0) {
            double flat = cdrFlat;
            if (cd > 7) {
                double full = Math.min(flat, cd - 7);   // 7초까지는 1:1 감소
                cd -= full;
                flat -= full;
            }
            cd -= flat * 0.5;                            // 7초 밑은 0.5배 체감
            if (cd < 4) cd = 4;                          // 하한 4초
        }
        return cd;
    }

    /** 평타 1타 주기(초) = 1 / (1 + 공속%/100). */
    public static double basicPeriod(double atkSpeedPct) {
        return 1.0 / (1 + atkSpeedPct / 100.0);
    }

    /** 쿨 기반 버프 가동률 = min(1, 지속/쿨). 상시(cd<=0)는 1. */
    public static double buffUptimeByCooldown(double duration, double cooldownSec) {
        if (cooldownSec <= 0) return 1.0;
        return Math.min(1.0, duration / cooldownSec);
    }

    /** 발동(스택) 기반 버프 가동률 = 지속 / (지속 + 발동횟수 × 평타주기). (아드레날린) */
    public static double buffUptimeByTrigger(double duration, int triggerCasts, double atkSpeedPct) {
        return duration / (duration + triggerCasts * basicPeriod(atkSpeedPct));
    }

    /**
     * 베놈 중독 가동률 u = 지속 / (지속 + (1/p) × 평타주기).
     * 베놈은 지속이 끝나야 재판정 → 재부여까지 평균 (1/p)×평타주기. p=발동확률(0~1).
     */
    public static double venomUptime(double duration, double procProb, double atkSpeedPct) {
        if (procProb <= 0) return 0.0;
        return duration / (duration + (1.0 / procProb) * basicPeriod(atkSpeedPct));
    }

    /** 메용 적용계수 = 표기 최종뎀% × (1 + 5%/100 × 메용 스킬레벨). 대상 스킬엔 ×(1 + 이 값/100). */
    public double meyongFinalBonus(double listedFinalPct, int charLevel, int allSkillLevel, int tierSkillLevel) {
        int lv = totalSkillLevel(data.meyongTier(), charLevel, allSkillLevel, tierSkillLevel);
        return listedFinalPct * (1 + data.growth("meyong") / 100.0 * lv);
    }
}
