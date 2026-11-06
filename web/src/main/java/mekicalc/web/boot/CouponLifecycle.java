package mekicalc.web.boot;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import mekicalc.web.CouponService;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 자동 입력 스케줄러. 주기적으로 {@link CouponService#check()}를 호출해 새(미처리) 쿠폰이
 * 있으면 자동 입력한다. 크롤러를 건드리지 않고 공지 쿠폰 목록만 폴링하는 구조라 결합도가 낮다.
 * 자동 on/off·중복 방지는 CouponService가 판단하므로 여기선 단순 주기 호출만 한다.
 */
@Component
public class CouponLifecycle {

    private static final long INITIAL_DELAY_SEC = 90;    // 서버·사이드카 예열 대기
    private static final long PERIOD_SEC = 180;          // 3분마다 확인

    private final CouponService coupon;
    private ScheduledExecutorService ses;

    public CouponLifecycle(CouponService coupon) {
        this.coupon = coupon;
    }

    @PostConstruct
    public void start() {
        ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "coupon-auto"); t.setDaemon(true); return t;
        });
        ses.scheduleWithFixedDelay(() -> {
            try { coupon.check(); }
            catch (Exception e) { System.err.println("[coupon] 자동 확인 실패: " + e.getMessage()); }
        }, INITIAL_DELAY_SEC, PERIOD_SEC, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (ses != null) ses.shutdownNow();
    }
}
