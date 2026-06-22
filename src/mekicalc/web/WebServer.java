package mekicalc.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
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
    private HttpServer http;

    public WebServer(int port, CalcService service, Path webDir) {
        this.port = port;
        this.service = service;
        this.webDir = webDir;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newFixedThreadPool(4));

        http.createContext("/api/stats", json("GET", ex -> service.statDefs()));
        http.createContext("/api/meta", json("GET", ex -> service.meta()));
        http.createContext("/api/mydata", json("GET", ex -> service.myData()));
        http.createContext("/api/qualitative", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.qualitativeMarkers((int) num(req, "job", 0), toDoubleMap(req.get("conversions")));
        }));

        http.createContext("/api/damage", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.previewDamage(toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")));
        }));

        http.createContext("/api/evaluate", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.evaluate(
                    str(req, "part"), str(req, "grade"), intOrNull(req, "slot"),
                    toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), bool(req, "bossMode", true));
        }));

        http.createContext("/api/recommend", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.recommend(
                    toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), bool(req, "bossMode", true),
                    req.get("cubeState"), RangeIO.parseContentMap(req.get("ranges")),
                    (int) num(req, "sliderValue", 0), (int) num(req, "sortKey", 0),
                    bool(req, "upper", true), bool(req, "lower", true));
        }));

        http.createContext("/api/compare", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.myCompare(
                    toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), bool(req, "bossMode", true),
                    req.get("cubeState"), RangeIO.parseContentMap(req.get("ranges")),
                    str(req, "part"), bool(req, "upper", true), str(req, "rightGrade"), mapList(req.get("rightOpts")));
        }));

        http.createContext("/api/slotdraws", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            return service.slotDraws(str(req, "part"), str(req, "grade"));
        }));

        http.createContext("/api/optcompare", json("POST", ex -> {
            Map<String, Object> req = readJsonBody(ex);
            Map<String, Object> l = asObj(req.get("left")), r = asObj(req.get("right"));
            return service.optCompare(
                    str(l, "part"), str(l, "grade"), mapList(l.get("opts")),
                    str(r, "part"), str(r, "grade"), mapList(r.get("opts")),
                    toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), bool(req, "bossMode", true));
        }));

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
            try {
                if (!method.equalsIgnoreCase(ex.getRequestMethod())) {
                    sendJson(ex, 405, Map.of("error", "허용되지 않은 메서드"));
                    return;
                }
                Object body = action.handle(ex);
                sendJson(ex, 200, body);
            } catch (IllegalArgumentException bad) {
                sendJson(ex, 400, Map.of("error", String.valueOf(bad.getMessage())));
            } catch (Exception e) {
                sendJson(ex, 500, Map.of("error", "서버 오류: " + e.getMessage()));
            } finally {
                ex.close();
            }
        };
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
            byte[] data = Files.readAllBytes(target);
            ex.getResponseHeaders().set("Content-Type", contentType(target));
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
        } finally {
            ex.close();
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
        return "application/octet-stream";
    }
}
