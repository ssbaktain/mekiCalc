package mekicalc.engine.equip;

/**
 * 장비 잠재 한 줄(슬롯)에 뜰 수 있는 하나의 옵션 후보.
 *
 * @param statId  스탯 id
 * @param value   수치(게임 표시값)
 * @param prob    이 슬롯에서 이 옵션·수치가 뜰 확률(%) — 한 슬롯 내 후보들의 합은 100% 미만(무의미 옵션 생략)
 * @param special 특옵(부위 전용) 여부
 * @param yellow  노란 옵션(같은 등급 내 더 높은 수치) 여부. 흰 옵션이면 false.
 */
public record PotentialDraw(String statId, double value, double prob, boolean special, boolean yellow) {

    @Override
    public String toString() {
        return String.format("%s%s %s (%.2f%%)", special ? "*" : "", statId, trim(value), prob);
    }

    private static String trim(double d) {
        return (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
