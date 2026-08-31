package mekicalc.timeline;

import mekicalc.timeline.ir.SupporterBook;
import mekicalc.timeline.ir.SupporterEffectIR;
import mekicalc.timeline.ir.SupporterIR;
import mekicalc.timeline.ir.SupporterSkillIR;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 동료 서브타임라인 빌더(v3.1) — 소환 1회분 [0,W]의 동료 딜을 {@link Timeline}로 펼치고,
 * 동료→플레이어 버프 창을 수집한다. 규약=_clt2_SUPPORTER_ENGINE.md.
 *
 * <ul>
 *   <li><b>평타</b>: 간격 1000/(1+공속/100)로 반복(atkRate). DmgType.BASIC.
 *   <li><b>액티브 딜</b>: 쿨마다 이산 발동(쿨감 미적용). <b>enter</b>: t=0 1회. DmgType.SKILL.
 *   <li><b>proc(onAttack)</b>: 동료 공격(평타+액티브)당 chance 누산, 내부쿨 캡. 기댓값 발동.
 *   <li><b>periodic 딜·dot</b>: 주기/틱 전개.
 *   <li><b>자기버프</b>(target companion + gradeBonus FinalDamage): 창 전체 상수 ModState로 각 슬롯에 적용.
 *   <li><b>플레이어버프</b>(target player/enemy): 창(start,end) 수집 → CalcService가 플레이어 타임라인에 주입.
 * </ul>
 * ★조건(cond) 기본 상시 가정. perHp=이미 최대값. targetCount 미반영(1마리/보스, 플레이어 엔진과 동일). 쿨감/스렙 미적용.
 */
public final class SupporterTimeline {

    private SupporterTimeline() {}

    /** 동료 버프 1건(소환 시작 상대 시각). stat=clt 원본명, value=‰. target=대상: player/both/companion/enemy(표시용·주입 게이팅). */
    public record PlayerBuff(int skillIdx, String skillName, String stat, double value, int startMs, int endMs, boolean toEnemy, String target) {}

    /** effect 대상 → 표시/주입용 target 문자열. player=소환주, companion=동료자기, both=양쪽, enemy=적. */
    private static String effTarget(SupporterEffectIR e, boolean enemy) {
        if (enemy) return "enemy";
        if (e.toPlayer() && e.toCompanion()) return "both";
        if (e.toCompanion()) return "companion";
        return "player";
    }

    /** 빌드 결과: 동료 딜 타임라인 + base 프리셋(Analyzer용) + 플레이어 버프 창. */
    public record Result(Timeline timeline, Preset companionBase, List<PlayerBuff> playerBuffs, int windowMs) {}

