package mekicalc.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.config.CharacterIO;
import mekicalc.config.ConversionIO;
import mekicalc.config.CubePresetIO;
import mekicalc.config.CubeStateIO;
import mekicalc.config.GameData;
import mekicalc.config.PresetIO;
import mekicalc.config.RangeIO;
import mekicalc.config.UISettingsIO;
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
    private Map<String, CubeStateIO.PartCube> cubeState;   // 활성 큐브 프리셋의 편집용 사본

    // 스탯 프리셋: 목록(가변) + 활성 프리셋 이름. current는 활성 프리셋의 편집용 사본.
    private List<PresetIO.Preset> presets;
    private String activeName;

    // 큐브 프리셋(스탯과 별개의 독립 프리셋).
    private List<CubePresetIO.CubePreset> cubePresets;
    private String cubeActiveName;

    // 정성적·스킬레벨 커스텀 딜 환산(전체 공용, conversions.json).
    private Map<String, ConversionIO.Conv> conversions;
    // 추천 옵션 수치 범위는 큐브 프리셋마다 따로 보관(cube_presets.json) → 별도 필드 없음.
    // 화면 설정(폰트 배율·창 크기, ui.json).
    private UISettingsIO.UISettings ui;

    public AppModel(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        reload();
    }

    /** 데이터 파일들을 다시 읽어온다. */
    public void reload() throws IOException {
        g = GameData.load(dataDir);
        ep = EquipmentPotential.load(dataDir);
        PresetIO.Store store = PresetIO.load(dataDir);
        presets = new ArrayList<>(store.presets());
        activeName = store.active();
        current = new StatBlock(activeStats());
        CubePresetIO.Store cubeStore = CubePresetIO.load(dataDir);
        cubePresets = new ArrayList<>(cubeStore.presets());
        cubeActiveName = cubeStore.active();
        cubeState = new LinkedHashMap<>(activeCubes());
        conversions = ConversionIO.load(dataDir);
        ui = UISettingsIO.load(dataDir);
    }

    // ──────────────────── 스탯 프리셋 ────────────────────

    /** 활성 프리셋의 (저장된) 스탯. */
    private StatBlock activeStats() {
        int i = indexOf(activeName);
        return presets.get(i >= 0 ? i : 0).stats();
    }

    private int indexOf(String name) {
        for (int i = 0; i < presets.size(); i++) if (presets.get(i).name().equals(name)) return i;
        return -1;
    }

    /** 프리셋 이름 목록(저장 순서). */
    public List<String> presetNames() {
        List<String> out = new ArrayList<>();
        for (PresetIO.Preset p : presets) out.add(p.name());
        return out;
    }

    /** 현재 활성 프리셋 이름. */
    public String activePreset() { return activeName; }

    /** 해당 이름의 프리셋이 있는지. */
    public boolean hasPreset(String name) { return indexOf(name) >= 0; }

    /** presets.json + character.json(활성 미러)을 기록. */
    private void writePresets() throws IOException {
        PresetIO.save(dataDir, new PresetIO.Store(presets, activeName), g.stats.keySet());
        // CLI/GameData가 읽는 character.json은 활성 프리셋을 그대로 미러링한다.
        CharacterIO.save(dataDir, current, g.stats.keySet());
    }

    /** 활성 프리셋을 name으로 전환(미저장 편집은 버림). 필드는 호출측에서 다시 채운다. */
    public void switchPreset(String name) throws IOException {
        if (indexOf(name) < 0 || name.equals(activeName)) return;
        activeName = name;
        current = new StatBlock(activeStats());
        writePresets();
    }

    /** 현재 화면 스탯(current)을 사본으로 하는 새 프리셋을 만들고 활성으로 전환. */
    public void addPreset(String name) throws IOException {
        if (name == null || name.isEmpty() || hasPreset(name)) return;
        presets.add(new PresetIO.Preset(name, new StatBlock(current)));
        activeName = name;
        current = new StatBlock(current);
        writePresets();
    }

    /** 기존 프리셋(srcName)의 저장된 스탯을 복사한 새 프리셋을 만들고 활성으로 전환. */
    public void addPresetFrom(String name, String srcName) throws IOException {
        int i = indexOf(srcName);
        if (name == null || name.isEmpty() || hasPreset(name) || i < 0) return;
        presets.add(new PresetIO.Preset(name, new StatBlock(presets.get(i).stats())));
        activeName = name;
        current = new StatBlock(activeStats());
        writePresets();
    }

    /** 빈 값(모두 0) 새 프리셋을 만들고 활성으로 전환. */
    public void addPresetBlank(String name) throws IOException {
        if (name == null || name.isEmpty() || hasPreset(name)) return;
        presets.add(new PresetIO.Preset(name, new StatBlock()));
        activeName = name;
        current = new StatBlock();
        writePresets();
    }

    /** 프리셋 이름 변경(스탯 보존). 활성이면 활성 이름도 갱신. */
    public void renamePreset(String oldName, String newName) throws IOException {
        int i = indexOf(oldName);
        if (i < 0 || newName == null || newName.isEmpty() || hasPreset(newName)) return;
        presets.set(i, new PresetIO.Preset(newName, presets.get(i).stats()));
        if (activeName.equals(oldName)) activeName = newName;
        writePresets();
    }

    /** 프리셋 삭제. 마지막 하나는 삭제 불가. 활성을 지우면 첫 프리셋으로 전환. */
    public void deletePreset(String name) throws IOException {
        if (presets.size() <= 1 || indexOf(name) < 0) return;
        presets.removeIf(p -> p.name().equals(name));
        if (activeName.equals(name)) {
            activeName = presets.get(0).name();
            current = new StatBlock(activeStats());
        }
        writePresets();
    }

    // ──────────────────── 정성적·스킬레벨 커스텀 딜 환산 ────────────────────

    /** 커스텀 환산을 설정할 수 있는 스탯들(정성적 스탯 + 1~4차/모든 스킬 레벨), stats.json 순서. */
    public List<StatDef> convertibleStats() {
        List<StatDef> out = new ArrayList<>();
        for (StatDef d : g.stats.values()) {
            if (d.qualitative || ConversionIO.SKILL_LEVEL_IDS.contains(d.id)) out.add(d);
        }
        return out;
    }

    /** 스탯의 현재 환산 설정(없으면 디폴트: 사용 안 함). */
    public ConversionIO.Conv conversionOf(String id) {
        return conversions.getOrDefault(id, ConversionIO.Conv.DEFAULT);
    }

    /** 스탯의 표시 단위(LEVEL/SEC/PCT/FLAT 등). 환산 입력칸의 접미사에 쓰인다. */
    public String statUnit(String id) {
        StatDef d = g.stats.get(id);
        return d != null ? d.unit : "";
    }

    /** 환산 설정을 저장(conversions.json)하고 데미지 모델을 즉시 갱신. current(스탯)는 보존. */
    public void saveConversions(Map<String, ConversionIO.Conv> map) throws IOException {
        ConversionIO.save(dataDir, map);
        conversions = new LinkedHashMap<>(map);
        g = GameData.load(dataDir);   // 새 환산값으로 DamageModel 재조립(current는 그대로)
    }

    // ──────────────────── 추천 옵션 범위 (크확·공속·정성적) ────────────────────

    /** 범위를 설정할 수 있는 스탯들: 크확·공속 + 정성적 스탯 전부(이 순서). */
    public List<StatDef> rangeStats() {
        List<StatDef> out = new ArrayList<>();
        for (String id : RangeIO.EXTRA_IDS) {
            StatDef d = g.stats.get(id);
            if (d != null) out.add(d);
        }
        for (StatDef d : g.stats.values()) {
            if (d.qualitative && !RangeIO.EXTRA_IDS.contains(d.id)) out.add(d);
        }
        return out;
    }

    /** 활성 큐브 프리셋의 추천 옵션 수치 범위(스탯→Range). */
    private Map<String, RangeIO.Range> activeRanges() {
        int i = cubeIndexOf(cubeActiveName);
        Map<String, RangeIO.Range> rm = i >= 0 ? cubePresets.get(i).ranges() : null;
        return rm != null ? rm : new LinkedHashMap<>();
    }

    /** 활성 큐브 프리셋·스탯의 현재 범위 설정(없으면 디폴트: 고려 안 함, 0~0). */
    public RangeIO.Range rangeOf(String id) {
        return activeRanges().getOrDefault(id, RangeIO.Range.DEFAULT);
    }

    /** 활성 큐브 프리셋의 범위 설정 저장(cube_presets.json) 후 추천 캐시 무효화. */
    public void saveCubeRanges(Map<String, RangeIO.Range> map) throws IOException {
        int i = cubeIndexOf(cubeActiveName);
        if (i < 0) return;
        cubePresets.set(i, new CubePresetIO.CubePreset(
                cubeActiveName, new LinkedHashMap<>(cubeState), new LinkedHashMap<>(map)));
        CubePresetIO.save(dataDir, new CubePresetIO.Store(cubePresets, cubeActiveName));
        recCache = null;
    }

    /** 추천 엔진에 넘길 고려 대상 범위 오프셋(statId→{lo, hi}) — 활성 큐브 프리셋. 제한없음은 ±무한대로. */
    private Map<String, double[]> activeCubeRangeOffsets() {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, RangeIO.Range> e : activeRanges().entrySet()) {
            RangeIO.Range r = e.getValue();
            if (r.enabled()) out.put(e.getKey(), new double[]{r.loBound(), r.hiBound()});
        }
        return out;
    }

    // ──────────────────── 화면 설정(폰트·창 크기) ────────────────────

    public UISettingsIO.UISettings uiSettings() { return ui; }

    public void saveUISettings(UISettingsIO.UISettings s) throws IOException {
        UISettingsIO.save(dataDir, s);
        ui = s;
    }

    /** 이미지 등 정적 자원(아이콘/배경) 폴더 — data 폴더와 같은 위치의 icon/. */
    public Path iconDir() { return dataDir.resolveSibling("icon"); }

    // ──────────────────── 큐브 프리셋(독립) ────────────────────

    /** 활성 큐브 프리셋의 (저장된) 부위별 큐브. */
    private Map<String, CubeStateIO.PartCube> activeCubes() {
        int i = cubeIndexOf(cubeActiveName);
        return cubePresets.get(i >= 0 ? i : 0).cubes();
    }

    private int cubeIndexOf(String name) {
        for (int i = 0; i < cubePresets.size(); i++) if (cubePresets.get(i).name().equals(name)) return i;
        return -1;
    }

    public List<String> cubePresetNames() {
        List<String> out = new ArrayList<>();
        for (CubePresetIO.CubePreset p : cubePresets) out.add(p.name());
        return out;
    }

    public String activeCubePreset() { return cubeActiveName; }

    public boolean hasCubePreset(String name) { return cubeIndexOf(name) >= 0; }

    /** 현재 편집 중인 큐브(cubeState)를 활성 프리셋에 반영하고 cube_presets.json(+mycube.json 미러)으로 기록. */
    private void writeCubePresets() throws IOException {
        int i = cubeIndexOf(cubeActiveName);
        if (i >= 0) cubePresets.set(i, new CubePresetIO.CubePreset(
                cubeActiveName, new LinkedHashMap<>(cubeState), cubePresets.get(i).ranges()));
        CubePresetIO.save(dataDir, new CubePresetIO.Store(cubePresets, cubeActiveName));
        CubeStateIO.save(dataDir, cubeState);   // 활성 미러(기존 코드 호환)
        recCache = null;
    }

    public void switchCubePreset(String name) throws IOException {
        if (cubeIndexOf(name) < 0 || name.equals(cubeActiveName)) return;
        cubeActiveName = name;
        cubeState = new LinkedHashMap<>(activeCubes());
        CubePresetIO.save(dataDir, new CubePresetIO.Store(cubePresets, cubeActiveName));
        CubeStateIO.save(dataDir, cubeState);
        recCache = null;
    }

    /** 현재 큐브를 사본으로 하는 새 프리셋 생성 후 활성 전환. */
    public void addCubePresetFrom(String name, String srcName) throws IOException {
        int i = cubeIndexOf(srcName);
        if (name == null || name.isEmpty() || hasCubePreset(name) || i < 0) return;
        cubePresets.add(new CubePresetIO.CubePreset(name, deepCopy(cubePresets.get(i).cubes()),
                new LinkedHashMap<>(cubePresets.get(i).ranges())));
        cubeActiveName = name;
        cubeState = new LinkedHashMap<>(activeCubes());
        writeCubePresets();
    }

    /** 빈 큐브 새 프리셋 생성 후 활성 전환. */
    public void addCubePresetBlank(String name) throws IOException {
        if (name == null || name.isEmpty() || hasCubePreset(name)) return;
        cubePresets.add(new CubePresetIO.CubePreset(name, new LinkedHashMap<>(), new LinkedHashMap<>()));
        cubeActiveName = name;
        cubeState = new LinkedHashMap<>();
        writeCubePresets();
    }

    public void renameCubePreset(String oldName, String newName) throws IOException {
        int i = cubeIndexOf(oldName);
        if (i < 0 || newName == null || newName.isEmpty() || hasCubePreset(newName)) return;
        cubePresets.set(i, new CubePresetIO.CubePreset(newName, cubePresets.get(i).cubes(), cubePresets.get(i).ranges()));
        if (cubeActiveName.equals(oldName)) cubeActiveName = newName;
        CubePresetIO.save(dataDir, new CubePresetIO.Store(cubePresets, cubeActiveName));
    }

    public void deleteCubePreset(String name) throws IOException {
        if (cubePresets.size() <= 1 || cubeIndexOf(name) < 0) return;
        cubePresets.removeIf(p -> p.name().equals(name));
        if (cubeActiveName.equals(name)) {
            cubeActiveName = cubePresets.get(0).name();
            cubeState = new LinkedHashMap<>(activeCubes());
        }
        writeCubePresets();
    }

    private static Map<String, CubeStateIO.PartCube> deepCopy(Map<String, CubeStateIO.PartCube> src) {
        return new LinkedHashMap<>(src);   // PartCube/Line/Slot은 불변 record라 얕은 복사로 충분
    }

    /** 부위의 저장된 내 큐브 옵션(없으면 null). */
    public CubeStateIO.PartCube cubeOf(String part) { return cubeState.get(part); }

    /** 한 부위의 내 큐브 옵션(윗잠+아랫잠)을 저장(활성 큐브 프리셋에 반영). */
    public void saveCube(String part, CubeStateIO.PartCube pc) throws IOException {
        cubeState.put(part, pc);
        writeCubePresets();
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

    /** 임의의 입력값 묶음으로부터 예상 데미지 {일반몹, 보스몹}을 계산(current 미변경, 라이브 미리보기용). */
    public double[] previewDamage(Map<String, Double> values) {
        StatBlock b = new StatBlock();
        for (Map.Entry<String, Double> e : values.entrySet()) b.set(e.getKey(), e.getValue());
        return new double[]{ g.model.finalDamageNormal(b), g.model.finalDamageBoss(b) };
    }

    public double currentDamage() { return g.model.finalDamage(current); }
    /** 일반 몬스터 대상 데미지(예상 데미지 기준). */
    public double currentDamageNormal() { return g.model.finalDamageNormal(current); }
    /** 보스 몬스터 대상 데미지. */
    public double currentDamageBoss() { return g.model.finalDamageBoss(current); }

    /** 현재 스탯을 활성 프리셋에 반영하고 presets.json(+character.json 미러)으로 저장. */
    public void save() throws IOException {
        int i = indexOf(activeName);
        if (i >= 0) presets.set(i, new PresetIO.Preset(activeName, new StatBlock(current)));
        writePresets();
    }

    /** 유저가 한 번이라도 스탯을 직접 저장했는지(첫 실행이면 false). 프리셋 파일이 있으면 입력된 것으로 본다. */
    public boolean isUserEntered() { return CharacterIO.isUserEntered(dataDir) || PresetIO.exists(dataDir); }

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
        // 실제 옵션이 하나도 없는 층은 추천 대상 아님(잠금만 있는 빈 층도 제외 → 팬텀 추천 방지).
        if (line.grade() == null || isLineEmpty(line)) return;
        List<List<PotentialDraw>> slots = ep.slots(part, line.grade());
        PotentialDraw[] curDraws = currentDraws(line, slots);
        boolean[] locked = line.locked() != null ? line.locked() : new boolean[3];
        CubeRecommender.EquipOpts eo = CubeRecommender.analyze(
                g.model, current, slots, curDraws, part, upper, price, activeCubeRangeOffsets(),
                locked, line.grade(), line.stack(), ep);
        if (!eo.shown().isEmpty()) recCache.add(eo);
    }

    /** 층(윗잠/아랫잠)에 실제 옵션이 하나도 없는지(잠금만으로는 추천 대상이 되지 않음). */
    private static boolean isLineEmpty(CubeStateIO.Line line) {
        if (line.slots() == null) return true;
        for (CubeStateIO.Slot s : line.slots()) if (s != null) return false;
        return true;
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
