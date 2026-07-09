package mekicalc.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mekicalc.json.Json;

/**
 * 공지(넥슨 포럼 크롤링 결과) 저장소. 파일 기반(data/notices.json) — Postgres 없이 로컬·프로덕션 모두 동작한다.
 * 공지 규모는 수백 건 수준이라 전체를 메모리에 올리고 변경 시 파일에 통째로 쓴다(원자적 교체).
 *
 * <p>모든 접근은 {@code synchronized}(저트래픽 · 1분 폴링이라 경합 미미). 저장 내용은 우리가 만든 <b>요약</b>과
 * 메타데이터뿐이며 원문 본문은 담지 않는다(원문은 sourceUrl 링크로 연결).
 */
public final class NoticeStore {

    /** 공지 한 건. 크롤러가 채우고 API가 읽는다. */
    public static final class Notice {
        public String threadId, boardId, category, headline, title, sourceUrl, author, thumbnail;
        public long postedAt, modifyAt, summarizedAt;   // epoch seconds (summarizedAt=0 → 요약 아직)
        public boolean sticky;
        public String summary;                          // 강한 요약(짧게, 개조식). null → 요약 대기
        public List<Map<String, Object>> coupons = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("threadId", threadId);
            m.put("boardId", boardId);
            m.put("category", category);
            m.put("headline", headline);
            m.put("title", title);
            m.put("sourceUrl", sourceUrl);
            m.put("author", author);
            m.put("thumbnail", thumbnail);
            m.put("postedAt", (double) postedAt);
            m.put("modifyAt", (double) modifyAt);
            m.put("summarizedAt", (double) summarizedAt);
            m.put("sticky", sticky);
            m.put("summary", summary);
            m.put("coupons", coupons);
            return m;
        }

        @SuppressWarnings("unchecked")
        static Notice fromMap(Map<String, Object> m) {
            Notice n = new Notice();
            n.threadId = str(m, "threadId");
            n.boardId = str(m, "boardId");
            n.category = str(m, "category");
            n.headline = str(m, "headline");
            n.title = str(m, "title");
            n.sourceUrl = str(m, "sourceUrl");
            n.author = str(m, "author");
            n.thumbnail = str(m, "thumbnail");
            n.postedAt = (long) Json.num(m, "postedAt", 0);
            n.modifyAt = (long) Json.num(m, "modifyAt", 0);
            n.summarizedAt = (long) Json.num(m, "summarizedAt", 0);
            n.sticky = Json.bool(m, "sticky", false);
            n.summary = str(m, "summary");
            Object cs = m.get("coupons");
            if (cs instanceof List) {
                for (Object o : (List<Object>) cs) {
                    if (o instanceof Map) n.coupons.add((Map<String, Object>) o);
                }
            }
            return n;
        }

