package mekicalc.web.boot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import mekicalc.json.Json;
import mekicalc.web.AuthService;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 관리자 전용 API 게이팅. {@code /api/admin/**} 요청은 관리자 세션(화이트리스트 이메일)만 통과시키고,
 * 비로그인·비관리자는 403으로 차단해 핸들러를 실행하지 않는다. (정적 /admin 페이지는 StaticController가 404로 은닉.)
 */
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final AuthService auth;
    private final boolean devBypass;   // 로컬 dev 프로파일: 로그인 없이 통과(프로덕션 false)

    public AdminAuthInterceptor(AuthService auth, boolean devBypass) {
        this.auth = auth;
        this.devBypass = devBypass;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws IOException {
        if (devBypass || auth.isAdmin(auth.session(Req.cookie(req, "sid")))) return true;
        byte[] body = Json.write(Map.of("error", "관리자 권한이 필요합니다.")).getBytes(StandardCharsets.UTF_8);
        res.setStatus(403);
        res.setHeader("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = res.getOutputStream()) { os.write(body); }
        return false;
    }
}
