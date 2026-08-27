package mekicalc.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 카카오봇 방 레지스트리(파일: data/bot_rooms.json). 서버가 방 목록과 방별 권한의 소스 오브 트루스가 된다.
 *
 * <p>방 권한은 둘로 독립: {@code notify}(공지·종료 임박 리마인더 발송) / {@code reply}(명령 응답).
 * 조합으로 공지만/응답만/둘 다/둘 다 안 함을 표현한다. 신규 방은 브릿지가 감지해 {@link #register}로
 * 올리며 <b>권한 off(pending)</b>로 들어오고, 관리자가 페이지에서 권한을 부여한다. 수동 추가({@link #addManual})도 지원.
 *
 * <p>chatId(카카오 채팅방 고유 숫자 문자열)가 식별자. 단일 머신(봇=서버 같은 미니PC)이라 파일+synchronized로 단순하게.
 */
public final class RoomStore {

    public static final class Room {
        public String chatId;
        public String name;      // 관리자가 사이트에서 붙인 커스텀 표시명(비울 수 있음 → 그러면 origName 사용)
        public String origName;  // 봇이 감지한 실제 카톡 방 이름(private_meta.name 등). 커스텀을 지웠을 때 회색 안내로 노출.
        public boolean notify;   // 공지·리마인더 발송 권한
        public boolean reply;    // 명령 응답 권한
        public boolean inquiry;  // 유저 제보/문의 접수 알림 발송 권한(새 문의 들어오면 이 방들로 알림)
        public long lastSeen;    // 마지막 감지(자동등록/갱신) epoch sec
        public String source = "auto";   // "auto"(브릿지 감지) | "manual"(관리자 수동)

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chatId", chatId);
            m.put("name", name);
            m.put("origName", origName);
            m.put("notify", notify);
            m.put("reply", reply);
            m.put("inquiry", inquiry);
            m.put("lastSeen", (double) lastSeen);
            m.put("source", source);
            return m;
        }
        static Room fromMap(Map<String, Object> m) {
            Room r = new Room();
            r.chatId = str(m, "chatId");
            r.name = str(m, "name");
            r.origName = str(m, "origName");
            if (r.origName == null || r.origName.isBlank()) r.origName = r.name;   // origName 없는 옛 데이터 보정(다음 sync에서 실제 이름으로 갱신)
            r.notify = Boolean.TRUE.equals(m.get("notify"));
            r.reply = Boolean.TRUE.equals(m.get("reply"));
            r.inquiry = Boolean.TRUE.equals(m.get("inquiry"));
            r.lastSeen = (long) Json.num(m, "lastSeen", 0);
            r.source = m.get("source") == null ? "auto" : m.get("source").toString();
            return r;
        }
        private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : v.toString(); }
    }

    private final Path file;
    private final Map<String, Room> rooms = new LinkedHashMap<>();   // chatId → Room (등록 순서 유지)

    public RoomStore(Path dataDir) {
        this.file = dataDir.resolve("bot_rooms.json");
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            for (Object o : Json.asList(Json.parse(Files.readString(file, StandardCharsets.UTF_8)))) {
                Room r = Room.fromMap(Json.asMap(o));
                if (r.chatId != null) rooms.put(r.chatId, r);
            }
        } catch (Exception e) { System.err.println("bot_rooms.json 읽기 실패(무시): " + e); }
    }

    private synchronized void save() {
        try {
            List<Object> arr = new ArrayList<>(rooms.size());
            for (Room r : rooms.values()) arr.add(r.toMap());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(arr), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { System.err.println("bot_rooms.json 저장 실패: " + e); }
    }

    /** 전체 방 목록(등록 순서). */
    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>(rooms.size());
        for (Room r : rooms.values()) out.add(r.toMap());
        return out;
    }

    public synchronized Room get(String chatId) { return rooms.get(chatId); }

    /**
     * 자동 등록(sync·브릿지): 없으면 <b>권한 off</b>로 새로 추가(source=auto, 카톡 이름). 이미 있으면
     * lastSeen만 갱신하고 <b>이름·권한은 보존</b>한다 — 관리자가 사이트에서 바꾼 표시 이름이 sync로 덮이지 않게.
     * @return 새로 추가됐으면 true
     */
    public synchronized boolean register(String chatId, String detectedName, long nowSec) {
        if (chatId == null || chatId.isBlank()) return false;
        String det = (detectedName == null || detectedName.isBlank()) ? chatId : detectedName;
        Room r = rooms.get(chatId);
        if (r == null) {
            r = new Room();
            r.chatId = chatId;
            r.name = det;        // 기본 표시명 = 감지된 실제 방 이름
            r.origName = det;    // 오리지널(실제) 이름 보존
            r.notify = false; r.reply = false;   // pending: 관리자가 권한 부여
            r.lastSeen = nowSec;
            r.source = "auto";
            rooms.put(chatId, r);
            save();
            return true;
        }
        // 기존 방: 실제 이름(origName)은 항상 최신 감지값으로 갱신, 커스텀 name·권한은 보존(관리자 설정 우선).
        boolean changed = false;
        if (!det.equals(r.origName)) { r.origName = det; changed = true; }
        if (nowSec > 0 && nowSec != r.lastSeen) { r.lastSeen = nowSec; changed = true; }
        if (changed) save();
        return false;
    }

    /** 관리자 수동 추가(이미 있으면 이름만 갱신). source=manual. */
    public synchronized Room addManual(String chatId, String name, long nowSec) {
        if (chatId == null || chatId.isBlank()) throw new IllegalArgumentException("chatId 필요");
        Room r = rooms.get(chatId);
        if (r == null) {
            r = new Room();
            r.chatId = chatId;
            r.source = "manual";
            r.lastSeen = nowSec;
            rooms.put(chatId, r);
        }
        if (name != null && !name.isBlank()) { r.name = name; if (r.origName == null) r.origName = name; }
        else if (r.name == null) r.name = chatId;
        save();
        return r;
    }

    /**
     * 관리자 권한/이름 수정. 존재하지 않으면 null. name이 <b>빈 문자열</b>이면 커스텀 표시명을 지운다
     * (그러면 UI는 origName을 회색 안내로 노출). name이 null(미전송)이면 이름은 손대지 않는다.
     */
    public synchronized Room update(String chatId, String name, boolean notify, boolean reply, boolean inquiry) {
        Room r = rooms.get(chatId);
        if (r == null) return null;
        if (name != null) r.name = name.isBlank() ? null : name;   // 빈 값 = 커스텀 지우기
        r.notify = notify;
        r.reply = reply;
        r.inquiry = inquiry;
        save();
        return r;
    }

    /** notify 권한 있는 방 chatId 목록(공지·리마인더 발송 대상 — 단계 4 발송에서 사용). */
    public synchronized List<String> notifyRooms() {
        List<String> out = new ArrayList<>();
        for (Room r : rooms.values()) if (r.notify) out.add(r.chatId);
        return out;
    }

    /** inquiry(문의 알림) 권한 있는 방 chatId 목록(새 제보/문의 접수 시 발송 대상). */
    public synchronized List<String> inquiryRooms() {
        List<String> out = new ArrayList<>();
        for (Room r : rooms.values()) if (r.inquiry) out.add(r.chatId);
        return out;
    }

    /** 해당 방이 명령 응답 권한을 가지는지(단계 3 명령 게이팅에서 사용). 미등록 방은 false. */
    public synchronized boolean canReply(String chatId) {
        Room r = rooms.get(chatId);
        return r != null && r.reply;
    }
}
