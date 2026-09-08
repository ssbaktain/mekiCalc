package mekicalc.web.boot;

import static mekicalc.web.boot.Req.asObj;
import static mekicalc.web.boot.Req.bool;
import static mekicalc.web.boot.Req.intOrNull;
import static mekicalc.web.boot.Req.mapList;
import static mekicalc.web.boot.Req.num;
import static mekicalc.web.boot.Req.str;
import static mekicalc.web.boot.Req.strOpt;
import static mekicalc.web.boot.Req.toDoubleMap;
import static mekicalc.web.boot.Req.toEquipList;
import static mekicalc.web.boot.Req.toIntSet;
import static mekicalc.web.boot.Req.toStrSet;

import java.util.Map;
import mekicalc.config.RangeIO;
import mekicalc.web.CalcService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계산 API 컨트롤러(MVC의 Controller). 요청을 파싱해 기존 {@link CalcService}(Service)에 위임하고
 * 결과 Map/List를 반환한다. 직렬화는 {@link JsonHttpMessageConverter}가 구 서버와 동일하게 처리.
 * 구 {@code WebServer}의 /api/* 계산 라우트와 1:1 대응.
 */
@RestController
public class ApiController {

    private final CalcService service;

    public ApiController(CalcService service) {
        this.service = service;
    }

    /** stats 파싱 + 접속 통계 직업분포용으로 stats.JOB 기록(계산 요청마다). statsOf(req) 대체. */
    private static Map<String, Double> statsOf(Map<String, Object> req) {
        Object st = req.get("stats");
        if (st instanceof Map) { Object j = ((Map<?, ?>) st).get("JOB"); if (j instanceof Number) AccessLogInterceptor.recordJob(((Number) j).intValue()); }
        return toDoubleMap(req.get("stats"));
    }