    /**
     * @param windowMs 소환창(동료가 실제로 행동하는 구간). 로테이션·버프 창의 상한.
     * @param simCapMs 전체 시뮬 경계(=simMs−소환시각). ★도트/지속딜은 소환창(windowMs) 이후에도 남은 지속을
     *                 계속 입히지만, 이 경계를 넘는 틱은 잘라낸다(시뮬 시간 밖 딜 미포함). windowMs≤simCapMs.
     */
    public static Result build(SupporterIR sup, int companionLevel, Preset companionBase, int windowMs, int simCapMs, SupporterBook book, boolean boss) {
        Timeline tl = new Timeline();
        List<PlayerBuff> pbuffs = new ArrayList<>();
        List<SupporterSkillIR> skills = sup.unlockedSkills(companionLevel);
        List<Integer> attackTimes = new ArrayList<>();   // 동료 공격 시각(proc 트리거)

        // 공속 = 계승 공속 + 동료 자기 공속(상수, target:companion). 슬롯(모션 간격) = 1000/(1+공속/100).
        double spd = Stat.ATK_SPEED.combine(companionBase.atkSpeed(), selfAtkSpeed(skills));   // ★공속 체감(선형합 금지)
        double slotMs = 1000.0 / (1 + spd / 100.0);
        if (slotMs < 1) slotMs = 1;

        // ── 로테이션(플레이어처럼 모션 슬롯 점유): 버프 액티브 우선 → 딜 액티브 → 평타. enter(소환 스킬)=t0 모션. ──
        SupporterSkillIR basic = null, enter = null;
        List<SupporterSkillIR> buffActives = new ArrayList<>(), dealActives = new ArrayList<>();
        for (SupporterSkillIR s : skills) {
            if (s.isBasicAttack()) basic = s;
            else if (s.isEnter()) enter = s;
            else if (s.isActive()) { if (s.hasDeal()) dealActives.add(s); else buffActives.add(s); }
        }
        List<SupporterSkillIR> actives = new ArrayList<>(buffActives);
        actives.addAll(dealActives);   // 버프 우선

        java.util.Map<Integer, Integer> nextReady = new java.util.HashMap<>();
        for (SupporterSkillIR a : actives) nextReady.put(a.idx(), 0);

        double cur = 0;
        if (enter != null) {   // 소환 스킬 = t0 모션(슬롯 점유)
            emitDeals(tl, enter, 0, DmgType.SKILL, simCapMs, attackTimes, boss);
            collectPlayerBuffs(enter, 0, effDur(enter), windowMs, pbuffs, boss);
            cur += slotMs;
        }
        while (cur < windowMs - 1e-9) {
            int ti = (int) Math.round(cur);
            SupporterSkillIR chosen = null;
            for (SupporterSkillIR a : actives) if (nextReady.get(a.idx()) <= ti) { chosen = a; break; }
            if (chosen != null) {
                if (chosen.hasDeal()) emitDeals(tl, chosen, ti, DmgType.SKILL, simCapMs, attackTimes, boss);
                collectPlayerBuffs(chosen, ti, effDur(chosen), windowMs, pbuffs, boss);   // 버프 액티브면 창 수집(딜 전용이면 no-op)
                int cd = chosen.cooldownMs() == null ? 0 : chosen.cooldownMs();
                nextReady.put(chosen.idx(), ti + Math.max(cd, (int) Math.round(slotMs)));
            } else if (basic != null) {
                emitDeals(tl, basic, ti, DmgType.BASIC, simCapMs, attackTimes, boss);   // 빈 슬롯 = 평타
            }
            cur += slotMs;
        }

        // ── periodic 딜(배경 타이머, 액션 로테이션과 별개) + periodic 버프 ──
        for (SupporterSkillIR s : skills) {
            if (!s.isPeriodic()) continue;
            int period = s.periodMs() == null ? 0 : s.periodMs();
            if (period > 0 && s.hasDeal())
                for (int pt = period; pt <= windowMs; pt += period) emitDeals(tl, s, pt, DmgType.SKILL, simCapMs, attackTimes, boss);
            collectPeriodicPlayerBuffs(s, period, windowMs, pbuffs, boss);
        }

        // ── onStart 패시브: target player/enemy 상시 버프 [0,W] (target companion은 applySelfBuffs) ──
        for (SupporterSkillIR s : skills) {
            if (!"onStart".equals(s.trigger())) continue;
            for (SupporterEffectIR e : s.effects()) {
                if (e.unused() || (!"statUp".equals(e.role()) && !"debuff".equals(e.role()))) continue;
                if (e.stat() == null || e.value() == null || !e.condActive(boss)) continue;
                boolean enemy = e.toEnemy() || "debuff".equals(e.role());
                if (!e.toPlayer() && !enemy) continue;   // companion 자기버프 제외
                pbuffs.add(new PlayerBuff(s.idx(), s.name(), e.stat(), e.value(), 0, windowMs, enemy, effTarget(e, enemy)));
            }
        }

        // ── proc(onAttack): 동료 공격당 chance 누산, 내부쿨 캡 ──
        Collections.sort(attackTimes);
        for (SupporterSkillIR s : skills) {
            if (!s.isOnAttack() || !hasActiveDeal(s, boss)) continue;   // boss 전용 proc은 일반몹에서 미발동
            int chance = s.chance() == null ? 0 : s.chance();
            int cd = s.cooldownMs() == null ? 0 : s.cooldownMs();
            double acc = 0; int lastFire = Integer.MIN_VALUE;
            for (int t : attackTimes) {
                acc += chance;
                if (acc >= 1000.0 && (cd <= 0 || t >= lastFire + cd)) {
                    acc -= 1000.0;
                    lastFire = t;
                    emitDeals(tl, s, t, DmgType.SKILL, simCapMs, null, boss);   // proc는 트리거 스트림에 재귀 안 함(v3.1)
                }
            }
        }

        // ── 동료 자기버프(상수 ModState): gradeBonus FinalDamage + target:companion statUp(onStart) ──
        applySelfBuffs(tl, sup, companionLevel, skills, book, windowMs);

        // ── 동료 자기버프 '표시용' 창(딜은 applySelfBuffs로 이미 반영, 여기선 간트 바 target=companion만) ──
        for (SupporterSkillIR s : skills)
            for (SupporterEffectIR e : s.effects()) {
                if (e.unused() || !"statUp".equals(e.role()) || e.stat() == null || e.value() == null) continue;
                if (e.toPlayer() || e.toEnemy() || !e.toCompanion()) continue;   // player/both/enemy는 위에서 수집됨 — 여기선 순수 동료
                int end = e.durationMs() != null ? Math.min(e.durationMs(), windowMs) : windowMs;
                pbuffs.add(new PlayerBuff(s.idx(), s.name(), e.stat(), e.value(), 0, end, false, "companion"));
            }
        SupporterBook.GradeBonus gb = book.gradeBonus();   // 유니크/레전 동료자기 최종뎀(+25%×도달레벨) — 표시
        if (gb != null && "FinalDamage".equals(gb.stat()) && "companion".equals(gb.target())
                && (gb.grades() == null || gb.grades().contains(sup.grade()))) {
            double sumPermille = 0;
            for (int[] lvVal : gb.byLevel()) if (companionLevel >= lvVal[0]) sumPermille += lvVal[1];
            if (sumPermille > 0) pbuffs.add(new PlayerBuff(-1, "등급 보너스", "FinalDamage", sumPermille, 0, windowMs, false, "companion"));
        }

        return new Result(tl, companionBase, pbuffs, windowMs);
    }

