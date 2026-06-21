package mekicalc.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 전체 백업/복원. 스탯 프리셋(presets.json) + 큐브 프리셋(cube_presets.json, 추천 범위 인라인 포함)
 * + 딜 환산(conversions.json)을 하나의 JSON 문자열로 묶고, 그대로 복원한다.
 *
 * <p>파일 저장/열기와 텍스트(클립보드) 양쪽에 같은 문자열을 쓴다. 복원은 각 파일을 덮어쓴 뒤
 * {@code AppModel.reload()}로 메모리에 반영한다(전체 덮어쓰기 = 복원).
 */
public final class BackupIO {

    public static final int VERSION = 1;
    /** 백업 파일 기본 확장자(자유 형식의 JSON). */
    public static final String EXT = "mkbak";

    private static final String[] SECTIONS = {"statPresets", "cubePresets", "conversions"};
    private static final String[] FILES = {PresetIO.FILE, CubePresetIO.FILE, ConversionIO.FILE};

    private BackupIO() {}

    /** 현재 데이터 폴더의 프리셋·설정을 백업 JSON 문자열로 직렬화. */
    public static String export(Path dataDir) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_comment", "MekiCalc 백업 — 스탯 프리셋 + 큐브 프리셋(추천 범위 포함) + 딜 환산 설정");
        root.put("version", (double) VERSION);
        for (int i = 0; i < SECTIONS.length; i++) {
            Path f = dataDir.resolve(FILES[i]);
            if (Files.exists(f)) {
                try {
                    root.put(SECTIONS[i], Json.parse(Files.readString(f)));
                } catch (Exception ignored) {
                    // 손상 파일은 섹션 생략
                }
            }
        }
        return Json.write(root);
    }

    /**
     * 백업 문자열을 데이터 폴더에 복원(각 파일 덮어쓰기). 백업에 없는 섹션은 건드리지 않는다.
     * 형식이 올바르지 않거나 복원할 섹션이 하나도 없으면 IOException.
     */
    public static void restore(Path dataDir, String content) throws IOException {
        Object parsed;
        try {
            parsed = Json.parse(content);
        } catch (Exception e) {
            throw new IOException("올바른 백업 형식이 아닙니다.");
        }
        if (!(parsed instanceof Map)) throw new IOException("올바른 백업 형식이 아닙니다.");
        Map<String, Object> root = Json.asMap(parsed);
        int restored = 0;
        for (int i = 0; i < SECTIONS.length; i++) {
            Object section = root.get(SECTIONS[i]);
            if (section == null) continue;
            Files.writeString(dataDir.resolve(FILES[i]), Json.write(section));
            restored++;
        }
        if (restored == 0) throw new IOException("백업에서 복원할 데이터를 찾지 못했습니다.");
    }
}
