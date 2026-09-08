package mekicalc.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 스탯 정보창 OCR → 캐릭터 프리셋 스탯 자동 채움.
 *
 * <p>흐름: 프론트가 누적 스크린샷(base64 여러 장)을 보내면 → 로컬 사이드카 <code>/ocr-stats</code>가
 * 장별로 {@code {type:main, rows:[...]}} 또는 {@code {type:detail, title,flat,pct,sum}}을 준다 →
 * 여기서 <b>라벨→statId 매핑</b>(data/stat_panel_map.json)·<b>주/부스탯 판별</b>·<b>공격력/주스탯 해석</b>을 한다.
 *
 * <p><b>공격력·주스탯</b>은 본문 총합(스킬·유물 포함)이 아니라 ⓘ 상세(+ / %, 스킬 제외)에서 받는다.
 * 단 게임 특성상 ⓘ 상세는 <b>'챕터 사냥' 프리셋 장비에 고정</b>이라, 대상 프리셋 장비가 다르면 어긋난다.
 * 이를 <b>검증 게이트</b>로 감지: {@code ⓘ깡공 × (1+무기%) × (1+선반영%) ≈ 본문 공격력}이면 장비 같음(ⓘ 직접),
 * 아니면 <b>역산</b>: {@code 깡공 = 본문공격력 ÷ (1+무기%) ÷ (1+선반영%)}, 주스탯은 {@code 깡주 = 본문주스탯 ÷ (1+주스탯%)}(추정).
 * 선반영%(상시 스킬 공격력%)는 유물을 뺀 상태(경고 게이트 강제)면 나이트로드=숙련된 표창술 하나 → 결정론 계산.
 */
public final class StatOcr {

    private static final double GATE_TOL = 0.005;   // 검증 게이트 상대오차(0.5%) — 이하면 장비 동일로 봄
    private static final double WARN_CONF = 0.80;   // 신뢰도 이 미만이면 ⚠️. 실측상 80%↑는 정확 일치 → 80%로(90%는 헛경고 많음).

    private final String sidecarUrl;
    private final Map<String, String> directMap = new LinkedHashMap<>();   // 정규화 라벨 → statId
    private final Map<String, Object> detailMap;      // 공격력/주스탯 상세 규칙
    private final Map<String, Object> cooldownMap;    // 쿨감 %/초
    private final List<String> mainStatTokens;        // STR·DEX·INT·LUK
    private final String subFlatId;                   // SUB_STAT_FLAT
    private final java.util.Set<String> displayIds = new java.util.LinkedHashSet<>();  // 미환산(표시전용) id
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @SuppressWarnings("unchecked")
    public StatOcr(GameContent content, OcrConfig cfg) {
        this.sidecarUrl = cfg.localOcrUrl == null ? "" : cfg.localOcrUrl.trim();
        Path dir = content.dataDir;
        Map<String, Object> panel = readMap(dir, "stat_panel_map.json");
        Map<String, Object> direct = (Map<String, Object>) panel.getOrDefault("direct", Map.of());
        for (Map.Entry<String, Object> e : direct.entrySet())
            if (!e.getKey().startsWith("_")) directMap.put(e.getKey(), String.valueOf(e.getValue()));
        this.detailMap = (Map<String, Object>) panel.getOrDefault("detail", Map.of());
        this.cooldownMap = (Map<String, Object>) panel.getOrDefault("cooldownReduction", Map.of());
        Map<String, Object> ms = (Map<String, Object>) panel.getOrDefault("mainStat", Map.of());
        this.mainStatTokens = new ArrayList<>();
        for (Object t : Json.asList(ms.getOrDefault("tokens", List.of()))) mainStatTokens.add(String.valueOf(t));
        this.subFlatId = String.valueOf(ms.getOrDefault("subFlat", "SUB_STAT_FLAT"));
        for (Object o : readList(dir, "display_stats.json")) {
            Map<String, Object> m = Json.asMap(o);
            Object id = m.get("id");
            if (id != null) displayIds.add(String.valueOf(id));
        }
    }

