package mekicalc.engine.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;
import mekicalc.model.StatBlock;

/**
 * 유물(아티팩트) 장착효과 계산 엔진. 계산 대상 = category=compute(조건/트리거) 유물의 used:true 효과만.
 * category=preReflected(선반영)/survival(비딜)/content(컨텐츠한정)는 딜 기여 0(슬롯만 차지).
 *
 * <p>이 클래스는 우선 <b>해석</b>(장착 유물 → 각성별 구체 효과)과 <b>슬롯 산출</b>을 담당한다.
 * 스탯 주입(플레이어 버프/적 디버프)은 후속 단계에서 {@code SkillDps.buffed}·recvDmgFactor·방어배수에 연결한다.
 * 규칙: [[mekicalc-artifact-spec]] — perEnemy 선형/dmgMult(DMG×(1+Σ))/derived(라이브 스탯×비율)/debuffCase(불타는용암)/
 * refresh(재발동 지속리셋)/assumeMax/bossOnly·bossZero/window[시작,끝]/content.
 */
public final class ArtifactEffects {

    /** 장착 유물 1개(유물 id + 각성 0~5). */
    public static final class Equip {
        public final String id;
        public final int awaken;
        public Equip(String id, int awaken) { this.id = id; this.awaken = Math.max(0, Math.min(5, awaken)); }
    }

    /** 해석된 컴퓨트 효과 1건(디버그/주입용). */
    public static final class Eff {
        public final String artifact, stat, target, trigger;
        public final double value;      // 해당 각성의 값(파생이면 비율)
        public final Map<String, Object> raw;   // 원본 effect 맵(플래그 접근용)
        Eff(String artifact, String stat, String target, String trigger, double value, Map<String, Object> raw) {
            this.artifact = artifact; this.stat = stat; this.target = target; this.trigger = trigger; this.value = value; this.raw = raw;
        }
    }

    /** 일반몹(챕터 사냥) 처치율 가정: 분당 110마리. onKill 트리거 유물(성배 등)의 가동률 산정 기준. */
    private static final double NORMAL_KILLS_PER_MIN = 110;

    private final Map<String, Object> root;
    private final Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
    private final List<Equip> equipped;

    public ArtifactEffects(Map<String, Object> artifactRoot, List<Equip> equipped) {
        this.root = artifactRoot == null ? new LinkedHashMap<>() : artifactRoot;
        this.equipped = equipped == null ? new ArrayList<>() : equipped;
        Object arr = this.root.get("artifacts");
        if (arr != null) for (Object o : Json.asList(arr)) {
            Map<String, Object> a = Json.asMap(o);
            byId.put(Json.str(a, "id", ""), a);
        }
    }

    /** 장착 유물들의 각성 총합. */
    public int awakenSum() {
        int s = 0;
        for (Equip e : equipped) s += e.awaken;
        return s;
    }

    /** 각성 총합 → 장착 슬롯 수(slotByAwakenSum: <10=1, <30=2, <50=3, ≥50=4). */
    public int slots() {
        int sum = awakenSum();
        int best = 1;
        for (Object o : Json.asList(root.get("slotByAwakenSum"))) {
            Map<String, Object> r = Json.asMap(o);
            int sl = (int) Json.num(r, "slots", 1);
            if (Json.bool(r, "else", false)) { best = Math.max(best, sl); continue; }
            if (r.containsKey("under") && sum < Json.num(r, "under", 0)) return sl;
        }
        return best;   // 임계 모두 초과 → else(최대)
    }

    /** 유물 id → 표시 이름(없으면 id 그대로). */
    public String nameOf(String id) {
        Map<String, Object> a = byId.get(id);
        return a == null ? id : Json.str(a, "name", id);
    }

    /** 초과분 파생값(감정물병): min(cap, max(0, source−threshold)/divisor × perStep). */
    private static double excessDerived(Map<String, Object> dm, int awaken, double sourceVal) {
        double excess = Math.max(0, sourceVal - Json.num(dm, "threshold", 0));
        double divisor = Json.num(dm, "divisor", 1);
        double per = arrAt(dm.get("perStep"), awaken);
        double cap = arrAt(dm.get("cap"), awaken);
        return Math.min(cap, divisor > 0 ? excess / divisor * per : 0);
    }

