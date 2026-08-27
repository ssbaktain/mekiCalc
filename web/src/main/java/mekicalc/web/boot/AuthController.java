package mekicalc.web.boot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import mekicalc.json.Json;
import mekicalc.web.AuthService;
import mekicalc.web.BanStore;
import mekicalc.web.CalcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인(OAuth)·세션·유저 프리셋·부트스트랩 컨트롤러. 구 {@code WebServer}의 /auth/*·/api/me·
 * /api/bootstrap·/api/presets 라우트와 1:1. 쿠키/리다이렉트 문자열은 구 서버와 동일하게 만든다.
 */
@RestController
public class AuthController {

    private final CalcService service;
    private final AuthService auth;
    private final BanStore bans;

    public AuthController(CalcService service, AuthService auth, BanStore bans) {
        this.service = service;
        this.auth = auth;
        this.bans = bans;
    }

    // ──────────────── OAuth 로그인 ────────────────

    @GetMapping("/auth/login")
    public void login(HttpServletResponse res) throws IOException {
        try {
            if (!auth.enabled()) { redirect(res, "/"); return; }
            String state = auth.randomToken();
            addCookie(res, cookie("oauth_state", state, 600));
            redirect(res, auth.authorizeUrl(state));
        } catch (Exception e) {
            redirect(res, "/?login=error");
        }
    }

    @GetMapping("/auth/callback")
    public void callback(HttpServletRequest req, HttpServletResponse res) throws IOException {
        try {
            if (!auth.enabled()) { redirect(res, "/"); return; }
            String code = req.getParameter("code"), state = req.getParameter("state"), saved = Req.cookie(req, "oauth_state");
            addCookie(res, clearCookieStr("oauth_state"));
            if (code == null || state == null || saved == null || !state.equals(saved)) {
                redirect(res, "/?login=error"); return;
            }
            AuthService.Session s = auth.completeLogin(code);
            addCookie(res, cookie("sid", s.token(), 30 * 24 * 3600));
            redirect(res, "/");
        } catch (Exception e) {
            redirect(res, "/?login=error");
        }
    }

    @RequestMapping(value = "/auth/logout", method = {org.springframework.web.bind.annotation.RequestMethod.GET,
            org.springframework.web.bind.annotation.RequestMethod.POST})
    public void logout(HttpServletRequest req, HttpServletResponse res) throws IOException {
        try {
            auth.logout(Req.cookie(req, "sid"));
            addCookie(res, clearCookieStr("sid"));
            if ("GET".equalsIgnoreCase(req.getMethod())) redirect(res, "/");
            else writeJson(res, 200, Map.of("ok", true));
        } catch (Exception e) {
            writeJson(res, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ──────────────── 세션 상태 / 부트스트랩 ────────────────

    @GetMapping("/api/me")
    public Object me(HttpServletRequest req) {
        return meInfo(sessionFrom(req));
    }

    /** 초기 로드 1회 왕복용: stats+meta+mydata+companions+artifacts+targetCatalog+me 묶음. */
    @GetMapping("/api/bootstrap")
    public Object bootstrap(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stats", service.statDefs());
        out.put("meta", service.meta());
        out.put("mydata", service.myData());
        out.put("companions", service.companions());
        out.put("companionMeta", service.companionMeta());
        out.put("artifacts", service.artifactCatalog());
        out.put("targetCatalog", service.targetCatalog());
        out.put("me", meInfo(sessionFrom(req)));
        return out;
    }

    // ──────────────── 유저 프리셋(로그인 필요, 불투명 번들) ────────────────

    @GetMapping("/api/presets")
    public void presetsGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        try {
            AuthService.Session s = sessionFrom(req);
            if (s == null) { writeJson(res, 401, Map.of("error", "로그인 필요")); return; }
            String bundle = auth.loadPresets(s.sub());
            byte[] out = (bundle != null ? bundle : "{}").getBytes(StandardCharsets.UTF_8);
            res.setStatus(200);
            res.setHeader("Content-Type", "application/json; charset=utf-8");
            res.setContentLength(out.length);
            try (OutputStream os = res.getOutputStream()) { os.write(out); }
        } catch (Exception e) {   // 구 서버는 presets 전 경로를 하나의 try로 감싸 예외를 400으로 응답.
            writeJson(res, 400, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PutMapping("/api/presets")
    public void presetsPut(HttpServletRequest req, HttpServletResponse res) throws IOException {
        savePresets(req, res);
    }

    @PostMapping("/api/presets")
    public void presetsPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        savePresets(req, res);
    }

    private void savePresets(HttpServletRequest req, HttpServletResponse res) throws IOException {
        try {
            AuthService.Session s = sessionFrom(req);
            if (s == null) { writeJson(res, 401, Map.of("error", "로그인 필요")); return; }
            String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Json.parse(body);   // JSON 검증
            auth.savePresets(s.sub(), body);
            writeJson(res, 200, Map.of("ok", true));
        } catch (Exception e) {
            writeJson(res, 400, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ──────────────── 헬퍼(구 WebServer와 동일) ────────────────

    private AuthService.Session sessionFrom(HttpServletRequest req) {
        return auth.session(Req.cookie(req, "sid"));
    }

    private Map<String, Object> meInfo(AuthService.Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("oauthEnabled", auth.enabled());
        m.put("loggedIn", s != null);
        if (s != null) { m.put("name", s.name()); m.put("email", s.email()); }
        m.put("isAdmin", auth.isAdmin(s));
        // 계정 정지 상태(계정 단위 차단). 정지 중이면 프론트가 전체 기능 차단 + 안내문 표시, 로그인 기능은 서버가 거부.
        BanStore.Ban ban = s == null ? null : bans.activeBan(s.sub(), s.email());
        m.put("banned", ban != null);
        if (ban != null) {
            m.put("banMessage", ban.displayMessage());
            m.put("banUntil", ban.banUntil == 0 ? null : (double) ban.banUntil);
        }
        return m;
    }

    private String cookie(String name, String value, int maxAgeSec) {
        String s = name + "=" + value + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAgeSec;
        return auth.config().secureCookies() ? s + "; Secure" : s;
    }

    private String clearCookieStr(String name) {
        String s = name + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0";
        return auth.config().secureCookies() ? s + "; Secure" : s;
    }

    private static void addCookie(HttpServletResponse res, String cookie) {
        res.addHeader("Set-Cookie", cookie);
    }

    private static void redirect(HttpServletResponse res, String location) {
        res.setStatus(302);
        res.setHeader("Location", location);
    }

    private static void writeJson(HttpServletResponse res, int status, Object body) throws IOException {
        byte[] out = Json.write(body).getBytes(StandardCharsets.UTF_8);
        res.setStatus(status);
        res.setHeader("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = res.getOutputStream()) { os.write(out); }
    }
}
