package mekicalc.engine.source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mekicalc.engine.AcquisitionOption;
import mekicalc.json.Json;
import mekicalc.model.StatBlock;

/**
 * 주흔작(주문서 강화) 소스.
 *
 * <p>0레벨에서 목표 레벨까지 강화하며, 매 시도의 성공 확률은 다음을 합산한다.
 * <pre>
 *   p(level) = clamp( baseSuccess + membershipBonus + guildBonus + milestoneBonus(level),  0.01 .. 1.0 )
 * </pre>
 *
 * <p>가정/규칙:
 * <ul>
 *   <li><b>초벌</b>(primeCoat): 반드시 바르고 간다는 가정. 1번째 강화는 성공 확정(p=1)으로 본다.</li>
 *   <li><b>5·10번째 보너스</b>: milestoneBonus 맵(예: {"5":0.10,"10":0.15})으로 해당 레벨 확률 상승.</li>
 *   <li><b>멤버십/길드 스킬</b>: 모든 시도에 일괄 가산.</li>
 *   <li>실패 시 레벨 하락/파괴는 없다고 가정(재시도). 한 레벨의 평균 시도 = 기하분포 1/p.</li>
 * </ul>
 *
 * <p>한 레벨당 스탯 보상(rewardPerLevel)을 목표 레벨만큼 합산한 것이 최종 delta 다.
 */
public final class EnhanceSource implements StatSource {

    private final String id;
    private final String name;
    private final String currency;
    private final double costPerScroll;
    private final int targetLevel;
    private final double baseSuccess;
    private final boolean primeCoat;
    private final double membershipBonus;
    private final double guildBonus;
    private final Map<Integer, Double> milestoneBonus;
    private final String rewardStat;
    private final double rewardPerLevel;
    private final String rewardLabel; // 화면 표기용 (null이면 자동 생성)

    public EnhanceSource(String id, String name, String currency, double costPerScroll,
                         int targetLevel, double baseSuccess, boolean primeCoat,
                         double membershipBonus, double guildBonus,
                         Map<Integer, Double> milestoneBonus,
                         String rewardStat, double rewardPerLevel, String rewardLabel) {
        this.id = id;
        this.name = name;
        this.currency = currency;
        this.costPerScroll = costPerScroll;
        this.targetLevel = targetLevel;
        this.baseSuccess = baseSuccess;
        this.primeCoat = primeCoat;
        this.membershipBonus = membershipBonus;
        this.guildBonus = guildBonus;
        this.milestoneBonus = milestoneBonus;
        this.rewardStat = rewardStat;
        this.rewardPerLevel = rewardPerLevel;
        this.rewardLabel = rewardLabel;
    }

    @Override public String id() { return id; }
    @Override public String name() { return name; }

    /** level(1-base)번째 강화의 성공 확률. */
    public double successProb(int level) {
        if (primeCoat && level == 1) return 1.0;
        double p = baseSuccess + membershipBonus + guildBonus
                + milestoneBonus.getOrDefault(level, 0.0);
        return Math.max(0.01, Math.min(1.0, p));
    }

    /** 0 -> targetLevel 까지 평균 주문서 소모량. */
    public double expectedScrolls() {
        double total = 0;
        for (int lv = 1; lv <= targetLevel; lv++) {
            total += 1.0 / successProb(lv);
        }
        return total;
    }

    @Override
    public List<AcquisitionOption> options(Map<String, Double> currencyValues) {
        double curVal = currencyValues.getOrDefault(currency, 1.0);
        double scrolls = expectedScrolls();
        double expectedCost = scrolls * costPerScroll * curVal;
        StatBlock delta = new StatBlock().with(rewardStat, rewardPerLevel * targetLevel);

        String costDesc = String.format(
                "목표 %d강까지 평균 %.1f장 × %.0f %s = %.0f %s (초벌 %s)",
                targetLevel, scrolls, costPerScroll, currency,
                scrolls * costPerScroll, currency,
                primeCoat ? "적용" : "미적용");

        String label = (rewardLabel != null)
                ? String.format("%s (%d강)", rewardLabel, targetLevel)
                : String.format("%s +%s (%d강)", rewardStat, trim(rewardPerLevel * targetLevel), targetLevel);

        List<AcquisitionOption> out = new ArrayList<>();
        out.add(new AcquisitionOption(id, name, label, delta, scrolls, expectedCost, costDesc));
        return out;
    }

    public static EnhanceSource fromJson(Map<String, Object> m) {
        Map<String, Object> cost = Json.asMap(m.get("costPerScroll"));
        Map<Integer, Double> milestones = new HashMap<>();
        Object mb = m.get("milestoneBonus");
        if (mb != null) {
            for (Map.Entry<String, Object> e : Json.asMap(mb).entrySet()) {
                milestones.put(Integer.parseInt(e.getKey()), ((Double) e.getValue()));
            }
        }
        Map<String, Object> reward = Json.asMap(m.get("rewardPerLevel"));
        return new EnhanceSource(
                Json.str(m, "id"),
                Json.str(m, "name"),
                Json.str(cost, "currency"),
                Json.num(cost, "amount"),
                (int) Json.num(m, "targetLevel"),
                Json.num(m, "baseSuccess"),
                Json.bool(m, "primeCoat", true),
                Json.num(m, "membershipBonus", 0.0),
                Json.num(m, "guildBonus", 0.0),
                milestones,
                Json.str(reward, "stat"),
                Json.num(reward, "amount"),
                Json.str(reward, "label", null));
    }

    private static String trim(double d) {
        if (d == Math.floor(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }
}
