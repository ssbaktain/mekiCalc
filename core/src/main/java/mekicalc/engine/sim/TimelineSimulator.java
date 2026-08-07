package mekicalc.engine.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import mekicalc.engine.DamageModel;
import mekicalc.model.StatBlock;
import mekicalc.model.StatDef;

/**
 * 이벤트 기반 타임라인 스케줄러(재설계 ④). SkillPlan들을 발동방식별로 시간축에 깔고,
 * "다음 이벤트 시각으로 점프"하며 전투시간 동안의 총 데미지·DPS를 낸다.
 *
 * <p>데미지는 검증된 넥슨 공식 {@link DamageModel#globalMultiplier}를 재사용한다.
 * 매 HIT에서 그 시각의 활성 플레이어 버프를 스냅샷해 유효 스탯을 만든다.
 *
 * <p>현 단계(뼈대): ACTIVE(쿨)·BASIC(공속)·ON_TIMER(주기)·PASSIVE(상시버프) + dot tick + 버프 스냅샷.
 * TODO: proc(ON_ATTACK/ON_DAMAGE) 가동률, 적 디버프(ENEMY)·컨텐츠 보정·대상 수(광역)·동료.
 */
public final class TimelineSimulator {

    private static final String ATK_SPEED = "ATK_SPEED";
    private static final String DEF_PEN = "DEF_PEN";

    private final DamageModel dmg;

    public TimelineSimulator(DamageModel dmg) {
        this.dmg = dmg;
    }

    /** 피격 빈도 근사(초당). ON_DAMAGE proc용 — 내부쿨 있으면 대개 쿨이 지배해 값에 둔감. TODO 정밀화. */
    private static final double HITS_TAKEN_PER_SEC = 2.0;

    /** 본체만(동료 없음). */
    public SimResult run(List<SkillPlan> plans, StatBlock stats, ContentProfile content, double durationSec) {
        return run(plans, List.of(), stats, content, durationSec);
    }

