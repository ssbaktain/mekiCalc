package mekicalc.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * "추천 옵션 범위" 설정 저장/로드. 파일: ranges.json. (컨텐츠별)
 *
 * <p>컨텐츠(큐브·어빌리티·유물 잠재 등)마다 나오는 옵션·수치가 달라 효율이 다르므로
 * 컨텐츠별로 따로 저장한다. 형식:
 * <pre>
 * {
 *   "cube":    { "CRIT_RATE": { "enabled": true, "lo": -10, "hi": -5 }, ... },
 *   "ability": { ... }
 * }
 * </pre>
 *
 * <p>크리티컬 확률 · 공격 속도 · 정성적 스탯에 대해, 해당 컨텐츠의 우선 순위 추천에서
 * 그 스탯을 고려할지({@code enabled})와 허용 범위를 정한다.
 *
 * <p>범위는 <b>현재값 기준 상대 오프셋</b>이다(lo ~ hi, 항상 hi ≥ lo):
 * 음수 → 그만큼 잃는 것까지 허용, 0/양수 → 그만큼 얻는 것까지 허용.
 * 예: lo=-10, hi=-5, 현재=100 → 추천은 그 스탯을 90~95 범위로만 바꿀 수 있다.
 */
public final class RangeIO {

    public static final String FILE = "ranges.json";

    /** 컨텐츠 키. */
    public static final String CUBE = "cube";

    /** 정성적 스탯 외에 항상 범위 설정 대상에 포함하는 스탯(크확·공속). */
    public static final List<String> EXTRA_IDS = List.of("CRIT_RATE", "ATK_SPEED");

    /** 한 스탯의 범위 설정: 고려 여부 + 현재값 기준 상대 오프셋(lo ~ hi). */
    public record Range(boolean enabled, double lo, double hi) {
        public static final Range DEFAULT = new Range(false, 0, 0);
    }

    private RangeIO() {}

    /** 전체(컨텐츠→스탯→Range) 로드. 구버전(플랫 스탯맵)은 cube로 마이그레이션. */
    public static Map<String, Map<String, Range>> loadAll(Path dataDir) {
        Map<String, Map<String, Range>> out = new LinkedHashMap<>();
        Path f = dataDir.resolve(FILE);
        if (!Files.exists(f)) return out;
        try {
            Map<String, Object> root = Json.asMap(Json.parse(Files.readString(f)));
            for (Map.Entry<String, Object> e : root.entrySet()) {
                if (e.getKey().startsWith("_")) continue;
                Map<String, Object> m = Json.asMap(e.getValue());
                if (m.containsKey("enabled")) {
                    // 구버전 플랫 형식(스탯→{enabled,lo,hi}) → cube 컨텐츠로 묶음
                    out.computeIfAbsent(CUBE, k -> new LinkedHashMap<>()).put(e.getKey(), parseRange(m));
                } else {
                    out.put(e.getKey(), parseContent(m));
                }
            }
        } catch (Exception ignored) {
            // 손상 → 빈 맵(모두 디폴트)
        }
        return out;
    }

    private static Map<String, Range> parseContent(Map<String, Object> m) {
        Map<String, Range> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey().startsWith("_")) continue;
            out.put(e.getKey(), parseRange(Json.asMap(e.getValue())));
        }
        return out;
    }

    private static Range parseRange(Map<String, Object> m) {
        return new Range(Json.bool(m, "enabled", false), Json.num(m, "lo", 0.0), Json.num(m, "hi", 0.0));
    }

    /** 한 컨텐츠의 스탯→Range. */
    public static Map<String, Range> load(Path dataDir, String content) {
        return loadAll(dataDir).getOrDefault(content, new LinkedHashMap<>());
    }

    /** 추천 엔진용: 한 컨텐츠에서 고려(enabled)인 항목만 statId→{lo, hi}. */
    public static Map<String, double[]> enabledOffsets(Path dataDir, String content) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, Range> e : load(dataDir, content).entrySet()) {
            if (e.getValue().enabled()) out.put(e.getKey(), new double[]{e.getValue().lo(), e.getValue().hi()});
        }
        return out;
    }

    public static void save(Path dataDir, Map<String, Map<String, Range>> all) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_comment\": \"추천 옵션 범위(컨텐츠별). enabled=true면 그 컨텐츠 우선순위 추천에서 스탯을 현재값+lo ~ 현재값+hi로 제한.\"");
        for (Map.Entry<String, Map<String, Range>> ce : all.entrySet()) {
            sb.append(",\n  \"").append(ce.getKey()).append("\": {");
            boolean first = true;
            for (Map.Entry<String, Range> e : ce.getValue().entrySet()) {
                Range r = e.getValue();
                sb.append(first ? "\n" : ",\n");
                first = false;
                sb.append("    \"").append(e.getKey()).append("\": { ");
                sb.append("\"enabled\": ").append(r.enabled());
                sb.append(", \"lo\": ").append(fmt(r.lo()));
                sb.append(", \"hi\": ").append(fmt(r.hi()));
                sb.append(" }");
            }
            sb.append(first ? "}" : "\n  }");
        }
        sb.append("\n}\n");
        Files.writeString(dataDir.resolve(FILE), sb.toString());
    }

    private static String fmt(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
