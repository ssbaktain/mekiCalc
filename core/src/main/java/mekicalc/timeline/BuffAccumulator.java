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
 * 버프/디버프 누적(3~5단계) — {@link BuffSource} 윈도우를 만들어 {@link Timeline}의 각 슬롯 {@link ModState}에 반영.
 *
 * <p>분류:
 * <ul>
 *   <li><b>후반영 상시 패시브</b>(preReflected=false 또는 mode=post, 지속 없음): 전 슬롯 [0,T). 숙련된표창술·페이크.
 *   <li><b>시한부 액티브 버프</b>(지속 있음): 시전 시각마다 윈도우. 다크사이트·퍼지·아드레날린(상시근사).
 *   <li><b>gatedBy 디버프</b>: 조건 스킬 상태 윈도우. 베놈약화(베놈 도트=연속).
 * </ul>
 * 선반영(preReflected=true)·전역 마스터리는 프리셋에 녹아 있어 건드리지 않는다.
 * 공속/이동속도는 데미지 모디파이어가 아니므로 route에서 무시(공속은 스케줄러 담당).
 */
public final class BuffAccumulator {

    private final SkillBook book;
    private final Preset preset;
    private final int charLv;

    private final Map<Integer, Double> durFlatMs = new HashMap<>(); // skill → +지속(ms)
    private final Map<Integer, Double> durPct = new HashMap<>();     // skill → +지속(분율)

    public BuffAccumulator(SkillBook book, Preset preset) {
        this.book = book;
        this.preset = preset;
        this.charLv = preset.level();
        indexDurationMasteries();
    }

    /** 스킬의 습득 차수 유효레벨(성장 입력). */
    private int effOf(int skillIdx) {
        SkillIR s = book.skill(skillIdx);
        return preset.effectiveSkillLevel(s == null ? 4 : s.tier());
    }

    public void accumulate(Timeline tl, Schedule sch, int simMs) {
        for (BuffSource src : buildSources(sch, simMs)) {
            // 버프가 시뮬 끝(simMs)까지 지속이면 마지막 슬롯(t=simMs)도 포함(end-inclusive).
            // 딜이 정확히 종료 시각에 떨어질 때 상시 패시브/디버프가 빠지는 경계 버그 방지.
            boolean incl = src.endMs >= simMs;
            for (Slot slot : tl.slots().subMap(src.startMs, true, src.endMs, incl).values())
                src.applyTo(slot.mods());
        }
    }

    /** 버프/디버프 소스(윈도우+수정치) 목록 생성. 슬롯 적용 전 단계 — 검증·진단에 그대로 노출. */
    public List<BuffSource> buildSources(Schedule sch, int simMs) {
        List<Action> actions = sch.actions();
        int firstAtk = firstAttackTime(actions, simMs);
        List<BuffSource> sources = new ArrayList<>();

        // 1) 후반영 상시 패시브
        for (SkillIR s : book.skills()) {
            if (s.requireLevel() > charLv || !"passive".equals(s.type()) || !"onStart".equals(s.trigger())) continue;
            BuffSource src = new BuffSource(s.name(), 0, simMs).skill(s.idx());
            boolean any = false;
            for (EffectIR e : s.effects()) {
                if (!"statUp".equals(e.role()) || e.durationMs() != null) continue;
                if (!Boolean.FALSE.equals(e.preReflected())) continue; // 후반영(preReflected:false)만
                any |= route(src, e.stat(), e.value(), e.growthSlot(), e.unit(), effOf(s.idx()));
            }
            if (any) sources.add(src);
        }

        // 2) 시한부 액티브 버프(+ 스킬귀속/gatedBy 마스터리)
        for (SkillIR s : book.skills()) {
            if (s.requireLevel() > charLv || !isTimedBuff(s)) continue;
            for (int[] w : windowsFor(s, actions, firstAtk, simMs, sch.eventWindows())) {
                BuffSource src = new BuffSource(s.name(), w[0], w[1]).skill(s.idx());
                for (EffectIR e : s.effects()) {
                    if ("statUp".equals(e.role()) || "debuff".equals(e.role()))
                        route(src, e.stat(), e.value(), e.growthSlot(), e.unit(), effOf(s.idx()));
                }
                applyMasteries(src, s.idx());
                sources.add(src);
            }
        }

        // 3) gatedBy 디버프 — 게이트가 '시한부 버프'가 아닌 경우(베놈처럼 확률 누산기 상태)
        for (MasteryIR m : book.masteries()) {
            if (m.lv() > charLv || m.gatedBy() == null) continue;
            SkillIR gate = book.skill(m.gatedBy());
            if (gate == null || isTimedBuff(gate)) continue; // 시한부 버프 게이트는 2)에서 처리됨
            List<int[]> gw = sch.eventWindows().get(m.gatedBy());
            if (gw != null) {
                // 확률 누산기 윈도우(베놈)에서만 디버프 on(적용 다음 딜부터 = w0+1).
                // ★빈 리스트 = 확률 미발동 → 약화 없음(폴백 금지). 폴백은 윈도우 자체가 없을(null) 때만.
                for (int[] w : gw) {
                    BuffSource src = new BuffSource(m.masteryName(), Math.min(w[0] + 1, simMs), w[1]).skill(m.gatedBy());
                    routeMastery(src, m);
                    sources.add(src);
                }
            } else {
                // 폴백: 윈도우 계산 자체가 없으면(null) 첫 공격 다음부터 연속(끝단 simMs 클립).
                BuffSource src = new BuffSource(m.masteryName(), Math.min(firstAtk + 1, simMs), simMs).skill(m.gatedBy());
                routeMastery(src, m);
                sources.add(src);
            }
        }
        return sources;
    }

