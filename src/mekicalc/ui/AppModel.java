package mekicalc.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.config.CharacterIO;
import mekicalc.config.CubeStateIO;
import mekicalc.config.GameData;
import mekicalc.engine.equip.CubeEvaluator;
import mekicalc.engine.equip.CubeRecommender;
import mekicalc.engine.equip.EquipmentPotential;
import mekicalc.engine.equip.PotentialDraw;
import mekicalc.model.StatBlock;
import mekicalc.model.StatDef;

/**
 * UI ↔ 엔진을 잇는 모델 계층(순수 로직, Swing 비의존 → 헤드리스 테스트 가능).
 *
 * <p>현재 스탯을 들고, 스탯 수정/저장/데미지 계산/장비 큐브 평가를 위임한다.
 */
public final class AppModel {

    /** 등급 코드 → 한글 표시. */
    public static final Map<String, String> GRADE_KO = Map.of(
            "NORMAL", "노말", "RARE", "레어", "EPIC", "에픽",
            "UNIQUE", "유니크", "LEGENDARY", "레전드리", "MYTHIC", "미스틱");

    private final Path dataDir;
    private GameData g;
    private EquipmentPotential ep;
    private StatBlock current;
    private Map<String, CubeStateIO.PartCube> cubeState;

    public AppModel(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        reload();
    }

    /** 데이터 파일들을 다시 읽어온다. */
    public void reload() throws IOException {
        g = GameData.load(dataDir);
        ep = EquipmentPotential.load(dataDir);
        current = CharacterIO.load(dataDir);
        cubeState = CubeStateIO.load(dataDir);
    }

    /** 부위의 저장된 내 큐브 옵션(없으면 null). */
    public CubeStateIO.PartCube cubeOf(String part) { return cubeState.get(part); }

    /** 한 부위의 내 큐브 옵션(윗잠+아랫잠)을 저장. */
    public void saveCube(String part, CubeStateIO.PartCube pc) throws IOException {
        cubeState.put(part, pc);
        CubeStateIO.save(dataDir, cubeState);
    }

    public Collection<StatDef> stats() { return g.stats.values(); }
    public String statName(String id) { StatDef d = g.stats.get(id); return d != null ? d.name : id; }

    /** 표시 단위가 %인 스탯인지(드롭다운/표의 수치 뒤 % 표기용). */
    public boolean isPercent(String id) {
        StatDef d = g.stats.get(id);
        return d != null && "PCT".equals(d.unit);
    }

    public double value(String id) { return current.get(id); }
    public void setValue(String id, double v) { current.set(id, v); }

    public double currentDamage() { return g.model.finalDamage(current); }
    /** 일반 몬스터 대상 데미지(예상 데미지 기준). */
    public double currentDamageNormal() { return g.model.finalDamageNormal(current); }
    /** 보스 몬스터 대상 데미지. */
    public double currentDamageBoss() { return g.model.finalDamageBoss(current); }

    /** 현재 스탯을 character.json으로 저장. */
    public void save() throws IOException { CharacterIO.save(dataDir, current, g.stats.keySet()); }

    /** 유저가 한 번이라도 스탯을 직접 저장했는지(첫 실행이면 false). */
    public boolean isUserEntered() { return CharacterIO.isUserEntered(dataDir); }

    /** 부위·등급의 슬롯별(0=1슬롯…) 옵션 후보 목록. 1슬롯은 노란만 들어있다. */
    public List<List<PotentialDraw>> slotDraws(String part, String grade) {
        return ep.slots(part, grade);
    }

