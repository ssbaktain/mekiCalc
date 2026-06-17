package mekicalc.engine.equip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.engine.DamageModel;
import mekicalc.model.StatBlock;

/**
 * 장비 잠재 큐브 평가기 (v1.0).
 *
 * <p>유저의 현재 스탯을 기준으로, 특정 부위·등급을 한 번 굴렸을 때 나올 수 있는 각 옵션에 대해
 * <ul>
 *   <li><b>딜 증가량</b> — 그 옵션 한 줄을 얻었다고 가정했을 때 최종 데미지 증가율</li>
 *   <li><b>큐브 기댓값</b> — 그 옵션이 (3줄 중 한 줄에라도) 뜰 때까지 평균 큐브 수</li>
 * </ul>
 * 를 계산한다.
 *
 * <p>큐브 1회 = 3줄을 모두 새로 굴림. 한 옵션이 이번 굴림에서 등장할 확률은
 * {@code P = 1 - Π_slot(1 - p_slot)} 이고, 등장까지 평균 큐브 수는 기하분포로 {@code 1/P} 다.
 * 윗잠=미라클 큐브, 아랫잠=에디셔널 큐브를 1개씩 소모한다.
 */
public final class CubeEvaluator {

    /** 한 옵션에 대한 평가 결과. */
    public record Result(
            String statId, double value, boolean special, boolean yellow,
            boolean qualitative, double damageIncrease,
            double pPerCube, double expectedCubes) {}

    private CubeEvaluator() {}

    public static List<Result> evaluate(EquipmentPotential ep, DamageModel model,
                                        StatBlock current, String part, String grade) {
        List<List<PotentialDraw>> slots = ep.slots(part, grade);

        // 서로 다른 옵션(stat|value|special)을 모으고, 슬롯별 등장확률을 누적
        Map<String, double[]> miss = new LinkedHashMap<>(); // key -> Π(1 - p_slot)
        Map<String, PotentialDraw> meta = new LinkedHashMap<>();
        for (List<PotentialDraw> slot : slots) {
            // 이번 슬롯에서 각 옵션의 등장확률(같은 옵션이 한 슬롯에 중복 표기되진 않음)
            Map<String, Double> here = new LinkedHashMap<>();
            for (PotentialDraw d : slot) {
                String key = key(d);
                here.merge(key, d.prob() / 100.0, Double::sum);
                meta.putIfAbsent(key, d);
            }
            // 누적: 각 옵션에 대해 이 슬롯에서 "안 뜰" 확률을 곱함
            for (String key : meta.keySet()) {
                double pHere = here.getOrDefault(key, 0.0);
                miss.computeIfAbsent(key, k -> new double[]{1.0})[0] *= (1.0 - pHere);
            }
        }

        List<Result> out = new ArrayList<>();
        for (Map.Entry<String, PotentialDraw> e : meta.entrySet()) {
            PotentialDraw d = e.getValue();
            double pAppear = 1.0 - miss.get(e.getKey())[0];
            double expectedCubes = pAppear > 0 ? 1.0 / pAppear : Double.POSITIVE_INFINITY;

            StatBlock delta = new StatBlock().with(d.statId(), d.value());
            boolean qual = model.hasQualitative(delta);
            double inc = qual ? Double.NaN : model.increase(current, delta);

            out.add(new Result(d.statId(), d.value(), d.special(), d.yellow(), qual, inc, pAppear, expectedCubes));
        }
        return out;
    }

    private static String key(PotentialDraw d) {
        return d.statId() + "|" + d.value() + "|" + d.special();
    }
}
