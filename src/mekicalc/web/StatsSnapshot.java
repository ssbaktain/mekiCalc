package mekicalc.web;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import mekicalc.json.Json;

/**
 * 통계 분석용 유저 프리셋 스냅샷 덤프. 하루 1회(미니PC 스케줄드 작업)로 실행.
 *
 * <p>유저 프리셋은 이미 DB(users.bundle)에 있으므로 요청 경로에 로깅하지 않고, 하루 한 번 DB 전체를
 * 떠서 날짜별 파일로 내보낸다. <b>cross-day dedup</b>: 유저별 직전 스냅샷과 번들 해시가 같으면 건너뛴다
 * (대부분 유저는 매일 안 바꾸므로 날짜 간 중복을 제거 — 분석 시 "그날 값"은 직전 스냅샷 carry-forward).
 *
 * <p>출력: {@code logs/snapshots/YYYY-MM-DD.jsonl.gz} (한 줄 = 변경된 유저 1명, gzip).
 * 상태: {@code logs/snapshots/.state.json} (sub → 마지막 번들 해시).
 * 보관: 365일 지난 {@code *.jsonl.gz} 자동 삭제.
 *
 * 실행: {@code java -cp out;lib\* mekicalc.web.WebMain --snapshot [dataDir]}
 */
public final class StatsSnapshot {

    private static final int RETENTION_DAYS = 365;

    public static void run(Path dataDir) throws Exception {
        Path snapDir = dataDir.resolveSibling("logs").resolve("snapshots");
        Files.createDirectories(snapDir);

        List<String[]> users = loadUsers(dataDir);   // [sub, name, email, createdAt, bundleText]
        Map<String, Object> state = loadState(snapDir.resolve(".state.json"));

        String runTs = Instant.now().toString();
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        Path outFile = snapDir.resolve(today + ".jsonl.gz");

        List<String> lines = new ArrayList<>();
        int total = 0, changed = 0, skipped = 0;
        for (String[] u : users) {
            String sub = u[0], name = u[1], email = u[2], createdAt = u[3];
            String bundle = minify(u[4] == null ? "" : u[4]);
            if (bundle.isEmpty() || bundle.equals("{}")) continue;   // 빈 프리셋 제외
            total++;
            String hash = sha256(bundle);
            if (hash.equals(state.get(sub))) { skipped++; continue; }   // 직전과 동일 → dedup
            state.put(sub, hash);
            changed++;
            // bundle 은 이미 검증된 JSON(minify된 한 줄) → 중첩 객체로 그대로 인라인
            lines.add("{\"ts\":" + j(runTs) + ",\"sub\":" + j(sub) + ",\"name\":" + j(name)
                    + ",\"email\":" + j(email) + ",\"createdAt\":" + j(createdAt)
                    + ",\"bundle\":" + bundle + "}\n");
        }

        if (!lines.isEmpty()) {
            // gzip append: 재실행 시 같은 날 파일에 새 gzip 멤버를 덧붙임(표준 디코더가 연결 멤버 처리).
            try (OutputStream fos = Files.newOutputStream(outFile,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                 GZIPOutputStream gz = new GZIPOutputStream(fos);
                 Writer w = new OutputStreamWriter(gz, StandardCharsets.UTF_8)) {
                for (String line : lines) w.write(line);
            }
            saveState(snapDir.resolve(".state.json"), state);
        }

        int purged = purgeOld(snapDir, today);
        System.out.println("스냅샷 완료: 대상 " + total + "명, 기록 " + changed
                + "명, dedup " + skipped + "명 → " + (lines.isEmpty() ? "(변경 없음, 파일 미생성)" : outFile.getFileName())
                + ", 만료삭제 " + purged + "개");
    }

    /** DB가 켜져 있으면 users 테이블, 아니면 data/users/*.json(개발 폴백, name/email 없음). */
    private static List<String[]> loadUsers(Path dataDir) {
        List<String[]> out = new ArrayList<>();
        Db db = Db.load(dataDir);
        if (db.enabled) {
            String sql = "SELECT sub, name, email, created_at, bundle::text FROM users";
            try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)});
                }
                return out;
            } catch (Exception e) {
                System.err.println("스냅샷 DB 읽기 실패, 파일 폴백 시도: " + e);
                out.clear();
            }
        }
        Path usersDir = dataDir.resolve("users");
        if (Files.isDirectory(usersDir)) {
            try (var s = Files.list(usersDir)) {
                for (Path f : s.toList()) {
                    String fn = f.getFileName().toString();
                    if (!fn.endsWith(".json")) continue;
                    String sub = fn.substring(0, fn.length() - ".json".length());
                    out.add(new String[]{sub, null, null, null, Files.readString(f)});
                }
            } catch (Exception e) {
                System.err.println("스냅샷 파일 읽기 실패: " + e);
            }
        }
        return out;
    }

    private static Map<String, Object> loadState(Path f) {
        try {
            if (Files.exists(f)) return Json.asMap(Json.parse(Files.readString(f)));
        } catch (Exception e) {
            System.err.println("스냅샷 상태 읽기 실패(새로 시작): " + e);
        }
        return new LinkedHashMap<>();
    }

    private static void saveState(Path f, Map<String, Object> state) throws Exception {
        Files.writeString(f, Json.write(state), StandardCharsets.UTF_8);
    }

    /** YYYY-MM-DD.jsonl.gz 중 today-365일 이전 파일 삭제. 반환=삭제 개수. */
    private static int purgeOld(Path snapDir, LocalDate today) {
        int n = 0;
        LocalDate cutoff = today.minusDays(RETENTION_DAYS);
        try (var s = Files.list(snapDir)) {
            for (Path p : s.toList()) {
                String fn = p.getFileName().toString();
                if (!fn.endsWith(".jsonl.gz")) continue;
                try {
                    LocalDate d = LocalDate.parse(fn.substring(0, fn.length() - ".jsonl.gz".length()));
                    if (d.isBefore(cutoff)) { Files.deleteIfExists(p); n++; }
                } catch (Exception ignore) { /* 날짜형식 아니면 건너뜀 */ }
            }
        } catch (Exception e) {
            System.err.println("스냅샷 만료 정리 실패: " + e);
        }
        return n;
    }

    /** 공백(문자열 밖)만 제거해 JSON을 한 줄로. 숫자/문자열 내용은 원형 보존. */
    private static String minify(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean inStr = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                sb.append(c);
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true; sb.append(c);
            } else if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());   // 폴백(이론상 도달 안 함)
        }
    }

    /** null·문자열을 JSON 값으로 안전 직렬화(한 줄). EventLog 와 동일 규약. */
    private static String j(Object o) {
        return Json.write(o).strip();
    }
}