    /**
     * 선택한 옵션 줄들을 현재 스탯에 (각 줄을 순차로 combine 규칙대로) 적용했을 때의 딜 증가율.
     * null은 무시(빈 슬롯). 같은 스탯이 여러 줄이면 최종뎀은 곱연산, 일반은 합연산으로 누적된다.
     */
    public double increaseForDraws(List<PotentialDraw> draws) {
        // 한 장비 내 큐브 줄들은 같은 스탯끼리 먼저 '합연산'으로 모은다.
        // (예: 최종뎀 12% + 12% → 24%) 그 뒤 합산 델타를 현재 스탯에
        // 스탯별 combine 규칙으로 한 번에 적용한다(최종뎀이면 24%가 곱연산으로 들어감).
        StatBlock delta = new StatBlock();
        for (PotentialDraw d : draws) {
            if (d == null) continue;
            delta.set(d.statId(), delta.get(d.statId()) + d.value());
        }
        StatBlock after = g.model.combine(current, delta);
        // 공속 DPS 배수(1+공속/100)는 효율/비교에만 반영(1타 데미지엔 미포함).
        double base = g.model.finalDamage(current) * g.model.attackSpeedFactor(current);
        double aft = g.model.finalDamage(after) * g.model.attackSpeedFactor(after);
        return base <= 0 ? 0 : aft / base - 1.0;
    }

    /** 선택한 옵션 중 데미지로 환산 못 하는 정성적 스탯(공속/방관)이 하나라도 있는지. */
    public boolean hasQualitative(List<PotentialDraw> draws) {
        StatBlock b = new StatBlock();
        for (PotentialDraw d : draws) if (d != null) b.set(d.statId(), d.value());
        return g.model.hasQualitative(b);
    }

    /**
     * 선택한 슬롯 옵션들이 한 큐브 1회에서 그대로(각 슬롯에 해당 옵션이) 나올 확률.
     * 빈 슬롯(null)은 무시한다. 아무 옵션도 고르지 않았으면 0.
     */
    public double probForDraws(List<PotentialDraw> draws) {
        double p = 1.0;
        boolean any = false;
        for (PotentialDraw d : draws) {
            if (d == null) continue;
            any = true;
            p *= d.prob() / 100.0;
        }
        return any ? p : 0.0;
    }

    /** 선택한 옵션들의 동일 옵션 등장 확률 총합(슬롯 배치만 다른 같은 구성 전부 합산). */
    public double probSumForDraws(String part, String grade, List<PotentialDraw> draws) {
        List<PotentialDraw> opts = new ArrayList<>();
        for (PotentialDraw d : draws) if (d != null) opts.add(d);
        if (opts.isEmpty()) return 0.0;
        return CubeRecommender.arrangeSum(opts, ep.slots(part, grade));
    }

