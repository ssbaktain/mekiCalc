package mekicalc.engine.sim;

/**
 * 이벤트 큐 항목. 스케줄러는 {@code time} 오름차순 우선순위 큐에서 하나씩 꺼내
 * "다음 이벤트 시각으로 점프"하며 시뮬을 진행한다(고정 dt 없음).
 *
 * @param time    발생 시각(초).
 * @param type    이벤트 종류.
 * @param payload 종류별 페이로드(예: CAST=SkillPlan, HIT=Cast+Target, SUMMON=SummonPlan). 타입은 처리부에서 캐스팅.
 */
public record TimelineEvent(double time, EventType type, Object payload)
        implements Comparable<TimelineEvent> {

    @Override
    public int compareTo(TimelineEvent o) {
        return Double.compare(time, o.time);
    }
}
