package mekicalc.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import mekicalc.json.Json;

/**
 * PostgreSQL 접속 설정 + 커넥션 제공. 파일: data/db.json (개인/비밀 — gitignore).
 *
 * <pre>
 * { "host": "127.0.0.1", "port": 5432, "database": "mekicalc", "user": "mekicalc", "password": "..." }
 * </pre>
 *
 * <p>파일이 없거나 필수값(database·user)이 비면 DB OFF({@link #enabled}=false) → AuthService가 파일 저장 모드로 폴백.
 * 커넥션 풀 없이 작업당 {@link #open()}으로 열고 닫는다(저트래픽 자가호스팅에 충분, JDBC 입문에 적합).
 */
public final class Db {

    public final boolean enabled;
    private final String url;
    private final Properties props;

    private Db(boolean enabled, String url, Properties props) {
        this.enabled = enabled;
        this.url = url;
        this.props = props;
    }

    private static Db disabled() { return new Db(false, null, null); }

    public static Db load(Path dataDir) {
        Path f = dataDir.resolve("db.json");
        if (!Files.exists(f)) return disabled();
        try {
            Map<String, Object> m = Json.asMap(Json.parse(Files.readString(f)));
            String host = str(m, "host", "127.0.0.1");
            String port = intStr(m.get("port"), "5432");
            String database = str(m, "database", "");
            String user = str(m, "user", "");
            String pw = str(m, "password", "");
            if (database.isEmpty() || user.isEmpty()) return disabled();
            String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
            Properties p = new Properties();
            p.setProperty("user", user);
            p.setProperty("password", pw);
            return new Db(true, url, p);
        } catch (Exception e) {
            System.err.println("db.json 읽기 실패, 파일 모드로 폴백: " + e);
            return disabled();
        }
    }

    /** 새 커넥션(호출자가 try-with-resources로 닫는다). */
    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, props);
    }

    /**
     * DB가 응답할 때까지 최대 maxSeconds 대기(스키마 보장 겸). 헤드리스 부팅에서 웹서버 작업이
     * Docker/Postgres보다 먼저 떠도 안전하게 붙도록 재시도한다. 성공=true, 타임아웃=false(파일 폴백).
     */
    public boolean waitReady(int maxSeconds) {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        int attempt = 0;
        while (true) {
            try {
                ensureSchema();
                if (attempt > 0) System.out.println("DB 연결 성공(" + attempt + "회 재시도 후).");
                return true;
            } catch (SQLException e) {
                attempt++;
                if (System.currentTimeMillis() >= deadline) {
                    System.err.println("DB 대기 타임아웃(" + attempt + "회 시도): " + e.getMessage());
                    return false;
                }
                System.out.println("DB 대기 중...(시도 " + attempt + ") " + e.getMessage());
                try { Thread.sleep(3000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
            }
        }
    }

    /** 테이블/인덱스 없으면 생성(재시작·이식에도 안전). */
    public void ensureSchema() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "sub TEXT PRIMARY KEY, name TEXT, email TEXT, "
                    + "bundle JSONB NOT NULL DEFAULT '{}'::jsonb, "
                    + "updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            st.execute("CREATE TABLE IF NOT EXISTS sessions ("
                    + "token TEXT PRIMARY KEY, sub TEXT NOT NULL, name TEXT, email TEXT, "
                    + "expires_at TIMESTAMPTZ NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS sessions_expires_idx ON sessions (expires_at)");
        }
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : v.toString();
    }

    /** JSON 숫자는 Double로 파싱되므로(예: 5432.0) 정수 문자열로 정규화. */
    private static String intStr(Object v, String def) {
        if (v == null) return def;
        if (v instanceof Double) return String.valueOf((long) (double) (Double) v);
        return v.toString();
    }
}
