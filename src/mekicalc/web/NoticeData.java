package mekicalc.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 공지 1건에서 Gemini가 추출한 <b>구조화 데이터</b>(NOTICE_PIPELINE_SPEC §4). 텍스트 요약이 아니라 구조만 담는다.
 *
 * <p>원칙: Gemini는 '형식'이 아니라 '데이터(JSON)'만 뽑는다. 압축('외 N건')·형식·발송 판단은 서버 렌더러가 담당.
 * 저장/API JSON은 스펙의 snake_case 키 그대로 쓴다(웹 JS가 스펙대로 소비, 카톡 렌더러는 이 타입을 읽음).
 * 필드가 없으면 null/빈 리스트. event_meta는 event, issues는 issue일 때만, coupons·mail_rewards는 있으면 항상.
 */
public final class NoticeData {

    public String noticeType;   // preview | patchnote | event | issue | devnote | coupon | etc
    public String title;        // 간결한 제목(원제목 기반)
    public String oneLiner;     // 40자 이내 한 문장(웹 목록 미리보기)
    public String dateLabel;    // "7/23(목) 예정" 등. 없으면 null
    public List<Section> sections = new ArrayList<>();
    public EventMeta eventMeta; // event 전용, 아니면 null
    public Issues issues;       // issue 전용, 아니면 null
    public List<Coupon> coupons = new ArrayList<>();
    public MailRewards mailRewards;  // 있으면, 없으면 null

    public static final class Section {
        public String key;      // new | improve | bugfix | event | promise | info
        public String heading;  // 섹션 표시명
        public List<Item> items = new ArrayList<>();
    }

    public static final class Item {
        public String text;                     // 항목 한 줄 요약
        public List<String> sub = new ArrayList<>();   // 보조 정보(웹에서만 노출)
    }

    public static final class EventMeta {
        public String period, benefit, howTo, caution;   // caution 없으면 null
    }

    public static final class Issues {
        public List<OpenIssue> open = new ArrayList<>();
        public List<FixedIssue> fixed = new ArrayList<>();
    }

    public static final class OpenIssue {
        public String text, workaround;   // workaround 없으면 null
    }

    public static final class FixedIssue {
        public String text, fixedDate;
    }

    public static final class Coupon {
        public String code, expires;      // expires 없으면 null
        public List<String> rewards = new ArrayList<>();   // '아이템명 수량' 전 항목(압축 금지)
    }

    public static final class MailRewards {
        public String reason, target, sentAt, claimDeadline;   // 없으면 null
        public List<String> items = new ArrayList<>();          // 전 항목(압축 금지)
    }

