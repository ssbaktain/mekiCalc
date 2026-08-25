package mekicalc.timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * TreeMap&lt;시간ms, Slot&gt;의 밸류. 한 시각의 상태 스냅샷.
 *
 * <ul>
 *   <li>{@link #deals} — 그 시각에 때리는 딜들(무엇이 때리나)
 *   <li>{@link #mods}  — 그 시각의 배수 환경(버프/디버프 누적)
 * </ul>
 *
 * 데미지 = 각 deal마다 formula(base 프리셋, mods, deal). 두 축은 직교라 절대 섞지 않는다.
 */
public final class Slot {

    private final List<DealEvent> deals = new ArrayList<>();
    private final ModState mods = new ModState();

    public List<DealEvent> deals() {
        return deals;
    }

    public ModState mods() {
        return mods;
    }

    public void addDeal(DealEvent d) {
        deals.add(d);
    }
}
