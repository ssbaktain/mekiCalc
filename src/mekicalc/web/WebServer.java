package mekicalc.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import mekicalc.config.RangeIO;
import mekicalc.json.Json;

/**
 * 의존성 없는 웹 서버(JDK 내장 {@link HttpServer}). 정적 파일(web/) + JSON API(/api/*) 제공.
 *
 * <p>API는 모두 무상태: 요청 본문에 입력을 받아 {@link CalcService}로 계산하고 결과 JSON만 돌려준다.
 * 직렬화/역직렬화는 기존 미니 파서({@link Json})를 재사용한다(무의존 유지).
 */
public final class WebServer {

    private final int port;
    private final CalcService service;
    private final Path webDir;
    private final AuthService auth;
    private final EventLog log;
    private HttpServer http;

    public WebServer(int port, CalcService service, Path webDir, AuthService auth, EventLog log) {
        this.port = port;
        this.service = service;
        this.webDir = webDir;
        this.auth = auth;
        this.log = log;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newFixedThreadPool(16));

        http.createContext("/api/stats", json("GET", ex -> service.statDefs()));
        http.createContext("/api/meta", json("GET", ex -> service.meta()));
        http.createContext("/api/mydata", json("GET", ex -> service.myData()));
        http.createContext("/api/qualitative", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.qualitativeMarkers((int) num(req, "job", 0), toDoubleMap(req.get("conversions")));
        }));

        http.createContext("/api/damage", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            Object cid = req.get("companionId"), gr = req.get("grade");
            return service.previewDamage(toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")),
                    toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                    cid == null ? null : cid.toString(), gr == null ? null : gr.toString(),
                    (int) num(req, "level", 0), num(req, "ratio", 0));
        }));

        http.createContext("/api/buffs", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.buffList(toDoubleMap(req.get("stats")));
        }));

        http.createContext("/api/evaluate", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.evaluate(
                    str(req, "part"), str(req, "grade"), intOrNull(req, "slot"),
                    toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), bool(req, "bossMode", true),
                    toStrSet(req.get("buffs")), num(req, "hitInterval", 0));
        }));

        http.createContext("/api/recommend", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.recommend(
                    toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), bool(req, "bossMode", true),
                    req.get("cubeState"), RangeIO.parseContentMap(req.get("ranges")),
                    (int) num(req, "sliderValue", 0), (int) num(req, "sortKey", 0),
                    bool(req, "upper", true), bool(req, "lower", true), toStrSet(req.get("buffs")), num(req, "hitInterval", 0));
        }));

        http.createContext("/api/compare", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.myCompare(
                    toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), bool(req, "bossMode", true),
                    req.get("cubeState"), RangeIO.parseContentMap(req.get("ranges")),
                    str(req, "part"), bool(req, "upper", true), str(req, "rightGrade"), mapList(req.get("rightOpts")),
                    toStrSet(req.get("buffs")), num(req, "hitInterval", 0));
        }));

        http.createContext("/api/slotdraws", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.slotDraws(str(req, "part"), str(req, "grade"));
        }));

        http.createContext("/api/applystats", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.applyStats(toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")),
                    mapList(req.get("oldOpts")), mapList(req.get("newOpts")));
        }));

        http.createContext("/api/optcompare", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            Map<String, Object> l = asObj(req.get("left")), r = asObj(req.get("right"));
            return service.optCompare(
                    str(l, "part"), str(l, "grade"), mapList(l.get("opts")),
                    str(r, "part"), str(r, "grade"), mapList(r.get("opts")),
                    toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), bool(req, "bossMode", true),
                    toStrSet(req.get("buffs")), num(req, "hitInterval", 0));
        }));

        // ──────────────────── 로그인(OAuth) ────────────────────
        http.createContext("/auth/login", ex -> {
            try {
                if (!auth.enabled()) { redirect(ex, "/"); return; }
                String state = auth.randomToken();
                addCookie(ex, cookie("oauth_state", state, 600));
                redirect(ex, auth.authorizeUrl(state));
            } catch (Exception e) {
                redirect(ex, "/?login=error");
            } finally { ex.close(); }
        });

        http.createContext("/auth/callback", ex -> {
            try {
                if (!auth.enabled()) { redirect(ex, "/"); return; }
                Map<String, String> q = queryParams(ex);
                String code = q.get("code"), state = q.get("state"), saved = getCookie(ex, "oauth_state");
                addCookie(ex, clearCookieStr("oauth_state"));
                if (code == null || state == null || saved == null || !state.equals(saved)) {
                    redirect(ex, "/?login=error"); return;
                }
                AuthService.Session s = auth.completeLogin(code);
                addCookie(ex, cookie("sid", s.token(), 30 * 24 * 3600));
                redirect(ex, "/");
            } catch (Exception e) {
                redirect(ex, "/?login=error");
            } finally { ex.close(); }
        });

        http.createContext("/auth/logout", ex -> {
            try {
                auth.logout(getCookie(ex, "sid"));
                addCookie(ex, clearCookieStr("sid"));
                if ("GET".equalsIgnoreCase(ex.getRequestMethod())) redirect(ex, "/");
                else sendJson(ex, 200, Map.of("ok", true));
            } catch (Exception e) {
                sendJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
            } finally { ex.close(); }
        });

        http.createContext("/api/me", json("GET", ex -> meInfo(sessionFrom(ex))));

        // 초기 로드 1회 왕복용: stats+meta+mydata+me를 한 응답으로 묶음(프론트 init의 순차 호출 제거).
        http.createContext("/api/bootstrap", json("GET", ex -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stats", service.statDefs());
            out.put("meta", service.meta());
            out.put("mydata", service.myData());
            out.put("companions", service.companions());
            out.put("monsterDefense", service.monsterDefense());
            out.put("me", meInfo(sessionFrom(ex)));
            return out;
        }));

        // 동료 공격력 계승 비율 데이터 수집(저장 시). 추후 비율 공식 모델링용.
        http.createContext("/api/ally-ratio", json("POST", ex -> service.recordAllyRatio(readJsonBody(ex))));

        // 메인 동료 DPS 기여 미리보기(Stage 1: 계승·가동률·동료 평타).
        http.createContext("/api/companion-dps", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.companionPreview(toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")),
                    bool(req, "bossMode", true), str(req, "companionId"), str(req, "grade"),
                    (int) num(req, "level", 1), num(req, "ratio", 0), toStrSet(req.get("buffs")));
        }));

        // 유저 프리셋 저장/로드(로그인 필요). 본문은 불투명 번들 JSON(.mkbak 형식).
        http.createContext("/api/presets", ex -> {
            try {
                AuthService.Session s = sessionFrom(ex);
                if (s == null) { sendJson(ex, 401, Map.of("error", "로그인 필요")); return; }
                String method = ex.getRequestMethod();
                if ("GET".equalsIgnoreCase(method)) {
                    String bundle = auth.loadPresets(s.sub());
                    byte[] out = (bundle != null ? bundle : "{}").getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    ex.sendResponseHeaders(200, out.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(out); }
                } else if ("PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method)) {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    Json.parse(body);   // JSON 검증
                    auth.savePresets(s.sub(), body);
                    sendJson(ex, 200, Map.of("ok", true));
                } else {
                    sendJson(ex, 405, Map.of("error", "허용되지 않은 메서드"));
                }
            } catch (Exception e) {
                sendJson(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
            } finally { ex.close(); }
        });

        http.createContext("/", this::serveStatic);   // 정적 파일(나머지 전부)

        http.start();
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    // ──────────────────── API 헬퍼 ────────────────────

    /** JSON 응답 핸들러를 만든다. 본문 처리 함수가 던지면 400, 내부 오류는 500. */
    private interface JsonAction { Object handle(HttpExchange ex) throws Exception; }

    private HttpHandler json(String method, JsonAction action) {
        return ex -> {
            long start = System.nanoTime();
            String reqMethod = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            int status = 200;
            try {
                if (!method.equalsIgnoreCase(reqMethod)) {
                    status = 405;
                    sendJson(ex, 405, Map.of("error", "허용되지 않은 메서드"));
                    return;
                }
                Object body = action.handle(ex);
                sendJson(ex, 200, body);
            } catch (IllegalArgumentException bad) {
                status = 400;
                sendJson(ex, 400, Map.of("error", String.valueOf(bad.getMessage())));
            } catch (Exception e) {
                status = 500;
                log.error(reqMethod, path, userOf(ex), e);
                sendJson(ex, 500, Map.of("error", "서버 오류: " + e.getMessage()));
            } finally {
                long ms = (System.nanoTime() - start) / 1_000_000;
                log.access(reqMethod, path, status, ms, userOf(ex), clientIp(ex));
                ex.close();
            }
        };
    }

    /** 로그인 상태면 유저 이메일, 아니면 null(비로그인 요청 식별). 세션은 인메모리 조회라 가볍다. */
    private String userOf(HttpExchange ex) {
        AuthService.Session s = sessionFrom(ex);
        return s != null ? s.email() : null;
    }

    /** 실제 클라이언트 IP. Cloudflare 터널 뒤라 CF-Connecting-IP → X-Forwarded-For → 원격주소 순. */
    private static String clientIp(HttpExchange ex) {
        String cf = ex.getRequestHeaders().getFirst("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();
        String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return ex.getRemoteAddress() != null
                ? ex.getRemoteAddress().getAddress().getHostAddress() : null;
    }

    private static Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (raw.length == 0) return new LinkedHashMap<>();
        return Json.asMap(Json.parse(new String(raw, StandardCharsets.UTF_8)));
    }

    /** {key: number, …} JSON 객체를 statId→Double 맵으로. null/비객체면 빈 맵. */
    @SuppressWarnings("unchecked")
    private static Map<String, Double> toDoubleMap(Object o) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (!(o instanceof Map)) return out;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
            if (e.getValue() instanceof Double) out.put(e.getKey(), (Double) e.getValue());
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) throw new IllegalArgumentException("필수 키 누락: " + key);
        return v.toString();
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        return (v instanceof Boolean) ? (Boolean) v : def;
    }

    private static double num(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        return (v instanceof Double) ? (Double) v : def;
    }

    /** 정수 또는 없음(null). slot처럼 선택적 인덱스에. */
    private static Integer intOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v instanceof Double) ? (int) (double) (Double) v : null;
    }

    /** Object → Map(객체). null/비객체면 빈 맵. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObj(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    /**
     * 버프 id 배열 → Set. <b>null/비배열이면 null</b>(= 전부 적용, 하위호환). 빈 배열이면 빈 Set(= 아무것도 적용 안 함).
     * 이 null vs 빈-Set 구분이 "버프 프리셋 없음(현행 유지)"과 "버프 전부 끔"을 가른다.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> toStrSet(Object o) {
        if (!(o instanceof List)) return null;
        Set<String> out = new LinkedHashSet<>();
        for (Object el : (List<Object>) o) if (el != null) out.add(el.toString());
        return out;
    }

    /** JSON 배열(객체들)을 List&lt;Map&gt;으로. null/비배열이면 빈 리스트. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object o) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (!(o instanceof List)) return out;
        for (Object el : (List<Object>) o) {
            if (el instanceof Map) out.add((Map<String, Object>) el);
        }
        return out;
    }

    // ──────────────────── 인증/쿠키/리다이렉트 헬퍼 ────────────────────

    private AuthService.Session sessionFrom(HttpExchange ex) {
        return auth.session(getCookie(ex, "sid"));
    }

    /** /api/me·/api/bootstrap 공용 로그인 상태 객체. */
    private Map<String, Object> meInfo(AuthService.Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("oauthEnabled", auth.enabled());
        m.put("loggedIn", s != null);
        if (s != null) { m.put("name", s.name()); m.put("email", s.email()); }
        return m;
    }

    /** 쿠키 문자열 생성(HttpOnly·SameSite=Lax·Path=/, HTTPS면 Secure). */
    private String cookie(String name, String value, int maxAgeSec) {
        String s = name + "=" + value + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAgeSec;
        return auth.config().secureCookies() ? s + "; Secure" : s;
    }

    private String clearCookieStr(String name) {
        String s = name + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0";
        return auth.config().secureCookies() ? s + "; Secure" : s;
    }

    private static void addCookie(HttpExchange ex, String cookie) {
        ex.getResponseHeaders().add("Set-Cookie", cookie);
    }

    private static String getCookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers) {
            for (String c : h.split(";")) {
                String t = c.trim();
                int i = t.indexOf('=');
                if (i > 0 && t.substring(0, i).equals(name)) return t.substring(i + 1);
            }
        }
        return null;
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
    }

    private static Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> m = new LinkedHashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8),
                  URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] out = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    // ──────────────────── 정적 파일 ────────────────────

    private void serveStatic(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) path = "/index.html";
            // 경로 탈출 방지: web 디렉터리 밖으로 나가지 못하게 정규화 후 검증.
            Path target = webDir.resolve("." + path).normalize();
            if (!target.startsWith(webDir.normalize()) || !Files.isRegularFile(target)) {
                byte[] nf = "404 Not Found".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, nf.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(nf); }
                return;
            }
            String name = target.getFileName().toString().toLowerCase();
            // HTML은 자산 참조(app.js·style.css)에 콘텐츠 해시(?v=)를 주입 → 내용이 바뀌면 URL이 바뀜(캐시버스팅).
            byte[] data = name.endsWith(".html") ? htmlWithVersionedAssets(target) : Files.readAllBytes(target);
            ex.getResponseHeaders().set("Content-Type", contentType(target));
            ex.getResponseHeaders().set("Cache-Control", cacheControlFor(name));
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
        } finally {
            ex.close();
        }
    }

    /**
     * 캐시 정책:
     * <ul>
     *   <li>HTML: no-store — 항상 최신(자산 버전 스탬프를 매번 새로 전달해야 함).</li>
     *   <li>JS/CSS: 1년 immutable — URL에 콘텐츠 해시(?v=)가 붙어 바뀌면 새 URL이 되므로 안전하게 장기 캐시(엣지·브라우저).</li>
     *   <li>그 외(이미지 등): 1일.</li>
     * </ul>
     */
    private static String cacheControlFor(String name) {
        if (name.endsWith(".html")) return "no-store";
        if (name.endsWith(".js") || name.endsWith(".css")) return "public, max-age=31536000, immutable";
        return "public, max-age=86400";
    }

    /** HTML 내 /style.css·/app.js 참조 뒤에 ?v=<콘텐츠 해시>를 붙여 반환. */
    private byte[] htmlWithVersionedAssets(Path htmlFile) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        html = html.replace("/style.css\"", "/style.css?v=" + assetVer("style.css") + "\"");
        html = html.replace("/app.js\"", "/app.js?v=" + assetVer("app.js") + "\"");
        return html.getBytes(StandardCharsets.UTF_8);
    }

    /** 자산 파일의 콘텐츠 해시(앞 8자리). mtime이 같으면 캐시값 재사용(매 요청 재해시 방지). */
    private final Map<String, String[]> assetVerCache = new java.util.concurrent.ConcurrentHashMap<>();
    private String assetVer(String name) {
        try {
            Path f = webDir.resolve(name);
            String mtime = String.valueOf(Files.getLastModifiedTime(f).toMillis());
            String[] cached = assetVerCache.get(name);
            if (cached != null && cached[0].equals(mtime)) return cached[1];
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", digest[i]));
            String ver = sb.toString();
            assetVerCache.put(name, new String[]{mtime, ver});
            return ver;
        } catch (Exception e) {
            return "0";
        }
    }

    private static String contentType(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }
}
