package mekicalc.web.boot;

import mekicalc.web.BotOutbox;
import mekicalc.web.NoticeCrawler;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 봇 수동 트리거·시뮬레이션 엔드포인트. <b>dev 프로파일에서만 등록</b>(프로덕션 기본 프로파일에선 404).
 *
 * <p>이 엔드포인트들은 봇 발신 큐에 메시지를 넣어 전 방에 브로드캐스트를 유발할 수 있어(구 서버에선
 * 프로덕션에도 노출됐던 위험) 개발 환경으로 격리한다. 로컬 실행 시: {@code --spring.profiles.active=dev}.
 */
@RestController
@Profile("dev")
public class BotDevController {

    private final BotOutbox outbox;
    private final NoticeCrawler crawler;

    public BotDevController(BotOutbox outbox, NoticeCrawler crawler) {
        this.outbox = outbox;
        this.crawler = crawler;
    }

    /** 수동 재확인: 지금 이미 22시 게이트를 지난 리마인더를 즉시 큐잉. */
    @GetMapping("/api/bot/remind-now")
    public Object remindNow() {
        crawler.checkExpiry();
        return outbox.health();
    }

    /** [테스트] 최신 공지를 크롤러 배달 형식 그대로 큐잉. */
    @GetMapping("/api/bot/simulate-notice")
    public Object simulateNotice() {
        return crawler.simulateLatestNotice();
    }

    /** [테스트] '지금이 22시'라 가정, 가장 임박한 실효 종료일의 이벤트·쿠폰을 묶어 리마인더 큐잉. */
    @GetMapping("/api/bot/simulate-remind")
    public Object simulateRemind() {
        return crawler.simulateReminder();
    }
}
