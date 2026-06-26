package mekicalc.engine.skill;

import java.util.List;
import java.util.Map;
import mekicalc.engine.DamageModel;
import mekicalc.json.Json;
import mekicalc.model.StatBlock;

/**
 * 메인 동료의 DPS 기여를 <b>시간진행 시뮬레이션</b>으로 계산한다(소환 30초 창을 dt 단위로 적분).
 * 매 시점에 그 순간 활성인 자체 버프/디버프/스택/도트/쿨을 반영하므로 버프 겹침·스택 램프·도트 소멸이 자연히 처리된다.
 *
 * <p>동료는 소환 시 플레이어 스탯을 계승(공격력만 계승비율, 그 외 전부)한다. 계승비율은 유저 입력값(A안) 사용.
 * 측정 기준 = 소환 유지창 W초(기본 30, 소환유지%로 증가) 동안의 평균 DPS(동료 혼자 딜, 보스 받피증은 동료 자체분 포함).
 * 데이터: companions.json. 1/2/3/4/모스렙 비계승(스킬 계수 고정) → 우리 globalMultiplier가 스킬레벨 미사용이라 자동 충족.
 */
public final class CompanionDps {

    private static final String ATK_FLAT = "ATK_FLAT", ATK_PCT = "ATK_PCT", ATK_SPEED = "ATK_SPEED",
            FINAL_DMG = "FINAL_DMG", SUMMON_DUR = "SUMMON_DURATION", LEVEL = "LEVEL";
    private static final double DT = 0.1;   // 시뮬레이션 시간 간격(초)

    private final DamageModel dm;
    private final Map<String, Object> root;
    private final Map<String, Object> companion;
    private final String grade;
    private final int companionLevel;
    private final double inheritRatioPct;
    /** 플레이어가 적에 건 받피증 합(분수). 동료도 같은 적을 때려 공유 → buildRecv에 합연산. 0이면 미반영. */
    private double playerRecvBonus = 0;
    /** 카드/홈 "1마리 기준" 표시용. true면 일반몹도 대상수 1(플레이어 카드 dpsBreakdown(singleTarget) 과 동일 기준). */
    private boolean singleTarget = false;

    public CompanionDps(DamageModel dm, Map<String, Object> root, String companionId,
                        String grade, int companionLevel, double inheritRatioPct) {
        this.dm = dm;
        this.root = root == null ? new java.util.LinkedHashMap<>() : root;
        this.grade = grade;
        this.companionLevel = companionLevel;
        this.inheritRatioPct = inheritRatioPct;
        this.companion = findCompanion(this.root, companionId);
    }

    /** 플레이어가 적에 건 받피증 합(분수, 예: 0.5=+50%)을 동료에도 공유시킨다. 체이닝 반환. */
    public CompanionDps playerRecvBonus(double bonus) { this.playerRecvBonus = bonus; return this; }

    /** 일반몹 대상수를 1로 강제(플레이어 카드 "1마리 기준"과 동일 기준 비교용). 체이닝 반환. */
    public CompanionDps singleTarget(boolean v) { this.singleTarget = v; return this; }

    private static Map<String, Object> findCompanion(Map<String, Object> root, String id) {
        Object arr = root.get("companions");
        if (arr == null || id == null) return null;
        for (Object o : Json.asList(arr)) {
            Map<String, Object> c = Json.asMap(o);
            if (id.equals(Json.str(c, "id", ""))) return c;
        }
        return null;
    }

    public boolean valid() { return gradeData() != null; }

    private Map<String, Object> gradeData() {
        if (companion == null || grade == null) return null;
        Object g = Json.asMap(companion.get("grades")).get(grade);
        return g == null ? null : Json.asMap(g);
    }

    /** 참고용 계승비율% = baseRatio[grade] × min(1, 플레이어레벨/gradeTierLevel[grade]). */
    public double refRatioPct(StatBlock player) {
        double base = Json.num(Json.asMap(root.get("inheritBaseRatio")), grade, 0);
        double tierLv = Json.num(Json.asMap(root.get("gradeTierLevel")), grade, 1);
        double pl = player.get(LEVEL);
        return base * (tierLv <= 0 ? 1.0 : Math.min(1.0, pl / tierLv));
    }

    /** 소환 유지창(초) = 소환유지 × (1+유지%). 측정 기준. */
    public double window(StatBlock player) {
        return Json.num(root, "summonDuration", 30) * (1 + player.get(SUMMON_DUR) / 100.0);
    }

    /** 소환 가동률(참고) = 유지창/소환쿨. */
    public double summonUptime(StatBlock player) {
        double cd = Json.num(root, "summonCd", 90);
        return cd <= 0 ? 0 : Math.min(1.0, window(player) / cd);
    }

    // ──────────────────── 시간진행 시뮬레이션 ────────────────────

    /** 동료 DPS [평타, 스킬, 합] — 소환 유지창 W 기준(가동 중 평균). own = 평타+스킬. */
    public double[] dpsBreakdown(StatBlock player, boolean boss) { return simulate(player, boss, null, window(player)); }

    /** 동료 DPS [평타, 스킬, 합] — 90초 한 사이클 기준(W초 가동 + 나머지 휴면 평균). 예상 데미지 통합용. */
    public double[] cycleBreakdown(StatBlock player, boolean boss) { return simulate(player, boss, null, cyclePeriod(player)); }

