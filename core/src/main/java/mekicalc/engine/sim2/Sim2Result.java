package mekicalc.engine.sim2;

import java.util.List;
import java.util.Map;

/**
 * 시뮬 결과 + 관측 리포트.
 *
 * @param total     총 데미지.
 * @param dps       초당 데미지.
 * @param perSkill  스킬별 기여.
 * @param buffReport 버프 키 → 실측 리포트(가동률%·시간가중 평균값). 착시 방지의 핵심 관측 도구.
 * @param warnings  엔진이 가정/생략한 지점 전체 목록.
 */
public record Sim2Result(double total, double dps, Map<String, Double> perSkill,
                         Map<String, BuffStat> buffReport, List<Warn> warnings) {

    /**
     * 버프 1종의 실측 통계.
     *
     * @param uptimePct  활성 시간 비율(0~100).
     * @param avgValue   시간가중 평균 유효값(스택형은 평균스택×스택당값). 첫 effects 스탯 기준.
     * @param stat       대표 스탯 id.
     */
    public record BuffStat(double uptimePct, double avgValue, String stat) {}
}
