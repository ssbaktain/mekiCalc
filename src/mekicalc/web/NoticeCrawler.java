package mekicalc.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import mekicalc.json.Json;

/**
 * 넥슨 포럼(메이플스토리 키우기) 공지 크롤러 + 요약 파이프라인.
 *
 * <p><b>폴링(1분)</b>: 4개 게시판의 최신 목록을 조회해 신규/수정 글을 발견하면 메타데이터를 저장한다(제목·말머리·게시일시·원문링크).
 * 목록 조회는 가벼워 1분 주기여도 부하가 작다.
 *
 * <p><b>요약 분리</b>: 본문 fetch + LLM 요약은 <i>필요할 때만</i> 돈다.
 * <ul>
 *   <li>신규 글(기동 기준선 이후 게시): 즉시 요약(빠른 전달). 틱당 소수로 제한.</li>
 *   <li>과거 글(기준선 이전): <b>하루 15건</b> 백필(요약 쿼터 절약).</li>
 * </ul>
 * 이렇게 폴링 빈도와 요약 비용을 분리해 "빠른 전달 + 저비용"을 함께 얻는다.
 *
 * <p>넥슨 API는 {@code /api/v1/board/{boardId}/threads}(목록) · {@code /api/v1/thread/{threadId}}(본문). 로그인 불필요.
 */
public final class NoticeCrawler {

    private static final String ALIAS = "maplestoryidle-kr";
    // 넥슨 목록은 '블록 커서' 페이지네이션: blockStartNo=1 + 센티넬 blockStartKey 2개(첫 블록 시작)를 고정하면
    // 같은 블록 안에서 pageNo만 올려 다음 페이지를 얻는다. blockSize=5 × pageSize=100 = 블록당 500건 수용이라
    // 어떤 보드도 첫 블록(최대 5페이지) 안에서 전부 조회된다(현재 최다 공지 167건).
    private static final String LIST_URL =
            "https://forum.nexon.com/api/v1/board/%s/threads?alias=" + ALIAS
            + "&pageNo=%d&pageSize=" + 100 + "&paginationType=PAGING&blockSize=5&hideType=WEB"
            + "&includeAdminThread=true&likeCount=false"
            + "&blockStartNo=1&blockStartKey=253402300799&blockStartKey=9223372036854775807";
    private static final String THREAD_URL = "https://forum.nexon.com/api/v1/thread/%s?alias=" + ALIAS;
    private static final String VIEW_URL = "https://forum.nexon.com/" + ALIAS + "/board_view?board=%s&thread=%s";
    // 정직하게 신원을 밝히는 봇 UA(구글봇/빙봇의 '+URL' 관례). 브라우저 위장 대신 팬사이트임을 밝혀 선의를 드러낸다.
    // 헤더는 ASCII만 허용되므로 영문. (WAF 호환 위해 'Mozilla/5.0 (compatible; ...)' 관용 형식 유지.)
    private static final String UA =
            "Mozilla/5.0 (compatible; MekiCalcBot/1.0; unofficial MapleStory Idle fan site; +https://mekicalc.com)";

    /** 게시판(boardId → 분류 라벨). 순서 = 탭 순서. */
    private static final Map<String, String> BOARDS = new LinkedHashMap<>();
    static {
        BOARDS.put("6633", "공지사항");
        BOARDS.put("6698", "패치노트");
        BOARDS.put("6697", "이벤트");
        BOARDS.put("6745", "확인 중인 현상");
    }

    private static final int PAGE_SIZE = 100;              // 목록 페이지 크기(위 LIST_URL과 일치)
    private static final int MAX_PAGE = 5;                 // blockSize=5 → 첫 블록 최대 5페이지
    private static final int DAILY_BACKFILL_BUDGET = 15;   // 과거 공지 요약: 하루 15건
    private static final int SUMMARIES_PER_TICK = 5;       // RPM 보호: 틱당 요약 상한(신규·백필 각각)

