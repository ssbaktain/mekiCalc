package mekicalc.engine.sim2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mekicalc.engine.skill.SkillDpsModel;
import mekicalc.json.Json;
import mekicalc.model.StatBlock;

/**
 * skills_v2 → {@link SkillPlan2} 변환(재설계판).
 *
 * <p>기존 {@code SkillEngineImpl} 대비 수정점:
 * <ul>
 *   <li><b>선반영 필터 수정</b>: "무한 지속"이 아니라 "무조건 상시(OnStart + 무한 + 무스택)"만
 *       스탯창 선반영으로 간주해 제외한다. 씨 서펜트 버스트(무한 지속이지만 스택 조건부)류의
 *       오삭제를 막는다.</li>
 *   <li><b>발동 조건 보존</b>: trigger.counterEvery/counterOn/requireState, buff.exclusiveGroup,
 *       스택(state+stateMax)을 {@link Trigger2}/{@link BuffSpec2}로 그대로 나른다.</li>
 *   <li><b>조용한 강등 금지</b>: 함수형 값·cd=0 액티브 등은 {@link Warn}으로 기록한다.</li>
 * </ul>
 * 계수 성장·쿨감·마스터리 합산·skillBoost·소환 가동률은 검증된 기존 로직을 계승한다.
 */
public final class SkillEngine2 {

    private static final String LEVEL = "LEVEL";
    private static final String CDR_PCT = "COOLDOWN_REDUCTION_PCT";
    private static final String CDR_FLAT = "COOLDOWN_REDUCTION";
    private static final String BASIC_TARGETS = "BASIC_ATK_TARGETS";
    private static final int AREA_TARGET_CAP = 999;

    /** 변환 결과: 플랜 + 데이터 계층 경고. */
    public record PlanResult(List<SkillPlan2> plans, List<Warn> warnings) {}

    private final List<Map<String, Object>> all;
    private final Map<String, Map<String, Object>> byId = new HashMap<>();
    private final int creatureIndex;

    @SuppressWarnings("unchecked")
    public SkillEngine2(List<Object> skillsJson, int creatureIndex) {
        this.all = new ArrayList<>();
        for (Object o : skillsJson) {
            Map<String, Object> m = Json.asMap(o);
            all.add(m);
            byId.put(Json.str(m, "index"), m);
        }
        this.creatureIndex = creatureIndex;
    }

    /**
     * 플랜 산출. basicSkillId=평타(공속 주기), activeSkillIds=명시 사용 액티브,
     * 습득 패시브(type=passive, open≤레벨)는 자동 포함(기존 SkillEngineImpl 계약과 동일).
     */
    public PlanResult plan(StatBlock stats, String basicSkillId, List<String> activeSkillIds) {
        List<Warn> warns = new ArrayList<>();
        List<SkillPlan2> out = new ArrayList<>();
        int cl = (int) stats.get(LEVEL);
        Map<String, Double> boost = collectBoost(stats);
        Map<String, Object> b = byId.get(basicSkillId);
        if (b != null) out.add(build(b, stats, Kind2.BASIC, boost, warns));
        for (String id : activeSkillIds) {
            Map<String, Object> s = byId.get(id);
            if (s != null) out.add(build(s, stats, Kind2.ACTIVE, boost, warns));
        }
        for (Map<String, Object> s : all) {
            if (!"passive".equals(s.get("type"))) continue;
            Integer op = openLv(s.get("open"));
            if (op == null || cl < op) continue;
            SkillPlan2 p = build(s, stats, Kind2.ACTIVE, boost, warns);
            if (p.cast() != null || !p.grants().isEmpty()) out.add(p);
        }
        return new PlanResult(dropPreReflected(out), warns);
    }

