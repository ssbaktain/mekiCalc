package mekicalc.web.boot;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import mekicalc.web.DiscordNotifier;
import org.springframework.stereotype.Component;

/**
 * 디스코드 발송기 데몬의 수명주기를 스프링에 이관({@link CrawlerLifecycle}과 동일 패턴).
 * 서버 시작 시 폴링 시작, 종료 시 우아하게 정리.
 */
@Component
public class DiscordLifecycle {

    private final DiscordNotifier notifier;

    public DiscordLifecycle(DiscordNotifier notifier) {
        this.notifier = notifier;
    }

    @PostConstruct
    public void start() {
        notifier.start();
    }

    @PreDestroy
    public void stop() {
        notifier.stop();
    }
}