    /** 탭 진입 카운트(접속 통계 '기능 사용량'). 프론트 sendBeacon(POST, 본문 없음) → 경로만 access.jsonl에 남기고 204.
     *  실제 집계는 {@link mekicalc.web.StatsStore}가 /api/view/{panel} 경로 수로 한다(전 탭 배경 /api/damage 오염 배제,
     *  '데미지 분석(구)'·'빌드'로 실제 진입한 횟수를 각각 정확히 카운트). */
    @PostMapping("/api/view/{panel}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void view(@PathVariable String panel) { /* no-op: 로깅은 AccessLogInterceptor가 처리 */ }

    @GetMapping("/api/stats")
    public Object stats() {
        return service.statDefs();
    }

    @GetMapping("/api/meta")
    public Object meta() {
        return service.meta();
    }

    @GetMapping("/api/mydata")
    public Object myData() {
        return service.myData();
    }

    @GetMapping("/api/gear-options")
    public Object gearOptions() {
        return service.gearOptions();
    }

    @PostMapping("/api/qualitative")
    public Object qualitative(@RequestBody Map<String, Object> req) {
        AccessLogInterceptor.recordJob((int) num(req, "job", 0));
        return service.qualitativeMarkers((int) num(req, "job", 0), toDoubleMap(req.get("conversions")));
    }

    @PostMapping("/api/damage")
    public Object damage(@RequestBody Map<String, Object> req) {
        Object cid = req.get("companionId"), gr = req.get("grade");
        return service.previewDamage(statsOf(req), toDoubleMap(req.get("conversions")),
                toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                cid == null ? null : cid.toString(), gr == null ? null : gr.toString(),
                (int) num(req, "level", 0), num(req, "ratio", 0), toEquipList(req.get("artifacts")));
    }

    @PostMapping("/api/buffs")
    public Object buffs(@RequestBody Map<String, Object> req) {
        return service.buffList(statsOf(req));
    }

    /** 큐브 계산 공통 유물·컨텐츠 설정(신엔진 타임라인 조립용) — 타임라인 payload와 동일 필드. */
    private CalcService.CubeCtx cubeCtx(Map<String, Object> req) {
        return new CalcService.CubeCtx(mapList(req.get("artifacts")), mapList(req.get("potentials")),
                intOrNull(req, "enemies"), req.get("kills") instanceof Number ? num(req, "kills", 0) : null,
                strOpt(req, "content"), (int) Math.round(num(req, "artOffsetSec", 0) * 1000),
                bool(req, "preActivateProc", false), bool(req, "autoFillBasic", true), toIntSet(req.get("skillsOff")),
                bool(req, "fillSkills", false), bool(req, "optimize", false), (int) num(req, "simMs", 0),
                strOpt(req, "tlCompanion"), (int) num(req, "tlCompanionLevel", 1), (int) num(req, "tlFirstSummonMs", 0), bool(req, "tlCompanionInstantFinalDmg", false));   // 빌드 동료(신엔진) → 큐브 딜 반영(구 ally 필드와 충돌 방지 위해 tl* 접두)
    }

    @PostMapping("/api/evaluate")
    public Object evaluate(@RequestBody Map<String, Object> req) {
        return service.evaluate(
                str(req, "part"), str(req, "grade"), intOrNull(req, "slot"),
                statsOf(req), toDoubleMap(req.get("conversions")), str(req, "chapter"), str(req, "target"),
                toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"), (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0),
                cubeCtx(req));
    }

    @PostMapping("/api/recommend")
    public Object recommend(@RequestBody Map<String, Object> req) {
        return service.recommend(
                statsOf(req), toDoubleMap(req.get("conversions")), str(req, "chapter"), str(req, "target"),
                req.get("cubeState"), RangeIO.parseContentMap(req.get("ranges")),
                (int) num(req, "sliderValue", 0), (int) num(req, "sortKey", 0),
                bool(req, "upper", true), bool(req, "lower", true), toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"), (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0),
                cubeCtx(req));
    }

    @PostMapping("/api/compare")
    public Object compare(@RequestBody Map<String, Object> req) {
        return service.myCompare(
                statsOf(req), toDoubleMap(req.get("conversions")), str(req, "chapter"), str(req, "target"),
                req.get("cubeState"), RangeIO.parseContentMap(req.get("ranges")),
                str(req, "part"), bool(req, "upper", true), str(req, "rightGrade"), mapList(req.get("rightOpts")),
                toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"), (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0),
                cubeCtx(req));
    }

    @PostMapping("/api/slotdraws")
    public Object slotDraws(@RequestBody Map<String, Object> req) {
        return service.slotDraws(str(req, "part"), str(req, "grade"));
    }

    @PostMapping("/api/applystats")
    public Object applyStats(@RequestBody Map<String, Object> req) {
        return service.applyStats(statsOf(req), toDoubleMap(req.get("conversions")),
                mapList(req.get("oldOpts")), mapList(req.get("newOpts")));
    }

    @PostMapping("/api/optcompare")
    public Object optCompare(@RequestBody Map<String, Object> req) {
        Map<String, Object> l = asObj(req.get("left")), r = asObj(req.get("right"));
        return service.optCompare(
                str(l, "part"), str(l, "grade"), mapList(l.get("opts")),
                str(r, "part"), str(r, "grade"), mapList(r.get("opts")),
                statsOf(req), toDoubleMap(req.get("conversions")), str(req, "chapter"), str(req, "target"),
                toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"), (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0),
                cubeCtx(req));
    }

    @PostMapping("/api/stat-compare")
    public Object statCompare(@RequestBody Map<String, Object> req) {
        return service.statCompare(
                statsOf(req), toDoubleMap(req.get("conversions")), str(req, "chapter"), str(req, "target"),
                toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"), (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0),
                str(req, "refStatId"), num(req, "refValue", 0), cubeCtx(req));
    }

    @PostMapping("/api/gear-compare")
    public Object gearCompare(@RequestBody Map<String, Object> req) {
        return service.gearCompare(
                statsOf(req), toDoubleMap(req.get("conversions")), str(req, "chapter"), str(req, "target"),
                mapList(req.get("leftOpts")), mapList(req.get("rightOpts")),
                toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"), (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0),
                cubeCtx(req));
    }

    @PostMapping("/api/gear-ocr")
    public Object gearOcr(@RequestBody Map<String, Object> req) throws Exception {
        return service.gearOcr(str(req, "image"), strOpt(req, "mimeType"), str(req, "part"));
    }

    /** 스탯 정보창 OCR: 누적 스크린샷(images[] 또는 image) → 캐릭터 프리셋 스탯 자동 채움. */
    @PostMapping("/api/stat-ocr")
    public Object statOcr(@RequestBody Map<String, Object> req) throws Exception {
        java.util.List<String> images = new java.util.ArrayList<>();
        Object imgs = req.get("images");
        if (imgs instanceof java.util.List) for (Object o : (java.util.List<?>) imgs) if (o != null) images.add(String.valueOf(o));
        if (req.get("image") != null) images.add(str(req, "image"));
        return service.statOcr(images, strOpt(req, "mimeType"), (int) num(req, "level", 0), bool(req, "nightlord", false));
    }

    /** 스탯 OCR — 이미지 1장 파싱(프론트가 장별 호출해 진행도 표시). */
    @PostMapping("/api/stat-ocr-parse")
    public Object statOcrParse(@RequestBody Map<String, Object> req) throws Exception {
        return service.statOcrParse(str(req, "image"), strOpt(req, "mimeType"));
    }

    /** 스탯 OCR — 파싱 결과들 → 계산·역산. */
    @SuppressWarnings("unchecked")
    @PostMapping("/api/stat-ocr-resolve")
    public Object statOcrResolve(@RequestBody Map<String, Object> req) throws Exception {
        java.util.List<Map<String, Object>> parsed = new java.util.ArrayList<>();
        Object p = req.get("parsed");
        if (p instanceof java.util.List) for (Object o : (java.util.List<?>) p) if (o instanceof Map) parsed.add((Map<String, Object>) o);
        return service.statOcrResolve(parsed, (int) num(req, "level", 0), bool(req, "nightlord", false));
    }

    @PostMapping("/api/target-damage")
    public Object targetDamage(@RequestBody Map<String, Object> req) {
        return service.targetDamage(str(req, "chapter"), str(req, "target"), num(req, "pen", 0),
                statsOf(req), toDoubleMap(req.get("conversions")),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"),
                (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0));
    }

    @GetMapping("/api/nl-skills")
    public Object nlSkills() {
        return service.nlSkills();
    }

    @PostMapping("/api/nl-timeline")
    public Object nlTimeline(@RequestBody Map<String, Object> req) {
        return service.nlTimeline(statsOf(req), str(req, "mode"),
                mapList(req.get("casts")), str(req, "chapter"), str(req, "target"),
                (int) num(req, "simMs", 0), mapList(req.get("artifacts")), mapList(req.get("potentials")),
                intOrNull(req, "enemies"), req.get("kills") instanceof Number ? num(req, "kills", 0) : null,
                strOpt(req, "content"), (int) Math.round(num(req, "artOffsetSec", 0) * 1000),
                bool(req, "preActivateProc", false), bool(req, "autoFillBasic", true), toIntSet(req.get("skillsOff")),
                bool(req, "fillSkills", false), bool(req, "optimize", false),
                strOpt(req, "companion"), (int) num(req, "companionLevel", 1), (int) num(req, "firstSummonMs", 0),
                bool(req, "companionInstantFinalDmg", false));
    }

    @GetMapping("/api/supporters")
    public Object supporters() {
        return service.supporters();
    }

    @PostMapping("/api/ally-ratio")
    public Object allyRatio(@RequestBody Map<String, Object> req) {
        return service.recordAllyRatio(req);
    }

    @PostMapping("/api/companion-dps")
    public Object companionDps(@RequestBody Map<String, Object> req) {
        return service.companionPreview(statsOf(req), toDoubleMap(req.get("conversions")),
                str(req, "chapter"), str(req, "target"), str(req, "companionId"), str(req, "grade"),
                (int) num(req, "level", 1), num(req, "ratio", 0), toStrSet(req.get("buffs")));
    }
}
