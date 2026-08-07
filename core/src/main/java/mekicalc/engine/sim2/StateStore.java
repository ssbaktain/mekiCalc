package mekicalc.engine.sim2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * State/스택/카운터의 시각별 상태 머신. 시뮬레이터가 유일하게 소유하며,
 * 버프 유효값(스택 비례)·발동 게이팅(requireState)·ON_STATE 콜백·카운터(N회마다)를 전부 여기서 판정한다.
 *
 * <p>스택 수명: 스택마다 자체 만료 시각을 가진다(선입선출 만료). 만료는 시뮬레이터가
 * {@link #expireDue}를 이벤트 시각마다 호출해 반영한다(이벤트 사이에는 상태 변화가 없으므로 충분).
 *
 * <p>관측: 상태별 시간가중 스택 적분을 유지해 평균 스택을 보고한다.
 */
final class StateStore {

    private static final class St {
        final Deque<Double> expiry = new ArrayDeque<>();   // 각 스택의 만료 시각(오름차순)
        int max = Integer.MAX_VALUE;
        double lastT = 0;          // 적분 마지막 갱신 시각
        double stackTimeSum = 0;   // ∫ stacks dt
        double activeTimeSum = 0;  // ∫ [stacks>0] dt
    }

    private final Map<String, St> states = new LinkedHashMap<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();
    private final Map<String, List<DoubleConsumer>> grantListeners = new LinkedHashMap<>();

    private St st(String name) {
        return states.computeIfAbsent(name, k -> new St());
    }

    /**
     * 만료 시각이 t 이하인 스택 제거. 적분은 만료 시점 단위로 나눠 수행해
     * [직전 갱신, 만료) 구간은 만료 전 스택수, 이후는 줄어든 스택수로 정확히 적산한다.
     */
    void expireDue(String name, double t) {
        St s = states.get(name);
        if (s == null) return;
        while (!s.expiry.isEmpty() && s.expiry.peekFirst() <= t) {
            integrate(s, Math.max(s.lastT, s.expiry.peekFirst()));
            s.expiry.pollFirst();
        }
        integrate(s, t);
    }

    /** 모든 상태 만료 처리. */
    void expireAll(double t) {
        for (String k : states.keySet()) expireDue(k, t);
    }

    private void integrate(St s, double t) {
        double dt = t - s.lastT;
        if (dt > 0) {
            s.stackTimeSum += dt * s.expiry.size();
            if (!s.expiry.isEmpty()) s.activeTimeSum += dt;
            s.lastT = t;
        }
    }

    /**
     * 스택 1 추가(상한 도달 시 가장 이른 만료 스택을 갱신 = 상한 유지). 0→1 진입이면 enter 콜백 발화.
     * @return 추가된 스택의 만료 시각(무한이면 +∞) — 시뮬레이터가 만료 재확인 이벤트를 걸 때 사용.
     */
    double addStack(String name, double t, double durSec, int max) {
        St s = st(name);
        expireDue(name, t);
        integrate(s, t);
        s.max = Math.min(s.max, max <= 0 ? Integer.MAX_VALUE : max);
        boolean wasEmpty = s.expiry.isEmpty();
        double end = Double.isInfinite(durSec) ? Double.POSITIVE_INFINITY : t + durSec;
        if (s.expiry.size() >= s.max) s.expiry.pollFirst();   // 상한: 오래된 스택 갱신
        // 만료 오름차순 유지 삽입(대개 끝에 붙음)
        if (s.expiry.isEmpty() || end >= s.expiry.peekLast()) s.expiry.addLast(end);
        else {
            List<Double> tmp = new ArrayList<>(s.expiry);
            tmp.add(end);
            tmp.sort(Double::compare);
            s.expiry.clear();
            s.expiry.addAll(tmp);
        }
        // 부여·갱신마다 콜백(OnState = "State 지속 동안" 의미론: 연동 버프를 함께 갱신해 구간 일치).
        for (DoubleConsumer c : grantListeners.getOrDefault(name, List.of())) c.accept(t);
        if (wasEmpty) { /* 진입 전용 훅이 필요하면 여기서 분기 */ }
        return end;
    }

    /** 시각 t의 스택 수(만료 반영). */
    int stacks(String name, double t) {
        St s = states.get(name);
        if (s == null) return 0;
        expireDue(name, t);
        return s.expiry.size();
    }

    /** State 활성(스택≥1) 여부. */
    boolean active(String name, double t) {
        return stacks(name, t) > 0;
    }

    /** State 부여·갱신 콜백 등록(ON_STATE 스킬: State 지속과 연동된 버프를 함께 갱신). */
    void onGrant(String name, DoubleConsumer cb) {
        grantListeners.computeIfAbsent(name, k -> new ArrayList<>()).add(cb);
    }

    /** 스택 n개(0 이하면 전부) 소모(서펜트 스톤 "모든 스톤 소모" 등). */
    void consume(String name, int n, double t) {
        St s = states.get(name);
        if (s == null) return;
        expireDue(name, t);
        integrate(s, t);
        int rm = (n <= 0) ? s.expiry.size() : Math.min(n, s.expiry.size());
        for (int i = 0; i < rm; i++) s.expiry.pollFirst();
    }

    /** 카운터 1 증가. N회째가 되면 true를 반환하고 0으로 리셋(counterEvery). */
    boolean count(String key, int every) {
        int v = counters.merge(key, 1, Integer::sum);
        if (v >= every) {
            counters.put(key, 0);
            return true;
        }
        return false;
    }

    /** 관측 마감 후 상태별 (평균 스택, 활성 비율%) 리포트. */
    Map<String, double[]> report(double endT) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, St> e : states.entrySet()) {
            St s = e.getValue();
            integrate(s, endT);
            double avg = endT > 0 ? s.stackTimeSum / endT : 0;
            double up = endT > 0 ? s.activeTimeSum / endT * 100 : 0;
            out.put(e.getKey(), new double[]{avg, up});
        }
        return out;
    }
}
