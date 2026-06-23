package mekicalc.web;

import java.nio.file.Path;

/**
 * 웹 서버 진입점.
 *
 * 실행:
 *   java -cp out mekicalc.web.WebMain [port] [dataDir] [webDir]
 *   기본값: port=8080, dataDir=data, webDir=web
 */
public final class WebMain {

    public static void main(String[] args) throws Exception {
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
            try { db.ensureSchema(); pg = new PgAuthStore(db); }
            catch (Exception e) { System.err.println("DB 초기화 실패, 파일 모드로 폴백: " + e); pg = null; }
        }
        AuthService auth = new AuthService(oauth, dataDir, pg);
        WebServer server = new WebServer(port, service, webDir, auth);
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
}
