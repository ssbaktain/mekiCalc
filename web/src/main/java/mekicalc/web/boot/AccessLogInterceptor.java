package mekicalc.web.boot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mekicalc.web.AuthService;
import mekicalc.web.EventLog;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API 접근/에러 로깅(구 {@code WebServer.json()} 래퍼의 EventLog 기록 재현).
 * 요청마다 시각·메서드·경로·상태·처리시간(ms)·유저(이메일)·클라이언트 IP를 access.jsonl에 남긴다.
 * 500 에러의 스택트레이스는 {@link ApiExceptionHandler}가 error.jsonl에 기록한다.
 */
public class AccessLogInterceptor implements HandlerInterceptor {

    private static final String START = AccessLogInterceptor.class.getName() + ".start";

    private final EventLog log;
    private final AuthService auth;

    public AccessLogInterceptor(EventLog log, AuthService auth) {
        this.log = log;
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        req.setAttribute(START, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        Object start = req.getAttribute(START);
        long ms = start instanceof Long ? (System.nanoTime() - (Long) start) / 1_000_000 : 0;
        log.access(req.getMethod(), req.getRequestURI(), res.getStatus(), ms, userOf(req, auth), clientIp(req));
    }

    /** 로그인 상태면 유저 이메일, 아니면 null. */
    static String userOf(HttpServletRequest req, AuthService auth) {
        AuthService.Session s = auth.session(Req.cookie(req, "sid"));
        return s != null ? s.email() : null;
    }

    /** 실제 클라이언트 IP. Cloudflare 터널 뒤라 CF-Connecting-IP → X-Forwarded-For → 원격주소 순. */
    static String clientIp(HttpServletRequest req) {
        String cf = req.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
