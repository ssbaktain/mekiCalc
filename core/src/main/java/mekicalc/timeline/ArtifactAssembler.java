package mekicalc.timeline;

import mekicalc.timeline.ir.ArtifactBook;
import mekicalc.timeline.ir.ArtifactIR;

import java.util.ArrayList;
import java.util.List;

/**
 * 유물 IR → {@link BuffSource} 조립기(v1). 장착 유물 목록 + 컨텐츠 맥락을 받아,
 * <b>기본 계산으로 확정 가능한</b> 데미지% 계열 효과만 타임라인 창(BuffSource)으로 만든다.
 * 스킬 버프({@link BuffAccumulator})와 같은 BuffSource·같은 ModState 풀을 공유하므로,
 * 결과를 {@link BuffAccumulator#accumulate}와 동일하게 슬롯에 적용하면 그대로 합쳐진다.
 *
 * <p><b>범위</b>(계약 §7): 상시 base / combatStart·period 시간창 / 컨텐츠 일치분(v1) +
 * derived 라이브 후처리 + 방관(DEF_PEN)·최대뎀(MAX_DMG_MULT) 풀(v2). derived는 슬롯 라이브값
 * 의존이라 {@link #accumulate}(base) 뒤 {@link #accumulateDerived}로 별도 반영.
 * 다음은 스킵하고 사유를 {@link Result#skips}에 남긴다(단계별 확장):
 * <ul>
 *   <li>{@code _defer}(피격·상태의존) → v4 컨텐츠모드</li>
 *   <li>{@code target:companion} → v3 동료엔진</li>
 *   <li>{@code critLt/critGte}(라이브 크확 파티션 + refresh) → v2b(순환)</li>
 *   <li>{@code priorityGroup}·{@code require}(대상 상태) → v4</li>
 *   <li>{@code minEnemies}·{@code perEnemy}·{@code targetMode}(마리수 M 필요) → M 미지정이면 스킵</li>
 *   <li>{@code onKill/onDamage/onState/onAttack/companionSummoned} 트리거(확률·처치수 K 등 미모델) → 후속</li>
 *   <li>공속의 공격빈도(스케줄) 반영·쿨감·버프/소환 지속 등 → 후속</li>
 * </ul>
 */
public final class ArtifactAssembler {

    /** 장착 유물 1건: idx + 각성차수(0~5, values 인덱스). */
    public record Equip(int idx, int awaken) {}

    /**
     * 계산 맥락. m(순간 마리수)·k(분당 처치수)는 미지정(null) 가능 → 해당 의존 효과 스킵.
     * preActivateProc=확률형 유물(성배·은펜던트·천도명약)이 전투 시작 시 이미 발동 중인 것으로 본다.
     */
    public record Ctx(boolean boss, String content, Integer m, Double k, boolean preActivateProc, int artOffsetMs,
                      double buffDurPct) {
        public static Ctx of(boolean boss, String content) { return new Ctx(boss, content, null, null, false, 0, 0); }
    }

    /** 스킵된 효과 1건과 사유(진단·검증용). */
    public record Skip(int idx, String name, int effIdx, String reason) {}

    /**
     * 파생(derived) 효과 1건 — 슬롯 라이브 스탯 후처리로 반영(v2). ratio!=null이면 비율형(입력×ratio),
     * 아니면 초과분형(입력 threshold 초과분을 step마다 perStep, 최대 maxCount회).
     */
    public record DerivedSpec(String label, String stat, String from,
                              Double ratio, Integer threshold, Integer step, Double perStep, Integer maxCount,
                              int startMs, int endMs, int inst) {
        boolean isRatio() { return ratio != null; }
    }

    /** 크확 파티션 사이클(순환의 고리)의 효과 1건. excessStep!=null이면 크확 초과분형(평타뎀), 아니면 상수형. */
    public record CycleEffect(String stat, double value, Integer excessStep, Integer excessMax) {
        boolean isExcess() { return excessStep != null; }
    }

    /**
     * 라이브 크확 파티션 사이클(순환의 고리). refresh period로 매 창마다 발동시점 크확(자기 제외)을 재고,
     * threshold 미만이면 {@link #ltEffects}, 이상이면 {@link #gteEffects}를 그 창에 적용.
     */
    public record CycleSpec(String label, List<int[]> windows,
                            List<CycleEffect> ltEffects, List<CycleEffect> gteEffects, double threshold, int inst) {}

    /**
     * dmgMult 유물(육각 수정 목걸이) — DMG를 "현재 수치의 value%×스택"만큼 곱연산 증가.
     * 스택: 일반몹=상시 최대, 보스=floor(t/periodMs)+1(전투시작부터 빌드업). value=천분율.
     */
    public record DmgMultSpec(String label, double value, int maxStacks, int periodMs, int inst) {}

    /**
     * onAttack 확률 약화(은펜던트·천도명약) — 공격 이벤트마다 chance‰ 확률 누산기로 발동, durMs 창.
     * individualDur=true(은펜던트): 발동마다 독립 창 중첩(최대 maxStacks). false(천도명약): refresh(활성 중 정지, 1스택).
     */
    public record UptimeSpec(String label, String stat, double value, int maxStacks, int chance, int durMs, boolean individualDur, int inst) {}

    /**
     * 조립 결과. sources=상시/시간창(성배 발동 창 포함), skips=미반영+사유, derived=라이브 후처리,
     * cycles=크확 사이클, dmgMults=DMG 곱중첩(육각), uptimes=onAttack 약화, artSpeed=상시 공속 합.
     */
    public record Result(List<BuffSource> sources, List<Skip> skips, List<DerivedSpec> derived,
                         List<CycleSpec> cycles, List<DmgMultSpec> dmgMults, List<UptimeSpec> uptimes,
                         List<BuffSource> speedDisplay,
                         double artSpeed, double artCdrPct, double artBuffDurPct, double artSummonDurPct) {}
    // speedDisplay = 유물 상시공속 '표시 전용' 소스(간트 버프바용). 공속은 preset에 이미 반영돼 딜 슬롯(mods)엔 안 넣음(파생 이중 방지).

    private final ArtifactBook book;
    /** 이번 assemble의 총 벞지(%) = 유저 프리셋 + 유물 BuffDurationRatio(무녀구슬). 시간창 '버프' 유물 지속에만 적용. */
    private double buffDurTotal = 0;

    public ArtifactAssembler(ArtifactBook book) { this.book = book; }

