package mekicalc.timeline.ir;

import java.util.List;
import java.util.Map;

/** IR(JSON Map) 파싱용 null-안전 읽기 헬퍼. Json.parse 결과(Double/Boolean/String/List/Map)를 그대로 받는다. */
final class IRRead {

    private IRRead() {}

    static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : (String) v;
    }

    static Double dbl(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : ((Number) v).doubleValue();
    }

    static Integer intg(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : (int) ((Number) v).doubleValue();
    }

    /** "9000" 같은 문자열 숫자 idx를 int로. 없으면 null. */
    static Integer strInt(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof Number n) return (int) n.doubleValue();
        return Integer.parseInt(((String) v).trim());
    }

    static Boolean bool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : (Boolean) v;
    }

    @SuppressWarnings("unchecked")
    static List<String> strList(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? List.of() : (List<String>) (List<?>) v;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mapList(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? List.of() : (List<Map<String, Object>>) (List<?>) v;
    }
}
