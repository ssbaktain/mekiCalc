package mekicalc.engine.sim2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.model.StatBlock;
import mekicalc.model.StatDef;

/**
 * 버프 인스턴스 타임라인. sim2에서 버프는 "정적 가동률 곱"이 아니라 실제 시작~종료 구간으로 존재하며,
 * 유효 스탯은 매 타격 시각에 활성 구간을 스냅샷해 만든다. 가동률은 근사값이 아니라 구간 합산 실측값이다.
 *
 * <p>갱신 규칙: 같은 key(스킬×스탯조합)의 재부여는 기존 구간을 t에서 절단하고 새 구간을 연다(넥슨 재발동=갱신).
 * 스택형(stackState)은 구간이 아니라 {@link StateStore} 스택으로 관리되고, 여기서는 스냅샷 시 스택수를 곱한다.
 */
final class BuffBook {

    private static final class Inst {
        final BuffSpec2 spec;
        final double start;
        double end;
        boolean closed;   // 적산 완료 표시(갱신 절단 시 1회, 종료 시 1회 — 이중 적산 방지)
        Inst(BuffSpec2 spec, double start, double end) {
            this.spec = spec;
            this.start = start;
            this.end = end;
        }
        boolean activeAt(double t) {
            return t >= start && t < end;
        }
    }

    private final List<Inst> live = new ArrayList<>();
    /** key → 종료(절단·만료) 확정 구간의 활성시간 합. */
    private final Map<String, Double> closedTime = new LinkedHashMap<>();
    /** key → 시간가중 값 적분(∫ 유효값 dt, 스택형 제외 — 스택형은 StateStore가 보고). */
    private final Map<String, Double> valueTime = new LinkedHashMap<>();
    private final Map<String, String> reprStat = new LinkedHashMap<>();

    /**
     * 버프 부여. 스택형이면 StateStore에 스택만 추가하고 구간은 만들지 않는다(값은 스냅샷 시 스택 비례).
     * @return 스택형이면 새 스택의 만료 시각(만료 재확인 이벤트용), 아니면 NaN.
     */
    double grant(BuffSpec2 bs, double t, StateStore states) {
        reprStat.putIfAbsent(bs.key(), bs.effects().keySet().stream().findFirst().orElse("?"));
        if (bs.stackState() != null) {
            return states.addStack(bs.stackState(), t, bs.durationSec(), bs.stackMax());
        }
        if (bs.grantsState() != null) {   // 비스택 버프의 State 존재 등록(재발동=갱신, max 1)
            states.addStack(bs.grantsState(), t, bs.durationSec(), 1);
        }
        for (Inst i : live) {
            if (i.spec.key().equals(bs.key()) && i.activeAt(t)) {   // 재발동 = 갱신(기존 절단)
                close(i, t);
                i.end = t;
            }
        }
        double end = Double.isInfinite(bs.durationSec()) ? Double.POSITIVE_INFINITY : t + bs.durationSec();
        live.add(new Inst(bs, t, end));
        return Double.NaN;
    }

    private void close(Inst i, double t) {
        if (i.closed) return;
        i.closed = true;
        double e = Math.min(i.end, t);
        if (e > i.start) {
            closedTime.merge(i.spec.key(), e - i.start, Double::sum);
            double v = i.spec.effects().values().stream().findFirst().orElse(0.0);
            valueTime.merge(i.spec.key(), (e - i.start) * v, Double::sum);
        }
    }

    /**
     * 시각 t의 유효 스탯 = base + 활성 버프(스택형은 스택수 비례). 합성은 스탯별 combine 규칙.
     * 스택형 스펙은 grant 목록이 아니라 인자 stackSpecs(플랜에 선언된 스택 버프들)로 평가한다.
     */
    StatBlock effective(StatBlock base, double t, StateStore states,
                        List<BuffSpec2> stackSpecs, Map<String, StatDef> defs, Target2 who) {
        StatBlock eff = new StatBlock(base);
        for (Inst i : live) {
            if (i.spec.target() != who || !i.activeAt(t)) continue;
            merge(eff, i.spec.effects(), 1.0, defs);
        }
        for (BuffSpec2 bs : stackSpecs) {
            if (bs.target() != who) continue;
            int n = states.stacks(bs.stackState(), t);
            if (n > 0) merge(eff, bs.effects(), n, defs);
        }
        return eff;
    }

    private static void merge(StatBlock eff, Map<String, Double> effects, double mult, Map<String, StatDef> defs) {
        for (Map.Entry<String, Double> en : effects.entrySet()) {
            String id = en.getKey();
            double v = en.getValue() * mult;
            StatDef def = defs.get(id);
            eff.set(id, def != null ? def.merge(eff.get(id), v) : eff.get(id) + v);
        }
    }

    /** 특정 스탯의 활성 합(적 디버프 RECV_AMP 등 단순 SUM 스냅샷). */
    double sumAt(String statId, double t, Target2 who) {
        double s = 0;
        for (Inst i : live)
            if (i.spec.target() == who && i.activeAt(t))
                s += i.spec.effects().getOrDefault(statId, 0.0);
        return s;
    }

    /** 시뮬 종료 시 리포트: key → (가동률%, 시간가중 평균값). */
    Map<String, Sim2Result.BuffStat> report(double endT) {
        for (Inst i : live) close(i, endT);
        live.clear();
        Map<String, Sim2Result.BuffStat> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : closedTime.entrySet()) {
            double up = endT > 0 ? e.getValue() / endT * 100 : 0;
            double avg = endT > 0 ? valueTime.getOrDefault(e.getKey(), 0.0) / endT : 0;
            out.put(e.getKey(), new Sim2Result.BuffStat(Math.min(up, 100), avg, reprStat.get(e.getKey())));
        }
        return out;
    }
}
