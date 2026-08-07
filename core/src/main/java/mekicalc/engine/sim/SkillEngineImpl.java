package mekicalc.engine.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mekicalc.engine.skill.SkillDpsModel;
import mekicalc.json.Json;
import mekicalc.model.StatBlock;
import mekicalc.model.StatDef.Combine;

/**
 * {@link SkillEngine} 구현 — skills_v2(5분류) 데이터로 {@link SkillPlan}을 산출한다.
 *
 * <p>반영: 계수 성장(레벨당 growth%)·쿨감(비율→고정 7초 특수규칙)·타수/대상 수·피해량 마스터리·
 * 버프 지속시간. 데미지 계산·사용 순서는 하지 않는다.
 *
 * <p>TODO(데이터 갭): skills_v2에 스킬 기본 대상 수·dot 주기/지속이 없어 각각 1·미지원으로 둠(clt 보강 예정).
 * 함수형 버프값($CreatureFinalStat…)은 현재 스킵(스케줄러에서 동적 평가 예정).
 */
public final class SkillEngineImpl implements SkillEngine {

    private static final String LEVEL = "LEVEL";
    private static final String CDR_PCT = "COOLDOWN_REDUCTION_PCT";
    private static final String CDR_FLAT = "COOLDOWN_REDUCTION";
    private static final String BASIC_TARGETS = "BASIC_ATK_TARGETS";
    private static final int AREA_TARGET_CAP = 999;   // 범위 광역 대상 상한(실제로는 컨텐츠 마리수로 min 제한)

    private final SkillDataV2 data;

    public SkillEngineImpl(SkillDataV2 data) {
        this.data = data;
    }

    @Override
    public List<SkillPlan> plan(StatBlock stats, SkillSelection sel) {
        List<SkillPlan> out = new ArrayList<>();
        int cl = (int) stats.get(LEVEL);
        Map<String, Double> boost = collectBoost(stats);   // 특정 스킬 강화(메용 등)
        if (sel.basicSkillId() != null) {
            Map<String, Object> b = data.skill(sel.basicSkillId());
            if (b != null) out.add(build(b, stats, Kind.BASIC, boost));
        }
        for (String id : sel.activeSkillIds()) {
            Map<String, Object> s = data.skill(id);
            if (s != null) out.add(build(s, stats, Kind.ACTIVE, boost));
        }
        // 습득한 패시브(딜/버프) 자동 포함 — 선택 불가·항상 반영. 딜·버프 없는 유틸/순수 skillBoost(메용)는 필터로 제외.
        for (Map<String, Object> s : data.all()) {
            if (!"passive".equals(s.get("type"))) continue;
            Integer op = openLv(s.get("open"));
            if (op == null || cl < op) continue;   // 미습득
            SkillPlan p = build(s, stats, Kind.ACTIVE, boost);
            if (p.cast() != null || !p.grants().isEmpty()) out.add(p);
        }
        return dropPreReflected(out);
    }

    /**
     * 상시 패시브 버프 선반영 제외(사용자 스탯창 규약): 항상 켜진(무한 지속) 플레이어 버프 중
     * 공격력%(ATK_PCT)·주스탯%(MAIN_STAT_PCT)를 제외한 스탯(기본공격뎀·보스뎀·크뎀·최소뎀 등)은
     * 이미 입력 스탯에 선반영돼 있으므로 엔진이 다시 더하지 않는다. 공퍼·주스탯%는 스탯창 미포함 → 유지.
     * 시간제 버프(아드레날린·퍼지 등 유한 지속)와 적 디버프(ENEMY)는 그대로 둔다.
     */
    private List<SkillPlan> dropPreReflected(List<SkillPlan> plans) {
        List<SkillPlan> out = new ArrayList<>();
        for (SkillPlan p : plans) {
            boolean changed = false;
            List<BuffSpec> kept = new ArrayList<>();
            for (BuffSpec bs : p.grants()) {
                if (bs.target() != Target.PLAYER || !Double.isInfinite(bs.durationSec())) { kept.add(bs); continue; }
                Map<String, Double> keep = new HashMap<>();
                for (Map.Entry<String, Double> e : bs.effects().entrySet())
                    if ("SKILL_ATK_PCT".equals(e.getKey()) || "MAIN_STAT_PCT".equals(e.getKey())) keep.put(e.getKey(), e.getValue());
                if (keep.size() != bs.effects().size()) changed = true;
                if (!keep.isEmpty()) kept.add(new BuffSpec(bs.target(), keep, bs.durationSec(), bs.combine(), bs.stack()));
            }
            out.add(changed ? new SkillPlan(p.id(), p.name(), p.kind(), p.cooldownSec(), p.trigger(), p.cast(), kept) : p);
        }
        return out;
    }

