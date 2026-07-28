package mekicalc.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NoticeData → 카카오톡 텍스트 렌더러(NOTICE_PIPELINE_SPEC §5, 부록 A). Gemini가 뽑은 구조만 받아
 * 형식·압축·병합을 <b>코드로</b> 만든다. 웹 렌더러와 데이터는 공유하되 렌더만 다르다.
 *
 * <p>블록 단위로 조립 후 빈 줄('\n\n')로 잇는다(코드 앞뒤 빈 줄·섹션 간 빈 줄이 자연히 맞음).
 */
public final class NoticeKakaoRenderer {

    private static final int EVENT_SHOWN = 4;     // 이벤트(묶음 요약) 섹션 comma 나열 개수
    private static final int FIXED_SHOWN = 3;     // 수정 완료 최근 N건
    private static final int CLIP = 40;           // Tier3 최후 방어: item.text 40자 초과 시 절단(쿠폰/우편 제외)

    private static final Map<String, String> SECTION_EMOJI = new LinkedHashMap<>();
    static {
        SECTION_EMOJI.put("new", "🆕");
        SECTION_EMOJI.put("improve", "⚙️");
        SECTION_EMOJI.put("bugfix", "🐛");
        SECTION_EMOJI.put("event", "🎉");
        SECTION_EMOJI.put("promise", "🤝");
        SECTION_EMOJI.put("info", "📋");
    }

    private NoticeKakaoRenderer() {}

    /** 공지 1건 → 카톡 메시지 텍스트. sourceUrl = 넥슨 원문 링크('원문 보기' 목적지). */
    public static String render(NoticeData d, String sourceUrl) {
        String type = d.noticeType == null ? "etc" : d.noticeType;
        boolean couponOnly = "coupon".equals(type);
        List<String> blocks = new ArrayList<>();

        // ── 헤더(+ 날짜) ──
        StringBuilder head = new StringBuilder(header(d, type));
        if ("devnote".equals(type)) {
            if (d.title != null) head.append("\n\"").append(cleanTitle(d.title)).append("\"");
        } else if (d.dateLabel != null) {
            head.append("\n📅 ").append(d.dateLabel);
        }
        blocks.add(head.toString());

        // ── 유형별 본문 ──
        if (!couponOnly) {
            switch (type) {
                case "preview": case "patchnote": blocks.addAll(sectionBlocks(d.sections)); break;
                case "event":   { String b = eventBlock(d.eventMeta); if (b != null) blocks.add(b); break; }
                case "issue":   blocks.addAll(issueBlocks(d.issues)); break;
                case "devnote": blocks.addAll(devnoteBlocks(d)); break;
                default:        blocks.addAll(sectionBlocks(d.sections)); break;
            }
        }

        // ── 쿠폰·우편 보상 병합(§5-5·5-6, 압축 금지) ──
        blocks.addAll(couponBlocks(d.coupons, couponOnly));
        String mail = mailBlock(d.mailRewards);
        if (mail != null) blocks.add(mail);

        // ── 원문 링크(넥슨 원문) ──
        blocks.add(sourceLink(sourceUrl));

        return String.join("\n\n", blocks);
    }

    /** 확인 중인 현상 갱신 델타(§A-8): 새로 추가된 open 항목 + (있으면) 새 우편 보상. 기존 목록은 반복하지 않음. */
    public static String renderIssueDelta(List<NoticeData.OpenIssue> newOpen, NoticeData.MailRewards mail, String dateLabel, String sourceUrl) {
        List<String> blocks = new ArrayList<>();
        StringBuilder head = new StringBuilder("⚠️ 확인 중인 현상 갱신");
        if (dateLabel != null) head.append("\n📅 ").append(dateLabel);
        blocks.add(head.toString());
        if (newOpen != null && !newOpen.isEmpty()) {
            StringBuilder b = new StringBuilder("🔴 새로 확인된 현상");
            for (NoticeData.OpenIssue o : newOpen) if (o.text != null) b.append("\n· ").append(clip(o.text));
            blocks.add(b.toString());
        }
        String mb = mailBlock(mail);
        if (mb != null) blocks.add(mb);
        blocks.add(sourceLink(sourceUrl));
        return String.join("\n\n", blocks);
    }

    /** '원문 보기' 블록 — 넥슨 원문 링크. sourceUrl 없으면 문구만. */
    private static String sourceLink(String sourceUrl) {
        return (sourceUrl == null || sourceUrl.isBlank()) ? "▶ 원문 보기" : "▶ 원문 보기\n" + sourceUrl;
    }

    // ──────────────────── 헤더 ────────────────────

    private static String header(NoticeData d, String type) {
        switch (type) {
            case "preview":   return "📢 업데이트 미리보기";
            case "patchnote": return "🔧 패치노트";
            case "event":     return "🎁 " + cleanTitle(d.title);
            case "issue":     return "⚠️ 확인 중인 현상 안내";
            case "devnote":   return "📝 개발자 노트";
            case "coupon":    return "🎟️ 신규 쿠폰 등록";
            default:          return "📋 " + cleanTitle(d.title);
        }
    }

    // ──────────────────── preview / patchnote 섹션 ────────────────────

