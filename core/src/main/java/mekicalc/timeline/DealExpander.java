package mekicalc.timeline;

import mekicalc.timeline.ir.EffectIR;
import mekicalc.timeline.ir.MasteryIR;
import mekicalc.timeline.ir.SkillBook;
import mekicalc.timeline.ir.SkillIR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 딜 이벤트 전개(2단계) — 배치된 액션 + 배경 트리거를 {@link DealEvent}로 펼쳐 {@link Timeline}에 적재한다.
 *
 * <p>딜 소스:
 * <ul>
 *   <li><b>배치 액션</b>(평타·딜 액티브): 즉발 딜 + 자체 도트(틱마다)
 *   <li><b>periodic</b>: periodMs 주기마다 딜(자동 방출)
 *   <li><b>onAttack 충전형</b>(chargeMs, 예: 마크): 충전이 격자로 재생성, 각 창의 첫 공격이 소모해 딜(창당 1회)
 *   <li><b>onAttack 즉발 프록</b>(쉐도우 파트너): 매 공격 시각에 <b>기댓값</b>(coeff×확률)
 *   <li><b>onAttack 도트 프록</b>(베놈): 매 공격 갱신 = 연속 도트(첫 공격~끝, 틱마다) — 확률 무관
 * </ul>
 *
 * <p>스킬 고유 스칼라(성장·피해마스터리·hit마스터리·skillBoost 최종뎀·프록확률)는 여기서 미리 접어
 * DealEvent에 담는다(전부 딜에 대한 곱/합이라 시각 불변). 시각 가변 버프/디버프 누적은 후속 단위.
 * <p>마리수(targetCount)는 아직 미반영(1마리 기준).
 */
public final class DealExpander {

    private final SkillBook book;
    private final Preset preset;
    private final int charLv;

    private final Map<Integer, Double> skillMastery = new HashMap<>(); // idx → Σ피해마스터리 분율
    private final Map<Integer, Integer> bonusHits = new HashMap<>();   // idx → +타수
    private final Map<Integer, Double> tickPct = new HashMap<>();      // idx → 타격주기 마스터리 분율(-0.25 = -25%)
    private final Map<Integer, Integer> tickFlatMs = new HashMap<>();  // idx → 타격주기 flat(ms) — unit이 ms일 때
    private final Map<Integer, Double> durPct = new HashMap<>();       // idx → 지속시간 마스터리 분율
    private final Map<Integer, Integer> durFlatMs = new HashMap<>();   // idx → 지속시간 flat(ms)
    private final Map<Integer, Double> boostFinal = new HashMap<>();   // idx → skillBoost 최종뎀 곱
    /** onAttack 상태형(베놈) 가동 윈도우 — expand 후 BuffAccumulator(약화 게이트)가 소비. */
    private final Map<Integer, List<int[]>> onAttackWindows = new HashMap<>();
    /** onAttack 인스턴스 스트림(비-dot 딜 + 프록 cascade) — expand 후 유물 onAttack 확률 누산이 소비. */
    private List<Integer> attackInstances = java.util.List.of();
    /** 도트/지속딜 스킬 idx → 실제 지속 윈도우[발동, 발동+effDur] 목록(마스터리 반영). 딜 라인 막대용. */
    private final Map<Integer, List<int[]>> dotWindows = new HashMap<>();

    /** 도트/지속딜 실제 지속 윈도우(발동~발동+지속, 마스터리 반영). 마지막 틱 이후 유지시간도 포함. expand() 후 유효. */
    public Map<Integer, List<int[]>> dotWindows() { return dotWindows; }

    /** onAttack 상태형(베놈) 가동 윈도우(약화 gatedBy용). expand() 호출 후 유효. */
    public Map<Integer, List<int[]>> onAttackWindows() { return onAttackWindows; }

    /** onAttack 공격 인스턴스 시각(비-dot, 베놈과 동일 스트림). expand() 호출 후 유효. */
    public List<Integer> attackInstances() { return attackInstances; }

    public DealExpander(SkillBook book, Preset preset) {
        this.book = book;
        this.preset = preset;
        this.charLv = preset.level();
        indexMasteries();
        indexSkillBoost();
    }

    /** 스킬의 습득 차수 유효레벨(성장 입력). */
    private int effOf(int skillIdx) {
        SkillIR s = book.skill(skillIdx);
        return preset.effectiveSkillLevel(s == null ? 4 : s.tier());
    }