    /**
     * 상시 패시브 선반영 제외(사용자 스탯창 규약) — 수정판.
     * 제외 조건: PLAYER 대상 ∧ 무한 지속 ∧ <b>스택 아님</b> ∧ <b>발동이 OnStart(무조건 상시)</b>.
     * 시간제·proc·타이머·스택 조건부(씨 서펜트 버스트)는 스탯창에 못 들어가므로 전부 유지.
     * 유지 스탯: SKILL_ATK_PCT·MAIN_STAT_PCT(스탯창 미포함 규약).
     */
    private List<SkillPlan2> dropPreReflected(List<SkillPlan2> plans) {
        List<SkillPlan2> out = new ArrayList<>();
        for (SkillPlan2 p : plans) {
            boolean uncondPassive = p.trigger().kind() == TriggerKind2.PASSIVE;
            if (!uncondPassive) { out.add(p); continue; }
            List<BuffSpec2> kept = new ArrayList<>();
            boolean changed = false;
            for (BuffSpec2 bs : p.grants()) {
                boolean uncondInfinite = bs.target() == Target2.PLAYER
                        && Double.isInfinite(bs.durationSec()) && bs.stackState() == null;
                if (!uncondInfinite) { kept.add(bs); continue; }
                Map<String, Double> keep = new HashMap<>();
                for (Map.Entry<String, Double> e : bs.effects().entrySet())
                    if ("SKILL_ATK_PCT".equals(e.getKey()) || "MAIN_STAT_PCT".equals(e.getKey()))
                        keep.put(e.getKey(), e.getValue());
                if (keep.size() != bs.effects().size()) changed = true;
                if (!keep.isEmpty())
                    kept.add(new BuffSpec2(bs.target(), keep, bs.durationSec(), null, 0, null,
                            bs.grantsState(), bs.sourceSkill(), bs.sourceName()));
            }
            out.add(changed
                    ? new SkillPlan2(p.id(), p.name(), p.kind(), p.cooldownSec(), p.trigger(), p.cast(), kept)
                    : p);
        }
        return out;
    }

    private SkillPlan2 build(Map<String, Object> s, StatBlock st, Kind2 kind,
                             Map<String, Double> boost, List<Warn> warns) {
        int cl = (int) st.get(LEVEL);
        int lv = effLevel(s, st);
        Cast2 cast = buildCast(s, st, kind, cl, lv, boost, warns);
        double cd = Json.num(s, "cd", 0);
        double cdrPct = merge(st.get(CDR_PCT), masterySum(s, cl, "cdrPct"), 100);
        double effCd = SkillDpsModel.cooldown(cd, cdrPct, st.get(CDR_FLAT) + crossCdFlatSec(Json.str(s, "index"), cl));
        List<BuffSpec2> grants = buildGrants(s, cl, lv, warns);
        return new SkillPlan2(Json.str(s, "index"), Json.str(s, "name"), kind, effCd,
                buildTrigger(s, kind), cast, grants);
    }

    private Trigger2 buildTrigger(Map<String, Object> s, Kind2 kind) {
        if (kind == Kind2.BASIC) return Trigger2.ACTIVE;
        Map<String, Object> tg = Json.asMap(s.get("trigger"));
        if (tg == null) return Trigger2.ACTIVE;
        TriggerKind2 tk = switch (Json.str(tg, "type", "None")) {
            case "OnStart" -> TriggerKind2.PASSIVE;
            case "OnTimer" -> TriggerKind2.ON_TIMER;
            case "OnAttack", "OnHit" -> TriggerKind2.ON_ATTACK;
            case "OnDamage" -> TriggerKind2.ON_DAMAGE;
            case "OnActiveSkill" -> TriggerKind2.ON_ACTIVE_SKILL;
            case "OnState" -> TriggerKind2.ON_STATE;
            default -> TriggerKind2.ACTIVE;
        };
        double proc = Json.num(tg, "procPct", 100);
        double periodSec = tg.containsKey("periodMs") ? Json.num(tg, "periodMs", 0) / 1000.0 : 0;
        boolean fast = Json.bool(tg, "fastByAtkSpeed", false);
        int counterEvery = (int) Json.num(tg, "counterEvery", 0);       // 확장 필드(SCHEMA 패치)
        String counterOn = Json.str(tg, "counterOn", null);
        String requireState = Json.str(tg, "requireState", null);
        boolean consumeRequire = Json.bool(tg, "consumeRequire", false);
        return new Trigger2(tk, proc, periodSec, fast, counterEvery, counterOn, requireState, consumeRequire);
    }

