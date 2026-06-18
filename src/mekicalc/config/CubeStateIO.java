package mekicalc.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * "내 큐브 옵션"(부위별 현재 잠재 상태) 저장/로드. 파일: mycube.json.
 *
 * <p>부위마다 윗잠(미라클)·아랫잠(에디셔널) 각각 등급 + 3슬롯(stat+value 또는 비움)을 기록한다.
 */
public final class CubeStateIO {

    public static final String FILE = "mycube.json";

    /** 한 슬롯에 박힌 옵션. 비움이면 슬롯 배열에서 null. */
    public record Slot(String stat, double value) {}

    /**
     * 한 잠재 줄(윗잠 또는 아랫잠): 등급 + 3슬롯(null 허용) + 슬롯별 잠금(추천이 못 돌리게 고정).
     * 잠금은 슬롯 위치별(빈 슬롯도 잠글 수 있음).
     */
    public record Line(String grade, Slot[] slots, boolean[] locked, int stack) {
        public Line(String grade, Slot[] slots) { this(grade, slots, new boolean[3], 0); }
        public Line(String grade, Slot[] slots, boolean[] locked) { this(grade, slots, locked, 0); }
        public boolean lockedAt(int i) { return locked != null && i < locked.length && locked[i]; }
    }

    /** 한 부위: 윗잠 + 아랫잠(각각 null 가능). */
    public record PartCube(Line upper, Line lower) {}

    private CubeStateIO() {}

    public static Map<String, PartCube> load(Path dataDir) {
        Map<String, PartCube> out = new LinkedHashMap<>();
        Path f = dataDir.resolve(FILE);
        if (!Files.exists(f)) return out;
        try {
            Map<String, Object> root = Json.asMap(Json.parse(Files.readString(f)));
            for (Map.Entry<String, Object> e : root.entrySet()) {
                if (e.getKey().startsWith("_")) continue;
                Map<String, Object> pm = Json.asMap(e.getValue());
                out.put(e.getKey(), new PartCube(parseLine(pm.get("윗잠")), parseLine(pm.get("아랫잠"))));
            }
        } catch (Exception ignored) {
            // 손상된 파일은 빈 상태로 시작
        }
        return out;
    }

    static Line parseLine(Object o) {
        if (o == null) return null;
        Map<String, Object> m = Json.asMap(o);
        List<Object> arr = Json.asList(m.get("slots"));
        Slot[] slots = new Slot[3];
        for (int i = 0; i < 3 && i < arr.size(); i++) {
            Object el = arr.get(i);
            if (el == null) continue;
            Map<String, Object> sm = Json.asMap(el);
            slots[i] = new Slot(Json.str(sm, "stat"), Json.num(sm, "value"));
        }
        boolean[] locked = new boolean[3];
        if (m.get("locked") instanceof List) {
            List<Object> la = Json.asList(m.get("locked"));
            for (int i = 0; i < 3 && i < la.size(); i++) locked[i] = Boolean.TRUE.equals(la.get(i));
        }
        int stack = (int) Json.num(m, "stack", 0.0);
        return new Line(Json.str(m, "grade"), slots, locked, stack);
    }

    public static void save(Path dataDir, Map<String, PartCube> map) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_comment\": \"내 큐브 옵션. 부위→{윗잠,아랫잠}, 각 {grade, slots[3]}, 슬롯 비움은 null.\"");
        for (Map.Entry<String, PartCube> e : map.entrySet()) {
            PartCube pc = e.getValue();
            sb.append(",\n  \"").append(e.getKey()).append("\": { ");
            sb.append("\"윗잠\": ").append(lineJson(pc.upper()));
            sb.append(", \"아랫잠\": ").append(lineJson(pc.lower()));
            sb.append(" }");
        }
        sb.append("\n}\n");
        Files.writeString(dataDir.resolve(FILE), sb.toString());
    }

    static String lineJson(Line line) {
        if (line == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("{ \"grade\": \"").append(line.grade()).append("\", \"slots\": [");
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append(", ");
            Slot s = (line.slots() != null && i < line.slots().length) ? line.slots()[i] : null;
            if (s == null) sb.append("null");
            else sb.append("{ \"stat\": \"").append(s.stat()).append("\", \"value\": ").append(fmt(s.value())).append(" }");
        }
        sb.append("], \"locked\": [");
        for (int i = 0; i < 3; i++) sb.append(i > 0 ? ", " : "").append(line.lockedAt(i));
        sb.append("], \"stack\": ").append(line.stack());
        sb.append(" }");
        return sb.toString();
    }

    private static String fmt(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
