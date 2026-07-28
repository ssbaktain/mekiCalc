package mekicalc.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 스탯 합계 묶음. 스탯 id -> 누적 수치(게임 표기 단위).
 *
 * <p>"현재 캐릭터 스탯" 과 "어떤 수단으로 얻는 스탯 변화량(delta)" 을 동일한 타입으로
 * 표현한다. 불변(immutable)에 가깝게 쓰되, 합산은 새 객체를 반환한다.
 */
public final class StatBlock {

    private final Map<String, Double> amounts = new LinkedHashMap<>();

    public StatBlock() {}

    public StatBlock(StatBlock other) {
        this.amounts.putAll(other.amounts);
    }

    /** 빌더 편의용: 스탯을 더한 뒤 자기 자신을 반환. */
    public StatBlock with(String statId, double amount) {
        amounts.merge(statId, amount, Double::sum);
        return this;
    }

    public double get(String statId) {
        return amounts.getOrDefault(statId, 0.0);
    }

    /** 절대값으로 설정(combine 결과를 덮어쓸 때 사용). */
    public StatBlock set(String statId, double amount) {
        amounts.put(statId, amount);
        return this;
    }

    public Map<String, Double> asMap() {
        return amounts;
    }

    /** this + other 를 담은 새 StatBlock 반환 (둘 다 변경하지 않음). */
    public StatBlock plus(StatBlock other) {
        StatBlock r = new StatBlock(this);
        for (Map.Entry<String, Double> e : other.amounts.entrySet()) {
            r.amounts.merge(e.getKey(), e.getValue(), Double::sum);
        }
        return r;
    }
}
