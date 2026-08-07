package mekicalc.engine.sim;

/**
 * 스케줄러 타임라인에 깔린 활성 버프/디버프 구간.
 * 매 타격 시각 t에서 {@code startSec <= t < endSec}인 인스턴스들을 스냅샷해 유효 스탯을 만든다.
 *
 * @param spec     원본 효과 정의.
 * @param startSec 발효 시각(초).
 * @param endSec   종료 시각(초). 상시면 {@link Double#POSITIVE_INFINITY}.
 * @param owner    이 구간이 속한 타임라인(PLAYER/COMPANION/ENEMY).
 */
public record BuffInstance(BuffSpec spec, double startSec, double endSec, Target owner) {

    /** 시각 t에 이 구간이 활성인가. */
    public boolean activeAt(double t) {
        return t >= startSec && t < endSec;
    }
}
