package mekicalc.timeline;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * 한 슬롯(시각)의 버프/디버프 누적 상태. 결합 규칙별로 분리 보관한다.
 *
 * <ul>
 *   <li>{@link #add} — 합연산 풀. 프리셋 스탯에 그대로 더해진다(같은 종류끼리 합).
 *   <li>{@link #finalDmgSources} — 최종뎀 곱연산 소스. 각 v마다 fFinal ×= (1 + v).
 * </ul>
 *
 * <p>스탯 프리셋과 분리돼 있어 "델타"만 담는다. 계산 시 base 프리셋을 주입해 합친다(원본 불변).
 * 값 단위: {@link #add}는 % 포인트, {@link #finalDmgSources}는 분율(0.157 = 15.7%).
 */
public final class ModState {

    private final EnumMap<Stat, Double> add = new EnumMap<>(Stat.class);
    private final List<Double> finalDmgSources = new ArrayList<>();

    /** 스탯 풀에 누적 — ★스탯 고유 합성 규칙 적용({@link Stat#combine}): 공속/방관/쿨감%는 체감(상한), 그 외 합산.
     *  체감은 가환·결합이라 0에서부터 순차 누적해도 최종 결과가 결합 순서와 무관(preset 합성도 같은 규칙). */
    public void addStat(Stat s, double pct) {
        add.merge(s, pct, s::combine);
    }

    /** 최종뎀 곱소스 추가(분율). 소스별로 개별 곱하므로 push. */
    public void addFinalDmgSource(double frac) {
        finalDmgSources.add(frac);
    }

    /** 합풀 조회(없으면 0). */
    public double get(Stat s) {
        return add.getOrDefault(s, 0.0);
    }

    /** 최종뎀 소스 전체 곱: Π(1 + v). 소스 없으면 1.0. */
    public double finalDmgMult() {
        double m = 1.0;
        for (double v : finalDmgSources) m *= (1.0 + v);
        return m;
    }

    public List<Double> finalDmgSources() {
        return finalDmgSources;
    }
}
