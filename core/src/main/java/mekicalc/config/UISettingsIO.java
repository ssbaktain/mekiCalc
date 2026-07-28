package mekicalc.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 화면 설정(폰트 배율 · 창 크기) 저장/로드. 파일: ui.json. (전체 공용)
 *
 * <ul>
 *   <li>{@code fontScale} — 전체 UI 폰트 배율(1.0 = 100%).</li>
 *   <li>{@code windowMode} — "percent"(기준 크기의 배율) 또는 "pixel"(직접 픽셀 지정).</li>
 *   <li>{@code windowPercent} — percent 모드의 배율(1.0 = 100%).</li>
 *   <li>{@code windowW / windowH} — pixel 모드의 창 크기(px). 0이면 미설정.</li>
 * </ul>
 */
public final class UISettingsIO {

    public static final String FILE = "ui.json";

    public static final String MODE_PERCENT = "percent";
    public static final String MODE_PIXEL = "pixel";

    public record UISettings(double fontScale, String windowMode, double windowPercent, int windowW, int windowH) {
        public static final UISettings DEFAULT = new UISettings(1.0, MODE_PERCENT, 1.0, 0, 0);
    }

    private UISettingsIO() {}

    public static UISettings load(Path dataDir) {
        Path f = dataDir.resolve(FILE);
        if (!Files.exists(f)) return UISettings.DEFAULT;
        try {
            Map<String, Object> m = Json.asMap(Json.parse(Files.readString(f)));
            double fs = clamp(Json.num(m, "fontScale", 1.0), 0.5, 3.0);
            String mode = Json.str(m, "windowMode", MODE_PERCENT);
            if (!MODE_PIXEL.equals(mode)) mode = MODE_PERCENT;
            double wp = clamp(Json.num(m, "windowPercent", 1.0), 0.5, 3.0);
            int ww = (int) Json.num(m, "windowW", 0.0);
            int wh = (int) Json.num(m, "windowH", 0.0);
            return new UISettings(fs, mode, wp, ww, wh);
        } catch (Exception ignored) {
            return UISettings.DEFAULT;
        }
    }

    public static void save(Path dataDir, UISettings s) throws IOException {
        String json = "{\n"
                + "  \"_comment\": \"화면 설정. fontScale=폰트 배율, windowMode=percent|pixel, windowPercent=창 배율, windowW/H=픽셀.\",\n"
                + "  \"fontScale\": " + fmt(s.fontScale()) + ",\n"
                + "  \"windowMode\": \"" + s.windowMode() + "\",\n"
                + "  \"windowPercent\": " + fmt(s.windowPercent()) + ",\n"
                + "  \"windowW\": " + s.windowW() + ",\n"
                + "  \"windowH\": " + s.windowH() + "\n"
                + "}\n";
        Files.writeString(dataDir.resolve(FILE), json);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmt(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
