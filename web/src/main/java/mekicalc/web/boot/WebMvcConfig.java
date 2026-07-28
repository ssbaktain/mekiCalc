package mekicalc.web.boot;

import java.util.List;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 설정. {@link JsonHttpMessageConverter}를 최우선으로 등록해 Map/List 응답·요청이
 * Jackson이 아니라 기존 {@code Json}으로 처리되게 한다(바이트 동일 보장).
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new JsonHttpMessageConverter());
    }
}