    /**
     * 습득 스킬의 skillBoost(특정 스킬 최종뎀 강화, 메이플 용사 등) 수집.
     * @return 대상 스킬 index(또는 소환 자식 index) → 최종뎀% 합.
     */
    private Map<String, Double> collectBoost(StatBlock st) {
        int cl = (int) st.get(LEVEL);
        String meyongId = (data.creatureIndex() * 10000 + 4100) + "";   // 메이플 용사(스킬레벨 비례 성장)
        Map<String, Double> m = new HashMap<>();
        for (Map<String, Object> s : data.all()) {
            Integer op = openLv(s.get("open"));
            if (op == null || cl < op) continue;
            List<Object> bl = Json.asList(s.get("skillBoost"));
            if (bl == null) continue;
            // 메용 성장: 값 × (1 + 0.05×min(스킬레벨,120) + 0.01×초과). 그 외 skillBoost는 고정.
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
                // 메용=특수 성장 mult. 그 외 skillBoost(어드밴스드 파이널어택·쇼크강화 등)=스킬레벨 비례 growth.
                double val = meyongId.equals(s.get("index"))
                        ? StatNameMap.toPct(v) * mult
                        : StatNameMap.toPct(SkillDpsModel.coefficient(v, Json.num(bt, "growth", 0), effLevel(s, st)));
                val *= 1 + masterySum(s, cl, "ratioBoost") / 100.0;   // 최종뎀 증가량 배수 마스터리(어드밴스드 파이널어택 +50%)
                m.merge(Json.str(bt, "targetSkill"), val, Double::sum);
            }
        }
        return m;
    }

    private SkillPlan build(Map<String, Object> s, StatBlock st, Kind kind, Map<String, Double> boost) {
        int cl = (int) st.get(LEVEL);
        int lv = effLevel(s, st);
        Cast cast = buildCast(s, st, kind, cl, lv, boost);
        double cd = Json.num(s, "cd", 0);
        double cdrPct = merge(st.get(CDR_PCT), masterySum(s, cl, "cdrPct"), 100);
        // cdFlatMs 마스터리(쿨 고정 감소)는 대상이 다른 스킬(cross-skill: 씨서펜트강화→씨서펜트버스트)일 수 있어 전 스킬에서 수집.
        double effCd = SkillDpsModel.cooldown(cd, cdrPct, st.get(CDR_FLAT) + crossCdFlatSec(Json.str(s, "index"), cl));
        List<BuffSpec> grants = buildGrants(s, cl, lv);
        return new SkillPlan(Json.str(s, "index"), Json.str(s, "name"), kind, effCd,
                buildTrigger(s, kind), cast, grants);
    }

    /** clt trigger → 발동 스펙. 평타는 항상 ACTIVE. */
    private Trigger buildTrigger(Map<String, Object> s, Kind kind) {
        if (kind == Kind.BASIC) return Trigger.ACTIVE;
        Map<String, Object> tg = Json.asMap(s.get("trigger"));
        if (tg == null) return Trigger.ACTIVE;
        TriggerKind tk = switch (Json.str(tg, "type", "None")) {
            case "OnStart" -> TriggerKind.PASSIVE;
            case "OnTimer" -> TriggerKind.ON_TIMER;
            case "OnAttack", "OnHit" -> TriggerKind.ON_ATTACK;
            case "OnDamage" -> TriggerKind.ON_DAMAGE;
            case "OnActiveSkill" -> TriggerKind.ON_ACTIVE_SKILL;
            case "OnState" -> TriggerKind.ON_STATE;
            default -> TriggerKind.ACTIVE;   // "None" = 유저/자동 발동
        };
        double proc = Json.num(tg, "procPct", 100);
        double periodSec = tg.containsKey("periodMs") ? Json.num(tg, "periodMs", 0) / 1000.0 : 0;
        boolean fast = Json.bool(tg, "fastByAtkSpeed", false);
        return new Trigger(tk, proc, periodSec, fast);
    }

    /**
     * 타격 스펙(계수 성장·타수·대상 수·피해 마스터리). 순수 버프 스킬이면 null.
     * 본체 직접딜(damage)이 없으면 소환/설치딜(summon)을 Cast로 산출(지속·주기 → Dot).
     */
    private Cast buildCast(Map<String, Object> s, StatBlock st, Kind kind, int cl, int lv, Map<String, Double> boost) {
        List<Object> dmg = Json.asList(s.get("damage"));
        Map<String, Object> summon = null;
        Map<String, Object> src;
        if (dmg != null && !dmg.isEmpty()) {
            src = Json.asMap(dmg.get(0));
        } else {
            Object sm = s.get("summon");
            if (sm == null) return null;                 // 순수 버프 스킬
            summon = Json.asMap(sm);
            Object sd = summon.get("dmg");
            if (sd == null) return null;                 // 딜 없는 소환(버프 소환 등)
            src = Json.asMap(sd);
        }
        double base = parseNum(String.valueOf(src.get("coeff")));
        if (Double.isNaN(base)) return null;   // 함수형 계수(#ShadowBat 등) — TODO 동적 평가
        double coeffPct = SkillDpsModel.coefficient(base, Json.num(src, "growth", 0), lv);
        double dmgMasteryPct = masterySum(s, cl, "dmgPct");   // 피해량 마스터리(공식: typeFactor에 합산, hitDamage서 처리)
        double h = parseNum(Json.str(src, "hits", "1"));
        // 타수+ 마스터리 + 소환 발사/폭발/설치 수+ 마스터리(미스트이럽션 폭발+1 등, 소환 딜 추가 타격 근사).
        int hits = (Double.isNaN(h) ? 1 : (int) h) + (int) masterySum(s, cl, "hitPlus") + (int) masterySum(s, cl, "summonFirePlus");

        int targets;
        Dot dot = null;
        if (summon != null) {
            double mt = parseNum(String.valueOf(summon.get("maxTarget")));
            targets = (Double.isNaN(mt) ? 1 : (int) mt) + (int) masterySum(s, cl, "summonTargetPlus");
            double durMs = parseNum(String.valueOf(summon.get("durationMs")));
            double tickMs = parseNum(String.valueOf(summon.get("tickMs")));
            // 소환 주기 감소 마스터리(피닉스 82렙 등): tickMs를 % 단축
            double tickCut = masterySum(s, cl, "tickPct") + masterySum(s, cl, "summonTickMs");
            if (tickCut > 0 && tickCut < 100) tickMs *= (1 - tickCut / 100.0);
            if (!Double.isNaN(tickMs) && tickMs > 0 && durMs > tickMs) {
                dot = new Dot(tickMs / 1000.0, durMs / 1000.0);   // 소환/설치 지속 동안 주기 딜
            }
        } else {
            Object tc = s.get("targetCount");
            int baseTargets = (tc instanceof Number n) ? n.intValue() : 1;   // 'basic'/동적 함수형 → 1
            targets = baseTargets + (int) masterySum(s, cl, "targetPlus");
            if (kind == Kind.BASIC || "basic".equals(tc)) targets += (int) st.get(BASIC_TARGETS);
            // 범위 광역(SkillRangeType=Rect): 범위 내 모든 적 = 컨텐츠 마리수까지(hitDamage서 min으로 제한).
            // clt TargetCount는 직접타겟 1이라 대상수를 못 담음 → 광역 여부는 area 플래그(MaxRangeMm 4개+Enemy). 평타는 위 BASIC_ATK_TARGETS.
            else if (Json.bool(s, "area", false)) targets = Math.max(targets, AREA_TARGET_CAP);
            // GetDot 지속(베놈 독 등): dotDurMs/dotTickMs → 지속 동안 주기 딜(1틱=coeff).
            double durMs = parseNum(String.valueOf(src.get("dotDurMs")));
            double tickMs = parseNum(String.valueOf(src.get("dotTickMs")));
            if (!Double.isNaN(durMs) && !Double.isNaN(tickMs) && tickMs > 0 && durMs > tickMs)
                dot = new Dot(tickMs / 1000.0, durMs / 1000.0);
        }
        // 특정 스킬 강화(메용 등): 이 스킬/소환 자식/딜 자식이 대상이면 계수에 최종뎀 곱(스킬 전용 → 계수 곱 = 곱연산 동일).
        // 게임 데이터(ModSkillStat)가 소환 자식(다크플레어 93041 등)에 최종뎀을 명시하면 소환수 딜에도 적용.
        double boostPct = boost.getOrDefault(Json.str(s, "index"), 0.0);
        if (summon != null) {
            Object child = summon.get("child");
            if (child != null) boostPct += boost.getOrDefault(String.valueOf(child), 0.0);
        }
        Object dealChild = s.get("dealChild");
        if (dealChild != null) boostPct += boost.getOrDefault(String.valueOf(dealChild), 0.0);
        DmgTag tag = (kind == Kind.BASIC) ? DmgTag.BASIC : DmgTag.SKILL;
        // 소환 종속 딜(비홀더 도미넌트·쇼크): 소환 지속 동안만 존재 → 가동률 곱(런타임 마스터리+스탯 쿨감 반영).
        double up = summonUptime(s, st, cl);
        // 스킬 전용 보스/일반몹 데미지: op ModStats 기본값(인핸스레이징블로우 보스뎀+100% 등) + 마스터리 statAdd. 스탯창 미포함→합산.
        double bossB = masteryStatAdd(s, cl, "BOSS_DMG") + Json.num(src, "bossDmg", 0);
        double normB = masteryStatAdd(s, cl, "NORMAL_DMG") + Json.num(src, "normalDmg", 0);
        return new Cast(coeffPct / 100.0 * (1 + boostPct / 100.0) * up, hits, targets, tag, dmgMasteryPct, bossB, normB, dot);
    }

    /** 스킬 전용 스탯 가산 마스터리(statAdd) 중 지정 엔진 스탯(BOSS_DMG/NORMAL_DMG) 합. 스탯창 미포함(스킬 한정)→엔진 반영. */
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

    /** 버프/디버프 값 배수 마스터리(ratioValue) 중 지정 clt 스탯(Attack/Weakness 등) 대상 합(%). */
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

    /** cdFlatMs 마스터리(쿨 고정 감소ms) 중 대상 스킬(targetSkill=id)인 것 합을 초 감소로. cross-skill(씨서펜트강화→버스트) 지원. */
    private double crossCdFlatSec(String id, int cl) {
        double sec = 0;
        for (Map<String, Object> other : data.all()) {
            List<Object> ms = Json.asList(other.get("masteries"));
            if (ms == null) continue;
            for (Object o : ms) {
                Map<String, Object> m = Json.asMap(o);
                Integer op = openLv(m.get("open"));
                if (op != null && cl >= op && "cdFlatMs".equals(Json.str(m, "type", ""))
                        && id.equals(Json.str(m, "targetSkill", "")))
                    sec += -Json.num(m, "value", 0) / 1000.0;   // value -2000ms → +2초 감소
            }
        }
        return sec;
    }

    /**
     * 소환 종속 딜의 가동률(§⑤): min(1, 소환 지속 / 소환스킬 effCd). effCd는 소환 스킬의 재사용 쿨감
     * 마스터리 + 스탯 쿨감을 반영한 실효 쿨(비홀더 30s → 마스터리 -30% 21s → 스탯 쿨감 더하면 상시).
     * ★정적 지속/쿨로 계산하면 마스터리·스탯을 놓쳐 오판 → 런타임 계산 필수.
     */
    private double summonUptime(Map<String, Object> s, StatBlock st, int cl) {
        Object ss = s.get("summonSkill");
        if (ss == null) return 1.0;
        Map<String, Object> sm = data.skill(String.valueOf(ss));
        if (sm == null) return 1.0;
        double scd = Json.num(sm, "cd", 0);
        double durSec = Json.num(s, "summonDurMs", 0) / 1000.0;
        if (scd <= 0 || durSec <= 0) return 1.0;
        double scdr = merge(st.get(CDR_PCT), masterySum(sm, cl, "cdrPct"), 100);
        double seffCd = SkillDpsModel.cooldown(scd, scdr, st.get(CDR_FLAT));
        return seffCd > 0 ? Math.min(1.0, durSec / seffCd) : 1.0;
    }

    /** 버프/디버프/간접딜 → BuffSpec. 피해 무관·함수형 값은 스킵. */
    private List<BuffSpec> buildGrants(Map<String, Object> s, int cl, int lv) {
        List<BuffSpec> out = new ArrayList<>();
        Map<String, Integer> stackPlus = collectStackPlus(cl);   // 콤보 등 state별 최대스택 증가
        addGrants(out, s, "buff", Target.PLAYER, cl, lv, stackPlus);
        addGrants(out, s, "debuff", Target.ENEMY, cl, lv, stackPlus);
        addGrants(out, s, "indirect", Target.PLAYER, cl, lv, stackPlus);
        double fdm = masterySum(s, cl, "finalDmgPct");   // 최종뎀 마스터리 → 상시 자버프(곱연산)
        if (fdm != 0) out.add(new BuffSpec(Target.PLAYER, Map.of("FINAL_DMG", fdm),
                Double.POSITIVE_INFINITY, Combine.MULT_PCT, null));
        return out;
    }

    private void addGrants(List<BuffSpec> out, Map<String, Object> s, String key, Target tgt, int cl, int lv,
                           Map<String, Integer> stackPlus) {
        List<Object> arr = Json.asList(s.get(key));
        if (arr == null) return;
        for (Object o : arr) {
            Map<String, Object> b = Json.asMap(o);
            String cltStat = Json.str(b, "stat");
            String sid = StatNameMap.id(cltStat);
            if (sid == null) continue;                       // 딜 무관
            double raw = parseNum(Json.str(b, "val"));
            if (Double.isNaN(raw)) continue;                 // 함수형($…) — TODO 동적 평가
            double amount = StatNameMap.toPct(SkillDpsModel.coefficient(raw, Json.num(b, "growth", 0), lv));
            amount *= 1 + masteryRatioValue(s, cl, cltStat) / 100.0;   // 버프/디버프 증가량 배수 마스터리(크로스오버 공퍼 +100% 등)
            // 스택 시스템(콤보 등): 정상상태 근사 = 스택당 값 × 최대스택(base stateMax + 마스터리 stackPlus).
            Object smO = b.get("stateMax");
            if (smO instanceof Number sm) {
                amount *= sm.intValue() + stackPlus.getOrDefault(Json.str(b, "state", ""), 0);
            }
            out.add(new BuffSpec(tgt, Map.of(sid, amount), durSec(b), null, null));
        }
    }

    /** 습득 스킬의 stackPlus 마스터리 집계 → state별 최대스택 증가(콤보 최대중첩+ 등). */
    private Map<String, Integer> collectStackPlus(int cl) {
        Map<String, Integer> m = new HashMap<>();
        for (Map<String, Object> s : data.all()) {
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

    // ---- 헬퍼 ----

    /** 유효 스킬 레벨 = 레벨 파생(차수 시작~다음차수 ×3) + 모든 스킬 레벨 + 차수 스킬 레벨. */
    private int effLevel(Map<String, Object> s, StatBlock st) {
        int cl = (int) st.get(LEVEL);
        Integer open = openLv(s.get("open"));
        int tier = (open != null) ? tierOf(open) : tierOf(cl);   // 평타는 캐릭 차수
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

    /** 버프 지속(초). durationMs 없음/상시마커(99999999↑) → 무한. */
    private static double durSec(Map<String, Object> b) {
        Object d = b.get("durationMs");
        if (d == null) return Double.POSITIVE_INFINITY;
        double ms = parseNum(String.valueOf(d));
        return (Double.isNaN(ms) || ms >= 99999999) ? Double.POSITIVE_INFINITY : ms / 1000.0;
    }

    /** 체감 누적 합성(쿨감%·상한 max). StatDef.DIMINISHING과 동일 식. */
    private static double merge(double cur, double add, double max) {
        return add == 0 ? cur : cur + (max - cur) * (add / max);
    }

    private static double parseNum(String s) {
        if (s == null) return Double.NaN;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }
}