    /**
     * 동료 DPS [평타, 스킬, 합] — 고정 전투시간 T초 기준(보스=70, 일반=90 등). T초 동안 소환쿨마다 소환되어
     * 각 소환창(W) 동안 딜 → 총딜 ÷ T. 마지막 소환이 T를 넘기면 비례(부분 가동)로 반영.
     */
    public double[] dpsOverSeconds(StatBlock player, boolean boss, double T) {
        if (T <= 0) return new double[]{0, 0, 0};
        double W = window(player);
        if (W <= 0) return new double[]{0, 0, 0};
        double[] win = simulate(player, boss, null, 1.0);   // 한 소환창(W) 동안의 총딜
        double cd = Json.num(root, "summonCd", 90);
        if (cd <= 0) cd = W;
        double[] out = new double[3];
        for (double s = 0; s < T - 1e-9; s += cd) {
            double frac = Math.min(1.0, (T - s) / W);       // 소환창이 전투 끝을 넘기면 부분 가동
            for (int i = 0; i < 3; i++) out[i] += win[i] * frac;
        }
        for (int i = 0; i < 3; i++) out[i] /= T;
        return out;
    }

    /** 사이클 주기 = max(소환쿨, 유지창). 유지창이 쿨보다 길면 상시 소환(가동률 1). */
    private double cyclePeriod(StatBlock player) {
        return Math.max(Json.num(root, "summonCd", 90), window(player));
    }

    // ──────────────────── 해석식(닫힌식) 동료 DPS — 시뮬과 별개·성능/정확도 비교용 ────────────────────
    // 시간제 버프를 가동률 평균으로 한 번 반영(buildStatsAvg)하고 각 성분을 레이트로 합산(시간스텝 없음).
    // 시뮬과 같은 성분: 평타 / 패시브 프록 / 도트(가동률) / 소환·액티브 즉발 본딜. 버프 겹침 비선형은 평균으로 근사.

    /** 소환 중(유지창 W) 동료 DPS [평타, 스킬, 합] — {@link #dpsBreakdown}의 해석식 버전. */
    public double[] dpsBreakdownAnalytic(StatBlock player, boolean boss) {
        Map<String, Object> g = gradeData();
        if (g == null) return new double[]{0, 0, 0};
        double W = window(player);
        if (W <= 0) return new double[]{0, 0, 0};
        List<Object> skills = Json.asList(g.get("skills"));
        StatBlock c = buildStatsAvg(player, boss, W);
        double recv = 1 + companionRecvAvg(boss, W) + playerRecvBonus;
        double atkRate = 1 + c.get(ATK_SPEED) / 100.0;
        double skillCastsPerSec = 0;
        for (Object so : skills) {
            Map<String, Object> s = Json.asMap(so);
            if (!learned(s)) continue;
            String t = Json.str(s, "type", "");
            if ("summon".equals(t)) skillCastsPerSec += 1.0 / W;
            else if ("active".equals(t)) { double cd = Json.num(s, "cd", 0); if (cd > 0) skillCastsPerSec += (Math.floor(W / cd) + 1) / W; }
        }
        double attackRate = atkRate + skillCastsPerSec;

        double basicDps = 0, skillDps = 0;
        Object gbo = Json.asMap(root.get("gradeBasicAttack")).get(grade);
        Map<String, Object> gb = gbo == null ? null : Json.asMap(gbo);
        if (gb != null && !gb.isEmpty()) {
            double coeff = Json.num(gb, "coeff", 0) / 100.0;
            int hits = (int) Json.num(gb, "hits", 0);
            int targets = (boss || singleTarget) ? 1 : (int) Json.num(gb, "targets", 1);
            basicDps = dm.globalMultiplier(c, boss) * dm.basicAtkDmgFactor(c) * coeff * hits * targets * atkRate;
        }
        for (Object so : skills) {                         // 연속 성분(패시브 프록·도트)
            Map<String, Object> s = Json.asMap(so);
            if (s.containsKey("modifies") || !learned(s)) continue;
            String type = Json.str(s, "type", "");
            if ("passive".equals(type)) {
                skillDps += passiveProcRate(s, c, boss, atkRate, attackRate, W, skills);
            } else if (s.get("dot") instanceof Map) {
                Map<String, Object> dot = Json.asMap(s.get("dot"));
                double u = uptimeOf(type, Json.num(dot, "dur", 0), Json.num(s, "cd", 0), W);
                if (u > 0) skillDps += dotPerSec(dot, c, boss) * u;
            }
        }
        for (Object so : skills) {                         // 이산 성분(소환·액티브 즉발 본딜): W내 시전수 ÷ W
            Map<String, Object> s = Json.asMap(so);
            if (s.containsKey("modifies") || !learned(s)) continue;
            String type = Json.str(s, "type", "");
            if ("summon".equals(type)) skillDps += dealPerCast(s, c, boss) / W;
            else if ("active".equals(type)) {
                double cd = Json.num(s, "cd", 0);
                if (cd > 0) skillDps += dealPerCast(s, c, boss) * (Math.floor((W - 1e-9) / cd) + 1) / W;
            }
        }
        basicDps *= recv; skillDps *= recv;
        return new double[]{basicDps, skillDps, basicDps + skillDps};
    }

