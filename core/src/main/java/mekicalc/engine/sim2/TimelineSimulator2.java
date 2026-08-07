package mekicalc.engine.sim2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import mekicalc.engine.DamageModel;
import mekicalc.model.StatBlock;

/**
 * 재설계 시뮬레이터(sim2) — 완전 이벤트 기반·결정론.
 *
 * <p>기존 엔진과의 구조 차이(직업간 편차 버그의 근본 수정):
 * <ul>
 *   <li>proc(ON_ATTACK/ON_DAMAGE/ON_ACTIVE_SKILL)의 딜·버프를 정적 가동률(min(1,freq×dur))로
 *       사전 주입하지 않는다. 실제 타격/사용 이벤트에 {@link ProcGate}(결정론 누적)로 발동시키고,
 *       버프는 실지속 구간으로 타임라인에 깔린다 → 가동률은 근사값이 아니라 관측값이 된다.</li>
 *   <li>"N회마다"(counterEvery + counterOn 소스)·State 게이팅(requireState)·상호배타 그룹
 *       (exclusiveGroup)·스택(stackState)을 이벤트 상태 머신으로 정확히 처리한다.</li>
 *   <li>cd=0 액티브는 발동 불능으로 조용히 죽지 않고 gcd 간격 재발동 + {@link Warn} 기록.</li>
 *   <li>동시각 이벤트는 seq로 전순서화(PriorityQueue 동점 비결정성 제거).</li>
 *   <li>ON_ATTACK proc은 "기본 공격 시"(홀리 차지 원문) — 평타 태그 타격에만 후킹한다. proc으로
 *       발생한 타격은 기본적으로 proc을 재귀 트리거하지 않는다(SimConfig로 제어).</li>
 * </ul>
 *
 * <p>데미지 1타는 검증된 넥슨 공식({@link DamageModel#globalMultiplier} + 기존 hitDamage 구조)을 그대로 쓴다.
 */
public final class TimelineSimulator2 {

    private static final String ATK_SPEED = "ATK_SPEED";
    private static final String DEF_PEN = "DEF_PEN";

    private final DamageModel dmg;

    public TimelineSimulator2(DamageModel dmg) {
        this.dmg = dmg;
    }

    public Sim2Result run(List<SkillPlan2> plans, StatBlock stats, Content2 content, SimConfig cfg) {
        return new Runner(plans, stats, content, cfg).run();
    }

    private enum EvType { CAST, HIT, HIT_TAKEN }