    public Result assemble(List<Equip> equipped, Ctx ctx, int simMs) {
        // 벞지 사전합산: 유저 프리셋 + 유물 BuffDurationRatio(무녀구슬). 시간창 버프 유물 지속(windows)에 쓰인다.
        buffDurTotal = ctx.buffDurPct();
        for (Equip eq : equipped) {
            ArtifactIR a = book.artifact(eq.idx());
            if (a == null || isCycle(a)) continue;
            for (ArtifactIR.Effect e : a.effects())
                if ("BuffDurationRatio".equals(e.stat()) && e.valueAt(eq.awaken()) != null)
                    buffDurTotal += e.valueAt(eq.awaken()) / 10.0;
        }
        List<BuffSource> sources = new ArrayList<>();
        List<Skip> skips = new ArrayList<>();
        List<DerivedSpec> derived = new ArrayList<>();
        List<CycleSpec> cycles = new ArrayList<>();
        List<DmgMultSpec> dmgMults = new ArrayList<>();
        List<UptimeSpec> uptimes = new ArrayList<>();
        List<BuffSource> speedDisplay = new ArrayList<>();   // 유물 상시공속 표시 전용(딜 미반영)
        double artSpeed = 0, artCdr = 0, artBuffDur = 0, artSummonDur = 0;
        java.util.Map<Integer, Integer> dupCount = new java.util.HashMap<>();   // 같은 유물 중복 장착 순번(간트 행 구분용)

        for (int inst = 0; inst < equipped.size(); inst++) {   // inst = 인스턴스 인덱스(중복 유물 오염방지·표시 그룹핑 키)
            Equip eq = equipped.get(inst);
            ArtifactIR a = book.artifact(eq.idx());
            if (a == null) continue;
            int dupSeq = dupCount.merge(eq.idx(), 1, Integer::sum);
            String name = dupSeq > 1 ? a.name() + " #" + dupSeq : a.name();   // 2번째부터 " #2" — 표시/진단 이름 구분(계산은 개별)

            // 라이브 크확 파티션 사이클(순환의 고리) — 유물 단위로 별도 처리, 개별 effect 루프 건너뜀
            if (isCycle(a)) {
                String reason = classifyArtifact(a, ctx);
                if (reason != null) { skips.add(new Skip(a.idx(), name, -1, reason)); continue; }
                cycles.add(buildCycle(a, name, inst, eq.awaken(), simMs));
                continue;
            }

            // 효과 없는 유물(월로·돼지의 리본 등 생존/회복/유틸) — 딜 미반영 안내
            if (a.effects().isEmpty()) { skips.add(new Skip(a.idx(), name, -1, "생존/유틸(딜 미반영)")); continue; }

            for (int ei = 0; ei < a.effects().size(); ei++) {
                ArtifactIR.Effect e = a.effects().get(ei);

                // 유물 상시 공속(헬레나 등) = 상시 base 스탯 → artSpeed로 모아 preset 공속에 체감 합성(buildTimeline).
                // ★슬롯 mods엔 넣지 않는다 — preset에 이미 있어, 넣으면 파생 liveOf(preset+mods)가 이중으로 읽음.
                if ("AttackSpeed".equals(e.stat()) && !e.hasDerived() && baseTrigger(a, e) == null) {
                    Double sp = e.valueAt(eq.awaken());
                    if (sp != null) {
                        artSpeed = Stat.ATK_SPEED.combine(artSpeed, sp / 10.0);   // ★공속 체감 합성(여러 상시공속 유물도) → preset
                        BuffSource disp = new BuffSource(name, 0, simMs);         // 표시 전용(간트 바). 딜 슬롯엔 안 넣음(speedDisplay)
                        disp.inst = inst;
                        disp.add(Stat.ATK_SPEED, sp / 10.0);
                        speedDisplay.add(disp);
                    }
                    continue;
                }
                // 쿨감(영혼계약서)·버프지속(무녀구슬)·동료소환지속(유리구두) — 프리셋에 합산. content 게이트.
                if (("COOLDOWN_REDUCTION_PCT".equals(e.stat()) || "BuffDurationRatio".equals(e.stat())
                        || "SupporterDurationRatio".equals(e.stat()))
                        && !e.hasDerived() && baseTrigger(a, e) == null) {
                    if (a.content() != null && !a.content().isEmpty()
                            && (ctx.content() == null || !a.content().contains(ctx.content()))) {
                        skips.add(new Skip(a.idx(), name, ei, "content 불일치: " + a.content())); continue;
                    }
                    Double v = e.valueAt(eq.awaken());
                    if (v != null) {
                        BuffSource src = new BuffSource(name, 0, simMs);
                        src.inst = inst;
                        switch (e.stat()) {   // 계산은 스케줄 경로(artXxx), 소스엔 표시 전용 실값을 담는다(딜 미사용)
                            case "COOLDOWN_REDUCTION_PCT" -> { artCdr = Stat.COOLDOWN_REDUCTION_PCT.combine(artCdr, v / 10.0); src.add(Stat.COOLDOWN_REDUCTION_PCT, v / 10.0); }   // ★쿨감 = 체감(상한100)
                            case "BuffDurationRatio"      -> { artBuffDur += v / 10.0; src.add(Stat.BUFF_DURATION, v / 10.0); }
                            default                        -> { artSummonDur += v / 10.0; src.add(Stat.SUMMON_DURATION, v / 10.0); }   // SupporterDurationRatio(유리구두)
                        }
                        sources.add(src);
                    }
                    continue;
                }

                String trig = e.trigger() != null ? e.trigger() : a.trigger();

                // onKill 확률 최종뎀(성배) — 처치 이벤트(K 균등) + 확률 누산기 → 발동 창(refresh: 활성 중 정지)
                if ("onKill".equals(trig) && "FinalDamage".equals(e.stat()) && e.valueAt(0) != null) {
                    if (a.defer() || e.defer()) { skips.add(new Skip(a.idx(), name, ei, "defer")); continue; }
                    if (ctx.k() == null || ctx.k() <= 0) { skips.add(new Skip(a.idx(), name, ei, "처치수 K 필요(v4)")); continue; }
                    int chance = e.chance() != null ? e.chance() : (a.chance() != null ? a.chance() : 1000);
                    int dur = e.durationMs() != null ? e.durationMs() : 0;
                    double frac = e.valueAt(eq.awaken()) / 1000.0;
                    double interval = 60000.0 / ctx.k();   // 처치 간격(ms) = 60초/분당처치수
                    // refresh: 활성 중에도 처치 누산 계속, 발동 시 지속 갱신(연장) → 연속 발동은 하나의 창으로 병합
                    // 사전활성: 전투 시작 시 이미 발동 중([0,dur)부터)
                    double acc = 0;
                    int winStart = ctx.preActivateProc() ? 0 : -1, buffEnd = ctx.preActivateProc() ? dur : -1;
                    for (double kt = interval; kt < simMs; kt += interval) {
                        int t = (int) kt;
                        acc += chance;
                        if (acc >= 1000.0) {
                            acc -= 1000.0;
                            if (winStart >= 0 && t <= buffEnd) buffEnd = t + dur;   // 활성 중 재발동 = 연장
                            else {
                                if (winStart >= 0) sources.add(grailWin(name, inst, winStart, buffEnd, simMs, frac));
                                winStart = t; buffEnd = t + dur;
                            }
                        }
                    }
                    if (winStart >= 0) sources.add(grailWin(name, inst, winStart, buffEnd, simMs, frac));
                    continue;
                }

                // onAttack 확률 약화(은펜던트·천도명약) — 가동률 근사(대상 무관, 상시)
                if ("onAttack".equals(trig) && "Weakness".equals(e.stat()) && e.valueAt(0) != null) {   // trig=위에서 선언
                    if (a.defer() || e.defer()) { skips.add(new Skip(a.idx(), name, ei, "defer")); continue; }
                    int chance = e.chance() != null ? e.chance() : (a.chance() != null ? a.chance() : 1000);
                    int dur = e.durationMs() != null ? e.durationMs() : 0;
                    uptimes.add(new UptimeSpec(name, "Weakness", e.valueAt(eq.awaken()),
                            e.maxStacks() != null ? e.maxStacks() : 1, chance, dur, e.individualDur(), inst));
                    continue;
                }

                if (e.dmgMult()) {   // 육각: DMG 곱중첩 — 슬롯별 스택×preset.dmg 후처리
                    String reason = classifyCommon(a, e, ctx, false);
                    if (reason != null) { if (!SILENT.equals(reason)) skips.add(new Skip(a.idx(), name, ei, reason)); continue; }
                    int period = a.periodMs() != null ? a.periodMs() : 0;
                    dmgMults.add(new DmgMultSpec(name, e.valueAt(eq.awaken()),
                            e.maxStacks() != null ? e.maxStacks() : 1, period, inst));
                    continue;
                }

                if (e.hasDerived()) {
                    String reason = classifyCommon(a, e, ctx, true);
                    if (reason != null) { if (!SILENT.equals(reason)) skips.add(new Skip(a.idx(), name, ei, reason)); continue; }
                    for (int[] w : windows(a, e, simMs, ctx.artOffsetMs())) {
                        boolean excess = e.derivedThreshold() != null;
                        derived.add(new DerivedSpec(name, e.stat(), e.derivedFrom(),
                                excess ? null : e.derivedRatioAt(eq.awaken()),
                                e.derivedThreshold(), e.derivedStep(),
                                excess ? e.derivedPerStepAt(eq.awaken()) : null, e.derivedMaxCount(),
                                w[0], w[1], inst));
                    }
                    continue;
                }

                String reason = classifyCommon(a, e, ctx, false);
                boolean dormant = DORMANT.equals(reason);   // 무실효(대상 불일치·마리수 미충족) — 그리되 딜 제외·흐리게
                if (reason != null && !dormant) { if (!SILENT.equals(reason)) skips.add(new Skip(a.idx(), name, ei, reason)); continue; }

                double v = e.valueAt(eq.awaken()) * contentRatio(e, ctx);   // 각성차수 + 컨텐츠 배수
                if (!dormant && e.perEnemyMax() != null && ctx.m() != null) // 화염초: 적 1명당 value(최대 perEnemyMax명)
                    v *= Math.min(ctx.m(), e.perEnemyMax());
                for (int[] w : windows(a, e, simMs, ctx.artOffsetMs())) {
                    BuffSource src = new BuffSource(name, w[0], w[1]);
                    src.inst = inst;
                    src.dormant = dormant;
                    route(src, e.stat(), v, e.unit());
                    sources.add(src);
                }
            }
        }
        return new Result(sources, skips, derived, cycles, dmgMults, uptimes, speedDisplay, artSpeed, artCdr, artBuffDur, artSummonDur);
    }

