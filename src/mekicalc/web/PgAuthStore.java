package mekicalc.web;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PostgreSQL 기반 세션·유저 프리셋 저장소. {@link AuthService}가 DB 모드일 때 사용한다.
 * 모든 작업은 {@link Db#open()}으로 커넥션을 열고 try-with-resources로 닫는다(작업당 커넥션).
 */
public final class PgAuthStore {

    private final Db db;

    public PgAuthStore(Db db) { this.db = db; }

    // ──────────────────── 유저 / 프리셋 ────────────────────

    /** 로그인 시 유저 행 upsert(이름·이메일 갱신, bundle은 건드리지 않음). */
    public void upsertUser(String sub, String name, String email) {
        String sql = "INSERT INTO users (sub, name, email) VALUES (?, ?, ?) "
                + "ON CONFLICT (sub) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sub);
            ps.setString(2, name);
            ps.setString(3, email);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("upsertUser DB 오류: " + e);
        }
    }

    /** 유저 프리셋 번들(불투명 JSON) 저장(upsert). */
    public void saveBundle(String sub, String bundleJson) {
        String sql = "INSERT INTO users (sub, bundle, updated_at) VALUES (?, ?::jsonb, now()) "
                + "ON CONFLICT (sub) DO UPDATE SET bundle = EXCLUDED.bundle, updated_at = now()";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sub);
            ps.setString(2, bundleJson);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("saveBundle DB 오류: " + e);
        }
    }

    /** 유저 프리셋 번들(JSON 문자열). 없거나 빈 객체면 null. */
    public String loadBundle(String sub) {
        String sql = "SELECT bundle::text FROM users WHERE sub = ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sub);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String b = rs.getString(1);
                    return (b == null || b.equals("{}")) ? null : b;
                }
            }
        } catch (Exception e) {
            System.err.println("loadBundle DB 오류: " + e);
        }
        return null;
    }

    // ──────────────────── 세션 ────────────────────

    /** 만료 안 된 세션 전부(시작 시 메모리 캐시 채우기용). */
    public Map<String, AuthService.Session> loadSessions() {
        Map<String, AuthService.Session> out = new LinkedHashMap<>();
        String sql = "SELECT token, sub, name, email, expires_at FROM sessions WHERE expires_at > now()";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long exp = rs.getTimestamp(5).getTime();
                AuthService.Session s = new AuthService.Session(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), exp);
                out.put(s.token(), s);
            }
        } catch (Exception e) {
            System.err.println("loadSessions DB 오류: " + e);
        }
        return out;
    }

    public void putSession(AuthService.Session s) {
        String sql = "INSERT INTO sessions (token, sub, name, email, expires_at) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (token) DO UPDATE SET expires_at = EXCLUDED.expires_at";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, s.token());
            ps.setString(2, s.sub());
            ps.setString(3, s.name());
            ps.setString(4, s.email());
            ps.setTimestamp(5, new Timestamp(s.expiresAt()));
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("putSession DB 오류: " + e);
        }
    }

    public void deleteSession(String token) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("deleteSession DB 오류: " + e);
        }
    }
}
