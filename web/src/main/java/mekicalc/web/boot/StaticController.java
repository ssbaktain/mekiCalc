package mekicalc.web.boot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mekicalc.web.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정적 파일 서빙(구 {@code WebServer.serveStatic} 이식). /api·/auth 외 모든 경로를 담당한다.
 *
 * <p>SPA 라우팅(클린 URL·index.html 폴백), 콘텐츠 해시 버전 스탬프(?v=), 라우트별 메타 주입,
 * 캐시 정책(HTML no-store / JS·CSS 1년 immutable / 그 외 1일), 표준 URL 강제(www·http→https 301), HSTS를
 * 구 서버와 동일하게 재현한다. Spring 기본 정적 핸들링은 application.properties에서 끔.
 */
@RestController
public class StaticController {

    private final Path webDir;
    private final AuthService auth;
    private final boolean devAdminBypass;   // 로컬 dev 프로파일: admin 게이팅 우회(프로덕션 false)

    public StaticController(@Value("${mekicalc.web-dir:public}") String webDir, AuthService auth,
                            @Value("${mekicalc.dev-admin-bypass:false}") boolean devAdminBypass) {
        this.webDir = Path.of(webDir);
        this.auth = auth;
        this.devAdminBypass = devAdminBypass;
    }

    @RequestMapping("/**")
    public void serve(HttpServletRequest req, HttpServletResponse res) throws IOException {
        // 표준 URL 강제: (1) 평문 http → https, (2) www → 비-www (중복 색인 방지). Cloudflare 터널이 원래 Host·스킴 보존.
        String host = hostOf(req);
        String proto = req.getHeader("X-Forwarded-Proto");
        boolean insecure = proto != null && proto.equalsIgnoreCase("http");
        boolean www = host != null && host.startsWith("www.");
        if (host != null && (insecure || www)) {
            String canonHost = www ? host.substring(4) : host;
            String q = req.getQueryString();
            String dest = "https://" + canonHost + req.getRequestURI() + (q != null ? "?" + q : "");
            res.setStatus(301);
            res.setHeader("Location", dest);
            return;
        }

        // 파일 매칭엔 디코딩된 경로 사용(구 serveStatic의 getRequestURI().getPath() 동등). 리다이렉트 목적지는 위에서 원문 URI 사용.
        String path = java.net.URLDecoder.decode(req.getRequestURI(), StandardCharsets.UTF_8);
        // /api·/auth 미매칭 경로는 정적/SPA 폴백 대상이 아님 → 404(HTML 반환·dev전용 엔드포인트 노출 방지).
        if (path.startsWith("/api/") || path.startsWith("/auth/")) { notFound(res); return; }
        // 관리자 페이지 게이팅: /admin* 은 관리자 세션만 서빙. 비관리자·비로그인은 404로 존재 자체를 숨긴다.
        // (dev 프로파일이면 로그인 없이 통과 — 로컬 UI 확인용.)
        if (path.equals("/admin") || path.equals("/admin.html") || path.startsWith("/admin/")) {
            if (!devAdminBypass && !auth.isAdmin(auth.session(Req.cookie(req, "sid")))) { notFound(res); return; }
        }
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        // 경로 탈출 방지: web 디렉터리 밖으로 나가지 못하게 정규화 후 검증.
        Path root = webDir.normalize();
        Path target = webDir.resolve("." + path).normalize();
        boolean safe = target.startsWith(root);
        // SPA 라우팅: 실제 파일이 없을 때 (1) "<path>.html" 있으면 그걸로 (2) 확장자 없으면 index.html 폴백.
        String spaRoute = null;
        if (safe && !Files.isRegularFile(target)) {
            Path html = webDir.resolve("." + path + ".html").normalize();
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (html.startsWith(root) && Files.isRegularFile(html)) target = html;
            else if (!last.contains(".")) { target = root.resolve("index.html"); spaRoute = path; }
        }
        if (!safe || !Files.isRegularFile(target)) { notFound(res); return; }
        String name = target.getFileName().toString().toLowerCase();
        byte[] data = name.endsWith(".html") ? htmlWithVersionedAssets(target, spaRoute) : Files.readAllBytes(target);
        res.setStatus(200);
        res.setHeader("Content-Type", contentType(name));
        res.setHeader("Cache-Control", cacheControlFor(name));
        res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        res.setContentLength(data.length);
        try (OutputStream os = res.getOutputStream()) { os.write(data); }
    }

