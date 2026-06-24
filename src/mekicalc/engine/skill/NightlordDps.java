package mekicalc.engine.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String BUFF_DURATION = "BUFF_DURATION";
    private static final String BASIC_ATK_DMG = "BASIC_ATK_DMG";
    private static final String MIN_DMG_MULT = "MIN_DMG_MULT";
    private static final String MAX_DMG_MULT = "MAX_DMG_MULT";
    private static final String BOSS_DMG = "BOSS_DMG";
    private static final String SKILL_DMG = "SKILL_DMG";

    private final DamageModel dm;
    private final SkillDpsModel model;
    private final SkillData data;
    /** 적용할 시간제 버프 id 집합. null이면 전부 적용(하위호환·기본). 빈 집합이면 아무 버프도 적용 안 함. */
    private final Set<String> activeBuffs;

    public NightlordDps(DamageModel dm, SkillDpsModel model) {
        this(dm, model, null);
    }

    public NightlordDps(DamageModel dm, SkillDpsModel model, Set<String> activeBuffs) {
        this.dm = dm;
        this.model = model;
        this.data = model.data();
        this.activeBuffs = activeBuffs;
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

        int cl = charLevel(b);
        double mastery = 1 + levelSum(s.raw, "masteryDmgByLevel", cl) / 100.0;
        if (basic && boss) mastery *= 1 + levelSum(s.raw, "masteryBossByLevel", cl) / 100.0;

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
        double cdrPct = diminish(b.get(CDR_PCT), levelSum(s.raw, "cdrMasteryByLevel", charLevel(b)), 100);
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
        double buffDur = input.get(BUFF_DURATION);    // 버프 지속시간 증가% → 가동률↑
        StatBlock eff = new StatBlock(input);
        double skillAtkSum = 0;

        // 스탯 패시브: 공퍼(숙련된 표창술)만 엔진 적용, 나머지는 입력 포함.
        // wanted=체크됨(기본 null이면 '습득한 것만'). learned=현재 레벨로 습득.
        // 미습득인데 체크하면 효과를 더하고(과대), 습득했는데 해제하면 차감(없다고 가정 — 근사).
        for (Map<String, Object> m : data.statPassives()) {
            boolean wanted = wantsBuff(m, cl);
            boolean learned = cl >= Json.num(m, "open", 0);
            double sc = passiveScale(m, cl, input);
            double atkPct = Json.num(m, "atkPct", 0);
            if (atkPct != 0) { if (wanted) skillAtkSum += atkPct * sc; }   // 공퍼: 원하면 적용(미습득이어도=과대)
            else adjustPassive(eff, m, sc, cl, wanted, learned);           // 입력 포함: 원함/보유 차이만큼 가감
        }
        // 상시 버프(페이크·다크세레니티)도 입력 포함.
        for (Map<String, Object> m : data.buffs()) {
            if (!Json.bool(m, "always", false)) continue;
            adjustPassive(eff, m, passiveScale(m, cl, input), cl, wantsBuff(m, cl), cl >= Json.num(m, "open", 0));
        }

        // 시간제 버프: 원하면(체크) 가동률 평균 기여(엔진 적용). 미습득이어도 체크하면 적용(과대).
        for (Map<String, Object> m : data.buffs()) {
            if (Json.bool(m, "always", false) || !wantsBuff(m, cl)) continue;
            boolean fixed = Json.bool(m, "fixed", false);
            double scale = fixed ? 1.0 : scale(Json.str(m, "kind", "statActive"), (int) Json.num(m, "tier", 0), cl, input);
            double u = buffUptime(m, atkSpeedBase, buffDur, cl);

            double atkPct = Json.num(m, "atkPct", 0);
            if (atkPct != 0) skillAtkSum += atkPct * scale * u;
            double cr = Json.num(m, "critRate", 0);
            if (cr != 0) eff.set(CRIT_RATE, eff.get(CRIT_RATE) + cr * scale * u);
            double cd = Json.num(m, "critDmg", 0);
            if (cd != 0) eff.set(CRIT_DMG, eff.get(CRIT_DMG) + cd * scale * u);
            // 레벨계수는 base에만, 마스터리는 스케일 없이 나중에 더함(인게임 툴팁이 마스터리 미표기 → 마지막 계산).
            double fd = Json.num(m, "finalDmg", 0) * scale + levelSum(m, "finalDmgMasteryByLevel", cl);
            if (fd != 0) eff.set(FINAL_DMG, multPct(eff.get(FINAL_DMG), fd * u));
            double as = Json.num(m, "atkSpeed", 0);
            if (as != 0) eff.set(ATK_SPEED, diminish(eff.get(ATK_SPEED), as * scale * u, 150));
        }
        return new Buffed(eff, 1 + skillAtkSum / 100.0);
    }

    /** 버프 반영 1타 데미지 = perHit(주입 스탯) × 공격력 별도 버킷. */
    public double perHit(Skill s, Buffed bf, boolean boss) {
        return perHit(s, bf.stats, boss) * bf.skillAtkFactor;
    }

    /**
     * 예상 데미지 카드용: 현재 레벨에서 쓰는 최고 차수 평타의 버프 평균 반영 1타(정해진 타수 중 한 타) 기대 데미지.
     * 버프(다크사이트·아드레날린·퍼지 등)와 상시 패시브 공퍼(숙련된 표창술)를 가동률 평균으로 반영한다.
     * 공속·타수·받는피해 디버프는 한 타 값이 아니므로 제외(평타 한 대의 한 타 기준).
     */
    public double basicPerHit(StatBlock input, boolean boss) {
        Buffed bf = buffed(input);
        Skill basic = highestBasic(charLevel(bf.stats));
        // 받는 피해 증가 디버프(베놈 약화·퍼지 등)는 적이 받는 모든 피해를 키우므로 평타 1타에도 반영.
        // 출처 스킬을 끄면 그 디버프도 빠짐(recvDmgFactor가 skillOn으로 게이팅) → 카드에도 즉시 반영.
        return basic == null ? 0 : perHit(basic, bf, boss) * recvDmgFactor(bf.stats);
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

    // ──────────────────── 버프 편집기용 표시 정보 ────────────────────

    /**
     * 버프 편집기 목록용 표시 정보. 시간제 버프(active/passive) + 상시 패시브(always: statPassives·상시 버프)를
     * 모두 포함한다. 항목 = {id, name, open, group, locked, effect, cooldown, skillLevel}. 습득레벨 오름차순.
     */
    public List<Map<String, Object>> buffInfos(StatBlock input) {
        int cl = charLevel(input);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : data.buffs()) {
            boolean always = Json.bool(m, "always", false);
            String group = always ? "always" : (Json.str(m, "kind", "").equals("statPassive") ? "passive" : "active");
            out.add(buffInfo(m, input, cl, group));
        }
        for (Map<String, Object> m : data.statPassives()) {
            out.add(buffInfo(m, input, cl, "always"));   // 상시 패시브(숙련된 표창술 등)
        }
        for (Skill s : data.skills()) {
            if (Json.str(s.raw, "dual", "").isEmpty()) continue;   // 딜+버프/디버프 스킬은 버프 뷰에도 표시(같은 id=상태 공유)
            out.add(dualBuffEntry(s, input, cl));
        }
        out.sort((a, b) -> Integer.compare((int) a.get("open"), (int) b.get("open")));
        return out;
    }

    /** 한 버프/패시브의 표시 항목. */
    private Map<String, Object> buffInfo(Map<String, Object> m, StatBlock input, int cl, String group) {
        int open = (int) Json.num(m, "open", 0);
        int tier = (int) Json.num(m, "tier", 0);
        double sc = passiveScale(m, cl, input);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", Json.str(m, "id", ""));
        e.put("name", Json.str(m, "name", Json.str(m, "id", "")));
        e.put("open", open);
        e.put("group", group);
        e.put("locked", cl < open);
        e.put("effects", buffEffects(m, sc, cl));
        e.put("cooldown", buffCooldownText(m, input));
        if (tier > 0) {
            int levelDerived = model.baseSkillLevel(tier, cl);
            int bonus = (int) input.get(SKILL_LEVELS) + (int) input.get("SKILL_LEVEL_" + tier);
            e.put("skillLevel", bonus != 0 ? (levelDerived + " + " + bonus) : String.valueOf(levelDerived));
        } else {
            e.put("skillLevel", null);
        }
        return e;
    }

    /** 패시브/버프 스케일 = 고정이면 1, 아니면 kind(없으면 statPassive)의 상승비 × 총 스킬레벨. */
    private double passiveScale(Map<String, Object> m, int cl, StatBlock input) {
        if (Json.bool(m, "fixed", false)) return 1.0;
        String kind = Json.str(m, "kind", "");
        return scale(kind.isEmpty() ? "statPassive" : kind, (int) Json.num(m, "tier", 0), cl, input);
    }

    /** 효과(스케일·마스터리 반영) 항목 리스트 — 2개 이상이면 프론트에서 열로 나열. */
    private List<String> buffEffects(Map<String, Object> m, double sc, int cl) {
        List<String> p = new ArrayList<>();
        addEff(p, "공격력", Json.num(m, "atkPct", 0) * sc);
        addEff(p, "크리티컬 확률", Json.num(m, "critRate", 0) * sc);
        addEff(p, "크리티컬 데미지", Json.num(m, "critDmg", 0) * sc);
        addEff(p, "최종 데미지", Json.num(m, "finalDmg", 0) * sc);   // 마스터리(레벨별)는 표기 안 함 — 내부 계산엔 적용
        addEff(p, "공격 속도", Json.num(m, "atkSpeed", 0) * sc);
        addEff(p, "기본 공격 데미지", Json.num(m, "basicAtkDmg", 0) * sc);
        addEff(p, "최소 데미지", Json.num(m, "minMult", 0) * sc);
        addEff(p, "최대 데미지", Json.num(m, "maxMult", 0) * sc);
        addEff(p, "보스 데미지", Json.num(m, "bossDmg", 0) * sc);
        addEff(p, "스킬 데미지", Json.num(m, "skillDmg", 0) * sc);
        return p;
    }

    private static void addEff(List<String> parts, String label, double v) {
        if (v != 0) parts.add(label + " +" + num1(v) + "%");
    }

    /** 버프/패시브 '원함'(적용 대상) 여부. */
    private boolean wantsBuff(Map<String, Object> m, int cl) {
        return wants(Json.str(m, "id", ""), Json.num(m, "open", 0), cl);
    }

    /** 스킬/버프 사용 여부. activeBuffs=null이면 습득한 것만(기본), 아니면 체크된 것(미습득 체크 시 포함=과대평가). */
    private boolean wants(String id, double open, int cl) {
        if (activeBuffs == null) return cl >= open;
        return activeBuffs.contains(id);
    }

    /** 스킬이 켜져 있는지(디버프 등 부수효과 게이팅용). activeBuffs=null이면 켜짐(기본), 아니면 체크된 것만. */
    private boolean skillOn(String id) {
        return activeBuffs == null || activeBuffs.contains(id);
    }

    /** 입력 포함 패시브 보정: 원하는데 미습득이면 +효과(과대), 습득했는데 원치 않으면 -효과(없다 가정). 근사. */
    private void adjustPassive(StatBlock eff, Map<String, Object> m, double sc, int cl, boolean wanted, boolean learned) {
        int sign = (wanted && !learned) ? +1 : (!wanted && learned) ? -1 : 0;
        if (sign == 0) return;
        add(eff, ATK_SPEED, sign * Json.num(m, "atkSpeed", 0) * sc);
        add(eff, CRIT_RATE, sign * Json.num(m, "critRate", 0) * sc);
        add(eff, CRIT_DMG, sign * Json.num(m, "critDmg", 0) * sc);
        add(eff, FINAL_DMG, sign * (Json.num(m, "finalDmg", 0) * sc + levelSum(m, "finalDmgMasteryByLevel", cl)));
        add(eff, BASIC_ATK_DMG, sign * Json.num(m, "basicAtkDmg", 0) * sc);
        add(eff, MIN_DMG_MULT, sign * Json.num(m, "minMult", 0) * sc);
        add(eff, MAX_DMG_MULT, sign * Json.num(m, "maxMult", 0) * sc);
        add(eff, BOSS_DMG, sign * Json.num(m, "bossDmg", 0) * sc);
        add(eff, SKILL_DMG, sign * Json.num(m, "skillDmg", 0) * sc);
    }

    private static void add(StatBlock b, String id, double v) { if (v != 0) b.set(id, b.get(id) + v); }

    /** 쿨타임 표시 = 원래 쿨타임(-줄어든 쿨타임). 줄어든 양은 쿨감퍼·쿨감초(스탯)만 — 쿨감 마스터리는 표기 안 함(내부 계산엔 적용). */
    private static String cdText(double rawCd, StatBlock b) {
        double red = rawCd - SkillDpsModel.cooldown(rawCd, b.get(CDR_PCT), b.get(CDR_FLAT));
        if (red > 0.05) return num1(rawCd) + "초 (-" + num1(red) + "초)";
        return num1(rawCd) + "초";
    }

    /** 버프 쿨타임 표시. 발동형은 "N타마다", 쿨 없음/상시는 "상시". */
    private String buffCooldownText(Map<String, Object> m, StatBlock b) {
        if (m.containsKey("triggerCasts")) return "발동형 (" + (int) Json.num(m, "triggerCasts", 0) + "타마다)";
        double rawCd = Json.num(m, "cd", 0);
        return rawCd <= 0 ? "상시" : cdText(rawCd, b);
    }

    /** 실제 계산값 그대로 표기(인위적 반올림 없음). 부동소수 잡음만 소수 3자리에서 정리, 후행 0 제거. */
    private static String num1(double v) {
        double r = Math.round(v * 1000) / 1000.0;
        if (r == Math.rint(r)) return String.valueOf((long) Math.rint(r));   // 정수면 정수로
        return String.valueOf(r);   // Double.toString이 최단 표기(후행 0 없음) — 예: 23.94, 12.4
    }

    // ──────────────────── 딜 스킬 편집기용 표시 정보 ────────────────────

    /** 딜 스킬 목록(평타 제외). 항목 = {id,name,open,locked,group(active/passive),skillLevel,cooldown,effects,dual,also}. 습득레벨 오름차순. */
    public List<Map<String, Object>> dealSkillInfos(StatBlock input) {
        int cl = charLevel(input);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Skill s : data.skills()) {
            if (s.kind.equals("basic") || !s.kind.startsWith("deal")) continue;
            Map<String, Object> e = skillBaseEntry(s, input, cl);
            e.put("group", s.kind.equals("dealActive") ? "active" : "passive");
            e.put("effects", dealEffects(s, input));
            e.put("targets", targetsText(s, cl));
            out.add(e);
        }
        out.sort((a, b) -> Integer.compare((int) a.get("open"), (int) b.get("open")));
        return out;
    }

    /** 딜 스킬 공통 표시 필드(id·이름·습득레벨·스킬레벨·쿨타임·dual). 효과·그룹은 호출자가 채운다. */
    private Map<String, Object> skillBaseEntry(Skill s, StatBlock input, int cl) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", s.id);
        e.put("name", s.name);
        e.put("open", s.open);
        e.put("locked", cl < s.open);
        int levelDerived = model.baseSkillLevel(s.tier, cl);
        int bonus = (int) input.get(SKILL_LEVELS) + (int) input.get("SKILL_LEVEL_" + s.tier);
        e.put("skillLevel", bonus != 0 ? (levelDerived + " + " + bonus) : String.valueOf(levelDerived));
        e.put("cooldown", dealCooldownText(s, input));
        String dual = Json.str(s.raw, "dual", "");   // 딜+버프/디버프 동시 스킬만 데이터에 명시("디버프" 등)
        e.put("dual", !dual.isEmpty());
        e.put("also", dual.isEmpty() ? null : dual);
        return e;
    }

    /** 딜+버프/디버프 스킬의 '버프 뷰' 항목(부수효과 표시). 딜 뷰 항목과 같은 id → 체크 상태 공유. */
    private Map<String, Object> dualBuffEntry(Skill s, StatBlock input, int cl) {
        Map<String, Object> e = skillBaseEntry(s, input, cl);
        e.put("group", s.kind.equals("dealActive") ? "active" : "passive");
        List<String> eff = new ArrayList<>();
        for (Map<String, Object> d : data.debuffs())
            if (s.id.equals(Json.str(d, "source", ""))) addEff(eff, "받는 피해", Json.num(d, "recvDmg", 0));
        e.put("effects", eff);
        return e;
    }

    /** 딜 스킬 쿨타임 표시. cd 있으면 원래(-줄어든), period면 "N초 주기", 발동 확률형이면 "확률형 (N%)", 그 외 "패시브". */
    private String dealCooldownText(Skill s, StatBlock input) {
        if (s.has("cd") && s.num("cd", 0) > 0) return cdText(s.num("cd", 0), input);
        if (s.has("period")) return num1(s.num("period", 0)) + "초 주기";
        double proc = s.has("dot") ? Json.num(Json.asMap(s.raw.get("dot")), "proc", 0) : s.num("proc", 0);
        if (proc > 0) return "확률형 (" + num1(proc) + "%)";
        return "패시브";
    }

    /** 딜 스킬 피해량(스케일 반영, 마스터리 미표기). 본 피해(×실타수) + 지속 피해(베놈 도트·써든레이드 도트). */
    private List<String> dealEffects(Skill s, StatBlock input) {
        List<String> p = new ArrayList<>();
        int sl = skillLevel(s, input);
        if (s.has("base")) {
            double c = SkillDpsModel.coefficient(s.num("base", 0), data.growth(s.kind), sl);
            int hits = hitsPerCast(s, input);   // 다크 플레어=틱×타격 등 실제 타수
            p.add(num1(c) + "%" + (hits > 1 ? " × " + hits + "타" : ""));
        }
        if (s.has("dot")) {   // 베놈 등 연속 도트
            Map<String, Object> dot = Json.asMap(s.raw.get("dot"));
            p.add("지속 피해 초당 " + num1(SkillDpsModel.coefficient(Json.num(dot, "perSec", 0), data.growth(s.kind), sl)) + "%");
        }
        if (s.has("dotPerCast")) {   // 써든레이드: 1시전 지속 피해(틱당×틱수)
            Map<String, Object> d = Json.asMap(s.raw.get("dotPerCast"));
            double dotBase = Json.num(d, "perTick", 0) * Json.num(d, "ticks", 0);
            p.add("지속 피해 " + num1(SkillDpsModel.coefficient(dotBase, data.growth(s.kind), sl)) + "%");
        }
        return p;
    }

    /** 딜 스킬 대상 수(없으면 null). 마크는 나이트로드 강화 시 늘어난 대상수 반영. */
    private String targetsText(Skill s, int cl) {
        if (!s.has("targets") && !s.has("targetsBase")) return null;
        double t = s.num("targets", s.num("targetsBase", 1));
        if (cl >= s.num("nightlordOpen", Double.MAX_VALUE)) t = s.num("targetsNightlord", t);
        return num1(t) + "명";
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

    /** 베놈 중독 가동률(eff의 공속 기준). 발동확률 마스터리(98렙)는 레벨 게이팅. */
    private double venomUptimeOf(StatBlock eff) {
        Map<String, Object> dot = Json.asMap(data.skill("venom").raw.get("dot"));
        double p = Json.num(dot, "proc", 0);
        if (charLevel(eff) >= Json.num(dot, "procMasteryOpen", Double.MAX_VALUE)) p += Json.num(dot, "procMasteryPlus", 0);
        return SkillDpsModel.venomUptime(Json.num(dot, "dur", 0), p / 100.0, eff.get(ATK_SPEED));
    }

    /** 쉐도우 파트너 타격당 기대 추가(공격력 버킷 제외). 평타 외 딜이라 스킬뎀% 적용. */
    public double shadowExtraPerHit(StatBlock eff, boolean boss) {
        Skill s = data.skill("shadowPartner");
        if (s == null || !wants(s.id, s.open, charLevel(eff))) return 0;
        return dm.globalMultiplier(eff, boss) * dm.skillDmgFactor(eff) * coeffFrac(s, s.num("base", 0), eff)
                * (1 + levelSum(s.raw, "masteryDmgByLevel", charLevel(eff)) / 100.0) * meyongFactor(s.num("meyong", 0), eff)
                * (s.num("proc", 0) / 100.0);
    }

    /** 페이탈 베놈 타격당 기대 추가(중독 가동률 반영, 메용 비대상). 스킬뎀% 적용. */
    public double fatalExtraPerHit(StatBlock eff, boolean boss) {
        Skill s = data.skill("fatalVenom");
        if (s == null || !wants(s.id, s.open, charLevel(eff))) return 0;
        return dm.globalMultiplier(eff, boss) * dm.skillDmgFactor(eff) * coeffFrac(s, s.num("base", 0), eff)
                * (1 + levelSum(s.raw, "masteryDmgByLevel", charLevel(eff)) / 100.0) * (s.num("proc", 0) / 100.0) * venomUptimeOf(eff);
    }

    /** 베놈 도트 초당 데미지(공격력 버킷 제외). 스킬뎀% 적용. */
    public double venomDotDps(StatBlock eff, boolean boss) {
        Skill s = data.skill("venom");
        if (s == null || !wants(s.id, s.open, charLevel(eff))) return 0;
        Map<String, Object> dot = Json.asMap(s.raw.get("dot"));
        double coeff = SkillDpsModel.coefficient(Json.num(dot, "perSec", 0), data.growth(s.kind), skillLevel(s, eff)) / 100.0;
        return dm.globalMultiplier(eff, boss) * dm.skillDmgFactor(eff) * coeff * meyongFactor(s.num("meyong", 0), eff) * venomUptimeOf(eff);
    }

    /** 마크 오브 어쌔신 1회 발동 데미지(공격력 버킷 제외). 나이트로드 최종뎀+300%는 107렙 게이팅. */
    public double markPerHit(StatBlock eff, boolean boss) {
        Skill s = data.skill("markOfAssassin");
        if (s == null || !wants(s.id, s.open, charLevel(eff))) return 0;
        // 나이트로드 최종뎀(+300%)은 4차 패시브라 4차 스킬레벨로 0.4% 스케일.
        double nightlord = 1.0;
        if (charLevel(eff) >= s.num("nightlordOpen", Double.MAX_VALUE)) {
            int nlLv = model.totalSkillLevel(data.meyongTier(), charLevel(eff),
                    (int) eff.get(SKILL_LEVELS), (int) eff.get("SKILL_LEVEL_" + data.meyongTier()));
            double scaledFinal = s.num("finalNightlord", 0) * (1 + 0.004 * nlLv);
            nightlord = 1 + scaledFinal / 100.0;
        }
        return dm.globalMultiplier(eff, boss) * dm.skillDmgFactor(eff) * coeffFrac(s, s.num("base", 0), eff)
                * (1 + levelSum(s.raw, "masteryDmgByLevel", charLevel(eff)) / 100.0) * nightlord;
    }

    /** 써든레이드 지속피해 1시전 데미지(공격력 버킷 제외). 스킬뎀% 적용. */
    public double suddenDotPerCast(StatBlock eff, boolean boss) {
        Skill s = data.skill("suddenRaid");
        Map<String, Object> dot = Json.asMap(s.raw.get("dotPerCast"));
        double dotBase = Json.num(dot, "perTick", 0) * Json.num(dot, "ticks", 0);
        double coeff = SkillDpsModel.coefficient(dotBase, data.growth(s.kind), skillLevel(s, eff)) / 100.0;
        return dm.globalMultiplier(eff, boss) * dm.skillDmgFactor(eff) * coeff * (1 + levelSum(s.raw, "masteryDmgByLevel", charLevel(eff)) / 100.0);
    }

    /** 받는 피해 증가 디버프 배수 = 1 + Σ(받는피해% × 가동률). 같은 종류 합연산. */
    public double recvDmgFactor(StatBlock eff) {
        int cl = charLevel(eff);
        double sum = 0;
        for (Map<String, Object> d : data.debuffs()) {
            if (cl < Json.num(d, "open", 0)) continue;
            String src = Json.str(d, "source", "");
            if (!src.isEmpty() && !skillOn(src)) continue;   // 출처 딜/버프 스킬을 끄면 그 디버프도 빠짐(동기화)
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
            // 퍼지는 ignoreBuffDuration(설치기) → 버프 지속% 미적용.
            double bd = Json.bool(m, "ignoreBuffDuration", false) ? 0 : eff.get(BUFF_DURATION);
            double dur = (Json.num(m, "dur", 0) + levelSum(m, "durMasteryFlatByLevel", charLevel(eff)))
                    * (1 + levelSum(m, "durMasteryPctByLevel", charLevel(eff)) / 100.0) * (1 + bd / 100.0);
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

    /** 한 시전당 액티브 타격수(다크플레어 틱·타격 마스터리 레벨 게이팅 포함). */
    private int hitsPerCast(Skill s, StatBlock eff) {
        if (s.id.equals("darkFlare")) {
            int cl = charLevel(eff);
            int ticks = (int) (s.num("ticks", 1) + levelSum(s.raw, "ticksPlus", cl));
            int perTick = (int) (s.num("hitsPerTick", 1) + levelSum(s.raw, "hitsPerTickPlus", cl));
            return ticks * perTick;
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
        // 액션 이코노미: 평타·액티브는 한 타임라인을 공유(시전속도 ≈ 공속). 초당 슬롯 R = 1 + 공속%.
        // 액티브 1회 시전 = 1슬롯, 평타는 액티브가 쓰고 남은 슬롯만큼만 나감. 버프(민첩·다크사이트)는 오프-GCD.
        double slotsPerSec = 1 + eff.get(ATK_SPEED) / 100.0;

        double baseDps = 0;          // 코어(평타+액티브+마크+써든도트), 추가타 제외
        double procActionsPerSec = 0; // 쉐파/페이탈 발동 횟수(시전당 판정, 타수 무관, 대상수 가중)
        double skillActionsPerSec = 0;   // 액티브가 먹는 슬롯

        for (Skill s : data.skills()) {
            if (!s.kind.equals("dealActive")) continue;      // 평타/패시브/마크는 별도
            if (!wants(s.id, s.open, cl)) continue;          // 미사용(체크 해제) 딜 스킬 제외. 미습득 체크 시 적용=과대
            double cd = cooldown(s, eff);
            if (cd <= 0) continue;
            skillActionsPerSec += 1.0 / cd;   // 시전 1회 = 평타 1슬롯
            int hits = hitsPerCast(s, eff);
            double targets = boss ? 1 : s.num("targets", 1);
            baseDps += perHit(s, bf, boss) * hits * targets / cd;
            // 발동은 시전(공격)당 1회 — 단 다크 플레어(설치기)는 틱마다 별도 공격 판정(소환수 취급).
            double procAttempts = s.id.equals("darkFlare")
                    ? s.num("ticks", 1) + levelSum(s.raw, "ticksPlus", cl) : 1;
            procActionsPerSec += procAttempts * targets / cd;
            if (s.id.equals("suddenRaid")) {
                baseDps += suddenDotPerCast(eff, boss) * atkF * targets / cd;   // 도트는 타수 아님
            }
        }

        // 평타: 액티브가 쓰고 남은 슬롯만큼만.
        double basicActionsPerSec = Math.max(0, slotsPerSec - skillActionsPerSec);
        Skill basic = highestBasic(cl);
        if (basic != null) {
            int hits = basicHits(basic, eff);
            double targets = boss ? 1 : basicTargets(basic, eff);
            baseDps += perHit(basic, bf, boss) * hits * targets * basicActionsPerSec;
            procActionsPerSec += targets * basicActionsPerSec;   // 발동은 시전당(타수 무관)
        }

        // 마크: 기존 타격에 얹히는 보너스(새 타수 아님). 주기 = period − 주기 마스터리(레벨 게이팅).
        Skill mark = data.skill("markOfAssassin");
        if (mark != null && wants("markOfAssassin", mark.open, cl)) {
            double period = mark.num("period", 5) - levelSum(mark.raw, "periodMasteryByLevel", cl);
            boolean nl = cl >= mark.num("nightlordOpen", Double.MAX_VALUE);
            double targets = boss ? 1 : (nl ? mark.num("targetsNightlord", mark.num("targetsBase", 1)) : mark.num("targetsBase", 1));
            if (period > 0) baseDps += markPerHit(eff, boss) * atkF * targets / period;
        }

        // 추가타(쉐파+페이탈): 시전(공격)당 발동 — 1차든 4차든 발동 확률 동일(타수 무관).
        double procPerProc = (shadowExtraPerHit(eff, boss) + fatalExtraPerHit(eff, boss)) * atkF;
        double procDps = procActionsPerSec * procPerProc;

        // 베놈 도트(연속): 타수 아님.
        double venomDps = venomDotDps(eff, boss) * atkF;

        return (baseDps + procDps + venomDps) * recvDmgFactor(eff);
    }

    /**
     * delta 만큼 스탯을 더했을 때의 종합 DPS 증가율(0.05 == +5%).
     * 회색 스탯(쿨감·공속·스킬레벨·대상수 등)을 DPS로 환산하는 자리.
     * 스탯 합성은 기존 {@link DamageModel#combine}(SUM/DIMINISHING/주스탯 전파) 규칙을 그대로 쓴다.
     */
    public double increase(StatBlock current, StatBlock delta, boolean boss) {
        double before = totalDps(current, boss);
        if (before <= 0) return 0;
        double after = totalDps(dm.combine(current, delta), boss);
        return after / before - 1.0;
    }

    /** 버프/스탯패시브 효과의 스킬레벨 스케일 = 1 + 상승비%/100 × 총 스킬레벨. */
    private double scale(String kind, int tier, int charLevel, StatBlock input) {
        int lv = model.totalSkillLevel(tier, charLevel, (int) input.get(SKILL_LEVELS), (int) input.get("SKILL_LEVEL_" + tier));
        return 1 + data.growth(kind) / 100.0 * lv;
    }

    /** 버프 가동률: triggerCasts 있으면 발동형, 아니면 쿨 기반. 지속 마스터리·버프지속% 레벨 게이팅. */
    private double buffUptime(Map<String, Object> m, double atkSpeedBase, double buffDurPct, int cl) {
        double bd = Json.bool(m, "ignoreBuffDuration", false) ? 0 : buffDurPct;   // 퍼지(설치기)는 버프지속% 무시
        double dur = (Json.num(m, "dur", 0) + levelSum(m, "durMasteryFlatByLevel", cl))
                * (1 + levelSum(m, "durMasteryPctByLevel", cl) / 100.0) * (1 + bd / 100.0);
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

    /** {"136":1} 형태의 정수 레벨 보정 합. */
    private static int sumLevelPlus(Skill s, String field, int charLevel) {
        return (int) levelSum(s.raw, field, charLevel);
    }

    /** {레벨:값} 맵에서 캐릭터 레벨 ≥ 키인 값들의 합(레벨 게이팅). 없으면 0. */
    private static double levelSum(Map<String, Object> raw, String field, int charLevel) {
        Object o = raw.get(field);
        if (!(o instanceof Map)) return 0;
        double sum = 0;
        for (Map.Entry<String, Object> e : Json.asMap(o).entrySet()) {
            if (charLevel >= Integer.parseInt(e.getKey())) sum += ((Number) e.getValue()).doubleValue();
        }
        return sum;
    }
}