    /**
     * 배치 액션 + 배경 트리거를 전개한 Timeline.
     * <p>★OnAttack = 적이 <b>비-dot 데미지</b>를 입는 <b>각 딜 인스턴스</b>당 1회(유저 실측 2026-08-19). 인스턴스 스트림 =
     * 평타·액티브딜(즉발 1개) + periodic 딜 + 다크플레어 소환틱 (<b>form:dot 제외</b>) + 충전형 소모(마크) + 프록딜(쉐도우·페이탈=cascade).
     */
    public Timeline expand(Schedule sch, int simMs) {
        List<Action> actions = sch.actions();
        Timeline tl = new Timeline();
        onAttackWindows.clear();

        // 1) 배치 액션 딜 + 2) periodic 딜 (데미지 방출).
        for (int ai = 0; ai < actions.size(); ai++) {
            Action a = actions.get(ai);
            int cap = simMs;   // 같은 스킬 다음 캐스트 = 재설치로 이전 지속딜 잘림(강제 재설치=덮어씀). 자동은 겹칠 일 없음.
            if (!a.basic()) for (int aj = ai + 1; aj < actions.size(); aj++) {
                Action b = actions.get(aj);
                if (!b.basic() && b.skill().idx() == a.skill().idx()) { cap = b.timeMs(); break; }
            }
            emitSkillDeals(tl, a.skill(), a.timeMs(), a.basic(), simMs, cap);
        }
        for (SkillIR s : book.skills()) {
            if (!"periodic".equals(s.trigger()) || s.requireLevel() > charLv || !s.hasDeal()) continue;
            int period = s.periodMs() == null ? 0 : s.periodMs();
            if (period <= 0) continue;
            for (int t = period; t <= simMs; t += period) emitSkillDeals(tl, s, t, false, simMs, simMs);
        }

        // 3) 충전형 onAttack(chargeMs) 발동: 충전은 chargeMs 절대 격자로 재생성(발동 여부 무관 = 쿨타임 아님),
        //    각 창의 첫 공격이 소모해 발동(창당 1회). 발동 시각을 다시 onAttack 인스턴스에 더해 베놈·유물이 프록.
        //    ★엔진은 "마크"를 모르고 이 규칙만 안다 — 데이터로 trigger:onAttack+chargeMs만 선언하면 어떤 스킬도 동일 동작.
        List<Integer> base = baseInstances(actions, simMs);
        Map<Integer, List<Integer>> chargeFires = new HashMap<>();
        List<Integer> chargeAll = new ArrayList<>();
        for (SkillIR s : book.skills()) {
            if (!s.isCharged() || s.requireLevel() > charLv) continue;
            List<Integer> fires = firstAttackPerWindow(base, s.chargeMs(), simMs);
            chargeFires.put(s.idx(), fires);
            chargeAll.addAll(fires);
        }
        List<Integer> atkBase = chargeAll.isEmpty() ? base : mergeSorted(base, chargeAll);

        // 4) OnAttack 상태형(베놈) 윈도우 + 프록 cascade 반복수렴
        List<Integer> instances = atkBase;
        for (int iter = 0; iter < 5; iter++) {
            Map<Integer, List<int[]>> vw = stateWindows(instances, simMs);
            onAttackWindows.clear(); onAttackWindows.putAll(vw);
            List<Integer> merged = mergeSorted(atkBase, instantProcTimes(instances, vw));  // 프록딜=새 인스턴스
            if (merged.size() == instances.size()) { instances = merged; break; }
            instances = merged;
        }

        // 5) 최종 방출: 충전형 소모 발동 + 베놈 dot(윈도우 내 틱) + 즉발 프록(발동 시각 full딜, 누산밀도가 확률).
        for (SkillIR s : book.skills()) {
            if (!"onAttack".equals(s.trigger()) || s.requireLevel() > charLv || !s.hasDeal()) continue;
            if (s.isCharged()) {                              // 충전형: 소모 발동 시각에 딜(계수·성장·마스터리 동일)
                List<Integer> fires = chargeFires.get(s.idx());
                if (fires != null) for (int t : fires) emitSkillDeals(tl, s, t, false, simMs, simMs);
                continue;
            }
            List<int[]> gate = s.gatedBy() == null ? null : onAttackWindows.get(s.gatedBy());
            for (EffectIR e : s.effects()) {
                if (!"deal".equals(e.role())) continue;
                double frac = coeffFrac(s.idx(), e);
                if (e.isDot()) {                                   // 상태 도트(베놈): 누산기 윈도우 내 틱
                    List<int[]> windows = onAttackWindows.get(s.idx());
                    int tick = effTick(s.idx(), e);
                    if (windows != null) for (int[] w : windows)
                        for (int t = w[0] + tick; t <= w[1]; t += tick)
                            tl.addDeal(t, deal(s.idx(), e, frac, true));
                } else {                                           // 즉발 프록(쉐도우·페이탈)
                    int chance = effChancePermille(s);
                    double acc = 0;
                    for (int t : instances) {
                        if (gate != null && !inGate(gate, t)) continue;
                        acc += chance;
                        if (acc >= 1000.0) { acc -= 1000.0; tl.addDeal(t, deal(s.idx(), e, frac, false)); }
                    }
                }
            }
        }
        this.attackInstances = instances;   // 유물 onAttack 확률 누산이 소비(베놈과 동일 스트림)
        return tl;
    }