    public boolean enabled() { return !sidecarUrl.isEmpty(); }

    // ─────────────────────────── 공개 진입점 ───────────────────────────

    /**
     * 누적 스크린샷을 해석해 statId→값 + 메타를 낸다.
     * @param images base64 이미지들(본문 스크롤 여러 장 + 공격력ⓘ + 주스탯ⓘ)
     * @param level  캐릭터 레벨(패널에 없어 프론트가 폼에서 전달) — 선반영 계산용
     * @param nightlord 나이트로드 여부(공격력 선반영 역산 지원)
     */
    public Map<String, Object> resolve(List<String> images, String mimeType, int level, boolean nightlord) throws Exception {
        List<Map<String, Object>> parsed = new ArrayList<>();
        for (String img : images) parsed.add(callSidecar(img, mimeType));
        return resolveParsed(parsed, level, nightlord);
    }

    /** 이미지 1장 파싱(사이드카 원문 그대로). 프론트가 장별로 호출해 진행도를 낸다. */
    public Map<String, Object> parseOne(String image, String mimeType) throws Exception {
        return callSidecar(image, mimeType);
    }

    /** 이미 파싱된 사이드카 결과들 → statId→값 + 메타. (장별 파싱과 분리 = 프론트 진행도 표시 지원) */
    public Map<String, Object> resolveParsed(List<Map<String, Object>> parsedResults, int level, boolean nightlord) {
        // 1) 장별 파싱 분류
        List<Map<String, Object>> mains = new ArrayList<>();
        Map<String, Object> attackDetail = null, mainStatDetail = null;
        for (Map<String, Object> r : parsedResults) {
            if (r == null) continue;
            if ("detail".equals(r.get("type"))) {
                String title = norm(String.valueOf(r.getOrDefault("title", "")));
                if ("공격력".equals(title)) attackDetail = r;
                else if (isMainStat(title)) mainStatDetail = r;
            } else {
                mains.add(r);
            }
        }
        // 2) 본문 행 병합(정규화 라벨 → 행). 뒤 장이 앞 장 덮음.
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        List<Map<String, Object>> mainStatRows = new ArrayList<>();
        for (Map<String, Object> main : mains) {
            for (Object ro : Json.asList(main.getOrDefault("rows", List.of()))) {
                Map<String, Object> row = Json.asMap(ro);
                String raw = String.valueOf(row.getOrDefault("label", ""));
                if (isMainStat(cleanMainTok(raw))) { mainStatRows.add(row); continue; }
                merged.put(norm(raw), row);
            }
        }

        Map<String, Object> values = new LinkedHashMap<>();   // statId → number
        Map<String, Object> meta = new LinkedHashMap<>();      // statId → {confidence, warn, estimated}
        List<String> notes = new ArrayList<>();

        // 3) 본문 직접 매핑(딜 %스탯 + 미환산). 쿨감은 단위로 분기.
        for (Map.Entry<String, Map<String, Object>> e : merged.entrySet()) {
            String label = e.getKey();
            Map<String, Object> row = e.getValue();
            double v = num(row.get("value"));
            boolean pct = Boolean.TRUE.equals(row.get("pct"));
            double lc = num(row.getOrDefault("labelConf", row.getOrDefault("confidence", 1.0)));
            double vc = num(row.getOrDefault("valueConf", row.getOrDefault("confidence", 1.0)));
            String id = mapLabel(label, pct);
            if (id == null) continue;
            put(values, meta, id, v, lc, vc, false);   // 라벨<80%→종류 주황, 값<80%→수치 주황
        }

        // 4) 주/부스탯: 본문 STR·DEX·INT·LUK 줄 중 값 최대=주스탯(총합, 역산용), 나머지=부스탯
        double mainStatTotal = 0, subTotal = 0, subLabelConf = 1, subValueConf = 1;
        for (Map<String, Object> row : mainStatRows) {
            double v = num(row.get("value"));
            if (v >= mainStatTotal) { subTotal = Math.max(subTotal, mainStatTotal); mainStatTotal = v; }
            else if (v > subTotal) { subTotal = v; subLabelConf = num(row.getOrDefault("labelConf", 1.0)); subValueConf = num(row.getOrDefault("valueConf", 1.0)); }
        }
        if (subTotal > 0) put(values, meta, subFlatId, subTotal, subLabelConf, subValueConf, false);

        // 5) 선반영%(상시 스킬 공격력%) — 나이트로드만(유물 제거 전제 = 숙련된 표창술 하나)
        int skill3 = (int) num(values.getOrDefault("SKILL_LEVEL_3", 0.0));
        int skillAll = (int) num(values.getOrDefault("SKILL_LEVELS", 0.0));
        double reflectPct = nightlord ? nightlordAtkReflectPct(level, skill3, skillAll) : 0.0;

        // 6) 공격력 해석(ⓘ 상세 + 검증 게이트/역산)
        boolean gearMatch = true;
        if (attackDetail != null) {
            double flatI = num(attackDetail.get("flat"));       // ⓘ 깡공(챕터사냥)
            double pctI = num(attackDetail.get("pct"));          // ⓘ 무기%(불변)
            double conf = num(attackDetail.getOrDefault("confidence", 1.0));
            double mainAtk = merged.containsKey("공격력") ? num(merged.get("공격력").get("value")) : 0;
            double wf = 1 + pctI / 100.0, rf = 1 + reflectPct / 100.0;
            if (nightlord && mainAtk > 0 && flatI > 0) {   // 선반영 계산 가능한 나이트로드만 게이트/역산
                double expect = flatI * wf * rf;
                gearMatch = Math.abs(expect - mainAtk) / mainAtk <= GATE_TOL;
                if (gearMatch) {
                    put(values, meta, "ATK_FLAT", flatI, 1.0, conf, false);       // 종류=ⓘ 확정(1.0), 값=신뢰도
                } else {
                    double flat = mainAtk / wf / rf;             // 역산(대상 장비 깡공)
                    put(values, meta, "ATK_FLAT", Math.round(flat), 1.0, conf, false);
                    notes.add("장비가 챕터 사냥과 달라 공격력을 역산했습니다.");
                }
                put(values, meta, "ATK_PCT", pctI, 1.0, conf, false);   // 무기%는 불변
            } else if (flatI > 0) {
                put(values, meta, "ATK_FLAT", flatI, 1.0, conf, false);
                put(values, meta, "ATK_PCT", pctI, 1.0, conf, false);
            }
        }

        // 7) 주스탯 해석(ⓘ + 게이트/앵커 추정)
        if (mainStatDetail != null) {
            double flatI = num(mainStatDetail.get("flat"));
            double pctI = num(mainStatDetail.get("pct"));
            double conf = num(mainStatDetail.getOrDefault("confidence", 1.0));
            if (gearMatch || mainStatTotal <= 0) {
                put(values, meta, "MAIN_STAT_FLAT", flatI, 1.0, conf, false);
                put(values, meta, "MAIN_STAT_PCT", pctI, 1.0, conf, false);
            } else {
                double flat = mainStatTotal / (1 + pctI / 100.0);   // 주스탯% 앵커로 역산(추정)
                put(values, meta, "MAIN_STAT_FLAT", Math.round(flat), 1.0, conf, true);   // estimated → 값 주황
                put(values, meta, "MAIN_STAT_PCT", pctI, 1.0, conf, false);
                notes.add("장비가 챕터 사냥과 달라 주스탯은 추정값입니다(부옵션 주스탯% 차이로 오차 가능).");
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("values", values);
        out.put("meta", meta);
        out.put("gearMatch", gearMatch);
        out.put("reflectPct", reflectPct);
        out.put("notes", notes);
        out.put("displayIds", new ArrayList<>(displayIds));
        return out;
    }

    // ─────────────────────────── 매핑 헬퍼 ───────────────────────────

    /** 정규화 라벨(+값의 %여부) → statId. 쿨감은 단위 분기. 미매칭=null. */
    private String mapLabel(String label, boolean pct) {
        Object cd = cooldownMap.get("label");
        if (cd != null && label.equals(norm(String.valueOf(cd))))
            return String.valueOf(cooldownMap.get(pct ? "pct" : "sec"));
        return directMap.get(label);
    }

    private boolean isMainStat(String s) {
        String u = cleanMainTok(s);
        for (String t : mainStatTokens) if (u.startsWith(t)) return true;
        return false;
    }

    /** 라벨 정규화: 공백 제거 + 꼬리 ⓘ(i·①) 제거 + 흔한 오독(탯↔탠) 보정. */
    private static String norm(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s", "");
        t = t.replaceAll("[iⓘ①ⅰ()]+$", "");
        t = t.replace("스탠", "스탯");
        return t;
    }

    /** 주스탯 토큰 정제: 영문 대문자만 남김(ⓘ가 'LUKi' 등으로 붙어도). */
    private static String cleanMainTok(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z]", "").toUpperCase();
    }

    /** 나이트로드 상시 공격력% = 숙련된 표창술(+18%, growthSlot22=0.3%/lv, 3차). eff=Preset.effectiveSkillLevel(3) 복제.
     *  ★유물 제거 전제(경고 게이트 강제). 검증: lv117·3차58·모든38 → 29.66%(실측 29.59% 일치). */
    static double nightlordAtkReflectPct(int level, int skill3, int skillAll) {
        int eff3 = Math.max(0, 3 * (Math.min(level, 100) - 60)) + skill3 + skillAll;   // Preset.effectiveSkillLevel(3)
        return Math.floor(18.0 * (1 + 0.003 * eff3) * 10) / 10.0;   // 게임 표기규칙(소수 1자리 버림)이 실제 적용값
    }

    /** meta 기록: 라벨·값 신뢰도를 따로 담아 프론트가 스탯종류/수치를 각각 주황 표시하게 한다. */
    private static void put(Map<String, Object> values, Map<String, Object> meta, String id, double value,
                            double labelConf, double valueConf, boolean estimated) {
        values.put(id, value);
        boolean labelWarn = labelConf < WARN_CONF;
        boolean valueWarn = valueConf < WARN_CONF || estimated;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labelConf", Math.round(labelConf * 1000) / 1000.0);
        m.put("valueConf", Math.round(valueConf * 1000) / 1000.0);
        m.put("confidence", Math.round(Math.min(labelConf, valueConf) * 1000) / 1000.0);
        if (labelWarn) m.put("labelWarn", true);
        if (valueWarn) m.put("valueWarn", true);
        if (estimated) m.put("estimated", true);
        if (labelWarn || valueWarn) m.put("warn", true);   // 전체(하단 박스 판정용)
        meta.put(id, m);
    }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    // ─────────────────────────── 사이드카 호출 ───────────────────────────

    private Map<String, Object> callSidecar(String base64, String mimeType) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("image", base64);
        body.put("mimeType", (mimeType == null || mimeType.isEmpty()) ? "image/png" : mimeType);
        HttpRequest req = HttpRequest.newBuilder(URI.create(sidecarUrl + "/ocr-stats"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new java.io.IOException("스탯 OCR 오류(" + resp.statusCode() + ")");
        Object parsed = Json.parse(resp.body());
        if (!(parsed instanceof Map)) throw new java.io.IOException("스탯 OCR 결과 형식 오류");
        return Json.asMap(parsed);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(Path dir, String name) {
        try {
            Path f = dir.resolve(name);
            if (Files.exists(f)) return Json.asMap(Json.parse(Files.readString(f)));
        } catch (Exception e) { System.err.println(name + " 읽기 실패: " + e); }
        return new LinkedHashMap<>();
    }

    private static List<Object> readList(Path dir, String name) {
        try {
            Path f = dir.resolve(name);
            if (Files.exists(f)) return Json.asList(Json.parse(Files.readString(f)));
        } catch (Exception e) { System.err.println(name + " 읽기 실패: " + e); }
        return new ArrayList<>();
    }
}
