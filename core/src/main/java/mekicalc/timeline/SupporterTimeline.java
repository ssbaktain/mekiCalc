package mekicalc.timeline;

import mekicalc.timeline.ir.SupporterBook;
import mekicalc.timeline.ir.SupporterEffectIR;
import mekicalc.timeline.ir.SupporterIR;
import mekicalc.timeline.ir.SupporterSkillIR;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** 추가 최종뎀(체크박스) = 동료 무관 고정 +25%(‰). 자유 세팅 비교용 별도 곱소스. */
    private static final int INSTANT_FINAL_DMG = 250;

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
    public static Result build(SupporterIR sup, int companionLevel, Preset companionBase, int windowMs, int simCapMs, SupporterBook book, boolean boss, boolean instantBonus) {
        Timeline tl = new Timeline();
        List<PlayerBuff> pbuffs = new ArrayList<>();
        List<SupporterSkillIR> skills = sup.unlockedSkills(companionLevel);
        List<Integer> attackTimes = new ArrayList<>();        // 동료 전체 공격 시각(onAttack proc)
        List<Integer> basicAttackTimes = new ArrayList<>();   // ★평타 시각(basicAttack-only proc, 예 씨서펜트버스트)
        Map<Integer, List<Integer>> activeCastTimes = new HashMap<>();   // ★스킬idx→시전 시각(triggerSkillIdx proc, 예 811480)
        Map<String, List<int[]>> stateWindows = new HashMap<>();         // ★상태이름→[start,end] 창(opensState/whenState 스왑)

        // 공속 = 계승 공속 + 동료 자기 공속(target:companion). ★가변 rate: 매 슬롯에서 '그 시점 활성' 공속으로 슬롯 간격 재계산.
        //   시한부 공속(ENTER 15초 등)이 창 전체가 아니라 실제 활성 구간에서만 공격 빈도를 올리도록(정확). slotMsAt 헬퍼가 산출.

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
            emitDeals(tl, enter, 0, DmgType.SKILL, simCapMs, windowMs, attackTimes, boss, stateWindows, slotMsAt(companionBase, skills, 0, activeCastTimes) / 1000.0);
            collectPlayerBuffs(enter, 0, effDur(enter), windowMs, pbuffs, boss);
            cur += slotMsAt(companionBase, skills, 0, activeCastTimes);   // 소환 모션 = t0 공속
        }
        while (cur < windowMs - 1e-9) {
            int ti = (int) Math.round(cur);
            double slotMs = slotMsAt(companionBase, skills, ti, activeCastTimes);   // ★가변: 이 시점 활성 공속으로 슬롯 간격
            SupporterSkillIR chosen = null;
            for (SupporterSkillIR a : actives) if (nextReady.get(a.idx()) <= ti) { chosen = a; break; }
            if (chosen != null) {
                if (chosen.hasDeal()) emitDeals(tl, chosen, ti, DmgType.SKILL, simCapMs, windowMs, attackTimes, boss, stateWindows, slotMs / 1000.0);
                collectPlayerBuffs(chosen, ti, effDur(chosen), windowMs, pbuffs, boss);   // 버프 액티브면 창 수집(딜 전용이면 no-op)
                activeCastTimes.computeIfAbsent(chosen.idx(), k -> new ArrayList<>()).add(ti);   // ★특정스킬 편승(triggerSkillIdx)용
                if (chosen.opensState() != null && chosen.opensStateMs() != null)              // ★상태창 열기(서펜트스톤 등)
                    stateWindows.computeIfAbsent(chosen.opensState(), k -> new ArrayList<>()).add(new int[]{ti, ti + chosen.opensStateMs()});
                int cd = chosen.cooldownMs() == null ? 0 : chosen.cooldownMs();
                nextReady.put(chosen.idx(), ti + Math.max(cd, (int) Math.round(slotMs)));
            } else if (basic != null) {
                emitDeals(tl, basic, ti, DmgType.BASIC, simCapMs, windowMs, attackTimes, boss, stateWindows, slotMs / 1000.0);   // 빈 슬롯 = 평타
                basicAttackTimes.add(ti);   // ★평타 시각(basicAttack-only proc용)
            }
            cur += slotMs;
        }

        // ── periodic 딜(배경 타이머, 액션 로테이션과 별개) + periodic 버프 ──
        for (SupporterSkillIR s : skills) {
            if (!s.isPeriodic()) continue;
            int period = s.periodMs() == null ? 0 : s.periodMs();
            if (period > 0 && s.hasDeal())
                for (int pt = period; pt <= windowMs; pt += period) emitDeals(tl, s, pt, DmgType.SKILL, simCapMs, windowMs, attackTimes, boss, stateWindows, 1.0);
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

        // ── proc(onAttack / onActiveSkill): 트리거 시각마다 chance 누산, 내부쿨 캡 ──
        //   트리거 스트림: triggerSkillIdx=특정스킬 시전(811480) / triggerBy basicAttack=평타만(씨서펜트버스트) / 그 외=전공격·전액션.
        //   onAttack=확률형(chance), onActiveSkill=편승(chance 없으면 100%·cd로만 캡). 딜은 stateWindows로 버스트↔어썰트 스왑.
        Collections.sort(attackTimes);
        for (SupporterSkillIR s : skills) {
            boolean onAtk = s.isOnAttack(), onAct = s.isOnActiveSkill();
            if ((!onAtk && !onAct) || !hasActiveDeal(s, boss)) continue;   // boss 전용 proc은 일반몹에서 미발동
            List<Integer> triggerTimes;
            if (s.triggerSkillIdx() != null) triggerTimes = activeCastTimes.getOrDefault(s.triggerSkillIdx(), List.of());   // 특정스킬 시전 편승
            else if (s.basicAttackOnly()) triggerTimes = basicAttackTimes;   // 평타 한정
            else triggerTimes = attackTimes;                                 // 전공격/전액션
            int chance = s.chance() == null ? (onAct ? 1000 : 0) : s.chance();   // onActiveSkill 기본 100%
            int cd = s.cooldownMs() == null ? 0 : s.cooldownMs();
            double acc = 0; int lastFire = Integer.MIN_VALUE;
            for (int t : triggerTimes) {
                acc += chance;
                if (acc >= 1000.0 && (cd <= 0 || t >= lastFire + cd)) {
                    acc -= 1000.0;
                    lastFire = t;
                    emitDeals(tl, s, t, DmgType.SKILL, simCapMs, windowMs, null, boss, stateWindows, 1.0);   // proc는 트리거 스트림에 재귀 안 함(v3.1), 상태창 스왑 반영
                    collectPlayerBuffs(s, t, effDur(s), windowMs, pbuffs, boss);              // ★proc의 버프/디버프(예 811480 받피증)도 발동마다 수집
                }
            }
        }

        // ── onAttack 버프-only proc(딜 없는 확률 버프, 예 팔라딘 홀리차지·히어로 콤보어택): ★확률 누산식(SIM_MODEL §79) ──
        //   공격마다 chance 누산 → 1000 도달 시 발동, 발동마다 durMs 독립 창(DLL 확인: 상태 스택=개별 SwapState 리스트=개별 지속).
        //   겹치는 창 수 = 스택(캡 maxStacks). 플레이어/적=스택 세그먼트 창 · 동료=슬롯별 스택. (딜 있는 onAttack proc은 위 proc path에서 처리.)
        //   ★모든 슬롯(로테 + periodic + proc딜) 생성 후 실행 — 동료 슬롯별 스택 반영 위해.
        for (SupporterSkillIR s : skills) {
            if (!s.isOnAttack() || hasActiveDeal(s, boss)) continue;
            int chance = s.chance() == null ? 0 : s.chance();
            List<Integer> stream = s.basicAttackOnly() ? basicAttackTimes : attackTimes;   // 트리거 스트림(평타-only/전공격)
            for (SupporterEffectIR e : s.effects()) {
                if (e.unused() || (!"statUp".equals(e.role()) && !"debuff".equals(e.role()))) continue;
                if (e.stat() == null || e.value() == null || !e.condActive(boss)) continue;
                int dur = e.durationMs() == null ? 0 : e.durationMs();
                int maxS = e.maxStacksOr1();
                // ★지속 방식: individualDur=은펜던트식 독립 창(겹침=스택), 기본=공유 타이머(refresh, 재발동 시 전체 갱신+스택+1, 만료 시 동시 소멸).
                List<int[]> segs = e.individualDurOn()
                        ? stackSegments(procWindows(stream, chance, dur), maxS)
                        : sharedStackSegments(stream, chance, dur, maxS);
                boolean enemy = e.toEnemy() || "debuff".equals(e.role());
                if (e.toPlayer() || enemy)                                     // 플레이어/적: 스택 세그먼트별 창(value×스택)
                    for (int[] sg : segs)
                        pbuffs.add(new PlayerBuff(s.idx(), s.name(), e.stat(), e.value() * sg[2], sg[0], sg[1], enemy, effTarget(e, enemy)));
                if (e.toCompanion()) {                                         // 동료: 딜슬롯 시각의 스택수만큼
                    double[] routed = routeSelf(e.stat(), e.value());
                    if (routed != null)
                        for (var slotEn : tl.slots().entrySet()) {
                            int t = slotEn.getKey(); if (t > windowMs) continue;
                            int st = stackAtSeg(segs, t); if (st <= 0) continue;
                            if (routed[0] < 0) slotEn.getValue().mods().addFinalDmgSource(routed[1] * st);
                            else slotEn.getValue().mods().addStat(Stat.values()[(int) routed[0]], routed[1] * st);
                        }
                }
            }
        }

        // ── 동료 자기버프(상수 ModState): gradeBonus FinalDamage + target:companion statUp(onStart) + 인스턴트 보너스 ──
        applySelfBuffs(tl, sup, companionLevel, skills, book, windowMs, instantBonus, activeCastTimes, boss);

        // ── 동료 자기버프 '표시용' 창(딜은 applySelfBuffs로 이미 반영, 여기선 간트 바 target=companion만) ──
        //   ★durationMs 있는 액티브=시전마다 창 / enter=[0,dur] / 그 외(onStart)=창 전체. (applySelfBuffs 윈도잉과 동일)
        for (SupporterSkillIR s : skills)
            for (SupporterEffectIR e : s.effects()) {
                if (e.unused() || !"statUp".equals(e.role()) || e.stat() == null || e.value() == null || !e.condActive(boss)) continue;
                if (e.toPlayer() || e.toEnemy() || !e.toCompanion()) continue;   // player/both/enemy는 위에서 수집됨 — 여기선 순수 동료
                int period = s.periodMs() == null ? 0 : s.periodMs();
                if (e.maxStacksOr1() > 1 && period > 0) {   // ★램프업 스택(나로 809470 최종뎀 1초당 +1.5% 최대 15중첩): 표시도 계산과 동일하게 스택별 램프
                    int maxS = e.maxStacksOr1();
                    boolean finalStack = "FinalDamage".equals(e.stat());   // 최종뎀 스택=곱적용(×(1+값)^스택), 그 외=합(값×스택)
                    for (int k = 1; k <= maxS; k++) {
                        int start = k * period; if (start >= windowMs) break;   // t∈[k·period,(k+1)·period) → stacks=min(max, t/period)=k
                        int end = k < maxS ? Math.min((k + 1) * period, windowMs) : windowMs;
                        double dispVal = finalStack ? (Math.pow(1.0 + e.value() / 1000.0, k) - 1.0) * 1000.0 : e.value() * k;
                        pbuffs.add(new PlayerBuff(s.idx(), s.name(), e.stat(), dispVal, start, end, false, "companion"));
                    }
                } else if (e.durationMs() != null && s.isActive()) {   // 액티브 시전마다 창
                    int dur = e.durationMs();
                    for (int ct : activeCastTimes.getOrDefault(s.idx(), List.of()))
                        pbuffs.add(new PlayerBuff(s.idx(), s.name(), e.stat(), e.value(), ct, Math.min(ct + dur, windowMs), false, "companion"));
                } else {
                    int end = e.durationMs() != null ? Math.min(e.durationMs(), windowMs) : windowMs;   // enter=[0,dur] / onStart=창전체
                    pbuffs.add(new PlayerBuff(s.idx(), s.name(), e.stat(), e.value(), 0, end, false, "companion"));
                }
            }
        SupporterBook.GradeBonus gb = book.gradeBonus();   // 유니크/레전 동료자기 최종뎀 — ★각 +25%가 별도 스킬=곱적용(합 아님)
        if (gb != null && "FinalDamage".equals(gb.stat()) && "companion".equals(gb.target())
                && (gb.grades() == null || gb.grades().contains(sup.grade()))) {
            // ★표시도 계산(applySelfBuffs)과 동일하게 곱적용 실효값으로: ∏(1+각/1000)−1. (예 25%×3 = ×1.25³ = +95.3%, +75% 아님)
            double mult = 1.0; int n = 0;
            for (int[] lvVal : gb.byLevel()) if (companionLevel >= lvVal[0]) { mult *= (1 + lvVal[1] / 1000.0); n++; }
            if (n > 0) pbuffs.add(new PlayerBuff(-1, "등급 보너스", "FinalDamage", (mult - 1) * 1000.0, 0, windowMs, false, "companion"));
        }
        if (instantBonus)   // 추가 최종뎀 표시(체크박스 ON, 동료 무관 +25%)
            pbuffs.add(new PlayerBuff(-2, "추가 최종뎀", "FinalDamage", (double) INSTANT_FINAL_DMG, 0, windowMs, false, "companion"));

        return new Result(tl, companionBase, pbuffs, windowMs);
    }

    /** 스킬에 현재 컨텐츠(boss)에서 활성인 deal 효과가 하나라도 있는가. */
    private static boolean hasActiveDeal(SupporterSkillIR s, boolean boss) {
        for (SupporterEffectIR e : s.effects()) if (e.isDeal() && e.condActive(boss)) return true;
        return false;
    }

    /** ★범용 상태창 조회: 이름의 상태창이 시각 t에 활성인가(opensState로 열린 [start,end] 창). windows null/없음이면 false. */
    private static boolean stateActiveAt(Map<String, List<int[]>> windows, String name, int t) {
        if (windows == null) return false;
        List<int[]> ws = windows.get(name);
        if (ws == null) return false;
        for (int[] w : ws) if (t >= w[0] && t < w[1]) return true;
        return false;
    }

    /**
     * 스킬의 deal 효과를 시각 t에 방출(즉발 + dot 틱). attackTimes!=null이면 공격 인스턴스 등록(proc 트리거). ★cond boss/nonBoss 게이팅.
     * @param simCapMs 시뮬 경계(상대시각). ★이 경계 이상(시뮬 시간 밖)의 딜/도트 틱은 방출하지 않는다(딜 미포함).
     */
    private static void emitDeals(Timeline tl, SupporterSkillIR s, int t, DmgType type, int simCapMs, int windowMs, List<Integer> attackTimes, boolean boss,
                                  Map<String, List<int[]>> stateWindows, double speedFactor) {
        boolean anyInstant = false;
        for (SupporterEffectIR e : s.effects()) {
            if (!e.isDeal() || !e.condActive(boss)) continue;
            if (!e.stateActive(name -> stateActiveAt(stateWindows, name, t))) continue;   // ★상태창 게이팅(버스트↔어썰트 스왑)
            double frac = (e.coeff() == null ? 0 : e.coeff()) / 1000.0 * e.chanceMult();   // 동료=성장/마스터리 없음. ★effect chance=기댓값 가중(버스트/어썰트 50%)
            int hits = e.hitsOr1();
            // ★설치기(바닥 장판·소환체=CreateProjectile, Flag ExpireOnCreatureDie)=소환 해제 시 제거 → 소환창(windowMs)에서 틱 절단.
            //   그 외(적 상태 도트 GetDotR·즉발)=적/플레이어 상태칸이라 소환 종료 후에도 잔존 → 시뮬 경계(simCapMs)까지.
            int cap = e.installer() ? Math.min(windowMs, simCapMs) : simCapMs;
            if (e.isChanneled()) {   // ★시간분산(채널링): N틱을 각 시각에 방출(예 SongOfSky 화살 13발). tickBySpeed면 공속 비례로 압축.
                int interval = e.tickMs() == null ? 0 : e.tickMs();
                int startMs = e.tickStartMs() != null ? e.tickStartMs() : interval;
                double sf = e.tickBySpeedOn() ? speedFactor : 1.0;
                for (int k = 0; k < e.tickCount(); k++) {
                    int tt = t + (int) Math.round((startMs + (long) interval * k) * sf);
                    if (tt >= cap) break;
                    tl.addDeal(tt, new DealEvent(s.idx(), DmgType.SKILL, frac, hits, 0.0, k > 0));   // 첫 틱=시전(캐스트 카운트), 이후=틱
                }
                anyInstant = true;   // 시전(캐스트) 자체는 공격 인스턴스로(proc 트리거 시각=t)
            } else if (e.isDot() || (e.tickMs() != null && e.durationMs() != null)) {
                int tick = e.tickMs() == null ? 0 : e.tickMs();
                int dur = e.durationMs() == null ? 0 : e.durationMs();
                int off = e.tickStartMs() != null ? e.tickStartMs() : 0;   // 지연 소환(배틀십 3초 후 등장) — 지속창을 그만큼 뒤로
                if (tick <= 0) continue;
                for (int k = 1; (long) tick * k <= dur; k++) {
                    int tt = t + off + tick * k;
                    // ★적 상태 도트(설치기 아님)는 소환창(W) 이후에도 남은 지속을 계속 입히지만(인게임: 적에 걸린 지속딜은 소환 종료 후에도 유지),
                    //   설치기는 소환 해제와 함께 제거되므로 windowMs에서 절단. 어느 쪽이든 시뮬 경계(simCapMs) 밖 틱은 미포함.
                    //   W 초과 틱(적 상태 도트)은 applySelfBuffs가 동료 자기버프(최종뎀 스택·등급보너스)를 싣지 않고, 적 디버프(받피증·방깎)는 그대로 수혜.
                    if (tt >= cap) break;
                    tl.addDeal(tt, new DealEvent(s.idx(), DmgType.SKILL, frac, hits, 0.0, true));
                }
            } else {   // 즉발(단발). tickStartMs 있으면 그만큼 지연(배틀십 소환 3초 후 등장 등).
                int t0 = t + (e.tickStartMs() != null ? e.tickStartMs() : 0);
                if (t0 < cap) { tl.addDeal(t0, new DealEvent(s.idx(), type, frac, hits, 0.0, false)); anyInstant = true; }
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
            int useEnd;
            if (e.durationMs() == null) {
                useEnd = windowMs;   // 무기한(상시)=소환창 끝
            } else if (e.refreshOn()) {
                // ★매 틱 갱신(디버프 op이 틱 딜스킬에 있음): 실효창 = 같은 스킬 설치딜 지속(마지막 틱) + op 지속. clt 구조 기반, 값 하드코딩 아님.
                int installerDur = 0;
                for (SupporterEffectIR de : s.effects())
                    if (de.isDeal() && de.durationMs() != null && de.tickMs() != null) installerDur = Math.max(installerDur, de.durationMs());
                useEnd = startMs + installerDur + e.durationMs();
            } else {
                useEnd = startMs + e.durationMs();   // 1회(op이 액티브 자체)
            }
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

    /** onAttack 버프-only proc의 기댓값 가동률 — 갱신형 버프가 durMs 내내 유지될 정상상태 확률.
     *  U = 1 − (1−chance/1000)^n, n = round(durMs/slotMs)(동료 공격 간격, ≥1). 확률 버프(예 팔라딘 홀리차지 15%/타격·10s)를
     *  '상시 가정' 대신 기댓값으로 상시화(공속↑=n↑=U→100% 근접). chance≥1000(100%)이면 U=1. */
    public static double procBuffUptime(int chance, int durMs, double slotMs) {
        if (chance >= 1000) return 1.0;
        if (chance <= 0 || durMs <= 0 || slotMs <= 0) return 0.0;
        int n = (int) Math.max(1, Math.round(durMs / slotMs));
        return 1.0 - Math.pow(1.0 - chance / 1000.0, n);
    }

    /** ★확률 누산식 발동 창(SIM_MODEL §79 · ArtifactAssembler.procWindows) — 트리거(공격) 스트림마다 chance(‰) 누산,
     *  1000 도달 시 발동 + 초과분 이월. 발동마다 <b>독립 창</b>[t, t+durMs]. ★은펜던트식 개별 지속(individualDur:true)일 때만 사용.
     *  게임설명 "지속시간은 개별 적용" = 개별. 그 문구 없으면 기본=공유 타이머(sharedStackSegments). */
    private static List<int[]> procWindows(List<Integer> atk, int chance, int durMs) {
        List<int[]> w = new ArrayList<>();
        if (atk == null || chance <= 0 || durMs <= 0) return w;
        double acc = 0;
        for (int t : atk) { acc += chance; if (acc >= 1000.0) { acc -= 1000.0; w.add(new int[]{t, t + durMs}); } }
        return w;
    }
    /** 시각 t에 겹치는 발동 창 수 = 스택(캡 maxStacks). */
    private static int stacksAt(List<int[]> windows, int t, int maxStacks) {
        int s = 0; for (int[] w : windows) if (w[0] <= t && t < w[1]) s++;
        return Math.min(s, maxStacks);
    }
    /** 발동 창들 → 스택 세그먼트 [start, end, stacks](겹침 카운트, 캡). 플레이어/적 버프 창 방출용.
     *  ★인접 세그먼트가 스택수 같고 끊김 없으면(한 스택 만료+다른 스택 유지로 개수 불변) 한 창으로 병합(타임라인 한 막대). */
    private static List<int[]> stackSegments(List<int[]> windows, int maxStacks) {
        List<int[]> evs = new ArrayList<>();
        for (int[] w : windows) { evs.add(new int[]{w[0], 1}); evs.add(new int[]{w[1], -1}); }
        evs.sort((a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]);   // 동시각은 -1(종료) 먼저 처리
        List<int[]> segs = new ArrayList<>();
        int cur = 0, segStart = 0;
        for (int[] ev : evs) {
            if (ev[0] > segStart && cur > 0) {
                int st = Math.min(cur, maxStacks);
                int[] last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
                if (last != null && last[1] == segStart && last[2] == st) last[1] = ev[0];   // 병합: 끊김 없고 스택 동일
                else segs.add(new int[]{segStart, ev[0], st});
            }
            cur += ev[1]; segStart = ev[0];
        }
        return segs;
    }

    /** ★공유 타이머 스택 세그먼트(<b>기본</b>, 은펜던트식 아님) — 확률 누산기로 발동 시각 산출 →
     *  <b>단일 타이머</b> 시뮬: 발동마다 timerEnd=t+durMs로 <b>전체 갱신</b> + 스택+1(캡). 타이머 만료 시 <b>전 스택 동시 소멸</b>.
     *  (히어로 콤보어택: 소환 종료 후에도 마지막 발동+지속까지 5스택 유지하다 한 번에 꺼짐 — 인게임 실측 일치.) */
    private static List<int[]> sharedStackSegments(List<Integer> atk, int chance, int durMs, int maxStacks) {
        List<int[]> segs = new ArrayList<>();
        if (atk == null || chance <= 0 || durMs <= 0) return segs;
        double acc = 0; int stack = 0, timerEnd = 0, segStart = 0;
        for (int t : atk) {
            acc += chance;
            if (acc < 1000.0) continue;
            acc -= 1000.0;
            if (stack > 0 && t >= timerEnd) { addSeg(segs, segStart, timerEnd, stack, maxStacks); stack = 0; }   // 이전 체인 만료(동시 소멸)
            if (stack == 0) { stack = 1; segStart = t; }
            else { addSeg(segs, segStart, t, stack, maxStacks); stack = Math.min(maxStacks, stack + 1); segStart = t; }   // 갱신+스택증가
            timerEnd = t + durMs;
        }
        if (stack > 0) addSeg(segs, segStart, timerEnd, stack, maxStacks);   // 마지막 체인=지속 끝까지 유지 후 소멸
        return segs;
    }
    /** 세그먼트 방출(인접 동일 스택·끊김 없으면 병합 → 타임라인 한 막대). */
    private static void addSeg(List<int[]> segs, int start, int end, int stack, int maxStacks) {
        if (end <= start || stack <= 0) return;
        int st = Math.min(stack, maxStacks);
        int[] last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
        if (last != null && last[1] == start && last[2] == st) last[1] = end;
        else segs.add(new int[]{start, end, st});
    }
    /** 세그먼트에서 시각 t의 스택수(없으면 0). */
    private static int stackAtSeg(List<int[]> segs, int t) {
        for (int[] sg : segs) if (sg[0] <= t && t < sg[1]) return sg[2];
        return 0;
    }

    /** 버프 지속(ms) — 효과 durationMs 최대(없으면 0=창전체 취급). */
    private static int effDur(SupporterSkillIR s) {
        int d = 0;
        for (SupporterEffectIR e : s.effects()) if (e.durationMs() != null) d = Math.max(d, e.durationMs());
        return d;
    }

    /** ★시점 t의 동료 자기 공속(%) = 그때 활성인 target:companion AttackSpeed statUp 합(‰÷10).
     *  durationMs 없으면 상시(onStart), enter=[0,dur], active=시전창(그 전 cast 기준). 시한부 공속(ENTER 15초)을 실제 구간에서만 반영. */
    private static double selfAtkSpeedAt(List<SupporterSkillIR> skills, int t, Map<Integer, List<Integer>> activeCastTimes) {
        double sum = 0;
        for (SupporterSkillIR s : skills)
            for (SupporterEffectIR e : s.effects()) {
                if (e.unused() || !"statUp".equals(e.role()) || !e.toCompanion() || !"AttackSpeed".equals(e.stat()) || e.value() == null) continue;
                boolean active;
                if (e.durationMs() == null) active = true;                       // 상시(onStart)
                else if (s.isEnter()) active = t < e.durationMs();               // enter [0,dur]
                else if (s.isActive()) {                                          // active 시전창
                    active = false;
                    for (int ct : activeCastTimes.getOrDefault(s.idx(), List.of())) if (t >= ct && t < ct + e.durationMs()) { active = true; break; }
                } else active = t < e.durationMs();                              // 기타=[0,dur] 근사
                if (active) sum += e.value() / 10.0;
            }
        return sum;
    }

    /** 시점 t의 동료 슬롯(모션) 간격(ms) = 1000/(1+공속/100). 공속=계승 공속 ⊕ 그 시점 자기 공속(체감·상한150%). */
    private static double slotMsAt(Preset base, List<SupporterSkillIR> skills, int t, Map<Integer, List<Integer>> activeCastTimes) {
        double spd = Stat.ATK_SPEED.combine(base.atkSpeed(), selfAtkSpeedAt(skills, t, activeCastTimes));
        double slot = 1000.0 / (1 + spd / 100.0);
        return slot < 1 ? 1 : slot;
    }

    /** 동료 자기버프를 딜 슬롯에 적용: gradeBonus/instant FinalDamage(상시) + target:companion statUp. ★FinalDamage=효과별 각각 곱(별도 소스).
     *  ★durationMs 있는 액티브/enter 동료버프(예 서펜트스톤 최종뎀·ENTER 크뎀)는 <b>시간창 안에서만</b> 적용(상시 아님). */
    private record WindowedSelf(double[] routed, List<int[]> windows) {
        boolean activeAt(int t) { for (int[] w : windows) if (t >= w[0] && t < w[1]) return true; return false; }
    }
    private static void applySelfBuffs(Timeline tl, SupporterIR sup, int companionLevel,
                                       List<SupporterSkillIR> skills, SupporterBook book, int windowMs, boolean instantBonus,
                                       Map<Integer, List<Integer>> activeCastTimes, boolean boss) {
        // 1) gradeBonus 동료 자기 최종뎀 — 도달한 각 레벨(+25%씩)을 ★별도 곱소스로(합산 아님: ×1.25×1.25×1.25).
        List<Double> selfFinalSources = new ArrayList<>();
        SupporterBook.GradeBonus gb = book.gradeBonus();
        if (gb != null && "FinalDamage".equals(gb.stat()) && "companion".equals(gb.target())
                && (gb.grades() == null || gb.grades().contains(sup.grade())))
            for (int[] lvVal : gb.byLevel()) if (companionLevel >= lvVal[0]) selfFinalSources.add(lvVal[1] / 1000.0);
        if (instantBonus)   // 추가 최종뎀(체크박스 ON) = 동료 무관 +25% 별도 곱소스(자유 세팅 비교용)
            selfFinalSources.add(INSTANT_FINAL_DMG / 1000.0);

        // 2) target:companion statUp — 상수(합풀 selfAdds / FinalDamage 별도 곱소스) · 램프업 스택 · ★시간창(액티브/enter durationMs) 분리.
        List<double[]> selfAdds = new ArrayList<>();
        List<double[]> stacking = new ArrayList<>();   // {routed ordinal(-1=final), 스택당 값, periodMs, maxStacks}
        List<WindowedSelf> windowed = new ArrayList<>();   // durationMs 있는 액티브/enter 동료버프 = 창 안에서만
        for (SupporterSkillIR s : skills) {
            int period = s.periodMs() == null ? 0 : s.periodMs();
            for (SupporterEffectIR e : s.effects()) {
                if (e.unused() || !"statUp".equals(e.role()) || !e.toCompanion() || !e.condActive(boss)) continue;
                if (s.isOnAttack()) continue;   // ★onAttack proc 버프(홀리차지·콤보)는 build()의 확률누산 패스에서 슬롯별 스택 적용됨 — 여기 중복 금지
                if (e.stat() == null || e.value() == null) continue;
                double[] routed = routeSelf(e.stat(), e.value());
                if (routed == null) continue;
                if (e.maxStacksOr1() > 1 && period > 0) {   // 램프업 스택(예 1초마다 최종뎀 +1.5% 최대 15중첩) → per-slot
                    stacking.add(new double[]{routed[0], routed[1], period, e.maxStacksOr1()});
                } else if (e.durationMs() != null && (s.isEnter() || s.isActive())) {   // ★시간창(서펜트스톤 최종뎀·ENTER 크뎀 등)
                    List<int[]> wins = new ArrayList<>();
                    int dur = e.durationMs();
                    if (s.isEnter()) wins.add(new int[]{0, dur});   // enter=소환 시점 1회 [0,dur]
                    else for (int ct : activeCastTimes.getOrDefault(s.idx(), List.of())) wins.add(new int[]{ct, ct + dur});   // active=시전마다 창
                    if (!wins.isEmpty()) windowed.add(new WindowedSelf(routed, wins));
                } else if (routed[0] < 0) {
                    selfFinalSources.add(routed[1]);   // 상수 FinalDamage → 별도 곱소스(각각 ×)
                } else {
                    selfAdds.add(routed);
                }
            }
        }

        // 3) 각 슬롯에 적용 (스택=램프업, 시간창=창 안에서만)
        for (java.util.Map.Entry<Integer, Slot> en : tl.slots().entrySet()) {
            int t = en.getKey();
            if (t > windowMs) continue;   // ★소환창 이후 슬롯(연장된 도트 틱)은 동료 자기버프(최종뎀 스택·등급보너스) 미적용 — 소환 종료로 사라짐
            ModState m = en.getValue().mods();
            for (double[] a : selfAdds) m.addStat(Stat.values()[(int) a[0]], a[1]);
            for (WindowedSelf wb : windowed) if (wb.activeAt(t)) {   // ★시간창 동료버프
                if (wb.routed()[0] < 0) m.addFinalDmgSource(wb.routed()[1]);
                else m.addStat(Stat.values()[(int) wb.routed()[0]], wb.routed()[1]);
            }
            for (double f : selfFinalSources) m.addFinalDmgSource(f);   // 소스별 각각 ×(1+f)
            for (double[] st : stacking) {
                int stacks = (int) Math.min((long) st[3], (long) (t / (int) st[2]));
                if (stacks <= 0) continue;
                if (st[0] < 0) m.addFinalDmgSource(Math.pow(1.0 + st[1], stacks) - 1.0);   // ★스택 최종뎀 = 스택별 곱적용 ×(1+값)^스택. 클뜯 규명: FinalDamage=modifier별 곱(AccumMultCalculator)이고 809470엔 StackCount 플래그가 없어 스택 15개가 각각 별도 곱소스 → ×1.015¹⁵
                else m.addStat(Stat.values()[(int) st[0]], st[1] * stacks);                // 그 외 스탯 = 합풀(스택수×스택당)
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
