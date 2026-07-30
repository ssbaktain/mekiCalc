package mekicalc.web.boot;

import static mekicalc.web.boot.Req.bool;
import static mekicalc.web.boot.Req.strOpt;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.web.BotBridge;
import mekicalc.web.RoomStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 API. {@link AdminAuthInterceptor}가 /api/admin/** 를 관리자 세션으로 게이팅하므로
 * 여기 핸들러는 권한 검사를 다시 하지 않는다. 방 레지스트리(방별 공지/응답 권한) 관리를 담당.
 * (개인 공지·테스트 발송은 단계 5에서 추가.)
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final RoomStore rooms;
    private final BotBridge bridge;

    public AdminController(RoomStore rooms, BotBridge bridge) {
        this.rooms = rooms;
        this.bridge = bridge;
    }

    /** 방 목록(+권한). */
    @GetMapping("/rooms")
    public Object listRooms() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rooms", rooms.list());
        return out;
    }

    /**
     * 봇 브릿지(Iris)에서 현재 오픈 그룹채팅 방을 조회해 자동 인식·등록. 새 방은 권한 off로 추가,
     * 기존 방은 이름·권한 보존(관리자 설정 우선). Iris 미연결이면 500(연결 실패 메시지).
     */
    @PostMapping("/rooms/sync")
    public Object syncRooms() {
        List<Map<String, Object>> found = bridge.listOpenGroupRooms();
        long now = Instant.now().getEpochSecond();
        int added = 0;
        for (Map<String, Object> r : found) {
            if (rooms.register(strOpt(r, "chatId"), strOpt(r, "name"), now)) added++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rooms", rooms.list());
        out.put("synced", (double) found.size());
        out.put("added", (double) added);
        return out;
    }

    /** 방 수동 추가(chatId 필수, name 선택). 이미 있으면 이름만 갱신. */
    @PostMapping("/rooms")
    public Object addRoom(@RequestBody Map<String, Object> req) {
        String chatId = strOpt(req, "chatId");
        if (chatId == null || chatId.isBlank()) throw new IllegalArgumentException("chatId가 필요합니다.");
        return rooms.addManual(chatId.trim(), strOpt(req, "name"), Instant.now().getEpochSecond()).toMap();
    }

    /** 방 권한/이름 수정. body: {name?, notify, reply}. */
    @PutMapping("/rooms/{chatId}")
    public Object updateRoom(@PathVariable String chatId, @RequestBody Map<String, Object> req) {
        RoomStore.Room r = rooms.update(chatId, strOpt(req, "name"), bool(req, "notify", false), bool(req, "reply", false));
        if (r == null) throw new IllegalArgumentException("존재하지 않는 방: " + chatId);
        return r.toMap();
    }
}
