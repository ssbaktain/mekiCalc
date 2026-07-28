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
    private final BotOutbox outbox;
    private final java.util.Set<String> pendingDeliver = new java.util.HashSet<>();   // 이번 세션 '신규 감지'된 글 → 추출되면 봇 큐에 넣을 대상
    private final java.util.Set<String> pendingReextract = new java.util.HashSet<>(); // 제목 변경(갱신) 감지된 글 → 재추출 + 발송 매트릭스
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

    public NoticeCrawler(NoticeStore store, NoticeSummarizer summarizer, BotOutbox outbox) {
        this.store = store;
        this.summarizer = summarizer;
        this.outbox = outbox;
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
        // 1) 실시간: 각 보드 첫 페이지(최신 100건)에서 신규/갱신 발견 → 메타 저장. issue(6745)는 게시종료 감지용으로 목록 수집.
        java.util.Set<String> seenIssue = new java.util.HashSet<>();
        boolean issueScanOk = false;
        for (Map.Entry<String, String> e : BOARDS.entrySet()) {
            boolean isIssue = ISSUE_BOARD.equals(e.getKey());
            int n = scanPage(e.getKey(), e.getValue(), 1, isIssue ? seenIssue : null);
            if (isIssue && n >= 0) issueScanOk = true;
        }
        // 2) 백필 발견: 과거 페이지(2..MAX_PAGE)를 보드당 한 페이지씩 훑어 메타를 채워 둔다.
        for (Map.Entry<String, String> e : BOARDS.entrySet()) {
            String board = e.getKey();
            if (Boolean.TRUE.equals(backfillDone.get(board))) continue;
            int page = backfillPage.get(board);
            int n = scanPage(board, e.getValue(), page, null);
            if (n < 0) continue;
            if (n < PAGE_SIZE || page >= MAX_PAGE) backfillDone.put(board, true);
            else backfillPage.put(board, page + 1);
        }
        // 3) 게시 종료 감지(확인 중인 현상): 목록에서 사라진 issue 글을 URL 프로브로 삭제/밀림 구분(§3-2). 카톡 발송 없음.
        if (issueScanOk) detectClosedIssues(seenIssue);
        // 4) 추출·발송.
        if (summarizer.enabled()) {
            // 4a) 신규 글 즉시 추출 → 봇 큐 배달(카톡 렌더).
            for (NoticeStore.Notice n : store.pendingSummaries(false, liveSince, SUMMARIES_PER_TICK)) {
                if (summarizeOne(n)) {
                    if (pendingDeliver.remove(n.threadId)) enqueueNotice(n);
                    if (n.postedAt > liveSince) liveSince = n.postedAt;
                }
            }
            // 4b) 갱신 글 재추출 → 발송 매트릭스(§3-3: issue 새 항목/우편 추가만 델타 발송).
            processReextract();
            // 4c) 과거 글 백필 추출(하루 15건).
            refreshBudget();
            int quota = Math.min(backfillRemaining, SUMMARIES_PER_TICK);
            if (quota > 0) {
                for (NoticeStore.Notice n : store.pendingSummaries(true, liveSince, quota)) {
                    if (summarizeOne(n)) { backfillRemaining--; if (backfillRemaining <= 0) break; }
                }
            }
        }
        store.save();
        checkExpiry();
    }

    private static final String ISSUE_BOARD = "6745";   // 확인 중인 현상

    /** 갱신(제목변경) 감지된 글 재추출 + 발송 매트릭스. 이전 data와 새 data를 비교(§3-3). 틱당 소수. */
    private void processReextract() {
        int quota = SUMMARIES_PER_TICK;
        for (String id : new java.util.ArrayList<>(pendingReextract)) {
            if (quota <= 0) break;
            NoticeStore.Notice n = store.get(id);
            if (n == null) { pendingReextract.remove(id); continue; }
            NoticeData old = n.data;                 // 재추출 전 스냅샷(비교용)
            if (!summarizeOne(n)) continue;          // 실패 → 다음 틱 재시도(pendingReextract 유지)
            pendingReextract.remove(id);
            quota--;
            String delta = issueDelta(old, n.data, n);
            if (delta != null) {
                String key = "notice-delta:" + id + ":" + Integer.toHexString(delta.hashCode());
                if (outbox.enqueue(delta, key) > 0)
                    System.out.println("[갱신 델타 큐잉] id=" + id + " title=" + n.title);
            }
        }
    }

    /**
     * 발송 매트릭스(§3-3): 확인 중인 현상 갱신에서 <b>open 새 항목</b> 또는 <b>mail_rewards 새로 추가</b>만 델타 발송.
     * open→fixed 이동·workaround 수정·게시종료는 발송 안 함. 그 외 유형 갱신은 웹만 갱신(발송 없음, §7.10).
     * @return 델타 메시지(있으면), 없으면 null
     */
    static String issueDelta(NoticeData old, NoticeData now, NoticeStore.Notice n) {
        if (now == null) return null;
        boolean isIssue = "issue".equals(now.noticeType) || "확인 중인 현상".equals(n.category);
        if (!isIssue) return null;
        java.util.List<NoticeData.OpenIssue> newOpen = new java.util.ArrayList<>();
        java.util.Set<String> prevOpen = new java.util.HashSet<>();
        if (old != null && old.issues != null) for (NoticeData.OpenIssue o : old.issues.open) if (o.text != null) prevOpen.add(o.text.trim());
        if (now.issues != null) for (NoticeData.OpenIssue o : now.issues.open) if (o.text != null && !prevOpen.contains(o.text.trim())) newOpen.add(o);
        boolean mailAdded = (old == null || old.mailRewards == null) && now.mailRewards != null;
        if (newOpen.isEmpty() && !mailAdded) return null;
        return NoticeKakaoRenderer.renderIssueDelta(newOpen, mailAdded ? now.mailRewards : null, now.dateLabel, n.sourceUrl);
    }

    /** 목록에서 사라진 issue 글을 프로브 → 삭제(게시종료)면 아카이브. 스캔 성공 시에만 호출(오탐 방지). */
    private void detectClosedIssues(java.util.Set<String> seenIssue) {
        for (NoticeStore.Notice n : store.all()) {
            if (!"확인 중인 현상".equals(n.category) || "closed".equals(n.status)) continue;
            if (seenIssue.contains(n.threadId)) continue;   // 목록에 있음
            // 목록에서 사라짐 → URL 직접 조회로 삭제/밀림 구분
            boolean accessible;
            try { getJson(String.format(THREAD_URL, n.threadId)); accessible = true; }
            catch (Exception ex) { accessible = false; }   // 404 등 → 삭제(게시종료)
            if (accessible) continue;                       // 접근 가능 = 목록에서 밀린 것(드묾) → 유지
            boolean allFixed = n.data == null || n.data.issues == null || n.data.issues.open.isEmpty();
            store.markClosed(n.threadId, allFixed ? "전 항목 수정 완료 · 게시 종료" : "게시 종료 (미해결 항목 있음)");
            System.out.println("[게시종료 감지] id=" + n.threadId + " title=" + n.title + " (" + (allFixed ? "전항목수정" : "미해결있음") + ")");
        }
    }

    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");

    /** 신규 공지를 봇 발신 큐에 적재(제목+요약+원문링크). dedupKey로 중복 방지. */
    private void enqueueNotice(NoticeStore.Notice n) {
        outbox.enqueue(buildNoticeMessage(n), "notice:" + n.threadId);
    }

    /** 신규 공지 발신 메시지 본문. 구조화 데이터가 있으면 카톡 렌더러(§5), 없으면 레거시 폴백. 실제 배달·시뮬 공용. */
    private String buildNoticeMessage(NoticeStore.Notice n) {
        if (n.data != null) return NoticeKakaoRenderer.render(n.data, n.sourceUrl);
        String cat = (n.category == null || n.category.isBlank()) ? "공지" : n.category;   // 레거시(구 summary)
        StringBuilder sb = new StringBuilder("[").append(cat).append("] ").append(n.title);
        if (n.summary != null && !n.summary.isBlank()) sb.append("\n\n").append(n.summary.trim());
        if (n.sourceUrl != null) sb.append("\n\n▶ 원문: ").append(n.sourceUrl);
        return sb.toString();
    }

    /**
     * [테스트용] 최신 저장 공지(요약 있는 것)를 크롤러 배달 형식 그대로 큐에 적재 — '지금 새 공지가 떴다'를 재현.
     * 고유 dedupKey로 매번 실제 전송된다(중복차단 회피). 반환: {ok,id,message}.
     */
    public Map<String, Object> simulateLatestNotice() {
        NoticeStore.Notice best = null;
        for (NoticeStore.Notice n : store.all()) {
            if (n.data == null && (n.summary == null || n.summary.isBlank())) continue;
            if (best == null || n.postedAt > best.postedAt) best = n;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (best == null) { out.put("ok", false); out.put("reason", "추출된 공지 없음"); return out; }
        String msg = buildNoticeMessage(best);
        long id = outbox.enqueue(msg, "sim-notice:" + System.currentTimeMillis());
        System.out.println("[시뮬] 신규공지 큐잉 id=" + id + " title=" + best.title);
        out.put("ok", true); out.put("id", (double) id); out.put("message", msg);
        return out;
    }

    /**
     * [테스트용] '지금이 22시'라 가정하고, 가장 가까운 실효 종료일의 이벤트·쿠폰을 묶어 리마인더로 큐잉(22시 게이트 무시).
     * 실제 checkExpiry와 같은 buildReminder를 사용. 반환: {ok,id,message,endDate,events,coupons}.
     */
    public Map<String, Object> simulateReminder() {
        long now = System.currentTimeMillis() / 1000;
        String today = java.time.LocalDate.now(KST).toString();
        List<Map<String, Object>> coupons = store.activeCoupons(today);
        java.util.TreeSet<java.time.LocalDate> dates = new java.util.TreeSet<>();
        for (NoticeStore.Notice n : store.all())
            if ("이벤트".equals(n.category) && n.endAt > now) dates.add(effectiveEndDate(n.endAt));
        for (Map<String, Object> c : coupons) {
            long ce = endOfDayEpoch(c.get("end") == null ? null : c.get("end").toString());
            if (ce > now) dates.add(effectiveEndDate(ce));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (dates.isEmpty()) { out.put("ok", false); out.put("reason", "예정된 종료 없음"); return out; }
        java.time.LocalDate e = dates.first();   // 가장 가까운 실효 종료일
        List<NoticeStore.Notice> evs = new java.util.ArrayList<>();
        for (NoticeStore.Notice n : store.all())
            if ("이벤트".equals(n.category) && n.endAt > now && e.equals(effectiveEndDate(n.endAt))) evs.add(n);
        evs.sort(java.util.Comparator.comparingLong(x -> x.endAt));
        List<Map<String, Object>> cps = new java.util.ArrayList<>();
        for (Map<String, Object> c : coupons) {
            long ce = endOfDayEpoch(c.get("end") == null ? null : c.get("end").toString());
            if (ce > now && e.equals(effectiveEndDate(ce))) cps.add(c);
        }
        String msg = buildReminder(e, evs, cps);
        long id = outbox.enqueue(msg, "sim-remind:" + System.currentTimeMillis());
        System.out.println("[시뮬] 리마인더 큐잉 id=" + id + " 실효종료일=" + e + " 이벤트=" + evs.size() + " 쿠폰=" + cps.size());
        out.put("ok", true); out.put("id", (double) id); out.put("message", msg);
        out.put("endDate", e.toString()); out.put("events", (double) evs.size()); out.put("coupons", (double) cps.size());
        return out;
    }

    /**
     * 종료 임박 리마인더. '실효 종료일 E'(종료시각이 정각 00:00이면 전날) 기준, E 하루 전 22시(KST)에
     * 그 날(E) 종료되는 이벤트·쿠폰 전부를 종료 빠른 순으로 '한 메시지'에 묶어 큐잉. Outbox dedup("remind:E")으로 날짜당 1회.
     * 예: 14일 22시 발화 → 실효종료일 15일(15일까지 종료)인 것들만. 미래(16일+)는 각자 자기 22시에.
     */
    void checkExpiry() {
        long now = System.currentTimeMillis() / 1000;
        String today = java.time.LocalDate.now(KST).toString();
        List<Map<String, Object>> coupons = store.activeCoupons(today);

        // 22시 게이트를 이미 지난 '실효 종료일' 수집(= 지금 알려야 할 날짜)
        java.util.Set<java.time.LocalDate> due = new java.util.LinkedHashSet<>();
        for (NoticeStore.Notice n : store.all()) {
            if ("이벤트".equals(n.category) && n.endAt > now) {
                java.time.LocalDate e = effectiveEndDate(n.endAt);
                if (now >= reminderEpoch(e)) due.add(e);
            }
        }
        for (Map<String, Object> c : coupons) {
            long ce = endOfDayEpoch(c.get("end") == null ? null : c.get("end").toString());
            if (ce > now) {
                java.time.LocalDate e = effectiveEndDate(ce);
                if (now >= reminderEpoch(e)) due.add(e);
            }
        }
        for (java.time.LocalDate e : due) {
            List<NoticeStore.Notice> evs = new java.util.ArrayList<>();
            for (NoticeStore.Notice n : store.all()) {
                if ("이벤트".equals(n.category) && n.endAt > now && e.equals(effectiveEndDate(n.endAt))) evs.add(n);
            }
            evs.sort(java.util.Comparator.comparingLong(x -> x.endAt));   // 종료 빠른 순
            List<Map<String, Object>> cps = new java.util.ArrayList<>();
            for (Map<String, Object> c : coupons) {
                long ce = endOfDayEpoch(c.get("end") == null ? null : c.get("end").toString());
                if (ce > now && e.equals(effectiveEndDate(ce))) cps.add(c);
            }
            if (evs.isEmpty() && cps.isEmpty()) continue;
            long id = outbox.enqueue(buildReminder(e, evs, cps), "remind:" + e);
            if (id > 0) System.out.println("[리마인더 큐잉] 실효종료일=" + e + " 이벤트=" + evs.size() + " 쿠폰=" + cps.size());
        }
    }

    /** 실효 종료일: 종료시각이 정각 00:00:00이면 '전날'(자정=전날 마감), 그 외엔 그 날짜. */
    private static java.time.LocalDate effectiveEndDate(long endEpoch) {
        java.time.ZonedDateTime z = java.time.Instant.ofEpochSecond(endEpoch).atZone(KST);
        java.time.LocalDate d = z.toLocalDate();
        if (z.getHour() == 0 && z.getMinute() == 0 && z.getSecond() == 0) d = d.minusDays(1);
        return d;
    }
    /** 실효 종료일 E → 리마인더 발화 시각((E-1)일 22:00 KST) epoch. */
    private static long reminderEpoch(java.time.LocalDate e) {
        return e.minusDays(1).atTime(22, 0).atZone(KST).toEpochSecond();
    }

    /** 종료 임박 메시지(한 통, 종료 빠른 순). 제목 뒤 중복 '(~M/D)'는 떼고 정확 종료시각만. 사이트 링크 없음. */
    private static String buildReminder(java.time.LocalDate endDate, List<NoticeStore.Notice> events, List<Map<String, Object>> coupons) {
        StringBuilder sb = new StringBuilder("⏰ 종료 임박 안내 (").append(fmtDate(endDate)).append(" 종료)");
        if (!events.isEmpty()) {
            sb.append("\n\n📌 이벤트");
            for (NoticeStore.Notice n : events) {
                sb.append("\n· ").append(cleanTitle(n.title));
                if (n.endAt > 0) sb.append(" (~").append(fmtTime(n.endAt)).append(")");
            }
        }
        if (!coupons.isEmpty()) {
            sb.append("\n\n🎟 쿠폰");
            for (Map<String, Object> c : coupons) {
                Object code = c.get("code");
                if (code == null) continue;
                sb.append("\n· ").append(code);
                long ce = endOfDayEpoch(c.get("end") == null ? null : c.get("end").toString());
                if (ce > 0) sb.append(" (~").append(fmtTime(ce)).append(")");
            }
        }
        return sb.toString();
    }

    /** 제목 끝의 중복 기간표기 '(~7/22)' 같은 걸 떼어낸다(리마인더엔 정확 종료시각을 따로 붙이므로). */
    private static String cleanTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("\\s*\\(\\s*~[^)]*\\)\\s*$", "").trim();
    }

    /** yyyy-MM-dd → 그 날 23:59:59(KST) epoch. 파싱 실패 0. */
    private static long endOfDayEpoch(String date) {
        if (date == null || date.isBlank()) return 0;
        try { return java.time.LocalDate.parse(date.trim()).atTime(23, 59, 59).atZone(KST).toEpochSecond(); }
        catch (Exception e) { return 0; }
    }
    private static String fmtDate(java.time.LocalDate d) {
        String[] dow = {"월","화","수","목","금","토","일"};
        return d.getMonthValue() + "/" + d.getDayOfMonth() + "(" + dow[d.getDayOfWeek().getValue() - 1] + ")";
    }
    private static String fmtTime(long epoch) {
        java.time.ZonedDateTime z = java.time.Instant.ofEpochSecond(epoch).atZone(KST);
        return String.format("%d/%d %02d:%02d", z.getMonthValue(), z.getDayOfMonth(), z.getHour(), z.getMinute());
    }

    /** 게시판 한 페이지를 읽어 메타 업서트. seenThisTick!=null이면 본 threadId 수집(게시종료 감지용). 반환: 글 수(오류 -1). */
    private int scanPage(String board, String category, int pageNo, java.util.Set<String> seenThisTick) {
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
                if (n == null) continue;
                if (seenThisTick != null) seenThisTick.add(n.threadId);
                NoticeStore.Upsert u = store.upsertMeta(n);
                if (u == NoticeStore.Upsert.NEW && n.postedAt >= liveSince) {
                    pendingDeliver.add(n.threadId);   // 추출되면 봇 큐(Outbox)로 배달
                    System.out.println("[신규공지 감지] crawledAt=" + java.time.Instant.now()
                            + " postedAt=" + n.postedAt + " board=" + category + " id=" + n.threadId + " title=" + n.title);
                } else if (u == NoticeStore.Upsert.UPDATED) {
                    pendingReextract.add(n.threadId);   // 제목 변경 = 갱신 → 재추출 + 발송 매트릭스
                    System.out.println("[공지 갱신 감지] board=" + category + " id=" + n.threadId + " title=" + n.title);
                }
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
            store.saveBody(n.threadId, text);     // 검색 전용 원문 저장(추출 성공 여부와 무관)
            NoticeData d = summarizer.extractStructured(n.category, n.title, text, n.sourceUrl);
            store.saveData(n.threadId, d, System.currentTimeMillis() / 1000);
            return true;
        } catch (Exception ex) {
            System.err.println("공지 구조화 추출 실패(thread=" + n.threadId + "): " + ex.getMessage());
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
