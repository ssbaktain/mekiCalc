package mekicalc.engine.sim2;

/**
 * 결정론 확률 게이트 — 시드 고정 SplitMix64로 발동을 추첨한다.
 * 같은 (시드, 시행 순서)이면 항상 같은 결과(재현성)이면서, 발동 간격의 분산이 실제
 * 베르누이 과정과 같아 시간제 버프 가동률이 정확히 재현된다.
 * (누적(pity) 방식은 간격이 균일해져 uptime을 과대평가한다 — 홀리 차지 실측 0.91 vs pity 0.99.)
 * 내부쿨(icdSec) 동안은 시행을 소비하지 않는다.
 */
final class ProcGate {
    private final double p;
    private final double icdSec;
    private long state;
    private double nextAllowed = 0;

    ProcGate(double procPct, double icdSec, long seed) {
        this.p = Math.max(0, Math.min(1, procPct / 100.0));
        this.icdSec = Math.max(0, icdSec);
        this.state = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
    }

    private double nextUniform() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return ((z >>> 11) * 0x1.0p-53);
    }

    /** 시각 t의 트리거 1회 처리. 발동하면 true. */
    boolean fire(double t) {
        if (p <= 0 || t < nextAllowed) return false;
        if (nextUniform() < p) {
            if (icdSec > 0) nextAllowed = t + icdSec;
            return true;
        }
        return false;
    }
}
