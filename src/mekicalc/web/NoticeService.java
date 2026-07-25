package mekicalc.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공지 조회 API 로직. {@link NoticeStore}를 읽어 프론트가 바로 쓸 JSON(맵)으로 포맷한다.
 * 계산 기능이 아닌 첫 기능 — 넥슨 포럼 공지를 크롤링·요약해 우리 사이트 게시판/쿠폰 위젯으로 제공한다.
 */
public final class NoticeService {

    /** 탭 순서(= 게시판 순서). "전체"는 프론트에서 category 미지정으로 요청. */
    public static final List<String> CATEGORIES = List.of("공지사항", "패치노트", "이벤트", "확인 중인 현상");

    private static final int PAGE_SIZE = 10;

    private final NoticeStore store;

    public NoticeService(NoticeStore store) { this.store = store; }

    /** 분류 목록(프론트 탭 구성용) + 각 개수. */
    public Map<String, Object> categories() {
        List<Object> cats = new ArrayList<>();
        for (String c : CATEGORIES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c);
            m.put("count", store.count(c));
            cats.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", cats);
        out.put("total", store.count(null));
        return out;
    }

    /**
     * 공지 목록(게시 시각 내림차순). category=null/빈값이면 전체.
     * @param page 0-based 페이지
     */
    public Map<String, Object> list(String category, int page) { return list(category, page, null); }

    /** @param query 검색어(제목·원문 본문·요약·구조화 텍스트 대상). null/빈값이면 전체. */
    public Map<String, Object> list(String category, int page, String query) {
        String cat = (category == null || category.isBlank() || "전체".equals(category)) ? null : category;
        String q = (query == null || query.isBlank()) ? null : query;
        int total = store.count(cat, q);
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.min(Math.max(0, page), pages - 1);
        int offset = p * PAGE_SIZE;
        List<NoticeStore.Notice> rows = store.list(cat, q, offset, PAGE_SIZE);
        List<Object> items = new ArrayList<>(rows.size());
        for (NoticeStore.Notice n : rows) items.add(toItem(n));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", (double) total);
        out.put("page", (double) p);
        out.put("pages", (double) pages);
        out.put("pageSize", (double) PAGE_SIZE);
        out.put("hasMore", offset + rows.size() < total);
        return out;
    }

    /** 카톡 /공지검색용: 검색어에 걸린 공지 전부 + 각 공지의 검색어 포함 문장(요약/구조화 기준). */
    public List<Map<String, Object>> searchSnippets(String query) {
        return store.searchWithSnippets(query);
    }

    /** 홈 위젯용 현재 유효 쿠폰. */
    public Map<String, Object> coupons(String today) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coupons", store.activeCoupons(today));
        return out;
    }

    /** 목록 항목 — 강한 요약 + 원문 링크(+ 쿠폰). 원문은 우리 서버에 저장하지 않고 링크로만 연결. */
    private static Map<String, Object> toItem(NoticeStore.Notice n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.threadId);
        m.put("category", n.category);
        m.put("headline", n.headline);
        m.put("title", n.title);
        m.put("postedAt", (double) n.postedAt);          // epoch sec (프론트에서 로컬 표시)
        m.put("startAt", (double) n.startAt);            // 이벤트 기간 시작(epoch sec, 0=없음)
        m.put("endAt", (double) n.endAt);                // 이벤트 기간 종료(epoch sec, 0=없음)
        m.put("author", n.author);
        m.put("sticky", n.sticky);
        m.put("summary", n.summary);                     // 레거시(data 없을 때 폴백)
        m.put("data", n.data == null ? null : n.data.toMap());   // 구조화 데이터(신규). 웹 렌더러가 스펙대로 소비
        m.put("status", n.status);                       // "closed"=게시 종료(확인 중인 현상 아카이브)
        m.put("closeNote", n.closeNote);
        m.put("coupons", n.coupons == null ? new ArrayList<>() : n.coupons);
        m.put("sourceUrl", n.sourceUrl);                 // 원문(넥슨 포럼) 링크
        return m;
    }
}
