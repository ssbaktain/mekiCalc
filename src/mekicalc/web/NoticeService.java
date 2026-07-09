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

    private static final int PAGE_SIZE = 20;

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
    public Map<String, Object> list(String category, int page) {
        String cat = (category == null || category.isBlank() || "전체".equals(category)) ? null : category;
        int total = store.count(cat);
        int offset = Math.max(0, page) * PAGE_SIZE;
        List<NoticeStore.Notice> rows = store.list(cat, offset, PAGE_SIZE);
        List<Object> items = new ArrayList<>(rows.size());
        for (NoticeStore.Notice n : rows) items.add(toItem(n));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", (double) total);
        out.put("page", (double) Math.max(0, page));
        out.put("pageSize", (double) PAGE_SIZE);
        out.put("hasMore", offset + rows.size() < total);
        return out;
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
        m.put("author", n.author);
        m.put("sticky", n.sticky);
        m.put("summary", n.summary);                     // null이면 프론트가 '요약 준비 중' 표시
        m.put("coupons", n.coupons == null ? new ArrayList<>() : n.coupons);
        m.put("sourceUrl", n.sourceUrl);                 // 원문(넥슨 포럼) 링크
        return m;
    }
}