    private Cast2 buildCast(Map<String, Object> s, StatBlock st, Kind2 kind, int cl, int lv,
                            Map<String, Double> boost, List<Warn> warns) {
        List<Object> dmgArr = Json.asList(s.get("damage"));
        Map<String, Object> summon = null;
        Map<String, Object> src;
        if (dmgArr != null && !dmgArr.isEmpty()) {
            // 조건 없는 다중 엔트리는 아래 buildCast에서 합산 처리(완료). 조건부(bossOnly/State배타)만 아직 미반영 → Warn.
            if (dmgArr.size() > 1 && dmgArr.stream().anyMatch(e -> hasDmgCond(Json.asMap(e))))
                warns.add(new Warn("MULTI_DAMAGE_COND", Json.str(s, "name"),
                        "조건부 damage 엔트리(보스추가타/State배타) 미반영(과소·근사) — bossExtra·requireState 처리 필요."));
            src = Json.asMap(dmgArr.get(0));
        } else {
            Object sm = s.get("summon");
            if (sm == null) return null;
            summon = Json.asMap(sm);
            Object sd = summon.get("dmg");
            if (sd == null) return null;
            src = Json.asMap(sd);
        }
        double base = parseNum(String.valueOf(src.get("coeff")));
        if (Double.isNaN(base)) {
            warns.add(new Warn("FUNC_COEFF_SKIPPED", Json.str(s, "name"),
                    "함수형 계수(" + src.get("coeff") + ") 해석 불가 → 이 딜 0 처리(과소 방향). 리버싱/실측 필요."));
            return null;
        }
        double coeffPct = SkillDpsModel.coefficient(base, Json.num(src, "growth", 0), lv);
        double dmgMasteryPct = masterySum(s, cl, "dmgPct");
        double h = parseNum(Json.str(src, "hits", "1"));
        int hits = (Double.isNaN(h) ? 1 : (int) h) + (int) masterySum(s, cl, "hitPlus")
                + (int) masterySum(s, cl, "summonFirePlus");

        int targets;
        Dot2 dot = null;
        if (summon != null) {
            double mt = parseNum(String.valueOf(summon.get("maxTarget")));
            targets = (Double.isNaN(mt) ? 1 : (int) mt) + (int) masterySum(s, cl, "summonTargetPlus");
            double durMs = parseNum(String.valueOf(summon.get("durationMs")));
            double tickMs = parseNum(String.valueOf(summon.get("tickMs")));
            double tickCut = masterySum(s, cl, "tickPct") + masterySum(s, cl, "summonTickMs");
            if (tickCut > 0 && tickCut < 100) tickMs *= (1 - tickCut / 100.0);
            if (!Double.isNaN(tickMs) && tickMs > 0 && durMs > tickMs)
                dot = new Dot2(tickMs / 1000.0, durMs / 1000.0);
        } else {
            Object tc = s.get("targetCount");
            int baseTargets = (tc instanceof Number n) ? n.intValue() : 1;
            targets = baseTargets + (int) masterySum(s, cl, "targetPlus");
            if (kind == Kind2.BASIC || "basic".equals(tc)) targets += (int) st.get(BASIC_TARGETS);
            else if (Json.bool(s, "area", false)) targets = Math.max(targets, AREA_TARGET_CAP);
            double durMs = parseNum(String.valueOf(src.get("dotDurMs")));
            double tickMs = parseNum(String.valueOf(src.get("dotTickMs")));
            if (!Double.isNaN(durMs) && !Double.isNaN(tickMs) && tickMs > 0 && durMs > tickMs)
                dot = new Dot2(tickMs / 1000.0, durMs / 1000.0);
        }
        double boostPct = boost.getOrDefault(Json.str(s, "index"), 0.0);
        if (summon != null) {
            Object child = summon.get("child");
            if (child != null) boostPct += boost.getOrDefault(String.valueOf(child), 0.0);
        }
        Object dealChild = s.get("dealChild");
        if (dealChild != null) boostPct += boost.getOrDefault(String.valueOf(dealChild), 0.0);
        DmgTag2 tag = (kind == Kind2.BASIC) ? DmgTag2.BASIC : DmgTag2.SKILL;
        double up = summonUptime(s, st, cl);
        double bossB = masteryStatAdd(s, cl, "BOSS_DMG") + Json.num(src, "bossDmg", 0);
        double normB = masteryStatAdd(s, cl, "NORMAL_DMG") + Json.num(src, "normalDmg", 0);
        // 다중 damage 합산(§3): 첫것 조건 없으면, 조건 없는 추가 엔트리를 계수×타수로 합산(주+부 타격, 디바인스티그마 460×6+400×4).
        // 조건부(bossOnly/requireState/requireStateNot)는 별도 처리 필요 → 이번 합산 대상 아님(Warn 유지).
        double prod = coeffPct / 100.0 * hits;
        int finalHits = hits;
        if (dmgArr != null && dmgArr.size() > 1 && !hasDmgCond(src)) {
            for (int i = 1; i < dmgArr.size(); i++) {
                Map<String, Object> e = Json.asMap(dmgArr.get(i));
                if (hasDmgCond(e)) continue;
                double eb = parseNum(String.valueOf(e.get("coeff")));
                if (Double.isNaN(eb)) continue;
                double eh = parseNum(Json.str(e, "hits", "1"));
                prod += SkillDpsModel.coefficient(eb, Json.num(e, "growth", 0), lv) / 100.0
                        * (Double.isNaN(eh) ? 1 : (int) eh);
                finalHits = 1;   // 합산 발생 → coeffFrac에 전부 담고 hitCount=1
            }
        }
        // 합산(finalHits=1): frac=계수분율×타수 합(hitCount=1). 단일: frac=계수분율만(hitCount=hits가 hitDamage서 곱).
        double frac = (finalHits == 1 ? prod : coeffPct / 100.0) * (1 + boostPct / 100.0) * up;
        return new Cast2(frac, finalHits, targets, tag, dmgMasteryPct, bossB, normB, dot);
    }

