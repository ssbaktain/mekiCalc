package mekicalc.model;

import java.util.Map;
import mekicalc.json.Json;

/**
 * 스탯 1종의 정의.
 *
 * <p>스탯은 enum이 아니라 데이터(stats.json)로 정의된다(게임 패치로 새 스탯이 생겨도
 * 데이터만 고치면 되도록). 데미지 공식에서 각 스탯이 어떤 역할인지는 {@code id}로 식별한다.
 *
 * <p>핵심은 {@code combine}: 현재 수치에 옵션 수치를 합칠 때의 규칙이다.
 * <ul>
 *   <li>{@code SUM}        : 단순 합산 (대부분의 깡스탯/%스탯).</li>
 *   <li>{@code DIMINISHING}: cap을 향한 체감 곱연산. 공격속도(max 150), 방어관통력(max 100).
 *       새값 = 현재 + (max - 현재) × (옵션 / max). 남은 차이에 곱연산이 적용된다.</li>
 *   <li>{@code MULT_PCT}   : 표시%끼리 곱연산. 최종 데미지(80%에 +10% → 98%, 즉 1.8×1.1).
 *       새값(표시%) = ((1+현재/100) × (1+옵션/100) − 1) × 100.</li>
 * </ul>
 */
public final class StatDef {

    public enum Combine { SUM, DIMINISHING, MULT_PCT }

    public final String id;
    public final String name;
    public final String unit;     // "FLAT" | "PCT" | "SEC" | "LEVEL" (표시용)
    public final Combine combine;
    public final double max;      // DIMINISHING 전용 cap
    /**
     * true면 데미지 공식으로 환산하지 않는다(공격속도/방어관통처럼 정확한 식·적 방어력이 불명).
     * 이런 옵션은 수치 대신 "정성적 고효율 후보"로 따로 표시한다.
     */
    public final boolean qualitative;
    /** true면 다른 스탯에서 자동 파생되어 표시(직접 입력 X). 예: 스탯 비례 데미지←주스탯. */
    public final boolean derived;
    /**
     * true면 스탯 입력 화면에 표시하지 않는다(직접 입력 대상이 아님).
     * 예: 1레벨당 주스탯 — 큐브 옵션으로만 들어오고 즉시 레벨×값의 깡주스탯으로 환산된다.
     */
    public final boolean hidden;
    /** 스탯 입력 화면에서 섹션 묶음 표시용 그룹 이름(예: 공격력/치명타/스킬 레벨). */
    public final String group;

    public StatDef(String id, String name, String unit, Combine combine, double max, boolean qualitative) {
        this(id, name, unit, combine, max, qualitative, false, false, "기타");
    }

    public StatDef(String id, String name, String unit, Combine combine, double max,
                   boolean qualitative, boolean derived) {
        this(id, name, unit, combine, max, qualitative, derived, false, "기타");
    }

    public StatDef(String id, String name, String unit, Combine combine, double max,
                   boolean qualitative, boolean derived, boolean hidden) {
        this(id, name, unit, combine, max, qualitative, derived, hidden, "기타");
    }

    public StatDef(String id, String name, String unit, Combine combine, double max,
                   boolean qualitative, boolean derived, boolean hidden, String group) {
        this.id = id;
        this.name = name;
        this.unit = unit;
        this.combine = combine;
        this.max = max;
        this.qualitative = qualitative;
        this.derived = derived;
        this.hidden = hidden;
        this.group = group;
    }

    /** 현재값 cur 에 옵션값 delta 를 combine 규칙으로 합친 새 값. */
    public double merge(double cur, double delta) {
        switch (combine) {
            case SUM:
                return cur + delta;
            case DIMINISHING:
                // 남은 차이(max-cur)의 (delta/max) 만큼만 채운다 → cap을 향한 체감
                return cur + (max - cur) * (delta / max);
            case MULT_PCT:
                // 표시%끼리 곱연산 (숨은 100% 기준)
                return ((1.0 + cur / 100.0) * (1.0 + delta / 100.0) - 1.0) * 100.0;
            default:
                throw new IllegalStateException("알 수 없는 combine: " + combine);
        }
    }

    /** merge 의 역연산: 합쳐진 값 merged 에서 delta 를 제거해 원래 cur 를 복원한다. */
    public double unmerge(double merged, double delta) {
        switch (combine) {
            case SUM:
                return merged - delta;
            case DIMINISHING:
                // merge: new = cur(1 - delta/max) + delta  →  cur = (new - delta)/(1 - delta/max)
                return (merged - delta) / (1.0 - delta / max);
            case MULT_PCT:
                // merge 역: cur = ((1+new/100)/(1+delta/100) - 1) × 100
                return ((1.0 + merged / 100.0) / (1.0 + delta / 100.0) - 1.0) * 100.0;
            default:
                throw new IllegalStateException("알 수 없는 combine: " + combine);
        }
    }

    public static StatDef fromJson(Map<String, Object> m) {
        return new StatDef(
                Json.str(m, "id"),
                Json.str(m, "name"),
                Json.str(m, "unit", "PCT"),
                Combine.valueOf(Json.str(m, "combine", "SUM")),
                Json.num(m, "max", 0.0),
                Json.bool(m, "qualitative", false),
                Json.bool(m, "derived", false),
                Json.bool(m, "hidden", false),
                Json.str(m, "group", "기타"));
    }
}
