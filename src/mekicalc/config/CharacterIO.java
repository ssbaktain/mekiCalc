package mekicalc.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import mekicalc.json.Json;
import mekicalc.model.StatBlock;

/**
 * 유저 개인 스탯(character.json)의 로드/세이브.
 *
 * <p>이 파일이 계산기의 1차 입력이다. 사용자는 직접 편집하거나, CLI의 {@code --input}
 * 대화형 모드로 채울 수 있다. 화면 인식/API 연동이 붙으면 이 save를 호출해 자동 생성하면 된다.
 */
public final class CharacterIO {

    private CharacterIO() {}

    /** 유저가 한 번이라도 직접 스탯을 저장했는지(첫 실행 판별). 파일 없거나 마커 없으면 false. */
    public static boolean isUserEntered(Path dataDir) {
        try {
            Map<String, Object> root = Json.asMap(Json.parse(
                    Files.readString(dataDir.resolve("character.json"))));
            return Json.bool(root, "_userEntered", false);
        } catch (Exception e) {
            return false;
        }
    }

    public static StatBlock load(Path dataDir) throws IOException {
        StatBlock b = new StatBlock();
        Map<String, Object> root = Json.asMap(Json.parse(Files.readString(dataDir.resolve("character.json"))));
        Map<String, Object> stats = Json.asMap(root.get("stats"));
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            if (e.getValue() instanceof Double) b.with(e.getKey(), (Double) e.getValue());
        }
        return b;
    }

    /**
     * 스탯 블록을 character.json으로 저장. statOrder 순서대로 모든 스탯을 기록한다(0 포함).
     * 사람이 다시 열어 편집하기 좋게 정렬해 둔다.
     */
    public static void save(Path dataDir, StatBlock block, Iterable<String> statOrder) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_comment\": \"유저 개인 스탯(표시값). --input 또는 직접 편집. 공속/방관은 현재 유효값.\",\n");
        sb.append("  \"_userEntered\": true,\n");
        sb.append("  \"stats\": {\n");
        StringBuilder rows = new StringBuilder();
        for (String id : statOrder) {
            if (rows.length() > 0) rows.append(",\n");
            rows.append("    \"").append(id).append("\": ").append(fmt(block.get(id)));
        }
        sb.append(rows).append("\n");
        sb.append("  }\n}\n");
        Files.writeString(dataDir.resolve("character.json"), sb.toString());
    }

    private static String fmt(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v))
                ? String.valueOf((long) v)
                : String.valueOf(v);
    }
}