    /** 본체 + 동료 통합 시뮬. 본체/동료는 {@link Actor}로 구분(동료는 소환 시 계승 스탯 고정). */
    public SimResult run(List<SkillPlan> plans, List<SummonPlan> summons, StatBlock stats,
                         ContentProfile content, double durationSec) {
        boolean boss = isBoss(content.type());
        PriorityQueue<TimelineEvent> q = new PriorityQueue<>();
        List<BuffInstance> buffs = new ArrayList<>();        // 플레이어 버프
        List<BuffInstance> compBuffs = new ArrayList<>();    // 동료 자버프
        List<BuffInstance> enemyDebuffs = new ArrayList<>(); // 적 디버프(공유)
        List<Actor> procs = new ArrayList<>();
        for (SkillPlan p : plans) {
            Actor a = new Actor(p, false, null, Double.POSITIVE_INFINITY);
            if (isProc(p.trigger().kind())) procs.add(a);
            else q.add(new TimelineEvent(0, EventType.CAST, a));
        }
        // proc 스킬(ON_ATTACK/ON_DAMAGE/ON_ACTIVE_SKILL)의 버프/디버프 grants는 CAST 이벤트가 없어 누락된다.
        // 가동률(freq×dur, 순환 방지 위해 입력 스탯·쿨 기준)로 사전 추정해 상시 반영: 곱연산 버프의 시간평균
        // dps = 1+uptime×값 이므로 값×uptime을 상시 적용하면 단일 버프엔 정확(기존 buffed() 가동률평균과 동형).
        applyProcGrants(plans, stats, durationSec, buffs, compBuffs, enemyDebuffs);
        for (SummonPlan sp : summons) q.add(new TimelineEvent(sp.atSec(), EventType.SUMMON, sp));

        double total = 0;
        Map<String, Double> per = new LinkedHashMap<>();
        int attackHits = 0, activeCasts = 0;

        while (!q.isEmpty()) {
            TimelineEvent e = q.poll();
            if (e.time() > durationSec) break;
            switch (e.type()) {
                case SUMMON -> {
                    // 소환 순간 본체 버프 스냅샷 × 계승 → 동료 스탯 고정. 소환 창 내 동료 스킬 발동.
                    SummonPlan sp = (SummonPlan) e.payload();
                    double sEnd = Math.min(e.time() + sp.durSec(), durationSec);
                    StatBlock cbase = companionStat(stats, buffs, e.time(), sp.inheritRatioPct());
                    for (SkillPlan cs : sp.skills()) {
                        Actor a = new Actor(cs, true, cbase, sEnd);
                        if (isProc(cs.trigger().kind())) procs.add(a);
                        else q.add(new TimelineEvent(e.time(), EventType.CAST, a));
                    }
                    double next = e.time() + sp.resummonCdSec();   // 재소환 주기
                    if (sp.resummonCdSec() > 0 && next < durationSec) q.add(new TimelineEvent(next, EventType.SUMMON, sp));
                }
                case CAST -> {
                    Actor a = (Actor) e.payload();
                    SkillPlan p = a.plan();
                    if (p.cast() != null) activeCasts++;
                    for (BuffSpec bs : p.grants()) {
                        List<BuffInstance> tl = switch (bs.target()) {
                            case ENEMY -> enemyDebuffs;
                            case COMPANION -> compBuffs;
                            case PLAYER -> buffs;
                        };
                        double end = Double.isInfinite(bs.durationSec())
                                ? Double.POSITIVE_INFINITY : e.time() + bs.durationSec();
                        tl.removeIf(bi -> bi.spec() == bs);   // 재발동 = 갱신
                        tl.add(new BuffInstance(bs, e.time(), end, bs.target()));
                    }
                    Cast c = p.cast();
                    if (c != null) {
                        if (c.dot() != null) {
                            double endT = Math.min(e.time() + c.dot().durationSec(), durationSec);
                            for (double t = e.time(); t < endT; t += c.dot().tickSec())
                                q.add(new TimelineEvent(t, EventType.HIT, a));
                        } else {
                            q.add(new TimelineEvent(e.time(), EventType.HIT, a));
                        }
                    }
                    // 본체 스킬만 쿨/공속 재발동(동료는 소환창 내 발동 — TODO). 동료 baseStat은 고정.
                    StatBlock ivBase = a.companion() ? a.base() : stats;
                    List<BuffInstance> ivBuffs = a.companion() ? compBuffs : buffs;
                    double iv = interval(p, applyBuffs(ivBase, ivBuffs, e.time()));
                    double nextT = e.time() + iv;
                    // 본체는 무제한 재발동, 동료는 소환 창 내에서만.
                    if (iv > 0 && (!a.companion() || nextT < a.summonEnd()))
                        q.add(new TimelineEvent(nextT, EventType.CAST, a));
                }
                case HIT -> {
                    Actor a = (Actor) e.payload();
                    StatBlock base = a.companion() ? a.base() : stats;
                    StatBlock eff = applyBuffs(base, a.companion() ? compBuffs : buffs, e.time());
                    double d = hitDamage(a.plan().cast(), eff, boss, content, recvAmpAt(enemyDebuffs, e.time()));
                    total += d;
                    if (!a.companion()) attackHits++;
                    per.merge(a.companion() ? "[동료]" + a.plan().name() : a.plan().name(), d, Double::sum);
                }
                default -> { }
            }
        }

        // proc 스킬(본체·동료): 트리거 빈도 × proc% (내부쿨 캡). 결정론적 기댓값.
        double recvAmpAvg = recvAmpAt(enemyDebuffs, durationSec * 0.5);
        double atkPerSec = attackHits / durationSec;
        double actPerSec = activeCasts / durationSec;
        for (Actor pa : procs) {
            SkillPlan pp = pa.plan();
            if (pp.cast() == null) continue;
            StatBlock base = pa.companion() ? pa.base() : stats;
            StatBlock eff = applyBuffsAvg(base, pa.companion() ? compBuffs : buffs, durationSec);
            double trig = switch (pp.trigger().kind()) {
                case ON_ATTACK -> atkPerSec;
                case ON_ACTIVE_SKILL -> actPerSec;
                case ON_DAMAGE -> HITS_TAKEN_PER_SEC;
                default -> 0;
            };
            double freq = trig * pp.trigger().procPct() / 100.0;
            // proc 내부쿨 = CoolTimeMs(cooldownSec)만(SCHEMA ②). TriggerTimeMs(periodSec)는 OnTimer 전용 → proc에 캡 금지.
            if (pp.cooldownSec() > 0) freq = Math.min(freq, 1.0 / pp.cooldownSec());
            double perHit = hitDamage(pp.cast(), eff, boss, content, recvAmpAvg);
            Dot d = pp.cast().dot();
            double contrib;
            if (d != null && d.tickSec() > 0) {
                double uptime = Math.min(1.0, freq * d.durationSec());
                contrib = perHit / d.tickSec() * uptime * durationSec;
            } else {
                contrib = perHit * freq * durationSec;
            }
            total += contrib;
            per.merge(pa.companion() ? "[동료]" + pp.name() : pp.name(), contrib, Double::sum);
        }
        double dps = durationSec > 0 ? total / durationSec : 0;
        double ttk = (content.enemyHp() > 0 && dps > 0) ? content.enemyHp() / dps : 0;
        // 자버프 가동률 진단: 전투 중반 유효 스탯 스냅샷(딜 관련 주요 stat).
        StatBlock mid = applyBuffs(stats, buffs, durationSec * 0.5);
        Map<String, Double> midStats = new LinkedHashMap<>();
        for (String k : new String[]{"SKILL_ATK_PCT", "FINAL_DMG", "BASIC_ATK_DMG", "SKILL_DMG",
                "CRIT_DMG", "BOSS_DMG", "NORMAL_DMG", "ATK_PCT", "STAT_PROP_DMG"})
            midStats.put(k, mid.get(k));
        return new SimResult(total, dps, per, ttk, midStats);
    }

