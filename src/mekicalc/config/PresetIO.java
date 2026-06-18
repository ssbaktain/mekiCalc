package mekicalc.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;
import mekicalc.model.StatBlock;

/**
 * 여러 개의 "이름 있는 스탯 프리셋" 저장/로드. 파일: presets.json.
 *
 * <p>스탯만 프리셋으로 분리한다(큐브 옵션 mycube.json은 단일·공용). 형식:
 * <pre>
 * {
 *   "active": "기본",
 *   "presets": [
 *     { "name": "기본", "stats": { "ATK_FLAT": 100, ... } },
 *     { "name": "보스", "stats": { ... } }
 *   ]
 * }
 * </pre>
 *
 * <p>presets.json이 없으면 기존 character.json을 "기본" 프리셋으로 자동 마이그레이션한다
 * (메모리 상에서만; 디스크 기록은 첫 저장 시점). 둘 다 없으면 빈 "기본" 하나로 시작한다.
 */
public final class PresetIO {

    public static final String FILE = "presets.json";
    /** 마이그레이션/신규 시 기본 프리셋 이름. */
    public static final String DEFAULT_NAME = "기본";

    /** 한 프리셋: 이름 + 스탯 묶음. */
    public record Preset(String name, StatBlock stats) {}

    /** 전체 프리셋 목록 + 활성 프리셋 이름. */
    public record Store(List<Preset> presets, String active) {}

    private PresetIO() {}

    /** presets.json 존재 여부. */
    public static boolean exists(Path dataDir) {
        return Files.exists(dataDir.resolve(FILE));
    }

    /**
     * 프리셋 목록을 로드. 없으면 character.json에서 마이그레이션, 그것도 없으면 빈 기본 하나.
     * 반환되는 active는 항상 presets 안의 어떤 이름과 일치(목록은 최소 1개 보장).
     */
    public static Store load(Path dataDir) {
        Path f = dataDir.resolve(FILE);
        if (Files.exists(f)) {
            try {
                Map<String, Object> root = Json.asMap(Json.parse(Files.readString(f)));
                List<Preset> presets = new ArrayList<>();
                for (Object o : Json.asList(root.get("presets"))) {
                    Map<String, Object> pm = Json.asMap(o);
                    String name = Json.str(pm, "name");
                    if (name == null || name.isEmpty()) continue;
                    presets.add(new Preset(name, parseStats(pm.get("stats"))));
                }
                if (!presets.isEmpty()) {
                    String active = Json.str(root, "active");
                    if (active == null || indexOf(presets, active) < 0) active = presets.get(0).name();
                    return new Store(presets, active);
                }
            } catch (Exception ignored) {
                // 손상된 파일 → 아래 마이그레이션/기본으로 폴백
            }
        }
        return migrateOrDefault(dataDir);
    }

    /** character.json이 있으면 "기본" 프리셋으로, 없으면 빈 기본 하나로. */
    private static Store migrateOrDefault(Path dataDir) {
        StatBlock stats = new StatBlock();
        try {
            Map<String, Object> ch = Json.asMap(Json.parse(
                    Files.readString(dataDir.resolve("character.json"))));
            stats = parseStats(ch.get("stats"));
        } catch (Exception ignored) {
            // character.json 없음/손상 → 빈 스탯
        }
        List<Preset> presets = new ArrayList<>();
        presets.add(new Preset(DEFAULT_NAME, stats));
        return new Store(presets, DEFAULT_NAME);
    }

    /** stats 맵(JSON) → StatBlock. 숫자값만 취한다. */
    private static StatBlock parseStats(Object statsObj) {
        StatBlock b = new StatBlock();
        if (statsObj == null) return b;
        for (Map.Entry<String, Object> e : Json.asMap(statsObj).entrySet()) {
            if (e.getValue() instanceof Double) b.with(e.getKey(), (Double) e.getValue());
        }
        return b;
    }

    private static int indexOf(List<Preset> presets, String name) {
        for (int i = 0; i < presets.size(); i++) if (presets.get(i).name().equals(name)) return i;
        return -1;
    }

    /** presets.json으로 저장. 각 프리셋 stats는 statOrder 순서대로 모든 스탯을 기록(0 포함). */
    public static void save(Path dataDir, Store store, Iterable<String> statOrder) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_comment\": \"스탯 프리셋. 여러 개의 이름 있는 스탯 세트. active=현재 활성 프리셋. 큐브 옵션은 별도(단일).\",\n");
        sb.append("  \"active\": \"").append(esc(store.active())).append("\",\n");
        sb.append("  \"presets\": [\n");
        List<Preset> presets = store.presets();
        for (int i = 0; i < presets.size(); i++) {
            Preset p = presets.get(i);
            sb.append("    { \"name\": \"").append(esc(p.name())).append("\", \"stats\": {\n");
            StringBuilder rows = new StringBuilder();
            for (String id : statOrder) {
                if (rows.length() > 0) rows.append(",\n");
                rows.append("      \"").append(id).append("\": ").append(fmt(p.stats().get(id)));
            }
            sb.append(rows).append("\n    } }");
            sb.append(i < presets.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        Files.writeString(dataDir.resolve(FILE), sb.toString());
    }

    /** JSON 문자열 이스케이프(역슬래시·따옴표). */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String fmt(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v))
                ? String.valueOf((long) v)
                : String.valueOf(v);
    }
}
