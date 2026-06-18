package mekicalc.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 정성적 스탯 · 스킬 레벨의 <b>커스텀 딜 환산</b> 설정 저장/로드. 파일: conversions.json.
 *
 * <p>전체 프리셋 공용(단일 파일)이다. 각 스탯마다:
 * <ul>
 *   <li>{@code enabled=true}  → 그 스탯을 {@code (1 + perUnit/100 × 스탯값)} 곱연산으로 환산해
 *       비교·추천에 반영한다(정성적 스탯이라도 더 이상 "계산 불가"가 아니다).</li>
 *   <li>{@code enabled=false} → 디폴트 처리(정성적=계산 안 함, 스킬 레벨=기본 공격 계수 공식).
 *       값(perUnit)이 들어 있어도 무시한다.</li>
 * </ul>
 * 파일이 없거나 항목이 없으면 모두 디폴트로 간주한다.
 */
public final class ConversionIO {

    public static final String FILE = "conversions.json";

    /** 커스텀 환산이 가능한 스킬 레벨 스탯 id(1~4차 + 모든 스킬 레벨). */
    public static final List<String> SKILL_LEVEL_IDS = List.of(
            "SKILL_LEVEL_1", "SKILL_LEVEL_2", "SKILL_LEVEL_3", "SKILL_LEVEL_4", "SKILL_LEVELS");

    /** 한 스탯의 환산 설정: 사용 여부 + 1단위당 딜 증가율(%). */
    public record Conv(boolean enabled, double perUnit) {
        public static final Conv DEFAULT = new Conv(false, 0);
    }

    private ConversionIO() {}

    /** conversions.json을 statId→Conv 맵으로 로드. 없거나 손상 시 빈 맵(모두 디폴트). */
    public static Map<String, Conv> load(Path dataDir) {
        Map<String, Conv> out = new LinkedHashMap<>();
        Path f = dataDir.resolve(FILE);
        if (!Files.exists(f)) return out;
        try {
            Map<String, Object> root = Json.asMap(Json.parse(Files.readString(f)));
            for (Map.Entry<String, Object> e : root.entrySet()) {
                if (e.getKey().startsWith("_")) continue;
                Map<String, Object> m = Json.asMap(e.getValue());
                out.put(e.getKey(), new Conv(
                        Json.bool(m, "enabled", false),
                        Json.num(m, "perUnit", 0.0)));
            }
        } catch (Exception ignored) {
            // 손상된 파일은 빈 상태(모두 디폴트)로 시작
        }
        return out;
    }

    /** 비교/데미지 모델이 쓰는 형태: 사용(enabled)인 항목만 statId→perUnit 으로. */
    public static Map<String, Double> enabledPerUnit(Path dataDir) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Conv> e : load(dataDir).entrySet()) {
            if (e.getValue().enabled()) out.put(e.getKey(), e.getValue().perUnit());
        }
        return out;
    }

    public static void save(Path dataDir, Map<String, Conv> map) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_comment\": \"정성적·스킬레벨 스탯의 커스텀 딜 환산. enabled=true면 (1 + perUnit/100 × 스탯값) 곱연산, false면 디폴트.\"");
        for (Map.Entry<String, Conv> e : map.entrySet()) {
            Conv c = e.getValue();
            sb.append(",\n  \"").append(e.getKey()).append("\": { ");
            sb.append("\"enabled\": ").append(c.enabled());
            sb.append(", \"perUnit\": ").append(fmt(c.perUnit()));
            sb.append(" }");
        }
        sb.append("\n}\n");
        Files.writeString(dataDir.resolve(FILE), sb.toString());
    }

    private static String fmt(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
