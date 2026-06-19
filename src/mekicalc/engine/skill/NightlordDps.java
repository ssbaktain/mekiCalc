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
    private static final String CRIT_RATE = "CRIT_RATE";
    private static final String CRIT_DMG = "CRIT_DMG";
    private static final String FINAL_DMG = "FINAL_DMG";
    private static final String CDR_PCT = "COOLDOWN_REDUCTION_PCT";
    private static final String CDR_FLAT = "COOLDOWN_REDUCTION";
    private static final String BASIC_ATK_TARGETS = "BASIC_ATK_TARGETS";

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

    // ---- 버프 가동률 주입 ----

    /** 버프 주입 결과: 효과가 반영된 StatBlock + 공격력 별도 버킷 배수. */
    public static final class Buffed {
        public final StatBlock stats;       // 크리/최종뎀/공속에 시간제 버프 평균기여 반영
        public final double skillAtkFactor; // 공격력 별도 곱 버킷 (1 + Σ스킬공퍼/100)
        Buffed(StatBlock stats, double skillAtkFactor) {
            this.stats = stats;
            this.skillAtkFactor = skillAtkFactor;
        }
    }

    /**
     * 입력 스탯에 시간제 버프(다크사이트·아드레날린·퍼지·민첩)를 가동률 평균으로 주입하고,
     * 공격력 별도 버킷(숙련된 표창술 + 다크사이트 등 스킬 공퍼)을 계산한다.
     * 상시 스탯 패시브(공격력% 제외)는 이미 입력에 포함되어 건드리지 않는다.
     */
    public Buffed buffed(StatBlock input) {
        int cl = charLevel(input);
        double atkSpeedBase = input.get(ATK_SPEED);   // 순환 방지: 가동률은 입력 공속 기준
        StatBlock eff = new StatBlock(input);
        double skillAtkSum = 0;

        // 스탯 패시브: 공격력%만 엔진 적용(나머지는 입력에 포함). 상시라 가동률 1.
        for (Map<String, Object> m : data.statPassives()) {
            double atkPct = Json.num(m, "atkPct", 0);
            if (atkPct == 0 || cl < Json.num(m, "open", 0)) continue;
            skillAtkSum += atkPct * scale("statPassive", (int) Json.num(m, "tier", 0), cl, input);
        }

        // 버프: 상시(always)는 입력 포함 → 건너뜀. 시간제만 가동률 평균기여.
        for (Map<String, Object> m : data.buffs()) {
            if (Json.bool(m, "always", false) || cl < Json.num(m, "open", 0)) continue;
            boolean fixed = Json.bool(m, "fixed", false);
            double scale = fixed ? 1.0 : scale(Json.str(m, "kind", "statActive"), (int) Json.num(m, "tier", 0), cl, input);
            double u = buffUptime(m, atkSpeedBase);

            double atkPct = Json.num(m, "atkPct", 0);
            if (atkPct != 0) skillAtkSum += atkPct * scale * u;
            double cr = Json.num(m, "critRate", 0);
            if (cr != 0) eff.set(CRIT_RATE, eff.get(CRIT_RATE) + cr * scale * u);
            double cd = Json.num(m, "critDmg", 0);
            if (cd != 0) eff.set(CRIT_DMG, eff.get(CRIT_DMG) + cd * scale * u);
            double fd = Json.num(m, "finalDmg", 0) + Json.num(m, "finalDmgMastery", 0);
            if (fd != 0) eff.set(FINAL_DMG, multPct(eff.get(FINAL_DMG), fd * scale * u));
            double as = Json.num(m, "atkSpeed", 0);
            if (as != 0) eff.set(ATK_SPEED, diminish(eff.get(ATK_SPEED), as * scale * u, 150));
        }
        return new Buffed(eff, 1 + skillAtkSum / 100.0);
    }

    /** 버프 반영 1타 데미지 = perHit(주입 스탯) × 공격력 별도 버킷. */
    public double perHit(Skill s, Buffed bf, boolean boss) {
        return perHit(s, bf.stats, boss) * bf.skillAtkFactor;
    }

    /** 버프 반영 평타 DPS. */
    public double basicDps(Skill s, Buffed bf, boolean boss) {
        double perActivation = perHit(s, bf, boss) * basicHits(s, bf.stats);
        double castsPerSec = 1 + bf.stats.get(ATK_SPEED) / 100.0;
        return perActivation * castsPerSec;
    }

    /** 버프 반영 단순 액티브 DPS. */
    public double simpleActiveDps(Skill s, Buffed bf, boolean boss) {
        double perCast = perHit(s, bf, boss) * (int) s.num("hits", 1);
        return perCast / cooldown(s, bf.stats);
    }

    // ---- 특수 스킬(도트·발동) 컴포넌트. 전부 G만 받음(스킬뎀% 미적용), 공격력 별도 버킷은 totalDps에서 곱. ----

    /** 스킬 계수(스케일 반영) / 100. */
    private double coeffFrac(Skill s, double base, StatBlock eff) {
        return SkillDpsModel.coefficient(base, data.growth(s.kind), skillLevel(s, eff)) / 100.0;
    }

    /** 메용 적용 배수 = 1 + 메용적용계수/100. */
    private double meyongFactor(double listed, StatBlock eff) {
        return 1 + model.meyongFinalBonus(listed, charLevel(eff),
                (int) eff.get(SKILL_LEVELS), (int) eff.get("SKILL_LEVEL_" + data.meyongTier())) / 100.0;
    }

    /** 베놈 중독 가동률(eff의 공속 기준). */
    private double venomUptimeOf(StatBlock eff) {
        Map<String, Object> dot = Json.asMap(data.skill("venom").raw.get("dot"));
        double p = (Json.num(dot, "proc", 0) + Json.num(dot, "procMasteryPlus", 0)) / 100.0;
        return SkillDpsModel.venomUptime(Json.num(dot, "dur", 0), p, eff.get(ATK_SPEED));
    }

    /** 쉐도우 파트너 타격당 기대 추가(공격력 버킷 제외). */
    public double shadowExtraPerHit(StatBlock eff, boolean boss) {
        Skill s = data.skill("shadowPartner");
        if (s == null || charLevel(eff) < s.open) return 0;
        return dm.globalMultiplier(eff, boss) * coeffFrac(s, s.num("base", 0), eff)
                * (1 + s.num("masteryDmg", 0) / 100.0) * meyongFactor(s.num("meyong", 0), eff)
                * (s.num("proc", 0) / 100.0);
    }

    /** 페이탈 베놈 타격당 기대 추가(중독 가동률 반영, 메용 비대상). */
    public double fatalExtraPerHit(StatBlock eff, boolean boss) {
        Skill s = data.skill("fatalVenom");
        if (s == null || charLevel(eff) < s.open) return 0;
        return dm.globalMultiplier(eff, boss) * coeffFrac(s, s.num("base", 0), eff)
                * (1 + s.num("masteryDmg", 0) / 100.0) * (s.num("proc", 0) / 100.0) * venomUptimeOf(eff);
    }

    /** 베놈 도트 초당 데미지(공격력 버킷 제외). */
    public double venomDotDps(StatBlock eff, boolean boss) {
        Skill s = data.skill("venom");
        if (s == null || charLevel(eff) < s.open) return 0;
        Map<String, Object> dot = Json.asMap(s.raw.get("dot"));
        double coeff = SkillDpsModel.coefficient(Json.num(dot, "perSec", 0), data.growth(s.kind), skillLevel(s, eff)) / 100.0;
        return dm.globalMultiplier(eff, boss) * coeff * meyongFactor(s.num("meyong", 0), eff) * venomUptimeOf(eff);
    }

    /** 마크 오브 어쌔신 1회 발동 데미지(공격력 버킷 제외). */
    public double markPerHit(StatBlock eff, boolean boss) {
        Skill s = data.skill("markOfAssassin");
        if (s == null || charLevel(eff) < s.open) return 0;
        return dm.globalMultiplier(eff, boss) * coeffFrac(s, s.num("base", 0), eff)
                * (1 + s.num("masteryDmg", 0) / 100.0) * (1 + s.num("finalNightlord", 0) / 100.0);
    }

    /** 써든레이드 지속피해 1시전 데미지(공격력 버킷 제외). */
    public double suddenDotPerCast(StatBlock eff, boolean boss) {
        Skill s = data.skill("suddenRaid");
        Map<String, Object> dot = Json.asMap(s.raw.get("dotPerCast"));
        double dotBase = Json.num(dot, "perTick", 0) * Json.num(dot, "ticks", 0);
        double coeff = SkillDpsModel.coefficient(dotBase, data.growth(s.kind), skillLevel(s, eff)) / 100.0;
        return dm.globalMultiplier(eff, boss) * coeff * (1 + s.num("masteryDmg", 0) / 100.0);
    }

    /** 받는 피해 증가 디버프 배수 = 1 + Σ(받는피해% × 가동률). 같은 종류 합연산. */
    public double recvDmgFactor(StatBlock eff) {
        int cl = charLevel(eff);
        double sum = 0;
        for (Map<String, Object> d : data.debuffs()) {
            if (cl < Json.num(d, "open", 0)) continue;
            double recv = Json.num(d, "recvDmg", 0) / 100.0;
            Object up = d.get("uptime");
            double u = (up instanceof Double) ? (Double) up
                    : "venom_u".equals(up) ? venomUptimeOf(eff)
                    : "purge".equals(up) ? purgeUptime(eff) : 1.0;
            sum += recv * u;
        }
        return 1 + sum;
    }

    private double purgeUptime(StatBlock eff) {
        for (Map<String, Object> m : data.buffs()) {
            if (!"purge".equals(Json.str(m, "id", ""))) continue;
            if (charLevel(eff) < Json.num(m, "open", 0)) return 0;
            double dur = (Json.num(m, "dur", 0) + Json.num(m, "durMasteryFlat", 0)) * (1 + Json.num(m, "durMasteryPct", 0) / 100.0);
            return SkillDpsModel.buffUptimeByCooldown(dur, Json.num(m, "cd", 0));
        }
        return 0;
    }

    // ---- 종합 DPS 합산 ----

    /** 그 레벨에서 쓰는 최고 차수 기본 공격(없으면 null). */
    private Skill highestBasic(int cl) {
        Skill best = null;
        for (Skill s : data.skills()) {
            if (!s.kind.equals("basic") || cl < s.open) continue;
            if (best == null || s.tier > best.tier) best = s;
        }
        return best;
    }

    /** 일반몹 모드 평타 대상수 = 기본 + 차수 대상 마스터리 + 프리셋 대상수 스탯. */
    public double basicTargets(Skill basic, StatBlock eff) {
        return basic.num("targetsBase", basic.num("targets", 1))
                + sumLevelPlus(basic, "targetPlus", charLevel(eff))
                + eff.get(BASIC_ATK_TARGETS);
    }

    /** 한 시전당 액티브 타격수(다크플레어 틱·평타 타격 보정 포함). */
    private int hitsPerCast(Skill s, StatBlock eff) {
        if (s.id.equals("darkFlare")) {
            return (int) (s.num("ticksWithPeriodMastery", s.num("ticks", 1))
                    * (s.num("hitsPerTick", 1) + s.num("hitsPerTickMastery", 0)));
        }
        return (int) s.num("hits", 1);
    }

    /**
     * 종합 DPS = (평타 + 액티브 + 마크 + 써든도트 + 추가타(쉐파·페이탈) + 베놈도트) × 받는피해 디버프.
     * boss=true 보스 단일, false 일반몹(대상수 곱·보스뎀 제외).
     * 추가타는 모든 타격(평타+액티브 본타)에 타격당 기대값으로 적용.
     */
    public double totalDps(StatBlock input, boolean boss) {
        Buffed bf = buffed(input);
        StatBlock eff = bf.stats;
        double atkF = bf.skillAtkFactor;
        int cl = charLevel(eff);
        double castsPerSec = 1 + eff.get(ATK_SPEED) / 100.0;

        double baseDps = 0;          // 코어(평타+액티브+마크+써든도트), 추가타 제외
        double hitsPerSec = 0;       // 쉐파/페이탈 적용 대상 타수(대상수 포함)

        Skill basic = highestBasic(cl);
        if (basic != null) {
            int hits = basicHits(basic, eff);
            double targets = boss ? 1 : basicTargets(basic, eff);
            baseDps += perHit(basic, bf, boss) * hits * targets * castsPerSec;
            hitsPerSec += hits * targets * castsPerSec;
        }

        for (Skill s : data.skills()) {
            if (cl < s.open) continue;
            if (!(s.kind.equals("dealActive"))) continue;   // 평타/패시브/마크는 별도
            double cd = cooldown(s, eff);
            if (cd <= 0) continue;
            int hits = hitsPerCast(s, eff);
            double targets = boss ? 1 : s.num("targets", 1);
            baseDps += perHit(s, bf, boss) * hits * targets / cd;
            hitsPerSec += hits * targets / cd;
            if (s.id.equals("suddenRaid")) {
                baseDps += suddenDotPerCast(eff, boss) * atkF * targets / cd;   // 도트는 타수 아님
            }
        }

        // 마크: 기존 타격에 얹히는 보너스(새 타수 아님). 주기 = period − periodMastery.
        Skill mark = data.skill("markOfAssassin");
        if (mark != null && cl >= mark.open) {
            double period = mark.num("period", 5) - mark.num("periodMastery", 0);
            double targets = boss ? 1 : mark.num("targetsNightlord", mark.num("targetsBase", 1));
            if (period > 0) baseDps += markPerHit(eff, boss) * atkF * targets / period;
        }

        // 추가타(쉐파+페이탈): 모든 타격에 타격당 기대값.
        double procPerHit = (shadowExtraPerHit(eff, boss) + fatalExtraPerHit(eff, boss)) * atkF;
        double procDps = hitsPerSec * procPerHit;

        // 베놈 도트(연속): 타수 아님.
        double venomDps = venomDotDps(eff, boss) * atkF;

        return (baseDps + procDps + venomDps) * recvDmgFactor(eff);
    }

    /** 버프/스탯패시브 효과의 스킬레벨 스케일 = 1 + 상승비%/100 × 총 스킬레벨. */
    private double scale(String kind, int tier, int charLevel, StatBlock input) {
        int lv = model.totalSkillLevel(tier, charLevel, (int) input.get(SKILL_LEVELS), (int) input.get("SKILL_LEVEL_" + tier));
        return 1 + data.growth(kind) / 100.0 * lv;
    }

    /** 버프 가동률: triggerCasts 있으면 발동형, 아니면 쿨 기반. 지속 = (dur+초마스터리)×(1+%마스터리). */
    private double buffUptime(Map<String, Object> m, double atkSpeedBase) {
        double dur = (Json.num(m, "dur", 0) + Json.num(m, "durMasteryFlat", 0)) * (1 + Json.num(m, "durMasteryPct", 0) / 100.0);
        if (m.containsKey("triggerCasts")) {
            return SkillDpsModel.buffUptimeByTrigger(dur, (int) Json.num(m, "triggerCasts", 0), atkSpeedBase);
        }
        return SkillDpsModel.buffUptimeByCooldown(dur, Json.num(m, "cd", 0));
    }

    /** MULT_PCT 합성: (1+a/100)(1+b/100)−1, %단위. (최종 데미지끼리 곱.) */
    private static double multPct(double a, double b) {
        return ((1 + a / 100.0) * (1 + b / 100.0) - 1) * 100.0;
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
