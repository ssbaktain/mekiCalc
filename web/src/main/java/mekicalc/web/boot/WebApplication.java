package mekicalc.web.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 진입점(신 웹 서버). 구 {@link mekicalc.web.WebMain}(JDK HttpServer)을 대체해 간다.
 *
 * <p>MVC 구조: Controller(web.boot) → Service(web.CalcService 등, 기존 재사용) → Model(core).
 * 응답 직렬화는 기존 {@code Json.write}를 재사용하는 {@link JsonHttpMessageConverter}로
 * 구 서버와 <b>바이트 단위 동일</b>하게 맞춘다(프론트 무변경 보장).
 *
 * <p>실행: java -jar web.jar [--server.port=8080] [--mekicalc.data-dir=data] [--mekicalc.web-dir=public]
 */
@SpringBootApplication
public class WebApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
