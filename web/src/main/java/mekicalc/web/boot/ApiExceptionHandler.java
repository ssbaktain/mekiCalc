package mekicalc.web.boot;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import mekicalc.web.AuthService;
import mekicalc.web.EventLog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * API 예외 → 응답 형태를 구 {@code WebServer.json()} 래퍼와 동일하게 맞춘다.
 * 잘못된 요청(필수 키 누락 등) 400, 그 외 500. 본문은 {"error": ...}. 500은 스택트레이스를 error.jsonl에 기록.
 */
@RestControllerAdvice(basePackages = "mekicalc.web.boot")
public class ApiExceptionHandler {

    private final EventLog log;
    private final AuthService auth;

    public ApiExceptionHandler(EventLog log, AuthService auth) {
        this.log = log;
        this.auth = auth;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(400).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> serverError(Exception e, HttpServletRequest req) {
        log.error(req.getMethod(), req.getRequestURI(), AccessLogInterceptor.userOf(req, auth), e);
        return ResponseEntity.status(500).body(Map.of("error", "서버 오류: " + e.getMessage()));
    }
}