    /**
     * onAttack 확률 약화(은펜던트·천도명약) 반영 — 공격 이벤트(딜 슬롯 시각)마다 chance 누산 → 발동 창.
     * 스킬 엔진 베놈과 동일 방식. individualDur=발동마다 독립 창 중첩(최대 maxStacks), 아니면 refresh(활성 중 정지).
     */
    public void accumulateUptime(Timeline tl, List<Integer> atkInstances, boolean preActivate, Result r) {
        if (r.uptimes().isEmpty()) return;
        List<Integer> atk = atkInstances != null ? atkInstances : new ArrayList<>(tl.slots().keySet());   // 비-dot 공격 인스턴스(베놈과 동일)
        for (UptimeSpec u : r.uptimes()) {
            List<int[]> windows = procWindows(atk, u, preActivate);
            for (var en : tl.slots().entrySet()) {                  // 슬롯별 겹치는 창 수 = 스택(최대 maxStacks)
                int t = en.getKey(), stacks = 0;
                for (int[] w : windows) if (w[0] <= t && t < w[1]) stacks++;
                stacks = Math.min(stacks, u.maxStacks());
                if (stacks > 0 && "Weakness".equals(u.stat())) en.getValue().mods().addStat(Stat.WEAKNESS, u.value() / 10.0 * stacks);
            }
        }
    }

    /** onAttack 확률 누산 발동 창들. individualDur=발동마다 독립 창, 아니면 refresh(연장 병합). preActivate=[0,dur)부터. */
    private static List<int[]> procWindows(List<Integer> atk, UptimeSpec u, boolean preActivate) {
        List<int[]> windows = new ArrayList<>();
        double acc = 0;
        if (u.individualDur()) {
            if (preActivate) windows.add(new int[]{0, u.durMs()});
            for (int t : atk) { acc += u.chance(); if (acc >= 1000.0) { acc -= 1000.0; windows.add(new int[]{t, t + u.durMs()}); } }
        } else {
            int winStart = preActivate ? 0 : -1, buffEnd = preActivate ? u.durMs() : -1;
            for (int t : atk) {
                acc += u.chance();
                if (acc >= 1000.0) {
                    acc -= 1000.0;
                    if (winStart >= 0 && t <= buffEnd) buffEnd = t + u.durMs();
                    else { if (winStart >= 0) windows.add(new int[]{winStart, buffEnd}); winStart = t; buffEnd = t + u.durMs(); }
                }
            }
            if (winStart >= 0) windows.add(new int[]{winStart, buffEnd});
        }
        return windows;
    }

    /** 스택 유물(육각·은펜던트·천도명약)의 시각별 스택 세그먼트 — 간트 표시용. */
    public record StackSeg(String label, int start, int end, int stacks, int inst) {}