    /** 이벤트 주체: 본체(companion=false, summonEnd=∞) 또는 동료(companion=true, base=계승 스탯, summonEnd=소환창 종료). */
    private record Actor(SkillPlan plan, boolean companion, StatBlock base, double summonEnd) {}

    /** 동료 스탯 = 소환 시점 본체 버프 스냅샷 계승(공격력만 계승비율, 그 외 전부). */
    private StatBlock companionStat(StatBlock playerBase, List<BuffInstance> buffs, double t, double inheritPct) {
        StatBlock snap = applyBuffs(playerBase, buffs, t);
        StatBlock c = new StatBlock(snap);
        double atk = snap.get("ATK_FLAT") * (1 + snap.get("ATK_PCT") / 100.0) * inheritPct / 100.0;
        c.set("ATK_FLAT", atk);
        c.set("ATK_PCT", 0);   // 계승 공격력에 이미 반영
        return c;
    }

    private static boolean isProc(TriggerKind k) {
        return k == TriggerKind.ON_ATTACK || k == TriggerKind.ON_DAMAGE || k == TriggerKind.ON_ACTIVE_SKILL;
    }

    /**
     * proc 스킬(ON_ATTACK/ON_DAMAGE/ON_ACTIVE_SKILL)의 버프·디버프 grants를 가동률 반영해 상시로 사전 적용.
     * 빈도는 입력 스탯·쿨 기준(순환 방지). 가동률 uptime=min(1, freq×dur), 효과값×uptime을 상시 버프로 둔다.
     */
    private void applyProcGrants(List<SkillPlan> plans, StatBlock stats, double durationSec,
            List<BuffInstance> buffs, List<BuffInstance> compBuffs, List<BuffInstance> enemyDebuffs) {
        double atkEst = 1 + stats.get(ATK_SPEED) / 100.0;   // 평타 초당(공속)
        double actEst = 0;                                   // 액티브 발동 초당(쿨 합)
        for (SkillPlan p : plans)
            if (p.cast() != null && !isProc(p.trigger().kind()) && p.kind() == Kind.ACTIVE && p.cooldownSec() > 0)
                actEst += 1.0 / p.cooldownSec();
        for (SkillPlan p : plans) {
            if (!isProc(p.trigger().kind()) || p.grants().isEmpty()) continue;
            double trig = switch (p.trigger().kind()) {
                case ON_ACTIVE_SKILL -> actEst;
                case ON_ATTACK -> atkEst;
                case ON_DAMAGE -> HITS_TAKEN_PER_SEC;
                default -> 0;
            };
            double freq = trig * p.trigger().procPct() / 100.0;
            if (p.cooldownSec() > 0) freq = Math.min(freq, 1.0 / p.cooldownSec());   // proc 내부쿨=CoolTimeMs만(SCHEMA ②)
            for (BuffSpec bs : p.grants()) {
                double dur = Double.isInfinite(bs.durationSec()) ? durationSec : bs.durationSec();
                double uptime = Math.min(1.0, freq * dur);
                if (uptime <= 0) continue;
                Map<String, Double> scaled = new LinkedHashMap<>();
                bs.effects().forEach((k, v) -> scaled.put(k, v * uptime));
                List<BuffInstance> tl = switch (bs.target()) {
                    case ENEMY -> enemyDebuffs;
                    case COMPANION -> compBuffs;
                    case PLAYER -> buffs;
                };
                tl.add(new BuffInstance(new BuffSpec(bs.target(), scaled, Double.POSITIVE_INFINITY, bs.combine(), bs.stack()),
                        0, Double.POSITIVE_INFINITY, bs.target()));
            }
        }
    }

    /** proc 딜용 평균 유효 스탯(근사) — 전투 중반 시점 스냅샷(정상상태 근사). TODO: 다시점 표본 평균. */
    private StatBlock applyBuffsAvg(StatBlock base, List<BuffInstance> buffs, double durationSec) {
        return applyBuffs(base, buffs, durationSec * 0.5);
    }

    /** 시각 t의 활성 적 디버프 받피증(RECV_AMP) 합(SUM). Weakness 여러 디버프는 합산. */
    private static double recvAmpAt(List<BuffInstance> enemyDebuffs, double t) {
        double sum = 0;
        for (BuffInstance bi : enemyDebuffs)
            if (bi.activeAt(t)) sum += bi.spec().effects().getOrDefault("RECV_AMP", 0.0);
        return sum;
    }

