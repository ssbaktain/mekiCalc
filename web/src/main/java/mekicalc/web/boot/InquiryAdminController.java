package mekicalc.web.boot;

import static mekicalc.web.boot.Req.num;
import static mekicalc.web.boot.Req.strOpt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import mekicalc.web.AuthService;
import mekicalc.web.BanStore;
import mekicalc.web.InquiryStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 제보/문의·계정 정지 관리 API. {@link AdminAuthInterceptor}가 /api/admin/** 를 관리자 세션으로
 * 게이팅하므로 여기서 권한 재검사는 하지 않는다(단, 정지 실행자 기록을 위해 세션 이메일은 읽는다).
 */
@RestController
@RequestMapping("/api/admin")
public class InquiryAdminController {

    private static final Set<String> STATUS = Set.of("new", "seen", "resolved");

    private final AuthService auth;
    private final InquiryStore inquiries;
    private final BanStore bans;

    public InquiryAdminController(AuthService auth, InquiryStore inquiries, BanStore bans) {
        this.auth = auth;
        this.inquiries = inquiries;
        this.bans = bans;
    }

    // ──────────────── 제보/문의 ────────────────

    /** 목록(필터: user=sub/email/name 부분일치, date=yyyy-MM-dd(KST), status). */
    @GetMapping("/inquiries")
    public Object list(@RequestParam(required = false) String user,
                       @RequestParam(required = false) String date,
                       @RequestParam(required = false) String status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inquiries", inquiries.list(user, date, status));
        out.put("unread", (double) inquiries.unreadCount());
        return out;
    }

    /** 상세(본문·스탯·첨부·스레드 포함). 열람 시 미확인(new)→확인 중(seen)으로 자동 전환. */
    @GetMapping("/inquiries/{id}")
    public Object detail(@PathVariable long id) {
        InquiryStore.Inquiry q = inquiries.get(id);
        if (q == null) throw new IllegalArgumentException("존재하지 않는 문의: " + id);
        if ("new".equals(q.status)) inquiries.setStatus(id, "seen");   // 읽음 처리
        return inquiries.get(id).toClientMapFull();
    }

    /** 상태 변경. body: {status: new|seen|resolved}. */
    @PostMapping("/inquiries/{id}/status")
    public Object setStatus(@PathVariable long id, @RequestBody Map<String, Object> req) {
        String status = strOpt(req, "status");
        if (status == null || !STATUS.contains(status)) throw new IllegalArgumentException("잘못된 상태: " + status);
        InquiryStore.Inquiry q = inquiries.setStatus(id, status);
        if (q == null) throw new IllegalArgumentException("존재하지 않는 문의: " + id);
        return q.toClientMapFull();
    }

    /** 관리자 답장(스레드에 추가). body: {body}. 확장 지점 — 유저는 '내 문의 내역'에서 확인. */
    @PostMapping("/inquiries/{id}/reply")
    public Object reply(@PathVariable long id, @RequestBody Map<String, Object> req) {
        String body = strOpt(req, "body");
        if (body == null || body.isBlank()) throw new IllegalArgumentException("답장 내용이 비어 있습니다.");
        InquiryStore.Inquiry q = inquiries.addMessage(id, "admin", body.trim());
        if (q == null) throw new IllegalArgumentException("존재하지 않는 문의: " + id);
        if ("new".equals(q.status)) inquiries.setStatus(id, "seen");
        return inquiries.get(id).toClientMapFull();
    }

    /** 첨부 이미지 열람(관리자). */
    @GetMapping("/inquiries/{id}/attachments/{n}")
    public void attachment(@PathVariable long id, @PathVariable int n, HttpServletResponse res) throws IOException {
        InquiryStore.Inquiry q = inquiries.get(id);
        if (q == null) { res.setStatus(404); return; }
        InquiryController.serveAttachment(inquiries.attachmentFile(id, n), q, n, res);
    }

    /** 문의 삭제(메타+첨부). */
    @DeleteMapping("/inquiries/{id}")
    public Object delete(@PathVariable long id) {
        return Map.of("ok", inquiries.delete(id));
    }

    // ──────────────── 계정 정지 ────────────────

    /** 정지 이력(최신순, 활성 포함). */
    @GetMapping("/bans")
    public Object listBans() {
        return Map.of("bans", bans.list());
    }

    /** 정지 설정. body: {sub, email?, days(0=영구), reason?, message?}. */
    @PostMapping("/bans")
    public Object ban(@RequestBody Map<String, Object> req, HttpServletRequest httpReq) {
        String sub = strOpt(req, "sub");
        if (sub == null || sub.isBlank()) throw new IllegalArgumentException("정지할 계정(sub)이 필요합니다.");
        int days = (int) num(req, "days", 0);
        String email = strOpt(req, "email");
        String reason = strOpt(req, "reason");
        String message = strOpt(req, "message");
        AuthService.Session s = auth.session(Req.cookie(httpReq, "sid"));
        String by = s != null ? s.email() : null;
        String ip = inquiries.latestIp(sub);   // 참고용(최근 접수 IP)
        BanStore.Ban b = bans.set(sub, email, days, reason, message, by, ip);
        return b.toClientMap();
    }

    /** 정지 해제. */
    @DeleteMapping("/bans/{sub}")
    public Object release(@PathVariable String sub) {
        return Map.of("ok", bans.release(sub));
    }
}