    /**
     * 유물 1개가 <b>유저 프리셋 스탯에 이미 선반영한 값</b> {statId: 값}. 유물 기여를 구할 땐 이 값을 스탯에서 빼고
     * 재계산해 "그 유물이 없을 때 대비 딜"을 얻는다. 대상=player·상시(또는 챕터사냥 컨텐츠·일반몹)만.
     * <ul><li>선반영 소스(used:false, 공속·크확·공퍼·쿨감 등) → 각성값</li>
     * <li>파생 결과(derived, 최대뎀배율=공속×비율 등) → <b>현재 스탯 기준</b> 파생값(총량)</li></ul>
     * (엔진은 딜 계산에서 파생을 '버프 증가분'만 더하므로, 프리셋 소스분까지 빼야 유물 전체 기여가 잡힌다.)
     */
    public Map<String, Double> preReflectedPlayerStats(Equip eq, StatBlock b, boolean boss) {
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, Object> a = byId.get(eq.id);
        if (a == null) return out;
        List<Object> effs = Json.asList(a.get("effects"));
        for (Object eo : effs) {
            Map<String, Object> ef = Json.asMap(eo);
            if (!"player".equals(Json.str(ef, "target", "player"))) continue;   // 동료 대상 선반영(소환지속 등)은 미소환 기준 0
            String c = Json.str(ef, "content", "");
            if (!c.isEmpty()) { if (!"챕터사냥".equals(c) || boss) continue; }   // 컨텐츠 유물: 챕터사냥(일반몹)만
            else if (!"always".equals(Json.str(ef, "trigger", "always"))) continue;   // 상시 선반영만(조건부는 엔진이 계산)
            String stat = Json.str(ef, "stat", "");
            double val;
            Object d = ef.get("derived");
            if (d instanceof Map) {                                             // 파생 결과의 프리셋 선반영분(이 유물의 소스 기여 제거 후 기준)
                Map<String, Object> dm = Json.asMap(d);
                String from = Json.str(dm, "from", "");
                double src = sourceAfterRemoval(effs, from, b, eq.awaken);
                val = dm.containsKey("threshold") ? excessDerived(dm, eq.awaken, src)
                        : arrAt(dm.get("ratio"), eq.awaken) / 100.0 * src;
            } else if (!Json.bool(ef, "used", false)) {                        // 선반영 소스: 체감 스탯(공속)은 실효 차감분, 합산 스탯은 각성값
                List<Object> vals = Json.asList(ef.get("values"));
                if (vals.isEmpty()) continue;
                Object vo = vals.get(Math.max(0, Math.min(eq.awaken, vals.size() - 1)));
                if (!(vo instanceof Number)) continue;
                double raw = ((Number) vo).doubleValue();
                val = "ATK_SPEED".equals(stat) ? b.get(stat) - unmergeDim(b.get(stat), raw, 150) : raw;
            } else continue;                                                   // used:true 비파생(어둠계약서 등)은 엔진 계산분 → 선반영 아님
            if (!stat.isEmpty()) out.merge(stat, val, Double::sum);
        }
        return out;
    }

    /**
     * 유물 1개가 <b>동료 대상</b>으로 프리셋에 선반영한 값 {statId: 값}(소환 지속시간 등). 동료 유물의 딜 기여는
     * 미소환(solo) 기준으로 0이라, 이 값을 스탯에서 빼고 <b>소환 종합 딜</b>(전투 평균, 동료 포함)을 재계산해 얻는다.
     * 대상=companion·상시·선반영(used:false)·비파생만(소환지속=합산형이라 raw 그대로).
     */
    public Map<String, Double> preReflectedCompanionStats(Equip eq, StatBlock b, boolean boss) {
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, Object> a = byId.get(eq.id);
        if (a == null) return out;
        for (Object eo : Json.asList(a.get("effects"))) {
            Map<String, Object> ef = Json.asMap(eo);
            if (!"companion".equals(Json.str(ef, "target", "player"))) continue;
            if (!"always".equals(Json.str(ef, "trigger", "always")) || Json.bool(ef, "used", false)) continue;
            if (ef.get("derived") instanceof Map) continue;
            String stat = Json.str(ef, "stat", "");
            List<Object> vals = Json.asList(ef.get("values"));
            if (stat.isEmpty() || vals.isEmpty()) continue;
            out.merge(stat, arrAt(vals, eq.awaken), Double::sum);
        }
        return out;
    }

