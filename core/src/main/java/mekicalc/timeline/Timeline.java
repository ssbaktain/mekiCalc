package mekicalc.timeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 시간축 상태 컨테이너.
 * <ul>
 *   <li>{@link #slots} — TreeMap&lt;시간ms, Slot&gt;. 딜이 발생하는 모든 시각에 슬롯이 존재.
 *   <li>{@link #skillTimes} — 스킬 idx → 그 스킬이 딜한 시각들(중복 없는 정렬셋). 스킬별/구간별 분석용 역인덱스.
 * </ul>
 */
public final class Timeline {

    private final TreeMap<Integer, Slot> slots = new TreeMap<>();
    private final Map<Integer, TreeSet<Integer>> skillTimes = new java.util.HashMap<>();

    /** 딜 이벤트 등록: 해당 시각 슬롯에 추가 + 역인덱스에 (idx→시각) 등록. */
    public void addDeal(int timeMs, DealEvent d) {
        slots.computeIfAbsent(timeMs, k -> new Slot()).addDeal(d);
        skillTimes.computeIfAbsent(d.skillIdx(), k -> new TreeSet<>()).add(timeMs);
    }

    /** 딜이 없더라도 슬롯을 확보(버프 누적 대상 시각 등). */
    public Slot slotAt(int timeMs) {
        return slots.computeIfAbsent(timeMs, k -> new Slot());
    }

    public TreeMap<Integer, Slot> slots() { return slots; }

    /** 스킬이 딜한 시각들(정렬, 중복 없음). 없으면 빈 리스트. */
    public List<Integer> timesOf(int skillIdx) {
        TreeSet<Integer> t = skillTimes.get(skillIdx);
        return t == null ? List.of() : new ArrayList<>(t);
    }

    public java.util.Set<Integer> dealingSkills() { return skillTimes.keySet(); }
}