    /** damage 엔트리에 발동 조건(bossOnly/requireState/requireStateNot)이 있으면 true(합산 대상 아님). */
    private static boolean hasDmgCond(Map<String, Object> m) {
        return m.get("bossOnly") != null || m.get("requireState") != null || m.get("requireStateNot") != null;
    }

    /** 버프/디버프 → BuffSpec2(스택·배타그룹 보존). 함수형 값은 Warn. */
    private List<BuffSpec2> buildGrants(Map<String, Object> s, int cl, int lv, List<Warn> warns) {
        List<BuffSpec2> out = new ArrayList<>();
        Map<String, Integer> stackPlus = collectStackPlus(cl);
        addGrants(out, s, "buff", Target2.PLAYER, cl, lv, stackPlus, warns);
        addGrants(out, s, "debuff", Target2.ENEMY, cl, lv, stackPlus, warns);
        addGrants(out, s, "indirect", Target2.PLAYER, cl, lv, stackPlus, warns);
        double fdm = masterySum(s, cl, "finalDmgPct");
        if (fdm != 0) out.add(new BuffSpec2(Target2.PLAYER, Map.of("FINAL_DMG", fdm),
                Double.POSITIVE_INFINITY, null, 0, null, null,
                Json.str(s, "index"), Json.str(s, "name") + "(마스터리)"));
        return out;
    }