    public List<StackSeg> stackSegments(Result r, boolean boss, int offsetMs, List<Integer> atk, boolean preActivate, int simMs) {
        List<StackSeg> out = new ArrayList<>();
        for (DmgMultSpec dm : r.dmgMults()) {                        // 육각: 전투 시작부터 빌드업(일반몹·보스 동일, 사전활성 offset 반영)
            int period = dm.periodMs(), max = dm.maxStacks();
            if (period <= 0) { out.add(new StackSeg(dm.label(), 0, simMs, max, dm.inst())); continue; }
            for (int s = 1; s <= max; s++) {   // s스택 구간 = [s×period-offset, (s+1)×period-offset). 0스택([0,period))은 표시 안 함
                int start = Math.max(0, s * period - offsetMs);
                int end = (s < max) ? Math.min((s + 1) * period - offsetMs, simMs) : simMs;
                if (start < end) out.add(new StackSeg(dm.label(), start, end, s, dm.inst()));
            }
        }
        for (UptimeSpec u : r.uptimes()) {                          // 은펜던트/천도명약: 발동 창 겹침 = 스택
            List<int[]> events = new ArrayList<>();
            for (int[] w : procWindows(atk, u, preActivate)) { events.add(new int[]{w[0], 1}); events.add(new int[]{Math.min(w[1], simMs), -1}); }
            events.sort((x, y) -> x[0] - y[0]);
            int cur = 0, segStart = 0;
            for (int[] ev : events) {
                if (ev[0] > segStart && cur > 0) out.add(new StackSeg(u.label(), segStart, ev[0], Math.min(cur, u.maxStacks()), u.inst()));
                cur += ev[1]; segStart = ev[0];
            }
        }
        return out;
    }

    /**
     * dmgMult(육각) 반영 — DMG를 슬롯별 스택만큼 곱증가. 옛 엔진과 동일: DMG += preset.dmg × (value‰/1000) × 스택.
     * 스택: 비보스=상시 최대, 보스=floor(t/periodMs)+1(전투시작부터 빌드업, 최대치 캡).
     */
    public void accumulateDmgMult(Timeline tl, Preset preset, Result r, boolean boss, int offsetMs) {
        for (DmgMultSpec dm : r.dmgMults()) {
            for (var en : tl.slots().entrySet()) {
                int t = en.getKey();
                // 전투 시작(t=0)=0스택, periodMs마다 +1(첫 스택은 period 경과 후). 사전활성(offsetMs)만큼 미리 채운다(디폴트 0).
                int stacks = dm.periodMs() > 0
                        ? Math.min(dm.maxStacks(), (int) ((double) (t + offsetMs) / dm.periodMs()))
                        : dm.maxStacks();
                double delta = preset.dmg() * dm.value() / 1000.0 * stacks;   // preset.dmg × 값(‰→배율) × 스택
                if (delta != 0) en.getValue().mods().addStat(Stat.DMG, delta);
            }
        }
    }

    /** 효과의 실효 트리거(effect레벨 우선, 없으면 유물레벨). 상시면 null. */
    private static String baseTrigger(ArtifactIR a, ArtifactIR.Effect e) {
        return e.trigger() != null ? e.trigger() : a.trigger();
    }

    /** 성배 발동 창 BuffSource(최종뎀 곱소스). */
    private static BuffSource grailWin(String label, int inst, int start, int end, int simMs, double frac) {
        BuffSource src = new BuffSource(label, start, Math.min(end, simMs));
        src.inst = inst;
        src.finalDmgFrac += frac;
        return src;
    }

    // ── 크확 파티션 사이클(순환의 고리) ──────────────────

    /** 라이브 크확 파티션 유물인가(어느 effect든 critLt/critGte 보유). 현재 순환의 고리만 해당. */
    private static boolean isCycle(ArtifactIR a) {
        for (ArtifactIR.Effect e : a.effects())
            if (e.critLt() != null || e.critGte() != null) return true;
        return false;
    }

    /** 유물 단위 게이트(컨텐츠 한정). 통과면 null. */
    private String classifyArtifact(ArtifactIR a, Ctx ctx) {
        if (a.defer()) return "defer(유물): 상태의존→컨텐츠모드";
        if (a.content() != null && !a.content().isEmpty()
                && (ctx.content() == null || !a.content().contains(ctx.content())))
            return "content 불일치: " + a.content();
        return null;
    }

    /** 순환 유물 → CycleSpec. effects를 critLt/critGte로 분류, period refresh 창 계산. */
    private CycleSpec buildCycle(ArtifactIR a, String name, int inst, int awaken, int simMs) {
        List<CycleEffect> lt = new ArrayList<>(), gte = new ArrayList<>();
        double threshold = 100.0;   // 기본 100%(critLt/critGte 값 1000‰)
        for (ArtifactIR.Effect e : a.effects()) {
            Double v = e.valueAt(awaken);
            if (v == null) continue;
            CycleEffect ce = new CycleEffect(e.stat(), v / 10.0, e.critExcessStep(), e.critExcessMax());
            if (e.critGte() != null) { gte.add(ce); threshold = e.critGte() / 10.0; }
            else if (e.critLt() != null) { lt.add(ce); threshold = e.critLt() / 10.0; }
        }
        // 창: period refresh(startDelayMs 첫 발동, periodMs마다 다음 갱신까지 지속)
        List<int[]> windows = new ArrayList<>();
        int period = a.periodMs() != null ? a.periodMs() : simMs;
        int first = a.startDelayMs() != null ? a.startDelayMs() : period;
        for (int t = first; t < simMs; t += period)
            windows.add(new int[]{t, Math.min(t + period, simMs)});
        return new CycleSpec(name, windows, lt, gte, threshold, inst);
    }

    /**
     * 크확 파티션 사이클을 슬롯에 반영. base({@link #accumulate}) 뒤·파생({@link #accumulateDerived}) <b>앞</b>에
     * 호출해야 순환이 준 크확이 파생(고대책 크뎀 등) 입력에 반영된다. 발동시점 크확은 창 첫 슬롯 기준(순환 자기 제외).
     */
    public java.util.List<java.util.Map<String, Object>> accumulateCycle(Timeline tl, Preset preset, Result r, int simMs) {
        var rows = new ArrayList<java.util.Map<String, Object>>();   // 창별 표시행(간트 툴팁용): 그 창의 실제 적용 효과
        for (CycleSpec cyc : r.cycles())
            for (int[] w : cyc.windows()) {
                var sub = tl.slots().subMap(w[0], true, w[1], w[1] >= simMs);
                if (sub.isEmpty()) continue;
                double liveCrit = preset.critRate() + sub.firstEntry().getValue().mods().get(Stat.CRIT_RATE);
                List<CycleEffect> chosen = (liveCrit >= cyc.threshold()) ? cyc.gteEffects() : cyc.ltEffects();
                var effs = new ArrayList<java.util.Map<String, Object>>();
                for (CycleEffect ce : chosen) {
                    double add = ce.value();
                    if (ce.isExcess()) {   // 초과형(평타뎀): 크확 초과분 step당 누진
                        double excess = Math.max(0, liveCrit - cyc.threshold());
                        int count = (int) Math.floor(excess / (ce.excessStep() / 10.0));
                        if (ce.excessMax() != null) count = Math.min(count, ce.excessMax());
                        add = count * ce.value();
                    }
                    if (add == 0) continue;
                    for (Slot slot : sub.values())
                        switch (ce.stat()) {
                            case "CriticalChance"  -> slot.mods().addStat(Stat.CRIT_RATE, add);
                            case "SkillPower"      -> slot.mods().addStat(Stat.SKILL_DMG, add);
                            case "BaseAttackPower" -> slot.mods().addStat(Stat.BASIC_ATK_DMG, add);
                            case "CriticalPower"   -> slot.mods().addStat(Stat.CRIT_DMG, add);
                            case "FinalDamage"     -> slot.mods().addFinalDmgSource(add / 100.0);
                            default -> { /* 확장 여지 */ }
                        }
                    effs.add(cycleEff(ce.stat(), add));   // 이 창에서 실제 적용된 효과+값(표시)
                }
                var row = new java.util.LinkedHashMap<String, Object>();
                row.put("label", cyc.label());
                row.put("start", w[0]);
                row.put("end", w[1]);
                row.put("always", false);
                row.put("artifact", true);
                row.put("inst", cyc.inst());
                if (!effs.isEmpty()) row.put("effects", effs);
                rows.add(row);
            }
        return rows;
    }