    // ── 윈도우/분류 ─────────────────────────────────────

    /** 데미지 모디파이어(또는 공속=공격빈도) 지속효과가 있는 액티브/onActiveSkill 버프인가. */
    private boolean isTimedBuff(SkillIR s) {
        boolean timedTrigger = "active".equals(s.trigger()) || "onActiveSkill".equals(s.trigger());
        if (!timedTrigger) return false;
        for (EffectIR e : s.effects()) {
            if (e.durationMs() == null) continue;
            if (!"statUp".equals(e.role()) && !"debuff".equals(e.role())) continue;
            // 공속은 데미지 모디파이어가 아니라 스케줄러가 반영하지만, 간트 표시를 위해 시한부 버프로 인정(값은 route 안 돼 no-op).
            if (routes(e.stat()) || "AttackSpeed".equals(e.stat())) return true;
        }
        return false;
    }

    /**
     * 버프 활성 윈도우들. onActiveSkill(아드레날린)=스케줄러가 전방 시뮬로 확정한 이벤트 윈도우를 소비
     * (count/refresh/공속되먹임 반영, 여기서 재계산 안 함). active=시전마다.
     */
    private List<int[]> windowsFor(SkillIR s, List<Action> actions, int firstAtk, int simMs,
                                   Map<Integer, List<int[]>> eventWindows) {
        if ("onActiveSkill".equals(s.trigger())) return eventWindows.getOrDefault(s.idx(), List.of());
        List<int[]> out = new ArrayList<>();
        int dur = (int) effDurationMs(s);
        List<Integer> casts = new ArrayList<>();
        for (Action a : actions) if (!a.basic() && a.skill().idx() == s.idx()) casts.add(a.timeMs());
        for (int i = 0; i < casts.size(); i++) {
            int start = casts.get(i);
            int end = Math.min(start + dur, simMs);
            if (i + 1 < casts.size()) end = Math.min(end, casts.get(i + 1));   // ★재발동 시 이전 버프 지속을 잘라냄(강제 재설치=덮어씀). 자동은 지속 끝나고 재사용이라 무영향.
            if (end > start) out.add(new int[]{start, end});
        }
        return out;
    }

    /** 실효 지속(ms) = (기본지속 + flat) × (1 + Σpct). */
    private double effDurationMs(SkillIR s) {
        double base = 0;
        for (EffectIR e : s.effects()) if (e.durationMs() != null) base = Math.max(base, e.durationMs());
        // 벞지(프리셋 BUFF_DURATION)는 '내게 걸리는 버프'에만 적용. 설치기(summon)·디버프는 제외.
        // 대상이 달라(버프=나/디버프=적) 원리상 분리되지만, 현존 스킬 중 '비-설치기 & 버프+디버프 혼재'는 없어
        // 스킬 단위로 (설치기 아님 && 자기버프 있음)일 때만 벞지를 준다(디버프전용·설치기는 0). 혼재 비-설치기 발생 시 윈도우 분리 필요.
        boolean selfBuff = false;
        for (EffectIR e : s.effects()) if ("statUp".equals(e.role())) { selfBuff = true; break; }
        double buffDurPct = (!s.summon() && selfBuff) ? preset.get("BUFF_DURATION") / 100.0 : 0.0;
        return (base + durFlatMs.getOrDefault(s.idx(), 0.0)) * (1 + durPct.getOrDefault(s.idx(), 0.0) + buffDurPct);
    }

    // ── 마스터리 라우팅 ─────────────────────────────────

