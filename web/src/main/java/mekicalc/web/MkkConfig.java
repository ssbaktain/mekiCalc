package mekicalc.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import mekicalc.json.Json;

/**
 * mkk(메이플 키우기 랭킹 사이트) 공지 푸시 설정 (파일: data/mkk.json). gitignore·배포 제외.
 *
 * <p>넥슨 포럼 공지 크롤러가 <b>새 공지를 감지하면(요약 전)</b> mkk의 공지판에 제목+원문링크만 전달한다.
 * mkk는 요약/본문을 받지 않고(요약은 mekiCalc 웹 전용), 제목 클릭 시 넥슨 원문으로 보낸다(mkk docs/board.md §4).
 *
 * <pre>
 * {
 *   "enabled": true,
 *   "ingestUrl": "http://127.0.0.1:8081/api/notices/ingest",  // mkk 백엔드 내부 포트(:8081)
 *   "secret": "&lt;mkk INTERNAL_SECRET&gt;"                    // mkk backend/.env의 INTERNAL_SECRET과 동일값
 * }
 * </pre>
 *
 * <p>파일이 없거나 secret이 비면 비활성(푸시 생략) — 안전한 no-op. mkk·mekiCalc는 같은 미니PC에서 구동.
 */
public final class MkkConfig {

    public static final String DEFAULT_INGEST_URL = "http://127.0.0.1:8081/api/notices/ingest";
    public static final String DEFAULT_COUPON_INGEST_URL = "http://127.0.0.1:8081/api/coupons/ingest";

    public boolean enabled = false;
    public String ingestUrl = DEFAULT_INGEST_URL;
    public String secret = "";

    /** 쿠폰 ingest URL — 공지 ingestUrl에서 경로만 치환(같은 mkk 백엔드, 다른 엔드포인트). 설정 불필요(파생). */
    public String couponIngestUrl() {
        return ingestUrl.contains("/notices/ingest")
            ? ingestUrl.replace("/notices/ingest", "/coupons/ingest")
            : DEFAULT_COUPON_INGEST_URL;
    }

    public static MkkConfig load(Path dataDir) {
        MkkConfig c = new MkkConfig();
        Path f = dataDir.resolve("mkk.json");
        if (!Files.exists(f)) return c;
        try {
            String text = Files.readString(f);
            if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);   // UTF-8 BOM 방어
            Map<String, Object> m = Json.asMap(Json.parse(text));
            if (m.containsKey("ingestUrl")) c.ingestUrl = s(m, "ingestUrl", DEFAULT_INGEST_URL);
            c.secret = s(m, "secret", "");
            // enabled: 명시값 우선, 없으면 secret이 있으면 자동 on
            c.enabled = m.containsKey("enabled") ? Boolean.TRUE.equals(m.get("enabled")) : !c.secret.isBlank();
        } catch (Exception e) {
            System.err.println("mkk.json 읽기 실패(공지 푸시 비활성): " + e);
        }
        return c;
    }

    private static String s(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : v.toString();
    }
}