    /** 체감(DIMINISHING) 역연산: 합쳐진 값 merged 에서 raw delta 를 제거한 원래 값. (StatDef.unmerge 와 동일 공식) */
    private static double unmergeDim(double merged, double delta, double max) {
        return (max > 0 && delta > 0 && delta < max) ? (merged - delta) / (1.0 - delta / max) : merged - delta;
    }

    /** 파생 소스(from)에서 이 유물이 선반영한 raw를 제거한 값 — 공속은 체감 역산(unmerge), 그 외 합산은 뺄셈. */
    private static double sourceAfterRemoval(List<Object> effs, String from, StatBlock b, int awaken) {
        double raw = 0;
        for (Object eo : effs) {
            Map<String, Object> ef = Json.asMap(eo);
            if (from.equals(Json.str(ef, "stat", "")) && !Json.bool(ef, "used", false)
                    && "player".equals(Json.str(ef, "target", "player")))
                raw += arrAt(ef.get("values"), awaken);
        }
        double cur = b.get(from);
        return "ATK_SPEED".equals(from) ? unmergeDim(cur, raw, 150) : cur - raw;
    }

    /** 장착 유물의 컴퓨트 효과(used:true, content 아님)를 각성값과 함께 해석. derived면 value=비율. */
    public List<Eff> resolvedCompute() {
        List<Eff> out = new ArrayList<>();
        for (Equip eq : equipped) {
            Map<String, Object> a = byId.get(eq.id);
            if (a == null || !"compute".equals(Json.str(a, "category", ""))) continue;
            String name = Json.str(a, "name", eq.id);
            for (Object eo : Json.asList(a.get("effects"))) {
                Map<String, Object> ef = Json.asMap(eo);
                if (!Json.bool(ef, "used", false)) continue;               // 비딜/선반영 제외
                if (ef.containsKey("content")) continue;                    // 컨텐츠 한정 → 우리 계산 0
                double v = effValue(ef, eq.awaken);
                out.add(new Eff(name, Json.str(ef, "stat", ""), Json.str(ef, "target", "player"),
                        Json.str(ef, "trigger", "always"), v, ef));
            }
        }
        return out;
    }

    /** 효과의 해당 각성 값. debuffCase는 buffValues 기준, derived는 ratio 기준, 그 외 values[awaken]. */
    private static double effValue(Map<String, Object> ef, int awaken) {
        if (Json.bool(ef, "debuffCase", false)) return arrAt(ef.get("buffValues"), awaken);
        Object d = ef.get("derived");
        if (d instanceof Map) return arrAt(Json.asMap(d).get("ratio"), awaken);
        return arrAt(ef.get("values"), awaken);
    }

