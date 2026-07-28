package mekicalc.web.boot;

import java.nio.file.Path;
import mekicalc.web.AuthService;
import mekicalc.web.BotOutbox;
import mekicalc.web.CalcService;
import mekicalc.web.Db;
import mekicalc.web.EventLog;
import mekicalc.web.GameContent;
import mekicalc.web.GeminiConfig;
import mekicalc.web.NoticeCrawler;
import mekicalc.web.NoticeService;
import mekicalc.web.NoticeStore;
import mekicalc.web.NoticeSummarizer;
import mekicalc.web.OAuthConfig;
import mekicalc.web.OcrConfig;
import mekicalc.web.PgAuthStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 웹 서버 빈 구성. 서비스 생성 순서(GameContent→CalcService→Auth→공지·봇)를 명시하고
 * 스프링 컨테이너가 수명주기를 관리한다. 서비스 로직은 <b>기존 클래스를 무변경 재사용</b>(MVC의 Service 층).
 */
@Configuration
public class BeanConfig {

    /** 게임 데이터·개인 저장이 놓이는 폴더(런타임 외부). 전 빈이 공유. */
    @Bean
    public Path dataDir(@Value("${mekicalc.data-dir:data}") String dataDir) {
        return Path.of(dataDir);
    }

    /** 게임 데이터(스탯정의·장비·동료·유물). 서버 시작 시 1회 로드, 전 요청 공유. */
    @Bean
    public GameContent gameContent(Path dataDir) throws Exception {
        return GameContent.load(dataDir);
    }

    /** 계산 서비스(무상태). */
    @Bean
    public CalcService calcService(GameContent content) {
        return new CalcService(content);
    }

    @Bean
    public OAuthConfig oAuthConfig(Path dataDir) {
        return OAuthConfig.load(dataDir);
    }

    /** data/db.json 있으면 PostgreSQL, 없으면 OFF(파일 모드 폴백). */
    @Bean
    public Db db(Path dataDir) {
        return Db.load(dataDir);
    }

    /**
     * 인증 서비스. DB 활성 시 최대 3분 대기 후 PgAuthStore, 실패/비활성이면 파일 저장 모드(pg=null).
     * (DB가 늦게 뜨는 헤드리스 부팅 대비 waitReady(180) 폴백)
     */
    @Bean
    public AuthService authService(OAuthConfig oauth, Db db, Path dataDir) {
        PgAuthStore pg = null;
        if (db.enabled) {
            if (db.waitReady(180)) pg = new PgAuthStore(db);
            else System.err.println("DB 연결 실패(타임아웃), 파일 모드로 폴백");
        }
        return new AuthService(oauth, dataDir, pg);
    }

    @Bean
    public EventLog eventLog(Path dataDir) {
        return new EventLog(dataDir);
    }

    @Bean
    public NoticeStore noticeStore(Path dataDir) {
        return new NoticeStore(dataDir);
    }

    @Bean
    public NoticeSummarizer noticeSummarizer(Path dataDir) {
        return new NoticeSummarizer(GeminiConfig.load(dataDir), OcrConfig.load(dataDir));
    }

    @Bean
    public NoticeService noticeService(NoticeStore store) {
        return new NoticeService(store);
    }

    /** 봇 발신 큐(신규공지·리마인더). */
    @Bean
    public BotOutbox botOutbox(Path dataDir) {
        return new BotOutbox(dataDir);
    }

    /** 공지 크롤러(넥슨 포럼 폴링·요약). 시작/종료는 {@link CrawlerLifecycle}가 관리. */
    @Bean
    public NoticeCrawler noticeCrawler(NoticeStore store, NoticeSummarizer summarizer, BotOutbox outbox) {
        return new NoticeCrawler(store, summarizer, outbox);
    }
}
