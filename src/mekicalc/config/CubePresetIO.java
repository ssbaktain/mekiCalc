package mekicalc.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.config.CubeStateIO.PartCube;
import mekicalc.json.Json;

/**
 * 여러 개의 "이름 있는 큐브 옵션 프리셋" 저장/로드. 파일: cube_presets.json.
 *
 * <p>큐브 전용 독립 프리셋이다(스탯 프리셋과 별개). 형식:
 * <pre>
 * {
 *   "active": "기본",
 *   "presets": [
 *     { "name": "기본", "cubes": { "모자": { "윗잠":..., "아랫잠":... }, ... } }
 *   ]
 * }
 * </pre>
 *
 * <p>cube_presets.json이 없으면 기존 mycube.json을 "기본" 프리셋으로 자동 마이그레이션한다.
 * 활성 프리셋의 큐브는 mycube.json에도 미러링해 기존 코드 호환을 유지한다.
 */
public final class CubePresetIO {

    public static final String FILE = "cube_presets.json";
    public static final String DEFAULT_NAME = "기본";

    /** 한 큐브 프리셋: 이름 + 부위별 큐브 옵션. */
    public record CubePreset(String name, Map<String, PartCube> cubes) {}

    /** 전체 큐브 프리셋 목록 + 활성 프리셋 이름. */
    public record Store(List<CubePreset> presets, String active) {}

    private CubePresetIO() {}

    public static boolean exists(Path dataDir) {
        return Files.exists(dataDir.resolve(FILE));
    }

    /** 프리셋 목록 로드. 없으면 mycube.json에서 마이그레이션, 그것도 없으면 빈 기본 하나. */
    public static Store load(Path dataDir) {
        Path f = dataDir.resolve(FILE);
        if (Files.exists(f)) {
            try {
                Map<String, Object> root = Json.asMap(Json.parse(Files.readString(f)));
                List<CubePreset> presets = new ArrayList<>();
                for (Object o : Json.asList(root.get("presets"))) {
                    Map<String, Object> pm = Json.asMap(o);
                    String name = Json.str(pm, "name");
                    if (name == null || name.isEmpty()) continue;
                    presets.add(new CubePreset(name, parseCubes(pm.get("cubes"))));
                }
                if (!presets.isEmpty()) {
                    String active = Json.str(root, "active", null);
                    if (active == null || indexOf(presets, active) < 0) active = presets.get(0).name();
                    return new Store(presets, active);
                }
            } catch (Exception ignored) {
                // 손상 → 마이그레이션/기본으로 폴백
            }
        }
        // mycube.json → "기본" 프리셋
        Map<String, PartCube> cubes = CubeStateIO.load(dataDir);
        List<CubePreset> presets = new ArrayList<>();
        presets.add(new CubePreset(DEFAULT_NAME, cubes));
        return new Store(presets, DEFAULT_NAME);
    }

    private static Map<String, PartCube> parseCubes(Object cubesObj) {
        Map<String, PartCube> out = new LinkedHashMap<>();
        if (cubesObj == null) return out;
        for (Map.Entry<String, Object> e : Json.asMap(cubesObj).entrySet()) {
            Map<String, Object> pm = Json.asMap(e.getValue());
            out.put(e.getKey(), new PartCube(
                    CubeStateIO.parseLine(pm.get("윗잠")), CubeStateIO.parseLine(pm.get("아랫잠"))));
        }
        return out;
    }

    private static int indexOf(List<CubePreset> presets, String name) {
        for (int i = 0; i < presets.size(); i++) if (presets.get(i).name().equals(name)) return i;
        return -1;
    }

    public static void save(Path dataDir, Store store) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_comment\": \"큐브 옵션 프리셋(독립). active=현재 활성. 활성 프리셋은 mycube.json에도 미러링.\",\n");
        sb.append("  \"active\": \"").append(esc(store.active())).append("\",\n");
        sb.append("  \"presets\": [\n");
        List<CubePreset> presets = store.presets();
        for (int i = 0; i < presets.size(); i++) {
            CubePreset p = presets.get(i);
            sb.append("    { \"name\": \"").append(esc(p.name())).append("\", \"cubes\": {");
            boolean first = true;
            for (Map.Entry<String, PartCube> e : p.cubes().entrySet()) {
                sb.append(first ? "\n" : ",\n");
                first = false;
                PartCube pc = e.getValue();
                sb.append("      \"").append(esc(e.getKey())).append("\": { ");
                sb.append("\"윗잠\": ").append(CubeStateIO.lineJson(pc.upper()));
                sb.append(", \"아랫잠\": ").append(CubeStateIO.lineJson(pc.lower()));
                sb.append(" }");
            }
            sb.append(first ? " } }" : "\n    } }");
            sb.append(i < presets.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        Files.writeString(dataDir.resolve(FILE), sb.toString());
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
