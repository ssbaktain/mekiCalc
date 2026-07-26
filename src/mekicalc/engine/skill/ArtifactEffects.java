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
                        double stacks = Json.num(ef, "maxStacks", 1);
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
            default:                                        // always/onKill/bossAttack/onDebuffed/onAttack(assumeMax·refresh)
                return 1;
        }
    }

    /** 효과 값. 파생=비율×(라이브−base), debuffCase=디버프 유무, perEnemy=선형, 그 외 values[각성]. */
    private static double playerValue(Map<String, Object> ef, int awaken, StatBlock eff, StatBlock base, boolean boss, boolean enemyDebuff) {
        Object d = ef.get("derived");
        if (d instanceof Map) {
            Map<String, Object> dm = Json.asMap(d);
            String from = Json.str(dm, "from", "");
            double ratio = arrAt(dm.get("ratio"), awaken);
            return ratio / 100.0 * (eff.get(from) - base.get(from));      // 버프로 늘어난 소스만큼만
        }
        if (Json.bool(ef, "debuffCase", false))
            return arrAt(ef.get(enemyDebuff ? "debuffValues" : "buffValues"), awaken);
        double v = arrAt(ef.get("values"), awaken);
        if (Json.bool(ef, "perEnemy", false)) v *= boss ? 1 : Json.num(ef, "maxEnemies", 1);
        return v;
    }

    private static double multPct(double a, double b) { return ((1 + a / 100.0) * (1 + b / 100.0) - 1) * 100.0; }
}