    /** 고정 전투시간 T초 기준 동료 DPS [평타,스킬,합] — {@link #dpsOverSeconds}의 해석식 버전(소환 가동률 가중). */
    public double[] dpsOverSecondsAnalytic(StatBlock player, boolean boss, double T) {
        if (T <= 0) return new double[]{0, 0, 0};
        double W = window(player);
        if (W <= 0) return new double[]{0, 0, 0};
        double[] a = dpsBreakdownAnalytic(player, boss);   // 소환창 W 기준 DPS → a×W = 소환 1번 총딜
        double cd = Json.num(root, "summonCd", 90);
        if (cd <= 0) cd = W;
        double[] out = new double[3];
        for (double s = 0; s < T - 1e-9; s += cd) {        // 시뮬 dpsOverSeconds와 동일한 T 기준 정규화
            double frac = Math.min(1.0, (T - s) / W);       // 소환창이 전투 끝을 넘기면 부분 가동
            for (int i = 0; i < 3; i++) out[i] += a[i] * W * frac;
        }
        for (int i = 0; i < 3; i++) out[i] /= T;
        return out;
    }

    /** 동료 자체 스탯에 시간제 버프를 가동률 평균으로 반영한 스냅샷(해석식용). */
    private StatBlock buildStatsAvg(StatBlock player, boolean boss, double W) {
        StatBlock c = new StatBlock(player);
        c.set(ATK_FLAT, player.get(ATK_FLAT) * (1 + player.get(ATK_PCT) / 100.0) * inheritRatioPct / 100.0);
        c.set(ATK_PCT, 0);
        if ("UNIQUE".equals(grade) || "LEGENDARY".equals(grade)) {
            Map<String, Object> ulf = Json.asMap(root.get("uniqueLegendaryFinalDmg"));
            double per = Json.num(ulf, "perLevel", 0);
            for (Object lv : Json.asList(ulf.get("levels"))) if (lv instanceof Number && companionLevel >= ((Number) lv).intValue()) c.set(FINAL_DMG, multPct(c.get(FINAL_DMG), per));
        }
        Map<String, Object> g = gradeData();
        for (Object so : Json.asList(g.get("skills"))) {
            Map<String, Object> s = Json.asMap(so);
            if (!learned(s)) continue;
            collectBuffsAvg(s, boss, W, false, (stat, v) -> applyStat(c, stat, v));
        }
        return c;
    }