        private static String str(Map<String, Object> m, String k) {
            Object v = m.get(k);
            return v == null ? null : v.toString();
        }
    }

    private final Path file;
    private final Map<String, Notice> byId = new LinkedHashMap<>();   // threadId → Notice
    private boolean dirty = false;                                    // 실제 변경 발생 시에만 저장

    public NoticeStore(Path dataDir) {
        this.file = dataDir.resolve("notices.json");
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            for (Object o : Json.asList(parsed)) {
                Notice n = Notice.fromMap(Json.asMap(o));
                if (n.threadId != null) byId.put(n.threadId, n);
            }
        } catch (Exception e) {
            System.err.println("notices.json 읽기 실패(무시하고 새로 시작): " + e);
        }
    }

    /** 변경분을 파일에 통째로 저장(원자적 교체). 크롤러가 폴링 틱 끝에 호출 — 변경 없으면 no-op. */
    public synchronized void save() {
        if (!dirty) return;
        try {
            List<Object> arr = new ArrayList<>(byId.size());
            for (Notice n : byId.values()) arr.add(n.toMap());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(arr), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            System.err.println("notices.json 저장 실패: " + e);
        }
    }

    public synchronized boolean isEmpty() { return byId.isEmpty(); }

    public synchronized boolean contains(String threadId) { return byId.containsKey(threadId); }

    /** 단건 조회(상세 페이지용). 없으면 null. */
    public synchronized Notice get(String threadId) { return byId.get(threadId); }

    /** 저장된 공지 중 가장 최신 게시 시각(epoch sec). 없으면 0. 기동 시 '실시간 기준선' 계산용. */
    public synchronized long maxPostedAt() {
        long max = 0;
        for (Notice n : byId.values()) if (n.postedAt > max) max = n.postedAt;
        return max;
    }

    /**
     * 메타데이터 업서트. 신규면 추가(요약 없음), 기존이면 제목/고정/수정시각 등 메타만 갱신하되 <b>요약은 보존</b>한다.
     * 단, 원문이 수정되어 modifyAt이 커졌으면 요약을 무효화(null)해 다음 폴링에서 재요약하게 한다.
     * @return 요약이 필요한(신규이거나 수정으로 무효화된) 경우 true
     */
    public synchronized boolean upsertMeta(Notice incoming) {
        Notice cur = byId.get(incoming.threadId);
        if (cur == null) {
            byId.put(incoming.threadId, incoming);   // summary=null 상태로 들어감
            dirty = true;
            return true;
        }
        // 주의: 포럼 목록의 modifyDate는 조회·활동에 따라 계속 증가해서 '수정 감지' 신호로 못 쓴다
        // (그걸로 판단하면 매 틱 대부분의 글이 '수정됨'으로 오판돼 요약이 지워지고 재요약 폭주). → 요약은 절대 무효화하지 않는다.
        boolean metaChanged = !eq(cur.title, incoming.title) || !eq(cur.headline, incoming.headline)
                || cur.sticky != incoming.sticky || !eq(cur.sourceUrl, incoming.sourceUrl);
        cur.boardId = incoming.boardId;
        cur.category = incoming.category;
        cur.headline = incoming.headline;
        cur.title = incoming.title;
        cur.sourceUrl = incoming.sourceUrl;
        cur.author = incoming.author;
        cur.thumbnail = incoming.thumbnail;
        cur.postedAt = incoming.postedAt;
        cur.modifyAt = incoming.modifyAt;
        cur.sticky = incoming.sticky;
        if (metaChanged) dirty = true;
        return needsSummary(cur);   // 요약이 없는(신규) 것만 요약 대상
    }

    /** 요약 저장(강한 요약 + 쿠폰). */
    public synchronized void saveSummary(String threadId, String summary,
                                         List<Map<String, Object>> coupons, long at) {
        Notice n = byId.get(threadId);
        if (n == null) return;
        n.summary = summary;
        n.coupons = (coupons == null) ? new ArrayList<>() : coupons;
        n.summarizedAt = at;
        dirty = true;
    }

    private static boolean needsSummary(Notice n) { return n.summary == null; }

    private static boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }

    /**
     * 요약이 필요한 공지 목록(summary==null). historical=false면 기준선 이후(신규, 즉시 요약 대상),
     * true면 기준선 이전(과거, 백필 대상). 신규는 최신부터, 과거는 최신부터(최근 것 먼저 채움).
     */
    public synchronized List<Notice> pendingSummaries(boolean historical, long liveSince, int limit) {
        List<Notice> out = new ArrayList<>();
        for (Notice n : byId.values()) {
            if (!needsSummary(n)) continue;
            boolean isHistorical = n.postedAt < liveSince;
            if (isHistorical != historical) continue;
            out.add(n);
        }
        out.sort(Comparator.comparingLong((Notice n) -> n.postedAt).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    // ──────────────────── 조회(API용) ────────────────────

    /**
     * 공지 목록(게시 시각 내림차순). category=null이면 전체, 아니면 해당 분류만. 요약 안 된 항목도 포함한다
     * (제목·원문 링크는 바로 노출, 요약은 준비되는 대로 채워짐).
     */
    public synchronized List<Notice> list(String category, int offset, int limit) {
        List<Notice> all = new ArrayList<>();
        for (Notice n : byId.values()) {
            if (category != null && !category.equals(n.category)) continue;
            all.add(n);
        }
        all.sort(Comparator.comparingLong((Notice n) -> n.postedAt).reversed());
        if (offset >= all.size()) return new ArrayList<>();
        int to = Math.min(all.size(), offset + limit);
        return new ArrayList<>(all.subList(offset, to));
    }

    public synchronized int count(String category) {
        if (category == null) return byId.size();
        int c = 0;
        for (Notice n : byId.values()) if (category.equals(n.category)) c++;
        return c;
    }

    /**
     * 현재 유효한 쿠폰 목록(종료일이 없거나 오늘 이후). 최신 공지부터. 각 항목에 출처(제목/링크/분류)를 붙여 반환.
     * @param today "YYYY-MM-DD" (서버 로컬 날짜) — 종료일 비교용
     */
    public synchronized List<Map<String, Object>> activeCoupons(String today) {
        List<Notice> src = new ArrayList<>(byId.values());
        src.sort(Comparator.comparingLong((Notice n) -> n.postedAt).reversed());
        // 코드 중복 제거(최신 공지 우선).
        Map<String, Map<String, Object>> uniq = new LinkedHashMap<>();
        for (Notice n : src) {
            if (n.coupons == null) continue;
            for (Map<String, Object> c : n.coupons) {
                Object codeO = c.get("code");
                if (codeO == null) continue;
                String code = codeO.toString().trim();
                if (code.isEmpty() || uniq.containsKey(code)) continue;
                Object end = c.get("end");
                if (end != null && !end.toString().isBlank() && end.toString().compareTo(today) < 0) continue;   // 만료
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("code", code);
                out.put("start", c.get("start"));
                out.put("end", c.get("end"));
                out.put("desc", c.get("desc"));
                out.put("title", n.title);
                out.put("sourceUrl", n.sourceUrl);
                out.put("category", n.category);
                uniq.put(code, out);
            }
        }
        return new ArrayList<>(uniq.values());
    }

    /** 분류별 개수(디버그/기동 로그용). */
    public synchronized Map<String, Integer> countsByCategory() {
        Map<String, Integer> m = new TreeMap<>();
        for (Notice n : byId.values()) m.merge(n.category == null ? "?" : n.category, 1, Integer::sum);
        return m;
    }
}
