package mekicalc.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import mekicalc.json.Json;

/**
 * OAuth 로그인 설정(시크릿). 파일: data/oauth.json (개인/비밀 — gitignore·빌드 제외).
 *
 * <pre>
 * {
 *   "provider": "google",
 *   "clientId": "...",
 *   "clientSecret": "...",
 *   "baseUrl": "https://mekicalc.com"      // 로컬 테스트는 "http://localhost:8080"
 * }
 * </pre>
 *
 * <p>파일이 없거나 값이 비면 로그인 기능 OFF({@link #enabled}=false) — 빌드/개발 환경 안전.
 */
public final class OAuthConfig {

    public final boolean enabled;
    public final String provider;
    public final String clientId;
    public final String clientSecret;
    public final String baseUrl;

    private OAuthConfig(boolean enabled, String provider, String clientId, String clientSecret, String baseUrl) {
        this.enabled = enabled;
        this.provider = provider;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.baseUrl = baseUrl;
    }

    private static OAuthConfig disabled() {
        return new OAuthConfig(false, "", "", "", "");
    }

    public static OAuthConfig load(Path dataDir) {
        Path f = dataDir.resolve("oauth.json");
        if (!Files.exists(f)) return disabled();
        try {
            Map<String, Object> m = Json.asMap(Json.parse(Files.readString(f)));
            String cid = s(m, "clientId"), sec = s(m, "clientSecret"), base = s(m, "baseUrl");
            if (cid.isEmpty() || sec.isEmpty() || base.isEmpty()) return disabled();
            String prov = m.get("provider") != null ? m.get("provider").toString() : "google";
            // baseUrl 끝 슬래시 제거
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            return new OAuthConfig(true, prov, cid, sec, base);
        } catch (Exception e) {
            return disabled();
        }
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    /** Google에 등록한 리디렉션 URI. */
    public String redirectUri() { return baseUrl + "/auth/callback"; }

    /** HTTPS 환경이면 Secure 쿠키 사용(로컬 http 테스트에선 끔). */
    public boolean secureCookies() { return baseUrl.startsWith("https"); }
}
