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
        public long startAt, endAt;                      // 이벤트/기간 시작·종료(epoch sec, KST). 0=없음. 요약 시 추출.
        public boolean sticky;
        public String summary;                          // (레거시) 텍스트 요약. 신규는 data 사용. 둘 다 null → 추출 대기
        public NoticeData data;                         // 구조화 데이터(신규 파이프라인). null이면 레거시 summary 사용
        public String body;                             // 원문 평문(검색 전용). 클라 미노출(toItem 제외). null=미저장
        public String status;                           // null=게시중, "closed"=게시 종료(확인 중인 현상 아카이브)
        public String closeNote;                        // 게시종료 사유(예: "전 항목 수정 완료 · 게시 종료")
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
            m.put("startAt", (double) startAt);
            m.put("endAt", (double) endAt);
            m.put("sticky", sticky);
            m.put("summary", summary);
            m.put("data", data == null ? null : data.toMap());
            m.put("body", body);
            m.put("status", status);
            m.put("closeNote", closeNote);
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
            n.startAt = (long) Json.num(m, "startAt", 0);
            n.endAt = (long) Json.num(m, "endAt", 0);
            n.sticky = Json.bool(m, "sticky", false);
            n.summary = str(m, "summary");
            Object dm = m.get("data");
            if (dm instanceof Map) n.data = NoticeData.fromMap((Map<String, Object>) dm);
            n.body = str(m, "body");
            n.status = str(m, "status");
            n.closeNote = str(m, "closeNote");
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

    /** 전체 공지 스냅샷(만료 리마인더 체커용). */
    public synchronized List<Notice> all() { return new ArrayList<>(byId.values()); }

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
    public enum Upsert { NEW, UPDATED, UNCHANGED }

    /**
     * 메타 업서트. 신규면 추가(추출 대기). 기존이면 메타 갱신하되 <b>data/summary는 보존</b>.
     * 넥슨은 확인 중인 현상 글 수정 시 제목에 갱신일('(7/14 추가)')을 붙이는 관행 → <b>제목 변경 = 갱신(UPDATED)</b>으로 감지.
     * 포럼 목록의 modifyDate는 조회·활동으로 계속 증가해 '수정 신호'로 못 쓰므로 사용하지 않는다.
     */
    public synchronized Upsert upsertMeta(Notice incoming) {
        Notice cur = byId.get(incoming.threadId);
        if (cur == null) {
            byId.put(incoming.threadId, incoming);   // data=null(추출 대기)
            dirty = true;
            return Upsert.NEW;
        }
        boolean titleChanged = !eq(cur.title, incoming.title);
        boolean metaChanged = titleChanged || !eq(cur.headline, incoming.headline)
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
        cur.status = null;   // 목록에 다시 보임 → 게시중으로 복귀
        if (metaChanged) dirty = true;
        return titleChanged ? Upsert.UPDATED : Upsert.UNCHANGED;
    }

    /** 요약 저장(강한 요약 + 쿠폰 + 이벤트 기간). */
    public synchronized void saveSummary(String threadId, String summary,
                                         List<Map<String, Object>> coupons, long at,
                                         long startAt, long endAt) {
        Notice n = byId.get(threadId);
        if (n == null) return;
        n.summary = summary;
        n.coupons = (coupons == null) ? new ArrayList<>() : coupons;
        n.summarizedAt = at;
        n.startAt = startAt;
        n.endAt = endAt;
        dirty = true;
    }

    /** 구조화 데이터 저장 + 리마인더/쿠폰 위젯용 구 필드(endAt, coupons) 브리지. */
    public synchronized void saveData(String threadId, NoticeData d, long at) {
        Notice n = byId.get(threadId);
        if (n == null) return;
        n.data = d;
        n.summary = null;            // 신규는 data 사용
        n.summarizedAt = at;
        n.coupons = new ArrayList<>();
        long start = 0, end = 0;
        if (d != null) {
            for (NoticeData.Coupon c : d.coupons) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", c.code);
                m.put("end", NoticeDates.toYmdFrom(c.expires, n.postedAt));   // 만료 연도=게시일 기준
                m.put("rewards", c.rewards);
                n.coupons.add(m);
            }
            if (d.eventMeta != null) { long[] se = NoticeDates.periodEpochsFrom(d.eventMeta.period, n.postedAt); start = se[0]; end = se[1]; }
        }
        n.startAt = start;
        n.endAt = end;
        dirty = true;
    }

    /** 원문 평문 저장(검색 전용). Gemini와 무관하게 독립 저장 — 추출 실패해도 검색은 되게. */
    public synchronized void saveBody(String threadId, String body) {
        Notice n = byId.get(threadId);
        if (n == null || body == null || body.isBlank()) return;
        n.body = body;
        dirty = true;
    }

    /** 게시 종료 처리(확인 중인 현상 아카이브). data는 보존, 상태만 변경. */
    public synchronized void markClosed(String threadId, String note) {
        Notice n = byId.get(threadId);
        if (n == null || "closed".equals(n.status)) return;
        n.status = "closed";
        n.closeNote = note;
        dirty = true;
    }

    private static boolean needsSummary(Notice n) { return n.data == null && n.summary == null; }

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
        return list(category, null, offset, limit);
    }

    /**
     * 검색·분류 필터 목록(게시 시각 내림차순). query가 있으면 제목·원문 본문·요약·구조화 텍스트를
     * 대상으로 공백 토큰 AND 부분일치 검색한다(원문 본문은 검색 전용, 화면엔 미노출).
     */
    public synchronized List<Notice> list(String category, String query, int offset, int limit) {
        List<Notice> all = filtered(category, query);
        if (offset >= all.size()) return new ArrayList<>();
        int to = Math.min(all.size(), offset + limit);
        return new ArrayList<>(all.subList(offset, to));
    }

    public synchronized int count(String category) { return count(category, null); }

    public synchronized int count(String category, String query) {
        if (query == null || query.isBlank()) {   // 검색 없으면 빠른 경로
            if (category == null) return byId.size();
            int c = 0;
            for (Notice n : byId.values()) if (category.equals(n.category)) c++;
            return c;
        }
        return filtered(category, query).size();
    }

    private List<Notice> filtered(String category, String query) {
        String[] toks = tokenize(query);
        List<Notice> all = new ArrayList<>();
        for (Notice n : byId.values()) {
            if (category != null && !category.equals(n.category)) continue;
            if (toks.length > 0 && !matches(n, toks)) continue;
            all.add(n);
        }
        all.sort(Comparator.comparingLong((Notice n) -> n.postedAt).reversed());
        return all;
    }

    private static String[] tokenize(String q) {
        if (q == null) return new String[0];
        String t = q.trim().toLowerCase();
        if (t.isEmpty()) return new String[0];
        return t.split("\\s+");
    }

    private static boolean matches(Notice n, String[] toks) {
        String blob = searchBlob(n);
        for (String t : toks) if (!blob.contains(t)) return false;   // AND
        return true;
    }

    // 검색 대상 = 우리 요약만(제목 + 레거시 요약 + 구조화 텍스트). 원문 본문은 검색하지 않는다 —
    // 매치가 화면에 보이는 곳(요약)에만 있어야 검색어 하이라이트(ntHl)가 항상 표시되기 때문.
    private static String searchBlob(Notice n) {
        StringBuilder sb = new StringBuilder();
        if (n.title != null) sb.append(n.title).append(' ');
        if (n.summary != null) sb.append(n.summary).append(' ');
        if (n.data != null) appendData(sb, n.data);
        return sb.toString().toLowerCase();
    }

    private static void appendData(StringBuilder sb, NoticeData d) {
        add(sb, d.oneLiner); add(sb, d.title);
        for (NoticeData.Section s : d.sections) {
            add(sb, s.heading);
            for (NoticeData.Item it : s.items) { add(sb, it.text); for (String su : it.sub) add(sb, su); }
        }
        if (d.eventMeta != null) { add(sb, d.eventMeta.benefit); add(sb, d.eventMeta.howTo); add(sb, d.eventMeta.caution); add(sb, d.eventMeta.period); }
        if (d.issues != null) {
            for (NoticeData.OpenIssue o : d.issues.open) { add(sb, o.text); add(sb, o.workaround); }
            for (NoticeData.FixedIssue f : d.issues.fixed) add(sb, f.text);
        }
        for (NoticeData.Coupon c : d.coupons) { add(sb, c.code); for (String r : c.rewards) add(sb, r); }
        if (d.mailRewards != null) { add(sb, d.mailRewards.reason); add(sb, d.mailRewards.target); for (String it : d.mailRewards.items) add(sb, it); }
    }

    private static void add(StringBuilder sb, String s) { if (s != null) sb.append(s).append(' '); }

    /**
     * 카톡 /공지검색용: 검색어에 걸린 공지 전부(카테고리 무관, 게시시각 내림차순) + 각 공지에서 검색어가 든 문장.
     * 웹 검색과 동일 기준(요약/구조화 텍스트=A). 문장은 요약 줄·구조화 항목에서 검색어(모든 토큰)를 포함한 것만, 중복 제거.
     */
    public synchronized List<Map<String, Object>> searchWithSnippets(String query) {
        String[] toks = tokenize(query);
        List<Map<String, Object>> out = new ArrayList<>();
        if (toks.length == 0) return out;
        for (Notice n : filtered(null, query)) {
            List<String> snippets = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String piece : snippetPieces(n)) {
                String p = piece.trim();
                if (p.isEmpty() || !seen.add(p.toLowerCase())) continue;   // 중복 문장 제거
                String lp = p.toLowerCase();
                boolean all = true;
                for (String t : toks) if (!lp.contains(t)) { all = false; break; }
                if (all) snippets.add(p);
            }
            if (snippets.isEmpty() && n.data != null && n.data.oneLiner != null) snippets.add(n.data.oneLiner);   // 제목에만 매치 시 폴백
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", n.title);
            m.put("sourceUrl", n.sourceUrl);
            m.put("snippets", snippets);
            out.add(m);
        }
        return out;
    }

    /** 문장 후보(요약 줄 + 구조화 항목 텍스트). 제목은 헤더로 따로 노출하므로 제외. */
    private static List<String> snippetPieces(Notice n) {
        List<String> out = new ArrayList<>();
        if (n.summary != null) for (String ln : n.summary.split("\\r?\\n")) {
            String s = ln.replaceFirst("^[\\s\\-•*ㄴ]+", "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        if (n.data != null) {
            NoticeData d = n.data;
            addP(out, d.oneLiner);
            for (NoticeData.Section s : d.sections) for (NoticeData.Item it : s.items) { addP(out, it.text); for (String su : it.sub) addP(out, su); }
            if (d.eventMeta != null) { addP(out, d.eventMeta.benefit); addP(out, d.eventMeta.howTo); addP(out, d.eventMeta.caution); }
            if (d.issues != null) {
                for (NoticeData.OpenIssue o : d.issues.open) { addP(out, o.text); addP(out, o.workaround); }
                for (NoticeData.FixedIssue f : d.issues.fixed) addP(out, f.text);
            }
            for (NoticeData.Coupon c : d.coupons) for (String r : c.rewards) addP(out, r);
            if (d.mailRewards != null) for (String it : d.mailRewards.items) addP(out, it);
        }
        return out;
    }
    private static void addP(List<String> out, String s) { if (s != null && !s.isBlank()) out.add(s); }

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
                out.put("rewards", c.get("rewards"));   // 구조화 브리지: 보상 전체(배너 노출)
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
