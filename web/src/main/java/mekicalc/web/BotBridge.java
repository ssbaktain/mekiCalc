package mekicalc.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 카카오봇 브릿지(Iris) 클라이언트. Iris의 {@code POST /query}(카톡 로컬 DB SQL 실행)로 방 목록을 조회한다.
 * Iris는 봇이 도는 미니PC의 로컬(127.0.0.1:3000)에만 있으므로, 로컬 개발(dev)에서는 {@code mock}으로 대체한다.
 *
 * <p>봇 관리 대상은 <b>오픈 그룹채팅(type=OM)</b>. 방 이름은 {@code private_meta}의 {@code {"name":"메키00n"}}
 * (사용자가 카톡에서 붙인 커스텀 이름)에서 뽑는다. ★chat_id는 큰 정수(약 1.8e16)라 Double 파싱 시 정밀도가
 * 깨지므로 SQL에서 {@code CAST(id AS TEXT)}로 문자열로 받아 정확히 보존한다.
 */
public final class BotBridge {

    private final String irisUrl;
    private final boolean mock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public BotBridge(String irisUrl, boolean mock) {
        this.irisUrl = (irisUrl != null && irisUrl.endsWith("/")) ? irisUrl.substring(0, irisUrl.length() - 1) : irisUrl;
        this.mock = mock;
    }

    /** 봇이 참여한 오픈 그룹채팅(OM) 방 목록 [{chatId, name}]. Iris 연결 실패 시 IllegalStateException. */
    public List<Map<String, Object>> listOpenGroupRooms() {
        if (mock) return mockRooms();
        String body;
        try {
            body = query("SELECT CAST(id AS TEXT) AS id, private_meta, meta FROM chat_rooms WHERE type='OM'");
        } catch (Exception e) {
            throw new IllegalStateException("봇 브릿지(Iris) 연결 실패: " + e.getMessage());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Object data = Json.asMap(Json.parse(body)).get("data");
        if (!(data instanceof List)) return out;
        for (Object o : (List<?>) data) {
            Map<String, Object> row = Json.asMap(o);
            String chatId = str(row.get("id"));
            if (chatId == null || chatId.isBlank()) continue;
            out.add(room(chatId, nameFrom(str(row.get("private_meta")), chatId)));
        }
        return out;
    }

    private String query(String sql) throws Exception {
        String reqBody = Json.write(Map.of("query", sql, "bind", List.of()));
        HttpRequest req = HttpRequest.newBuilder(URI.create(irisUrl + "/query"))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(reqBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new IllegalStateException("Iris 응답 " + r.statusCode());
        return r.body();
    }

    /** private_meta({"name":".."})에서 방 이름을 뽑는다. 없으면 chatId로 폴백. */
    private static String nameFrom(String privateMeta, String chatId) {
        if (privateMeta != null && !privateMeta.isBlank()) {
            try {
                Object n = Json.asMap(Json.parse(privateMeta)).get("name");
                if (n != null && !n.toString().isBlank()) return n.toString();
            } catch (Exception ignore) { }
        }
        return chatId;
    }

    /** dev 모드 mock(로컬 UI 테스트용). 실제 chat_id·이름 샘플. */
    private static List<Map<String, Object>> mockRooms() {
        String[][] fake = {
            {"18488589739179370", "메키000"}, {"18473102057478780", "메키001"},
            {"18467154652119770", "메키002"}, {"18466995836545474", "메키003"},
            {"18469658639955377", "메키004"},
        };
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] f : fake) out.add(room(f[0], f[1]));
        return out;
    }

    private static Map<String, Object> room(String chatId, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chatId", chatId);
        m.put("name", name);
        return m;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
