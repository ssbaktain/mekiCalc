package mekicalc.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 운영 일회성 CLI 유틸리티(웹 서버 진입점 아님 — 서버는 {@link mekicalc.web.boot.WebApplication}).
 *
 * <pre>
 *   java -cp &lt;web install lib&gt; mekicalc.web.AdminCli --migrate-db [dataDir]   # 파일 저장본 → DB 이전(멱등)
 *   java -cp &lt;web install lib&gt; mekicalc.web.AdminCli --snapshot   [dataDir]   # 유저 프리셋 통계 스냅샷(하루 1회 스케줄)
 * </pre>
 */
public final class AdminCli {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--migrate-db")) {
            migrateToDb(Path.of(args.length > 1 ? args[1] : "data"));
            return;
        }
        if (args.length > 0 && args[0].equals("--snapshot")) {
            StatsSnapshot.run(Path.of(args.length > 1 ? args[1] : "data"));
            return;
        }
        System.out.println("사용법: AdminCli --migrate-db [dataDir] | --snapshot [dataDir]");
        System.out.println("(웹 서버는 mekicalc.web.boot.WebApplication)");
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
