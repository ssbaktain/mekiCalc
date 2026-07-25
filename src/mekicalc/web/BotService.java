package mekicalc.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 카카오톡 봇(메신저봇R/Iris) 명령 처리. 폰(에뮬)의 리스너가 방에 올라온 명령을 이 엔드포인트로 넘기면,
 * 명령을 파싱해 응답 문자열을 만들어 돌려준다. 모든 로직은 서버에 둬서 명령을 추가/수정할 때
 * 봇/리스너는 안 건드리고 서버만 재배포한다.
 *
 * <p>지원 명령(멘션 '@MekiCalc' 또는 '/' 접두 무관):
 * <ul>
 *   <li>명령어 / 도움 — 명령어 목록('/명령어' 자신은 목록에서 숨김)</li>
 *   <li>공지 — 최근 공지</li>
 *   <li>공지검색 &lt;키워드&gt; — 요약본에서 키워드 검색</li>
 *   <li>이벤트 — 진행 중(최근) 이벤트</li>
 *   <li>쿠폰 — 현재 사용 가능한 쿠폰</li>
 *   <li>링크 — 웹사이트 링크</li>
 * </ul>
 * 인식 못 한 입력은 무응답(도배 방지).
 */
public final class BotService {

    private static final int NOTICE_LIMIT = 3;
    private static final int MAX_LIST = 20;   // 이벤트/쿠폰 목록 안전 상한(사실상 전부)
    private static final String SITE = "https://mekicalc.com";

    private BotService() {}

