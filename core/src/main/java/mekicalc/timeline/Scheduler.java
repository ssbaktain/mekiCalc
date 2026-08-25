package mekicalc.timeline;

import mekicalc.timeline.ir.EffectIR;
import mekicalc.timeline.ir.MasteryIR;
import mekicalc.timeline.ir.SkillBook;
import mekicalc.timeline.ir.SkillIR;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 타임라인 스케줄러 — 1단계 "행동 배치".
 *
 * <p>자동 모드: 매 행동슬롯마다 우선순위(버프 액티브 &gt; 딜 액티브, 각 그룹 idx 내림차순)로 쿨 완료
 * 스킬을 재선택, 없으면 평타. 남은 시간을 최고차수 평타로 채운다.
 *
 * <p>동적 반영:
 * <ul>
 *   <li>공속 버프(민첩한 몸놀림 등, 선반영 아님)가 켜진 구간은 슬롯 간격을 재계산한다.
 *   <li>쿨타임은 매 시전 시점에 계산한다({@link SkillDpsModel#cooldown}: %먼저→flat 특수규칙→하한4초).
 *       크로스스킬 쿨감(타 스킬이 대상 쿨에 영향)도 이 지점에서 반영할 수 있게 열어 둔다.
 * </ul>
 *
 * 딜 이벤트 전개(도트·프록)·버프 누적은 후속 단위.
 */
public final class Scheduler {

    private final SkillBook book;
    private final Preset preset;
    private final int charLv;
    /** 사용자가 사용 해제한 액티브 스킬 idx(자동 선택·수동 스킬 자동채움에서 제외). 수동 직접 배치는 제외 안 함. */
    private final java.util.Set<Integer> disabled;

    /** 스킬 idx → 쿨감%(체감 실효, 지금은 단일소스 직접값). */
    private final Map<Integer, Double> cdrPct = new HashMap<>();
    /** 스킬 idx → 쿨감 flat(초). */
    private final Map<Integer, Double> cdrFlatSec = new HashMap<>();
    /** 스킬 idx → Σ지속증가 분율(0.5 = +50%). */
    private final Map<Integer, Double> durBoost = new HashMap<>();
    /** 스킬 idx → Σ지속증가 flat(ms). */
    private final Map<Integer, Double> durFlatMs = new HashMap<>();

    /** 스킬 사용 최적화: 스킬 앞 잔틈(&gt;평타 절반)을 평타로 메워 스킬을 슬롯 경계로 밀기. 자동=항상, 수동=토글. */
    private boolean optimize = false;
    public void setOptimize(boolean o) { this.optimize = o; }

    public Scheduler(SkillBook book, Preset preset) { this(book, preset, java.util.Set.of()); }

    public Scheduler(SkillBook book, Preset preset, java.util.Set<Integer> disabled) {
        this.book = book;
        this.preset = preset;
        this.charLv = preset.level();
        this.disabled = disabled == null ? java.util.Set.of() : disabled;
        indexMasteries();
    }

    /** 쿨감/지속 마스터리를 스킬별로 미리 집계(해금 레벨 필터). */
    private void indexMasteries() {
        for (MasteryIR m : book.masteries()) {
            if (m.lv() > charLv || m.skill() == null) continue;
            int sk = m.skill();
            String type = m.type();
            double v = m.value() == null ? 0 : m.value();
            if ("cooldown".equals(type)) {
                // 천분율: value -300 = -30%. unit pct=%, 그 외(ms/sec)는 flat.
                if (m.unit() == null || m.unit().contains("pct")) {
                    cdrPct.merge(sk, Math.abs(v) / 10.0, Double::sum);
                } else if (m.unit().contains("ms")) {
                    cdrFlatSec.merge(sk, Math.abs(v) / 1000.0, Double::sum);
                } else { // sec
                    cdrFlatSec.merge(sk, Math.abs(v), Double::sum);
                }
            } else if ("duration".equals(type)) {
                // unit pct=천분율(500=+50%), ms=flat, 그 외 sec. cooldown과 동일 규칙.
                if (m.unit() == null || m.unit().contains("pct")) durBoost.merge(sk, v / 1000.0, Double::sum);
                else if (m.unit().contains("ms")) durFlatMs.merge(sk, v, Double::sum);
                else durFlatMs.merge(sk, v * 1000, Double::sum);
            }
            // 복합 마스터리의 nested duration(아드레날린-지속 +5000ms)
            for (EffectIR e : m.effects()) {
                if ("duration".equals(e.type()) && e.value() != null)
                    durFlatMs.merge(sk, e.value(), Double::sum);
            }
        }
    }

    /**
     * 이벤트 누산 버프/디버프의 전방 상태. 발동을 <b>결정론적 누산기</b>로 확정해 실제 활성 윈도우를 만든다.
     * <ul>
     *   <li><b>count형</b>(아드레날린, onActiveSkill): 트리거 N회 누적마다 발동.
     *   <li><b>확률형</b>(베놈, onAttack): 트리거마다 확률(‰) 누적, 1000 도달 시 발동+초과분 이월
     *       (장기 발동률=chance, 기댓값 보존). ★계수/값은 full로 두고 <b>윈도우 밀도가 확률을 표현</b>.
     * </ul>
     * 공통: 활성 중(refresh:false)엔 누산 정지 → dot 지속(예 10초)+재충전 시간만큼의 <b>실제 가동률</b>(≈88%)이 나온다.
     * 공속을 가진 경우 스케줄에 되먹임돼(활성 구간 슬롯 간격 단축) 자연 반영된다.
     */
    private static final class EventBuff {
        final int idx, durMs;
        final boolean onAttackOnly;   // true=onAttack(딜 액션만 트리거), false=onActiveSkill(모든 액션)
        final int count;              // count 누산이면 >0
        final int chancePermille;     // 확률 누산이면 >0(천분율)
        final double spdPct;          // 공속% (없으면 0)
        int counter = 0, buffEnd = -1;
        double accum = 0;             // 확률 누산치(‰)
        final List<int[]> windows = new ArrayList<>();
        EventBuff(int idx, boolean onAttackOnly, int count, int chancePermille, int durMs, double spdPct) {
            this.idx = idx; this.onAttackOnly = onAttackOnly; this.count = count;
            this.chancePermille = chancePermille; this.durMs = durMs; this.spdPct = spdPct;
        }
        double spdAt(int t) { return t < buffEnd ? spdPct : 0.0; }
        /** 액션 1회. onAttack형은 딜 액션만. 활성 중이면 정지(refresh:false). 임계 도달 시 윈도우 개방(초과분 이월). */
        void onAction(int t, int simMs, boolean deals) {
            if (onAttackOnly && !deals) return;
            if (t < buffEnd) return;                       // 활성 중 누산 정지
            boolean fire;
            if (count > 0) { fire = (++counter >= count); if (fire) counter = 0; }
            else { accum += chancePermille; fire = (accum >= 1000.0); if (fire) accum -= 1000.0; }
            if (fire) { windows.add(new int[]{t, Math.min(t + durMs, simMs)}); buffEnd = t + durMs; }
        }
    }

    /** 자동 배치. simMs = 유저 지정 시뮬 길이. 액션 + 이벤트 버프 윈도우를 함께 확정해 반환. */
    public Schedule placeAuto(int simMs) {
        SkillIR basic = pickBasic();
        List<SkillIR> actives = castableActives();      // 우선순위 정렬 완료본
        List<EventBuff> evtBuffs = buildEventBuffs();   // onActiveSkill count 버프(아드)

        Map<Integer, Integer> nextReady = new HashMap<>();
        for (SkillIR s : actives) nextReady.put(s.idx(), 0);

        List<double[]> spdWindows = new ArrayList<>();   // 시전형 공속(민첩): {start, end, pct}
        double baseSpd = preset.atkSpeed();

        List<Action> out = new ArrayList<>();
        double t = 0;
        while (t < simMs) {
            int ti = (int) Math.round(t);

            // 현재 공속 = base + 시전형 공속윈도우 + 이벤트형 공속(아드, 과거로 확정)
            double spd = baseSpd + activeAtkSpeed(spdWindows, ti);
            for (EventBuff eb : evtBuffs) spd += eb.spdAt(ti);
            double slot = 1000.0 / (1.0 + spd / 100.0);

            // 우선순위대로 쿨 완료된 첫 액티브
            SkillIR chosen = null;
            for (SkillIR s : actives) {
                if (nextReady.get(s.idx()) <= ti) { chosen = s; break; }
            }

            if (chosen != null) {
                out.add(Action.cast(ti, chosen));
                nextReady.put(chosen.idx(), ti + (int) Math.round(reuseIntervalMs(chosen)));
                double spdPct = atkSpeedBuffPct(chosen);
                if (spdPct > 0) addSpdWindow(spdWindows, ti, effDurationMs(chosen), spdPct, chosen.idx());
                // (크로스스킬 쿨감 훅: chosen이 타 스킬 쿨을 줄이면 여기서 nextReady 조정)
                for (EventBuff eb : evtBuffs) eb.onAction(ti, simMs, chosen.hasDeal());
                t += slot;
                continue;
            }

            // 쿨 도는 스킬이 이 슬롯 안(t~t+slot)에 있으면 → 스킬 우선.
            // 평타를 격자로 먼저 채우지 말고 쿨 만료 시점으로 점프해 스킬을 그 시각에 발동한다.
            // (스킬을 쿨타임대로 먼저 놓고, 남는 틈에만 평타를 채운다. 슬롯 미만 잔틈은 비운다.)
            int nsr = Integer.MAX_VALUE;
            for (SkillIR s : actives) {
                int nr = nextReady.get(s.idx());
                if (nr > ti && nr < nsr) nsr = nr;
            }
            if (nsr < t + slot) {
                // 자동=항상 최적화: 잔틈(nsr−t)이 평타 절반 초과면 평타 한 대로 메우고(스킬을 슬롯 경계로 밀기),
                // 절반 이하면 정확한 쿨 만료 시각에 발동(잔틈은 비움).
                if ((nsr - t) > slot * 0.5) {
                    out.add(Action.basic(ti, basic));
                    for (EventBuff eb : evtBuffs) eb.onAction(ti, simMs, true);
                    t += slot;
                } else {
                    t = nsr;
                }
                continue;
            }

            // 온전한 평타 한 대가 들어가는 틈 → 평타로 채움.
            out.add(Action.basic(ti, basic));
            for (EventBuff eb : evtBuffs) eb.onAction(ti, simMs, true);
            t += slot;
        }

        Map<Integer, List<int[]>> ew = new HashMap<>();
        for (EventBuff eb : evtBuffs) ew.put(eb.idx, eb.windows);
        return new Schedule(out, ew);
    }

    /** 수동 배치 입력 1건: 지정 시각(ms)에 지정 스킬 시전. */
    public record ManualCast(int timeMs, int skillIdx) {}

    /**
     * 수동 배치. 유저가 지정한 (시각, 스킬)대로 시전한다.
     * <ul>
     *   <li>{@code autoFillBasic=true}: 빈 시간을 최고차수 평타로 채운다(기존 기본 동작).
     *       스킬은 지정 시각에 시전(그 앞엔 평타를 채우되 한 대가 안 들어가면 빔). 지정 시각에 쿨이 안 돌았으면
     *       <b>B안 지연</b>(쿨 돌 때까지 평타로 때우다 발동).
     *   <li>{@code autoFillBasic=false}: 자동 평타 채움 없이 <b>배치표에 놓은 액션만</b> 시전한다.
     *       평타도 배치표에서 직접 놓을 수 있다(basicAttack 트리거 스킬 = 평타로 처리).
     * </ul>
     * 공속·쿨·이벤트버프(아드)는 자동모드와 동일하게 전방 반영.
     */
    public Schedule placeManual(List<ManualCast> casts, int simMs, boolean autoFillBasic) {
        return placeManual(casts, simMs, autoFillBasic, false);
    }

    /**
     * @param fillSkills 하이브리드: 핀 배치(유저 지정) 사이 빈 슬롯을 <b>사용 중인 액티브 스킬로 쿨 맞춰 자동 채움</b>(그 뒤 평타는 autoFillBasic).
     */
    public Schedule placeManual(List<ManualCast> casts, int simMs, boolean autoFillBasic, boolean fillSkills) {
        if (fillSkills) return placeManualHybrid(casts, simMs, autoFillBasic);
        return autoFillBasic ? placeManualAutoFill(casts, simMs) : placeManualExact(casts, simMs);
    }

    /**
     * 하이브리드 배치: 유저가 지정한 핀 캐스트는 그 시각에 두고, 나머지 슬롯은 자동모드처럼
     * 우선순위·쿨 기반으로 사용 중인 액티브 스킬을 채운다. 그래도 빈 슬롯은 autoFillBasic이면 평타.
     */
    private Schedule placeManualHybrid(List<ManualCast> casts, int simMs, boolean autoFillBasic) {
        List<ManualCast> pending = new ArrayList<>(casts);
        pending.sort(Comparator.comparingInt(ManualCast::timeMs));
        int pi = 0;

        // 핀 스킬별 지정 시각 — 자동채움이 이 스킬을 핀 시각에 쓸 수 있도록 예약(그 전에 자동으로 안 씀).
        Map<Integer, List<Integer>> pinTimes = new HashMap<>();
        for (ManualCast c : pending) {
            SkillIR sk = book.skill(c.skillIdx());
            if (sk != null && !"basicAttack".equals(sk.trigger()))
                pinTimes.computeIfAbsent(sk.idx(), k -> new ArrayList<>()).add(c.timeMs());
        }

        SkillIR basic = pickBasic();
        List<SkillIR> actives = castableActives();      // disabled(사용해제) 반영됨
        List<EventBuff> evtBuffs = buildEventBuffs();
        Map<Integer, Integer> nextReady = new HashMap<>();       // 자동채움 재사용(reuseInterval: 설치/버프는 지속 끝나야)
        Map<Integer, Integer> nextReadyCd = new HashMap<>();     // 쿨만(핀=강제용: reuseInterval 무시, 쿨만 지킴)
        for (SkillIR s : actives) { nextReady.put(s.idx(), 0); nextReadyCd.put(s.idx(), 0); }
        List<double[]> spdWindows = new ArrayList<>();
        double baseSpd = preset.atkSpeed();

        List<Action> out = new ArrayList<>();
        double t = 0;
        while (t < simMs) {
            int ti = (int) Math.round(t);
            double slot = 1000.0 / (1.0 + curSpd(ti, spdWindows, baseSpd, evtBuffs) / 100.0);

            // 1) 이 슬롯 안에 핀 캐스트가 있으면 배치. 최적화 ON이면 핀(유저 배치 스킬)도 슬롯 경계로 스냅한다:
            //    핀 앞 잔틈 > 평타 절반이면 평타로 메우고 핀을 다음 슬롯으로 밀기, ≤ 절반이면 슬롯 경계로 앞당김(쿨 OK일 때만).
            if (pi < pending.size() && pending.get(pi).timeMs() <= t + slot) {
                SkillIR sk = book.skill(pending.get(pi).skillIdx());
                int pinT = Math.max((int) Math.round(pending.get(pi).timeMs()), ti);
                boolean skillPin = sk != null && !"basicAttack".equals(sk.trigger());
                int ready = skillPin ? nextReadyCd.getOrDefault(sk.idx(), 0) : 0;   // 핀=강제 사용: 쿨만 지키고 reuseInterval(지속 대기)은 무시 → 기존 설치/버프 덮어쓰고 즉시
                if (optimize && autoFillBasic && skillPin) {
                    double gap = pinT - t;
                    // 잔틈>절반 이거나 아직 쿨 미완이면 → 평타로 메우고 핀을 다음 슬롯으로 밀기(pi 유지).
                    if (gap > slot * 0.5 || ti < ready) {
                        out.add(Action.basic(ti, basic));
                        for (EventBuff eb : evtBuffs) eb.onAction(ti, simMs, true);
                        t += slot;
                        continue;
                    } else if (gap > 0) {
                        pinT = ti;   // 잔틈 ≤ 절반 & 쿨 충족 → 슬롯 경계로 앞당김
                    }
                }
                // 핀 쿨 미완이면 지연(최적화 무관·평타 못 채우는 경우 포함): 쿨 도는 시점으로 밀기.
                if (skillPin && ready > pinT) pinT = ready;
                int st = pinT;
                pi++;
                if (sk == null) continue;
                if ("basicAttack".equals(sk.trigger())) {
                    out.add(Action.basic(st, basic));
                    for (EventBuff eb : evtBuffs) eb.onAction(st, simMs, true);
                } else {
                    out.add(Action.cast(st, sk));
                    nextReady.put(sk.idx(), st + (int) Math.round(reuseIntervalMs(sk)));
                    nextReadyCd.put(sk.idx(), st + (int) Math.round(effCooldownMs(sk)));   // 쿨(핀 강제용)
                    double spdPct = atkSpeedBuffPct(sk);
                    if (spdPct > 0) addSpdWindow(spdWindows, st, effDurationMs(sk), spdPct, sk.idx());
                    for (EventBuff eb : evtBuffs) eb.onAction(st, simMs, sk.hasDeal());
                }
                t = st + 1000.0 / (1.0 + curSpd(st, spdWindows, baseSpd, evtBuffs) / 100.0);
                continue;
            }

            // 2) 자동: 쿨 완료된 우선순위 액티브(다가오는 핀 막는 스킬은 예약 제외) → 지금(ti) 발동.
            SkillIR chosen = null;
            for (SkillIR s : actives) {
                if (nextReady.get(s.idx()) > ti) continue;
                if (reservedForPin(s, ti, pinTimes)) continue;   // 이 스킬은 곧 핀 예정 → 지금 안 씀(쿨 보존)
                chosen = s; break;
            }
            if (chosen != null) {
                out.add(Action.cast(ti, chosen));
                nextReady.put(chosen.idx(), ti + (int) Math.round(reuseIntervalMs(chosen)));
                nextReadyCd.put(chosen.idx(), ti + (int) Math.round(effCooldownMs(chosen)));   // 쿨(핀 강제용)
                double spdPct = atkSpeedBuffPct(chosen);
                if (spdPct > 0) addSpdWindow(spdWindows, ti, effDurationMs(chosen), spdPct, chosen.idx());
                for (EventBuff eb : evtBuffs) eb.onAction(ti, simMs, chosen.hasDeal());
                t += slot;
                continue;
            }

            // 3) 곧(이 슬롯 안에) 쿨 도는 자동 스킬 또는 핀이 있으면 그 시점으로 점프 — 평타를 격자로 채우지 않고
            //    스킬을 정확한 쿨 만료 시각에 발동(자동 모드와 동일). 슬롯 미만 잔틈은 비운다.
            int nextPin = pi < pending.size() ? Math.max(pending.get(pi).timeMs(), ti) : Integer.MAX_VALUE;
            int nsr = Integer.MAX_VALUE;
            for (SkillIR s : actives) {
                if (reservedForPin(s, ti, pinTimes)) continue;
                int nr = nextReady.get(s.idx());
                if (nr > ti && nr < nsr) nsr = nr;
            }
            int nextEvt = Math.min(nextPin, nsr);
            if (nextEvt < t + slot) {
                // 최적화(토글): 다음이 '자동 스킬 쿨'이고 잔틈>평타 절반이면 평타로 메워 스킬을 슬롯 경계로 밀기.
                // 핀(유저 지정 시각)은 정시 유지 — 밀지 않는다.
                if (optimize && autoFillBasic && nsr <= nextPin && (nsr - t) > slot * 0.5) {
                    out.add(Action.basic(ti, basic));
                    for (EventBuff eb : evtBuffs) eb.onAction(ti, simMs, true);
                    t += slot;
                } else {
                    t = nextEvt;
                }
                continue;
            }

            // 4) 온전한 평타 한 대가 들어가는 틈 → 평타로 채움(autoFillBasic).
            if (autoFillBasic) {
                out.add(Action.basic(ti, basic));
                for (EventBuff eb : evtBuffs) eb.onAction(ti, simMs, true);
            }
            t += slot;
        }

        Map<Integer, List<int[]>> ew = new HashMap<>();
        for (EventBuff eb : evtBuffs) ew.put(eb.idx, eb.windows);
        return new Schedule(out, ew);
    }

    /** ti에 이 스킬을 자동으로 쓰면 다가오는 핀(같은 스킬) 시각에 쿨이 안 돌아 못 쓰게 되는가 → 예약(자동 사용 보류). */
    private boolean reservedForPin(SkillIR s, int ti, Map<Integer, List<Integer>> pinTimes) {
        List<Integer> pins = pinTimes.get(s.idx());
        if (pins == null) return false;
        int cd = (int) Math.round(effCooldownMs(s));
        for (int pt : pins) if (pt > ti && ti + cd > pt) return true;
        return false;
    }

    /** 빈 시간 평타 자동 채움(기존 동작). */
    private Schedule placeManualAutoFill(List<ManualCast> casts, int simMs) {
        List<ManualCast> pending = new ArrayList<>(casts);
        pending.sort(Comparator.comparingInt(ManualCast::timeMs));

        SkillIR basic = pickBasic();
        List<EventBuff> evtBuffs = buildEventBuffs();
        Map<Integer, Integer> nextReady = new HashMap<>();
        List<double[]> spdWindows = new ArrayList<>();
        double baseSpd = preset.atkSpeed();

        List<Action> out = new ArrayList<>();
        double t = 0;
        int pi = 0;
        while (t < simMs) {
            int ti = (int) Math.round(t);
            double slot = 1000.0 / (1.0 + curSpd(ti, spdWindows, baseSpd, evtBuffs) / 100.0);

            double nextSkillTime = (pi < pending.size()) ? pending.get(pi).timeMs() : Double.MAX_VALUE;

            if (t + slot <= nextSkillTime) {
                // 다음 스킬 전에 평타 한 대가 온전히 들어감 → 평타
                out.add(Action.basic(ti, basic));
                for (EventBuff eb : evtBuffs) eb.onAction(ti, simMs, true);   // 평타=딜
                t += slot;
            } else if (optimize && (nextSkillTime - t) > slot * 0.5) {
                // 최적화(토글): 스킬 앞 잔틈>평타 절반이면 평타로 메우고 스킬은 다음 슬롯으로 밀기(pi 유지).
                out.add(Action.basic(ti, basic));
                for (EventBuff eb : evtBuffs) eb.onAction(ti, simMs, true);
                t += slot;
            } else {
                // 스킬 차례(지정시각 도달). ★핀=강제 사용: 쿨/지속 지연 없이 유저 시각 그대로 시전(프론트가 쿨 이전 배치를 막음). 기존 설치/버프는 덮어씀.
                SkillIR sk = book.skill(pending.get(pi).skillIdx());
                if (sk == null) { pi++; continue; }
                int st = Math.max((int) Math.round(nextSkillTime), ti);
                if (optimize && ti < st && !"basicAttack".equals(sk.trigger()))   // 최적화: 잔틈 ≤ 평타 절반 → 슬롯 경계로 앞당김
                    st = ti;
                if ("basicAttack".equals(sk.trigger())) {
                    out.add(Action.basic(st, basic));
                    for (EventBuff eb : evtBuffs) eb.onAction(st, simMs, true);
                } else {
                    out.add(Action.cast(st, sk));
                    nextReady.put(sk.idx(), st + (int) Math.round(reuseIntervalMs(sk)));
                    double spdPct = atkSpeedBuffPct(sk);
                    if (spdPct > 0) addSpdWindow(spdWindows, st, effDurationMs(sk), spdPct, sk.idx());
                    for (EventBuff eb : evtBuffs) eb.onAction(st, simMs, sk.hasDeal());
                }
                pi++;
                t = st + 1000.0 / (1.0 + curSpd(st, spdWindows, baseSpd, evtBuffs) / 100.0);
            }
        }

        Map<Integer, List<int[]>> ew = new HashMap<>();
        for (EventBuff eb : evtBuffs) ew.put(eb.idx, eb.windows);
        return new Schedule(out, ew);
    }

    /** 자동 평타 채움 없이 배치표에 놓은 액션만 시전(평타도 직접 배치 가능). 액션 간 최소 한 슬롯(공속) 간격 보장. */
    private Schedule placeManualExact(List<ManualCast> casts, int simMs) {
        List<ManualCast> pending = new ArrayList<>(casts);
        pending.sort(Comparator.comparingInt(ManualCast::timeMs));

        SkillIR basic = pickBasic();
        List<EventBuff> evtBuffs = buildEventBuffs();
        Map<Integer, Integer> nextReady = new HashMap<>();
        List<double[]> spdWindows = new ArrayList<>();
        double baseSpd = preset.atkSpeed();

        List<Action> out = new ArrayList<>();
        int cursor = 0;   // 직전 액션 다음 시전 가능 시각(최소 한 슬롯 뒤)
        for (ManualCast c : pending) {
            SkillIR sk = book.skill(c.skillIdx());
            if (sk == null) continue;
            boolean basicCast = "basicAttack".equals(sk.trigger());
            int st = Math.max(c.timeMs(), cursor);   // ★핀=강제 사용: 유저 시각 그대로(쿨/지속 지연 없음). 프론트가 쿨 이전 배치를 막으므로 항상 유효. 기존 설치/버프는 덮어씀.
            if (st >= simMs) break;
            if (basicCast) {
                out.add(Action.basic(st, basic));
                for (EventBuff eb : evtBuffs) eb.onAction(st, simMs, true);
            } else {
                out.add(Action.cast(st, sk));
                nextReady.put(sk.idx(), st + (int) Math.round(reuseIntervalMs(sk)));
                double spdPct = atkSpeedBuffPct(sk);
                if (spdPct > 0) addSpdWindow(spdWindows, st, effDurationMs(sk), spdPct, sk.idx());
                for (EventBuff eb : evtBuffs) eb.onAction(st, simMs, sk.hasDeal());
            }
            double slot = 1000.0 / (1.0 + curSpd(st, spdWindows, baseSpd, evtBuffs) / 100.0);
            cursor = st + (int) Math.round(slot);
        }

        Map<Integer, List<int[]>> ew = new HashMap<>();
        for (EventBuff eb : evtBuffs) ew.put(eb.idx, eb.windows);
        return new Schedule(out, ew);
    }

    /** 시각 ti의 현재 공속(%) = base + 시전형 공속윈도우 + 이벤트형 공속. */
    private double curSpd(int ti, List<double[]> spdWindows, double baseSpd, List<EventBuff> evtBuffs) {
        double spd = baseSpd + activeAtkSpeed(spdWindows, ti);
        for (EventBuff eb : evtBuffs) spd += eb.spdAt(ti);
        return spd;
    }

    /** 활성 액티브 스킬의 실효 쿨다운(ms) — 유물·버프·마스터리 반영본. 프론트 배치 자동시각 계산용. */
    public java.util.Map<Integer, Integer> effectiveCooldowns() {
        java.util.Map<Integer, Integer> out = new HashMap<>();
        for (SkillIR s : book.skills()) {
            if (!"active".equals(s.trigger()) || s.requireLevel() > charLv || s.cooldownMs() == null) continue;
            out.put(s.idx(), (int) Math.round(effCooldownMs(s)));
        }
        return out;
    }

    /** 기본 1액션 슬롯(ms) — 유물 공속(헬레나 등)까지 반영된 실효값. */
    public int baseSlotMs() {
        return (int) Math.round(1000.0 / (1.0 + preset.atkSpeed() / 100.0));
    }

    /**
     * 이벤트 누산 버프를 수집. (1) onActiveSkill+count(아드) — 모든 액션 트리거. (2) onAttack+chance+상태지속(베놈)
     * — 딜 액션마다 확률 누산해 실제 dot 윈도우 생성. 공속 있으면 스케줄 반영, 없어도 윈도우는 출력.
     */
    private List<EventBuff> buildEventBuffs() {
        List<EventBuff> out = new ArrayList<>();
        for (SkillIR s : book.skills()) {
            if (s.requireLevel() > charLv) continue;
            // onActiveSkill+count(아드)만 스케줄러 담당(공속 되먹임). onAttack(베놈)은 DealExpander가
            // 비-dot 딜 인스턴스 스트림 위에서 계산(마크·다크플레어틱·프록 cascade 반영).
            if ("onActiveSkill".equals(s.trigger()) && s.count() != null && s.count() > 0) {
                out.add(new EventBuff(s.idx(), false, s.count(), 0, (int) effDurationMs(s), atkSpeedBuffPct(s)));
            }
        }
        return out;
    }

    /** deal 효과에 durationMs가 있으면(도트=상태 지속) true. 베놈처럼 상태를 남기는 확률 프록 판별. */
    private static boolean hasStateDuration(SkillIR s) {
        for (EffectIR e : s.effects())
            if ("deal".equals(e.role()) && e.durationMs() != null) return true;
        return false;
    }

    /** 프록 실효 확률(‰) = 기본 chance + Σ probability 마스터리(합, %p). (베놈 500 + 확률마스터리 100 = 600) */
    private int effChancePermille(SkillIR s) {
        int c = s.chance() == null ? 0 : s.chance();
        for (MasteryIR m : book.masteries()) {
            if (m.lv() > charLv || m.skill() == null || m.skill() != s.idx()) continue;
            if ("probability".equals(m.type()) && m.value() != null) c += (int) Math.round(m.value());
        }
        return c;
    }

    // ── 헬퍼 ───────────────────────────────────────────

    /** 최고차수 평타(basicAttack) 스킬. */
    private SkillIR pickBasic() {
        SkillIR best = null;
        for (SkillIR s : book.skills()) {
            if (!"basicAttack".equals(s.trigger())) continue;
            if (s.requireLevel() > charLv) continue;
            if (best == null || s.requireLevel() > best.requireLevel()) best = s;
        }
        if (best == null) throw new IllegalStateException("평타 스킬 없음");
        return best;
    }

    /**
     * 시전 가능한 액티브(trigger=active, 해금, 전투기여) — 우선순위(버프 먼저, idx 내림차순) 정렬.
     * <p>role에 buff/deal/debuff가 하나도 없는 <b>utility 단독</b> 스킬(이동기 등)은 자동선택 제외.
     */
    private List<SkillIR> castableActives() {
        List<SkillIR> list = new ArrayList<>();
        for (SkillIR s : book.skills()) {
            if (!"active".equals(s.trigger())) continue;
            if (s.requireLevel() > charLv) continue;
            if (!isCombatRelevant(s)) continue;   // utility 단독 제외
            if (disabled.contains(s.idx())) continue;   // 사용 해제된 스킬 제외(자동·자동채움)
            list.add(s);
        }
        list.sort(Comparator
                .comparingInt((SkillIR s) -> s.hasDeal() ? 1 : 0)   // 버프/디버프(0) 먼저
                .thenComparing(Comparator.comparingInt(SkillIR::idx).reversed())); // idx 내림차순
        return list;
    }

    /** buff/deal/debuff 역할이 하나라도 있으면 전투기여 스킬. */
    private static boolean isCombatRelevant(SkillIR s) {
        if (s.hasDeal()) return true;
        for (String r : s.role()) {
            if ("buff".equals(r) || "deal".equals(r) || "debuff".equals(r)) return true;
        }
        return false;
    }

    /** 시점 ti에 활성인 공속버프 합(%). */
    private double activeAtkSpeed(List<double[]> windows, int ti) {
        double sum = 0;
        for (double[] w : windows) if (w[0] <= ti && ti < w[1]) sum += w[2];
        return sum;
    }

    /** 스킬의 공속 버프%(선반영 아닌 것만). 없으면 0. */
    private double atkSpeedBuffPct(SkillIR s) {
        for (EffectIR e : s.effects()) {
            if ("statUp".equals(e.role()) && "AttackSpeed".equals(e.stat())
                    && !Boolean.TRUE.equals(e.preReflected()) && e.value() != null) {
                return e.value() / 10.0;   // 천분율 → % (150 = 15)
            }
        }
        return 0;
    }

    /** 버프의 실효 지속(ms) = (기본지속 + flat) × (1 + Σ지속증가 + 벞지). 기본지속 = 효과 durationMs 최대.
     *  벞지(BUFF_DURATION)는 '내게 걸리는 버프'에만 — 설치기(summon)·디버프전용은 제외(아드레날린·공속버프는 받음). */
    private double effDurationMs(SkillIR s) {
        double base = 0;
        for (EffectIR e : s.effects()) if (e.durationMs() != null) base = Math.max(base, e.durationMs());
        boolean selfBuff = false;
        for (EffectIR e : s.effects()) if ("statUp".equals(e.role())) { selfBuff = true; break; }
        double buffDurPct = (!s.summon() && selfBuff) ? preset.get("BUFF_DURATION") / 100.0 : 0.0;
        return (base + durFlatMs.getOrDefault(s.idx(), 0.0)) * (1 + durBoost.getOrDefault(s.idx(), 0.0) + buffDurPct);
    }

    /**
     * 자동 배치 재사용 간격(ms). ★설치기(summon)·자기버프(statUp)는 "지속이 끝나야 재사용" → max(쿨, 지속).
     * 디버프(적 대상)·딜 전용은 쿨 그대로(쿨 돌면 재사용). 복합 스킬은 설치/버프 성격이 하나라도 있으면 규칙 적용.
     * ★적용 대상 = "자동으로 채택된 스킬"(placeAuto + 수동 스킬자동채움 — 자동 스킬 선택 로직을 그대로 쓴 것).
     *   유저가 직접 찍은 핀 = 강제 사용이라 이 간격을 무시(기존 설치/버프를 지우고 즉시 재발동).
     */
    /** 공속버프 창 추가 — 같은 스킬이 재발동하면 이전 창을 그 시각에 잘라낸다(강제 재설치=덮어씀). curSpd가 합산이라 겹치면 이중이 되므로 필수. */
    private static void addSpdWindow(List<double[]> spdWindows, int st, double dur, double pct, int idx) {
        for (double[] w : spdWindows) if ((int) w[3] == idx && w[1] > st) w[1] = st;
        spdWindows.add(new double[]{st, st + dur, pct, idx});
    }

    private double reuseIntervalMs(SkillIR s) {
        double cd = effCooldownMs(s);
        boolean selfHold = s.summon();                                   // 설치기
        if (!selfHold) for (EffectIR e : s.effects()) if ("statUp".equals(e.role())) { selfHold = true; break; }   // 자기버프(내게 걸림)
        return selfHold ? Math.max(cd, effDurationMs(s)) : cd;
    }

    /**
     * 실효 쿨다운(ms). ★계산 순서: 스킬 마스터리 쿨감(스킬 고유)을 <b>먼저</b> 적용해 이 스킬의 base 쿨타임을 재정의하고,
     * 그 base에 스탯 쿨감%/쿨감초를 적용한다. (마스터리+스탯을 합산해 한 번에 체감하면 특히 쿨감%에서 오차 — 인게임은 순차)
     * 예) 다크플레어 raw 20s, 마스터리 30%, 스탯 50% → 20×0.7=14 → 14×0.5=7s (합산이면 20×0.2=4s로 틀림).
     */
    private double effCooldownMs(SkillIR s) {
        if (s.cooldownMs() == null) return 0;
        double rawSec = s.cooldownMs() / 1000.0;
        // 1) 마스터리 쿨감(스킬 고유) → base 쿨타임. %는 단순 감소, 초는 그대로 차감(스킬 자체 값이므로 하한/특수규칙 없음).
        double mPct = cdrPct.getOrDefault(s.idx(), 0.0);
        double mFlat = cdrFlatSec.getOrDefault(s.idx(), 0.0);
        double baseSec = Math.max(0, rawSec * (1 - Math.min(mPct, 100.0) / 100.0) - mFlat);
        // 2) 그 base에 스탯 쿨감%(체감)·쿨감초(7초 특수규칙)·하한 4초 적용.
        double cdSec = Cooldown.cooldown(baseSec, preset.get("COOLDOWN_REDUCTION_PCT"), preset.get("COOLDOWN_REDUCTION"));
        return cdSec * 1000.0;
    }
}