    /** 순환·파생 효과 stat(원본 문자열) → 표시 스탯키(route와 동일 매핑) + 값. */
    private static java.util.Map<String, Object> cycleEff(String rawStat, double value) {
        String key = switch (rawStat) {
            case "CriticalChance"  -> "CRIT_RATE";
            case "SkillPower"      -> "SKILL_DMG";
            case "BaseAttackPower" -> "BASIC_ATK_DMG";
            case "CriticalPower"   -> "CRIT_DMG";
            case "MaxDamageRatio"  -> "MAX_DMG_MULT";
            case "FinalDamage"     -> "FINAL_DMG";
            default                -> rawStat;
        };
        var e = new java.util.LinkedHashMap<String, Object>();
        e.put("stat", key);
        e.put("value", Math.round(value * 10) / 10.0);
        return e;
    }

    /**
     * 조립 소스를 타임라인 슬롯에 반영({@link BuffAccumulator#accumulate}와 동일 방식).
     * 스킬 버프 반영 뒤에 호출하면 같은 ModState 풀에 합쳐진다.
     */
    public void accumulate(Timeline tl, Result r, int simMs) {
        for (BuffSource src : r.sources()) {
            if (src.dormant()) continue;   // 무실효(dormant)는 딜에 반영 안 함 — 표시만(흐리게)
            boolean incl = src.endMs() >= simMs;   // 끝까지 지속이면 종료 슬롯 포함(경계 버그 방지, BuffAccumulator와 동일)
            for (Slot slot : tl.slots().subMap(src.startMs(), true, src.endMs(), incl).values())
                src.applyTo(slot.mods());
        }
    }

    /** 스킵 사유(적용 가능하면 null). derivedMode=파생 효과(라이브 후처리)면 route/값 검사 대신 파생 파라미터 검사. */
    /** 조건부 유물이 '현재 상황에선 정상적으로 비활성'일 때의 사유 — 미반영 목록에 표시하지 않는다. */
    private static final String SILENT = "__silent__";
    /** 활성이지만 현재 무실효(대상 불일치·마리수 미충족) — 딜 제외하되 흐리게 그린다(컨텐츠 전용 비활성과 구분). */
    static final String DORMANT = "__dormant__";

    private String classifyCommon(ArtifactIR a, ArtifactIR.Effect e, Ctx ctx, boolean derivedMode) {
        if (a.defer()) return "defer(유물): 상태의존→컨텐츠모드";
        if (e.defer()) return "defer(effect): 상태의존→컨텐츠모드";
        if ("companion".equals(e.target())) return "companion→동료엔진(v3)";
        if (e.priorityGroup() != null || e.require() != null) return "대상상태 require(v4)";
        if (e.critLt() != null || e.critGte() != null) return "라이브 크확 조건(v2b: 순환)";
        if (derivedMode) {
            if (e.derivedFrom() == null) return "파생 입력 미상";
            if (e.derivedRatioAt(0) == null && e.derivedPerStepAt(0) == null) return "파생 계수 없음";
        } else {
            if (!routable(e.stat())) return "ModState 풀 없음: " + e.stat();
            if (e.valueAt(0) == null) return "base 값 없음(derived 전용)";
        }

        // 컨텐츠 한정: 현재 컨텐츠가 목록에 없으면 미적용
        if (a.content() != null && !a.content().isEmpty()
                && (ctx.content() == null || !a.content().contains(ctx.content())))
            return "content 불일치: " + a.content();

        String trig = e.trigger() != null ? e.trigger() : a.trigger();
        if (trig != null && !"period".equals(trig) && !"combatStart".equals(trig))
            return "트리거 미모델(v1): " + trig;

        // 마리수 M 의존 = ★발동 게이트. 미충족이면 그 옵션은 아예 안 켜짐(SILENT skip). '무영향'이 아니라 '비발동'.
        // (세이람 일뎀=적2↑, 보뎀=적1처럼 마리수로 켜지는 효과가 배타적으로 갈리는 유물 — 미충족 옵션은 그리지 않는다)
        if (e.minEnemies() != null || e.perEnemyMax() != null || e.targetMode() != null) {
            if (ctx.m() == null || ctx.m() <= 0) return "마리수 M 필요(v4)";
            if (e.minEnemies() != null && ctx.m() < e.minEnemies()) return SILENT;
            boolean single = "single".equals(e.targetMode());
            boolean multi = "multi".equals(e.targetMode());
            if (single && ctx.m() != 1) return SILENT;
            if (multi && ctx.m() < 2) return SILENT;
        }

        // 스탯이 대상 한정(보스뎀·일몹뎀)인데 대상 불일치 = ★발동은 하나 딜 무영향 → DORMANT(그리되 툴팁서 취소선). 마리수 게이트(비발동)와 구분.
        boolean bossStat = "AttackPowerToBoss".equals(e.stat());
        boolean nonBossStat = "AttackPowerExcludeBoss".equals(e.stat());
        if ((e.bossOnly() || bossStat) && !ctx.boss()) return DORMANT;
        if ((e.bossZero() || nonBossStat) && ctx.boss()) return DORMANT;
        return null;
    }

    /** 컨텐츠 배수(현재 컨텐츠가 contentMult에 있으면 그 ratio, 없으면 1). */
    private double contentRatio(ArtifactIR.Effect e, Ctx ctx) {
        if (ctx.content() == null) return 1.0;
        for (ArtifactIR.ContentMult cm : e.contentMult())
            if (cm.content() != null && cm.content().contains(ctx.content()) && cm.ratio() != null)
                return cm.ratio();
        return 1.0;
    }

