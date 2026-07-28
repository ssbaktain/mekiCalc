package mekicalc.web;

import java.util.List;

/** 임시 검증(Phase 3): 발송 매트릭스(issueDelta §3-3) + saveData 브리지(endAt/coupons) 시나리오. LLM 호출 없음. */
public final class NoticePhase3Test {
    public static void main(String[] args) {
        NoticeStore.Notice n = new NoticeStore.Notice();
        n.threadId = "3490607"; n.category = "확인 중인 현상";

        // 시나리오 1: open 새 항목 추가 → 델타 발송
        NoticeData oldD = issue(open("스킬 이펙트 미출력"), fixed("월드 아레나 진입 불가", "7/15"));
        NoticeData newD = issue(open("스킬 이펙트 미출력", "콜로세움 입장 시 강제 종료"), fixed("월드 아레나 진입 불가", "7/15"));
        System.out.println("── 시나리오 1: open 새 항목 추가 (발송 기대) ──");
        System.out.println(NoticeCrawler.issueDelta(oldD, newD, n));

        // 시나리오 2: open → fixed 이동 (발송 안 함 기대)
        NoticeData oldD2 = issue(open("스킬 이펙트 미출력"), fixed());
        NoticeData newD2 = issue(open(), fixed("스킬 이펙트 미출력", "7/16"));
        System.out.println("\n── 시나리오 2: open→fixed 이동 (발송 없음 기대) ──");
        System.out.println("결과: " + NoticeCrawler.issueDelta(oldD2, newD2, n));

        // 시나리오 3: mail_rewards 새로 추가 (발송 기대)
        NoticeData oldD3 = issue(open("스킬 이펙트 미출력"), fixed());
        NoticeData newD3 = issue(open("스킬 이펙트 미출력"), fixed());
        newD3.mailRewards = new NoticeData.MailRewards();
        newD3.mailRewards.target = "7/2 파퀘 참여 유저";
        newD3.mailRewards.claimDeadline = "7/17(금)까지";
        newD3.mailRewards.items = List.of("탐험 티켓 5개", "레드 다이아 300개");
        System.out.println("\n── 시나리오 3: 우편 보상 추가 (발송 기대) ──");
        System.out.println(NoticeCrawler.issueDelta(oldD3, newD3, n));

        // 시나리오 4: 변화 없음 (발송 없음 기대)
        System.out.println("\n── 시나리오 4: 변화 없음 (발송 없음 기대) ──");
        System.out.println("결과: " + NoticeCrawler.issueDelta(oldD, oldD, n));

        // saveData 브리지: event_meta.period → endAt, coupons.expires → end
        System.out.println("\n── saveData 브리지(날짜 파싱) ──");
        long[] se = NoticeDates.periodEpochs("7/9(목) ~ 7/22(수)");
        System.out.println("period '7/9 ~ 7/22' → start=" + iso(se[0]) + " end=" + iso(se[1]));
        System.out.println("expires '7/31(금)' → " + NoticeDates.toYmd("~7/31(금)까지"));
    }

    static NoticeData issue(List<NoticeData.OpenIssue> op, List<NoticeData.FixedIssue> fx) {
        NoticeData d = new NoticeData();
        d.noticeType = "issue"; d.dateLabel = "7/2 업데이트 관련 (7/16 갱신)";
        d.issues = new NoticeData.Issues();
        d.issues.open = op; d.issues.fixed = fx;
        return d;
    }
    static List<NoticeData.OpenIssue> open(String... texts) {
        java.util.ArrayList<NoticeData.OpenIssue> l = new java.util.ArrayList<>();
        for (String t : texts) { NoticeData.OpenIssue o = new NoticeData.OpenIssue(); o.text = t; l.add(o); }
        return l;
    }
    static List<NoticeData.FixedIssue> fixed(String... pairs) {
        java.util.ArrayList<NoticeData.FixedIssue> l = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) { NoticeData.FixedIssue f = new NoticeData.FixedIssue(); f.text = pairs[i]; f.fixedDate = pairs[i + 1]; l.add(f); }
        return l;
    }
    static String iso(long ep) {
        return ep == 0 ? "0" : java.time.Instant.ofEpochSecond(ep).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDateTime().toString();
    }
}
