package mekicalc.timeline;

import java.util.List;
import java.util.Map;

/**
 * 스케줄러 출력 — 배치된 액션 + 전방 시뮬로 확정된 이벤트 버프 윈도우.
 *
 * <p>onActiveSkill count 버프(아드레날린 등)는 공속이 스케줄에 되먹임되므로 스케줄러 전방 패스에서
 * 함께 확정한다. 이렇게 나온 윈도우를 {@link BuffAccumulator}가 그대로 소비한다(중복 계산·순환의존 없음).
 *
 * @param actions      시간순 액션(평타·시전)
 * @param eventWindows 스킬 idx → 활성 윈도우 [startMs, endMs) 목록
 */
public record Schedule(List<Action> actions, Map<Integer, List<int[]>> eventWindows) {}
