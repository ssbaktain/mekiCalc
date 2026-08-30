package mekicalc.web.boot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mekicalc.json.Json;
import mekicalc.web.AuthService;
import mekicalc.web.BanStore;
import mekicalc.web.BotOutbox;
import mekicalc.web.InquiryStore;
import mekicalc.web.RoomStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 유저 제보/문의 접수 API(로그인 전용). 익명 허위제보 방지를 위해 로그인 계정만 접수하며,
 * 접수 시 계정·IP·시각·자동첨부 스탯을 기록한다. 계정 정지 중이면 접수 불가(403). 하루 20건 제한.
 * 새 문의는 관리자 카톡 알림 방(inquiry 권한)으로 요약을 발송한다. 답장 확장 대비로 본인 문의 내역 조회 제공.
 */
@RestController
public class InquiryController {

    private static final int DAILY_LIMIT = 20;
    private static final int MAX_IMAGES = 5;                   // 1회 첨부 이미지 최대 장수(카톡 발송 캡과 동일)
    private static final long MAX_TOTAL = 20L * 1024 * 1024;   // 이미지+내용 합계 20MB
    private static final Set<String> ALLOWED_MIME = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private static final Set<String> TYPES = Set.of("bug", "suggest", "etc");

    private final AuthService auth;
    private final InquiryStore inquiries;
    private final BanStore bans;
    private final RoomStore rooms;
    private final BotOutbox outbox;

    public InquiryController(AuthService auth, InquiryStore inquiries, BanStore bans, RoomStore rooms, BotOutbox outbox) {
        this.auth = auth;
        this.inquiries = inquiries;
        this.bans = bans;
        this.rooms = rooms;
        this.outbox = outbox;
    }