    /** 스킬에 현재 컨텐츠(boss)에서 활성인 deal 효과가 하나라도 있는가. */
    private static boolean hasActiveDeal(SupporterSkillIR s, boolean boss) {
        for (SupporterEffectIR e : s.effects()) if (e.isDeal() && e.condActive(boss)) return true;
        return false;
    }

    /**
     * 스킬의 deal 효과를 시각 t에 방출(즉발 + dot 틱). attackTimes!=null이면 공격 인스턴스 등록(proc 트리거). ★cond boss/nonBoss 게이팅.
     * @param simCapMs 시뮬 경계(상대시각). ★이 경계 이상(시뮬 시간 밖)의 딜/도트 틱은 방출하지 않는다(딜 미포함).
     */
    private static void emitDeals(Timeline tl, SupporterSkillIR s, int t, DmgType type, int simCapMs, List<Integer> attackTimes, boolean boss) {
        boolean anyInstant = false;
        for (SupporterEffectIR e : s.effects()) {
            if (!e.isDeal() || !e.condActive(boss)) continue;
            double frac = (e.coeff() == null ? 0 : e.coeff()) / 1000.0;   // 동료=성장/마스터리 없음
            int hits = e.hitsOr1();
            if (e.isDot() || (e.tickMs() != null && e.durationMs() != null)) {
                int tick = e.tickMs() == null ? 0 : e.tickMs();
                int dur = e.durationMs() == null ? 0 : e.durationMs();
                if (tick <= 0) continue;
                for (int k = 1; (long) tick * k <= dur; k++) {
                    int tt = t + tick * k;
                    // ★도트는 소환창(W) 이후에도 남은 지속을 계속 입히지만(인게임: 시전된 지속딜은 소환 종료 후에도 유지),
                    //   시뮬 경계(simCapMs) 밖의 틱은 자른다(시뮬 시간 밖 딜 미포함). W 초과 틱은 applySelfBuffs가
                    //   동료 자기버프(최종뎀 스택·등급보너스)를 싣지 않고, 적 디버프(받피증·방깎)는 그대로 수혜.
                    if (tt >= simCapMs) break;
                    tl.addDeal(tt, new DealEvent(s.idx(), DmgType.SKILL, frac, hits, 0.0, true));
                }
            } else if (t < simCapMs) {
                tl.addDeal(t, new DealEvent(s.idx(), type, frac, hits, 0.0, false));
                anyInstant = true;
            }
        }
        if (anyInstant && attackTimes != null) attackTimes.add(t);
    }

