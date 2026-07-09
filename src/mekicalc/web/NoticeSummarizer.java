package mekicalc.web;

import java.io.IOException;
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
 * 넥슨 공지 본문(텍스트)을 한국어로 요약하고 쿠폰 정보를 추출한다.
 *
 * <p>1순위 <b>Gemini</b>(data/gemini.json — 놀고 있는 무료 키, 공지 요약은 저빈도라 일일 한도에 여유). 키가 없으면
 * 로컬/개발 환경에서도 요약을 볼 수 있도록 기존 OCR 제공자(Groq→Mistral, OpenAI 호환 chat)로 폴백한다. 셋 다 없으면
 * {@link #enabled()}=false(요약 없이 제목·원문 링크만 노출). JDK 내장 {@link HttpClient}만 사용(무의존).
 *
 * <p>본문 원문을 그대로 저장·복제하지 않고 <b>재구성한 요약</b>만 만든다(저작권 안전지대). 각 말머리(분류)별로
 * 핵심 관점을 프롬프트로 지시해 요약 품질을 높인다.
 */
public final class NoticeSummarizer {

    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final String GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MISTRAL_ENDPOINT = "https://api.mistral.ai/v1/chat/completions";

    private final GeminiConfig gemini;
    private final OcrConfig ocr;
    private final HttpClient http = HttpClient.newHttpClient();

    public NoticeSummarizer(GeminiConfig gemini, OcrConfig ocr) {
        this.gemini = gemini;
        this.ocr = ocr;
    }

    public boolean enabled() {
        return gemini.enabled || ocr.groqEnabled || ocr.mistralEnabled;
    }

    /** 현재 사용 중인 제공자 이름(로그/기동 출력용). */
    public String provider() {
        if (gemini.enabled) return "Gemini(" + gemini.model + ")";
        if (ocr.groqEnabled) return "Groq(" + ocr.groqModel + ")";
        if (ocr.mistralEnabled) return "Mistral(" + ocr.mistralModel + ")";
        return "없음";
    }

    /**
     * 요약 결과: 강한(짧은) 요약 + 추출된 쿠폰 목록. 원문을 재구성한 것이며 verbatim 복제가 아니다.
     * (원문 이미지·표·본문은 저작권/약관 리스크 때문에 가져오지 않는다 — 상세는 '원문 보기' 링크로 대체.)
     */
    public static final class Result {
        public final String summary;   // 강한 요약(짧게, 개조식)
        public final List<Map<String, Object>> coupons;
        public Result(String summary, List<Map<String, Object>> coupons) {
            this.summary = summary;
            this.coupons = coupons;
        }
    }

    /**
     * 공지 한 건을 요약한다. 실패(제공자 오류·빈 응답)하면 {@link IOException}. 호출부가 다음 폴링에 재시도한다.
     *
     * @param category 말머리 분류(공지사항/패치노트/이벤트/확인 중인 현상 등) — 요약 관점 결정
     * @param title    게시글 제목
     * @param plainText 태그 제거된 본문 텍스트
     */
    public Result summarize(String category, String title, String plainText)
            throws IOException, InterruptedException {
        String prompt = buildPrompt(category, title, plainText);
        // 제공자 폴백 체인: Gemini → Groq → Mistral. 한 제공자가 오류(429 한도 등)면 다음으로 넘어간다.
        Map<String, Object> parsed = null;
        IOException lastErr = null;
        if (gemini.enabled) {
            try { parsed = callGemini(prompt); }
            catch (IOException e) { lastErr = e; }
        }
        if (parsed == null && ocr.groqEnabled) {
            try { parsed = callOpenAiChat(GROQ_ENDPOINT, ocr.groqApiKey, ocr.groqModel, prompt); }
            catch (IOException e) { lastErr = e; }
        }
        if (parsed == null && ocr.mistralEnabled) {
            try { parsed = callOpenAiChat(MISTRAL_ENDPOINT, ocr.mistralApiKey, ocr.mistralModel, prompt); }
            catch (IOException e) { lastErr = e; }
        }
        if (parsed == null) throw (lastErr != null ? lastErr : new IOException("요약 제공자가 설정되지 않았습니다."));

        String summary = str(parsed.get("summary"));
        if (summary == null || summary.isBlank()) throw new IOException("요약 결과가 비어 있습니다.");
        List<Map<String, Object>> coupons = new ArrayList<>();
        Object cs = parsed.get("coupons");
        if (cs instanceof List) {
            for (Object o : (List<?>) cs) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                String code = str(m.get("code"));
                if (code == null || code.isBlank()) continue;   // 코드 없는 항목은 쿠폰이 아님
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("code", code.trim());
                c.put("start", blankToNull(str(m.get("start"))));
                c.put("end", blankToNull(str(m.get("end"))));
                c.put("desc", blankToNull(str(m.get("desc"))));
                coupons.add(c);
            }
        }
        return new Result(cleanSummaryLines(summary), coupons);
    }

    /** 값 없는 '레이블 :' 줄(모델이 가끔 남기는 빈 항목)과 빈 줄을 제거한다. */
    private static String cleanSummaryLines(String s) {
        StringBuilder out = new StringBuilder();
        for (String raw : s.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            String bodyText = line.replaceFirst("^[-•*]\\s*", "").strip();   // 불릿 제거 후
            if (bodyText.matches("[^:]{1,14}\\s*[:：]\\s*")) continue;         // 콜론 뒤 값이 비면 버림
            out.append(out.length() > 0 ? "\n" : "").append(line);
        }
        return out.toString();
    }

    // ──────────────────── 프롬프트 ────────────────────

    private static String buildPrompt(String category, String title, String plainText) {
        String focus = focusFor(category);
        // 본문이 너무 길면 잘라서 토큰 절약(공지 본문은 대개 짧음).
        String body = plainText.length() > 6000 ? plainText.substring(0, 6000) + " …(생략)" : plainText;
        return "너는 모바일 게임 '메이플스토리 키우기'의 공식 게시글을 한국어로 요약하는 도우미다.\n"
                + "아래 [" + category + "] 게시글을 읽고, 핵심만 담은 짧은 요약을 만들어라.\n\n"
                + "[요약 지침]\n"
                + focus
                + "- **문체(중요)**: 원문을 그대로 옮기지 말고 '개조식'으로 다시 써라. '~합니다/~됩니다/~입니다/~하세요/~있습니다' 같은 "
                + "존댓말 종결어미로 끝내지 말고 명사형·짧은 형태로 끝맺어라. 예) '획득할 수 있습니다' → '획득' / '증가합니다' → '증가' / '참여 가능합니다' → '참여 가능'.\n"
                + "- **가시성(중요)**: 속성이 뚜렷한 항목은 '레이블 : 값' 형태로 써서 한눈에 보이게 하라. "
                + "예) '기간 : 2026년 7월 9일 오전 0시 ~ 7월 22일 오후 11시 59분', '대상 : 전체 서버', '조건 : 챕터 1-4 클리어', '보상 : 레드 다이아 5,000개', '쿠폰 : VICTORY2026'.\n"
                + "- 기간·범위는 물결표(~)로만 연결하라. '부터/까지/시작/끝' 같은 단어는 넣지 마라. "
                + "예) '2026년 7월 9일 오전 0시 ~ 7월 22일 오후 11시 59분'.\n"
                + "- 원문 문장을 복사하지 말고 핵심만 남겨 3~6줄로 간결하게. 각 줄 앞에 '- '. 표현·어순도 원문과 다르게.\n"
                + "- 일시·기간·수치·고유명사 등 구체 정보는 살리되 과장·창작 금지. 이미지·표 내용은 글로 옮기지 마라.\n"
                + "- 원문에 없는 속성의 레이블은 만들지 마라(예: 참여 조건이 없으면 '조건 :' 줄 자체를 생략). 값 없는 레이블 금지.\n"
                + "- 쿠폰/coupon 코드가 있으면 coupons 배열에 코드와 '쿠폰 사용 기한'을 넣어라(start=시작일, end=종료일, YYYY-MM-DD). "
                + "**쿠폰 사용 기한과 무관한 날짜(오프라인 행사일·게시일 등)와 혼동 금지.** 명시되지 않은 start는 빈 문자열로 두고 연도를 임의로 지어내지 마라. "
                + "요약의 '기간 :' 도 이 쿠폰/이벤트 사용 기간을 쓰고 부수 일정과 섞지 마라. 쿠폰이 없으면 빈 배열([]).\n\n"
                + "[게시글]\n"
                + "제목: " + title + "\n"
                + "본문:\n" + body + "\n\n"
                + "반드시 아래 JSON 형식만 출력하라(다른 텍스트 금지). summary 예시처럼 '레이블 : 값'을 활용하라:\n"
                + "{\"summary\": \"- 배틀 패스 부스트 이벤트 진행\\n- 기간 : 2026년 7월 9일 오전 0시 ~ 7월 22일 오후 11시 59분\\n- 보상 : 배틀 패스 보상 2배\", "
                + "\"coupons\": [{\"code\": \"\", \"start\": \"\", \"end\": \"\", \"desc\": \"\"}]}";
    }

    /** 말머리(분류)별 요약이 집중해야 할 핵심 관점. */
    private static String focusFor(String category) {
        String c = category == null ? "" : category;
        if (c.contains("패치")) {
            return "- 이 글은 패치노트다. 추가/변경/수정된 항목과 밸런스 조정, 버그 수정을 항목별로 정리하라.\n";
        }
        if (c.contains("이벤트")) {
            return "- 이 글은 이벤트 안내다. 이벤트 기간, 참여 조건, 보상, 그리고 쿠폰 코드/사용 기한을 반드시 확인해 정리하라.\n";
        }
        if (c.contains("확인") || c.contains("현상")) {
            return "- 이 글은 '확인 중인 현상' 안내다. 알려진 문제(증상), 발생 조건, 임시 조치, 수정 예정 여부를 정리하라.\n";
        }
        // 공지사항(기본): 점검/운영 이슈
        return "- 이 글은 공식 공지다. 무엇에 대한 안내인지, 일시/기간, 영향 범위, 이용자가 해야 할 조치를 중심으로 정리하라.\n";
    }

    // ──────────────────── 제공자별 호출 ────────────────────

    private Map<String, Object> callGemini(String prompt) throws IOException, InterruptedException {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", prompt);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("parts", List.of(textPart));

        Map<String, Object> genCfg = new LinkedHashMap<>();
        genCfg.put("temperature", 0.2);
        genCfg.put("responseMimeType", "application/json");
        genCfg.put("responseSchema", geminiSchema());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(content));
        body.put("generationConfig", genCfg);

        HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(GEMINI_ENDPOINT, gemini.model, gemini.apiKey)))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        String raw = send(req, "Gemini");
        String text = geminiText(Json.asMap(Json.parse(raw)));
        return parseJsonObject(text);
    }

    private Map<String, Object> callOpenAiChat(String endpoint, String apiKey, String model, String prompt)
            throws IOException, InterruptedException {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", prompt);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(msg));
        body.put("temperature", 0.2);
        Map<String, Object> rf = new LinkedHashMap<>();
        rf.put("type", "json_object");
        body.put("response_format", rf);

        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        String raw = send(req, "요약 모델");
        String text = openAiContent(Json.asMap(Json.parse(raw)));
        return parseJsonObject(text);
    }

    /** 공통 전송 + 재시도(503/500 백오프). 2xx 아니면 IOException. */
    private String send(HttpRequest req, String who) throws IOException, InterruptedException {
        int[] backoffMs = {800, 2000, 4000};
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int sc = resp.statusCode();
            if (sc / 100 == 2) return resp.body();
            if ((sc == 503 || sc == 500 || sc == 429) && attempt < backoffMs.length) {
                Thread.sleep(backoffMs[attempt]);
                continue;
            }
            throw new IOException(who + " API 오류(" + sc + "): " + clip(resp.body()));
        }
    }

    // ──────────────────── 응답 파싱 ────────────────────

    private static Map<String, Object> parseJsonObject(String text) throws IOException {
        if (text == null || text.isBlank()) throw new IOException("요약 응답이 비어 있습니다.");
        String t = text.trim();
        // 혹시 코드펜스로 감싸져 오면 벗겨낸다.
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        Object parsed = Json.parse(t);
        if (!(parsed instanceof Map)) throw new IOException("요약 응답 형식 오류");
        return Json.asMap(parsed);
    }

    private static String geminiText(Map<String, Object> root) {
        Object cands = root.get("candidates");
        if (!(cands instanceof List) || ((List<?>) cands).isEmpty()) return null;
        Object c0 = ((List<?>) cands).get(0);
        if (!(c0 instanceof Map)) return null;
        Object contentObj = ((Map<?, ?>) c0).get("content");
        if (!(contentObj instanceof Map)) return null;
        Object parts = ((Map<?, ?>) contentObj).get("parts");
        if (!(parts instanceof List)) return null;
        StringBuilder sb = new StringBuilder();
        for (Object p : (List<?>) parts) {
            if (p instanceof Map) {
                Object x = ((Map<?, ?>) p).get("text");
                if (x != null) sb.append(x);
            }
        }
        return sb.toString();
    }

    private static String openAiContent(Map<String, Object> root) {
        Object choices = root.get("choices");
        if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) return null;
        Object c0 = ((List<?>) choices).get(0);
        if (!(c0 instanceof Map)) return null;
        Object message = ((Map<?, ?>) c0).get("message");
        if (!(message instanceof Map)) return null;
        Object content = ((Map<?, ?>) message).get("content");
        return content == null ? null : content.toString();
    }

    /** Gemini responseSchema(OpenAPI 서브셋). */
    private static Object geminiSchema() {
        Map<String, Object> str = Map.of("type", "STRING");
        Map<String, Object> couponProps = new LinkedHashMap<>();
        couponProps.put("code", str);
        couponProps.put("start", str);
        couponProps.put("end", str);
        couponProps.put("desc", str);
        Map<String, Object> couponItem = new LinkedHashMap<>();
        couponItem.put("type", "OBJECT");
        couponItem.put("properties", couponProps);
        Map<String, Object> coupons = new LinkedHashMap<>();
        coupons.put("type", "ARRAY");
        coupons.put("items", couponItem);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("summary", str);
        props.put("coupons", coupons);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", props);
        return schema;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }
    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