    /**
     * 효과의 활성 창들 [start,end). 상시=[0,sim), combatStart=1창, period=주기별 창.
     * 사전활성(off): 전투가 표시 t=0보다 off ms 먼저 시작했다고 보고, 진짜시간 창을 (t-off)로 시프트해 [0,sim) 클립.
     * → 양초(지연 보스뎀)·죽은자의 부적(period)·무지개 등껍질(combatStart) 등 시간형 유물이 사전활성만큼 이미 진행된 상태로 반영.
     */
    private List<int[]> windows(ArtifactIR a, ArtifactIR.Effect e, int simMs, int off) {
        String trig = e.trigger() != null ? e.trigger() : a.trigger();
        int delay = e.delayMs() != null ? e.delayMs() : 0;
        Integer dur = e.durationMs();
        // 비-상시 '버프' 유물은 벞지(BUFF_DURATION) 적용. 제외: 디버프(enemy/Weakness)·상시(dur=null)·순환(isCycle,여기 안 옴)·
        // 강제소멸형(fixedDuration: 양초처럼 절대시점에 전 효과 소멸 — 늘려도 강제로 사라짐).
        if (dur != null && buffDurTotal != 0 && !a.fixedDuration()
                && !"enemy".equals(e.target()) && !"Weakness".equals(e.stat()))
            dur = (int) Math.round(dur * (1 + buffDurTotal / 100.0));
        int H = simMs + Math.max(0, off);   // 진짜시간 지평(표시 [0,sim) = 진짜 [off, sim+off))
        List<int[]> raw = new ArrayList<>();

        if ("period".equals(trig)) {
            int period = a.periodMs() != null ? a.periodMs() : 0;
            if (period <= 0) raw.add(new int[]{0, H});
            else {
                int first = a.startDelayMs() != null ? a.startDelayMs() : period;
                int d = dur != null ? dur : period;
                for (int t = first; t < H; t += period) raw.add(new int[]{t, Math.min(t + d, H)});
            }
        } else {   // combatStart / 상시(트리거 없음) 동일 처리
            int start = delay;
            int end = dur != null ? Math.min(start + dur, H) : H;
            if (start < end) raw.add(new int[]{start, end});
        }

        // 사전활성 시프트: 진짜시간 [s,e) → 표시 [s-off, e-off), [0,sim) 클립
        List<int[]> out = new ArrayList<>();
        for (int[] w : raw) {
            int s = Math.max(0, w[0] - off), en = Math.min(simMs, w[1] - off);
            if (s < en) out.add(new int[]{s, en});
        }
        return out;
    }

    // ── 라우팅 ─────────────────────────────────────────
    // 스킬 IR와 동일 규약 + 계약 §6 추가분(SkillPower/BaseAttackPower/AttackPowerToBoss/AttackPowerExcludeBoss/DMG).
    // 유물은 성장(growthSlot) 없음 → 값 그대로. %스탯=÷10 표시%, FinalDamage=÷1000 배율.

    private static boolean routable(String stat) {
        if (stat == null) return false;
        return switch (stat) {
            case "FinalDamage", "ATK_PCT", "CriticalChance", "CriticalPower", "Weakness",
                 "DMG", "SkillPower", "BaseAttackPower", "AttackPowerToBoss", "AttackPowerExcludeBoss",
                 "PiercePower", "MaxDamageRatio",
                 "EnemyCritResist", "EnemyRecvCritDmg" -> true;   // v2: 방관·최대뎀 풀 + 크리티컬 디버프
            default -> false;
        };
    }

    private static void route(BuffSource src, String stat, double v, String unit) {
        switch (stat) {
            case "FinalDamage"            -> src.finalDmgFrac += v / 1000.0;
            case "ATK_PCT"                -> src.add("flat".equals(unit) ? Stat.ATK_PCT_ADD : Stat.ATK_PCT, v / 10.0);
            case "CriticalChance"         -> src.add(Stat.CRIT_RATE, v / 10.0);
            case "CriticalPower"          -> src.add(Stat.CRIT_DMG, v / 10.0);
            case "Weakness"               -> src.add(Stat.WEAKNESS, v / 10.0);
            case "DMG"                    -> src.add(Stat.DMG, v / 10.0);
            case "SkillPower"             -> src.add(Stat.SKILL_DMG, v / 10.0);
            case "BaseAttackPower"        -> src.add(Stat.BASIC_ATK_DMG, v / 10.0);
            case "AttackPowerToBoss"      -> src.add(Stat.BOSS_DMG, v / 10.0);
            case "AttackPowerExcludeBoss" -> src.add(Stat.NORMAL_DMG, v / 10.0);
            case "PiercePower"            -> src.add(Stat.DEF_PEN, v / 10.0);
            case "MaxDamageRatio"         -> src.add(Stat.MAX_DMG_MULT, v / 10.0);
            // 크리티컬 디버프(분류=디버프). 최종 fCrit에만 크확/크뎀에 가산 — 파생·증폭 제외(육각목류가 크확/크뎀 증폭해도 이 값은 별도로 최종 합류)
            case "EnemyCritResist"        -> src.add(Stat.CRIT_RATE_ENEMY, v / 10.0);
            case "EnemyRecvCritDmg"       -> src.add(Stat.CRIT_DMG_ENEMY, v / 10.0);
            default -> { /* routable()에서 이미 걸러짐 */ }
        }
    }

    // ── 파생(derived) 후처리(v2) ─────────────────────────
    // 라이브 스탯(크확·공속)에 의존해 슬롯마다 값이 다르므로 정적 BuffSource로 못 만들고,
    // 스킬·유물 base 반영이 끝난 뒤 각 슬롯의 라이브값을 읽어 파생 스탯을 주입한다.