    private static List<String> sectionBlocks(List<NoticeData.Section> sections) {
        List<String> out = new ArrayList<>();
        for (NoticeData.Section s : sections) {
            if (s.items.isEmpty()) continue;
            String emoji = SECTION_EMOJI.getOrDefault(s.key, "📋");
            if ("event".equals(s.key)) {
                // 🎉 {heading} N종 + 앞 4개 comma + "외 N건"(묶음 요약이라 유지)
                StringBuilder b = new StringBuilder(emoji + " " + s.heading + " " + s.items.size() + "종\n");
                List<String> texts = new ArrayList<>();
                for (int i = 0; i < Math.min(EVENT_SHOWN, s.items.size()); i++) texts.add(clip(s.items.get(i).text));
                b.append(String.join(", ", texts));
                if (s.items.size() > EVENT_SHOWN) b.append(" 외 ").append(s.items.size() - EVENT_SHOWN).append("건");
                out.add(b.toString());
            } else {
                // 콘텐츠 섹션(신규/개선/버그수정 등)은 핵심 → 압축 없이 전부 표시(사용자 요청)
                StringBuilder b = new StringBuilder(emoji + " " + s.heading);
                for (NoticeData.Item it : s.items) b.append("\n· ").append(clip(it.text));
                out.add(b.toString());
            }
        }
        return out;
    }

    // ──────────────────── event ────────────────────

    private static String eventBlock(NoticeData.EventMeta e) {
        if (e == null) return null;
        List<String> lines = new ArrayList<>();
        if (e.benefit != null) lines.add("💰 혜택: " + e.benefit);
        if (e.howTo != null)   lines.add("📍 방법: " + e.howTo);
        if (e.caution != null) lines.add("※ " + e.caution);
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    // ──────────────────── issue ────────────────────

    private static List<String> issueBlocks(NoticeData.Issues iss) {
        List<String> out = new ArrayList<>();
        if (iss == null) return out;
        if (!iss.open.isEmpty()) {
            StringBuilder b = new StringBuilder("🔴 확인 중");
            for (NoticeData.OpenIssue o : iss.open) if (o.text != null) b.append("\n· ").append(clip(o.text));
            out.add(b.toString());
        }
        if (!iss.fixed.isEmpty()) {
            StringBuilder b = new StringBuilder("✅ 수정 완료");
            int n = iss.fixed.size(), shown = Math.min(FIXED_SHOWN, n);
            for (int i = 0; i < shown; i++) {
                NoticeData.FixedIssue f = iss.fixed.get(i);
                b.append("\n· ").append(clip(f.text));
                if (f.fixedDate != null) b.append(" (").append(f.fixedDate).append(")");
            }
            if (n > shown) b.append("\n· 그 외 수정 완료 ").append(n - shown).append("건");
            out.add(b.toString());
        }
        return out;
    }

    // ──────────────────── devnote ────────────────────

    private static List<String> devnoteBlocks(NoticeData d) {
        List<String> out = new ArrayList<>();
        if (d.oneLiner != null) out.add(d.oneLiner);   // 배경 1~2줄
        for (NoticeData.Section s : d.sections) {
            if (!"promise".equals(s.key) || s.items.isEmpty()) continue;
            StringBuilder b = new StringBuilder("🤝 " + s.heading);
            for (NoticeData.Item it : s.items) b.append("\n· ").append(clip(it.text));
            out.add(b.toString());
        }
        return out;
    }

    // ──────────────────── 쿠폰(§5-4·5-5, 압축 금지) ────────────────────

    private static List<String> couponBlocks(List<NoticeData.Coupon> coupons, boolean couponOnly) {
        List<String> out = new ArrayList<>();
        if (coupons == null || coupons.isEmpty()) return out;
        if (!couponOnly) out.add("🎟️ 쿠폰");   // 병합 시 서브헤더(단독은 이미 헤더가 '신규 쿠폰 등록')
        for (NoticeData.Coupon c : coupons) {
            if (c.code == null) continue;
            out.add(c.code);   // 코드 = 독립 블록 → 앞뒤 빈 줄(길게 눌러 복사)
            StringBuilder b = new StringBuilder("💰 보상");
            for (String r : c.rewards) b.append("\n· ").append(r);
            out.add(b.toString());
        }
        return out;
    }

    // ──────────────────── 우편 보상(§5-6, 압축 금지) ────────────────────

    private static String mailBlock(NoticeData.MailRewards m) {
        if (m == null) return null;
        List<String> lines = new ArrayList<>();
        lines.add("📬 우편 보상");
        if (m.target != null) lines.add("대상: " + m.target);
        for (String it : m.items) lines.add("· " + it);
        if (m.claimDeadline != null) lines.add("수령 기한: " + m.claimDeadline);
        return String.join("\n", lines);
    }

    /** Tier3 최후 방어: item.text 40자 초과 시 절단(+…). 쿠폰/우편/event_meta엔 미적용. 정상 경로는 Tier1·2에서 걸러짐. */
    private static String clip(String t) {
        if (t == null) return "";
        return t.length() <= CLIP ? t : t.substring(0, CLIP - 1) + "…";
    }

    /** 제목 끝의 기간 괄호 '(~7/22)' 등을 떼어 헤더/이벤트명을 깔끔하게. */
    private static String cleanTitle(String t) {
        if (t == null) return "";
        return t.replaceAll("\\s*\\(~?[^)]*\\)\\s*$", "").trim();
    }
}
