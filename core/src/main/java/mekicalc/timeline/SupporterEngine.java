package mekicalc.timeline;

import mekicalc.timeline.ir.SupporterBook;
import mekicalc.timeline.ir.SupporterIR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 동료 엔진 오케스트레이터(v3.1) — 전체 시뮬 [0,simMs)에서 다중 소환을 돌려 동료 DPS(스킬별)와
 * 동료→플레이어 버프 주입 목록을 낸다. 규약=_clt2_SUPPORTER_ENGINE.md §3·§8.
 *
 * <ul>
 *   <li><b>스케줄</b>: 첫 소환 t0 + 90초 간격(쿨감 미적용). 각 창 [s, s+W], 시뮬 끝 넘으면 자름.
 *   <li><b>동적 스냅샷(B)</b>: 소환 시점 s의 플레이어 실효 스탯(base + 그 시점 버프)으로 계승 base 조립.
 *   <li><b>병합</b>: 각 소환 서브타임라인의 스킬별 딜을 전 구간 합산 → DPS = 총딜/(simMs/1000).
 *   <li><b>플레이어 버프</b>: 절대 시각으로 오프셋해 반환(CalcService가 플레이어 타임라인에 BuffSource 주입).
 * </ul>
 */
public final class SupporterEngine {

    private SupporterEngine() {}

    public record CompanionResult(
            Map<Integer, Double> perSkillDamage,   // 동료 스킬 idx → 전 구간 총딜
            double totalDamage,
            double dps,                            // 총딜 / (simMs/1000)
            List<SupporterTimeline.PlayerBuff> playerBuffInjections,   // 절대 시각(플레이어 타임라인 주입)
            int summonCount,
            List<int[]> summonWindows,             // 소환창 [start,end] 절대(간트 표시용)
            Map<Integer, List<double[]>> dealTicks, // 동료 스킬 idx → [{t절대, dmg}] (간트 딜 라인용)
            Map<Integer, Long> perSkillHits,       // 동료 스킬 idx → 전 구간 총 타수(한줄 데미지 산출용)
            Map<Integer, Long> perSkillCasts       // 동료 스킬 idx → 전 구간 시전 횟수
    ) {}

    /**
     * @param playerBase   플레이어 base 프리셋(선반영 포함)
     * @param playerBuffs  플레이어 버프 소스(BuffAccumulator.buildSources) — 동적 스냅샷용. null=정적(base만)
     * @param firstSummonMs 첫 소환 시각(유저 지정)
     */
    public static CompanionResult run(Preset playerBase, List<BuffSource> playerBuffs,
                                      String companionCode, int companionLevel,
                                      int firstSummonMs, int simMs, boolean boss, double monsterDef,
                                      SupporterBook book) {
        SupporterIR sup = book.supporter(companionCode);
        if (sup == null) return new CompanionResult(Map.of(), 0, 0, List.of(), 0, List.of(), Map.of(), Map.of(), Map.of());
        int cd = sup.summonCooldownMs();

        Map<Integer, Double> per = new HashMap<>();
        Map<Integer, Long> hitsPer = new HashMap<>();
        Map<Integer, Long> castsPer = new HashMap<>();
        double total = 0;
        int count = 0;
        List<SupporterTimeline.PlayerBuff> injections = new ArrayList<>();
        List<int[]> summonWindows = new ArrayList<>();
        Map<Integer, List<double[]>> dealTicks = new HashMap<>();

        for (int s = Math.max(0, firstSummonMs); s < simMs; s += (cd > 0 ? cd : simMs)) {
            // 동적 스냅샷(B): 소환 시점 s의 플레이어 실효 스탯
            Preset playerEff = effectivePlayerPreset(playerBase, modStateAt(playerBuffs, s));
            Preset cbase = SupporterInherit.build(playerEff, sup, companionLevel, book);
            int W = SupporterInherit.windowMs(playerEff, sup);
            int usableW = Math.min(W, simMs - s);
            if (usableW <= 0) break;

            SupporterTimeline.Result r = SupporterTimeline.build(sup, companionLevel, cbase, usableW, book, boss);
            // 적 받피증(Weakness) → 동료 딜에도 적용(적에 붙은 디버프=모든 공격자 수혜, §5). 소스: 플레이어 Weakness(절대시각) + 동료 자기 enemy 디버프.
            applyEnemyRecvDmg(r.timeline(), r.playerBuffs(), playerBuffs, s);
            Analyzer an = new Analyzer(r.timeline(), cbase, monsterDef, boss, usableW);
            for (Map.Entry<Integer, Double> e : an.perSkillDamage().entrySet()) {
                per.merge(e.getKey(), e.getValue(), Double::sum);
                total += e.getValue();
                hitsPer.merge(e.getKey(), an.skillHits(e.getKey()), Long::sum);     // 한줄 데미지·시전 횟수 집계(소환 누적)
                castsPer.merge(e.getKey(), an.skillCasts(e.getKey()), Long::sum);
            }
            for (SupporterTimeline.PlayerBuff b : r.playerBuffs())
                injections.add(new SupporterTimeline.PlayerBuff(b.skillIdx(), b.skillName(),
                        b.stat(), b.value(), s + b.startMs(), Math.min(s + b.endMs(), simMs), b.toEnemy(), b.target()));
            // 간트용: 소환창 + 스킬별 딜 틱(절대 시각)
            summonWindows.add(new int[]{s, s + usableW});
            for (int idx : r.timeline().dealingSkills()) {
                List<double[]> ticks = dealTicks.computeIfAbsent(idx, k -> new ArrayList<>());
                for (int t : r.timeline().timesOf(idx)) ticks.add(new double[]{s + t, an.skillDamageAt(idx, t)});
            }
            count++;
            if (cd <= 0) break;
        }

        double dps = simMs > 0 ? total / (simMs / 1000.0) : 0;
        return new CompanionResult(per, total, dps, injections, count, summonWindows, dealTicks, hitsPer, castsPer);
    }