    /**
     * 동료 버프(선물)를 가동률 평균으로 한 번에 수집 — {@link #playerGift}의 해석식 버전(480스텝 시뮬 제거).
     * wantPlayer=true면 플레이어 대상(player/both) 버프, recvBonus는 동료가 적에 건 받피증 공유분(가동률 평균).
     */
    public Map<String, Object> playerGiftAnalytic(StatBlock player, boolean boss) {
        Map<String, Object> buffs = new java.util.LinkedHashMap<>();
        Map<String, Object> g = gradeData();
        double W = window(player);
        double recvSum = 0;
        if (g != null && W > 0) {
            for (Object so : Json.asList(g.get("skills"))) {
                Map<String, Object> s = Json.asMap(so);
                if (!learned(s)) continue;
                collectBuffsAvg(s, boss, W, true, (stat, v) -> addD(buffs, stat, v));
            }
            recvSum = companionRecvAvg(boss, W);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("buffs", buffs);
        out.put("recvBonus", recvSum);
        return out;
    }

    private void collectBuffsAvg(Map<String, Object> s, boolean boss, double W, boolean wantPlayer, BuffSink sink) {
        String type = Json.str(s, "type", "");
        if (s.get("buffs") instanceof List)
            for (Object bo : Json.asList(s.get("buffs"))) emitBuffAvg(s, Json.asMap(bo), type, boss, W, wantPlayer, false, sink);
        if (s.get("proc") instanceof Map) {
            Object pb = Json.asMap(s.get("proc")).get("buff");
            if (pb instanceof Map) emitBuffAvg(s, Json.asMap(pb), type, boss, W, wantPlayer, true, sink);
        }
    }

    private void emitBuffAvg(Map<String, Object> s, Map<String, Object> b, String type, boolean boss, double W, boolean wantPlayer, boolean isProc, BuffSink sink) {
        if (!Json.bool(b, "used", false) || !condMet(b)) return;
        String tgt = Json.str(b, "target", "");
        boolean ok = wantPlayer ? ("player".equals(tgt) || "both".equals(tgt)) : ("companion".equals(tgt) || "both".equals(tgt));
        if (!ok) return;
        double u, stacks;
        if (isProc) { u = 1.0; stacks = Json.num(b, "maxStacks", 1); }   // 발동형: 소환 중 풀가동 가정
        else {
            double dur = Json.num(b, "dur", Json.num(s, "dur", 0));
            u = uptimeOf(type, dur, Json.num(s, "cd", 0), W);
            stacks = avgStacks(b, boss, W);
        }
        if (u <= 0) return;
        String stat = Json.str(b, "stat", "");
        double base = Json.num(b, "value", 0);
        double full = "FINAL_DMG".equals(stat) ? (Math.pow(1 + base / 100.0, stacks) - 1) * 100 : base * stacks;
        sink.add(stat, full * u);   // 가동률 평균
    }

    /** 동료 자체 받피증 합(분수) — 가동률 평균. {@link #companionRecvSum}의 해석식 버전. */
    private double companionRecvAvg(boolean boss, double W) {
        Map<String, Object> g = gradeData();
        if (g == null) return 0;
        double sum = 0;
        for (Object so : Json.asList(g.get("skills"))) {
            Map<String, Object> s = Json.asMap(so);
            if (!learned(s)) continue;
            String type = Json.str(s, "type", "");
            if (s.get("debuffs") instanceof List) {
                for (Object dobj : Json.asList(s.get("debuffs"))) {
                    Map<String, Object> d = Json.asMap(dobj);
                    if (!Json.bool(d, "used", false) || !"RECV_DMG".equals(Json.str(d, "stat", ""))) continue;
                    if (Json.bool(d, "bossOnly", false) && !boss) continue;
                    double u = uptimeOf(type, Json.num(d, "dur", Json.num(s, "dur", 0)), Json.num(s, "cd", 0), W);
                    sum += Json.num(d, "value", 0) * (boss ? Json.num(d, "bossMult", 1) : 1) * u;
                }
            }
            if ("passive".equals(type) && s.get("proc") instanceof Map) {
                Object dbo = Json.asMap(s.get("proc")).get("debuff");
                if (dbo instanceof Map) {
                    Map<String, Object> d = Json.asMap(dbo);
                    if (Json.bool(d, "used", false) && "RECV_DMG".equals(Json.str(d, "stat", "")))
                        sum += Json.num(d, "value", 0) * (boss ? Json.num(d, "bossMult", 1) : 1);
                }
            }
        }
        return sum / 100.0;
    }

    /** 효과 가동률 = 활성 시간 ÷ W. 패시브=1, 소환=min(dur,W)/W, 액티브=쿨마다 dur 활성 누적/W. */
    private static double uptimeOf(String type, double dur, double cd, double W) {
        if ("passive".equals(type)) return 1.0;
        if (dur <= 0 || W <= 0) return 0;
        if (!"active".equals(type) || cd <= 0) return Math.min(dur, W) / W;   // summon 또는 쿨없음
        double active = 0, prevEnd = 0;                       // 시전 구간 '합집합'(재시전=갱신) → 시뮬 activeAt와 동일, 최대 1.0
        for (double k = 0; k * cd < W - 1e-9; k++) {
            double st = Math.max(k * cd, prevEnd), en = Math.min(W, k * cd + dur);
            if (en > st) active += en - st;
            prevEnd = Math.max(prevEnd, en);
        }
        return active / W;
    }

    /** 평균 중첩 수: perEnemy=보스1/일반최대, stackPeriod=램프 시간평균(시뮬 floor(t/sp) 적분), 그 외=max. */
    private double avgStacks(Map<String, Object> b, boolean boss, double W) {
        if (!b.containsKey("maxStacks")) return 1;
        double max = Json.num(b, "maxStacks", 1);
        if (Json.bool(b, "perEnemy", false)) return boss ? 1 : max;
        if (b.containsKey("stackPeriod")) {
            double sp = Math.max(Json.num(b, "stackPeriod", 1), 1e-9);
            double R = max * sp;                                    // 램프가 max에 닿는 시각
            double avg = W <= R ? W / (2 * sp)                       // 램프 도중에 창이 끝남
                                : (sp * max * (max - 1) / 2.0 + max * (W - R)) / W;   // 램프 후 max 유지
            return Math.min(max, Math.max(0, avg));
        }
        return max;
    }

    /** 항목별 동료 DPS(평타 + 스킬별 기여) — 디버그/검증용. 키=항목 라벨, 값=DPS(W 기준). */
    public Map<String, Object> detail(StatBlock player, boolean boss) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        simulate(player, boss, d, window(player));
        return d;
    }

    private static void addD(Map<String, Object> d, String k, double v) {
        if (d == null || v == 0) return;
        d.put(k, (d.containsKey(k) ? ((Number) d.get(k)).doubleValue() : 0) + v);
    }

