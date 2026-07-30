package mekicalc.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mekicalc.json.Json;

/**
 * 관리자 이메일 화이트리스트. 파일: data/admins.json (개인/비밀 — gitignore·배포 제외).
 *
 * <pre>
 * { "admins": ["you@gmail.com", "other@gmail.com"] }
 * </pre>
 *
 * <p>파일이 없거나 목록이 비면 <b>관리자 0명</b> — 관리자 기능이 전면 차단되는 안전 기본값이다.
 * 비교는 소문자 정규화(구글 이메일 대소문자 무시). OAuth 로그인 이메일({@link AuthService.Session#email})과 대조한다.
 */
public final class AdminConfig {

    private final Set<String> emails;

    private AdminConfig(Set<String> emails) { this.emails = emails; }

    /** 관리자 없음(파일 부재·미주입 시 안전 기본값). */
    public static AdminConfig empty() { return new AdminConfig(Set.of()); }

    public static AdminConfig load(Path dataDir) {
        Path f = dataDir.resolve("admins.json");
        if (!Files.exists(f)) return empty();
        try {
            Map<String, Object> m = Json.asMap(Json.parse(Files.readString(f)));
            Object arr = m.get("admins");
            if (!(arr instanceof List)) return empty();
            Set<String> set = new HashSet<>();
            for (Object o : (List<?>) arr) {
                if (o != null && !o.toString().isBlank()) set.add(norm(o.toString()));
            }
            return new AdminConfig(set);
        } catch (Exception e) {
            System.err.println("admins.json 읽기 실패(관리자 0명으로 처리): " + e);
            return empty();
        }
    }

    /** 이메일이 관리자 화이트리스트에 있는지(소문자 정규화 비교). */
    public boolean isAdmin(String email) {
        return email != null && emails.contains(norm(email));
    }

    public int size() { return emails.size(); }

    private static String norm(String s) { return s.trim().toLowerCase(Locale.ROOT); }
}
