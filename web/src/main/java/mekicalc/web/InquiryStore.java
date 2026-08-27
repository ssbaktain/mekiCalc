package mekicalc.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 유저 제보/문의 저장소(파일: data/inquiries.json + 첨부 이미지 data/inquiries/&lt;id&gt;/).
 *
 * <p>로그인 유저만 접수(익명 허위제보 방지). 각 문의는 접수 계정(sub/email/name), 유형·제목·내용,
 * <b>자동 첨부된 스탯 스냅샷</b>(presetBundle), 첨부 이미지, 접수 <b>IP·시각</b>을 기록한다(악용 방지 목적).
 * 상태는 {@code new}(미확인)/{@code seen}(확인 중)/{@code resolved}(해결). 답장 확장 대비로 {@code messages}
 * 스레드(author=user|admin)를 열어둔다 — 초기 본문은 {@code body}, 이후 왕복은 messages에 append.
 *
 * <p>저트래픽(계정당 하루 20건)이라 전량 메모리 로드 + synchronized 파일 저장(NoticeStore·RoomStore와 동일 방식).
 */
public final class InquiryStore {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 업로드 입력(컨트롤러 → 저장소). 디스크 기록은 저장소가 담당. */
    public static final class Upload {
        public final String name, mime;
        public final byte[] bytes;
        public Upload(String name, String mime, byte[] bytes) { this.name = name; this.mime = mime; this.bytes = bytes; }
    }