    /**
     * 동료→플레이어 버프(PlayerBuff) → 플레이어 타임라인 주입용 {@link BuffSource}(3단계).
     * clt 스탯명 → ModState 라우팅(BuffAccumulator.route와 동일). ★AttackSpeed는 스케줄 변수라 슬롯딜 무효(스킵, v3.2).
     */
    public static BuffSource toBuffSource(SupporterTimeline.PlayerBuff pb) {
        BuffSource src = new BuffSource("동료:" + pb.stat(), pb.startMs(), pb.endMs());
        switch (pb.stat()) {
            case "FinalDamage"            -> src.finalDmgFrac += pb.value() / 1000.0;
            case "ATK_PCT"                -> src.add(Stat.ATK_PCT, pb.value() / 10.0);
            case "DMG"                    -> src.add(Stat.DMG, pb.value() / 10.0);
            case "CriticalChance"         -> src.add(Stat.CRIT_RATE, pb.value() / 10.0);
            case "CriticalPower"          -> src.add(Stat.CRIT_DMG, pb.value() / 10.0);
            case "MaxDamageRatio"         -> src.add(Stat.MAX_DMG_MULT, pb.value() / 10.0);
            case "AttackPowerToBoss"      -> src.add(Stat.BOSS_DMG, pb.value() / 10.0);
            case "AttackPowerExcludeBoss" -> src.add(Stat.NORMAL_DMG, pb.value() / 10.0);
            case "SkillPower"             -> src.add(Stat.SKILL_DMG, pb.value() / 10.0);
            case "BaseAttackPower"        -> src.add(Stat.BASIC_ATK_DMG, pb.value() / 10.0);
            case "Weakness", "RECV_DMG"   -> src.add(Stat.WEAKNESS, pb.value() / 10.0);        // 받피증(적 디버프)
            case "EnemyCritResist", "CriticalResist", "CRIT_RESIST" -> src.add(Stat.CRIT_RATE_ENEMY, pb.value() / 10.0); // 적 크저감
            case "EnemyRecvCritDmg"       -> src.add(Stat.CRIT_DMG_ENEMY, pb.value() / 10.0); // 적 받크뎀↑
            case "AttackSpeed"            -> src.add(Stat.ATK_SPEED, pb.value() / 10.0);       // 슬롯딜엔 무효지만 파생-공속 입력 + 스케줄 창(별도)용
            default -> { }
        }
        return src;
    }

    /**
     * 동료→플레이어 버프 주입(3단계) — 플레이어 타임라인의 각 슬롯 ModState에 반영해 플레이어 딜을 높인다.
     * 기존 슬롯(딜 시각)에만 적용(BuffAccumulator.accumulate와 동일 방식).
     */
    /** BuffSource 목록을 타임라인 슬롯에 반영(파생/순환 계산 전 동료 버프 슬롯 주입용 — buildTimeline에서 호출). */
    public static void accumulateBuffSources(Timeline tl, List<BuffSource> sources, int simMs) {
        for (BuffSource cs : sources) {
            if (cs.dormant()) continue;
            boolean incl = cs.endMs() >= simMs;
            for (Slot slot : tl.slots().subMap(cs.startMs(), true, cs.endMs(), incl).values()) cs.applyTo(slot.mods());
        }
    }

    public static void injectBuffs(Timeline tl, List<SupporterTimeline.PlayerBuff> injections, int simMs) {
        for (SupporterTimeline.PlayerBuff pb : injections) {
            BuffSource src = toBuffSource(pb);
            boolean incl = src.endMs() >= simMs;
            for (Slot slot : tl.slots().subMap(src.startMs(), true, src.endMs(), incl).values())
                src.applyTo(slot.mods());
        }
    }

    /** 적에 붙는 디버프(모든 공격자 수혜) — DamageFormula가 ModState에서 읽는 적측 항. */
    private static final Stat[] ENEMY_DEBUFFS = { Stat.WEAKNESS, Stat.CRIT_RATE_ENEMY, Stat.CRIT_DMG_ENEMY };

