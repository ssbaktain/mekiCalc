package mekicalc.web.boot;

import java.util.List;
import mekicalc.web.AuthService;
import mekicalc.web.EventLog;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 설정.
 * <ul>
 *   <li>{@link JsonHttpMessageConverter}를 최우선 등록 → Map/List 응답·요청이 Jackson이 아니라
 *       기존 {@code Json}으로 처리(바이트 동일).</li>
 *   <li>{@link AccessLogInterceptor}를 /api/** 에 등록(프리셋 제외) → 구 서버의 접근 로깅 재현.</li>
 * </ul>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final EventLog eventLog;
    private final AuthService auth;
    private final boolean devAdminBypass;

    public WebMvcConfig(EventLog eventLog, AuthService auth,
                        @org.springframework.beans.factory.annotation.Value("${mekicalc.dev-admin-bypass:false}") boolean devAdminBypass) {
        this.eventLog = eventLog;
        this.auth = auth;
        this.devAdminBypass = devAdminBypass;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new JsonHttpMessageConverter());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 구 json() 래퍼 커버리지와 동일: /api/* 는 로깅, /api/presets 는 제외(구 서버도 미기록).
        registry.addInterceptor(new AccessLogInterceptor(eventLog, auth))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/presets");
        // 관리자 전용 API는 관리자 세션만(비관리자 403). dev 프로파일이면 우회.
        registry.addInterceptor(new AdminAuthInterceptor(auth, devAdminBypass))
                .addPathPatterns("/api/admin/**");
    }
}
