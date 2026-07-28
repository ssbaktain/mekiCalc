package mekicalc.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import mekicalc.json.Json;

/** 임시 검증(Phase 1): 유형별 샘플 공지의 구조화 추출(NoticeData) 결과를 읽기 좋게 덤프. */
public final class NoticeStructTest {
    public static void main(String[] args) throws Exception {
        Path data = Path.of("data");
        NoticeSummarizer sum = new NoticeSummarizer(GeminiConfig.load(data), OcrConfig.load(data));
        System.out.println("provider: " + sum.provider() + "\n");
        // {threadId, boardType}
        String[][] samples = {
                {"3501869", "공지사항"},   // preview
                {"3490212", "패치노트"},   // patchnote
                {"3490082", "이벤트"},     // event
                {"3490607", "확인 중인 현상"}, // issue (+ 우편보상 케이스)
        };
        if (args.length >= 2) samples = new String[][]{{args[0], args[1]}};
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        for (String[] s : samples) {
            String id = s[0], board = s[1];
            try {
                HttpRequest req = HttpRequest.newBuilder(
                        URI.create("https://forum.nexon.com/api/v1/thread/" + id + "?alias=maplestoryidle-kr"))
                        .header("User-Agent", "Mozilla/5.0 (compatible; MekiCalcBot/1.0)")
                        .timeout(Duration.ofSeconds(20)).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                String rb = resp.body();
                System.out.println("  (forum HTTP " + resp.statusCode() + ", " + (rb == null ? 0 : rb.length()) + "B)");
                Map<String, Object> detail = Json.asMap(Json.parse(rb));
                String title = detail.get("title") == null ? "(제목없음)" : detail.get("title").toString();
                String text = NoticeCrawler.htmlToText(detail.get("content") == null ? "" : detail.get("content").toString());
                String url = "https://forum.nexon.com/maplestoryidle-kr/board_view?thread=" + id;
                NoticeData d = sum.extractStructured(board, title, text, url);
                dump(id, board, title, d);
                System.out.println("──────── 카톡 렌더 ────────");
                System.out.println(NoticeKakaoRenderer.render(d, id));
                System.out.println();
            } catch (Exception e) {
                System.out.println("[FAIL] " + id + " (" + board + "): " + e.getMessage() + "\n");
            }
        }
    }

    static void dump(String id, String board, String title, NoticeData d) {
        System.out.println("══════════════════════════════════════════════════════");
        System.out.println("[" + d.noticeType + "] " + title + "  (board=" + board + ", id=" + id + ")");
        System.out.println("  one_liner : " + d.oneLiner);
        System.out.println("  date_label: " + d.dateLabel);
        if (!d.sections.isEmpty()) {
            System.out.println("  sections:");
            for (NoticeData.Section sec : d.sections) {
                System.out.println("    [" + sec.key + "] " + sec.heading);
                for (NoticeData.Item it : sec.items) {
                    System.out.println("       · " + it.text + (it.sub.isEmpty() ? "" : "   (sub: " + String.join(" | ", it.sub) + ")"));
                }
            }
        }
        if (d.eventMeta != null) {
            System.out.println("  event_meta: 기간=" + d.eventMeta.period + " | 혜택=" + d.eventMeta.benefit
                    + " | 방법=" + d.eventMeta.howTo + " | 주의=" + d.eventMeta.caution);
        }
        if (d.issues != null) {
            System.out.println("  issues.open (" + d.issues.open.size() + "):");
            for (NoticeData.OpenIssue o : d.issues.open) System.out.println("       🔴 " + o.text + (o.workaround != null ? "  [해결: " + o.workaround + "]" : ""));
            System.out.println("  issues.fixed (" + d.issues.fixed.size() + "):");
            for (NoticeData.FixedIssue f : d.issues.fixed) System.out.println("       ✅ " + f.text + " (" + f.fixedDate + ")");
        }
        if (!d.coupons.isEmpty()) {
            System.out.println("  coupons:");
            for (NoticeData.Coupon c : d.coupons) {
                System.out.println("       🎟 " + c.code + " (만료 " + c.expires + ")");
                for (String r : c.rewards) System.out.println("          · " + r);
            }
        }
        if (d.mailRewards != null) {
            System.out.println("  mail_rewards: 사유=" + d.mailRewards.reason + " | 대상=" + d.mailRewards.target
                    + " | 수령기한=" + d.mailRewards.claimDeadline);
            for (String it : d.mailRewards.items) System.out.println("       📬 " + it);
        }
        System.out.println();
    }
}
