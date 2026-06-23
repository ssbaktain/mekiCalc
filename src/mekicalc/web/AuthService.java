package mekicalc.web;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mekicalc.json.Json;

/**
 * OAuth 로그인 + 세션 + 유저별 프리셋 저장(서버 동기화). 무의존(JDK 내장 {@link HttpClient}).
 *
 * <p>흐름(Google): /auth/login → 인증 URL 리다이렉트 → /auth/callback(code) → 토큰 교환 → userinfo(sub)
 * → 세션 토큰 쿠키 발급. 세션은 파일 영속(서버 재시작에도 로그인 유지). 유저 프리셋은
 * data/users/&lt;sub&gt;.json(불투명 번들, .mkbak와 동일 형식)으로 저장. 모두 개인 데이터(gitignore·빌드 제외).
 */
public final class AuthService {

    /** 로그인 세션. */
    public record Session(String token, String sub, String name, String email, long expiresAt) {}

    private static final long SESSION_DAYS = 30;

    private final OAuthConfig cfg;
    private final Path sessionsFile;   // data/sessions.json (파일 모드)
    private final Path usersDir;       // data/users/ (파일 모드)
    private final PgAuthStore pg;      // null이면 파일 모드, 아니면 DB 모드
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private final SecureRandom rng = new SecureRandom();

    public AuthService(OAuthConfig cfg, Path dataDir) { this(cfg, dataDir, null); }

    public AuthService(OAuthConfig cfg, Path dataDir, PgAuthStore pg) {
        this.cfg = cfg;
        this.sessionsFile = dataDir.resolve("sessions.json");
        this.usersDir = dataDir.resolve("users");
        this.pg = pg;
        if (pg != null) sessions.putAll(pg.loadSessions());   // DB: 시작 시 세션 캐시 적재
        else loadSessions();                                  // 파일: sessions.json
    }

    public OAuthConfig config() { return cfg; }
    public boolean enabled() { return cfg.enabled; }

    /** 무작위 URL-safe 토큰(state/세션용). */
    public String randomToken() {
        byte[] b = new byte[32];
        rng.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Google 인증 URL(state CSRF 포함). */
    public String authorizeUrl(String state) {
        return "https://accounts.google.com/o/oauth2/v2/auth?response_type=code"
                + "&client_id=" + enc(cfg.clientId)
                + "&redirect_uri=" + enc(cfg.redirectUri())
                + "&scope=" + enc("openid email profile")
                + "&state=" + enc(state)
                + "&access_type=online&prompt=select_account";
    }

    /** code 교환 + userinfo → 세션 생성(토큰 발급). 실패 시 예외. */
    public Session completeLogin(String code) throws Exception {
        // 1) 인가 코드 → 액세스 토큰
        String form = "code=" + enc(code)
                + "&client_id=" + enc(cfg.clientId)
                + "&client_secret=" + enc(cfg.clientSecret)
                + "&redirect_uri=" + enc(cfg.redirectUri())
                + "&grant_type=authorization_code";
        HttpResponse<String> tr = http.send(
                HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, Object> tok = Json.asMap(Json.parse(tr.body()));
        Object access = tok.get("access_token");
        if (access == null) throw new IllegalStateException("토큰 교환 실패");

        // 2) userinfo (sub=고유 ID)
        HttpResponse<String> ur = http.send(
                HttpRequest.newBuilder(URI.create("https://openidconnect.googleapis.com/v1/userinfo"))
                        .header("Authorization", "Bearer " + access)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, Object> ui = Json.asMap(Json.parse(ur.body()));
        Object sub = ui.get("sub");
        if (sub == null) throw new IllegalStateException("사용자 정보 조회 실패");
        String email = ui.get("email") != null ? ui.get("email").toString() : "";
        String name = ui.get("name") != null ? ui.get("name").toString() : email;

        Session s = new Session(randomToken(), sub.toString(), name, email,
                System.currentTimeMillis() + SESSION_DAYS * 24 * 3600_000L);
        sessions.put(s.token(), s);
        if (pg != null) { pg.upsertUser(s.sub(), s.name(), s.email()); pg.putSession(s); }
        else saveSessions();
        return s;
    }

    /** 토큰으로 유효 세션 조회(만료 시 제거 후 null). */
    public Session session(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (s.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(token);
            if (pg != null) pg.deleteSession(token); else saveSessions();
            return null;
        }
        return s;
    }

    public void logout(String token) {
        if (token != null && sessions.remove(token) != null) {
            if (pg != null) pg.deleteSession(token); else saveSessions();
        }
    }

    // ──────────────────── 유저 프리셋(서버 동기화) ────────────────────

    /** 유저의 저장된 프리셋 번들(JSON 문자열). 없으면 null. */
    public String loadPresets(String sub) {
        if (pg != null) return pg.loadBundle(sub);
        try {
            Path f = usersDir.resolve(safe(sub) + ".json");
            if (Files.exists(f)) return Files.readString(f);
        } catch (Exception ignored) { }
        return null;
    }

    /** 유저의 프리셋 번들 저장(불투명 JSON 문자열 그대로). */
    public void savePresets(String sub, String bundleJson) throws Exception {
        if (pg != null) { pg.saveBundle(sub, bundleJson); return; }
        Files.createDirectories(usersDir);
        Files.writeString(usersDir.resolve(safe(sub) + ".json"), bundleJson);
    }

    private static String safe(String sub) {
        return sub.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    // ──────────────────── 세션 영속 ────────────────────

    private void loadSessions() {
        try {
            if (!Files.exists(sessionsFile)) return;
            for (Object o : Json.asList(Json.parse(Files.readString(sessionsFile)))) {
                Map<String, Object> m = Json.asMap(o);
                String token = str(m, "token");
                Session s = new Session(token, str(m, "sub"), str(m, "name"), str(m, "email"),
                        (long) Json.num(m, "expiresAt", 0));
                if (token != null && s.expiresAt() > System.currentTimeMillis()) sessions.put(token, s);
            }
        } catch (Exception ignored) { }
    }

    private void saveSessions() {
        try {
            List<Object> list = new ArrayList<>();
            for (Session s : sessions.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("token", s.token());
                m.put("sub", s.sub());
                m.put("name", s.name());
                m.put("email", s.email());
                m.put("expiresAt", (double) s.expiresAt());
                list.add(m);
            }
            Files.writeString(sessionsFile, Json.write(list));
        } catch (Exception ignored) { }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
