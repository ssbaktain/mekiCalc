package mekicalc.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 계정 정지 레지스트리(파일: data/bans.json). 제보/문의 악용 계정을 관리자가 기간제/영구로 정지한다.
 *
 * <p>키=Google {@code sub}(계정 고유 ID). 정지는 <b>계정 삭제(탈퇴)와 독립</b>이라 재가입해도 유지된다
 * (같은 구글 계정이면 sub 동일 → 재차단). 부차적으로 {@code email}로도 매칭(이메일 재사용 대비).
 * IP는 관리자 수동 확인용으로 기록만 한다(자동 차단 아님).
 *
 * <p>기간: {@code banUntil}=0 이면 영구, 아니면 그 시각(epoch ms)까지. 만료된 정지는 조회 시 자동으로
 * 비활성 처리한다. 해제는 {@code active=false}로 두되 기록(사유·IP·이력)은 남긴다.
 */
public final class BanStore {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST);
    /** 기본 안내문 템플릿. {until} = 종료 시각("…까지") 또는 영구. */
    public static final String DEFAULT_MESSAGE = "제보/문의 악용으로 {until} 계정을 이용하실 수 없습니다.";

    public static final class Ban {
        public String sub;
        public String email;
        public String reason;    // 관리자 사유(유저에게 비노출)
        public String message;   // 유저 안내문 템플릿({until} 치환)
        public long bannedAt;    // epoch ms
        public long banUntil;    // epoch ms, 0=영구
        public String createdBy; // 정지 실행 관리자 이메일
        public String lastIp;    // 참고용(마지막 확인 IP)
        public boolean active = true;
        public long releasedAt;  // 해제 시각(0=미해제)

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sub", sub);
            m.put("email", email);
            m.put("reason", reason);
            m.put("message", message);
            m.put("bannedAt", (double) bannedAt);
            m.put("banUntil", (double) banUntil);
            m.put("createdBy", createdBy);
            m.put("lastIp", lastIp);
            m.put("active", active);
            m.put("releasedAt", (double) releasedAt);
            return m;
        }
        static Ban fromMap(Map<String, Object> m) {
            Ban b = new Ban();
            b.sub = str(m, "sub");
            b.email = str(m, "email");
            b.reason = str(m, "reason");
            b.message = str(m, "message");
            b.bannedAt = (long) Json.num(m, "bannedAt", 0);
            b.banUntil = (long) Json.num(m, "banUntil", 0);
            b.createdBy = str(m, "createdBy");
            b.lastIp = str(m, "lastIp");
            b.active = !Boolean.FALSE.equals(m.get("active"));
            b.releasedAt = (long) Json.num(m, "releasedAt", 0);
            return b;
        }
        private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : v.toString(); }

        /** 지금 시점에 유효(활성 + 미만료)한 정지인지. */
        public boolean isEffective(long nowMs) {
            return active && (banUntil == 0 || banUntil > nowMs);
        }

        /** 유저 표시용 최종 안내문({until} 치환). */
        public String displayMessage() {
            String tpl = (message == null || message.isBlank()) ? DEFAULT_MESSAGE : message;
            String repl = (banUntil == 0) ? "영구적으로" : (FMT.format(Instant.ofEpochMilli(banUntil)) + "까지");
            return tpl.replace("{until}", repl);
        }

        /** 관리자/클라 표시용 맵(안내문 조합 포함). */
        public Map<String, Object> toClientMap() {
            Map<String, Object> m = toMap();
            m.put("displayMessage", displayMessage());
            m.put("permanent", banUntil == 0);
            return m;
        }
    }

    private final Path file;
    private final List<Ban> bans = new ArrayList<>();   // 최신이 뒤(append), 조회 시 역순 사용

    public BanStore(Path dataDir) {
        this.file = dataDir.resolve("bans.json");
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            for (Object o : Json.asList(Json.parse(Files.readString(file, StandardCharsets.UTF_8)))) {
                bans.add(Ban.fromMap(Json.asMap(o)));
            }
        } catch (Exception e) { System.err.println("bans.json 읽기 실패(무시): " + e); }
    }

    private synchronized void save() {
        try {
            List<Object> arr = new ArrayList<>(bans.size());
            for (Ban b : bans) arr.add(b.toMap());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(arr), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { System.err.println("bans.json 저장 실패: " + e); }
    }

    /**
     * sub 또는 email로 매칭되는 <b>유효한</b> 정지를 반환(없으면 null). 만료된 활성 정지는 여기서 비활성 처리.
     * email은 sub 매칭이 없을 때만 보조로 사용.
     */
    public synchronized Ban activeBan(String sub, String email) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Ban hit = null;
        for (int i = bans.size() - 1; i >= 0; i--) {
            Ban b = bans.get(i);
            if (!b.active) continue;
            if (b.banUntil != 0 && b.banUntil <= now) { b.active = false; b.releasedAt = now; changed = true; continue; }
            boolean match = (sub != null && sub.equals(b.sub))
                    || (hit == null && email != null && !email.isBlank() && email.equalsIgnoreCase(b.email));
            if (match && (hit == null || sub != null && sub.equals(b.sub))) hit = b;
        }
        if (changed) save();
        return hit;
    }

    public synchronized boolean isBanned(String sub, String email) { return activeBan(sub, email) != null; }

    /** 현재 활성 정지(있으면). sub 기준. */
    public synchronized Ban getActive(String sub) {
        long now = System.currentTimeMillis();
        for (int i = bans.size() - 1; i >= 0; i--) {
            Ban b = bans.get(i);
            if (b.active && sub != null && sub.equals(b.sub) && b.isEffective(now)) return b;
        }
        return null;
    }

    /**
     * 정지 설정(같은 sub의 기존 활성 정지는 해제하고 새로 추가). days=0 이면 영구, 아니면 지금부터 days일 뒤까지.
     * message가 비면 기본 템플릿 사용.
     */
    public synchronized Ban set(String sub, String email, int days, String reason, String message,
                                String createdBy, String ip) {
        if (sub == null || sub.isBlank()) throw new IllegalArgumentException("sub가 필요합니다.");
        long now = System.currentTimeMillis();
        for (Ban b : bans) if (b.active && sub.equals(b.sub)) { b.active = false; b.releasedAt = now; }
        Ban b = new Ban();
        b.sub = sub;
        b.email = email;
        b.reason = reason;
        b.message = (message == null || message.isBlank()) ? DEFAULT_MESSAGE : message;
        b.bannedAt = now;
        b.banUntil = days <= 0 ? 0 : now + days * 24L * 3600_000L;
        b.createdBy = createdBy;
        b.lastIp = ip;
        b.active = true;
        bans.add(b);
        save();
        return b;
    }

    /** 해당 sub의 활성 정지를 해제. 해제된 게 있으면 true. */
    public synchronized boolean release(String sub) {
        long now = System.currentTimeMillis();
        boolean any = false;
        for (Ban b : bans) if (b.active && sub != null && sub.equals(b.sub)) { b.active = false; b.releasedAt = now; any = true; }
        if (any) save();
        return any;
    }

    /** 전체 정지 이력(최신순). 관리자 표시용(안내문 조합 포함). */
    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>(bans.size());
        for (int i = bans.size() - 1; i >= 0; i--) out.add(bans.get(i).toClientMap());
        return out;
    }
}
