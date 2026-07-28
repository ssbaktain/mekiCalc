package mekicalc.web.boot;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mekicalc.engine.skill.ArtifactEffects;
import mekicalc.json.Json;

/**
 * 요청 파싱 헬퍼. 본문(Json.parse 결과 Map)에서 값을 꺼내는 static 유틸 모음(숫자는 전부 Double 규약).
 * 쿠키 읽기 등 요청 공통 파싱도 여기 모은다.
 */
final class Req {

    private Req() {}

    /** 요청 Cookie 헤더에서 name 쿠키 값(없으면 null). */
    static String cookie(HttpServletRequest req, String name) {
        String header = req.getHeader("Cookie");
        if (header == null) return null;
        for (String c : header.split(";")) {
            String t = c.trim();
            int i = t.indexOf('=');
            if (i > 0 && t.substring(0, i).equals(name)) return t.substring(i + 1);
        }
        return null;
    }

    /** {key: number, …} → statId→Double 맵. null/비객체면 빈 맵. */
    @SuppressWarnings("unchecked")
    static Map<String, Double> toDoubleMap(Object o) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (!(o instanceof Map)) return out;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
            if (e.getValue() instanceof Double) out.put(e.getKey(), (Double) e.getValue());
        }
        return out;
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) throw new IllegalArgumentException("필수 키 누락: " + key);
        return v.toString();
    }

    /** 선택 키(누락 시 null). */
    static String strOpt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        return (v instanceof Boolean) ? (Boolean) v : def;
    }

    static double num(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        return (v instanceof Double) ? (Double) v : def;
    }

    /** 정수 또는 없음(null). */
    static Integer intOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v instanceof Double) ? (int) (double) (Double) v : null;
    }

    /** Object → Map(객체). null/비객체면 빈 맵. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asObj(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    /**
     * 버프 id 배열 → Set. <b>null/비배열이면 null</b>(= 전부 적용, 하위호환). 빈 배열이면 빈 Set(= 아무것도 적용 안 함).
     */
    @SuppressWarnings("unchecked")
    static Set<String> toStrSet(Object o) {
        if (!(o instanceof List)) return null;
        Set<String> out = new LinkedHashSet<>();
        for (Object el : (List<Object>) o) if (el != null) out.add(el.toString());
        return out;
    }

    /** JSON 배열(객체들) → List&lt;Map&gt;. null/비배열이면 빈 리스트. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mapList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(o instanceof List)) return out;
        for (Object el : (List<Object>) o) {
            if (el instanceof Map) out.add((Map<String, Object>) el);
        }
        return out;
    }

    /** 유물 장착 배열 [{id, awaken}] → List&lt;Equip&gt;. null/비배열이면 빈 리스트. */
    static List<ArtifactEffects.Equip> toEquipList(Object o) {
        List<ArtifactEffects.Equip> out = new ArrayList<>();
        for (Map<String, Object> e : mapList(o)) {
            Object id = e.get("id");
            if (id == null) continue;
            out.add(new ArtifactEffects.Equip(id.toString(), (int) Json.num(e, "awaken", 0)));
        }
        return out;
    }
}
