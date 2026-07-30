package mekicalc.web.boot;

import static mekicalc.web.boot.Req.strOpt;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import mekicalc.web.BotOutbox;
import mekicalc.web.BotService;
import mekicalc.web.NoticeService;
import mekicalc.web.RoomStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공지(넥슨 포럼 크롤링·요약)·카카오봇 API 컨트롤러. 구 {@code WebServer}의 공지/봇 라우트와 1:1.
 * NoticeService·BotService·BotOutbox·NoticeCrawler(Service 층)에 위임.
 */
@RestController
public class NoticeBotController {

    private final NoticeService notices;
    private final BotOutbox outbox;
    private final RoomStore rooms;

    public NoticeBotController(NoticeService notices, BotOutbox outbox, RoomStore rooms) {
        this.notices = notices;
        this.outbox = outbox;
        this.rooms = rooms;
    }

    // ──────────────── 공지 ────────────────

    @GetMapping("/api/notice-categories")
    public Object categories() {
        return notices.categories();
    }

    @GetMapping("/api/notices")
    public Object noticeList(@RequestParam(required = false) String category,
                            @RequestParam(required = false) String page,
                            @RequestParam(name = "q", required = false) String q) {
        int p = 0;
        try { if (page != null) p = Integer.parseInt(page); } catch (NumberFormatException ignore) { }
        return notices.list(category, p, q);
    }

    @GetMapping("/api/coupons")
    public Object coupons() {
        return notices.coupons(LocalDate.now().toString());
    }

    // ──────────────── 카카오봇 ────────────────

    /** 봇 명령 웹훅(폰 → 서버). */
    @PostMapping("/api/bot")
    public Object bot(@RequestBody Map<String, Object> req) {
        return BotService.handle(notices, strOpt(req, "room"), strOpt(req, "sender"), strOpt(req, "msg"));
    }

    /** 봇 발신 큐 폴링(서버 → 봇 브릿지). */
    @GetMapping("/api/bot/outbox/poll")
    public Object outboxPoll(@RequestParam(required = false) String limit) {
        int n = 10;
        try { if (limit != null) n = Integer.parseInt(limit); } catch (NumberFormatException ignore) { }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", outbox.poll(n));
        return out;
    }

    /** 봇 큐 확정(중복 방지). */
    @PostMapping("/api/bot/outbox/ack")
    @SuppressWarnings("unchecked")
    public Object outboxAck(@RequestBody Map<String, Object> req) {
        Object ids = req.get("ids");
        int n = (ids instanceof List) ? outbox.ack((List<Object>) ids) : 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("acked", (double) n);
        return out;
    }

    @GetMapping("/api/bot/outbox/health")
    public Object outboxHealth() {
        return outbox.health();
    }

    /**
     * 방 자동 등록(봇 브릿지 → 서버). 브릿지가 감지한 방을 권한 off(pending)로 올린다.
     * 이미 있으면 이름·감지시각만 갱신(권한 보존). 관리자가 페이지에서 권한을 부여한다.
     */
    @PostMapping("/api/bot/rooms/register")
    public Object registerRoom(@RequestBody Map<String, Object> req) {
        boolean added = rooms.register(strOpt(req, "chatId"), strOpt(req, "name"), Instant.now().getEpochSecond());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("added", added);
        return out;
    }

    // 수동 트리거·시뮬레이션(remind-now, simulate-*)은 dev 전용으로 분리 → BotDevController(@Profile("dev")).
}
