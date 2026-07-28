package mekicalc.web.boot;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import mekicalc.web.NoticeCrawler;
import org.springframework.stereotype.Component;

/**
 * 공지 크롤러 데몬의 수명주기를 스프링에 이관. 구 {@code WebMain}이 서버 시작 후 {@code crawler.start()}를
 * 호출하던 것을 @PostConstruct로, 종료 시 정리를 @PreDestroy로 대체(우아한 종료).
 */
@Component
public class CrawlerLifecycle {

    private final NoticeCrawler crawler;

    public CrawlerLifecycle(NoticeCrawler crawler) {
        this.crawler = crawler;
    }

    @PostConstruct
    public void start() {
        crawler.start();
    }

    @PreDestroy
    public void stop() {
        crawler.stop();
    }
}