    private final NoticeStore store;
    private final NoticeSummarizer summarizer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "notice-crawler");
                t.setDaemon(true);
                return t;
            });

    private long liveSince;                    // epoch sec. 이 시각 이후 게시 글 = '신규'(즉시 요약)
    private long budgetDay = -1;               // 마지막으로 백필 예산을 리셋한 날(epoch day)
    private int backfillRemaining = DAILY_BACKFILL_BUDGET;
    private final Map<String, Integer> backfillPage = new LinkedHashMap<>();   // boardId → 다음에 훑을 과거 페이지
    private final Map<String, Boolean> backfillDone = new LinkedHashMap<>();

    public NoticeCrawler(NoticeStore store, NoticeSummarizer summarizer) {
        this.store = store;
        this.summarizer = summarizer;
        for (String b : BOARDS.keySet()) { backfillPage.put(b, 2); backfillDone.put(b, false); }   // 페이지1은 실시간이 담당
    }

    public void start() {
        // 기동 기준선: 지금까지 저장된 최신 게시 시각. 최초 실행(빈 저장소)이면 '지금' → 기존 글은 전부 과거(백필), 이후 글만 신규.
        long max = store.maxPostedAt();
        liveSince = (max > 0) ? max : (System.currentTimeMillis() / 1000);
        scheduler.scheduleWithFixedDelay(this::safeTick, 5, 60, TimeUnit.SECONDS);
        System.out.println(" 공지 크롤러: 1분 폴링 시작 (요약=" + summarizer.provider()
                + ", 기준선=" + liveSince + ", 저장=" + store.count(null) + "건)");
    }

    public void stop() { scheduler.shutdownNow(); }

    private void safeTick() {
        try { tick(); }
        catch (Throwable t) { System.err.println("공지 폴링 틱 오류(다음 주기 재시도): " + t); }
    }

    private void tick() {
        // 1) 실시간: 각 보드 첫 페이지(최신 100건)에서 신규/수정 발견 → 메타 저장.
        for (Map.Entry<String, String> e : BOARDS.entrySet()) scanPage(e.getKey(), e.getValue(), 1);
        // 2) 백필 발견: 과거 페이지(2..MAX_PAGE)를 보드당 한 페이지씩 훑어 메타를 채워 둔다(요약은 아래 예산 내에서).
        for (Map.Entry<String, String> e : BOARDS.entrySet()) {
            String board = e.getKey();
            if (Boolean.TRUE.equals(backfillDone.get(board))) continue;
            int page = backfillPage.get(board);
            int n = scanPage(board, e.getValue(), page);
            if (n < 0) continue;                                     // 조회 오류 → 다음 틱 재시도(페이지 고정)
            if (n < PAGE_SIZE || page >= MAX_PAGE) backfillDone.put(board, true);   // 마지막 페이지(덜 참) 또는 블록 끝
            else backfillPage.put(board, page + 1);
        }
        // 3) 신규 글 즉시 요약(빠른 전달). 예산 무관, 틱당 소수.
        if (summarizer.enabled()) {
            for (NoticeStore.Notice n : store.pendingSummaries(false, liveSince, SUMMARIES_PER_TICK)) {
                if (summarizeOne(n) && n.postedAt > liveSince) liveSince = n.postedAt;
            }
            // 4) 과거 글 백필 요약(하루 15건).
            refreshBudget();
            int quota = Math.min(backfillRemaining, SUMMARIES_PER_TICK);
            if (quota > 0) {
                for (NoticeStore.Notice n : store.pendingSummaries(true, liveSince, quota)) {
                    if (summarizeOne(n)) { backfillRemaining--; if (backfillRemaining <= 0) break; }
                }
            }
        }
        store.save();   // 실제 변경이 있을 때만 파일에 씀(내부 dirty 플래그)
    }

    /** 게시판 한 페이지를 읽어 메타 업서트. 반환: 페이지의 글 수(조회 오류면 -1). */
    private int scanPage(String board, String category, int pageNo) {
        try {
            Map<String, Object> root = getJson(String.format(LIST_URL, board, pageNo));
            Object threads = root.get("threads");
            if (!(threads instanceof List)) return 0;
            List<?> list = (List<?>) threads;
            for (Object o : list) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> t = (Map<String, Object>) o;
                NoticeStore.Notice n = toNotice(t, board, category);
                if (n != null) store.upsertMeta(n);
            }
            return list.size();
        } catch (Exception ex) {
            System.err.println("공지 목록 조회 실패(board=" + board + ", page=" + pageNo + "): " + ex.getMessage());
            return -1;
        }
    }

    /** 목록 항목(JSON) → Notice 메타. */
    private static NoticeStore.Notice toNotice(Map<String, Object> t, String board, String category) {
        Object idO = t.get("threadId");
        if (idO == null) return null;
        NoticeStore.Notice n = new NoticeStore.Notice();
        n.threadId = idO.toString();
        n.boardId = board;
        n.category = category;
        n.headline = str(t, "headlineName");
        n.title = str(t, "title");
        n.postedAt = (long) Json.num(t, "createDate", 0);
        n.modifyAt = (long) Json.num(t, "modifyDate", 0);
        n.sticky = Json.bool(t, "isSticky", false);
        n.thumbnail = str(t, "thumbnailImageUrl");
        Object user = t.get("user");
        if (user instanceof Map) n.author = str((Map<?, ?>) user, "nickname");
        n.sourceUrl = String.format(VIEW_URL, board, n.threadId);
        return n;
    }

    /**
     * 본문을 fetch → 태그 제거한 평문으로 요약(강한 요약 + 쿠폰) → 저장. 성공 true.
     * 원문 이미지·표·본문은 저작권/약관 리스크 때문에 저장하지 않는다(상세는 '원문 보기' 링크로 대체).
     */
    private boolean summarizeOne(NoticeStore.Notice n) {
        try {
            Map<String, Object> detail = getJson(String.format(THREAD_URL, n.threadId));
            String text = htmlToText(str(detail, "content"));
            if (text.isBlank()) text = n.title;   // 본문이 이미지뿐이면 제목이라도
            NoticeSummarizer.Result r = summarizer.summarize(n.category, n.title, text);
            store.saveSummary(n.threadId, r.summary, r.coupons, System.currentTimeMillis() / 1000);
            return true;
        } catch (Exception ex) {
            System.err.println("공지 요약 실패(thread=" + n.threadId + "): " + ex.getMessage());
            return false;
        }
    }

    private void refreshBudget() {
        long day = System.currentTimeMillis() / 86_400_000L;
        if (day != budgetDay) { budgetDay = day; backfillRemaining = DAILY_BACKFILL_BUDGET; }
    }

    // ──────────────────── HTTP / HTML ────────────────────

    private Map<String, Object> getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new Exception("HTTP " + resp.statusCode());
        Object parsed = Json.parse(resp.body());
        if (!(parsed instanceof Map)) throw new Exception("JSON 객체 아님");
        return Json.asMap(parsed);
    }

    /** 아주 단순한 HTML → 평문 변환(블록 태그는 줄바꿈, 나머지 태그 제거, 엔티티 디코드, 공백 정리). */
    static String htmlToText(String html) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?i)<\\s*(br|/p|/div|/li|/h[1-6]|/tr)\\s*/?>", "\n");
        s = s.replaceAll("(?i)<\\s*li[^>]*>", "\n- ");
        s = s.replaceAll("(?is)<\\s*(script|style)[^>]*>.*?<\\s*/\\s*\\1\\s*>", " ");
        s = s.replaceAll("<[^>]+>", " ");                 // 남은 태그 제거
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        s = s.replaceAll("&#(\\d+);", " ");               // 남은 숫자 엔티티는 공백 처리(방어)
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");       // 가로 공백 압축
        s = s.replaceAll(" *\n *", "\n");                  // 줄 주변 공백 정리
        s = s.replaceAll("\n{3,}", "\n\n");                // 빈 줄 3개+ → 2개
        return s.trim();
    }

    private static String str(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
}