    /** @return {"reply": 문자열 or null}. reply=null이면 봇이 아무 답도 안 함. */
    public static Map<String, Object> handle(NoticeService notices, String room, String sender, String msg) {
        String reply;
        switch (route(parseCmd(msg))) {
            case "coupon": reply = coupons(notices); break;
            case "notice": reply = latest(notices, null, NOTICE_LIMIT, "📢 최근 공지"); break;
            case "event":  reply = events(notices); break;
            case "search": reply = search(notices, parseArg(msg)); break;
            case "link":   reply = link(); break;
            case "help":   reply = help(); break;
            default:       reply = null;   // 인식 못 한 명령 → 무응답
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reply", reply);
        return out;
    }

    /** 메시지에서 첫 명령 토큰을 뽑는다('@MekiCalc'·'/' 접두 제거 후 첫 단어, 소문자화). */
    private static String parseCmd(String msg) {
        String t = (msg == null) ? "" : msg.trim();
        if (t.startsWith("@MekiCalc")) t = t.substring("@MekiCalc".length()).trim();
        else if (t.startsWith("@메키캘크")) t = t.substring("@메키캘크".length()).trim();
        if (t.startsWith("/")) t = t.substring(1).trim();
        if (t.isEmpty()) return "";
        return t.split("\\s+")[0].toLowerCase();
    }

    /** 명령 뒤 인자(첫 토큰 이후 전체). '/공지검색 나이트로드' → '나이트로드'. */
    private static String parseArg(String msg) {
        String t = (msg == null) ? "" : msg.trim();
        if (t.startsWith("@MekiCalc")) t = t.substring("@MekiCalc".length()).trim();
        else if (t.startsWith("@메키캘크")) t = t.substring("@메키캘크".length()).trim();
        if (t.startsWith("/")) t = t.substring(1).trim();
        String[] p = t.split("\\s+", 2);
        return p.length > 1 ? p[1].trim() : "";
    }

    private static String route(String cmd) {
        if (cmd.equals("쿠폰") || cmd.startsWith("coupon")) return "coupon";
        if (cmd.equals("공지검색") || cmd.equals("검색") || cmd.startsWith("search")) return "search";
        if (cmd.equals("공지") || cmd.startsWith("notice")) return "notice";
        if (cmd.equals("이벤트") || cmd.startsWith("event")) return "event";
        if (cmd.equals("링크") || cmd.startsWith("link") || cmd.equals("사이트") || cmd.equals("site")) return "link";
        if (cmd.isEmpty() || cmd.equals("명령어") || cmd.equals("도움") || cmd.equals("도움말")
                || cmd.equals("help") || cmd.equals("?")) return "help";
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private static String coupons(NoticeService notices) {
        Map<String, Object> res = notices.coupons(LocalDate.now().toString());
        List<Map<String, Object>> list = (List<Map<String, Object>>) res.get("coupons");
        if (list == null || list.isEmpty()) return "지금 사용 가능한 쿠폰이 없어요.";
        StringBuilder sb = new StringBuilder("🎟 현재 사용 가능한 쿠폰");
        int n = 0;
        for (Map<String, Object> c : list) {
            String code = str(c, "code");
            if (code == null || code.isBlank()) continue;
            String end = str(c, "end");
            if (end == null || end.isBlank()) continue;   // 무기한(종료일 없음) 제외
            if (n >= MAX_LIST) break; n++;
            sb.append("\n· ").append(code).append(" (~").append(end).append(")");
        }
        if (n == 0) return "지금 (종료일 있는) 사용 가능한 쿠폰이 없어요.";
        return sb.toString();
    }

    /** 최근 공지 목록(category=null이면 전체). 제목 + 원문 링크. */
    @SuppressWarnings("unchecked")
    private static String latest(NoticeService notices, String category, int limit, String header) {
        Map<String, Object> res = notices.list(category, 0);
        List<Object> items = (List<Object>) res.get("items");
        if (items == null || items.isEmpty()) return header + "\n(항목이 없어요.)";
        StringBuilder sb = new StringBuilder(header);
        int n = 0;
        for (Object o : items) {
            if (n >= limit) break;
            if (!(o instanceof Map)) continue;
            n++;
            Map<String, Object> it = (Map<String, Object>) o;
            sb.append("\n· ");
            if (category == null) { String c = str(it, "category"); if (c != null) sb.append("[").append(c).append("] "); }
            sb.append(str(it, "title"));
            String url = str(it, "sourceUrl");
            if (url != null && !url.isBlank()) sb.append("\n  ▶ ").append(url);
        }
        return sb.toString();
    }

    /** /공지검색 <키워드>: 걸린 공지 전부 + 각 공지의 키워드 포함 문장 + 전체 결과 링크(?q=). */
    @SuppressWarnings("unchecked")
    private static String search(NoticeService notices, String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return "검색어를 입력해 주세요.\n예) /공지검색 나이트로드";
        List<Map<String, Object>> hits = notices.searchSnippets(kw);
        String link = SITE + "/notices?q=" + urlq(kw);
        if (hits.isEmpty()) return "🔍 '" + kw + "' 검색 결과가 없어요.\n\n▶ 공지 보기\n" + link;
        StringBuilder sb = new StringBuilder("🔍 '" + kw + "' 검색 결과 " + hits.size() + "건");
        for (Map<String, Object> h : hits) {
            sb.append("\n\n📄 ").append(str(h, "title"));
            Object sn = h.get("snippets");
            if (sn instanceof List) for (Object s : (List<Object>) sn) sb.append("\n· ").append(s);
        }
        sb.append("\n\n▶ 전체 결과 보기\n").append(link);
        return sb.toString();
    }

    /** URL 쿼리값: 한글은 그대로 두되(카톡에서 잘 열림) URL을 깨뜨리는 문자만 인코딩(링크 잘림 방지). */
    private static String urlq(String s) {
        return s.replace("%", "%25").replace(" ", "%20").replace("&", "%26")
                .replace("#", "%23").replace("?", "%3F").replace("+", "%2B");
    }

    private static String link() {
        return "MekiCalc — 메이플 키우기 계산기\n" + SITE;
    }

    private static String help() {
        return "📋 MekiCalc 봇 명령어\n"
                + "· /공지 — 최근 공지\n"
                + "· /공지검색 <키워드> — 공지 검색\n"
                + "· /이벤트 — 진행 중인 이벤트\n"
                + "· /쿠폰 — 사용 가능한 쿠폰\n"
                + "· /링크 — 웹사이트";
    }

    /** 진행 중인 이벤트: 종료일 있고 안 지난 것 '전부'(무기한=endAt 없음 은 제외), 종료 임박순, 기간(날짜+종료시각) 표시. */
    @SuppressWarnings("unchecked")
    private static String events(NoticeService notices) {
        Map<String, Object> res = notices.list("이벤트", 0);
        List<Object> items = (List<Object>) res.get("items");
        if (items == null || items.isEmpty()) return "🎉 진행 중인 이벤트\n(항목이 없어요.)";
        long now = java.time.Instant.now().getEpochSecond();
        java.util.List<Map<String, Object>> live = new java.util.ArrayList<>();
        for (Object o : items) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> it = (Map<String, Object>) o;
            long end = (long) num(it, "endAt");
            if (end > now) live.add(it);   // 종료일 있고 안 지난 것만 = 진행 중(무기한 endAt==0 제외)
        }
        live.sort((a, b) -> Long.compare((long) num(a, "endAt"), (long) num(b, "endAt")));   // 종료 임박순
        if (live.isEmpty()) return "🎉 진행 중인 이벤트\n(지금 진행 중인 이벤트가 없어요.)";
        StringBuilder sb = new StringBuilder("🎉 진행 중인 이벤트");
        int n = 0;
        for (Map<String, Object> it : live) {
            if (n >= MAX_LIST) break; n++;   // 안전 상한(사실상 전부)
            sb.append(n == 1 ? "\n· " : "\n\n· ").append(str(it, "title"));   // 항목 사이 빈 줄(가독성)
            String period = period((long) num(it, "startAt"), (long) num(it, "endAt"));
            if (period != null) sb.append("\n  ").append(period);
        }
        return sb.toString();
    }

    /** 기간 문자열: 시작~종료(KST). 종료만 있으면 "~종료", 둘 다 없으면 null. */
    private static String period(long startAt, long endAt) {
        if (startAt == 0 && endAt == 0) return null;
        if (endAt == 0) return "🗓 " + fmt(startAt) + " ~";
        if (startAt == 0) return "🗓 ~" + fmt(endAt);
        return "🗓 " + fmt(startAt) + " ~ " + fmt(endAt);
    }

    /** epoch sec → KST "M/d(요일) HH:mm". */
    private static String fmt(long epoch) {
        if (epoch <= 0) return "";
        java.time.ZonedDateTime z = java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.of("Asia/Seoul"));
        String[] dow = {"월","화","수","목","금","토","일"};
        return z.getMonthValue() + "/" + z.getDayOfMonth() + "(" + dow[z.getDayOfWeek().getValue() - 1] + ") "
                + String.format("%02d:%02d", z.getHour(), z.getMinute());
    }

    private static double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return (v instanceof Number) ? ((Number) v).doubleValue() : 0;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
}