    /** (time, seq) 전순서 이벤트 — 동시각 처리 순서 결정론 보장. */
    private record Ev(double t, long seq, EvType type, SkillPlan2 plan, boolean fromProc)
            implements Comparable<Ev> {
        @Override public int compareTo(Ev o) {
            int c = Double.compare(t, o.t);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    /** 1회 실행 상태(이벤트 큐·버프·State·게이트)의 소유자. */
    private final class Runner {
        final List<SkillPlan2> plans;
        final StatBlock stats;
        final Content2 content;
        final SimConfig cfg;
        final double T;

        final PriorityQueue<Ev> q = new PriorityQueue<>();
        long seq = 0;
        final BuffBook book = new BuffBook();
        final StateStore states = new StateStore();
        final List<Warn> warns = new ArrayList<>();
        final Set<String> warned = new HashSet<>();
        final Map<String, Double> per = new LinkedHashMap<>();
        double total = 0;

        final List<BuffSpec2> stackSpecs = new ArrayList<>();
        final Map<SkillPlan2, ProcGate> gates = new HashMap<>();
        final Map<String, Integer> rr = new HashMap<>();
        /** 훅 소스별 proc 목록. counterOn이 소스를 재지정할 수 있다(디바인 저지먼트=basicAttack). */
        final List<SkillPlan2> hookBasic = new ArrayList<>();
        final List<SkillPlan2> hookActive = new ArrayList<>();
        final List<SkillPlan2> hookTaken = new ArrayList<>();

        Runner(List<SkillPlan2> plans, StatBlock stats, Content2 content, SimConfig cfg) {
            this.plans = plans;
            this.stats = stats;
            this.content = content;
            this.cfg = cfg;
            this.T = cfg.durationSec();
        }

        Sim2Result run() {
            for (SkillPlan2 p : plans)
                for (BuffSpec2 bs : p.grants())
                    if (bs.stackState() != null) stackSpecs.add(bs);

            for (SkillPlan2 p : plans) init(p);

            if (!hookTaken.isEmpty()) {
                warns.add(new Warn("HITS_TAKEN_ASSUMED", "-",
                        "피격 빈도를 " + cfg.hitsTakenPerSec() + "회/초로 가정(ON_DAMAGE proc). SimConfig에서 조정."));
                for (double t = 0; t <= T; t += 1.0 / cfg.hitsTakenPerSec())
                    q.add(new Ev(t, seq++, EvType.HIT_TAKEN, null, false));
            }

            while (!q.isEmpty()) {
                Ev e = q.poll();
                if (e.t() > T) break;
                states.expireAll(e.t());
                switch (e.type()) {
                    case CAST -> onCast(e);
                    case HIT -> onHit(e);
                    case HIT_TAKEN -> { for (SkillPlan2 p : hookTaken) tryProc(p, e.t()); }
                }
            }

            Map<String, Sim2Result.BuffStat> report = new LinkedHashMap<>(book.report(T));
            for (Map.Entry<String, double[]> e : states.report(T).entrySet())
                report.put("[state]" + e.getKey(),
                        new Sim2Result.BuffStat(e.getValue()[1], e.getValue()[0], "stacks(avg)"));
            return new Sim2Result(total, T > 0 ? total / T : 0, per, report, warns);
        }

        void init(SkillPlan2 p) {
            Trigger2 tr = p.trigger();
            switch (tr.kind()) {
                case PASSIVE -> grantAll(p, 0);
                case ACTIVE, ON_TIMER -> {
                    double[] sched = cfg.schedule().get(p.id());
                    if (sched != null) {
                        for (double st : sched) if (st <= T) q.add(new Ev(st, seq++, EvType.CAST, p, false));
                    } else {
                        q.add(new Ev(0, seq++, EvType.CAST, p, false));
                    }
                }
                case ON_ATTACK, ON_ACTIVE_SKILL, ON_DAMAGE -> {
                    gates.put(p, new ProcGate(tr.procPct(), p.cooldownSec(), p.id().hashCode() * 0x100000001B3L));
                    String src = tr.counterOn() != null ? tr.counterOn()
                            : switch (tr.kind()) {
                                case ON_ATTACK -> "basicAttack";
                                case ON_ACTIVE_SKILL -> "activeSkill";
                                default -> "hitTaken";
                            };
                    switch (src) {
                        case "basicAttack" -> hookBasic.add(p);
                        case "activeSkill" -> hookActive.add(p);
                        default -> hookTaken.add(p);
                    }
                }
                case ON_STATE -> {
                    String rs = tr.requireStateName();
                    if (rs == null) {
                        warns.add(new Warn("ON_STATE_UNBOUND", p.name(),
                                "OnState 발동인데 조건 State(requireState)가 데이터에 없음 → 미발동 처리(과소 방향). "
                                + "파이프라인에서 TriggerCondition 추출 필요."));
                    } else {
                        // "State 지속 동안" 의미론: State 부여·갱신마다 함께 발동(연동 버프 구간 일치).
                        states.onGrant(rs, t -> fireSkill(p, t));
                    }
                }
            }
        }

        void onCast(Ev e) {
            SkillPlan2 p = e.plan();
            Trigger2 tr = p.trigger();
            boolean gatedOff = tr.requireState() != null
                    && states.stacks(tr.requireStateName(), e.t()) < tr.requireStacks();
            if (!gatedOff) {
                if (tr.requireState() != null && tr.consumeRequire())
                    states.consume(tr.requireStateName(), 0, e.t());
                grantAll(p, e.t());
                if (p.cast() != null) scheduleHits(p, e.t(), false);
                if (p.kind() == Kind2.ACTIVE && tr.kind() == TriggerKind2.ACTIVE)
                    for (SkillPlan2 hp : hookActive) tryProc(hp, e.t());
            }
            if (!cfg.schedule().containsKey(p.id())) {
                double iv = interval(p, effective(e.t()));
                if (iv > 0 && e.t() + iv <= T) q.add(new Ev(e.t() + iv, seq++, EvType.CAST, p, false));
            }
        }

        void onHit(Ev e) {
            SkillPlan2 p = e.plan();
            double d = hitDamage(p.cast(), effective(e.t()), content,
                    book.sumAt("RECV_AMP", e.t(), Target2.ENEMY));
            total += d;
            per.merge(p.name(), d, Double::sum);
            boolean isBasic = p.cast().tag() == DmgTag2.BASIC;
            if (isBasic && (!e.fromProc() || cfg.procHitsTriggerProcs()))
                for (SkillPlan2 hp : hookBasic) tryProc(hp, e.t());
        }

        /** proc 판정 순서: requireState → counterEvery → 확률 게이트. 통과 시 실발동. */
        void tryProc(SkillPlan2 p, double t) {
            Trigger2 tr = p.trigger();
            if (tr.requireState() != null
                    && states.stacks(tr.requireStateName(), t) < tr.requireStacks()) return;
            if (tr.counterEvery() > 0 && !states.count("cnt:" + p.id(), tr.counterEvery())) return;
            ProcGate g = gates.get(p);
            if (g != null && !g.fire(t)) return;
            if (tr.requireState() != null && tr.consumeRequire())
                states.consume(tr.requireStateName(), 0, t);
            fireSkill(p, t);
        }

        /** 스킬 실발동(버프 실지속 부여 + 딜 즉시). proc·ON_STATE 공용. */
        void fireSkill(SkillPlan2 p, double t) {
            grantAll(p, t);
            if (p.cast() != null) scheduleHits(p, t, true);
        }

        /** grants 적용 — 배타 그룹은 결정론 라운드로빈으로 1개 선택(장기 평균 = 균등 기댓값). */
        void grantAll(SkillPlan2 p, double t) {
            Map<String, List<BuffSpec2>> groups = new LinkedHashMap<>();
            for (BuffSpec2 bs : p.grants()) {
                if (bs.exclusiveGroup() == null) book.grant(bs, t, states);
                else groups.computeIfAbsent(bs.exclusiveGroup(), k -> new ArrayList<>()).add(bs);
            }
            for (Map.Entry<String, List<BuffSpec2>> g : groups.entrySet()) {
                List<BuffSpec2> list = g.getValue();
                int i = rr.merge(p.id() + "#" + g.getKey(), 1, Integer::sum) - 1;
                book.grant(list.get(i % list.size()), t, states);
            }
        }

        /** 타격 이벤트 예약(즉발 1회 또는 dot 틱들). */
        void scheduleHits(SkillPlan2 p, double t, boolean fromProc) {
            Dot2 d = p.cast().dot();
            if (d == null) {
                q.add(new Ev(t, seq++, EvType.HIT, p, fromProc));
            } else {
                double end = Math.min(t + d.durationSec(), T);
                for (double tt = t; tt < end; tt += d.tickSec())
                    q.add(new Ev(tt, seq++, EvType.HIT, p, fromProc));
            }
        }

        /** 재발동 간격. cd=0 액티브는 gcd 가정(+Warn 1회). */
        double interval(SkillPlan2 p, StatBlock st) {
            double as = st.get(ATK_SPEED);
            return switch (p.trigger().kind()) {
                case ACTIVE -> {
                    if (p.kind() == Kind2.BASIC) yield 1.0 / (1 + as / 100.0);
                    if (p.cooldownSec() > 0) yield p.cooldownSec();
                    if (warned.add("CD0:" + p.id()))
                        warns.add(new Warn("CD0_ACTIVE_GCD", p.name(),
                                "쿨 0 액티브 → " + cfg.gcdSec() + "초 간격 재사용 가정(실게임 시전 간격 확인 필요)."));
                    yield cfg.gcdSec();
                }
                case ON_TIMER -> {
                    double pd = p.trigger().periodSec();
                    yield p.trigger().fastByAtkSpeed() ? pd / (1 + as / 100.0) : pd;
                }
                default -> 0;
            };
        }

        StatBlock effective(double t) {
            return book.effective(stats, t, states, stackSpecs, dmg.stats(), Target2.PLAYER);
        }
    }

    /**
     * 1타 데미지 — 검증된 넥슨 공식(기존 hitDamage와 동일 구조).
     * 공통배수 × (타입뎀+피해마스터리) × 타수 × 방어배수 × 받피증감 × 스킬공퍼 × 상태이상
     * → 콘텐츠 보정 → 레벨보정 × 계수 × 대상수.
     */
    private double hitDamage(Cast2 c, StatBlock st, Content2 content, double debuffRecvAmp) {
        if ((content.boss() && c.bossBonus() != 0) || (!content.boss() && c.normalBonus() != 0)) {
            st = new StatBlock(st);
            if (content.boss()) st.set("BOSS_DMG", st.get("BOSS_DMG") + c.bossBonus());
            else st.set("NORMAL_DMG", st.get("NORMAL_DMG") + c.normalBonus());
        }
        double g = dmg.globalMultiplier(st, content.boss());
        double tf = ((c.tag() == DmgTag2.BASIC) ? dmg.basicAtkDmgFactor(st) : dmg.skillDmgFactor(st))
                + c.dmgMasteryPct() / 100.0;
        double defPen = Math.min(st.get(DEF_PEN), 100) / 100.0;
        double defFactor = 5000.0 / (content.enemyDefense() * (1 - defPen) + 6000);
        double recv = (1 + (content.enemyRecvAmp() + debuffRecvAmp) / 100.0)
                * (1 - Math.min(content.enemyRecvReduce(), 95) / 100.0);
        double pre = g * tf * c.hitCount() * defFactor * recv;
        pre *= (1 + st.get("SKILL_ATK_PCT") / 100.0);
        if (content.targetInCc()) pre *= (1 + st.get("STATUS_DMG") / 100.0);
        double adjusted = switch (content.sqrtAdjust()) {
            case 1 -> Math.sqrt(pre);
            case 2 -> Math.cbrt(pre);
            default -> pre;
        };
        int effTargets = Math.max(1, Math.min(c.targetCount(), content.maxTargets()));
        return adjusted * content.levelPenalty() * c.coeffFrac() * effTargets;
    }
}
