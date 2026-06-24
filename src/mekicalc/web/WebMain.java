package mekicalc.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 웹 서버 진입점.
 *
 * 실행:
 *   java -cp out mekicalc.web.WebMain [port] [dataDir] [webDir]
 *   기본값: port=8080, dataDir=data, webDir=web
 */
public final class WebMain {

    public static void main(String[] args) throws Exception {
        // 일회성: 파일(data/users·sessions.json) → DB 이전. 사용: java ... WebMain --migrate-db [dataDir]
        if (args.length > 0 && args[0].equals("--migrate-db")) {
            migrateToDb(Path.of(args.length > 1 ? args[1] : "data"));
            return;
        }

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path dataDir = Path.of(args.length > 1 ? args[1] : "data");
        Path webDir = Path.of(args.length > 2 ? args[2] : "web");

        GameContent content = GameContent.load(dataDir);
        CalcService service = new CalcService(content);
        OAuthConfig oauth = OAuthConfig.load(dataDir);

        // 저장소: data/db.json 있으면 PostgreSQL, 없거나 초기화 실패 시 파일(data/users) 모드로 폴백.
        Db db = Db.load(dataDir);
        PgAuthStore pg = null;
        if (db.enabled) {
            // 헤드리스 부팅: Docker/Postgres가 늦게 떠도 최대 3분 기다렸다 붙음(파일모드 폴백은 최후수단).
            if (db.waitReady(180)) pg = new PgAuthStore(db);
            else System.err.println("DB 연결 실패(타임아웃), 파일 모드로 폴백");
        }
        AuthService auth = new AuthService(oauth, dataDir, pg);
        EventLog log = new EventLog(dataDir);
        WebServer server = new WebServer(port, service, webDir, auth, log);
        server.start();

        System.out.println("========================================");
        System.out.println(" MekiCalc 웹 서버 시작");
        System.out.println(" 주소: http://localhost:" + port);
        System.out.println(" 데이터: " + dataDir.toAbsolutePath());
        System.out.println(" 정적:   " + webDir.toAbsolutePath());
        System.out.println(" 로그인: " + (oauth.enabled ? oauth.provider + " (OAuth ON)" : "OFF (oauth.json 없음)"));
        System.out.println(" 저장소: " + (pg != null ? "PostgreSQL (DB)" : "파일 (data/users)"));
        System.out.println(" 종료: Ctrl + C");
        System.out.println("========================================");
    }

    /** 파일 저장본(data/users/*.json + sessions.json)을 DB로 이전(멱등: ON CONFLICT 업서트). */
    private static void migrateToDb(Path dataDir) throws Exception {
        Db db = Db.load(dataDir);
        if (!db.enabled) { System.out.println("db.json 없음 또는 비활성 — 마이그레이션 중단"); return; }
        db.ensureSchema();
        PgAuthStore pg = new PgAuthStore(db);

        int users = 0, sessions = 0;
        Path usersDir = dataDir.resolve("users");
        if (Files.isDirectory(usersDir)) {
            List<Path> files;
            try (var s = Files.list(usersDir)) { files = s.toList(); }
            for (Path f : files) {
                String fn = f.getFileName().toString();
                if (!fn.endsWith(".json")) continue;
                String sub = fn.substring(0, fn.length() - ".json".length());
                pg.saveBundle(sub, Files.readString(f));   // 번들 upsert
                users++;
            }
        }
        Path sf = dataDir.resolve("sessions.json");
        if (Files.exists(sf)) {
            for (Object o : Json.asList(Json.parse(Files.readString(sf)))) {
                Map<String, Object> m = Json.asMap(o);
                String token = s(m, "token"), sub = s(m, "sub"), name = s(m, "name"), email = s(m, "email");
                if (token == null || sub == null) continue;
                pg.upsertUser(sub, name, email);   // 세션의 이름/이메일을 users에도 채움
                pg.putSession(new AuthService.Session(token, sub, name, email, (long) Json.num(m, "expiresAt", 0)));
                sessions++;
            }
        }
        System.out.println("마이그레이션 완료: users=" + users + ", sessions=" + sessions);
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
}