    /** 선택한 배치 그대로의 단일 등장 확률(선택 슬롯=옵션 확률, 미선택 슬롯=빈 확률). */
    public double probSingleForDraws(String part, String grade, List<PotentialDraw> draws) {
        List<List<PotentialDraw>> sl = ep.slots(part, grade);
        double p = 1.0;
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            PotentialDraw d = (i < draws.size()) ? draws.get(i) : null;
            if (d != null) { p *= d.prob() / 100.0; any = true; }
            else p *= CubeRecommender.emptyProb(sl.get(i));
        }
        return any ? p : 0.0;
    }

    public List<String> grades() { return ep.grades(); }

    public List<String> parts() { return new ArrayList<>(ep.parts()); }

    /** 부위·등급의 잠재 옵션 평가 결과(딜 증가량 내림차순, 정성적은 뒤로). */
    public List<CubeEvaluator.Result> evaluate(String part, String grade) {
        List<CubeEvaluator.Result> res = CubeEvaluator.evaluate(ep, g.model, current, part, grade);
        res.sort((a, b) -> Double.compare(rank(b), rank(a)));
        return res;
    }

    private static double rank(CubeEvaluator.Result r) {
        return Double.isNaN(r.damageIncrease()) ? -1 : r.damageIncrease();
    }

    // ──────────────────── 큐브 우선순위 추천 ────────────────────

    private List<CubeRecommender.EquipOpts> recCache;
    private int maxDivisions = 1;

    /**
     * 장비별(윗/아랫 별개) 딜증가 옵션을 분석해 캐시. 슬라이더 분기 수 = 표시(비숨김) 옵션 수 최대값.
     * 화면 진입/큐브 수정 시 호출.
     */
    public void buildRecommendations() {
        recCache = new ArrayList<>();
        double upPrice = g.currencyValues.getOrDefault("미라클큐브", 1.0);
        double loPrice = g.currencyValues.getOrDefault("에디셔널큐브", 2.0);
        for (Map.Entry<String, CubeStateIO.PartCube> e : cubeState.entrySet()) {
            CubeStateIO.PartCube pc = e.getValue();
            if (pc.upper() != null) addLayer(e.getKey(), pc.upper(), true, upPrice);
            if (pc.lower() != null) addLayer(e.getKey(), pc.lower(), false, loPrice);
        }
        maxDivisions = 1;
        for (CubeRecommender.EquipOpts eo : recCache) maxDivisions = Math.max(maxDivisions, eo.shown().size());
    }

    private void addLayer(String part, CubeStateIO.Line line, boolean upper, double price) {
        if (line.grade() == null) return;
        List<List<PotentialDraw>> slots = ep.slots(part, line.grade());
        PotentialDraw[] curDraws = currentDraws(line, slots);
        CubeRecommender.EquipOpts eo = CubeRecommender.analyze(g.model, current, slots, curDraws, part, upper, price);
        if (!eo.shown().isEmpty()) recCache.add(eo);
    }

    /** 슬라이더 분기 수(표시 옵션 수 최대). */
    public int maxDivisions() {
        if (recCache == null) buildRecommendations();
        return maxDivisions;
    }

    /** mycube 한 줄의 3슬롯을 PotentialDraw[]로(확률은 잠재 테이블에서 매칭). 빈 슬롯은 null. */
    private PotentialDraw[] currentDraws(CubeStateIO.Line line, List<List<PotentialDraw>> slots) {
        PotentialDraw[] cur = new PotentialDraw[3];
        CubeStateIO.Slot[] ss = line.slots();
        for (int i = 0; i < 3; i++) {
            CubeStateIO.Slot s = (ss != null && i < ss.length) ? ss[i] : null;
            if (s == null) continue;
            PotentialDraw match = null;
            for (PotentialDraw d : slots.get(i))
                if (d.statId().equals(s.stat()) && d.value() == s.value()) { match = d; break; }
            cur[i] = (match != null) ? match : new PotentialDraw(s.stat(), s.value(), 0, false, false);
        }
        return cur;
    }

    /**
     * 슬라이더 값(0~분기수-1)·정렬키·층 필터로 추천을 반환. 정렬키: 0=전체확률합 1=남은확률합
     * 2=딜증가량 3=효율(내림차순). 아랫잠은 정렬에서만 ÷2(효율은 이미 /cost 반영).
     */
    public List<CubeRecommender.Rec> recommend(int sliderValue, int sortKey, boolean upper, boolean lower) {
        if (recCache == null) buildRecommendations();
        double f = maxDivisions > 1 ? (double) sliderValue / (maxDivisions - 1) : 0;
        List<CubeRecommender.Rec> out = new ArrayList<>();
        for (CubeRecommender.EquipOpts eo : recCache) {
            if (eo.upper() && !upper) continue;
            if (!eo.upper() && !lower) continue;
            CubeRecommender.Rec r = CubeRecommender.pick(eo, f);
            if (r != null) out.add(r);
        }
        out.sort((a, b) -> Double.compare(sortValue(b, sortKey), sortValue(a, sortKey)));
        return out;
    }

    private static double sortValue(CubeRecommender.Rec r, int key) {
        switch (key) {
            case 0: return r.totalProb() / r.cost();     // 전체 딜증가 확률합 (아랫잠 ÷2)
            case 2: return r.dealDiff() / r.cost();       // 딜 증가량 (아랫잠 ÷2)
            case 3: return r.gainEff();                   // 효율 (최종 효율=딜차이×확률, 이미 /cost)
            default: return r.remainProb() / r.cost();    // 1: 남은 딜증가 확률합 (아랫잠 ÷2)
        }
    }

    /** 등급 코드 목록을 한글 라벨로. */
    public List<String> gradeLabels() {
        List<String> out = new ArrayList<>();
        for (String code : grades()) out.add(GRADE_KO.getOrDefault(code, code));
        return out;
    }

    /** 한글 등급 라벨 → 코드 역매핑. */
    public Map<String, String> gradeLabelToCode() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String code : grades()) m.put(GRADE_KO.getOrDefault(code, code), code);
        return m;
    }
}
