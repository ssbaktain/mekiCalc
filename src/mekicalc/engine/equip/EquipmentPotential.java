package mekicalc.engine.equip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 장비 윗잠/아랫잠 잠재 옵션 표 생성기.
 *
 * <p>붙여진 원본 표(등급 6 × 슬롯 3 × 부위 13)를 그대로 옮겨 적는 대신, 검증된 생성 규칙으로
 * {@code equipment.json}의 소수 기본값에서 만들어 낸다(전사 오류 방지). 규칙:
 * <ul>
 *   <li>공통 옵션 확률 = baseProb × 슬롯배수(1슬롯 노란1.0 / 2슬롯 0.76·0.24 / 3슬롯 0.92·0.08), 등급 무관.</li>
 *   <li>수치는 등급별 [흰, 노란]. 1슬롯은 노란만, 2·3슬롯은 흰+노란.</li>
 *   <li>특옵 = baseProb 1.0, 같은 슬롯배수. firstGrade에선 노란만, 그 위 등급부터 흰+노란.</li>
 *   <li>주스탯+(MAIN_STAT_FLAT)은 같은 색 특옵 확률의 carve(0.25)만큼 차감.</li>
 *   <li>노말은 노란이 없어 3슬롯 모두 동일(흰 단일값, baseProb 그대로).</li>
 * </ul>
 *
 * <p>이 생성 결과는 {@code SelfTest}가 사용자가 붙여준 실제 숫자들과 대조해 검증한다.
 */
public final class EquipmentPotential {

    private static final String WHITE = "white";
    private static final String YELLOW = "yellow";

    private final List<String> grades;
    private final Map<String, Integer> gradeIndex = new LinkedHashMap<>();
    private final Map<String, Map<String, Double>> slotProb; // slotKey -> color -> multiplier
    private final List<Common> commons = new ArrayList<>();
    private final double specialBaseProb;
    private final double carve;
    private final Map<String, PartSpecial> parts = new LinkedHashMap<>();
    private final Map<String, double[]> gradeUp = new LinkedHashMap<>(); // grade -> {prob, ceiling}

    private static final class Common {
        String stat;
        double baseProb;
        boolean specialCarve;
        Map<String, double[]> values; // grade -> [white, yellow] (노말은 [단일])
    }

    private static final class PartSpecial {
        String stat;
        String firstGrade;
        Map<String, double[]> values;
    }

    private EquipmentPotential(List<String> grades, Map<String, Map<String, Double>> slotProb,
                               double specialBaseProb, double carve) {
        this.grades = grades;
        for (int i = 0; i < grades.size(); i++) gradeIndex.put(grades.get(i), i);
        this.slotProb = slotProb;
        this.specialBaseProb = specialBaseProb;
        this.carve = carve;
    }

    public List<String> grades() { return grades; }
    public java.util.Set<String> parts() { return parts.keySet(); }
    public int gradeIndex(String grade) { return gradeIndex.getOrDefault(grade, -1); }

    /** 그 등급의 다음 등급(없으면 null). */
    public String nextGrade(String grade) {
        int i = gradeIndex(grade);
        return (i >= 0 && i < grades.size() - 1) ? grades.get(i + 1) : null;
    }

    /** 등급업 천장(이만큼 쓰면 강제 승급). 최상위/미정의는 0. */
    public int gradeUpCeiling(String grade) {
        double[] v = gradeUp.get(grade);
        return v != null ? (int) v[1] : 0;
    }

    /**
     * 현재 등급에서 스택 stack일 때 "다음 등급으로 승급까지의 1회당 환산 확률" = 1/E.
     * E(승급까지 기대 큐브) = 상한(천장−stack) 있는 기하분포의 기대값.
     * 최상위 등급(다음 없음)이면 0.
     */
    public double gradeUpEffProb(String grade, int stack) {
        double e = gradeUpCubes(grade, stack);
        return e > 0 ? 1.0 / e : 0.0;
    }

    /**
     * 현재 등급에서 스택 stack일 때 다음 등급 승급까지의 <b>기대 큐브 수</b>.
     * E = (1 − (1−g)^r) / g, r = 천장 − stack (천장 있는 기하분포의 기대값, 항상 1/g 이하).
     * 최상위 등급(승급 정의 없음)이면 0.
     */
    public double gradeUpCubes(String grade, int stack) {
        double[] v = gradeUp.get(grade);
        if (v == null) return 0.0;
        double g = v[0];
        int n = (int) v[1];
        int remaining = Math.max(1, n - Math.max(0, stack));
        return (g > 0) ? (1.0 - Math.pow(1.0 - g, remaining)) / g : remaining;
    }