    private double[] simulate(StatBlock player, boolean boss, Map<String, Object> detail, double avgOver) {
        Map<String, Object> g = gradeData();
        if (g == null) return new double[]{0, 0, 0};
        double W = window(player);
        if (W <= 0 || avgOver <= 0) return new double[]{0, 0, 0};
        List<Object> skills = Json.asList(g.get("skills"));
        int steps = (int) Math.round(W / DT);

        double skillCastsPerSec = 0;   // 공격 시 프록 트리거용(평타+스킬 시전/초, 상수)
        for (Object so : skills) {
            Map<String, Object> s = Json.asMap(so);
            if (!learned(s)) continue;
            String t = Json.str(s, "type", "");
            if ("summon".equals(t)) skillCastsPerSec += 1.0 / W;
            else if ("active".equals(t)) { double cd = Json.num(s, "cd", 0); if (cd > 0) skillCastsPerSec += (Math.floor(W / cd) + 1) / W; }
        }
        Object gbo = Json.asMap(root.get("gradeBasicAttack")).get(grade);
        Map<String, Object> gb = gbo == null ? null : Json.asMap(gbo);

        double basicDmg = 0, skillDmg = 0;
        for (int i = 0; i < steps; i++) {                 // 연속 성분(평타·도트·프록)
            double t = i * DT;
            StatBlock c = buildStats(player, boss, t);
            double recv = buildRecv(player, boss, t);
            double atkRate = 1 + c.get(ATK_SPEED) / 100.0;
            double attackRate = atkRate + skillCastsPerSec;
            if (gb != null && !gb.isEmpty()) {
                double coeff = Json.num(gb, "coeff", 0) / 100.0;
                int hits = (int) Json.num(gb, "hits", 0);
                int targets = (boss || singleTarget) ? 1 : (int) Json.num(gb, "targets", 1);
                double inc = dm.globalMultiplier(c, boss) * dm.basicAtkDmgFactor(c) * coeff * hits * targets * atkRate * DT * recv;
                basicDmg += inc; addD(detail, "평타(기본공격)", inc);
            }
            for (Object so : skills) {
                Map<String, Object> s = Json.asMap(so);
                if (s.containsKey("modifies") || !learned(s)) continue;
                String type = Json.str(s, "type", "");
                if ("passive".equals(type)) {
                    double inc = passiveProcRate(s, c, boss, atkRate, attackRate, W, skills) * DT * recv;
                    skillDmg += inc; addD(detail, label(s), inc);
                } else if (s.get("dot") instanceof Map) {
                    Map<String, Object> dot = Json.asMap(s.get("dot"));
                    if (activeAt(type, Json.num(dot, "dur", 0), Json.num(s, "cd", 0), t)) {
                        double inc = dotPerSec(dot, c, boss) * DT * recv;
                        skillDmg += inc; addD(detail, label(s) + " [도트]", inc);
                    }
                }
            }
        }
        for (Object so : skills) {                        // 이산 성분(소환·액티브 즉발 본딜)
            Map<String, Object> s = Json.asMap(so);
            if (s.containsKey("modifies") || !learned(s)) continue;
            String type = Json.str(s, "type", "");
            if ("summon".equals(type)) {
                double inc = dealPerCast(s, buildStats(player, boss, 0), boss) * buildRecv(player, boss, 0);
                skillDmg += inc; addD(detail, label(s) + " [본딜]", inc);
            } else if ("active".equals(type)) {
                double cd = Json.num(s, "cd", 0);
                if (cd <= 0) continue;
                for (double ct = 0; ct < W - 1e-9; ct += cd) {
                    double inc = dealPerCast(s, buildStats(player, boss, ct), boss) * buildRecv(player, boss, ct);
                    skillDmg += inc; addD(detail, label(s) + " [본딜]", inc);
                }
            }
        }
        if (detail != null) for (Map.Entry<String, Object> e : detail.entrySet()) e.setValue(((Number) e.getValue()).doubleValue() / avgOver);
        return new double[]{basicDmg / avgOver, skillDmg / avgOver, (basicDmg + skillDmg) / avgOver};
    }

    private static String label(Map<String, Object> s) {
        String raw = Json.str(s, "raw", Json.str(s, "type", ""));
        return raw.length() > 40 ? raw.substring(0, 40) + "…" : raw;
    }

    public double basicDps(StatBlock player, boolean boss) { return dpsBreakdown(player, boss)[0]; }
    public double skillDps(StatBlock player, boolean boss) { return dpsBreakdown(player, boss)[1]; }
    public double ownDps(StatBlock player, boolean boss) { return dpsBreakdown(player, boss)[2]; }

    private boolean learned(Map<String, Object> s) { return !s.containsKey("lvl") || companionLevel >= (int) Json.num(s, "lvl", 0); }

    /** 동료 유효 스탯 스냅샷(t=0 기준, 자체버프 반영 확인용). 공격력은 계승×버프, 그 외 버프 가산/곱. */
    public Map<String, Object> statsView(StatBlock player, boolean boss) {
        StatBlock c = buildStats(player, boss, 0.0);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (String k : new String[]{"ATK_FLAT", "ATK_PCT", "ATK_SPEED", "CRIT_RATE", "CRIT_DMG", "FINAL_DMG", "SKILL_DMG", "BASIC_ATK_DMG", "BOSS_DMG"})
            m.put(k, c.get(k));
        m.put("atkRate(초당타수)", 1 + c.get(ATK_SPEED) / 100.0);
        return m;
    }

    /** 시각 t의 동료 스탯: 계승(공격력×비율) + 유니크/레전 최종뎀(곱연산) + 그 순간 활성 자체버프(스택 램프 반영). */
    private StatBlock buildStats(StatBlock player, boolean boss, double t) {
        StatBlock c = new StatBlock(player);
        c.set(ATK_FLAT, player.get(ATK_FLAT) * (1 + player.get(ATK_PCT) / 100.0) * inheritRatioPct / 100.0);
        c.set(ATK_PCT, 0);
        if ("UNIQUE".equals(grade) || "LEGENDARY".equals(grade)) {
            Map<String, Object> ulf = Json.asMap(root.get("uniqueLegendaryFinalDmg"));
            double per = Json.num(ulf, "perLevel", 0);
            for (Object lv : Json.asList(ulf.get("levels"))) if (lv instanceof Number && companionLevel >= ((Number) lv).intValue()) c.set(FINAL_DMG, multPct(c.get(FINAL_DMG), per));
        }
        Map<String, Object> g = gradeData();
        for (Object so : Json.asList(g.get("skills"))) {
            Map<String, Object> s = Json.asMap(so);
            if (!learned(s)) continue;
            collectBuffs(s, boss, t, false, (stat, v) -> applyStat(c, stat, v));   // 동료 자신(target companion/both) 버프
        }
        return c;
    }

