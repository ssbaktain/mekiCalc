package mekicalc.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 의존성 없는 미니 JSON 파서/접근 헬퍼.
 * 표준 JSON(객체/배열/문자열/숫자/true/false/null)을 다음 Java 타입으로 변환한다.
 *   object -> {@code Map<String,Object>} (입력 순서 보존)
 *   array  -> {@code List<Object>}
 *   string -> String
 *   number -> Double
 *   bool   -> Boolean
 *   null   -> null
 * 게임 데이터 파일이 작고 단순하므로 외부 라이브러리 없이 자체 파서로 충분하다.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String src) {
        this.s = src;
        this.i = 0;
    }

    /** JSON 문자열을 Object 트리로 파싱한다. */
    public static Object parse(String src) {
        Json p = new Json(src);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw p.err("입력 끝에 불필요한 문자가 남아 있음");
        }
        return v;
    }

    // ---- 재귀 하강 파서 ----

    private Object value() {
        char c = peek();
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': case 'f': return bool();
            case 'n': literal("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("값을 파싱할 수 없음 (문자 '" + c + "')");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        expect('{');
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            ws();
            m.put(key, value());
            ws();
            char c = next();
            if (c == '}') break;
            if (c != ',') throw err("객체에서 ',' 또는 '}' 기대");
        }
        return m;
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        expect('[');
        ws();
        if (peek() == ']') { i++; return list; }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = next();
            if (c == ']') break;
            if (c != ',') throw err("배열에서 ',' 또는 ']' 기대");
        }
        return list;
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') break;
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        String hex = s.substring(i, i + 4);
                        i += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                        break;
                    default: throw err("알 수 없는 이스케이프 '\\" + e + "'");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Double number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                i++;
            } else {
                break;
            }
        }
        return Double.parseDouble(s.substring(start, i));
    }

    private Boolean bool() {
        if (peek() == 't') { literal("true"); return Boolean.TRUE; }
        literal("false");
        return Boolean.FALSE;
    }

    // ---- 저수준 유틸 ----

    private void literal(String lit) {
        if (!s.startsWith(lit, i)) throw err("'" + lit + "' 기대");
        i += lit.length();
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private char peek() {
        if (i >= s.length()) throw err("입력이 예상보다 일찍 끝남");
        return s.charAt(i);
    }

    private char next() {
        if (i >= s.length()) throw err("입력이 예상보다 일찍 끝남");
        return s.charAt(i++);
    }

    private void expect(char c) {
        char got = next();
        if (got != c) throw err("'" + c + "' 기대했지만 '" + got + "'");
    }

    private RuntimeException err(String msg) {
        return new IllegalArgumentException("JSON 파싱 오류 (위치 " + i + "): " + msg);
    }

    // ---- 타입 안전 접근 헬퍼 (캐스팅을 한 곳에 모음) ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("객체(Map) 기대: " + o);
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        if (!(o instanceof List)) throw new IllegalArgumentException("배열(List) 기대: " + o);
        return (List<Object>) o;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) throw new IllegalArgumentException("필수 문자열 키 누락: " + key);
        return v.toString();
    }

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }

    public static double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Double)) throw new IllegalArgumentException("필수 숫자 키 누락/형식오류: " + key);
        return (Double) v;
    }

    public static double num(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        return (v instanceof Double) ? (Double) v : def;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        return (v instanceof Boolean) ? (Boolean) v : def;
    }

    /** Object 트리(Map/List/String/Double/Boolean/null)를 들여쓴 JSON 문자열로 직렬화. */
    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        writeValue(o, sb, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeValue(Object o, StringBuilder sb, int indent) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { sb.append('"').append(escape((String) o)).append('"'); return; }
        if (o instanceof Boolean) { sb.append(o.toString()); return; }
        if (o instanceof Double) { sb.append(numStr((Double) o)); return; }
        if (o instanceof Number) { sb.append(numStr(((Number) o).doubleValue())); return; }
        if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int n = m.size(), k = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, indent + 1);
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\": ");
                writeValue(e.getValue(), sb, indent + 1);
                sb.append(++k < n ? ",\n" : "\n");
            }
            pad(sb, indent); sb.append('}');
            return;
        }
        if (o instanceof List<?> list) {
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int idx = 0; idx < list.size(); idx++) {
                pad(sb, indent + 1);
                writeValue(list.get(idx), sb, indent + 1);
                sb.append(idx < list.size() - 1 ? ",\n" : "\n");
            }
            pad(sb, indent); sb.append(']');
            return;
        }
        // 알 수 없는 타입은 문자열로
        sb.append('"').append(escape(String.valueOf(o))).append('"');
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
    }

    private static String numStr(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15)
                ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