    /** 액티브·enter 버프(target player/enemy) → 창[t, t+지속] 수집. ★시한부는 소환 종료 후에도 남은 지속만큼 유지(엔진이 simMs로 캡). */
    private static void collectPlayerBuffs(SupporterSkillIR s, int startMs, int durMs, int windowMs, List<PlayerBuff> out, boolean boss) {
        for (SupporterEffectIR e : s.effects()) {
            if (e.unused() || (!"statUp".equals(e.role()) && !"debuff".equals(e.role()))) continue;
            if (e.stat() == null || e.value() == null || !e.condActive(boss)) continue;
            boolean enemy = e.toEnemy() || "debuff".equals(e.role());
            if (!e.toPlayer() && !enemy) continue;   // companion 자기버프는 applySelfBuffs
            int useEnd = e.durationMs() != null ? (startMs + e.durationMs()) : windowMs;   // 시한부=지속만큼 / 무기한(상시)=소환창 끝
            out.add(new PlayerBuff(s.idx(), s.name(), e.stat(), e.value(), startMs, useEnd, enemy, effTarget(e, enemy)));
        }
    }

    /** periodic 버프(target player) → dur>=period면 상시[0,W], 아니면 각 주기 창. cond 상시 가정. */
    private static void collectPeriodicPlayerBuffs(SupporterSkillIR s, int period, int windowMs, List<PlayerBuff> out, boolean boss) {
        for (SupporterEffectIR e : s.effects()) {
            if (e.unused() || !"statUp".equals(e.role())) continue;
            if (e.stat() == null || e.value() == null || (!e.toPlayer() && !e.toEnemy()) || !e.condActive(boss)) continue;
            int dur = e.durationMs() == null ? 0 : e.durationMs();
            boolean enemy = e.toEnemy();
            if (dur >= period) {   // 갱신형: 단일 창(스택X), 마지막 갱신(≤소환창) 후 dur까지 유지(종료 후에도)
                int lastGrant = period > 0 ? (windowMs / period) * period : windowMs;
                out.add(new PlayerBuff(s.idx(), s.name(), e.stat(), e.value(), 0, lastGrant + dur, enemy, effTarget(e, enemy)));
            } else {   // dur<period: 각 갱신 창(비겹침), 지속만큼 유지
                for (int t = period; t <= windowMs; t += period)
                    out.add(new PlayerBuff(s.idx(), s.name(), e.stat(), e.value(), t, t + dur, enemy, effTarget(e, enemy)));
            }
        }
    }

    /** 버프 지속(ms) — 효과 durationMs 최대(없으면 0=창전체 취급). */
    private static int effDur(SupporterSkillIR s) {
        int d = 0;
        for (SupporterEffectIR e : s.effects()) if (e.durationMs() != null) d = Math.max(d, e.durationMs());
        return d;
    }

    /** 동료 자기 공속(%) = target:companion AttackSpeed statUp 합(‰÷10). 로테이션 슬롯 간격에 반영. */
    private static double selfAtkSpeed(List<SupporterSkillIR> skills) {
        double sum = 0;
        for (SupporterSkillIR s : skills)
            for (SupporterEffectIR e : s.effects())
                if (!e.unused() && "statUp".equals(e.role()) && e.toCompanion() && "AttackSpeed".equals(e.stat()) && e.value() != null)
                    sum += e.value() / 10.0;
        return sum;
    }