    /** 버프 스킬에 귀속(skill==idx)되거나 그 스킬로 게이팅(gatedBy==idx)되는 마스터리를 소스에 반영. */
    private void applyMasteries(BuffSource src, int buffIdx) {
        for (MasteryIR m : book.masteries()) {
            if (m.lv() > charLv) continue;
            boolean owns = m.skill() != null && m.skill() == buffIdx;
            boolean gated = m.gatedBy() != null && m.gatedBy() == buffIdx;
            if (owns || gated) routeMastery(src, m);
        }
    }

    /** 마스터리 1건의 딜영향 스탯을 소스에 라우팅(직접 stat 또는 nested effects). duration류는 무시. 성장은 마스터리 소속 스킬 차수레벨. */
    private void routeMastery(BuffSource src, MasteryIR m) {
        int eff = effOf(m.skill() != null ? m.skill() : (m.gatedBy() != null ? m.gatedBy() : 0));
        if (m.stat() != null && m.value() != null && !"duration".equals(m.type()))
            route(src, m.stat(), m.value(), null, m.unit(), eff);
        for (EffectIR e : m.effects()) {
            if ("duration".equals(e.type()) || "duration".equals(e.role())) continue;
            if (e.stat() != null && e.value() != null) route(src, e.stat(), e.value(), e.growthSlot(), e.unit(), eff);
        }
    }

    // ── 스탯 라우팅(단위 규칙) ───────────────────────────

    private boolean routes(String stat) {
        if (stat == null) return false;
        return switch (stat) {
            case "FinalDamage", "ATK_PCT", "CriticalChance", "CriticalPower", "Weakness" -> true;
            default -> false;
        };
    }

    /** 스탯명→ModState 라우팅. FinalDamage=배율(÷1000), 그 외 %스탯=표시%(÷10). 성장(스킬 차수레벨) 반영. 반환=라우팅 여부. */
    private boolean route(BuffSource src, String stat, Double value, Integer growthSlot, String unit, int eff) {
        if (stat == null || value == null) return false;
        double g = Growth.factor(growthSlot, eff);
        switch (stat) {
            case "FinalDamage"    -> src.finalDmgFrac += value / 1000.0 * g;
            // 스킬/유물 공%는 기본 곱(ATK_PCT 풀), unit:flat 예외만 프리셋 공%에 합산(ATK_PCT_ADD)
            case "ATK_PCT"        -> src.add("flat".equals(unit) ? Stat.ATK_PCT_ADD : Stat.ATK_PCT, value / 10.0 * g);
            case "CriticalChance" -> src.add(Stat.CRIT_RATE, value / 10.0 * g);
            case "CriticalPower"  -> src.add(Stat.CRIT_DMG, value / 10.0 * g);
            case "Weakness"       -> src.add(Stat.WEAKNESS, value / 10.0 * g);
            // 크리티컬 디버프(분류=디버프). 최종 fCrit에만 크확/크뎀에 가산 — 크확/크뎀 파생·증폭 입력엔 미포함
            case "EnemyCritResist"  -> src.add(Stat.CRIT_RATE_ENEMY, value / 10.0 * g);
            case "EnemyRecvCritDmg" -> src.add(Stat.CRIT_DMG_ENEMY, value / 10.0 * g);
            // 공속: 딜 공식 미사용(공격 빈도는 스케줄러가 별도 반영)이나, 슬롯 mods에 담아야 파생 입력(헬레나 최대뎀배율·물병 최종뎀)과 툴팁 표시가 산다
            case "AttackSpeed"    -> src.add(Stat.ATK_SPEED, value / 10.0 * g);
            default -> { return false; }  // 이속/기타(프리셋 반영분) 무시
        }
        return true;
    }

    // ── 사전 집계 ───────────────────────────────────────

    private void indexDurationMasteries() {
        for (MasteryIR m : book.masteries()) {
            if (m.lv() > charLv || m.skill() == null) continue;
            if ("duration".equals(m.type()) && m.value() != null) mergeDuration(m.skill(), m.value(), m.unit());
            for (EffectIR e : m.effects()) {
                if ("duration".equals(e.type()) && e.value() != null) mergeDuration(m.skill(), e.value(), e.unit());
            }
        }
    }

    /** 지속 강화 누적: unit:pct=비율(천분율÷1000), 그 외 flat=정수 ms 그대로 더함. */
    private void mergeDuration(int skill, double value, String unit) {
        if ("pct".equals(unit)) durPct.merge(skill, value / 1000.0, Double::sum);
        else durFlatMs.merge(skill, value, Double::sum);
    }

    private int firstAttackTime(List<Action> actions, int simMs) {
        for (Action a : actions) if (a.basic() || a.skill().hasDeal()) return a.timeMs();
        return simMs;
    }
}