    /**
     * 파생 효과를 타임라인 슬롯에 반영. {@link #accumulate}(스킬 base + 유물 base) <b>이후</b> 호출해야
     * 라이브 크확/공속이 확정된다.
     */
    public java.util.List<java.util.Map<String, Object>> accumulateDerived(Timeline tl, Preset preset, Result r, int simMs, java.util.List<BuffSource> allSources) {
        var rows = new ArrayList<java.util.Map<String, Object>>();
        for (DerivedSpec ds : r.derived()) {
            // ── 1) 딜 반영: 딜 슬롯(공격 시각)의 정확한 라이브 스탯으로 주입(현행과 동일 — 딜 불변) ──
            for (var en : tl.slots().subMap(ds.startMs(), true, ds.endMs(), ds.endMs() >= simMs).entrySet()) {
                double val = derivedCalc(ds, liveOf(ds.from(), preset, en.getValue().mods()));
                if (val != 0) switch (ds.stat()) {
                    case "CriticalPower"  -> en.getValue().mods().addStat(Stat.CRIT_DMG, val);
                    case "MaxDamageRatio" -> en.getValue().mods().addStat(Stat.MAX_DMG_MULT, val);
                    case "FinalDamage"    -> en.getValue().mods().addFinalDmgSource(val / 100.0);
                    default -> { /* v2b 확장 */ }
                }
            }
            // ── 2) 표시 세그먼트: 파생 입력 버프들의 실제 경계(정확한 시간) 기준. 딜 슬롯과 무관하게 시간 연속으로 자른다 ──
            Stat inStat = derivedInputStat(ds.from());
            if (inStat == null) continue;
            double base = derivedInputBase(ds.from(), preset);
            int lo = ds.startMs(), hi = Math.min(ds.endMs(), simMs);
            var bounds = new java.util.TreeSet<Integer>(java.util.List.of(lo, hi));
            var inputs = new ArrayList<BuffSource>();   // 파생 입력 stat을 주는 버프(그 경계가 파생 변화점)
            for (BuffSource s : allSources) {
                Double v = s.adds().get(inStat);
                if (v == null || v == 0) continue;
                int st = Math.max(s.startMs(), lo), en = Math.min(s.endMs(), hi);
                if (st >= en) continue;
                inputs.add(s); bounds.add(st); bounds.add(en);
            }
            var pts = new ArrayList<>(bounds);
            double cur = 0; int segStart = lo; boolean has = false;
            for (int i = 0; i < pts.size() - 1; i++) {
                int t1 = pts.get(i), t2 = pts.get(i + 1);
                double live = base;                                     // 이 구간 활성 입력 버프를 ★스탯 규칙대로 합성(공속=체감) — liveOf(딜)와 일치
                for (BuffSource s : inputs) if (s.startMs() <= t1 && s.endMs() >= t2) live = inStat.combine(live, s.adds().get(inStat));
                double disp = Math.round(derivedCalc(ds, live) * 10) / 10.0;
                if (!has || disp != cur) {
                    if (has && cur != 0) rows.add(derivedRow(ds, segStart, t1, cur, simMs));   // 값 바뀌는 지점에서 자름
                    cur = disp; segStart = t1; has = true;
                }
            }
            if (has && cur != 0) rows.add(derivedRow(ds, segStart, hi, cur, simMs));
        }
        return rows;
    }

    // ══ WindowSet용 값-윈도우 방출(Stage 1) ══════════════════════════════════════════
    // 순환/파생/육각/약화를 슬롯에 굽는 대신 [start,end)+값 {@link BuffSource} 윈도우로 방출한다.
    // ★호출 시점: base(스킬+유물+동료) 슬롯 반영 직후 · accumulateCycle 이전 —
    //   cycleWindows가 '창 첫 슬롯의 base 크확'을 읽어 accumulateCycle과 동일 발동을 재현해야 하기 때문.
    // baseInputs와 합쳐 {@link WindowSet}으로 만들면 임의 시각 fold(at)가 딜타임 slot.mods()와 일치한다(파리티 계약).

    /**
     * 순환/육각/약화/파생을 값-윈도우로 방출(cycle→dmgMult→uptime→derived 순, accumulate* 순서와 동일).
     * @param baseInputs 스킬버프+유물base+동료버프 소스 — 파생 입력(크확/공속). 여기에 순환 크확을 합류해 딜 경로와 일치.
     */
    public List<BuffSource> valueWindows(Timeline tl, Preset preset, Result r, int offsetMs, int simMs,
                                         List<Integer> atk, boolean preActivate, List<BuffSource> baseInputs) {
        List<BuffSource> cyc = cycleWindows(tl, preset, r, simMs);
        List<BuffSource> out = new ArrayList<>(cyc);
        out.addAll(dmgMultWindows(preset, r, offsetMs, simMs));
        out.addAll(uptimeWindows(atk, r, preActivate, simMs));
        List<BuffSource> derivedInputs = new ArrayList<>(baseInputs);
        derivedInputs.addAll(cyc);   // 파생 입력 = base + 순환 크확(딜 경로 slot.mods와 동일)
        out.addAll(derivedWindows(preset, r, simMs, derivedInputs));
        return out;
    }

    private static BuffSource win(String label, int inst, int start, int end) {
        BuffSource s = new BuffSource(label, start, end);
        s.inst = inst;
        return s;
    }

    /** 순환(순환의 고리) → 값-윈도우. accumulateCycle와 동일: 창 첫 슬롯 base 크확으로 lt/gte 파티션 선택. 효과별 개별 윈도우. */
    private List<BuffSource> cycleWindows(Timeline tl, Preset preset, Result r, int simMs) {
        List<BuffSource> out = new ArrayList<>();
        for (CycleSpec cyc : r.cycles())
            for (int[] w : cyc.windows()) {
                var sub = tl.slots().subMap(w[0], true, w[1], w[1] >= simMs);
                if (sub.isEmpty()) continue;
                double liveCrit = preset.critRate() + sub.firstEntry().getValue().mods().get(Stat.CRIT_RATE);
                List<CycleEffect> chosen = (liveCrit >= cyc.threshold()) ? cyc.gteEffects() : cyc.ltEffects();
                for (CycleEffect ce : chosen) {
                    double add = ce.value();
                    if (ce.isExcess()) {
                        double excess = Math.max(0, liveCrit - cyc.threshold());
                        int count = (int) Math.floor(excess / (ce.excessStep() / 10.0));
                        if (ce.excessMax() != null) count = Math.min(count, ce.excessMax());
                        add = count * ce.value();
                    }
                    if (add == 0) continue;
                    BuffSource s = win(cyc.label(), cyc.inst(), w[0], w[1]);
                    boolean routed = true;
                    switch (ce.stat()) {
                        case "CriticalChance"  -> s.add(Stat.CRIT_RATE, add);
                        case "SkillPower"      -> s.add(Stat.SKILL_DMG, add);
                        case "BaseAttackPower" -> s.add(Stat.BASIC_ATK_DMG, add);
                        case "CriticalPower"   -> s.add(Stat.CRIT_DMG, add);
                        case "FinalDamage"     -> s.finalDmgFrac += add / 100.0;
                        default -> routed = false;
                    }
                    if (routed) out.add(s);
                }
            }
        return out;
    }

    /** 육각(dmgMult) → DMG 값-윈도우. accumulateDmgMult와 동일: 스택 구간마다 delta=preset.dmg×값‰×스택. */
    private List<BuffSource> dmgMultWindows(Preset preset, Result r, int offsetMs, int simMs) {
        List<BuffSource> out = new ArrayList<>();
        for (DmgMultSpec dm : r.dmgMults()) {
            int period = dm.periodMs(), max = dm.maxStacks();
            if (period <= 0) {   // 상시 최대 스택
                double delta = preset.dmg() * dm.value() / 1000.0 * max;
                if (delta != 0) { BuffSource s = win(dm.label(), dm.inst(), 0, simMs); s.add(Stat.DMG, delta); out.add(s); }
                continue;
            }
            for (int st = 1; st <= max; st++) {   // st스택 구간 = [st×period-offset, (st+1)×period-offset), st==max는 simMs까지
                int start = Math.max(0, st * period - offsetMs);
                int end = (st < max) ? Math.min((st + 1) * period - offsetMs, simMs) : simMs;
                if (start >= end) continue;
                double delta = preset.dmg() * dm.value() / 1000.0 * st;
                if (delta == 0) continue;
                BuffSource s = win(dm.label(), dm.inst(), start, end); s.add(Stat.DMG, delta); out.add(s);
            }
        }
        return out;
    }