    private static double arrAt(Object arr, int i) {
        List<Object> l = Json.asList(arr);
        if (l.isEmpty()) return 0;
        int k = Math.max(0, Math.min(l.size() - 1, i));
        Object v = l.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    // ──────────────────── 스탯 주입(플레이어 버프 / 적 디버프) ────────────────────

    /**
     * 장착 유물의 <b>플레이어 버프</b> 델타를 eff에 적용한다. t&lt;0=해석식(가동률 가중), t≥0=시뮬 순간(창 on/off).
     * @param eff 현재(버프 반영) 스탯 — 여기에 유물 델타 가산. 파생 유물의 라이브 소스값도 이걸 읽음.
     * @param base 프리셋 원본 스탯 — 파생 델타 기준(비율×(라이브−base)).
     * @param atkSum 공퍼 버킷 누적기(길이1). ATK_PCT 유물은 여기에 합산(스킬 공퍼와 동일 버킷).
     * @param enemyDebuff 적이 (내 직업/동료가 준) 디버프 보유 판정 — 불타는용암 debuffCase.
     * @param seconds 전투시간(창/트리거 가동률용). compUptime 동료 소환 가동률.
     */
    public void applyPlayer(StatBlock eff, StatBlock base, double[] atkSum, boolean boss,
                            boolean enemyDebuff, double seconds, double compUptime, double t, boolean inheritance) {
        // 2패스: 파생(라이브 소스 의존)은 마지막에 — 다른 유물의 크확/공속 가산까지 반영.
        for (int pass = 0; pass < 2; pass++) {
            for (Equip eq : equipped) {
                Map<String, Object> a = byId.get(eq.id);
                if (a == null || !"compute".equals(Json.str(a, "category", ""))) continue;
                for (Object eo : Json.asList(a.get("effects"))) {
                    Map<String, Object> ef = Json.asMap(eo);
                    if (!Json.bool(ef, "used", false) || "enemy".equals(Json.str(ef, "target", ""))) continue;
                    boolean derived = ef.get("derived") instanceof Map;
                    if (derived != (pass == 1)) continue;                   // 0패스=일반, 1패스=파생
                    if (!appliesPlayer(ef, boss)) continue;
                    double u = weight(ef, boss, seconds, compUptime, t);
                    if (u <= 0) continue;
                    double v = playerValue(ef, eq.awaken, eff, base, boss, enemyDebuff);
                    if (boss) v *= inheritance ? 1.0 : Json.num(ef, "bossSelfMult", 1.0);   // 뿔피리: 보스전 자신만 2배(계승은 동료 몫이라 제외)
                    if (v == 0) continue;
                    String stat = Json.str(ef, "stat", "");
                    if (Json.bool(ef, "dmgMult", false)) {                  // 육각목걸이: DMG × (1+v×중첩×u/100)
                        double stacks = stackCount(ef, boss, seconds, t, Json.num(ef, "maxStacks", 1));
                        eff.set("DMG", eff.get("DMG") * (1 + v * stacks * u / 100.0));
                    } else if ("FINAL_DMG".equals(stat)) {
                        eff.set("FINAL_DMG", multPct(eff.get("FINAL_DMG"), v * u));
                    } else if ("ATK_PCT".equals(stat)) {
                        atkSum[0] += v * u;                                 // 공퍼 버킷(스킬 공퍼와 합)
                    } else {
                        eff.set(stat, eff.get(stat) + v * u);              // CRIT_RATE/CRIT_DMG/BOSS/NORMAL/MAX_MULT/DMG(가산)/쿨감
                    }
                }
            }
        }
    }

    /** 장착 유물의 <b>적 받는피해 증가</b>(RECV_DMG) 합%(분수 아님). recvDmgFactor(플레이어·동료 공유)에 더한다. */
    public double enemyRecvDmgPct(boolean boss, double attackRate, double seconds, double t) {
        double sum = 0;
        for (Equip eq : equipped) {
            Map<String, Object> a = byId.get(eq.id);
            if (a == null || !"compute".equals(Json.str(a, "category", ""))) continue;
            for (Object eo : Json.asList(a.get("effects"))) {
                Map<String, Object> ef = Json.asMap(eo);
                if (!Json.bool(ef, "used", false) || !"enemy".equals(Json.str(ef, "target", ""))
                        || !"RECV_DMG".equals(Json.str(ef, "stat", ""))) continue;
                if (!appliesPlayer(ef, boss)) continue;
                double v = arrAt(ef.get("values"), eq.awaken);
                if (Json.bool(ef, "individualDur", false)) {              // 은펜던트: 지속 개별 → 실제 중첩
                    double stacks = Math.min(Json.num(ef, "maxStacks", 1),
                            Json.num(ef, "chance", 100) / 100.0 * Math.max(0, attackRate) * Json.num(ef, "dur", 0));
                    sum += v * stacks;
                } else {
                    sum += v * weight(ef, boss, seconds, 0, t);           // 천도명약 등: assumeMax→가동률 1
                }
            }
        }
        return sum;
    }

    /** 이 컨텍스트(일반/보스)에서 효과가 발동하는지: content(챕터사냥만 일반)·bossOnly/Zero·targetMode 게이팅. */
    private static boolean appliesPlayer(Map<String, Object> ef, boolean boss) {
        String c = Json.str(ef, "content", "");
        if (!c.isEmpty()) { if (!"챕터사냥".equals(c)) return false; if (boss) return false; }   // 챕터사냥=일반만, 그 외 컨텐츠=0
        if (Json.bool(ef, "bossOnly", false) && !boss) return false;
        if (Json.bool(ef, "bossZero", false) && boss) return false;
        String tm = Json.str(ef, "targetMode", "");
        if ("multi".equals(tm) && boss) return false;                    // 세이람 일반뎀(적2+)=일반만
        if ("single".equals(tm) && !boss) return false;                  // 세이람 보스뎀(적1)=보스만
        return true;
    }

    /** 효과 가동 가중치. t&lt;0=해석식 가동률, t≥0=시뮬 순간(0/1). */
    private static double weight(Map<String, Object> ef, boolean boss, double seconds, double compUptime, double t) {
        String tr = Json.str(ef, "trigger", "always");
        double dur = Json.num(ef, "dur", 0);
        switch (tr) {
            case "period": {
                double p = Json.num(ef, "period", 0);
                if (t < 0) return p > 0 ? Math.min(1, dur / p) : 1;
                return (p > 0 && (t % p) < dur) ? 1 : 0;
            }
            case "combatStart": {
                Object w = ef.get("window");
                double s0 = 0, e0 = dur > 0 ? dur : seconds;
                if (w instanceof List) { List<Object> l = Json.asList(w); s0 = arrAt(l, 0); e0 = arrAt(l, 1); }
                if (t < 0) return seconds > s0 ? (Math.min(e0, seconds) - s0) / seconds : 0;
                return (t >= s0 && t < e0) ? 1 : 0;
            }
            case "companionSummoned":
                return t < 0 ? compUptime : compUptime;   // 시뮬 정밀 창은 2b-3(현재 가동률 근사)
            case "onKill": {
                // 처치 트리거(성배): 일반몹만 발동(보스 처치=전투종료라 전투 중 0). 분당 110마리 × 처치당 확률로
                // 초당 발동수 λ 산정 → refresh/지속 dur 동안 "최근 dur 내 1회 이상 발동" 확률 = 1-e^(-λ·dur).
                // (확률 100%면 e^(-큰수)≈0 → 가동률 1로 기존 '상시' 가정과 일치.)
                if (boss) return 0;
                double lambda = (NORMAL_KILLS_PER_MIN / 60.0) * (Json.num(ef, "chance", 100) / 100.0);
                double d = dur > 0 ? dur : seconds;
                if (lambda <= 0 || d <= 0) return 0;
                return 1 - Math.exp(-lambda * d);
            }
            default:                                        // always/bossAttack/onDebuffed/onAttack(assumeMax·refresh)
                return 1;
        }
    }

    /**
     * dmgMult 유물(육각 수정 목걸이)의 중첩 수. <b>일반몹</b>=무한 사냥이라 상시 최대(maxStacks), <b>보스전</b>=제한 시간
     * 안에서 0→최대로 쌓임(period마다 +1). 해석식(t&lt;0)은 전투시간 평균 스택, 시뮬(t≥0)은 그 순간 스택.
     * (육각은 dur&gt;period+refresh라 한 번 쌓인 스택은 안 빠지므로 floor(t/period)+1로 근사.)
     */
    private static double stackCount(Map<String, Object> ef, boolean boss, double seconds, double t, double maxStacks) {
        if (!boss) return maxStacks;
        double period = Json.num(ef, "period", 0);
        if (period <= 0) return maxStacks;
        if (t >= 0) return Math.min(maxStacks, Math.floor(t / period) + 1);
        double sum = 0; int n = 0;                                        // 해석식: 0~seconds 평균 스택
        for (double tt = 0; tt < seconds; tt += 0.1) { sum += Math.min(maxStacks, Math.floor(tt / period) + 1); n++; }
        return n > 0 ? sum / n : maxStacks;
    }

    /** 효과 값. 파생=비율×(라이브−base) 또는 초과분형, debuffCase=디버프 유무, perEnemy=선형, 그 외 values[각성]. */
    private static double playerValue(Map<String, Object> ef, int awaken, StatBlock eff, StatBlock base, boolean boss, boolean enemyDebuff) {
        Object d = ef.get("derived");
        if (d instanceof Map) {
            Map<String, Object> dm = Json.asMap(d);
            String from = Json.str(dm, "from", "");
            if (dm.containsKey("threshold"))                             // 초과분형(감정물병): 라이브−프리셋 증가분만(프리셋분은 선반영)
                return excessDerived(dm, awaken, eff.get(from)) - excessDerived(dm, awaken, base.get(from));
            double ratio = arrAt(dm.get("ratio"), awaken);
            return ratio / 100.0 * (eff.get(from) - base.get(from));      // 버프로 늘어난 소스만큼만(프리셋 소스분은 선반영)
        }
        if (Json.bool(ef, "debuffCase", false))
            return arrAt(ef.get(enemyDebuff ? "debuffValues" : "buffValues"), awaken);
        double v = arrAt(ef.get("values"), awaken);
        if (Json.bool(ef, "perEnemy", false)) v *= boss ? 1 : Json.num(ef, "maxEnemies", 1);
        return v;
    }

    private static double multPct(double a, double b) { return ((1 + a / 100.0) * (1 + b / 100.0) - 1) * 100.0; }
}