    /** 버프 1건을 (스탯, 환산값)으로 흘려보내는 싱크. */
    private interface BuffSink { void add(String stat, double value); }

    /** 버프 조건 충족 여부. HP/MP 등 플레이어 자원 조건은 충족 가정(딜 상황 상시 참), 그 외(적 상태 등)는 미적용. */
    private static boolean condMet(Map<String, Object> b) {
        if (!b.containsKey("cond")) return true;
        String c = Json.str(b, "cond", "");
        return c.contains("HP") || c.contains("MP");
    }

    /**
     * 한 스킬이 주는 버프(직접 buffs[] + proc.buff)를 대상(wantPlayer: 플레이어=player/both, false=동료=companion/both)에 맞춰 수집.
     * 직접 버프는 시각 t의 활성·스택 반영, proc(발동형) 버프는 소환 중 풀가동(최대 중첩) 가정. used=false·조건 미충족은 제외.
     */
    private void collectBuffs(Map<String, Object> s, boolean boss, double t, boolean wantPlayer, BuffSink sink) {
        String type = Json.str(s, "type", "");
        if (s.get("buffs") instanceof List)
            for (Object bo : Json.asList(s.get("buffs"))) emitBuff(s, Json.asMap(bo), type, boss, t, wantPlayer, false, sink);
        if (s.get("proc") instanceof Map) {
            Object pb = Json.asMap(s.get("proc")).get("buff");
            if (pb instanceof Map) emitBuff(s, Json.asMap(pb), type, boss, t, wantPlayer, true, sink);
        }
    }

    private void emitBuff(Map<String, Object> s, Map<String, Object> b, String type, boolean boss, double t,
                          boolean wantPlayer, boolean isProc, BuffSink sink) {
        if (!Json.bool(b, "used", false) || !condMet(b)) return;
        String tgt = Json.str(b, "target", "");
        boolean ok = wantPlayer ? ("player".equals(tgt) || "both".equals(tgt)) : ("companion".equals(tgt) || "both".equals(tgt));
        if (!ok) return;
        int stacks;
        if (isProc) {
            stacks = (int) Json.num(b, "maxStacks", 1);   // 발동형 버프는 소환 중 최대중첩 풀가동 가정
        } else {
            double dur = Json.num(b, "dur", Json.num(s, "dur", 0));
            if (!activeAt(type, dur, Json.num(s, "cd", 0), t)) return;
            stacks = stacksAt(b, boss, t);
        }
        String stat = Json.str(b, "stat", "");
        double base = Json.num(b, "value", 0);
        sink.add(stat, "FINAL_DMG".equals(stat) ? (Math.pow(1 + base / 100.0, stacks) - 1) * 100 : base * stacks);
    }

    /** 시각 t의 받피증 배수 = 1 + 동료 자체 받피증(분수) + 플레이어가 적에 건 받피증(공유). 보스 디버프라 동료 딜에도 적용. */
    private double buildRecv(StatBlock player, boolean boss, double t) {
        return 1 + companionRecvSum(boss, t) + playerRecvBonus;
    }

    /** 시각 t의 동료 '자체' 받피증 합(분수, 합연산). 플레이어에게 공유할 양 = 이 값(시간평균). */
    private double companionRecvSum(boolean boss, double t) {
        Map<String, Object> g = gradeData();
        if (g == null) return 0;
        double sum = 0;
        for (Object so : Json.asList(g.get("skills"))) {
            Map<String, Object> s = Json.asMap(so);
            if (!learned(s)) continue;
            String type = Json.str(s, "type", "");
            if (s.get("debuffs") instanceof List) {
                for (Object dobj : Json.asList(s.get("debuffs"))) {
                    Map<String, Object> d = Json.asMap(dobj);
                    if (!Json.bool(d, "used", false) || !"RECV_DMG".equals(Json.str(d, "stat", ""))) continue;
                    if (Json.bool(d, "bossOnly", false) && !boss) continue;
                    if (!activeAt(type, Json.num(d, "dur", Json.num(s, "dur", 0)), Json.num(s, "cd", 0), t)) continue;
                    sum += Json.num(d, "value", 0) * (boss ? Json.num(d, "bossMult", 1) : 1);
                }
            }
            // 발동형 디버프(보우마 '1초마다 받피증'): 패시브면 소환 내내 갱신 → 활성 취급.
            if ("passive".equals(type) && s.get("proc") instanceof Map) {
                Object dbo = Json.asMap(s.get("proc")).get("debuff");
                if (dbo instanceof Map) {
                    Map<String, Object> d = Json.asMap(dbo);
                    if (Json.bool(d, "used", false) && "RECV_DMG".equals(Json.str(d, "stat", "")))
                        sum += Json.num(d, "value", 0) * (boss ? Json.num(d, "bossMult", 1) : 1);
                }
            }
        }
        return sum / 100.0;
    }

