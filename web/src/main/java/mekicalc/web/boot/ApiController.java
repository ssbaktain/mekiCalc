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
import static mekicalc.web.boot.Req.toStrSet;

import java.util.Map;
import mekicalc.config.RangeIO;
import mekicalc.web.CalcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
        return service.qualitativeMarkers((int) num(req, "job", 0), toDoubleMap(req.get("conversions")));
    }

    @PostMapping("/api/damage")
    public Object damage(@RequestBody Map<String, Object> req) {
        Object cid = req.get("companionId"), gr = req.get("grade");
        return service.previewDamage(toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")),
                toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                cid == null ? null : cid.toString(), gr == null ? null : gr.toString(),
                (int) num(req, "level", 0), num(req, "ratio", 0), toEquipList(req.get("artifacts")));
    }

    @PostMapping("/api/buffs")
    public Object buffs(@RequestBody Map<String, Object> req) {
        return service.buffList(toDoubleMap(req.get("stats")));
    }

    @PostMapping("/api/evaluate")
    public Object evaluate(@RequestBody Map<String, Object> req) {
        return service.evaluate(
                str(req, "part"), str(req, "grade"), intOrNull(req, "slot"),
                toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), str(req, "chapter"), str(req, "target"),
                toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"), (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0));
    }

    @PostMapping("/api/recommend")
    public Object recommend(@RequestBody Map<String, Object> req) {
        return service.recommend(
                toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), str(req, "chapter"), str(req, "target"),
                req.get("cubeState"), RangeIO.parseContentMap(req.get("ranges")),
                (int) num(req, "sliderValue", 0), (int) num(req, "sortKey", 0),
                bool(req, "upper", true), bool(req, "lower", true), toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"), (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0));
    }

    @PostMapping("/api/compare")
    public Object compare(@RequestBody Map<String, Object> req) {
        return service.myCompare(
                toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), str(req, "chapter"), str(req, "target"),
                req.get("cubeState"), RangeIO.parseContentMap(req.get("ranges")),
                str(req, "part"), bool(req, "upper", true), str(req, "rightGrade"), mapList(req.get("rightOpts")),
                toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"), (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0));
    }

    @PostMapping("/api/slotdraws")
    public Object slotDraws(@RequestBody Map<String, Object> req) {
        return service.slotDraws(str(req, "part"), str(req, "grade"));
    }

    @PostMapping("/api/applystats")
    public Object applyStats(@RequestBody Map<String, Object> req) {
        return service.applyStats(toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")),
                mapList(req.get("oldOpts")), mapList(req.get("newOpts")));
    }

    @PostMapping("/api/optcompare")
    public Object optCompare(@RequestBody Map<String, Object> req) {
        Map<String, Object> l = asObj(req.get("left")), r = asObj(req.get("right"));
        return service.optCompare(
                str(l, "part"), str(l, "grade"), mapList(l.get("opts")),
                str(r, "part"), str(r, "grade"), mapList(r.get("opts")),
                toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), str(req, "chapter"), str(req, "target"),
                toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"), (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0));
    }

    @PostMapping("/api/stat-compare")
    public Object statCompare(@RequestBody Map<String, Object> req) {
        return service.statCompare(
                toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), str(req, "chapter"), str(req, "target"),
                toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"), (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0),
                str(req, "refStatId"), num(req, "refValue", 0));
    }

    @PostMapping("/api/gear-compare")
    public Object gearCompare(@RequestBody Map<String, Object> req) {
        return service.gearCompare(
                toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")), str(req, "chapter"), str(req, "target"),
                mapList(req.get("leftOpts")), mapList(req.get("rightOpts")),
                toStrSet(req.get("buffs")), num(req, "hitInterval", 0),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"), (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0));
    }

    @PostMapping("/api/gear-ocr")
    public Object gearOcr(@RequestBody Map<String, Object> req) throws Exception {
        return service.gearOcr(str(req, "image"), strOpt(req, "mimeType"), str(req, "part"));
    }

    @PostMapping("/api/target-damage")
    public Object targetDamage(@RequestBody Map<String, Object> req) {
        return service.targetDamage(str(req, "chapter"), str(req, "target"), num(req, "pen", 0),
                toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")),
                strOpt(req, "companionId"), strOpt(req, "companionGrade"),
                (int) num(req, "companionLevel", 0), num(req, "companionRatio", 0));
    }

    @PostMapping("/api/ally-ratio")
    public Object allyRatio(@RequestBody Map<String, Object> req) {
        return service.recordAllyRatio(req);
    }

    @PostMapping("/api/companion-dps")
    public Object companionDps(@RequestBody Map<String, Object> req) {
        return service.companionPreview(toDoubleMap(req.get("stats")), toDoubleMap(req.get("conversions")),
                str(req, "chapter"), str(req, "target"), str(req, "companionId"), str(req, "grade"),
                (int) num(req, "level", 1), num(req, "ratio", 0), toStrSet(req.get("buffs")));
    }
}