    // ── OnAttack 인스턴스 스트림 ─────────────────────────

    /** 비-dot 딜 인스턴스 시각(정렬). 평타·액티브딜(즉발=액션당1) · periodic 딜 · 다크플레어 소환틱. ★form:dot 제외. */
    private List<Integer> baseInstances(List<Action> actions, int simMs) {
        List<Integer> out = new ArrayList<>();
        for (Action a : actions) {
            boolean instant = a.basic();
            for (EffectIR e : a.skill().effects()) {
                if (!"deal".equals(e.role()) || e.isDot() || e.isAdditional()) continue;   // 지속피해·추가피해 제외
                if (isSummonTick(e)) {                                    // 비-dot 소환틱(다크플레어): 각 틱=인스턴스
                    int tick = effTick(a.skill().idx(), e), dur = effDur(a.skill().idx(), e);   // 주기·지속 마스터리 반영
                    for (int k = 1; (long) tick * k <= dur; k++) { int tt = a.timeMs() + tick * k; if (tt > simMs) break; out.add(tt); }
                } else instant = true;                                    // 즉발 딜 → 액션당 1개(타수 무관)
            }
            if (instant) out.add(a.timeMs());
        }
        for (SkillIR s : book.skills()) {                                 // periodic 비-dot=각 주기 인스턴스
            if (!"periodic".equals(s.trigger()) || s.requireLevel() > charLv || !s.hasDeal()) continue;
            boolean nonDot = false;
            for (EffectIR e : s.effects()) if ("deal".equals(e.role()) && !e.isDot()) nonDot = true;
            int period = s.periodMs() == null ? 0 : s.periodMs();
            if (!nonDot || period <= 0) continue;
            for (int t = period; t <= simMs; t += period) out.add(t);
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * 충전형 발동 시각 = 공격 스트림을 periodMs 절대 격자로 나눠 각 창 [k·P,(k+1)·P)의 <b>첫 공격</b>(창당 최대 1회).
     * 충전은 격자로 재생성되므로(발동·소모 여부 무관 = 쿨타임 아님) 공격 없는 창은 스킵.
     * ★첫 충전은 <b>첫 주기가 지난 t=periodMs부터</b>(사전활성 아님 — 전투 시작엔 충전 없음). 마크류의 범용 소모 규칙.
     */
    private static List<Integer> firstAttackPerWindow(List<Integer> base, int periodMs, int simMs) {
        List<Integer> out = new ArrayList<>();
        if (periodMs <= 0) return out;
        int lastWin = -1;
        for (int t : base) {                          // base 오름차순
            if (t >= simMs) break;
            int win = t / periodMs;                   // 격자 창 인덱스(0 = 첫 주기 전, 아직 충전 없음)
            if (win < 1) continue;                    // 첫 주기(periodMs) 전엔 표식 없음 → 소모 불가
            if (win != lastWin) { out.add(t); lastWin = win; }   // 그 창의 첫 공격만 소모
        }
        return out;
    }

    /** 비-dot 소환 지속딜(다크플레어): form:dot 아니면서 tickMs+durationMs. */
    private static boolean isSummonTick(EffectIR e) {
        return !e.isDot() && e.tickMs() != null && e.durationMs() != null;
    }

    /** 상태형(onAttack + form:dot 지속딜 = 베놈) 확률 누산기 윈도우 — refresh:false(활성 중 정지). */
    private Map<Integer, List<int[]>> stateWindows(List<Integer> instances, int simMs) {
        Map<Integer, List<int[]>> out = new HashMap<>();
        for (SkillIR s : book.skills()) {
            if (!"onAttack".equals(s.trigger()) || s.requireLevel() > charLv) continue;
            int dur = 0;
            for (EffectIR e : s.effects())
                if ("deal".equals(e.role()) && e.isDot() && e.durationMs() != null) dur = Math.max(dur, effDur(s.idx(), e));
            if (dur <= 0) continue;                                       // 상태 도트 없으면 skip
            int chance = effChancePermille(s);
            List<int[]> windows = new ArrayList<>();
            double acc = 0; int buffEnd = -1;
            for (int t : instances) {
                if (t < buffEnd) continue;                                // 활성 중 정지(refresh:false)
                acc += chance;
                if (acc >= 1000.0) { acc -= 1000.0; windows.add(new int[]{t, Math.min(t + dur, simMs)}); buffEnd = t + dur; }
            }
            out.put(s.idx(), windows);
        }
        return out;
    }

    /** 즉발 프록(쉐도우·페이탈) 발동 시각 = 스트림에 추가할 비-dot 인스턴스(cascade). gatedBy면 게이트 창 안만. */
    private List<Integer> instantProcTimes(List<Integer> instances, Map<Integer, List<int[]>> venomW) {
        List<Integer> fires = new ArrayList<>();
        for (SkillIR s : book.skills()) {
            if (!"onAttack".equals(s.trigger()) || s.requireLevel() > charLv || !s.hasDeal()) continue;
            if (s.isCharged()) continue;                 // 충전형은 확률 프록 아님(격자 소모로 별도 방출)
            boolean hasInstant = false;
            for (EffectIR e : s.effects()) if ("deal".equals(e.role()) && !e.isDot() && !e.isAdditional()) hasInstant = true;
            if (!hasInstant) continue;   // 추가피해/dot만인 프록(쉐도우·페이탈)은 트리거 대상 아님 → 스트림에 cascade X
            int chance = effChancePermille(s);
            List<int[]> gate = s.gatedBy() == null ? null : venomW.get(s.gatedBy());
            double acc = 0;
            for (int t : instances) {
                if (gate != null && !inGate(gate, t)) continue;
                acc += chance;
                if (acc >= 1000.0) { acc -= 1000.0; fires.add(t); }
            }
        }
        return fires;
    }

    private static List<Integer> mergeSorted(List<Integer> a, List<Integer> b) {
        List<Integer> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a); out.addAll(b);
        java.util.Collections.sort(out);
        return out;
    }

    // ── 딜 방출 ─────────────────────────────────────────

    /** 한 스킬의 모든 deal 효과를 시각 t에 방출(즉발 + 도트 틱 전개). */
    private void emitSkillDeals(Timeline tl, SkillIR s, int t, boolean basic, int simMs, int cap) {
        for (EffectIR e : s.effects()) {
            if (!"deal".equals(e.role())) continue;
            double frac = coeffFrac(s.idx(), e);
            if (isDot(e)) {
                int tick = effTick(s.idx(), e), dur = effDur(s.idx(), e);   // 주기·지속시간 마스터리 반영
                int end = Math.min(t + dur, Math.min(simMs, cap));          // ★재발동 시 이전 지속딜 자름(cap=다음 같은 스킬 캐스트). 자동은 겹칠 일 없어 무영향.
                // 지속 막대 = 발동~종료(마지막 틱 이후 유지시간 포함, 재발동에 잘림). 틱 시각과 별개로 실제 duration 반영.
                if (end > t) dotWindows.computeIfAbsent(s.idx(), k -> new ArrayList<>()).add(new int[]{t, end});
                for (int k = 1; (long) tick * k <= dur; k++) {              // 경계 딱 떨어지면 마지막 틱 포함
                    int tt = t + tick * k;
                    if (tt > simMs || tt >= cap) break;                     // simMs 또는 재발동(cap) 이전 틱만 — 이후는 새 설치가 담당
                    tl.addDeal(tt, deal2(s.idx(), e, frac, true, basic));
                }
            } else {
                tl.addDeal(t, deal2(s.idx(), e, frac, false, basic));
            }
        }
    }

    private DealEvent deal(int idx, EffectIR e, double frac, boolean dot) {
        return new DealEvent(idx, DmgType.SKILL, frac, e.hitsOr1() + bonusHits.getOrDefault(idx, 0),
                skillMastery.getOrDefault(idx, 0.0), dot);
    }

    private DealEvent deal2(int idx, EffectIR e, double frac, boolean dot, boolean basic) {
        return new DealEvent(idx, basic ? DmgType.BASIC : DmgType.SKILL, frac,
                e.hitsOr1() + bonusHits.getOrDefault(idx, 0), skillMastery.getOrDefault(idx, 0.0), dot);
    }

    /** 계수 분율 = coeff/1000 × 성장(스킬 차수레벨) × skillBoost최종뎀. */
    private double coeffFrac(int idx, EffectIR e) {
        double base = (e.coeff() == null ? 0 : e.coeff()) / 1000.0;
        return base * Growth.factor(e.growthSlot(), effOf(idx)) * boostFinal.getOrDefault(idx, 1.0);
    }

    private static boolean isDot(EffectIR e) {
        return e.isDot() || (e.tickMs() != null && e.durationMs() != null);
    }

    /** 타격 주기(마스터리 flat+% 반영, 최소 1). (raw+flat)×(1+pct). */
    private int effTick(int idx, EffectIR e) {
        if (e.tickMs() == null) return 0;
        double t = (e.tickMs() + tickFlatMs.getOrDefault(idx, 0)) * (1 + tickPct.getOrDefault(idx, 0.0));
        return Math.max(1, (int) Math.round(t));
    }

    /** 지속시간(마스터리 flat+% 반영). (raw+flat)×(1+pct). */
    private int effDur(int idx, EffectIR e) {
        if (e.durationMs() == null) return 0;
        double d = (e.durationMs() + durFlatMs.getOrDefault(idx, 0)) * (1 + durPct.getOrDefault(idx, 0.0));
        return Math.max(0, (int) Math.round(d));
    }

    /** 게이트 스킬(베놈)이 적용된 상태의 공격인가 — 적용 다음 딜부터(w0 초과 ~ w1 이하). */
    private static boolean inGate(List<int[]> windows, int t) {
        for (int[] w : windows) if (t > w[0] && t <= w[1]) return true;
        return false;
    }

    /** 프록 실효 확률(‰) = 기본 chance + Σ probability 마스터리(합). 스케줄러 누산기와 동일 규칙. */
    private int effChancePermille(SkillIR s) {
        int c = s.chance() == null ? 0 : s.chance();
        for (MasteryIR m : book.masteries()) {
            if (m.lv() > charLv || m.skill() == null || m.skill() != s.idx()) continue;
            if ("probability".equals(m.type()) && m.value() != null) c += (int) Math.round(m.value());
        }
        return c;
    }

    // ── 사전 집계 ───────────────────────────────────────

    private void indexMasteries() {
        for (MasteryIR m : book.masteries()) {
            if (m.lv() > charLv || m.skill() == null) continue;
            double v = m.value() == null ? 0 : m.value();
            switch (m.type() == null ? "" : m.type()) {
                case "skillDamage" -> skillMastery.merge(m.skill(), v / 1000.0, Double::sum);
                case "hit" -> bonusHits.merge(m.skill(), (int) v, Integer::sum);
                case "tick" -> {   // 타격 주기(다크플레어 -250 = -25%). unit pct=천분율, ms=flat
                    if (m.unit() == null || m.unit().contains("pct")) tickPct.merge(m.skill(), v / 1000.0, Double::sum);
                    else tickFlatMs.merge(m.skill(), (int) v, Integer::sum);
                }
                case "duration" -> {   // 도트 지속시간(버프 지속은 Scheduler에서 별도)
                    if (m.unit() == null || m.unit().contains("pct")) durPct.merge(m.skill(), v / 1000.0, Double::sum);
                    else durFlatMs.merge(m.skill(), (int) v, Integer::sum);
                }
                default -> { }   // stat(전역=유저 스탯창 포함)·cooldown(스케줄러)·targetCount(1마리 무관)은 여기서 미처리
            }
        }
    }

    /** skillBoost(finalDmg) → 대상 스킬 고유 최종뎀 곱(성장 반영). */
    private void indexSkillBoost() {
        for (SkillIR s : book.skills()) {
            if (s.requireLevel() > charLv) continue;
            for (EffectIR e : s.effects()) {
                if (!"skillBoost".equals(e.role()) || !"finalDmg".equals(e.type()) || e.targetSkill() == null) continue;
                // 부스트 값은 부스터 스킬(s)의 차수레벨로 성장
                double boost = (e.value() == null ? 0 : e.value()) / 1000.0 * Growth.factor(e.growthSlot(), effOf(s.idx()));
                boostFinal.merge(e.targetSkill(), 1.0 + boost, (a, b) -> a * b);
            }
        }
    }
}
