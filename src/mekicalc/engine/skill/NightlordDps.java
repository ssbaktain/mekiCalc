package mekicalc.engine.skill;

import java.util.Map;
import mekicalc.engine.DamageModel;
import mekicalc.engine.skill.SkillData.Skill;
import mekicalc.json.Json;
import mekicalc.model.StatBlock;

/**
 * 나이트로드 종합 DPS 조립. 기존 {@link DamageModel}의 공통 배수 G와 {@link SkillDpsModel}의
 * 스킬 계수/가동률을 합쳐 스킬별 데미지·DPS를 계산한다.
 *
 * <p>이번 단위 범위: 스킬 1타 데미지(G×계수×타입데미지×마스터리×메용)와 평타/단순 액티브 DPS.
 * 버프 가동률 주입·DoT/발동·종합 합산·일반몹 대상수는 다음 단위에서 추가한다(메모리 스펙 참조).
 */
public final class NightlordDps {

    private static final String LEVEL = "LEVEL";
    private static final String SKILL_LEVELS = "SKILL_LEVELS";
    private static final String ATK_SPEED = "ATK_SPEED";
    private static final String CDR_PCT = "COOLDOWN_REDUCTION_PCT";
    private static final String CDR_FLAT = "COOLDOWN_REDUCTION";

    private final DamageModel dm;
    private final SkillDpsModel model;
    private final SkillData data;

    public NightlordDps(DamageModel dm, SkillDpsModel model) {
        this.dm = dm;
        this.model = model;
        this.data = model.data();
    }

    private int charLevel(StatBlock b) { return (int) b.get(LEVEL); }
    private int allSkillLv(StatBlock b) { return (int) b.get(SKILL_LEVELS); }
    private int tierSkillLv(StatBlock b, int tier) { return (int) b.get("SKILL_LEVEL_" + tier); }

    /** 스킬의 총 스킬 레벨 = 기본 스킬레벨(차수) + 모스렙 + n스렙. */
    public int skillLevel(Skill s, StatBlock b) {
        return model.totalSkillLevel(s.tier, charLevel(b), allSkillLv(b), tierSkillLv(b, s.tier));
    }

    /**
     * 스킬 1타의 기대 데미지 = G × 계수 × (기본공격뎀%|스킬뎀%) × 마스터리 × 메용.
     * boss=true면 보스 데미지·기본공격 보스 마스터리 반영.
     */
    public double perHit(Skill s, StatBlock b, boolean boss) {
        double coeff = SkillDpsModel.coefficient(s.num("base", 0), data.growth(s.kind), skillLevel(s, b)) / 100.0;
        boolean basic = s.kind.equals("basic");
        double typeFactor = basic ? dm.basicAtkDmgFactor(b) : dm.skillDmgFactor(b);

        double mastery = 1 + s.num("masteryDmg", 0) / 100.0;
        if (basic && boss) mastery *= 1 + s.num("masteryBoss", 0) / 100.0;

        double meyong = 1.0;
        if (s.has("meyong")) {
            double bonus = model.meyongFinalBonus(s.num("meyong", 0),
                    charLevel(b), allSkillLv(b), tierSkillLv(b, data.meyongTier()));
            meyong = 1 + bonus / 100.0;
        }
        return dm.globalMultiplier(b, boss) * coeff * typeFactor * mastery * meyong;
    }

    /** 그 레벨에서 기본 공격 타격 수 = hitsBase + (개방된 타격 마스터리). */
    public int basicHits(Skill s, StatBlock b) {
        int hits = (int) s.num("hitsBase", s.num("hits", 1));
        return hits + sumLevelPlus(s, "hitPlus", charLevel(b));
    }

    /** 평타 DPS = 1타 데미지 × 타격수 × 초당타수(= 1 + 공속%/100). 보스모드 대상수 1. */
    public double basicDps(Skill s, StatBlock b, boolean boss) {
        double perActivation = perHit(s, b, boss) * basicHits(s, b);
        double castsPerSec = 1 + b.get(ATK_SPEED) / 100.0;
        return perActivation * castsPerSec;
    }

    /** 단순 액티브 DPS = 1타 데미지 × 타격수 ÷ 쿨. (틱·DoT·발동형 스킬은 다음 단위에서.) */
    public double simpleActiveDps(Skill s, StatBlock b, boolean boss) {
        double perCast = perHit(s, b, boss) * (int) s.num("hits", 1);
        return perCast / cooldown(s, b);
    }

    /** 스킬의 실효 쿨 = 쿨감퍼(전역 + 스킬 마스터리 DIMINISHING) → 쿨감초(체감) 적용. */
    public double cooldown(Skill s, StatBlock b) {
        double cdrPct = diminish(b.get(CDR_PCT), s.num("cdrMastery", 0), 100);
        return SkillDpsModel.cooldown(s.num("cd", 0), cdrPct, b.get(CDR_FLAT));
    }

    /** DIMINISHING 합성: cur + (max − cur) × (delta / max). (방관/공속/쿨감퍼와 동일 원리.) */
    private static double diminish(double cur, double delta, double max) {
        if (delta <= 0) return cur;
        return cur + (max - cur) * (delta / max);
    }

    /** {"136":1} 형태의 레벨 임계 보정 합(캐릭터 레벨 ≥ 키일 때 가산). */
    private static int sumLevelPlus(Skill s, String field, int charLevel) {
        if (!s.has(field)) return 0;
        int sum = 0;
        for (Map.Entry<String, Object> e : Json.asMap(s.raw.get(field)).entrySet()) {
            if (charLevel >= Integer.parseInt(e.getKey())) sum += (int) (double) (Double) e.getValue();
        }
        return sum;
    }
}