    /**
     * 1타 데미지(넥슨 공식). 공통배수 × 타입뎀 × 타수 × 방어배수 × 받피증감 → 콘텐츠 보정 → 스킬 계수.
     * 방어 배수·받피·보정은 {@link ContentProfile}에서. 방어력 0·보정 NONE이면 상대지표(방어배수 5000/6000 공통).
     */
    private double hitDamage(Cast c, StatBlock st, boolean boss, ContentProfile content, double debuffRecvAmp) {
        // 스킬 전용 보스/일반몹 데미지 마스터리(statAdd): 컨텐츠에 맞는 stat에 합산 후 공통배수(스킬 한정 → 스탯창 미포함).
        if ((boss && c.bossBonus() != 0) || (!boss && c.normalBonus() != 0)) {
            st = new StatBlock(st);
            if (boss) st.set("BOSS_DMG", st.get("BOSS_DMG") + c.bossBonus());
            else st.set("NORMAL_DMG", st.get("NORMAL_DMG") + c.normalBonus());
        }
        double g = dmg.globalMultiplier(st, boss);
        // 타입 데미지%(평타=기본공격뎀, 스킬=스킬뎀)에 피해량 마스터리를 합산(넥슨 공식: 별도 곱 아님).
        double tf = ((c.tag() == DmgTag.BASIC) ? dmg.basicAtkDmgFactor(st) : dmg.skillDmgFactor(st)) + c.dmgMasteryPct() / 100.0;
        // 방어 배수: 방관(100% 상한) 적용 후 5000/(방어력+6000).
        double defPen = Math.min(st.get(DEF_PEN), 100) / 100.0;
        double defFactor = 5000.0 / (content.enemyDefense() * (1 - defPen) + 6000);
        // 대상 받는 피해 증가(컨텐츠 고정 + 적 디버프 Weakness)/감소(받피감 95% 상한).
        double recv = (1 + (content.enemyRecvAmp() + debuffRecvAmp) / 100.0) * (1 - Math.min(content.enemyRecvReduce(), 95) / 100.0);
        double pre = g * tf * c.hitCount() * defFactor * recv;
        pre *= (1 + st.get("SKILL_ATK_PCT") / 100.0);   // 스킬 공격력%(숙련된 표창술 등): 장비공퍼와 별도 곱(공식 스킬%배율 단계)
        if (content.targetInCc()) pre *= (1 + st.get("STATUS_DMG") / 100.0);   // 상태이상(빙결/기절/무력화) 대상
        // 콘텐츠 보정(제곱근/세제곱근) — 스킬 계수 직전. PVP 상수/레벨보정 상세는 후속.
        double adjusted = switch (content.adjust()) {
            case SQRT -> Math.sqrt(pre);
            case CBRT -> Math.cbrt(pre);
            default -> pre;
        };
        // 대상 수: 스킬 최대 대상 ∩ 컨텐츠 동시 대상(보스 1, 챕터 사냥 다수).
        int effTargets = Math.max(1, Math.min(c.targetCount(), content.maxTargets()));
        return adjusted * content.levelPenalty() * c.coeffFrac() * effTargets;
    }

    /** 시각 t의 활성 플레이어 버프를 base에 적용한 유효 스탯. */
    private StatBlock applyBuffs(StatBlock base, List<BuffInstance> buffs, double t) {
        StatBlock eff = new StatBlock(base);
        for (BuffInstance bi : buffs) {
            if (bi.owner() != Target.PLAYER || !bi.activeAt(t)) continue;
            for (Map.Entry<String, Double> en : bi.spec().effects().entrySet()) {
                String id = en.getKey();
                StatDef def = dmg.stats().get(id);
                eff.set(id, def != null ? def.merge(eff.get(id), en.getValue()) : eff.get(id) + en.getValue());
            }
        }
        return eff;
    }

    /** 재발동 간격(초). ACTIVE=쿨(평타=공속), ON_TIMER=주기(공속비례), 그 외=0(재발동 없음). */
    private double interval(SkillPlan p, StatBlock st) {
        double as = st.get(ATK_SPEED);
        return switch (p.trigger().kind()) {
            case ACTIVE -> (p.kind() == Kind.BASIC) ? 1.0 / (1 + as / 100.0) : p.cooldownSec();
            case ON_TIMER -> {
                double pd = p.trigger().periodSec();
                yield p.trigger().fastByAtkSpeed() ? pd / (1 + as / 100.0) : pd;
            }
            default -> 0;
        };
    }

    private static boolean isBoss(Content c) {
        return c != Content.CHAPTER_HUNT;   // 사냥만 일반몹, 나머지는 단일(보스/PVP) 취급
    }
}
