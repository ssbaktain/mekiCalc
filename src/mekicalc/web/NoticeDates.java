package mekicalc.web;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 구조화 데이터의 사람용 날짜 문자열('M/D(요일)', 'M/D ~ M/D')을 epoch/ISO로 브리지한다.
 * 리마인더(종료 임박)·쿠폰 위젯이 쓰는 구 필드(endAt, coupon.end=yyyy-MM-dd)를 유지하기 위함.
 * 연도 미표기라 '가장 가까운' 연도로 추정(6개월 이상 과거면 +1년).
 */
final class NoticeDates {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Pattern MD = Pattern.compile("(\\d{1,2})\\s*/\\s*(\\d{1,2})");

    private NoticeDates() {}

    /** 문자열에서 첫 'M/D'를 LocalDate(가장 가까운 연도)로. 없으면 null. */
    static LocalDate parseMd(String s) {
        if (s == null) return null;
        Matcher m = MD.matcher(s);
        if (!m.find()) return null;
        return nearest(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    /** 문자열의 '마지막' M/D(기간 종료용). 없으면 null. */
    static LocalDate parseLastMd(String s) {
        if (s == null) return null;
        Matcher m = MD.matcher(s);
        LocalDate last = null;
        while (m.find()) last = nearest(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        return last;
    }

    /** 'M/D(요일)' 등 → "yyyy-MM-dd"(가장 가까운 연도). 실패 null. */
    static String toYmd(String s) {
        LocalDate d = parseMd(s);
        return d == null ? null : d.toString();
    }

    /** 'M/D ~ M/D' 기간 → [startEpoch, endEpoch(그 날 23:59:59 KST)]. 파싱 실패분은 0. */
    static long[] periodEpochs(String period) { return periodEpochsFrom(period, 0); }

    // ──────────────────── 게시일(anchor) 기반 — 쿠폰/이벤트 연도 정확화 ────────────────────
    // 만료·종료일은 항상 '게시 이후'이므로, now가 아니라 게시일 기준으로 첫 도래 연도를 잡는다.
    // (예: 2025-11 게시 + '12/17' → 2025-12-17. now 기준이면 2026-12-17로 오변환됨)

    /** 'M/D(요일)' 등 → "yyyy-MM-dd"(게시일 이후 첫 도래 연도). anchorEpoch<=0이면 now 기준. */
    static String toYmdFrom(String s, long anchorEpoch) {
        LocalDate d = parseMdFrom(s, anchorEpoch);
        return d == null ? null : d.toString();
    }

    /** 게시일 기준 기간 → [startEpoch, endEpoch(23:59:59 KST)]. end는 start 이후로 보정(연말 경계 대응). */
    static long[] periodEpochsFrom(String period, long anchorEpoch) {
        if (period == null) return new long[]{0, 0};
        String[] parts = period.split("~", 2);
        LocalDate start = parseMdFrom(parts[0], anchorEpoch);
        long endAnchor = start != null ? start.atStartOfDay(KST).toEpochSecond() : anchorEpoch;  // end는 start 이후
        LocalDate end = parts.length > 1 ? parseLastMdFrom(parts[1], endAnchor) : parseLastMdFrom(period, endAnchor);
        long s = start == null ? 0 : start.atStartOfDay(KST).toEpochSecond();
        long e = end == null ? 0 : end.atTime(23, 59, 59).atZone(KST).toEpochSecond();
        return new long[]{s, e};
    }

    // 'YYYY년 M/D' 처럼 연도가 명시된 경우 그 연도를 그대로 쓴다(추정보다 확실). 없으면 anchor로 추정.
    private static final Pattern YMD = Pattern.compile("(?:(20\\d{2})\\s*[년./\\-]?\\s*)?(\\d{1,2})\\s*/\\s*(\\d{1,2})");

    private static LocalDate parseMdFrom(String s, long anchorEpoch) {
        if (s == null) return null;
        Matcher m = YMD.matcher(s);
        if (!m.find()) return null;
        return resolve(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), anchorEpoch);
    }

    private static LocalDate parseLastMdFrom(String s, long anchorEpoch) {
        if (s == null) return null;
        Matcher m = YMD.matcher(s);
        LocalDate last = null;
        while (m.find()) last = resolve(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), anchorEpoch);
        return last;
    }

    /** 연도 명시면 그 연도, 아니면 게시일 앵커 추정. */
    private static LocalDate resolve(String year, int month, int day, long anchorEpoch) {
        try {
            if (year != null) return LocalDate.of(Integer.parseInt(year), month, day);
        } catch (Exception ignore) { /* 잘못된 날짜면 앵커로 폴백 */ }
        return fromAnchor(month, day, anchorEpoch);
    }

    // 이벤트/쿠폰 날짜는 대개 게시일 언저리(며칠 전~수개월 후)에 있다. '게시일 - 이 일수'보다 이르면 다음 해로 본다.
    // 너무 작으면 게시 며칠 전 시작한 이벤트가 +1년 오변환되고(토스페이 사례), 너무 크면 장기 프로모션이
    // 전년으로 당겨진다(Play Pass 사례). 한 달(31일)이 두 오류를 가르는 실용적 경계.
    private static final int ANCHOR_BACK_TOLERANCE_DAYS = 31;

    /** month/day를 anchor(게시일) 기준 가장 알맞은 연도로. 게시일 -31일보다 이르면 다음 해. anchor 없으면 now 근사. */
    private static LocalDate fromAnchor(int month, int day, long anchorEpoch) {
        try {
            if (anchorEpoch <= 0) return nearest(month, day);
            LocalDate anchor = java.time.Instant.ofEpochSecond(anchorEpoch).atZone(KST).toLocalDate();
            LocalDate floor = anchor.minusDays(ANCHOR_BACK_TOLERANCE_DAYS);
            LocalDate cand = LocalDate.of(anchor.getYear(), month, day);
            while (cand.isBefore(floor)) cand = cand.plusYears(1);   // [floor, floor+1년) 구간의 도래로 정규화
            return cand;
        } catch (Exception e) { return null; }
    }

    /** 오늘 기준 가장 가까운 연도의 month/day. 6개월 이상 과거면 다음 해로. */
    private static LocalDate nearest(int month, int day) {
        try {
            LocalDate today = LocalDate.now(KST);
            LocalDate cand = LocalDate.of(today.getYear(), month, day);
            if (cand.isBefore(today.minusMonths(6))) cand = cand.plusYears(1);
            else if (cand.isAfter(today.plusMonths(6))) cand = cand.minusYears(1);
            return cand;
        } catch (Exception e) { return null; }
    }
}
