package mekicalc.engine.sim2;

import java.util.Map;

/**
 * 시뮬 파라미터 — 게임 규칙이 아닌 "가정"은 전부 여기에 명시한다(암묵 상수 금지).
 *
 * @param durationSec        전투 시간(초).
 * @param gcdSec             cd=0 액티브의 재발동 간격 가정(서펜트 스톤 등). 발생 시 Warn 기록.
 * @param hitsTakenPerSec    피격 빈도 가정(ON_DAMAGE proc용). 발생 시 Warn 기록.
 * @param procHitsTriggerProcs proc으로 발생한 타격이 다시 ON_ATTACK을 트리거하는지(게임 기본: false,
 *                            파이널 어택류 자기재귀 방지).
 * @param schedule           타임라인 스케줄러 훅: 스킬 id → 사용자 지정 시전 시각 배열.
 *                           지정된 스킬은 쿨 자동 사용 대신 이 시각에만 시전한다(빌드 재현).
 *                           비어 있으면 전 스킬 "쿨 오는 대로" 자동.
 */
public record SimConfig(double durationSec, double gcdSec, double hitsTakenPerSec,
                        boolean procHitsTriggerProcs, Map<String, double[]> schedule) {

    public static SimConfig auto(double durationSec) {
        return new SimConfig(durationSec, 1.0, 2.0, false, Map.of());
    }
}