    /** clt 스탯명 → 적 디버프 ModState 항(없으면 null). */
    private static Stat enemyDebuffStat(String stat) {
        return switch (stat == null ? "" : stat) {
            case "Weakness", "RECV_DMG" -> Stat.WEAKNESS;
            case "EnemyCritResist", "CriticalResist", "CRIT_RESIST" -> Stat.CRIT_RATE_ENEMY;
            case "EnemyRecvCritDmg" -> Stat.CRIT_DMG_ENEMY;
            default -> null;
        };
    }

    /**
     * 적 디버프를 동료 딜 슬롯에 적용(§5, 적에 붙은 디버프=모든 공격자 수혜). ★계승 스탯 아님(스냅샷 X) — 동적 적용.
     * 소스: (1) 플레이어의 적 디버프(그 절대시각 상태) (2) 동료 자기 enemy 디버프(상대시각 창).
     */
    private static void applyEnemyRecvDmg(Timeline ctl, List<SupporterTimeline.PlayerBuff> compBuffs,
                                          List<BuffSource> playerSrc, int summonStart) {
        for (Map.Entry<Integer, Slot> en : ctl.slots().entrySet()) {
            int t = en.getKey();
            ModState cm = en.getValue().mods();
            ModState pm = modStateAt(playerSrc, summonStart + t);   // 플레이어 그 절대시각 상태(적 디버프 포함)
            for (Stat st : ENEMY_DEBUFFS) {
                double v = pm.get(st);
                if (v != 0) cm.addStat(st, v);
            }
            for (SupporterTimeline.PlayerBuff b : compBuffs) {       // 동료 자기 enemy 디버프(상대시각 t에 활성)
                if (!b.toEnemy() || t < b.startMs() || t >= b.endMs()) continue;
                Stat st = enemyDebuffStat(b.stat());
                if (st != null) cm.addStat(st, b.value() / 10.0);
            }
        }
    }

    /** 버프 소스에서 시각 s의 ModState 재구성(그 시점 활성 창 전부 적용). */
    static ModState modStateAt(List<BuffSource> sources, int s) {
        ModState m = new ModState();
        if (sources != null)
            for (BuffSource src : sources)
                if (!src.dormant() && src.startMs() <= s && s < src.endMs()) src.applyTo(m);
        return m;
    }

    /**
     * 소환 시점 플레이어 실효 프리셋 = base + 그 시점 ModState(버프) 접기.
     * 공격력%는 곱(base×(1+공%)×(1+버프공%)), 나머지 %스탯은 합, 최종뎀은 (1+base)×Π소스를 등가 합%로 접음.
     */
    static Preset effectivePlayerPreset(Preset b, ModState m) {
        Map<String, Double> s = new HashMap<>();
        double effAtk = b.atkFlat() * (1 + b.atkPct() / 100.0) * (1 + m.get(Stat.ATK_PCT) / 100.0);
        s.put("ATK_FLAT", effAtk);
        s.put("ATK_PCT", 0.0);
        s.put("CRIT_RATE", b.critRate() + m.get(Stat.CRIT_RATE) + m.get(Stat.CRIT_RATE_ENEMY));
        s.put("CRIT_DMG", b.critDmg() + m.get(Stat.CRIT_DMG) + m.get(Stat.CRIT_DMG_ENEMY));
        s.put("ATK_SPEED", Stat.ATK_SPEED.combine(b.atkSpeed(), m.get(Stat.ATK_SPEED)));   // ★공속 체감(선형합 금지)
        s.put("DMG", b.dmg() + b.dmgAdd() + m.get(Stat.DMG));
        s.put("DMG_AMP", b.dmgAmp() + m.get(Stat.DMG_AMP));
        s.put("STAT_PROP_DMG", b.statProp() + m.get(Stat.STAT_PROP));
        s.put("BASIC_ATK_DMG", b.basicAtkDmg() + m.get(Stat.BASIC_ATK_DMG));
        s.put("SKILL_DMG", b.skillDmg() + m.get(Stat.SKILL_DMG));
        s.put("BOSS_DMG", b.bossDmg() + m.get(Stat.BOSS_DMG));
        s.put("NORMAL_DMG", b.normalDmg() + m.get(Stat.NORMAL_DMG));
        s.put("DEF_PEN", b.defPen() + m.get(Stat.DEF_PEN));
        s.put("MAX_DMG_MULT", b.maxMult() + m.get(Stat.MAX_DMG_MULT));
        s.put("MIN_DMG_MULT", b.minMult());
        double effFinal = ((1 + b.finalDmg() / 100.0) * m.finalDmgMult() - 1) * 100.0;
        s.put("FINAL_DMG", effFinal);
        s.put("LEVEL", (double) b.level());
        s.put("SUMMON_DURATION", b.get("SUMMON_DURATION"));
        return new Preset(s);
    }
}
