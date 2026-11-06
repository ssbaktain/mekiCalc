package mekicalc.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 쿠폰 자동 입력 설정·상태(파일: data/coupon.json). gitignore·배포 제외.
 *
 * <pre>
 * {
 *   "sidecarUrl": "http://127.0.0.1:8091",   // 쿠폰 사이드카(헤드리스 Chromium) 베이스 URL
 *   "probePin": "THANKYOUALL",               // 회원코드 검증용 "이미 존재하는" 쿠폰코드(비면 활성 상시 쿠폰 중 랜덤)
 *   "autoRedeem": false,                      // 새 쿠폰 감지 시 자동 입력 on/off
 *   "delayMinMs": 2000, "delayMaxMs": 3000,   // 회원코드 간 입력 딜레이(랜덤, 입력오류 방지)
 *   "processedPins": ["..."]                  // 자동으로 이미 처리한 쿠폰(중복 입력 방지)
 * }
 * </pre>
 *
 * <p>{@link CouponService}가 런타임 변경(autoRedeem 토글·probePin·processedPins 추가)을 이 파일에 되쓴다.
 */
public final class CouponConfig {

    public static final String DEFAULT_SIDECAR_URL = "http://127.0.0.1:8091";

    public String sidecarUrl = DEFAULT_SIDECAR_URL;
    public String probePin = "";
    public boolean autoRedeem = false;
    public int delayMinMs = 2000;
    public int delayMaxMs = 3000;
    public List<String> processedPins = new ArrayList<>();

    public static CouponConfig load(Path dataDir) {
        CouponConfig c = new CouponConfig();
        Path f = dataDir.resolve("coupon.json");
        if (!Files.exists(f)) return c;
        try {
            String text = Files.readString(f);
            if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);   // UTF-8 BOM 방어
            Map<String, Object> m = Json.asMap(Json.parse(text));
            if (m.containsKey("sidecarUrl")) c.sidecarUrl = s(m, "sidecarUrl", DEFAULT_SIDECAR_URL);
            c.probePin = s(m, "probePin", "");
            c.autoRedeem = Boolean.TRUE.equals(m.get("autoRedeem"));
            c.delayMinMs = (int) Json.num(m, "delayMinMs", 2000);
            c.delayMaxMs = (int) Json.num(m, "delayMaxMs", 3000);
            Object pp = m.get("processedPins");
            if (pp != null) for (Object o : Json.asList(pp)) if (o != null) c.processedPins.add(o.toString());
        } catch (Exception e) {
            System.err.println("coupon.json 읽기 실패(기본값 사용): " + e);
        }
        return c;
    }

    private static String s(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : v.toString();
    }
}
