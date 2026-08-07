package mekicalc.engine.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;
import mekicalc.model.StatBlock;

/**
 * 동료 엔진 — companions.json에서 선택 동료(등급)의 {@link SummonPlan}을 산출한다.
 * 동료 스킬(deal/dot/buffs/debuffs)을 {@link SkillPlan}으로 변환하고, 등급·레벨 기반 계승비율을 계산한다.
 *
 * <p>버프 대상(target): player→PLAYER, companion→COMPANION, both→PLAYER+COMPANION, enemy→ENEMY.
 * {@code used=true}(딜 반영)인 효과만 변환한다. 계수는 소환 시점 계승 스탯 기준이라 불변.
 */
public final class CompanionEngine {

    private final Map<String, Object> root;

    public CompanionEngine(String companionsJson) {
        this.root = Json.asMap(Json.parse(companionsJson));
    }

    /** 선택 동료(id)의 등급 기준 SummonPlan. 없으면 null. */
    public SummonPlan plan(String companionId, String grade, StatBlock player) {
        Map<String, Object> comp = findCompanion(companionId);
        if (comp == null) return null;
        Map<String, Object> grades = Json.asMap(comp.get("grades"));
        Map<String, Object> gd = grades != null ? Json.asMap(grades.get(grade)) : null;
        if (gd == null) return null;

        List<SkillPlan> skills = new ArrayList<>();
        List<Object> sk = Json.asList(gd.get("skills"));
        int i = 0;
        if (sk != null) for (Object o : sk) {
            SkillPlan p = toSkillPlan(Json.asMap(o), companionId + "#" + (i++), Json.str(comp, "name", companionId));
            if (p != null) skills.add(p);
        }
        double dur = Json.num(root, "summonDuration", 30);
        double resummonCd = Json.num(root, "summonCd", 90);
        return new SummonPlan(companionId, 0, inheritRatio(grade, player), dur, resummonCd, skills);
    }

    /** 계승비율% = base[grade] × min(1, 레벨/tierLevel[grade]), 90% 상한(넥슨). */
    public double inheritRatio(String grade, StatBlock player) {
        double base = Json.num(Json.asMap(root.get("inheritBaseRatio")), grade, 0);
        double tierLv = Json.num(Json.asMap(root.get("gradeTierLevel")), grade, 1);
        double ratio = base * Math.min(1.0, player.get("LEVEL") / Math.max(1, tierLv));
        return Math.min(90, ratio);
    }

    private SkillPlan toSkillPlan(Map<String, Object> s, String id, String name) {
        String type = Json.str(s, "type", "passive");
        Cast cast = buildCast(s);
        List<BuffSpec> grants = new ArrayList<>();
        addEffects(grants, s.get("buffs"));
        addEffects(grants, s.get("debuffs"));
        if (cast == null && grants.isEmpty()) return null;   // 딜·버프 없는 유틸 스킵
        TriggerKind tk = "passive".equals(type) ? TriggerKind.PASSIVE : TriggerKind.ACTIVE;
        double cd = Json.num(s, "cd", 0);   // active 재사용, summon=소환마다(0)
        return new SkillPlan(id, name, Kind.ACTIVE, cd, new Trigger(tk, 100, 0, false), cast, grants);
    }

    private Cast buildCast(Map<String, Object> s) {
        Object dealO = s.get("deal"), dotO = s.get("dot");
        Map<String, Object> deal = (dealO != null) ? Json.asMap(dealO) : null;
        Map<String, Object> dot = (dotO != null) ? Json.asMap(dotO) : null;
        Map<String, Object> src = deal != null ? deal : dot;
        if (src == null) return null;
        double coeff = Json.num(src, "coeff", 0);
        int hits = (int) Json.num(src, "hits", 1);
        int targets = (int) Json.num(src, "targets", 1);
        Dot d = null;
        if (dot != null) {
            double period = Json.num(dot, "period", 1), dur = Json.num(dot, "dur", 0);
            if (period > 0 && dur > period) d = new Dot(period, dur);
        }
        return new Cast(coeff / 100.0, hits, targets, DmgTag.SKILL, 0, 0, 0, d);
    }

    /** buffs/debuffs(used=true) → BuffSpec. both는 PLAYER+COMPANION 2개. */
    private void addEffects(List<BuffSpec> out, Object arrO) {
        if (arrO == null) return;
        for (Object o : Json.asList(arrO)) {
            Map<String, Object> e = Json.asMap(o);
            if (!Json.bool(e, "used", false)) continue;      // 딜 무관(생존/유틸)
            String sid = statId(Json.str(e, "stat"));
            if (sid == null) continue;
            double amount = Json.num(e, "value", 0);
            double durSec = e.containsKey("dur") ? Json.num(e, "dur", 0) : Double.POSITIVE_INFINITY;
            String tgt = Json.str(e, "target", "player");
            if ("both".equals(tgt)) {
                out.add(new BuffSpec(Target.PLAYER, Map.of(sid, amount), durSec, null, null));
                out.add(new BuffSpec(Target.COMPANION, Map.of(sid, amount), durSec, null, null));
            } else {
                out.add(new BuffSpec(target(tgt), Map.of(sid, amount), durSec, null, null));
            }
        }
    }

    /** 동료 효과 stat → StatBlock id(딜 관련만). 나머지(SHIELD/IMMUNE/HP_REGEN/MOVE/ENEMY_ATK…)는 null. */
    private static String statId(String s) {
        return switch (s) {
            case "ATK_PCT", "CRIT_RATE", "CRIT_DMG", "ATK_SPEED", "MAX_DMG_MULT", "MIN_DMG_MULT",
                 "FINAL_DMG", "DMG", "BOSS_DMG", "NORMAL_DMG", "DEF_PEN" -> s;
            case "RECV_DMG" -> "RECV_AMP";   // 적 받피증(debuff)
            default -> null;                 // 딜 무관
        };
    }

    private static Target target(String t) {
        return switch (t) {
            case "companion" -> Target.COMPANION;
            case "enemy" -> Target.ENEMY;
            default -> Target.PLAYER;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findCompanion(String id) {
        for (Object o : Json.asList(root.get("companions"))) {
            Map<String, Object> c = Json.asMap(o);
            if (id.equals(Json.str(c, "id"))) return c;
        }
        return null;
    }
}