    public static final class Inquiry {
        public long id;
        public String sub, email, name;
        public String type, title, body;
        public Object presetBundle;                 // 자동 첨부: 전체 프리셋 스냅샷(불투명 JSON 객체)
        public Object build;                        // 자동 첨부: 현재 빌드 번들(나이트로드 등) — 없으면 null
        public List<Map<String, Object>> attachments = new ArrayList<>();  // [{file,name,size,mime}]
        public List<Map<String, Object>> messages = new ArrayList<>();     // 답장 스레드 [{author,body,at}]
        public String ip;
        public String status = "new";               // new | seen | resolved
        public String adminNote;
        public long createdAt, readAt, updatedAt;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", (double) id);
            m.put("sub", sub); m.put("email", email); m.put("name", name);
            m.put("type", type); m.put("title", title); m.put("body", body);
            m.put("presetBundle", presetBundle);
            m.put("build", build);
            m.put("attachments", attachments);
            m.put("messages", messages);
            m.put("ip", ip);
            m.put("status", status);
            m.put("adminNote", adminNote);
            m.put("createdAt", (double) createdAt);
            m.put("readAt", (double) readAt);
            m.put("updatedAt", (double) updatedAt);
            return m;
        }
        @SuppressWarnings("unchecked")
        static Inquiry fromMap(Map<String, Object> m) {
            Inquiry q = new Inquiry();
            q.id = (long) Json.num(m, "id", 0);
            q.sub = str(m, "sub"); q.email = str(m, "email"); q.name = str(m, "name");
            q.type = str(m, "type"); q.title = str(m, "title"); q.body = str(m, "body");
            q.presetBundle = m.get("presetBundle");
            q.build = m.get("build");
            q.attachments = mapList(m.get("attachments"));
            q.messages = mapList(m.get("messages"));
            q.ip = str(m, "ip");
            q.status = m.get("status") == null ? "new" : m.get("status").toString();
            q.adminNote = str(m, "adminNote");
            q.createdAt = (long) Json.num(m, "createdAt", 0);
            q.readAt = (long) Json.num(m, "readAt", 0);
            q.updatedAt = (long) Json.num(m, "updatedAt", 0);
            return q;
        }
        private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : v.toString(); }
        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> mapList(Object o) {
            List<Map<String, Object>> out = new ArrayList<>();
            if (o instanceof List) for (Object e : (List<?>) o) if (e instanceof Map) out.add((Map<String, Object>) e);
            return out;
        }

        /** 상세용(본문·스탯·첨부·스레드 포함) — 다른 패키지(컨트롤러)에서 접근 가능한 공개 접근자. */
        public Map<String, Object> toClientMapFull() { return toClientMap(true); }

        /** 관리자 목록/상세용 요약 맵(본문 포함 여부는 호출자가 결정). full=false면 본문·번들·첨부 바이트 제외 경량. */
        Map<String, Object> toClientMap(boolean full) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", (double) id);
            m.put("sub", sub); m.put("email", email); m.put("name", name);
            m.put("type", type); m.put("title", title);
            m.put("status", status);
            m.put("ip", ip);
            m.put("createdAt", (double) createdAt);
            m.put("updatedAt", (double) updatedAt);
            m.put("attachmentCount", (double) attachments.size());
            m.put("hasStats", presetBundle != null);
            m.put("hasBuild", build != null);
            if (full) {
                m.put("body", body);
                m.put("presetBundle", presetBundle);
                m.put("build", build);
                m.put("attachments", attachments);
                m.put("messages", messages);
                m.put("adminNote", adminNote);
                m.put("readAt", (double) readAt);
            } else {
                String preview = body == null ? "" : (body.length() > 120 ? body.substring(0, 120) + "…" : body);
                m.put("preview", preview);
            }
            return m;
        }
    }

    private final Path file;
    private final Path attachRoot;   // data/inquiries/
    private final List<Inquiry> items = new ArrayList<>();
    private long seq = 0;

    public InquiryStore(Path dataDir) {
        this.file = dataDir.resolve("inquiries.json");
        this.attachRoot = dataDir.resolve("inquiries");
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            for (Object o : Json.asList(Json.parse(Files.readString(file, StandardCharsets.UTF_8)))) {
                Inquiry q = Inquiry.fromMap(Json.asMap(o));
                items.add(q);
                if (q.id > seq) seq = q.id;
            }
        } catch (Exception e) { System.err.println("inquiries.json 읽기 실패(무시): " + e); }
    }

    private synchronized void save() {
        try {
            List<Object> arr = new ArrayList<>(items.size());
            for (Inquiry q : items) arr.add(q.toMap());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(arr), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { System.err.println("inquiries.json 저장 실패: " + e); }
    }

    /** KST 오늘(00:00~) 해당 sub의 접수 건수 — 일일 제한 판정용. */
    public synchronized int countToday(String sub) {
        long start = LocalDate.now(KST).atStartOfDay(KST).toInstant().toEpochMilli();
        int n = 0;
        for (Inquiry q : items) if (sub != null && sub.equals(q.sub) && q.createdAt >= start) n++;
        return n;
    }

    /** 새 문의 접수. 첨부 바이트를 디스크에 기록하고 메타를 저장한다. */
    public synchronized Inquiry create(String sub, String email, String name, String type, String title,
                                       String body, Object presetBundle, Object build, List<Upload> uploads, String ip) throws IOException {
        Inquiry q = new Inquiry();
        q.id = ++seq;
        q.sub = sub; q.email = email; q.name = name;
        q.type = type; q.title = title; q.body = body;
        q.presetBundle = presetBundle;
        q.build = build;
        q.ip = ip;
        q.status = "new";
        q.createdAt = q.updatedAt = System.currentTimeMillis();
        if (uploads != null && !uploads.isEmpty()) {
            Path dir = attachRoot.resolve(String.valueOf(q.id));
            Files.createDirectories(dir);
            int i = 0;
            for (Upload u : uploads) {
                if (u == null || u.bytes == null || u.bytes.length == 0) continue;
                String ext = extOf(u.name, u.mime);
                String fname = (i++) + ext;
                Files.write(dir.resolve(fname), u.bytes);
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("file", fname);
                a.put("name", u.name);
                a.put("size", (double) u.bytes.length);
                a.put("mime", u.mime);
                q.attachments.add(a);
            }
        }
        items.add(q);
        save();
        return q;
    }

    public synchronized Inquiry get(long id) {
        for (Inquiry q : items) if (q.id == id) return q;
        return null;
    }

    /** 해당 sub의 가장 최근 접수 IP(정지 기록의 참고용). 없으면 null. */
    public synchronized String latestIp(String sub) {
        String ip = null; long best = -1;
        for (Inquiry q : items) if (sub != null && sub.equals(q.sub) && q.createdAt > best) { best = q.createdAt; ip = q.ip; }
        return ip;
    }

    /** 본인 문의 목록(최신순, 경량). */
    public synchronized List<Map<String, Object>> mine(String sub) {
        List<Inquiry> mine = new ArrayList<>();
        for (Inquiry q : items) if (sub != null && sub.equals(q.sub)) mine.add(q);
        mine.sort(Comparator.comparingLong((Inquiry q) -> q.createdAt).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Inquiry q : mine) out.add(q.toClientMap(true));   // 본인은 본문·스레드 포함
        return out;
    }

    /**
     * 관리자 목록(필터: user=sub 또는 email 부분일치, date=yyyy-MM-dd(KST), status). 최신순 경량.
     */
    public synchronized List<Map<String, Object>> list(String user, String date, String status) {
        long dayStart = 0, dayEnd = 0;
        if (date != null && !date.isBlank()) {
            try {
                LocalDate d = LocalDate.parse(date.trim());
                dayStart = d.atStartOfDay(KST).toInstant().toEpochMilli();
                dayEnd = d.plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli();
            } catch (Exception ignored) { }
        }
        String u = user == null ? null : user.trim().toLowerCase();
        List<Inquiry> hit = new ArrayList<>();
        for (Inquiry q : items) {
            if (status != null && !status.isBlank() && !status.equals(q.status)) continue;
            if (dayEnd > 0 && !(q.createdAt >= dayStart && q.createdAt < dayEnd)) continue;
            if (u != null && !u.isEmpty()) {
                String hay = ((q.sub == null ? "" : q.sub) + " " + (q.email == null ? "" : q.email)
                        + " " + (q.name == null ? "" : q.name)).toLowerCase();
                if (!hay.contains(u)) continue;
            }
            hit.add(q);
        }
        hit.sort(Comparator.comparingLong((Inquiry q) -> q.createdAt).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Inquiry q : hit) out.add(q.toClientMap(false));
        return out;
    }

    /** 미확인(new) 건수 — 관리자 배지용. */
    public synchronized int unreadCount() {
        int n = 0; for (Inquiry q : items) if ("new".equals(q.status)) n++; return n;
    }

    /** 관리자 상태 변경(new|seen|resolved). new→다른 상태 전환 시 readAt 기록. */
    public synchronized Inquiry setStatus(long id, String status) {
        Inquiry q = get(id);
        if (q == null) return null;
        if (!"new".equals(status) && q.readAt == 0) q.readAt = System.currentTimeMillis();
        q.status = status;
        q.updatedAt = System.currentTimeMillis();
        save();
        return q;
    }

    /** 답장/메모 스레드에 메시지 추가(author=admin|user). 확장 지점. */
    public synchronized Inquiry addMessage(long id, String author, String body) {
        Inquiry q = get(id);
        if (q == null) return null;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("author", author);
        msg.put("body", body);
        msg.put("at", (double) System.currentTimeMillis());
        q.messages.add(msg);
        q.updatedAt = System.currentTimeMillis();
        save();
        return q;
    }

    /** 첨부 파일 경로(관리자/본인 열람용). 범위 밖이면 null. */
    public synchronized Path attachmentFile(long id, int index) {
        Inquiry q = get(id);
        if (q == null || index < 0 || index >= q.attachments.size()) return null;
        Object f = q.attachments.get(index).get("file");
        if (f == null) return null;
        Path p = attachRoot.resolve(String.valueOf(id)).resolve(f.toString()).normalize();
        if (!p.startsWith(attachRoot.normalize())) return null;   // 경로 탈출 방지
        return Files.isRegularFile(p) ? p : null;
    }

    /** 문의 삭제(메타 + 첨부 폴더). */
    public synchronized boolean delete(long id) {
        Inquiry q = get(id);
        if (q == null) return false;
        items.remove(q);
        save();
        try {
            Path dir = attachRoot.resolve(String.valueOf(id));
            if (Files.isDirectory(dir)) {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            }
        } catch (IOException ignored) { }
        return true;
    }

    private static String extOf(String name, String mime) {
        String n = name == null ? "" : name.toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot >= n.length() - 6) return n.substring(dot);   // 원본 확장자 보존(.png/.jpg/.webp/.gif)
        if (mime == null) return ".bin";
        return switch (mime) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".bin";
        };
    }
}