    /**
     * 한 번 굴림 시 나오는 3개 슬롯 각각의 옵션 분포.
     * @return 크기 3 리스트. 각 원소는 그 슬롯에 뜰 수 있는 후보(PotentialDraw) 목록.
     */
    public List<List<PotentialDraw>> slots(String part, String grade) {
        if (!gradeIndex.containsKey(grade)) throw new IllegalArgumentException("알 수 없는 등급: " + grade);
        List<List<PotentialDraw>> result = new ArrayList<>();

        // 노말: 노란 없음 → 3슬롯 동일(흰 단일값, baseProb)
        if (grade.equals(grades.get(0))) {
            for (int s = 0; s < 3; s++) {
                List<PotentialDraw> list = new ArrayList<>();
                for (Common c : commons) {
                    list.add(new PotentialDraw(c.stat, c.values.get(grade)[0], c.baseProb, false, false));
                }
                result.add(list);
            }
            return result;
        }

        PartSpecial ps = parts.get(part);
        boolean hasSpecial = ps != null && gradeIndex.get(grade) >= gradeIndex.get(ps.firstGrade);
        boolean specialHasWhite = ps != null && gradeIndex.get(grade) > gradeIndex.get(ps.firstGrade);

        String[] slotKeys = {"slot1", "slot2", "slot3"};
        for (String slotKey : slotKeys) {
            Map<String, Double> mult = slotProb.get(slotKey);
            List<PotentialDraw> list = new ArrayList<>();

            // 특옵 먼저(표시 순서 모방)
            if (hasSpecial) {
                double[] sv = ps.values.get(grade);
                // 노란 특옵(특옵이 존재하면 항상)
                list.add(new PotentialDraw(ps.stat, sv[sv.length - 1],
                        specialBaseProb * mult.get(YELLOW), true, true));
                // 흰 특옵(첫 등급 위부터, 그리고 흰 색이 있는 슬롯=2·3슬롯)
                if (specialHasWhite && mult.containsKey(WHITE)) {
                    list.add(new PotentialDraw(ps.stat, sv[0],
                            specialBaseProb * mult.get(WHITE), true, false));
                }
            }

            for (Common c : commons) {
                for (String color : mult.keySet()) {
                    double[] gv = c.values.get(grade);
                    double val = color.equals(WHITE) ? gv[0] : gv[gv.length - 1];
                    double prob = c.baseProb * mult.get(color);
                    if (c.specialCarve && hasSpecial) {
                        boolean specColorExists = color.equals(YELLOW) || specialHasWhite;
                        if (specColorExists) prob -= carve * (specialBaseProb * mult.get(color));
                    }
                    list.add(new PotentialDraw(c.stat, val, prob, false, color.equals(YELLOW)));
                }
            }
            result.add(list);
        }
        return result;
    }

    // ---- 로딩 ----

    @SuppressWarnings("unchecked")
    public static EquipmentPotential load(Path dataDir) throws IOException {
        Map<String, Object> root = Json.asMap(Json.parse(Files.readString(dataDir.resolve("equipment.json"))));

        List<String> grades = new ArrayList<>();
        for (Object o : Json.asList(root.get("grades"))) grades.add(o.toString());

        Map<String, Map<String, Double>> slotProb = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.asMap(root.get("slotProb")).entrySet()) {
            Map<String, Double> colors = new LinkedHashMap<>();
            for (Map.Entry<String, Object> c : Json.asMap(e.getValue()).entrySet()) {
                colors.put(c.getKey(), (Double) c.getValue());
            }
            slotProb.put(e.getKey(), colors);
        }

        Map<String, Object> special = Json.asMap(root.get("special"));
        EquipmentPotential ep = new EquipmentPotential(grades, slotProb,
                Json.num(special, "baseProb"), Json.num(special, "carveFromMainStatFlat"));

        for (Object o : Json.asList(root.get("commonOptions"))) {
            Map<String, Object> m = Json.asMap(o);
            Common c = new Common();
            c.stat = Json.str(m, "stat");
            c.baseProb = Json.num(m, "baseProb");
            c.specialCarve = Json.bool(m, "specialCarve", false);
            c.values = parseValues(Json.asMap(m.get("values")));
            ep.commons.add(c);
        }

        Object gu = root.get("gradeUp");
        if (gu instanceof Map) {
            for (Map.Entry<String, Object> e : Json.asMap(gu).entrySet()) {
                Map<String, Object> m = Json.asMap(e.getValue());
                ep.gradeUp.put(e.getKey(), new double[]{Json.num(m, "prob", 0.0), Json.num(m, "ceiling", 0.0)});
            }
        }

        for (Map.Entry<String, Object> e : Json.asMap(special.get("parts")).entrySet()) {
            Map<String, Object> m = Json.asMap(e.getValue());
            PartSpecial ps = new PartSpecial();
            ps.stat = Json.str(m, "stat");
            ps.firstGrade = Json.str(m, "firstGrade");
            ps.values = parseValues(Json.asMap(m.get("values")));
            ep.parts.put(e.getKey(), ps);
        }
        return ep;
    }

    private static Map<String, double[]> parseValues(Map<String, Object> m) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            List<Object> arr = Json.asList(e.getValue());
            double[] vals = new double[arr.size()];
            for (int i = 0; i < arr.size(); i++) vals[i] = (Double) arr.get(i);
            out.put(e.getKey(), vals);
        }
        return out;
    }
}
