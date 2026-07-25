package mekicalc.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 임시 유틸: 공지 원문을 재크롤해 (1) 검색 전용 본문 저장, (2) 구조화 추출(extractStructured)로 data 저장.
 * 서버 정지 상태에서 실행. 사용법:
 *   MigrateNotices [N] [body]
 *   N     : 최신 N건만(기본 10). 0 또는 all = 전체.
 *   body  : 두 번째 인자가 "body"면 본문만 저장(Gemini 추출 생략) — 검색 즉시 활성화용, 무료쿼터 소모 없음.
 * 본문 저장은 추출 성공 여부와 무관하게 항상 수행(추출 쿼터 초과해도 검색은 됨).
 */
public final class MigrateNotices {
    public static void main(String[] a) throws Exception {
        boolean bodyOnly = a.length > 1 && "body".equalsIgnoreCase(a[1]);
        Path d = Path.of("data");
        NoticeStore st = new NoticeStore(d);
        NoticeSummarizer s = new NoticeSummarizer(GeminiConfig.load(d), OcrConfig.load(d));
        boolean extract = !bodyOnly && s.enabled();
        if (!bodyOnly && !s.enabled()) { System.out.println("no provider (본문만 저장하려면 두 번째 인자 body)"); return; }
        List<NoticeStore.Notice> all = st.all();
        all.sort((x, y) -> Long.compare(y.postedAt, x.postedAt));
        int n = 10;
        if (a.length > 0) { if ("all".equalsIgnoreCase(a[0])) n = all.size(); else n = Integer.parseInt(a[0]); }
        if (n <= 0) n = all.size();
        HttpClient h = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        int body = 0, data = 0, fail = 0, lim = Math.min(n, all.size());
        for (int i = 0; i < lim; i++) {
            NoticeStore.Notice x = all.get(i);
            try {
                HttpRequest r = HttpRequest.newBuilder(
                        URI.create("https://forum.nexon.com/api/v1/thread/" + x.threadId + "?alias=maplestoryidle-kr"))
                        .header("User-Agent", "Mozilla/5.0 (compatible; MekiCalcBot/1.0)")
                        .timeout(Duration.ofSeconds(20)).GET().build();
                Map<String, Object> dt = Json.asMap(Json.parse(h.send(r, HttpResponse.BodyHandlers.ofString()).body()));
                String txt = NoticeCrawler.htmlToText(dt.get("content") == null ? "" : dt.get("content").toString());
                if (txt.isBlank()) txt = x.title;
                st.saveBody(x.threadId, txt);   // 검색 전용 본문 — 항상 저장
                body++;
                if (extract) {
                    NoticeData nd = s.extractStructured(x.category, x.title, txt, x.sourceUrl);
                    st.saveData(x.threadId, nd, System.currentTimeMillis() / 1000);
                    data++;
                    System.out.println("[OK](" + nd.noticeType + ") " + x.title);
                    Thread.sleep(700);
                } else {
                    System.out.println("[BODY] " + x.title);
                    Thread.sleep(200);
                }
            } catch (Exception e) { fail++; System.out.println("[FAIL] " + x.title + " : " + e.getMessage()); }
            if (i % 20 == 19) st.save();   // 중간 저장(장시간 실행 대비)
        }
        st.save();
        System.out.println("본문 " + body + " · 구조화 " + data + " · 실패 " + fail + " / " + lim);
    }
}