    /** 404 응답(text/plain). 정적 미매칭·게이팅 실패 공용. */
    private static void notFound(HttpServletResponse res) throws IOException {
        byte[] nf = "404 Not Found".getBytes(StandardCharsets.UTF_8);
        res.setStatus(404);
        res.setHeader("Content-Type", "text/plain; charset=utf-8");
        try (OutputStream os = res.getOutputStream()) { os.write(nf); }
    }

    private static String cacheControlFor(String name) {
        if (name.endsWith(".html")) return "no-store";
        if (name.endsWith(".js") || name.endsWith(".css")) return "public, max-age=31536000, immutable";
        return "public, max-age=86400";
    }

    /** SPA 라우트별 [타이틀, 설명]. index.html 폴백 시 서버가 주입(개별 색인). 클라 ROUTE_META와 동기. */
    private static final Map<String, String[]> ROUTE_META = Map.of(
        "/cube", new String[]{"큐브 잠재능력 기댓값 - MekiCalc",
            "메이플 키우기 큐브 잠재능력 옵션별 딜 증가량과 큐브 한 개당 기댓값을 계산합니다."},
        "/cube/option-compare", new String[]{"큐브 옵션 비교 - MekiCalc",
            "두 큐브 잠재 옵션 조합의 딜 기여를 나란히 비교합니다."},
        "/cube/my-options", new String[]{"내 큐브 옵션 비교·변경 - MekiCalc",
            "현재 장착 큐브 옵션과 바꿀 옵션의 딜 차이를 비교하고 반영합니다."},
        "/cube/recommend", new String[]{"큐브 우선순위 추천 - MekiCalc",
            "현재 스탯 기준으로 어떤 큐브 옵션부터 챙기는 게 효율적인지 순위로 추천합니다."},
        "/gear/compare", new String[]{"장비 비교 - MekiCalc",
            "메이플 키우기에서 현재 장착 장비와 새 장비의 옵션을 입력해 어느 쪽이 딜에 더 유리한지 바로 비교합니다."},
        "/notices", new String[]{"공지사항 - 메이플 키우기 공지·패치노트·이벤트 | MekiCalc",
            "메이플 키우기 공식 공지사항·패치노트·이벤트·확인 중인 현상을 한 곳에서 요약과 함께 보고, 사용 가능한 쿠폰을 확인합니다."});

    private byte[] htmlWithVersionedAssets(Path htmlFile, String spaRoute) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        html = html.replace("/style.css\"", "/style.css?v=" + assetVer("style.css") + "\"");
        html = html.replace("/app.js\"", "/app.js?v=" + assetVer("app.js") + "\"");
        String[] meta = spaRoute == null ? null : ROUTE_META.get(spaRoute);
        if (meta != null) {
            html = html.replaceFirst("(?s)<title>.*?</title>",
                    java.util.regex.Matcher.quoteReplacement("<title>" + meta[0] + "</title>"));
            html = html.replaceFirst("(<meta name=\"description\" content=\")[^\"]*",
                    "$1" + java.util.regex.Matcher.quoteReplacement(meta[1]));
            html = html.replaceFirst("(<link rel=\"canonical\" href=\")[^\"]*",
                    "$1" + java.util.regex.Matcher.quoteReplacement("https://mekicalc.com" + spaRoute));
        }
        return html.getBytes(StandardCharsets.UTF_8);
    }

    /** 자산 파일의 콘텐츠 해시(앞 8자리). mtime 같으면 캐시값 재사용. */
    private final Map<String, String[]> assetVerCache = new ConcurrentHashMap<>();
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

    /** 요청 호스트(소문자, 포트 제거). Cloudflare 터널 뒤라 X-Forwarded-Host → Host 순. */
    private static String hostOf(HttpServletRequest req) {
        String h = req.getHeader("X-Forwarded-Host");
        if (h == null || h.isEmpty()) h = req.getHeader("Host");
        if (h == null) return null;
        int c = h.indexOf(':');
        return (c >= 0 ? h.substring(0, c) : h).toLowerCase();
    }

    private static String contentType(String n) {
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".xml")) return "application/xml; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }
}
