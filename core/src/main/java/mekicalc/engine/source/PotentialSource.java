package mekicalc.engine.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mekicalc.engine.AcquisitionOption;
import mekicalc.json.Json;
import mekicalc.model.StatBlock;

/**
 * 확률 재굴림(reroll) 방식 소스: 장비 윗잠/아랫잠, 어빌리티, 용사의 여정, 유물 잠재.
 *
 * <p>한 번 굴릴 때마다 일정 비용이 들고, 원하는 옵션(line)이 확률 p 로 등장한다.
 * 특정 옵션 1건을 확보하기까지의 평균 시도 횟수는 기하분포 기댓값 {@code 1/p} 다.
 * (다른 옵션이 같이 떠도 무시하고 "원하는 옵션이 뜰 때까지" 굴린다는 가정)
 *
 * <p>장비별 특수 옵션은 line 의 {@code special=true} 로 표시만 해 둔다(해당 부위에만 존재).
 */
public final class PotentialSource implements StatSource {

    /** 잠재 옵션 한 줄. */
    static final class Line {
        final String statId;
        final double amount;
        final double prob;
        final boolean special;
        final String label;

        Line(String statId, double amount, double prob, boolean special, String label) {
            this.statId = statId;
            this.amount = amount;
            this.prob = prob;
            this.special = special;
            this.label = label;
        }
    }

    private final String id;
    private final String name;
    private final String currency;
    private final double costPerRoll;
    private final List<Line> lines;

    public PotentialSource(String id, String name, String currency, double costPerRoll, List<Line> lines) {
        this.id = id;
        this.name = name;
        this.currency = currency;
        this.costPerRoll = costPerRoll;
        this.lines = lines;
    }

    @Override public String id() { return id; }
    @Override public String name() { return name; }

    @Override
    public List<AcquisitionOption> options(Map<String, Double> currencyValues) {
        double curVal = currencyValues.getOrDefault(currency, 1.0);
        List<AcquisitionOption> out = new ArrayList<>();
        for (Line ln : lines) {
            if (ln.prob <= 0) continue; // 등장 불가 옵션은 건너뜀
            double expectedAttempts = 1.0 / ln.prob;
            double expectedCost = expectedAttempts * costPerRoll * curVal;
            StatBlock delta = new StatBlock().with(ln.statId, ln.amount);
            String costDesc = String.format(
                    "평균 %.1f회 굴림 × %.0f %s = %.0f %s%s",
                    expectedAttempts, costPerRoll, currency,
                    expectedAttempts * costPerRoll, currency,
                    ln.special ? " (부위 전용 옵션)" : "");
            out.add(new AcquisitionOption(id, name, ln.label, delta,
                    expectedAttempts, expectedCost, costDesc));
        }
        return out;
    }

    public static PotentialSource fromJson(Map<String, Object> m) {
        Map<String, Object> cost = Json.asMap(m.get("costPerRoll"));
        List<Line> lines = new ArrayList<>();
        for (Object o : Json.asList(m.get("lines"))) {
            Map<String, Object> lm = Json.asMap(o);
            String statId = Json.str(lm, "stat");
            double amount = Json.num(lm, "amount");
            boolean special = Json.bool(lm, "special", false);
            String label = Json.str(lm, "label", statId + " +" + trim(amount));
            lines.add(new Line(statId, amount, Json.num(lm, "prob"), special, label));
        }
        return new PotentialSource(
                Json.str(m, "id"),
                Json.str(m, "name"),
                Json.str(cost, "currency"),
                Json.num(cost, "amount"),
                lines);
    }

    private static String trim(double d) {
        if (d == Math.floor(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }
}
