package mekicalc.web.boot;

import mekicalc.web.NoticeCrawler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 공지 유지보수 API. {@code /api/admin/**}는 {@link AdminAuthInterceptor}가 관리자 세션으로
 * 게이팅하므로 여기 핸들러는 권한 검사를 다시 하지 않는다.
 *
 * <p>{@link AdminController}(방·디스코드 등)와 분리해 공지 교정 기능만 담는다.
 */
@RestController
@RequestMapping("/api/admin/notices")
public class NoticeAdminController {

    private final NoticeCrawler crawler;

    public NoticeAdminController(NoticeCrawler crawler) {
        this.crawler = crawler;
    }

    /**
     * 특정 공지를 넥슨에서 다시 가져와 재요약 후 저장(웹 표시 갱신). <b>봇 발송 없음</b> — 이미 발송된 공지의
     * 웹 표시만 정정하는 용도(예: notice_type 오분류 교정). body 불필요, 쿼리 {@code thread}로 threadId 지정.
     */
    @PostMapping("/resummarize")
    public Object resummarize(@RequestParam String thread) {
        return crawler.resummarize(thread);
    }
}