    /** 동료 자기버프를 모든 딜 슬롯에 상수 적용: gradeBonus FinalDamage + target:companion statUp(onStart). ★FinalDamage=효과별 각각 곱(별도 소스). */
    private static void applySelfBuffs(Timeline tl, SupporterIR sup, int companionLevel,
                                       List<SupporterSkillIR> skills, SupporterBook book, int windowMs) {
        // 1) gradeBonus 동료 자기 최종뎀 — 도달한 각 레벨(+25%씩)을 ★별도 곱소스로(합산 아님: ×1.25×1.25×1.25).
        List<Double> selfFinalSources = new ArrayList<>();
        SupporterBook.GradeBonus gb = book.gradeBonus();
        if (gb != null && "FinalDamage".equals(gb.stat()) && "companion".equals(gb.target())
                && (gb.grades() == null || gb.grades().contains(sup.grade())))
            for (int[] lvVal : gb.byLevel()) if (companionLevel >= lvVal[0]) selfFinalSources.add(lvVal[1] / 1000.0);

        // 2) target:companion statUp — 상수(합풀 selfAdds / FinalDamage 별도 곱소스)와 램프업 스택(maxStacks>1) 분리.
        List<double[]> selfAdds = new ArrayList<>();
        List<double[]> stacking = new ArrayList<>();   // {routed ordinal(-1=final), 스택당 값, periodMs, maxStacks}
        for (SupporterSkillIR s : skills) {
            int period = s.periodMs() == null ? 0 : s.periodMs();
            for (SupporterEffectIR e : s.effects()) {
                if (e.unused() || !"statUp".equals(e.role()) || !e.toCompanion()) continue;
                if (e.stat() == null || e.value() == null) continue;
                double[] routed = routeSelf(e.stat(), e.value());
                if (routed == null) continue;
                if (e.maxStacksOr1() > 1 && period > 0) {   // 램프업 스택(예 1초마다 최종뎀 +1.5% 최대 15중첩) → per-slot
                    stacking.add(new double[]{routed[0], routed[1], period, e.maxStacksOr1()});
                } else if (routed[0] < 0) {
                    selfFinalSources.add(routed[1]);   // 상수 FinalDamage → 별도 곱소스(각각 ×)
                } else {
                    selfAdds.add(routed);
                }
            }
        }

        // 3) 각 슬롯에 적용 (스택은 슬롯 시각 t의 스택수 = min(maxStacks, t/periodMs)로 램프업)
        for (java.util.Map.Entry<Integer, Slot> en : tl.slots().entrySet()) {
            int t = en.getKey();
            if (t > windowMs) continue;   // ★소환창 이후 슬롯(연장된 도트 틱)은 동료 자기버프(최종뎀 스택·등급보너스) 미적용 — 소환 종료로 사라짐
            ModState m = en.getValue().mods();
            for (double[] a : selfAdds) m.addStat(Stat.values()[(int) a[0]], a[1]);
            for (double f : selfFinalSources) m.addFinalDmgSource(f);   // 소스별 각각 ×(1+f)
            for (double[] st : stacking) {
                int stacks = (int) Math.min((long) st[3], (long) (t / (int) st[2]));
                if (stacks <= 0) continue;
                double v = st[1] * stacks;
                if (st[0] < 0) m.addFinalDmgSource(v);           // 스택 최종뎀 = (1 + 스택수×스택당) 단일 곱소스
                else m.addStat(Stat.values()[(int) st[0]], v);
            }
        }
    }

    /** clt 스탯명 → {Stat.ordinal(-1=finalDmg), 값}. BuffAccumulator.route와 동일 매핑(÷10 표시%, FinalDamage ÷1000 배율). */
    private static double[] routeSelf(String stat, double v) {
        return switch (stat) {
            case "FinalDamage"            -> new double[]{-1, v / 1000.0};
            case "ATK_PCT"                -> new double[]{Stat.ATK_PCT.ordinal(), v / 10.0};
            case "DMG"                    -> new double[]{Stat.DMG.ordinal(), v / 10.0};
            case "CriticalChance"         -> new double[]{Stat.CRIT_RATE.ordinal(), v / 10.0};
            case "CriticalPower"          -> new double[]{Stat.CRIT_DMG.ordinal(), v / 10.0};
            case "AttackPowerToBoss"      -> new double[]{Stat.BOSS_DMG.ordinal(), v / 10.0};
            case "AttackPowerExcludeBoss" -> new double[]{Stat.NORMAL_DMG.ordinal(), v / 10.0};
            case "SkillPower"             -> new double[]{Stat.SKILL_DMG.ordinal(), v / 10.0};
            case "BaseAttackPower"        -> new double[]{Stat.BASIC_ATK_DMG.ordinal(), v / 10.0};
            case "MaxDamageRatio"         -> new double[]{Stat.MAX_DMG_MULT.ordinal(), v / 10.0};
            case "Weakness"               -> new double[]{Stat.WEAKNESS.ordinal(), v / 10.0};
            case "AttackSpeed"            -> new double[]{Stat.ATK_SPEED.ordinal(), v / 10.0};   // 슬롯딜엔 무효(스케줄용)이나 보존
            default -> null;
        };
    }
}