    private void addGrants(List<BuffSpec2> out, Map<String, Object> s, String key, Target2 tgt,
                           int cl, int lv, Map<String, Integer> stackPlus, List<Warn> warns) {
        List<Object> arr = Json.asList(s.get(key));
        if (arr == null) return;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object o : arr) {
            Map<String, Object> b = Json.asMap(o);
            String cltStat = Json.str(b, "stat");
            String sid = StatNameMap.id(cltStat);
            if (sid == null) continue;
            double raw = parseNum(Json.str(b, "val"));
            if (Double.isNaN(raw)) {
                warns.add(new Warn("FUNC_VALUE_SKIPPED", Json.str(s, "name"),
                        cltStat + " 버프값이 함수형(" + Json.str(b, "val") + ") → 0 처리(과소 방향). 근사식/리버싱 필요."));
                continue;
            }
            double amount = StatNameMap.toPct(SkillDpsModel.coefficient(raw, Json.num(b, "growth", 0), lv));
            amount *= 1 + masteryRatioValue(s, cl, cltStat) / 100.0;
            String state = Json.str(b, "state", null);
            Object smO = b.get("stateMax");
            String stackState = null;
            int stackMax = 0;
            if (smO instanceof Number sm) {   // 스택형: 수치는 "스택당", 상한 = stateMax + stackPlus 마스터리
                stackState = state;
                stackMax = sm.intValue() + stackPlus.getOrDefault(state == null ? "" : state, 0);
            }
            String group = Json.str(b, "exclusiveGroup", null);   // 확장 필드(SCHEMA 패치)
            String grantsState = (stackState == null) ? state : null;   // 비스택 버프의 State 존재 등록용
            String dupKey = sid + "|" + Json.str(b, "val") + "|" + state + "|" + b.get("durationMs");
            if (!seen.add(dupKey)) {   // 동일 (스탯·값·State·지속) 중복 엔트리 = 구간 강화판 중복 추정 → 1개만
                warns.add(new Warn("DUPLICATE_BUFF_ENTRY", Json.str(s, "name"),
                        cltStat + " 동일 버프 엔트리 중복 → 1개만 반영(클뜯 구간 강화판 추정, SCHEMA 확인 필요)."));
                continue;
            }
            out.add(new BuffSpec2(tgt, Map.of(sid, amount), durSec(b), stackState, stackMax, group,
                    grantsState, Json.str(s, "index"), Json.str(s, "name")));
        }
    }

    // ---- 이하 검증된 기존 로직 계승(collectBoost/collectStackPlus/effLevel/mastery/summonUptime) ----

    private Map<String, Double> collectBoost(StatBlock st) {
        int cl = (int) st.get(LEVEL);
        String meyongId = (creatureIndex * 10000 + 4100) + "";
        Map<String, Double> m = new HashMap<>();
        for (Map<String, Object> s : all) {
            Integer op = openLv(s.get("open"));
            if (op == null || cl < op) continue;
            List<Object> bl = Json.asList(s.get("skillBoost"));
            if (bl == null) continue;
            double mult = 1.0;
            if (meyongId.equals(s.get("index"))) {
                int lv = effLevel(s, st);
                mult = 1 + 0.05 * Math.min(lv, 120) + 0.01 * Math.max(0, lv - 120);
            }
            for (Object o : bl) {
                Map<String, Object> bt = Json.asMap(o);
                if (!"FinalDamage".equals(Json.str(bt, "stat"))) continue;
                double v = parseNum(Json.str(bt, "value"));
                if (Double.isNaN(v)) continue;
                double val = meyongId.equals(s.get("index"))
                        ? StatNameMap.toPct(v) * mult
                        : StatNameMap.toPct(SkillDpsModel.coefficient(v, Json.num(bt, "growth", 0), effLevel(s, st)));
                val *= 1 + masterySum(s, cl, "ratioBoost") / 100.0;
                m.merge(Json.str(bt, "targetSkill"), val, Double::sum);
            }
        }
        return m;
    }

    private Map<String, Integer> collectStackPlus(int cl) {
        Map<String, Integer> m = new HashMap<>();
        for (Map<String, Object> s : all) {
            Integer op = openLv(s.get("open"));
            if (op == null || cl < op) continue;
            List<Object> ms = Json.asList(s.get("masteries"));
            if (ms == null) continue;
            for (Object o : ms) {
                Map<String, Object> mm = Json.asMap(o);
                if (!"stackPlus".equals(mm.get("type"))) continue;
                Integer mo = openLv(mm.get("open"));
                if (mo != null && cl >= mo)
                    m.merge(Json.str(mm, "state", ""), (int) Json.num(mm, "value", 0), Integer::sum);
            }
        }
        return m;
    }

    private double summonUptime(Map<String, Object> s, StatBlock st, int cl) {
        Object ss = s.get("summonSkill");
        if (ss == null) return 1.0;
        Map<String, Object> sm = byId.get(String.valueOf(ss));
        if (sm == null) return 1.0;
        double scd = Json.num(sm, "cd", 0);
        double durSec = Json.num(s, "summonDurMs", 0) / 1000.0;
        if (scd <= 0 || durSec <= 0) return 1.0;
        double scdr = merge(st.get(CDR_PCT), masterySum(sm, cl, "cdrPct"), 100);
        double seffCd = SkillDpsModel.cooldown(scd, scdr, st.get(CDR_FLAT));
        return seffCd > 0 ? Math.min(1.0, durSec / seffCd) : 1.0;
    }

    private double masteryStatAdd(Map<String, Object> s, int cl, String engineStat) {
        double sum = 0;
        List<Object> ms = Json.asList(s.get("masteries"));
        if (ms != null) for (Object o : ms) {
            Map<String, Object> m = Json.asMap(o);
            Integer op = openLv(m.get("open"));
            if (op != null && cl >= op && "statAdd".equals(Json.str(m, "type", ""))
                    && engineStat.equals(StatNameMap.id(Json.str(m, "stat", ""))))
                sum += Json.num(m, "value", 0);
        }
        return sum;
    }

    private double masteryRatioValue(Map<String, Object> s, int cl, String cltStat) {
        double sum = 0;
        List<Object> ms = Json.asList(s.get("masteries"));
        if (ms != null) for (Object o : ms) {
            Map<String, Object> m = Json.asMap(o);
            Integer op = openLv(m.get("open"));
            if (op != null && cl >= op && "ratioValue".equals(Json.str(m, "type", ""))
                    && cltStat.equals(Json.str(m, "stat", ""))) sum += Json.num(m, "value", 0);
        }
        return sum;
    }

    private double crossCdFlatSec(String id, int cl) {
        double sec = 0;
        for (Map<String, Object> other : all) {
            List<Object> ms = Json.asList(other.get("masteries"));
            if (ms == null) continue;
            for (Object o : ms) {
                Map<String, Object> m = Json.asMap(o);
                Integer op = openLv(m.get("open"));
                if (op != null && cl >= op && "cdFlatMs".equals(Json.str(m, "type", ""))
                        && id.equals(Json.str(m, "targetSkill", "")))
                    sec += -Json.num(m, "value", 0) / 1000.0;
            }
        }
        return sec;
    }

    private int effLevel(Map<String, Object> s, StatBlock st) {
        int cl = (int) st.get(LEVEL);
        Integer open = openLv(s.get("open"));
        int tier = (open != null) ? tierOf(open) : tierOf(cl);
        int start, next;
        switch (tier) {
            case 4 -> { start = 100; next = Integer.MAX_VALUE; }
            case 3 -> { start = 60;  next = 100; }
            case 2 -> { start = 30;  next = 60; }
            default -> { start = 10; next = 30; }
        }
        int base = Math.max(0, Math.min(cl, next) - start) * 3;
        return (int) (base + st.get("SKILL_LEVELS") + st.get("SKILL_LEVEL_" + tier));
    }

    private static int tierOf(int lv) {
        return lv >= 100 ? 4 : lv >= 60 ? 3 : lv >= 30 ? 2 : 1;
    }

    private static Integer openLv(Object o) {
        return (o == null) ? null : (int) ((Number) o).doubleValue();
    }

    private static double masterySum(Map<String, Object> s, int cl, String type) {
        double sum = 0;
        List<Object> ms = Json.asList(s.get("masteries"));
        if (ms != null) for (Object o : ms) {
            Map<String, Object> m = Json.asMap(o);
            Integer op = openLv(m.get("open"));
            if (op != null && cl >= op && type.equals(Json.str(m, "type", ""))) sum += Json.num(m, "value", 0);
        }
        return sum;
    }

    private static double durSec(Map<String, Object> b) {
        Object d = b.get("durationMs");
        if (d == null) return Double.POSITIVE_INFINITY;
        double ms = parseNum(String.valueOf(d));
        return (Double.isNaN(ms) || ms >= 99999999) ? Double.POSITIVE_INFINITY : ms / 1000.0;
    }

    private static double merge(double cur, double add, double max) {
        return add == 0 ? cur : cur + (max - cur) * (add / max);
    }

    private static double parseNum(String s) {
        if (s == null) return Double.NaN;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }
}