    /**
     * 소환 유지창(W) 동안 동료가 <b>플레이어</b>에게 주는 효과(시간평균). 플레이어 본인 DPS에 소환 가동률만큼 가중 반영용.
     * @return {"buffs": Map&lt;stat,평균값&gt;(target player/both), "recvBonus": 동료 자체 받피증 평균(분수)}
     */
    public Map<String, Object> playerGift(StatBlock player, boolean boss) {
        Map<String, Object> buffs = new java.util.LinkedHashMap<>();
        double recvSum = 0;
        Map<String, Object> g = gradeData();
        double W = window(player);
        if (g != null && W > 0) {
            int steps = (int) Math.round(W / DT);
            for (int i = 0; i < steps; i++) {
                double t = i * DT;
                recvSum += companionRecvSum(boss, t) * DT;
                for (Object so : Json.asList(g.get("skills"))) {
                    Map<String, Object> s = Json.asMap(so);
                    if (!learned(s)) continue;
                    collectBuffs(s, boss, t, true, (stat, v) -> addD(buffs, stat, v * DT));   // 플레이어(target player/both) 버프
                }
            }
            for (Map.Entry<String, Object> e : buffs.entrySet()) e.setValue(((Number) e.getValue()).doubleValue() / W);
            recvSum /= W;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("buffs", buffs);
        out.put("recvBonus", recvSum);
        return out;
    }

    /**
     * 플레이어 스탯에 동료 선물 버프 적용. 공격력%는 거대한 기어 공퍼풀에 합산하면 묻히므로 <b>별도 곱버킷</b>(ATK_FLAT×(1+v/100))으로 처리
     * (플레이어 본인 스킬 공퍼와 동일 취급). 최종뎀=곱, 공속=DIMINISHING, 그 외=가산.
     */
    public static void applyGift(StatBlock target, Map<String, Object> giftBuffs) {
        if (giftBuffs == null) return;
        for (Map.Entry<String, Object> e : giftBuffs.entrySet()) {
            String stat = e.getKey();
            double val = ((Number) e.getValue()).doubleValue();
            if ("ATK_PCT".equals(stat)) target.set(ATK_FLAT, target.get(ATK_FLAT) * (1 + val / 100.0));   // 공격력% = 별도 곱버킷
            else applyStat(target, stat, val);
        }
    }

    /** 시각 t에 효과 활성 여부. 패시브=항상, 소환=t<지속, 액티브=(t mod 쿨)<지속(쿨마다 재시전). 지속0=비활성. */
    private static boolean activeAt(String type, double dur, double cd, double t) {
        if ("passive".equals(type)) return true;
        if (dur <= 0) return false;
        if (!"active".equals(type)) return t < dur;       // summon
        if (cd <= 0) return t < dur;
        return (t % cd) < dur;
    }

    /** 시각 t의 자체버프 중첩 수. perEnemy=보스1/일반최대, 시간스택(stackPeriod)=floor(t/주기) 램프(최대캡), 그 외=1. */
    private int stacksAt(Map<String, Object> b, boolean boss, double t) {
        if (!b.containsKey("maxStacks")) return 1;
        int max = (int) Json.num(b, "maxStacks", 1);
        if (Json.bool(b, "perEnemy", false)) return boss ? 1 : max;
        if (b.containsKey("stackPeriod")) {
            double sp = Math.max(Json.num(b, "stackPeriod", 1), 1e-9);
            return (int) Math.min(max, Math.floor(t / sp));
        }
        return max;   // 발동스택 등은 근사로 최대
    }

    /** 패시브 발동 딜의 순간 DPS(초당). 주기형=1/period, 공격형=공격빈도×확률(내부쿨 상한). 조건/바이퍼 대체 처리. */
    private double passiveProcRate(Map<String, Object> s, StatBlock c, boolean boss, double atkRate, double attackRate, double W, List<Object> skills) {
        Object po = s.get("proc");
        if (!(po instanceof Map)) return 0;
        Map<String, Object> p = Json.asMap(po);
        if (Json.bool(p, "bossOnly", false) && !boss) return 0;
        double perProc = 0;
        if (p.get("deal") instanceof Map) perProc += oneDeal(Json.asMap(p.get("deal")), c, boss);
        if (p.get("dot") instanceof Map) perProc += oneDot(Json.asMap(p.get("dot")), c, boss);
        if (perProc == 0) return 0;
        if (p.containsKey("cond")) {                       // 보스 상태 조건
            String cond = Json.str(p, "cond", "");
            if (cond.contains("상태이상")) return 0;          // 보스 기절 면역 → 미발동
            if (cond.contains("버프")) return perProc * 2.0 / W;   // 30초 창에 보스 버프 2회 가정
        }
        String on = Json.str(p, "on", "");
        double cd = Json.num(s, "cd", 0);
        double rate;
        if ("period".equals(on)) {
            double period = Json.num(p, "period", 0);
            rate = period > 0 ? 1.0 / period : 0;
        } else if ("companionBasicAttack".equals(on) || "companionAttack".equals(on)) {
            double base = "companionBasicAttack".equals(on) ? atkRate : attackRate;   // 기본공격 시=평타만 / 공격 시=평타+스킬
            rate = base * Json.num(p, "chance", 100) / 100.0;
            if (cd > 0) rate = Math.min(rate, 1.0 / cd);
        } else {
            return 0;   // 미상 트리거(특정 액티브 발동 연동 등)는 미반영
        }
        // 바이퍼: 버스트→어썰트 대체. '이 스킬이 버스트일 때만'(raw가 어썰트의 modifies와 일치) 적용.
        for (Object sib : skills) {
            Map<String, Object> sm = Json.asMap(sib);
            Object spo = sm.get("proc");
            if (!(spo instanceof Map)) continue;
            Map<String, Object> sp = Json.asMap(spo);
            if (!sp.containsKey("replaceOfBurst") || !(sp.get("deal") instanceof Map)) continue;
            String modifies = Json.str(sm, "modifies", "");
            if (modifies.isEmpty() || !Json.str(s, "raw", "").contains(modifies)) continue;
            double R = Json.num(sp, "replaceOfBurst", 0) / 100.0;
            return (perProc * (1 - R) + oneDeal(Json.asMap(sp.get("deal")), c, boss) * R) * rate;
        }
        return perProc * rate;
    }

    /** 한 시전의 즉발 본딜 = 본딜 + 마무리(도트 제외 — 도트는 연속 적분). */
    private double dealPerCast(Map<String, Object> s, StatBlock c, boolean boss) {
        double v = 0;
        if (s.get("deal") instanceof Map) v += oneDeal(Json.asMap(s.get("deal")), c, boss);
        if (s.get("finisher") instanceof Map) v += oneDeal(Json.asMap(s.get("finisher")), c, boss);
        return v;
    }

    /** 단일 본딜 = G × 스킬뎀% × 계수 × 타수 × 대상수 × (보스배수) × (반복). */
    private double oneDeal(Map<String, Object> deal, StatBlock c, boolean boss) {
        double coeff = Json.num(deal, "coeff", 0) / 100.0;
        int hits = (int) Json.num(deal, "hits", 1);
        int targets = (boss || singleTarget) ? 1 : (int) Json.num(deal, "targets", 1);
        double bossMult = boss ? Json.num(deal, "bossMult", 1) : 1;
        int repeat = (int) Json.num(deal, "repeat", 1);
        return dm.globalMultiplier(c, boss) * dm.skillDmgFactor(c) * coeff * hits * targets * bossMult * Math.max(1, repeat);
    }

    /** 도트 초당 딜 = G × 스킬뎀% × 계수 × 타수 × 대상수 / 주기. */
    private double dotPerSec(Map<String, Object> dot, StatBlock c, boolean boss) {
        double coeff = Json.num(dot, "coeff", 0) / 100.0;
        int hits = (int) Json.num(dot, "hits", 1);
        int targets = (boss || singleTarget) ? 1 : (int) Json.num(dot, "targets", 1);
        double period = Json.num(dot, "period", 1);
        if (period <= 0) period = 1;
        return dm.globalMultiplier(c, boss) * dm.skillDmgFactor(c) * coeff * hits * targets / period;
    }

    /** 도트 1회 총딜(발동 프록용) = 초당딜 × 틱수(dur/period)... = 계수×타수×틱수×대상수×G×스킬뎀. */
    private double oneDot(Map<String, Object> dot, StatBlock c, boolean boss) {
        double dur = Json.num(dot, "dur", 0), period = Json.num(dot, "period", 1);
        int ticks = period > 0 ? Math.max(1, (int) Math.round(dur / period)) : 1;
        return dotPerSec(dot, c, boss) * (period <= 0 ? 1 : period) * ticks;
    }

    private static double multPct(double a, double b) { return ((1 + a / 100.0) * (1 + b / 100.0) - 1) * 100.0; }

    /** 동료 스탯에 버프값 적용. 최종뎀=곱연산, 공속=DIMINISHING(max150, 플레이어 모델과 동일), 그 외 딜 스탯=가산. 미매핑은 무시. */
    private static void applyStat(StatBlock c, String stat, double val) {
        if (val == 0) return;
        switch (stat) {
            case "FINAL_DMG": c.set("FINAL_DMG", multPct(c.get("FINAL_DMG"), val)); break;
            case "ATK_SPEED": {   // 공속 합성: cur + (max-cur)×(delta/max), max 150 (StatDef DIMINISHING과 동일)
                double cur = c.get("ATK_SPEED");
                c.set("ATK_SPEED", cur + (150 - cur) * (val / 150.0));
                break;
            }
            case "ATK_PCT": case "CRIT_RATE": case "CRIT_DMG":
            case "BOSS_DMG": case "NORMAL_DMG": case "MAX_DMG_MULT": case "MIN_DMG_MULT":
            case "SKILL_DMG": case "BASIC_ATK_DMG": case "DMG": case "DMG_AMP": case "STAT_PROP_DMG":
                c.set(stat, c.get(stat) + val); break;
            default: break;
        }
    }
}