    // ──────────────────── JSON 직렬화(스펙 snake_case) ────────────────────

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("notice_type", noticeType);
        m.put("title", title);
        m.put("one_liner", oneLiner);
        m.put("date_label", dateLabel);
        List<Object> secs = new ArrayList<>();
        for (Section s : sections) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("key", s.key);
            sm.put("heading", s.heading);
            List<Object> its = new ArrayList<>();
            for (Item it : s.items) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("text", it.text);
                im.put("sub", new ArrayList<Object>(it.sub));
                its.add(im);
            }
            sm.put("items", its);
            secs.add(sm);
        }
        m.put("sections", secs);
        if (eventMeta != null) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("period", eventMeta.period);
            em.put("benefit", eventMeta.benefit);
            em.put("how_to", eventMeta.howTo);
            em.put("caution", eventMeta.caution);
            m.put("event_meta", em);
        } else m.put("event_meta", null);
        if (issues != null) {
            Map<String, Object> is = new LinkedHashMap<>();
            List<Object> op = new ArrayList<>();
            for (OpenIssue o : issues.open) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("text", o.text);
                om.put("workaround", o.workaround);
                op.add(om);
            }
            List<Object> fx = new ArrayList<>();
            for (FixedIssue f : issues.fixed) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("text", f.text);
                fm.put("fixed_date", f.fixedDate);
                fx.add(fm);
            }
            is.put("open", op);
            is.put("fixed", fx);
            m.put("issues", is);
        } else m.put("issues", null);
        List<Object> cps = new ArrayList<>();
        for (Coupon c : coupons) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("code", c.code);
            cm.put("expires", c.expires);
            cm.put("rewards", new ArrayList<Object>(c.rewards));
            cps.add(cm);
        }
        m.put("coupons", cps);
        if (mailRewards != null) {
            Map<String, Object> mr = new LinkedHashMap<>();
            mr.put("reason", mailRewards.reason);
            mr.put("target", mailRewards.target);
            mr.put("items", new ArrayList<Object>(mailRewards.items));
            mr.put("sent_at", mailRewards.sentAt);
            mr.put("claim_deadline", mailRewards.claimDeadline);
            m.put("mail_rewards", mr);
        } else m.put("mail_rewards", null);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static NoticeData fromMap(Map<String, Object> m) {
        if (m == null) return null;
        NoticeData d = new NoticeData();
        d.noticeType = str(m.get("notice_type"));
        d.title = str(m.get("title"));
        d.oneLiner = str(m.get("one_liner"));
        d.dateLabel = str(m.get("date_label"));
        for (Object so : asList(m.get("sections"))) {
            Map<String, Object> sm = asMap(so);
            if (sm == null) continue;
            Section s = new Section();
            s.key = str(sm.get("key"));
            s.heading = str(sm.get("heading"));
            for (Object io : asList(sm.get("items"))) {
                Map<String, Object> im = asMap(io);
                if (im == null) continue;
                Item it = new Item();
                it.text = str(im.get("text"));
                it.sub = strList(im.get("sub"));
                s.items.add(it);
            }
            d.sections.add(s);
        }
        Map<String, Object> em = asMap(m.get("event_meta"));
        if (em != null && !em.isEmpty()) {
            d.eventMeta = new EventMeta();
            d.eventMeta.period = str(em.get("period"));
            d.eventMeta.benefit = str(em.get("benefit"));
            d.eventMeta.howTo = str(em.get("how_to"));
            d.eventMeta.caution = str(em.get("caution"));
        }
        Map<String, Object> is = asMap(m.get("issues"));
        if (is != null && !is.isEmpty()) {
            d.issues = new Issues();
            for (Object oo : asList(is.get("open"))) {
                Map<String, Object> om = asMap(oo);
                if (om == null) continue;
                OpenIssue o = new OpenIssue();
                o.text = str(om.get("text"));
                o.workaround = str(om.get("workaround"));
                d.issues.open.add(o);
            }
            for (Object fo : asList(is.get("fixed"))) {
                Map<String, Object> fm = asMap(fo);
                if (fm == null) continue;
                FixedIssue f = new FixedIssue();
                f.text = str(fm.get("text"));
                f.fixedDate = str(fm.get("fixed_date"));
                d.issues.fixed.add(f);
            }
        }
        for (Object co : asList(m.get("coupons"))) {
            Map<String, Object> cm = asMap(co);
            if (cm == null) continue;
            String code = str(cm.get("code"));
            if (code == null || code.isBlank()) continue;
            Coupon c = new Coupon();
            c.code = code.trim();
            c.expires = str(cm.get("expires"));
            c.rewards = strList(cm.get("rewards"));
            d.coupons.add(c);
        }
        Map<String, Object> mr = asMap(m.get("mail_rewards"));
        if (mr != null && !mr.isEmpty()) {
            d.mailRewards = new MailRewards();
            d.mailRewards.reason = str(mr.get("reason"));
            d.mailRewards.target = str(mr.get("target"));
            d.mailRewards.items = strList(mr.get("items"));
            d.mailRewards.sentAt = str(mr.get("sent_at"));
            d.mailRewards.claimDeadline = str(mr.get("claim_deadline"));
        }
        return d;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return (s.isEmpty() || s.equalsIgnoreCase("null")) ? null : s;
    }

    // null·비-Map/List에도 안전(Json.asMap/asList는 던짐). optional 필드(event_meta 등) 대응.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) { return (o instanceof Map) ? (Map<String, Object>) o : null; }
    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) { return (o instanceof List) ? (List<Object>) o : java.util.Collections.emptyList(); }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        for (Object x : asList(o)) {
            String s = str(x);
            if (s != null) out.add(s);
        }
        return out;
    }
}