    /** 접수 상태(로그인·오늘 사용/남은 건수·정지 여부). 폼 진입 시 조회. */
    @GetMapping("/api/inquiries/quota")
    public Object quota(HttpServletRequest req) {
        AuthService.Session s = auth.session(Req.cookie(req, "sid"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loggedIn", s != null);
        m.put("limit", (double) DAILY_LIMIT);
        if (s == null) { m.put("usedToday", 0.0); m.put("remaining", 0.0); m.put("banned", false); return m; }
        BanStore.Ban ban = bans.activeBan(s.sub(), s.email());
        m.put("banned", ban != null);
        if (ban != null) { m.put("banMessage", ban.displayMessage()); m.put("banUntil", ban.banUntil == 0 ? null : (double) ban.banUntil); }
        int used = inquiries.countToday(s.sub());
        m.put("usedToday", (double) used);
        m.put("remaining", (double) Math.max(0, DAILY_LIMIT - used));
        return m;
    }

    /** 제보/문의 접수(multipart). fields: type,title,body,bundle(JSON 문자열) + files[](이미지). */
    @PostMapping(value = "/api/inquiries", consumes = "multipart/form-data")
    public ResponseEntity<Object> submit(HttpServletRequest req,
                                         @RequestParam(value = "type", required = false) String type,
                                         @RequestParam(value = "title", required = false) String title,
                                         @RequestParam(value = "body", required = false) String body,
                                         @RequestParam(value = "bundle", required = false) String bundle,
                                         @RequestParam(value = "build", required = false) String build,
                                         @RequestParam(value = "files", required = false) MultipartFile[] files) throws IOException {
        AuthService.Session s = auth.session(Req.cookie(req, "sid"));
        if (s == null) return err(401, "로그인이 필요합니다. 인웹 제보/문의는 로그인 후 이용할 수 있어요.");
        BanStore.Ban ban = bans.activeBan(s.sub(), s.email());
        if (ban != null) return ResponseEntity.status(403).body(Map.of("error", ban.displayMessage(), "banned", true));
        if (title == null || title.isBlank()) return err(400, "제목을 입력해 주세요.");
        if (body == null || body.isBlank()) return err(400, "내용을 입력해 주세요.");
        int used = inquiries.countToday(s.sub());
        if (used >= DAILY_LIMIT)
            return ResponseEntity.status(429).body(Map.of(
                    "error", "하루 " + DAILY_LIMIT + "건 제한이에요. 내일 00시(자정, KST) 이후에 다시 문의해 주세요.",
                    "limit", (double) DAILY_LIMIT));

        long total = body.getBytes(StandardCharsets.UTF_8).length;
        List<InquiryStore.Upload> ups = new ArrayList<>();
        if (files != null) for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            String mime = f.getContentType();
            if (mime == null || !ALLOWED_MIME.contains(mime))
                return err(415, "이미지 파일(PNG·JPG·WEBP·GIF)만 첨부할 수 있어요.");
            total += f.getSize();
            ups.add(new InquiryStore.Upload(f.getOriginalFilename(), mime, f.getBytes()));
        }
        if (ups.size() > MAX_IMAGES) return err(400, "이미지는 최대 " + MAX_IMAGES + "장까지 첨부할 수 있어요.");
        if (total > MAX_TOTAL) return err(413, "1회 제보/문의 최대 용량(20MB)을 초과했어요. 이미지 수·크기를 줄여 주세요.");

        Object bundleObj = null, buildObj = null;
        if (bundle != null && !bundle.isBlank()) { try { bundleObj = Json.parse(bundle); } catch (Exception ignore) { } }
        if (build != null && !build.isBlank()) { try { buildObj = Json.parse(build); } catch (Exception ignore) { } }
        String ip = AccessLogInterceptor.clientIp(req);
        InquiryStore.Inquiry q = inquiries.create(s.sub(), s.email(), s.name(),
                normType(type), trimOrNull(title), body, bundleObj, buildObj, ups, ip);
        notifyKakao(q);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", (double) q.id);
        out.put("remainingToday", (double) Math.max(0, DAILY_LIMIT - (used + 1)));
        return ResponseEntity.ok(out);
    }

    /** 본인 문의 내역(최신순, 본문·답장 스레드 포함). */
    @GetMapping("/api/inquiries/mine")
    public ResponseEntity<Object> mine(HttpServletRequest req) {
        AuthService.Session s = auth.session(Req.cookie(req, "sid"));
        if (s == null) return err(401, "로그인이 필요합니다.");
        return ResponseEntity.ok(Map.of("inquiries", inquiries.mine(s.sub())));
    }

    /** 본인 문의의 첨부 이미지 열람(소유자 한정). */
    @GetMapping("/api/inquiries/mine/{id}/attachments/{n}")
    public void myAttachment(@PathVariable long id, @PathVariable int n,
                             HttpServletRequest req, HttpServletResponse res) throws IOException {
        AuthService.Session s = auth.session(Req.cookie(req, "sid"));
        if (s == null) { res.setStatus(401); return; }
        InquiryStore.Inquiry q = inquiries.get(id);
        if (q == null || !s.sub().equals(q.sub)) { res.setStatus(404); return; }
        serveAttachment(inquiries.attachmentFile(id, n), q, n, res);
    }

    // ──────────────── 헬퍼 ────────────────

    private void notifyKakao(InquiryStore.Inquiry q) {
        try {
            List<String> targets = rooms.inquiryRooms();
            if (targets.isEmpty()) return;
            StringBuilder t = new StringBuilder();
            t.append("🆕 새 제보/문의 #").append(q.id).append("\n");
            t.append("유형: ").append(typeLabel(q.type)).append("\n");
            t.append("계정: ").append(q.name == null ? "" : q.name).append(" (").append(q.email == null ? "" : q.email).append(")\n");
            if (q.title != null && !q.title.isBlank()) t.append("제목: ").append(q.title).append("\n");
            t.append("내용: ").append(preview(q.body, 140));
            if (!q.attachments.isEmpty()) t.append("\n📷 첨부 ").append(q.attachments.size()).append("장");
            // 첨부 이미지 절대경로(최대 MAX_IMAGES장) — 봇이 읽어 카톡으로 함께 발송. 이미지는 봇=서버 같은 미니PC 디스크에 있음.
            List<String> imgs = new ArrayList<>();
            for (int i = 0; i < q.attachments.size() && imgs.size() < MAX_IMAGES; i++) {
                Path p = inquiries.attachmentFile(q.id, i);
                if (p != null) imgs.add(p.toAbsolutePath().toString());
            }
            // dedupKey에 createdAt(epoch ms) 포함 — 문의 데이터 초기화로 id가 재사용돼도 옛 sent 메시지 키와 충돌해
            // 알림이 조용히 드롭되지 않도록(같은 문의의 중복 적재만 막고, id 재사용은 시각으로 구분). 과거 버그 원인.
            outbox.enqueue(t.toString(), "inquiry:" + q.id + "@" + q.createdAt, targets, imgs);
        } catch (Exception ignore) { }
    }

    static void serveAttachment(Path file, InquiryStore.Inquiry q, int n, HttpServletResponse res) throws IOException {
        if (file == null) { res.setStatus(404); return; }
        String mime = "application/octet-stream";
        if (n >= 0 && n < q.attachments.size()) {
            Object mm = q.attachments.get(n).get("mime");
            if (mm != null) mime = mm.toString();
        }
        byte[] data = Files.readAllBytes(file);
        res.setStatus(200);
        res.setHeader("Content-Type", mime);
        res.setHeader("Cache-Control", "private, no-store");
        res.setContentLength(data.length);
        try (OutputStream os = res.getOutputStream()) { os.write(data); }
    }

    private static String normType(String t) {
        return (t != null && TYPES.contains(t)) ? t : "etc";
    }
    static String typeLabel(String t) {
        return switch (t == null ? "" : t) {
            case "bug" -> "버그 제보";
            case "suggest" -> "기능 건의";
            default -> "기타 문의";
        };
    }
    private static String trimOrNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String preview(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
    private static ResponseEntity<Object> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
