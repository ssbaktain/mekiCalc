package mekicalc.web.boot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * 기존 미니 JSON({@link Json})으로 요청/응답을 처리하는 컨버터. Jackson 대신 이걸 최우선으로 써서
 * 구 서버와 <b>바이트 단위 동일한 응답</b>을 낸다.
 *
 * <ul>
 *   <li>쓰기: {@code Json.write}(2칸 들여쓰기·정수는 소수점 없이·큰수 지수표기·끝 개행) 그대로.</li>
 *   <li>읽기: {@code Json.parse}로 모든 숫자를 Double로(구 {@code readJsonBody}와 동일 — 서비스가
 *       {@code instanceof Double}로 파싱하므로 Jackson의 Integer/Long 혼용을 피해야 함).</li>
 *   <li>Content-Type: {@code application/json; charset=utf-8} 문자열까지 구 서버와 일치.</li>
 * </ul>
 */
public class JsonHttpMessageConverter extends AbstractHttpMessageConverter<Object> {

    private static final MediaType JSON_UTF8 = MediaType.valueOf("application/json; charset=utf-8");

    public JsonHttpMessageConverter() {
        super(StandardCharsets.UTF_8, MediaType.APPLICATION_JSON, MediaType.valueOf("application/json;charset=UTF-8"));
    }

    /** Map/List 형태 응답·요청만 담당(String·ResponseEntity 등 기본 컨버터에 위임). */
    @Override
    protected boolean supports(Class<?> clazz) {
        return Map.class.isAssignableFrom(clazz) || List.class.isAssignableFrom(clazz);
    }

    @Override
    protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage) throws IOException {
        byte[] raw = inputMessage.getBody().readAllBytes();
        if (raw.length == 0) return new LinkedHashMap<String, Object>();
        try {
            return Json.asMap(Json.parse(new String(raw, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new HttpMessageNotReadableException("JSON 파싱 실패: " + e.getMessage(), inputMessage);
        }
    }

    @Override
    protected void writeInternal(Object obj, HttpOutputMessage outputMessage) throws IOException {
        // 구 서버와 동일한 Content-Type 문자열 강제(대소문자·공백 포함).
        outputMessage.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
        byte[] out = Json.write(obj).getBytes(StandardCharsets.UTF_8);
        outputMessage.getBody().write(out);
    }
}