    /** 약화(uptime, 은펜던트·천도명약) → WEAKNESS 값-윈도우. accumulateUptime와 동일: 발동창 겹침 수=스택(캡). */
    private List<BuffSource> uptimeWindows(List<Integer> atk, Result r, boolean preActivate, int simMs) {
        List<BuffSource> out = new ArrayList<>();
        List<Integer> atkList = atk != null ? atk : new ArrayList<>();
        for (UptimeSpec u : r.uptimes()) {
            if (!"Weakness".equals(u.stat())) continue;
            List<int[]> events = new ArrayList<>();
            for (int[] w : procWindows(atkList, u, preActivate)) { events.add(new int[]{w[0], 1}); events.add(new int[]{Math.min(w[1], simMs), -1}); }
            events.sort((x, y) -> x[0] - y[0]);
            int cur = 0, segStart = 0;
            for (int[] ev : events) {
                if (ev[0] > segStart && cur > 0) {
                    int stacks = Math.min(cur, u.maxStacks());
                    BuffSource s = win(u.label(), u.inst(), segStart, ev[0]); s.add(Stat.WEAKNESS, u.value() / 10.0 * stacks); out.add(s);
                }
                cur += ev[1]; segStart = ev[0];
            }
        }
        return out;
    }

    /** 파생(헬레나 최대뎀·물병 최종뎀·고대책 크뎀) → 값-윈도우. accumulateDerived 딜경로와 동일: 입력(크확/공속) 경계마다 잘라 val=derivedCalc(live). */
    private List<BuffSource> derivedWindows(Preset preset, Result r, int simMs, List<BuffSource> inputWindows) {
        List<BuffSource> out = new ArrayList<>();
        for (DerivedSpec ds : r.derived()) {
            Stat inStat = derivedInputStat(ds.from());
            if (inStat == null) continue;
            double base = derivedInputBase(ds.from(), preset);
            int lo = ds.startMs(), hi = Math.min(ds.endMs(), simMs);
            var bounds = new java.util.TreeSet<Integer>(java.util.List.of(lo, hi));
            var inputs = new ArrayList<BuffSource>();
            for (BuffSource s : inputWindows) {
                if (s.dormant()) continue;
                Double v = s.adds().get(inStat);
                if (v == null || v == 0) continue;
                int st = Math.max(s.startMs(), lo), en = Math.min(s.endMs(), hi);
                if (st >= en) continue;
                inputs.add(s); bounds.add(st); bounds.add(en);
            }
            var pts = new ArrayList<>(bounds);
            for (int i = 0; i < pts.size() - 1; i++) {
                int t1 = pts.get(i), t2 = pts.get(i + 1);
                double live = base;
                for (BuffSource s : inputs) if (s.startMs() <= t1 && s.endMs() >= t2) live = inStat.combine(live, s.adds().get(inStat));
                double val = derivedCalc(ds, live);
                if (val == 0) continue;
                BuffSource w = win(ds.label(), ds.inst(), t1, t2);
                boolean routed = true;
                switch (ds.stat()) {
                    case "CriticalPower"  -> w.add(Stat.CRIT_DMG, val);
                    case "MaxDamageRatio" -> w.add(Stat.MAX_DMG_MULT, val);
                    case "FinalDamage"    -> w.finalDmgFrac += val / 100.0;
                    default -> routed = false;
                }
                if (routed) out.add(w);
            }
        }
        return out;
    }

    /** 파생 입력 stat(from) → 슬롯 mods 키. */
    private static Stat derivedInputStat(String from) {
        return "CriticalChance".equals(from) ? Stat.CRIT_RATE : "AttackSpeed".equals(from) ? Stat.ATK_SPEED : null;
    }

    /**
     * 파생·순환·육각이 <b>입력</b>으로 쓰는 스탯 집합. 이 스탯들은 유물 효과(mods)를 통해 딜에 재반영되므로,
     * 큐브 옵션 등으로 이 스탯을 바꾸면 유물까지 다시 조립(재sim)해야 정확하다(그룹 캐시 재평가로는 못 잡음).
     * 헬레나·물병(파생)=from, 순환의 고리(사이클)=크확, 육각 목걸이(dmgMult)=DMG.
     */
    public static java.util.Set<Stat> derivedInputStats(Result r) {
        java.util.Set<Stat> s = java.util.EnumSet.noneOf(Stat.class);
        for (DerivedSpec d : r.derived()) { Stat in = derivedInputStat(d.from()); if (in != null) s.add(in); }
        if (!r.cycles().isEmpty()) s.add(Stat.CRIT_RATE);   // 순환의 고리 = 라이브 크확 파티션 입력
        if (!r.dmgMults().isEmpty()) s.add(Stat.DMG);        // 육각 수정 목걸이 = 현재 DMG% 기반 곱
        return s;
    }
    /** 파생 입력 프리셋 base(%). */
    private static double derivedInputBase(String from, Preset preset) {
        return "CriticalChance".equals(from) ? preset.critRate() : "AttackSpeed".equals(from) ? preset.atkSpeed() : 0;
    }
    /** 파생값(%p) = f(입력 라이브값). ratio형=비례, 초과형=threshold 초과분 step당 누진. */
    private static double derivedCalc(DerivedSpec ds, double live) {
        if (ds.isRatio()) return live * ds.ratio() / 1000.0;
        double excess = Math.max(0, live - ds.threshold() / 10.0);
        int count = (int) Math.floor(excess / (ds.step() / 10.0));
        if (ds.maxCount() != null) count = Math.min(count, ds.maxCount());
        return count * ds.perStep() / 10.0;
    }

    /** 파생 세그먼트 1개 → 표시행. */
    private static java.util.Map<String, Object> derivedRow(DerivedSpec ds, int start, int end, double val, int simMs) {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("label", ds.label());
        row.put("start", start);
        row.put("end", end);
        row.put("always", start == 0 && end >= simMs);
        row.put("artifact", true);
        row.put("inst", ds.inst());
        var effs = new ArrayList<java.util.Map<String, Object>>();
        effs.add(cycleEff(ds.stat(), val));
        row.put("effects", effs);
        return row;
    }

    /** 파생 입력 라이브값(%) = 프리셋 ↔ 슬롯 mods를 ★스탯 규칙대로 합성(공속=체감150, 크확=합). 그 시점까지 반영된 총 스탯. */
    private static double liveOf(String from, Preset preset, ModState mods) {
        return switch (from) {
            case "CriticalChance" -> Stat.CRIT_RATE.combine(preset.critRate(), mods.get(Stat.CRIT_RATE));
            case "AttackSpeed"    -> Stat.ATK_SPEED.combine(preset.atkSpeed(), mods.get(Stat.ATK_SPEED));   // 슬롯 공속(유물 상시+스킬+동료) 체감 합성
            default -> 0;
        };
    }
}
