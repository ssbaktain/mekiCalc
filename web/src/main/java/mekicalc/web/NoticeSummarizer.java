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
        public final long startAt;     // 이벤트/기간 시작(epoch sec, KST 기준). 없으면 0.
        public final long endAt;       // 이벤트/기간 종료(epoch sec). 리마인더·진행중 판별용. 없으면 0.
        public Result(String summary, List<Map<String, Object>> coupons, long startAt, long endAt) {
            this.summary = summary;
            this.coupons = coupons;
            this.startAt = startAt;
            this.endAt = endAt;
        }
    }

    /** ISO 일시("yyyy-MM-ddTHH:mm[:ss]" 또는 날짜만) → epoch sec(KST). 실패/빈값 0. 날짜만이면 그 날 23:59:59. */
    static long parseKstEpoch(String s, boolean endOfDayIfDateOnly) {
        if (s == null) return 0;
        String t = s.trim().replace(' ', 'T');
        if (t.isEmpty()) return 0;
        try {
            java.time.LocalDateTime ldt;
            if (t.length() <= 10) {
                java.time.LocalDate d = java.time.LocalDate.parse(t.substring(0, Math.min(10, t.length())));
                ldt = endOfDayIfDateOnly ? d.atTime(23, 59, 59) : d.atStartOfDay();
            } else {
                ldt = java.time.LocalDateTime.parse(t.length() == 16 ? t + ":00" : t.substring(0, 19));
            }
            return ldt.atZone(java.time.ZoneId.of("Asia/Seoul")).toEpochSecond();
        } catch (Exception e) { return 0; }
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
            try { parsed = callGemini(prompt, geminiSchema()); }
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

        String summary = joinSummary(parsed.get("summary"));
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
        long startAt = parseKstEpoch(str(parsed.get("startAt")), false);
        long endAt = parseKstEpoch(str(parsed.get("endAt")), true);
        return new Result(subBulletLongLists(cleanSummaryLines(summary)), coupons, startAt, endAt);
    }

    /**
     * summary를 줄바꿈 문자열로 정규화. 모델이 배열(줄 배열)로 주면 원소당 한 줄로 합쳐 줄바꿈을 보장한다
     * (문자열로 주면서 '\n'을 빠뜨리는 비결정성 방지). 문자열이면 그대로 쓰되, 한 줄에 뭉친 '- ' 불릿은 분리한다.
     */
    private static String joinSummary(Object sm) {
        if (sm instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object ln : (List<?>) sm) {
                if (ln == null) continue;
                String l = ln.toString().strip();
                if (l.isEmpty()) continue;
                if (!l.startsWith("-")) l = "- " + l;   // 불릿 없으면 보정
                sb.append(sb.length() > 0 ? "\n" : "").append(l);
            }
            return sb.toString();
        }
        String s = str(sm);
        if (s == null) return null;
        // 문자열인데 '- ' 불릿이 한 줄에 뭉친 경우(비-공백 뒤 '- ') 줄바꿈 삽입. 기간은 '~'라 '- '는 사실상 불릿뿐.
        return s.replaceAll("(\\S)- (?=\\S)", "$1\n- ");
    }

    /** 값 없는 '레이블 :' 줄(모델이 가끔 남기는 빈 항목)과 빈 줄을 제거한다. */
    private static String cleanSummaryLines(String s) {
        StringBuilder out = new StringBuilder();
        for (String raw : s.split("\n")) {
            String line = raw.stripTrailing();   // 들여쓰기(앞 공백=서브불릿 계층)는 보존, 뒤 공백만 정리
            String t = line.strip();
            if (t.isEmpty()) continue;
            String bodyText = t.replaceFirst("^[-•*ㄴ]\\s*", "").strip();   // 불릿/서브불릿 제거 후
            if (bodyText.matches("[^:]{1,14}\\s*[:：]\\s*")) continue;         // 콜론 뒤 값이 비면 버림
            out.append(out.length() > 0 ? "\n" : "").append(line);
        }
        return out.toString();
    }

    /**
     * 한 줄에 쉼표로 몰린 긴 목록('- 레이블 : a, b, c … 5개 이상')을 제목 줄 + '   ㄴ 항목' 서브불릿으로 분해(가독성).
     * '이벤트'처럼 하위 항목이 많은 대분류가 한 줄 블롭으로 올 때 결정론적으로 처리한다(모델 형식에 의존 안 함). 4개 이하는 그대로.
     */
    private static String subBulletLongLists(String s) {
        if (s == null) return null;
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            int idx = line.indexOf(" : ");
            if (line.startsWith("- ") && idx > 0) {
                String[] items = line.substring(idx + 3).split("\\s*,\\s*");
                if (items.length >= 5) {
                    out.append(out.length() > 0 ? "\n" : "").append(line, 0, idx);   // "- 이벤트"(값 없이 제목만)
                    for (String it : items) {
                        String v = it.trim();
                        if (!v.isEmpty()) out.append("\n   ㄴ ").append(v);
                    }
                    continue;
                }
            }
            out.append(out.length() > 0 ? "\n" : "").append(line);
        }
        return out.toString();
    }

    // ──────────────────── 구조화 추출(NOTICE_PIPELINE_SPEC §4) ────────────────────

    /**
     * 공지 본문에서 구조화 데이터(NoticeData)를 추출한다. 텍스트 요약이 아니라 JSON 구조만 뽑는다.
     * 압축·형식·발송판단은 렌더러(서버 코드)가 담당. 실패 시 IOException(호출부가 다음 폴링에 재시도).
     *
     * @param boardType 게시판 유형(공지사항/패치노트/이벤트/확인 중인 현상) — notice_type 1순위 힌트
     * @param title     게시글 제목
     * @param plainText 태그 제거된 본문
     * @param sourceUrl 원문 URL
     */
    public NoticeData extractStructured(String boardType, String title, String plainText, String sourceUrl)
            throws IOException, InterruptedException {
        Map<String, Object> parsed = callChain(buildStructuredPrompt(boardType, title, plainText, sourceUrl));
        NoticeData d = NoticeData.fromMap(parsed);
        if (d == null || (d.title == null && d.sections.isEmpty() && d.coupons.isEmpty()
                && d.issues == null && d.eventMeta == null && d.mailRewards == null)) {
            throw new IOException("구조화 추출 결과가 비어 있습니다.");
        }
        demoteWrongIssue(d, boardType);   // '확인 중인 현상'이 아닌 게시판인데 issue로 오분류되면 일반 안내로 강등(프롬프트 비결정성 보완)
        stripBoilerplate(d);   // 상투 안내(점검·이벤트 규약·표준 수령 등) 결정론적 제거(프롬프트 비결정성 보완)
        fixLongItemTexts(d);   // Tier 2: 40자 초과 item.text만 재압축(sections 한정, 쿠폰/우편 제외)
        return d;
    }

    // 매 공지에 반복되는 상투 안내(고유 정보 아님). caution·sections item에서 결정론적으로 제거.
    private static final java.util.regex.Pattern[] BOILERPLATE = {
        java.util.regex.Pattern.compile("순단"),
        java.util.regex.Pattern.compile("점검\\s*영향"),
        java.util.regex.Pattern.compile("접속.{0,8}(불가|제한)"),   // '접속·쿠폰 사용 불가' 등 점검 영향 상투
        java.util.regex.Pattern.compile("데이터 다운로드"),
        java.util.regex.Pattern.compile("(?i)wi-?fi|와이파이"),
        java.util.regex.Pattern.compile("데이터 사용"),
        java.util.regex.Pattern.compile("재실행"),
        java.util.regex.Pattern.compile("완전(히)?\\s*종료"),
        java.util.regex.Pattern.compile("일정.{0,6}변경"),
        java.util.regex.Pattern.compile("규약"),
        java.util.regex.Pattern.compile("사전\\s*안내"),
        java.util.regex.Pattern.compile("미수령|미획득"),
        java.util.regex.Pattern.compile("직접.{0,4}수령|터치.{0,4}수령|수령하(셔야|셔서|해야)"),
        java.util.regex.Pattern.compile("(매일|0시|자정).{0,6}초기화|초기화.{0,6}(매일|0시|자정)"),
        java.util.regex.Pattern.compile("공지.{0,4}업데이트|추가\\s*안내"),
        java.util.regex.Pattern.compile("원문.{0,5}참고|자세한\\s*내용은|자세한.{0,5}참고"),
        java.util.regex.Pattern.compile("개발\\s*중"),
        java.util.regex.Pattern.compile("고객센터|1:1\\s*문의|문의\\s*(하|주|부탁)"),
        java.util.regex.Pattern.compile("이용에\\s*참고|참고\\s*(부탁|바랍)"),
    };

    /**
     * 'issue'(확인 중인 현상)는 '확인 중인 현상' 게시판 전용 유형이다. 다른 게시판(공지사항·패치노트·이벤트) 글이
     * issue로 잘못 분류되면 렌더러가 "⚠️ 확인 중인 현상 안내" + 🔴확인중/✅수정완료 템플릿을 붙여 오해를 준다
     * (예: '월드 아레나 제재 완료 안내'가 '확인 중인 현상'으로 보임). 이 경우 issues 데이터를 일반 안내(info 섹션)로
     * 옮기고 notice_type을 etc로 강등한다 → 헤더가 "📋 {제목}"이 되고 본문은 그대로 보존. 쿠폰·우편 보상은 손대지 않는다.
     */
    static void demoteWrongIssue(NoticeData d, String boardType) {
        if (d == null || !"issue".equals(d.noticeType)) return;
        if ("확인 중인 현상".equals(boardType)) return;   // 정상: 확인 중인 현상 게시판 글
        if (d.issues != null) {
            NoticeData.Section info = null;
            for (NoticeData.Section s : d.sections) if ("info".equals(s.key)) { info = s; break; }
            if (info == null) {
                info = new NoticeData.Section();
                info.key = "info"; info.heading = "안내";
                d.sections.add(info);
            }
            for (NoticeData.OpenIssue o : d.issues.open) {
                if (o.text == null) continue;
                NoticeData.Item it = new NoticeData.Item();
                it.text = o.text;
                if (o.workaround != null) it.sub.add(o.workaround);
                info.items.add(it);
            }
            for (NoticeData.FixedIssue f : d.issues.fixed) {
                if (f.text == null) continue;
                NoticeData.Item it = new NoticeData.Item();
                it.text = f.fixedDate != null ? f.text + " (" + f.fixedDate + ")" : f.text;
                info.items.add(it);
            }
            if (info.items.isEmpty()) d.sections.remove(info);
            d.issues = null;
        }
        d.noticeType = "etc";
    }

    static boolean isBoilerplate(String t) {
        if (t == null) return false;
        for (java.util.regex.Pattern p : BOILERPLATE) if (p.matcher(t).find()) return true;
        return false;
    }

    /** caution·sections item에서 상투 문구 제거. 쿠폰/우편/issues(고유 현상)는 대상 아님. */
    static void stripBoilerplate(NoticeData d) {
        if (d == null) return;
        if (d.eventMeta != null && isBoilerplate(d.eventMeta.caution)) d.eventMeta.caution = null;
        for (NoticeData.Section s : d.sections) {
            s.items.removeIf(it -> isBoilerplate(it.text));
            for (NoticeData.Item it : s.items) it.sub.removeIf(NoticeSummarizer::isBoilerplate);
        }
        d.sections.removeIf(s -> s.items.isEmpty());
    }

    /** 제공자 폴백 체인(Gemini→Groq→Mistral). 429·파싱오류 모두 다음으로. 전부 실패면 IOException. */
    private Map<String, Object> callChain(String prompt) throws IOException, InterruptedException {
        Exception last = null;
        if (gemini.enabled) { try { return callGemini(prompt, null); } catch (Exception e) { last = e; } }
        if (ocr.groqEnabled) { try { return callOpenAiChat(GROQ_ENDPOINT, ocr.groqApiKey, ocr.groqModel, prompt); } catch (Exception e) { last = e; } }
        if (ocr.mistralEnabled) { try { return callOpenAiChat(MISTRAL_ENDPOINT, ocr.mistralApiKey, ocr.mistralModel, prompt); } catch (Exception e) { last = e; } }
        throw new IOException("요약 제공자 호출 실패: " + (last != null ? last.getMessage() : "제공자 없음"));
    }

    static final int ITEM_TEXT_MAX = 40;   // sections item.text 하드 리밋(목표 25자, 여유 40)

    /** Tier 2: sections[].items[].text 중 40자 초과분만 모아 압축 전용 호출 1회로 재작성. 쿠폰/우편은 대상 아님. */
    private void fixLongItemTexts(NoticeData d) {
        List<NoticeData.Item> longs = new ArrayList<>();
        for (NoticeData.Section s : d.sections)
            for (NoticeData.Item it : s.items)
                if (it.text != null && it.text.length() > ITEM_TEXT_MAX) longs.add(it);
        if (longs.isEmpty()) return;
        List<String> texts = new ArrayList<>();
        for (NoticeData.Item it : longs) texts.add(it.text);
        try {
            List<String> comp = compressTexts(texts);
            if (comp.size() == texts.size())
                for (int i = 0; i < longs.size(); i++)
                    if (comp.get(i) != null && !comp.get(i).isBlank()) longs.get(i).text = comp.get(i).trim();
        } catch (Exception ignore) { /* 실패는 무시 — Tier3 렌더러 클립이 최후 방어 */ }
    }

    /** 문장 배열을 25자 내외로 압축(압축 전용 미니 호출). 단순 태스크라 비결정성 거의 없음. */
    public List<String> compressTexts(List<String> texts) throws IOException, InterruptedException {
        if (texts == null || texts.isEmpty()) return texts == null ? new ArrayList<>() : texts;
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) in.append(i).append(": ").append(texts.get(i)).append("\n");
        String prompt = "각 문장을 25자 내외의 한 줄로 압축한다. [대상 + 현상 + 수정] 골격만 남기고 조건·상황 설명은 제거한다. 원문 복사 금지, 재작성. "
                + "입력과 같은 개수·같은 순서로, JSON 객체 {\"out\": [\"압축1\", \"압축2\", ...]} 형식만 출력한다(다른 텍스트 금지).\n\n"
                + "입력(줄당 '번호: 문장'):\n" + in;
        Map<String, Object> parsed = callChain(prompt);
        List<String> out = new ArrayList<>();
        Object arr = parsed.get("out");
        if (arr instanceof List) for (Object o : (List<?>) arr) out.add(o == null ? null : o.toString());
        return out;
    }

    /** 구조화 추출 프롬프트(NOTICE_PIPELINE_SPEC §4). systemInstruction + 입력을 한 프롬프트로 합친다. */
    private static String buildStructuredPrompt(String boardType, String title, String plainText, String sourceUrl) {
        String body = plainText.length() > 20000 ? plainText.substring(0, 20000) + " …(생략)" : plainText;
        return "당신은 모바일 게임 '메이플 키우기'의 공지사항에서 구조화된 데이터를 추출하는 봇입니다.\n"
                + "입력: 공지 제목, 게시판 유형, 본문 텍스트, 원문 URL.\n"
                + "출력: 아래 스키마의 JSON만 출력한다. 마크다운 코드블록, 설명, 인사말을 절대 붙이지 않는다.\n\n"
                + "{\n"
                + "  \"notice_type\": \"preview | patchnote | event | issue | devnote | coupon | etc 중 하나\",\n"
                + "  \"title\": \"공지 제목 (원제목 기반, 간결하게)\",\n"
                + "  \"one_liner\": \"이 공지를 한 문장(40자 이내)으로 요약. 웹 목록 미리보기용\",\n"
                + "  \"date_label\": \"핵심 날짜. 예: '7/23(목) 예정', '7/9(목)~7/22(수)', '7/2(목) 적용'. 없으면 null\",\n"
                + "  \"sections\": [\n"
                + "    { \"key\": \"new | improve | bugfix | event | promise | info 중 하나\",\n"
                + "      \"heading\": \"섹션 표시명. 예: 신규 콘텐츠, 시스템/개선, 버그 수정, 이벤트, 주요 약속, 안내\",\n"
                + "      \"items\": [ { \"text\": \"항목 한 줄 요약 (25자 내외)\", \"sub\": [\"보조 정보, 없으면 빈 배열\"] } ] }\n"
                + "  ],\n"
                + "  \"event_meta\": { \"period\": \"7/9(목) ~ 7/22(수)\", \"benefit\": \"핵심 혜택 한 줄\", \"how_to\": \"참여 방법 한 줄\", \"caution\": \"필수 주의사항 한 줄, 없으면 null\" },\n"
                + "  \"issues\": { \"open\": [ { \"text\": \"현상 요약\", \"workaround\": \"임시 해결책 한 줄, 없으면 null\" } ], \"fixed\": [ { \"text\": \"현상 요약\", \"fixed_date\": \"7/15\" } ] },\n"
                + "  \"coupons\": [ { \"code\": \"쿠폰 코드 원문 그대로\", \"expires\": \"만료일 'M/D(요일)', 없으면 null\", \"rewards\": [\"보상 전체 목록. '아이템명 수량' 형식\"] } ],\n"
                + "  \"mail_rewards\": { \"reason\": \"지급 사유 한 줄\", \"target\": \"지급 대상 한 줄\", \"items\": [\"지급 보상 전체 목록. '아이템명 수량' 형식\"], \"sent_at\": \"지급 시점, 없으면 null\", \"claim_deadline\": \"수령 기한, 없으면 null\" }\n"
                + "}\n\n"
                + "[추출 규칙]\n"
                + "1. 이미지·영상·첨부파일 관련 내용은 무시한다.\n"
                + "2. event_meta는 notice_type이 event일 때만, issues는 issue일 때만 채우고 그 외엔 null. coupons와 mail_rewards는 notice_type과 무관하게 본문에 해당 내용이 있으면 항상 채우고, 없으면 null(또는 빈 배열).\n"
                + "3. 항목은 빠짐없이 전부 추출한다. 개수 제한이나 생략 처리를 하지 않는다. (압축은 시스템이 처리함)\n"
                + "4. [중요] coupons.rewards와 mail_rewards.items는 요약·생략·대표 추출 없이 본문에 명시된 보상 전 항목을 그대로 담는다. 수량도 정확히 옮긴다.\n"
                + "5. [중요] 각 항목의 text는 원문 문장을 옮기지 말고 반드시 재작성해 압축한다. 원문 문장이 40자를 넘으면 그대로 복사하는 것을 금지한다. "
                + "변환 예시 — 원문: \"2개 이상의 핫딜을 동시에 활성화한 상태로 첫 번째 핫딜을 구매해서 블루 다이아를 획득했을 때, 이전 핫딜에서 획득한 블루 다이아의 수량이 2번째 핫딜을 구매하는 버튼에 갱신되지 않던 현상 수정\" "
                + "→ text: \"핫딜 블루 다이아 수량 미갱신 수정\". 조건·상황 설명은 버리고 [대상 + 현상 + 수정] 골격만 남긴다. 세부 조건이 중요하면 sub에 한 줄로 담는다. 수치 테이블·상세 절차는 sub에 핵심만 담거나 생략한다.\n"
                + "6. 같은 대상이 반복되면 합친다. (예: 신규 직업 A,B + 신규 동료 A,B → \"직업/동료: A, B\")\n"
                + "7. 본문에 없는 내용을 추측해서 넣지 않는다. 해당 없는 필드는 null 또는 빈 배열.\n"
                + "8. 날짜는 \"M/D(요일)\" 형식으로 통일한다.\n"
                + "9. notice_type 판단: 게시판 유형을 신뢰한다. 특히 issue(확인 중인 현상)는 게시판 유형이 '확인 중인 현상'일 때만 쓴다. "
                + "공지사항·패치노트·이벤트 게시판의 글은 버그·오류·제재·조치·보상 지급 내용을 담고 있어도 issue로 분류하지 마라 — '확인 중인 현상'은 넥슨 전용 게시판이자 '조사 중인 알려진 문제' 개념이라, 다른 게시판 글(예: 제재 완료 안내, 보상 지급 안내)에 이 유형을 붙이면 완전히 틀린 안내가 된다. "
                + "그런 글은 성격에 따라 분류한다: 밸런스/버그 수정 위주면 patchnote, 이벤트면 event, 운영방향/사과/디렉터 글은 devnote, 제재·보상 지급·서버·점검·계정 등 일반 안내는 etc. "
                + "게시판이 '확인 중인 현상'인데 본문이 명백히 다른 유형(예: 순수 이벤트)일 때만 예외적으로 정정한다.\n"
                + "10. [중요] 각 공지에 관례적으로 붙는, 그 공지 '고유 정보'가 아닌 일반 안내·면책·메타 문구는 추출하지 마라(무시). 유형별 예: \n"
                + "  - 점검/패치: '지연/순단 현상 발생 가능', '데이터 다운로드 진행/필요', 'Wi-Fi 이용 권장', '게임 접속/이용 지연', '게임 완전 종료 후 재실행', '패치 일정은 변경될 수 있음'.\n"
                + "  - 이벤트: '이벤트 규약 참조', '이벤트 유의사항', '내용은 사전 안내 없이 변경/종료될 수 있음', '기간·보상은 변동될 수 있음'. "
                + "표준 보상 수령 안내도 제외: '미수령/미획득 보상은 종료 시 소멸', '보상은 직접(터치) 수령', '보상은 매일 0시 초기화'.\n"
                + "  - 메타: '추가 안내 : 공지사항 업데이트', '본 공지는 수정될 수 있음', '자세한 내용은 원문/해당 공지 참고'(원문 링크로 대체됨).\n"
                + "  - 미리보기: '개발 중이며 내용이 변경될 수 있음'.\n"
                + "  - 맺음말: 인사·감사·문의(고객센터)·'이용에 참고 부탁' 류.\n"
                + "sections·event_meta.caution·issues 어디에도 이런 일반 문구를 넣지 말고, 그 공지에서만 의미 있는 고유 정보(실제 변경/수정/추가·보상·일정·조건·현상)만 담아라. caution은 그 이벤트만의 진짜 특이 주의사항일 때만 채운다(일반 규약·면책·표준 보상 수령 안내면 null). 단 '챕터 1-4 클리어' 같은 참여 조건은 유용하니 유지. 담을 고유 내용이 없으면 해당 배열은 비운다.\n\n"
                + "[입력]\n"
                + "제목: " + title + "\n"
                + "게시판 유형: " + boardType + "\n"
                + "원문 URL: " + (sourceUrl == null ? "" : sourceUrl) + "\n"
                + "본문:\n" + body + "\n";
    }

    // ──────────────────── 프롬프트(레거시 텍스트 요약) ────────────────────

    private static String buildPrompt(String category, String title, String plainText) {
        String focus = focusFor(category);
        // 본문이 아주 길면(장문 패치노트) 잘라서 토큰 절약. Gemini 컨텍스트가 커서 여유 있게 둔다.
        String body = plainText.length() > 20000 ? plainText.substring(0, 20000) + " …(생략)" : plainText;
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
                + "- 원문 문장을 복사하지 말고 핵심만 남겨 각 줄 앞에 '- '를 붙여 간결하게 정리하라. "
                + "**원문의 모든 대분류(🔸·■·▶ 등 최상위 소제목)를 빠짐없이, 대분류마다 한 줄씩** 요약하라. 각 줄엔 그 대분류의 핵심(구체적 대상·수정 내용·보상·일정)은 살리되, 부차적인 세부 항목·수치를 여러 줄로 풀어 나열하지는 마라(상세는 원문 링크가 대신함). "
                + "예) '- 신규 직업 추가 : 윈드브레이커, 나이트워커', '- 내용 : 월드 아레나 직업 변경 오류 수정', '- 월간 출석 이벤트 추가'. "
                + "여러 항목을 묶는 대분류(예 '이벤트')는 하위 항목명만 쉼표로 한 줄에 나열. 단순 공지·짧은 패치는 종전처럼 '레이블 : 값' 3~6줄. 대분류를 임의로 생략하지 마라. 표현·어순은 원문과 다르게.\n"
                + "- 일시·기간·수치·고유명사 등 구체 정보는 살리되 과장·창작 금지. 이미지·표 내용은 글로 옮기지 마라.\n"
                + "- 원문에 없는 속성의 레이블은 만들지 마라(예: 참여 조건이 없으면 '조건 :' 줄 자체를 생략). 값 없는 레이블 금지.\n"
                + "- 쿠폰/coupon 코드가 있으면 coupons 배열에 코드와 '쿠폰 사용 기한'을 넣어라(start=시작일, end=종료일, YYYY-MM-DD). "
                + "**쿠폰 사용 기한과 무관한 날짜(오프라인 행사일·게시일 등)와 혼동 금지.** 명시되지 않은 start는 빈 문자열로 두고 연도를 임의로 지어내지 마라. "
                + "요약의 '기간 :' 도 이 쿠폰/이벤트 사용 기간을 쓰고 부수 일정과 섞지 마라. 쿠폰이 없으면 빈 배열([]).\n"
                + "- **이벤트/기간 종료시각(중요)**: 이 게시글이 기간이 있는 이벤트/혜택이면, 그 진행 기간의 시작·종료 '일시'를 "
                + "startAt, endAt 에 **ISO-8601(한국시간) `YYYY-MM-DDTHH:mm:ss` 형식**으로 넣어라. "
                + "예) 7월 22일 오후 11시 59분 종료 → endAt=\"2026-07-22T23:59:00\", 7월 16일 0시 종료 → endAt=\"2026-07-16T00:00:00\". "
                + "시각이 명시 안 됐으면 날짜만(\"2026-07-16\")이라도 넣어라. 기간이 없는 글(단순 공지·패치노트 등)이면 startAt·endAt 모두 빈 문자열. "
                + "종료가 '상시/무기한'이면 빈 문자열. 연도 미표기면 게시 맥락상 가장 가까운 미래로.\n\n"
                + "[게시글]\n"
                + "제목: " + title + "\n"
                + "본문:\n" + body + "\n\n"
                + "반드시 아래 JSON 형식만 출력하라(다른 텍스트 금지). summary는 각 줄을 줄바꿈(\\n)으로 구분한 하나의 문자열이며, 각 줄 앞에 '- '를 붙이고 '레이블 : 값'을 활용하라:\n"
                + "{\"summary\": \"- 배틀 패스 부스트 이벤트 진행\\n- 기간 : 2026년 7월 9일 오전 0시 ~ 7월 22일 오후 11시 59분\\n- 보상 : 배틀 패스 보상 2배\", "
                + "\"coupons\": [{\"code\": \"\", \"start\": \"\", \"end\": \"\", \"desc\": \"\"}], "
                + "\"startAt\": \"2026-07-09T00:00:00\", \"endAt\": \"2026-07-22T23:59:00\"}";
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

    private Map<String, Object> callGemini(String prompt, Object schema) throws IOException, InterruptedException {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", prompt);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("parts", List.of(textPart));

        Map<String, Object> genCfg = new LinkedHashMap<>();
        genCfg.put("temperature", 0.2);
        genCfg.put("responseMimeType", "application/json");
        if (schema != null) genCfg.put("responseSchema", schema);   // 복잡한 구조화 추출은 스키마 없이 프롬프트로

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
        props.put("summary", str);   // 줄바꿈(\n) 포함 단일 문자열. joinSummary가 뭉친 불릿 보정.
        props.put("coupons", coupons);
        props.put("startAt", str);
        props.put("endAt", str);
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
