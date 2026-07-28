package mekicalc.web.boot;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * API 예외 → 응답 형태를 구 {@code WebServer.json()} 래퍼와 동일하게 맞춘다.
 * 잘못된 요청(필수 키 누락 등) 400, 그 외 500. 본문은 {"error": ...}.
 */
@RestControllerAdvice(basePackages = "mekicalc.web.boot")
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(400).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> serverError(Exception e) {
        return ResponseEntity.status(500).body(Map.of("error", "서버 오류: " + e.getMessage()));
    }
}
