package mekicalc.timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 버프/디버프를 "값 윈도우"({@link BuffSource})의 집합으로 보관하고, <b>임의 시각 t</b>의 실효
 * {@link ModState}를 즉석 계산(fold)한다. 슬롯에 미리 굽지 않으므로(lazy) 경계값이 정확하다:
 * {@link #at(int)}는 t를 포함하는 모든 윈도우를 그 시점 기준으로 정확히 fold한다.
 *
 * <p><b>딜타임 파리티 계약</b>: 딜 슬롯 시각 t에서 {@code at(t)}는 기존 per-slot 누적({@code slot.mods()})과
 * 동일해야 한다. 그래서 순환(첫 슬롯 크확 기준 발동)·파생(입력=base+순환)·육각(스택=f(t))·약화(창 겹침 스택)를
 * 모두 <b>같은 값</b>의 윈도우로 방출한다. fold는 {@link BuffSource#applyTo}(=ModState.addStat, 체감 합성)로 하므로
 * 순서 무관(체감=가환·결합)하고 누적 경로와 일치한다.
 *
 * <p>끝 경계는 기존 {@code subMap(start,true,end,end>=simMs)} 의미를 그대로 따른다: 창이 simMs까지 지속이면
 * t==end도 포함(마지막 슬롯 누락 방지), 아니면 [start,end) 반열림.
 */
public final class WindowSet {

    private final List<BuffSource> windows;
    private final int simMs;

    public WindowSet(List<BuffSource> windows, int simMs) {
        this.windows = windows;
        this.simMs = simMs;
    }

    public List<BuffSource> windows() { return windows; }

    /** 시각 t의 실효 상태 = t를 포함하는(끝 경계는 subMap과 동일) 활성 윈도우 전부 fold. dormant는 딜 미반영이라 제외. */
    public ModState at(int t) {
        ModState m = new ModState();
        for (BuffSource w : windows) {
            if (w.dormant()) continue;
            boolean incl = w.endMs() >= simMs;   // 끝까지 지속이면 종료 경계 포함(BuffAccumulator/asm.accumulate와 동일)
            if (w.startMs() <= t && (t < w.endMs() || (incl && t == w.endMs()))) w.applyTo(m);
        }
        return m;
    }

    /** 빈 WindowSet(동료 없음·정적 검증용). at(t)는 항상 빈 ModState. */
    public static WindowSet empty() { return new WindowSet(new ArrayList<>(), Integer.MAX_VALUE); }
}
