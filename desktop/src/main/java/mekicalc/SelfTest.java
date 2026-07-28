package mekicalc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import mekicalc.config.CharacterIO;
import mekicalc.config.ConversionIO;
import mekicalc.config.GameData;
import mekicalc.config.RangeIO;
import mekicalc.engine.equip.CubeRecommender;
import mekicalc.engine.AcquisitionOption;
import mekicalc.engine.CommonDealModel;
import mekicalc.engine.DamageModel;
import mekicalc.engine.Recommender;
import mekicalc.engine.equip.CubeEvaluator;
import mekicalc.engine.equip.EquipmentPotential;
import mekicalc.engine.equip.PotentialDraw;
import mekicalc.engine.skill.NightlordDealModel;
import mekicalc.engine.skill.SkillDps;
import mekicalc.engine.skill.SkillData;
import mekicalc.engine.skill.SkillDpsModel;
import mekicalc.json.Json;
import mekicalc.model.Profile;
import mekicalc.model.StatBlock;
import mekicalc.model.StatDef;
import mekicalc.ui.AppModel;

/**
 * 외부 테스트 프레임워크 없이 도는 결정적 자체 검증.
 * 손계산으로 알 수 있는 입력에 대해 엔진 출력이 일치하는지 확인한다.
 */
public final class SelfTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final double EPS = 1e-9;

    public static void run() throws Exception {
        System.out.println("=== SelfTest 시작 ===");

        testJsonParser();
        testNeutralBaseline();
        testDamageAmp();
        testStatPropDamage();
        testMainStatBoostsAttack();
        testPerLevelMainStat();
        testCrit();
        testQualitativeStats();
        testCustomConversion();
        testConversionIO();
        testRangeConstraint();
        testParetoAxisVerify();
        testCrossGradeProbBounded();
        testCeilingEdgeForcesGradeUp();
        testSkillDpsModelCore();
        testSkillDps();
        testDarkKnight();
        testRangeBestEffort();
        testSlotLock();
        testGradeUp();
        testRangeIO();
        testCubePresetIO();
        testAttackSpeed();
        testFinalAndSkillLevels();
        testSkillLevelByTier();
        testDamageRange();
        testCombineModes();
        testUncombine();
        testAdditiveVsMultiplicative();
        testIncreaseEndToEnd();
        testLoadRealDataConsistency();
        testEquipmentPotential();
        testCubeMath();
        testCharacterIO();
        testPresetIO();
        testAppModel();
        testFirstRunMarker();
        testCompareDraws();
        testCubeStateIO();

        System.out.println("=== 결과: " + passed + " PASS / " + failed + " FAIL ===");
        if (failed > 0) System.exit(1);
    }

    // ---- 데미지 공식 ----

    /** 중립 입력: ATK_FLAT만 있고 배수 항목이 모두 1 → finalDamage == ATK_FLAT. */
    private static void testNeutralBaseline() {
        DamageModel m = new DamageModel(Map.of(), neutral());
        check("neutral", m.finalDamage(baseBlock(100)), 100.0);
    }

    private static void testDamageAmp() {
        DamageModel m = new DamageModel(Map.of(), neutral());
        // 데미지 100%, 증폭 50% → (1+1)*(1.5)=3 → 100*3=300
        StatBlock b = baseBlock(100).with("DMG", 100).with("DMG_AMP", 50);
        check("amp", m.finalDamage(b), 300.0);
    }

    /** 스탯 비례 데미지%는 유저 입력값(STAT_PROP_DMG)을 그대로 factor로 쓴다. 주스탯 자체는 1타에 직접 영향 X. */
    private static void testStatPropDamage() {
        DamageModel m = new DamageModel(Map.of(), neutral());
        // 스탯비례 2% → factor 1.02 → 102
        check("statprop.field", m.finalDamage(baseBlock(100).with("STAT_PROP_DMG", 2)), 102.0);
        // 주스탯깡/%만 있고 STAT_PROP_DMG=0이면 1타 데미지 영향 없음(스탯비례는 STAT_PROP_DMG로만 들어감).
        check("statprop.mainNoDirect",
                m.finalDamage(baseBlock(100).with("MAIN_STAT_FLAT", 100).with("MAIN_STAT_PCT", 100)), 100.0);
    }

    /** '1레벨당 주스탯' 큐브 옵션 = 즉시 (레벨×값) 깡주스탯 환산 → 깡공·스탯비례 동반 상승, 누적 안 됨. */
    private static void testPerLevelMainStat() {
        Map<String, StatDef> stats = makeStats(
                new StatDef("LEVEL", "레벨", "LEVEL", StatDef.Combine.SUM, 0, false),
                new StatDef("MAIN_STAT_FLAT", "주스탯깡", "FLAT", StatDef.Combine.SUM, 0, false),
                new StatDef("MAIN_STAT_PER_LEVEL", "레벨당주스탯", "FLAT", StatDef.Combine.SUM, 0, false));
        DamageModel m = new DamageModel(stats, neutral());
        StatBlock cur = baseBlock(10000).with("LEVEL", 200).with("STAT_PROP_DMG", 0);
        StatBlock c = m.combine(cur, new StatBlock().with("MAIN_STAT_PER_LEVEL", 5));
        check("perlevel.flat", c.get("MAIN_STAT_FLAT"), 1000);      // 200 × 5
        check("perlevel.atk", c.get("ATK_FLAT"), 11000);            // 10000 + 1000
        check("perlevel.statprop", c.get("STAT_PROP_DMG"), 10);     // 1000 / 100
        check("perlevel.notKept", c.get("MAIN_STAT_PER_LEVEL"), 0); // 누적 스탯 아님
        StatBlock back = m.uncombine(c, new StatBlock().with("MAIN_STAT_PER_LEVEL", 5));
        check("perlevel.un.flat", back.get("MAIN_STAT_FLAT"), 0);
        check("perlevel.un.atk", back.get("ATK_FLAT"), 10000);
        check("perlevel.un.statprop", back.get("STAT_PROP_DMG"), 0);
    }

    /**
     * 옵션 비교 관점: 주스탯이 "늘어나면" 스탯비례뿐 아니라 깡공(1:1)도 올라야 한다.
     * 단 raw 입력 블록의 예상 데미지는 입력 공격력에 현재 주스탯이 이미 포함이라 baseline은 그대로.
     */
    private static void testMainStatBoostsAttack() {
        DamageModel m = new DamageModel(Map.of(), neutral());
        // current: 깡공 10000, 주스탯 100, 스탯비례 base 1%(=주스탯100/100) → 예상 10000×(1+1/100)=10100
        StatBlock cur = baseBlock(10000).with("MAIN_STAT_FLAT", 100).with("MAIN_STAT_PCT", 0).with("STAT_PROP_DMG", 1);
        check("mainstat.baseline", m.finalDamage(cur), 10100.0);

        // 주스탯깡 +100 → 늘어난 100이 깡공(+100)·스탯비례(+1%)에 동반 반영 → 10100×(1+2/100)=10302
        StatBlock delta = new StatBlock().with("MAIN_STAT_FLAT", 100);
        check("mainstat.boostsAtk", m.finalDamage(m.combine(cur, delta)), 10302.0);
        // 증가량 = 10302/10100 - 1 = 정확히 2% (깡공 +1% × 스탯비례 +0.99%)
        check("mainstat.incBothParts", m.increase(cur, delta), 0.02);
    }

    private static void testCrit() {
        DamageModel m = new DamageModel(Map.of(), neutral());
        // 크리데미지 100% → 크리 시 +100%. 확률 50% → 1 + 0.5*1.0 = 1.5
        check("crit", m.finalDamage(baseBlock(100).with("CRIT_RATE", 50).with("CRIT_DMG", 100)), 150.0);
        // 확률 150% → 100%로 클램프 → 1 + 1*1 = 2
        check("crit.clamp", m.finalDamage(baseBlock(100).with("CRIT_RATE", 150).with("CRIT_DMG", 100)), 200.0);
    }

    /** 방관은 정성적 스탯(몬스터 방어력 불명) → 데미지 영향 없음. 공속은 정성적 아님(DPS 배수). */
    private static void testQualitativeStats() {
        Map<String, StatDef> stats = makeStats(
                new StatDef("ATK_SPEED", "공속", "PCT", StatDef.Combine.DIMINISHING, 150, false),
                new StatDef("DEF_PEN", "방관", "PCT", StatDef.Combine.DIMINISHING, 100, true),
                new StatDef("ATK_PCT", "공%", "PCT", StatDef.Combine.SUM, 0, false));
        DamageModel m = new DamageModel(stats, neutral());
        // 1타 데미지엔 공속/방관 모두 미포함
        check("qual.speedNoPerHit", m.finalDamage(baseBlock(100).with("ATK_SPEED", 50)), 100.0);
        check("qual.penNoDamage", m.finalDamage(baseBlock(100).with("DEF_PEN", 80)), 100.0);
        // 방관은 효율에도 0(데미지 환산 안 함), 공속은 효율에 DPS로 반영(아래 별도 테스트)
        check("qual.penZeroInc", m.increase(baseBlock(100), new StatBlock().with("DEF_PEN", 30)), 0.0);
        checkTrue("qual.flagTruePen", m.hasQualitative(new StatBlock().with("DEF_PEN", 10)));
        checkTrue("qual.flagFalseSpeed", !m.hasQualitative(new StatBlock().with("ATK_SPEED", 10)));
        checkTrue("qual.flagFalse", !m.hasQualitative(new StatBlock().with("ATK_PCT", 10)));
    }

    /**
     * 커스텀 딜 환산: 환산 ON이면 정성적 스탯도 (1 + perUnit/100 × 값) 곱연산으로 데미지에 반영되고
     * 더 이상 '정성적(계산 불가)'이 아니다. 스킬 레벨은 기본공격계수에서 빠지고 환산으로만 반영(중복 금지).
     */
    private static void testCustomConversion() {
        Map<String, StatDef> stats = makeStats(
                new StatDef("DEF_PEN", "방관", "PCT", StatDef.Combine.DIMINISHING, 100, true),
                new StatDef("ATK_PCT", "공%", "PCT", StatDef.Combine.SUM, 0, false));

        // 방관 1%당 딜 +0.5% 환산 ON
        DamageModel m = new DamageModel(stats, neutral(), Map.of("DEF_PEN", 0.5));
        // 1타 데미지에 반영: 방관 30 → ×(1+0.005×30)=1.15 → 115
        check("conv.pen.damage", m.finalDamage(baseBlock(100).with("DEF_PEN", 30)), 115.0);
        // 더 이상 정성적 아님 → 효율로 환산(방관 0→30, DIMINISHING이라 30 그대로) → +15%
        check("conv.pen.inc", m.increase(baseBlock(100), new StatBlock().with("DEF_PEN", 30)), 0.15);
        checkTrue("conv.pen.notQual", !m.hasQualitative(new StatBlock().with("DEF_PEN", 10)));

        // 환산 OFF(기본): 정성적 그대로 → 데미지 미반영, 정성적 플래그 유지
        DamageModel off = new DamageModel(stats, neutral());
        check("conv.pen.off.damage", off.finalDamage(baseBlock(100).with("DEF_PEN", 30)), 100.0);
        checkTrue("conv.pen.off.qual", off.hasQualitative(new StatBlock().with("DEF_PEN", 10)));

        // 스킬 레벨 중복 계산 방지: 4차 레벨110 + 모든스킬5, '모든 스킬 레벨' 환산 ON(레벨당 +1%)
        Map<String, StatDef> sl = makeStats(
                new StatDef("LEVEL", "레벨", "LEVEL", StatDef.Combine.SUM, 0, false),
                new StatDef("SKILL_LEVELS", "모든스킬", "LEVEL", StatDef.Combine.SUM, 0, false));
        StatBlock b = baseBlock(100).with("LEVEL", 110).with("SKILL_LEVELS", 5);
        // 기본(환산 OFF): E = 3×(110-100) + 5 = 35 → 2.9×(1+0.004×35)=3.306 → 330.6
        DamageModel base = new DamageModel(sl, new Profile(0, 2.9, 0.004, false));
        check("conv.skill.default", base.finalDamage(b), 330.6);
        // 환산 ON: 모든스킬은 계수에서 제외(E=30 → 3.248), 대신 ×(1+0.01×5)=1.05 → 3.248×1.05×100=341.04
        DamageModel cv = new DamageModel(sl, new Profile(0, 2.9, 0.004, false), Map.of("SKILL_LEVELS", 1.0));
        check("conv.skill.noDoubleCount", cv.finalDamage(b), 341.04);
    }

    /** conversions.json 저장/로드 라운드트립 + enabledPerUnit 필터. */
    private static void testConversionIO() throws Exception {
        java.nio.file.Path tmp = Files.createTempDirectory("mekicalc-conv");
        Map<String, ConversionIO.Conv> map = new LinkedHashMap<>();
        map.put("DEF_PEN", new ConversionIO.Conv(true, 0.5));
        map.put("SKILL_LEVELS", new ConversionIO.Conv(false, 2));
        ConversionIO.save(tmp, map);

        Map<String, ConversionIO.Conv> loaded = ConversionIO.load(tmp);
        checkTrue("conv.io.penEnabled", loaded.get("DEF_PEN").enabled());
        check("conv.io.penVal", loaded.get("DEF_PEN").perUnit(), 0.5);
        checkTrue("conv.io.skillDisabled", !loaded.get("SKILL_LEVELS").enabled());

        Map<String, Double> en = ConversionIO.enabledPerUnit(tmp);
        checkTrue("conv.io.enabledOnly", en.containsKey("DEF_PEN") && !en.containsKey("SKILL_LEVELS"));
        check("conv.io.enabledVal", en.get("DEF_PEN"), 0.5);

        // 없는 폴더 → 빈 맵(모두 디폴트)
        checkTrue("conv.io.missing", ConversionIO.load(Files.createTempDirectory("mekicalc-conv2")).isEmpty());
        Files.deleteIfExists(tmp.resolve(ConversionIO.FILE));
        Files.deleteIfExists(tmp);
    }

    /**
     * 추천 옵션 범위: 크확을 '현재값 유지(0~0)'로 고려하면, 크확을 버리는(잃는) 추천 후보가 제외된다.
     * 결과: 달성 가능한 최대 딜 증가량이 줄고(크확 칸을 다른 옵션에 못 씀), 추천은 크확을 유지한다.
     */
    private static void testRangeConstraint() {
        Map<String, StatDef> stats = makeStats(
                new StatDef("ATK_FLAT", "공", "FLAT", StatDef.Combine.SUM, 0, false),
                new StatDef("CRIT_RATE", "크확", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("CRIT_DMG", "크뎀", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("DMG", "뎀", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("MIN_DMG_MULT", "최소", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("MAX_DMG_MULT", "최대", "PCT", StatDef.Combine.SUM, 0, false));
        DamageModel m = new DamageModel(stats, neutral());
        StatBlock current = baseBlock(100).with("CRIT_RATE", 50).with("CRIT_DMG", 100);

        // slot0: 크확+20 또는 뎀+100 / slot1: 뎀+100 / slot2: 없음. 현재 slot0에 크확+20.
        List<List<PotentialDraw>> slots = List.of(
                List.of(new PotentialDraw("CRIT_RATE", 20, 50, false, false),
                        new PotentialDraw("DMG", 100, 50, false, false)),
                List.of(new PotentialDraw("DMG", 100, 50, false, false)),
                List.of());
        PotentialDraw[] curDraws = { new PotentialDraw("CRIT_RATE", 20, 50, false, false), null, null };

        CubeRecommender.EquipOpts noR = CubeRecommender.analyze(m, current, slots, curDraws, "x", true, 1.0);
        CubeRecommender.EquipOpts wR = CubeRecommender.analyze(m, current, slots, curDraws, "x", true, 1.0,
                Map.of("CRIT_RATE", new double[]{0, 0}));

        double noMax = maxShownDeal(noR), wMax = maxShownDeal(wR);
        checkTrue("range.lowersMaxDeal", noMax > wMax + 1e-6);   // 제약이 최대 딜을 낮춤
        checkTrue("range.topKeepsCrit", topShownHasStat(wR, "CRIT_RATE"));  // 추천이 크확 유지
        checkTrue("range.noRangeDropsCrit", !topShownHasStat(noR, "CRIT_RATE")); // 제약 없으면 크확 버림
    }

    /**
     * (1) 동일 옵션 중복 보정: 같은 옵션이 여러 슬롯에 있어도 arrangeSum이 순열만큼 부풀지 않음(∏ mi!로 나눔).
     * (1b) 한계 등장확률(appearProb): 나머지 슬롯 무관 → 단일이 arrangeSum(정확구성, 나머지 빈)보다 큼.
     * (2) 파레토 선정 축이 '단일 최고배치'가 아니라 '한계 등장확률'.
     * (3) 확률 총합(totalProb, 상호배타 arrangeSum)이 100%를 넘지 않음(사용자 보고 버그).
     * 슬롯 [P],[P],[]에 P(60%) → 후보 {P}(딜100), {P,P}(딜200) 둘뿐.
     *   한계(appearProb): {P}=1−0.4²=0.84, {P,P}=0.6²=0.36
     *   정확구성(arrangeSum): {P}=2·0.6·0.4=0.48, {P,P}=0.6²=0.36
     *   단일 최고배치: {P}=0.24, {P,P}=0.36
     *   한계 축이면 낮은딜 {P}(0.84>0.36)도 표시(size 2). 단일 축이면 {P}(0.24<0.36) 숨김(size 1).
     */
    private static void testParetoAxisVerify() {
        Map<String, StatDef> stats = makeStats(
                new StatDef("ATK_FLAT", "공", "FLAT", StatDef.Combine.SUM, 0, false),
                new StatDef("MIN_DMG_MULT", "최소", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("MAX_DMG_MULT", "최대", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("DMG", "뎀", "PCT", StatDef.Combine.SUM, 0, false));
        DamageModel m = new DamageModel(stats, neutral());
        StatBlock current = baseBlock(100);
        PotentialDraw P = new PotentialDraw("DMG", 100, 60, false, false);   // 등장확률 60%
        List<List<PotentialDraw>> slots = List.of(List.of(P), List.of(P), List.of());
        PotentialDraw[] curDraws = { null, null, null };
        CubeRecommender.EquipOpts eo = CubeRecommender.analyze(m, current, slots, curDraws, "x", true, 1.0);

        // (1) 동일 옵션 중복 보정
        check("arrangeSum.identicalCorrected", CubeRecommender.arrangeSum(List.of(P, P), slots), 0.6 * 0.6);
        check("arrangeSum.singleTwoSlots", CubeRecommender.arrangeSum(List.of(P), slots), 2 * 0.6 * 0.4);

        // (1b) 한계 등장확률(appearProb): 단일 = 1−Π(1−p) (arrangeSum의 정확구성 0.48보다 큰 0.84)
        check("appearProb.single", CubeRecommender.appearProb(List.of(P), slots), 1 - 0.4 * 0.4);
        check("appearProb.identical", CubeRecommender.appearProb(List.of(P, P), slots), 0.6 * 0.6);   // 2슬롯 모두 P

        // (2) 총합 기준이면 낮은딜 단일 {P}도 표시
        boolean singlePShown = false;
        for (CubeRecommender.OptSet s : eo.shown()) {
            int cnt = 0; for (PotentialDraw d : s.rep()) if (d != null) cnt++;
            if (cnt == 1) singlePShown = true;
        }
        checkTrue("paretoAxis.usesTotal", singlePShown && eo.shown().size() == 2);

        // (3) 확률 총합 100% 초과 금지
        checkTrue("prob.totalUnder100", eo.totalProb() <= 1.0 + 1e-9);
    }

    /** 승급(상위 등급) 후보가 섞여도 '전체 딜 증가 확률 총합'은 같은 등급만 합산해 100%를 넘지 않는다. */
    private static void testCrossGradeProbBounded() throws Exception {
        GameData g = GameData.load(Path.of("data"));
        EquipmentPotential ep = EquipmentPotential.load(Path.of("data"));
        String part = "장갑";
        List<List<PotentialDraw>> slots = ep.slots(part, "NORMAL");
        PotentialDraw[] curDraws = { null, null, null };   // 현재 비어 있음 → 승급 후보 다수 생성
        CubeRecommender.EquipOpts eo = CubeRecommender.analyze(
                g.model, g.current, slots, curDraws, part, true, 1.0,
                java.util.Map.of(), null, "NORMAL", 0, ep);
        boolean hasCross = false;
        for (CubeRecommender.OptSet s : eo.shown()) if (!"NORMAL".equals(s.grade())) hasCross = true;
        checkTrue("crossGrade.present", hasCross);                       // 승급 후보가 실제로 섞임
        checkTrue("crossGrade.totalUnder100", eo.totalProb() <= 1.0 + 1e-9);
    }

    /** 천장 직전(stack = 천장−1)이면 다음 큐브가 강제 승급 → totalProb이 천장(stack=천장)과 같고, stack=0과는 달라야 한다. */
    private static void testCeilingEdgeForcesGradeUp() throws Exception {
        GameData g = GameData.load(Path.of("data"));
        EquipmentPotential ep = EquipmentPotential.load(Path.of("data"));
        String part = "장갑";
        List<List<PotentialDraw>> slots = ep.slots(part, "NORMAL");
        // 현재 옵션을 강한 값으로 채워 포화 방지(빈 상태면 모든 결과가 개선이라 확률이 1로 포화됨).
        PotentialDraw[] curDraws = { bestDraw(slots.get(0)), bestDraw(slots.get(1)), bestDraw(slots.get(2)) };
        int ceil = ep.gradeUpCeiling("NORMAL");                          // 42
        double t0    = analyzeTotal(g, ep, part, slots, curDraws, ceil - 1);
        double tCeil = analyzeTotal(g, ep, part, slots, curDraws, ceil);
        double tLow  = analyzeTotal(g, ep, part, slots, curDraws, 0);
        check("ceiling.edgeForced", t0, tCeil);                          // 41 == 42 (둘 다 강제)
        checkTrue("ceiling.lowDiffers", Math.abs(t0 - tLow) > 1e-6);     // 0 은 자연확률이라 다름
    }

    /** 한 슬롯 후보 중 딜에 영향 주는 가장 높은 값 한 줄(현재 옵션을 강하게 만들기 위함). */
    private static PotentialDraw bestDraw(List<PotentialDraw> slot) {
        PotentialDraw best = null;
        for (PotentialDraw d : slot)
            if (d != null && (best == null || d.value() > best.value())) best = d;
        return best;
    }

    private static double analyzeTotal(GameData g, EquipmentPotential ep, String part,
            List<List<PotentialDraw>> slots, PotentialDraw[] curDraws, int stack) {
        return CubeRecommender.analyze(g.model, g.current, slots, curDraws, part, true, 1.0,
                java.util.Map.of(), null, "NORMAL", stack, ep).totalProb();
    }

    /** DPS 모델 핵심 계산 검산(메모리 스펙의 검증값들). */
    private static void testSkillDpsModelCore() throws Exception {
        SkillData sd = SkillData.load(Path.of("data"));
        SkillDpsModel m = new SkillDpsModel(sd);
        // 데이터 로드
        check("skill.data.basic4Base", sd.skill("basic4").num("base", 0), 290);
        check("skill.data.growthDealActive", sd.growth("dealActive"), 0.5);
        // 현재 계수: 4차 평타 290% × (1+0.4%×100) = 406
        check("skill.coeff.basic4", SkillDpsModel.coefficient(290, sd.growth("basic"), 100), 406);
        // 기본 스킬 레벨
        check("skill.baseLv.t3@99", m.baseSkillLevel(3, 99), 117);
        check("skill.baseLv.t3@100", m.baseSkillLevel(3, 100), 120);
        check("skill.baseLv.t4@100", m.baseSkillLevel(4, 100), 0);
        check("skill.baseLv.t4@101", m.baseSkillLevel(4, 101), 3);
        // 쿨감: 비율(다크플레어 45×0.7=31.5), 고정 체감(원쿨8·쿨감초5 → 5)
        check("skill.cd.pct", SkillDpsModel.cooldown(45, 30, 0), 31.5);
        check("skill.cd.flatFloor", SkillDpsModel.cooldown(8, 0, 5), 5);
        // 베놈 가동률: 지속10, p0.4, 공속100% → 10/11.25 ≈ 0.8889
        check("skill.venomUptime", SkillDpsModel.venomUptime(10, 0.4, 100), 10.0 / 11.25);
        // 메용 배수: 레벨 120까지 5%, 초과 1% 체감. 레벨157 → 130×(1+6.0+0.37)=958.1
        check("skill.meyong.break", m.meyongFinalBonus(130, 100, 157, 0), 958.1);
        check("skill.meyong.under120", m.meyongFinalBonus(130, 100, 105, 0), 812.5); // 105 → 130×6.25
    }

    /** 나이트로드 DPS: 통제된 StatBlock(공격력100·배율100, 나머지 0)으로 1타 데미지·DPS 손계산 대조. */
    private static void testSkillDps() throws Exception {
        GameData g = GameData.load(Path.of("data"));
        SkillData sd = SkillData.load(Path.of("data"));
        SkillDps nd = new SkillDps(g.model, new SkillDpsModel(sd));
        // G_boss = 공격력100 × 1 × ... × 데미지범위((100+100)/200=1) × ... × (1+보스뎀0) = 100
        StatBlock b = new StatBlock()
                .set("LEVEL", 100).set("ATK_FLAT", 100)
                .set("MIN_DMG_MULT", 100).set("MAX_DMG_MULT", 100);

        // 레벨150 블록(모든 마스터리 개방) — 레벨 게이팅 ON 검증용.
        StatBlock b150 = new StatBlock().set("LEVEL", 150).set("ATK_FLAT", 100)
                .set("MIN_DMG_MULT", 100).set("MAX_DMG_MULT", 100);

        SkillData.Skill basic4 = sd.skill("basic4");
        // 레벨100: 4차 피해/보스 마스터리(102~132렙) 미개방 → 마스터리 1.0. 계수 2.9 × G100 = 290
        check("nl.perHit.basic4.lv100", nd.perHit(basic4, b, true), 290);
        check("nl.basicDps.basic4.lv100", nd.basicDps(basic4, b, true), 1450); // 290 × 5타 × 초당타수1
        // 레벨150: 마스터리 전부(피해+75%·보스+20% = 2.1), 계수 290×(1+0.4%×150)=4.64 → 100×4.64×2.1 = 974.4
        check("nl.perHit.basic4.lv150", nd.perHit(basic4, b150, true), 974.4);
        check("nl.basicHits.basic4", nd.basicHits(basic4, b), 5);

        // [실측 2026-07] 평타/스킬 피해량 마스터리는 기본공격뎀%/스킬뎀%에 '합산'(별도 곱 아님).
        //   lv150 평타 피해량 마스터리 75%. 기본공격뎀 0 → typeFactor 1.75, 기본공격뎀 50 → 1.5+0.75=2.25(합산).
        //   비율 2.25/1.75 (곱 모델이면 1.5). 보스 마스터리 배제 위해 boss=false.
        double mNoAtk = nd.perHit(basic4, b150, false);
        double mWithAtk = nd.perHit(basic4, new StatBlock(b150).set("BASIC_ATK_DMG", 50), false);
        check("nl.mastery.summedIntoType", mWithAtk / mNoAtk, 2.25 / 1.75);
        // 보스 피해량 마스터리도 보스뎀%에 '합산'(곱 아님). lv150 보스 마스터리 20%.
        //   보스뎀 0 → 보스팩터 1.2, 보스뎀 50 → 1+(50+20)/100 = 1.7. 비율 1.7/1.2 (곱 모델이면 1.5).
        double bossNo = nd.perHit(basic4, b150, true);
        double bossWith = nd.perHit(basic4, new StatBlock(b150).set("BOSS_DMG", 50), true);
        check("nl.mastery.bossSummed", bossWith / bossNo, 1.7 / 1.2);

        SkillData.Skill wind = sd.skill("windTalisman");
        // 2차 base스킬레벨90 → 계수 5.8, 메용 130%(×2.3), G100 = 1334
        check("nl.perHit.wind", nd.perHit(wind, b, true), 1334);
        check("nl.cd.wind", nd.cooldown(wind, b), 24);
        check("nl.activeDps.wind", nd.simpleActiveDps(wind, b, true), 1334.0 / 24);

        // 쿼드러플 쿨감 마스터리(108렙): 레벨150 → 10×0.7 = 7초
        check("nl.cd.quad", nd.cooldown(sd.skill("quadThrow"), b150), 7);

        // 버프 주입: 레벨100 → 다크사이트(15렙)·아드레날린(66렙)·민첩 활성, 퍼지(110렙)·페이크/세레니티(상시)는 제외.
        SkillDps.Buffed bf = nd.buffed(b);
        check("nl.buff.crit", bf.stats.get("CRIT_RATE"), 3.5712);     // 다크사이트 크확 6 × 1.24 × 0.48 (statActive kind growth 0.4: 1+0.004×60)
        check("nl.buff.atkFactor", bf.skillAtkFactor, 1.30432);       // 숙련된표창술 24.48(0.3) + 다크사이트 5.952(0.4)
        check("nl.buff.finalDmg", bf.stats.get("FINAL_DMG"), (10 * 1.36 + 6) * (15.0 / 22.0)); // 아드레날린: base10×scale1.36 + 마스터리6(나중), ×가동률
        double adrAS = 8 * 1.36 * (15.0 / 22.0);
        double expAS = adrAS + (150 - adrAS) * ((15 * 0.25) / 150.0);
        check("nl.buff.atkSpeed", bf.stats.get("ATK_SPEED"), expAS);

        // 특수 스킬 컴포넌트(공속0 → G=100, 베놈 가동률 0.8).
        check("nl.mark.noNightlord", nd.markPerHit(b, true), 734.4);  // 레벨100: 나이트로드(107렙) 미개방, 마크 0.4%
        check("nl.mark.full", nd.markPerHit(b150, true), 4259.52);    // 레벨150: 나이트로드 최종뎀 300×(1+0.4%×150)=480→×5.8, 마크 0.4%
        check("nl.venom", nd.venomDotDps(b, true), 79.92);
        // 스킬뎀% 적용 검증: SKILL_DMG 100% → 베놈도트 2배
        StatBlock bSkillDmg = new StatBlock().set("LEVEL", 100).set("ATK_FLAT", 100)
                .set("MIN_DMG_MULT", 100).set("MAX_DMG_MULT", 100).set("SKILL_DMG", 100);
        check("nl.venom.skillDmg", nd.venomDotDps(bSkillDmg, true), 79.92 * 2);
        check("nl.shadow", nd.shadowExtraPerHit(b, true), 93.24);   // base 84(이전 60): 66.6 × 84/60 (마스터리 +100% 유지)
        check("nl.fatal.locked", nd.fatalExtraPerHit(b, true), 0);    // 117렙 개방, 레벨100 잠김
        // 레벨150: 페이탈 피해 마스터리(130렙) 개방, 스킬레벨150 → 계수9.6 → 100×9.6×2.0×0.2×0.8 = 307.2
        check("nl.fatal", nd.fatalExtraPerHit(b150, true), 307.2);
        check("nl.recvDebuff", nd.recvDmgFactor(b), 1.096);          // 베놈약화 0.12×0.8 (퍼지·마크 미개방)
        // 종합 DPS: 양수. 대상수 곱 제거 → 단일대상이라 보스/일반 차이는 보스뎀(BOSS_DMG)뿐.
        checkTrue("nl.total.pos", nd.totalDps(b, true) > 0);
        check("nl.total.mobEqBossNoBossDmg", nd.totalDps(b, false), nd.totalDps(b, true));   // BOSS_DMG 미설정 → 동일
        StatBlock bBoss = new StatBlock().set("LEVEL", 100).set("ATK_FLAT", 100)
                .set("MIN_DMG_MULT", 100).set("MAX_DMG_MULT", 100).set("BOSS_DMG", 50);
        checkTrue("nl.total.bossGtMobWithBossDmg", nd.totalDps(bBoss, true) > nd.totalDps(bBoss, false));

        // 회색 스탯 환산: DPS 증가율. 레벨200(전 스킬 개방) 기준.
        StatBlock big = new StatBlock().set("LEVEL", 200).set("ATK_FLAT", 100)
                .set("MIN_DMG_MULT", 100).set("MAX_DMG_MULT", 100);
        checkTrue("nl.conv.skillLv", nd.increase(big, new StatBlock().set("SKILL_LEVELS", 10), true) > 0);
        checkTrue("nl.conv.atkSpeed", nd.increase(big, new StatBlock().set("ATK_SPEED", 10), true) > 0);
        checkTrue("nl.conv.cdrFlat", nd.increase(big, new StatBlock().set("COOLDOWN_REDUCTION", 3), true) > 0);
        checkTrue("nl.conv.cdrPct", nd.increase(big, new StatBlock().set("COOLDOWN_REDUCTION_PCT", 20), true) > 0);
        // 대상수(BASIC_ATK_TARGETS): 대상수 곱 제거 → 보스·일반 모두 DPS 무효(=0)
        check("nl.conv.targetsBoss", nd.increase(big, new StatBlock().set("BASIC_ATK_TARGETS", 1), true), 0);
        check("nl.conv.targetsMob", nd.increase(big, new StatBlock().set("BASIC_ATK_TARGETS", 1), false), 0);

        // 실제 캐릭터 데이터(data/character.json) 경로: 예외 없이 동작하고 쿨감·모스렙이 DPS로 잡혀야 함.
        NightlordDealModel ndm = new NightlordDealModel(nd, g.model, true);
        checkTrue("nl.real.totalPos", nd.totalDps(g.current, true) > 0);
        checkTrue("nl.real.cdrValued", ndm.increase(g.current, new StatBlock().set("COOLDOWN_REDUCTION", 2)) > 0);
        checkTrue("nl.real.skillLvValued", ndm.increase(g.current, new StatBlock().set("SKILL_LEVELS", 1)) > 0);
        checkTrue("nl.real.cdrNotQual", !ndm.hasQualitative(new StatBlock().set("COOLDOWN_REDUCTION", 2)));
        // 방관: 몬스터 방어력을 알게 되어 더 이상 정성적 스탯이 아니다(방어배수로 수치 환산).
        checkTrue("nl.real.defPenNotQual", !ndm.hasQualitative(new StatBlock().set("DEF_PEN", 5)));
        // 공용 딜 모델(비나이트로드)도 방관을 수치 환산: 대상 방어력이 있으면 정성 아니고 딜 증가가 양수.
        mekicalc.engine.CommonDealModel cdm = new mekicalc.engine.CommonDealModel(g.model, true, 6000.0);
        checkTrue("common.defPenNotQual", !cdm.hasQualitative(new StatBlock().set("DEF_PEN", 5)));
        // 합성 스탯(데이터 무관)으로 방관 수치 환산 확인: 대상 방어력 있으면 방관에 딜 증가.
        StatBlock synth = new StatBlock().set("ATK_FLAT", 1000).set("MIN_DMG_MULT", 100).set("MAX_DMG_MULT", 100);
        checkTrue("common.defPenValued", cdm.increase(synth, new StatBlock().set("DEF_PEN", 20)) > 0);
        // 공용 딜 모델 보스/일반몹 전환: 보스뎀>일반몹뎀이면 보스 모드 출력이 더 큼.
        StatBlock cb = new StatBlock().set("ATK_FLAT", 100).set("MIN_DMG_MULT", 100)
                .set("MAX_DMG_MULT", 100).set("BOSS_DMG", 50);
        checkTrue("common.bossGtNormal",
                new CommonDealModel(g.model, true).output(cb) > new CommonDealModel(g.model, false).output(cb));

        // 버프 지속시간 증가%: 가동률↑로 DPS 환산(계산 불가 아님). 레벨200(버프 전부 개방).
        NightlordDealModel ndmBig = new NightlordDealModel(nd, g.model, true);
        checkTrue("nl.buffDur.valued", ndmBig.increase(big, new StatBlock().set("BUFF_DURATION", 20)) > 0);
        checkTrue("nl.buffDur.notQual", !ndmBig.hasQualitative(new StatBlock().set("BUFF_DURATION", 20)));
        // 기본 공격 대상 수(BASIC_ATK_TARGETS): 대상수 곱 제거 → 커스텀 환산 없으면 보스·일반 모두 '딜 미환산'.
        NightlordDealModel ndmMob = new NightlordDealModel(nd, g.model, false);
        checkTrue("nl.targets.mobQual", ndmMob.hasQualitative(new StatBlock().set("BASIC_ATK_TARGETS", 1)));
        checkTrue("nl.targets.bossQual", ndmBig.hasQualitative(new StatBlock().set("BASIC_ATK_TARGETS", 1)));
    }

    /** 다크나이트: DK 데이터 로드 + 일반화 엔진 검증(기본공격=공용 동일, 궁니르 baseMult, 총합 양수). */
    private static void testDarkKnight() throws Exception {
        GameData g = GameData.load(Path.of("data"));
        SkillData sd = SkillData.load(Path.of("data"), "darkknight");
        SkillDps nd = new SkillDps(g.model, new SkillDpsModel(sd));
        StatBlock b = new StatBlock().set("LEVEL", 150).set("ATK_FLAT", 100)
                .set("MIN_DMG_MULT", 100).set("MAX_DMG_MULT", 100);
        // 기본공격(다크 임페일)=공용 4차와 동일 계수·마스터리 → 나이트로드와 같은 974.4(보스).
        check("dk.perHit.basic4", nd.perHit(sd.skill("basic4"), b, true), 974.4);
        // 궁니르 baseMultByLevel(134렙 기본피해 ×0.5): 133→134에서 base 반토막 → 1타 데미지 급감.
        double p133 = nd.perHit(sd.skill("gungnir"), new StatBlock(b).set("LEVEL", 133), false);
        double p134 = nd.perHit(sd.skill("gungnir"), new StatBlock(b).set("LEVEL", 134), false);
        checkTrue("dk.gungnir.baseMult", p134 > 0 && p134 < p133 * 0.6);
        // 총합 DPS 양수(엔진 일반화 파이프라인 동작).
        checkTrue("dk.total.pos", nd.totalDps(b, true) > 0);
    }

    private static double maxShownDeal(CubeRecommender.EquipOpts eo) {
        double mx = -1;
        for (CubeRecommender.OptSet s : eo.shown()) mx = Math.max(mx, s.deal());
        return mx;
    }

    private static boolean topShownHasStat(CubeRecommender.EquipOpts eo, String statId) {
        if (eo.shown().isEmpty()) return false;
        CubeRecommender.OptSet top = eo.shown().get(eo.shown().size() - 1);   // 최대 딜 세트
        for (PotentialDraw d : top.rep()) if (d != null && d.statId().equals(statId)) return true;
        return false;
    }

    /** 등급업 환산 확률(상한 있는 기하분포의 1/E) + 다음 등급. */
    private static void testGradeUp() throws Exception {
        EquipmentPotential ep = EquipmentPotential.load(Path.of("data"));
        check("gradeup.normalCeil", ep.gradeUpEffProb("NORMAL", 42), 1.0);              // 천장 도달 → 다음 1개에 확정
        checkTrue("gradeup.normal0", ep.gradeUpEffProb("NORMAL", 0) > 0.06 && ep.gradeUpEffProb("NORMAL", 0) < 0.07);
        checkTrue("gradeup.mono", ep.gradeUpEffProb("NORMAL", 30) > ep.gradeUpEffProb("NORMAL", 0)); // 스택 높을수록↑
        check("gradeup.mythicNone", ep.gradeUpEffProb("MYTHIC", 0), 0.0);              // 최상위는 승급 없음
        checkTrue("gradeup.next", "MYTHIC".equals(ep.nextGrade("LEGENDARY")) && ep.nextGrade("MYTHIC") == null);

        // 승급 기대 큐브 E = (1-(1-g)^r)/g : effProb 의 역수와 일치, 그리고 절대 1/g 를 넘지 않음(천장 효과).
        check("gradeup.cubesInverse", ep.gradeUpCubes("RARE", 10), 1.0 / ep.gradeUpEffProb("RARE", 10));
        check("gradeup.cubesValue", ep.gradeUpCubes("RARE", 10), (1 - Math.pow(1 - 0.03333, 65)) / 0.03333);
        checkTrue("gradeup.cubesUnderInvG", ep.gradeUpCubes("RARE", 0) <= 1.0 / 0.03333 + 1e-9);
        check("gradeup.cubesTopZero", ep.gradeUpCubes("MYTHIC", 0), 0.0);              // 최상위는 0
    }

    /** 잠금/현재 상태로 범위를 만족할 후보가 없으면 범위를 무시하고라도 추천(best-effort). */
    private static void testRangeBestEffort() {
        Map<String, StatDef> stats = makeStats(
                new StatDef("ATK_FLAT", "공", "FLAT", StatDef.Combine.SUM, 0, false),
                new StatDef("CRIT_RATE", "크확", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("CRIT_DMG", "크뎀", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("DMG", "뎀", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("MIN_DMG_MULT", "최소", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("MAX_DMG_MULT", "최대", "PCT", StatDef.Combine.SUM, 0, false));
        DamageModel m = new DamageModel(stats, neutral());
        StatBlock current = baseBlock(100).with("CRIT_RATE", 50).with("CRIT_DMG", 100);
        List<List<PotentialDraw>> slots = List.of(
                List.of(new PotentialDraw("DMG", 100, 50, false, false)),
                List.of(), List.of());
        PotentialDraw[] curDraws = { null, null, null };
        // 불가능한 범위(크확이 -50이 되라) → 만족 후보 0 → best-effort로 DMG 추천이 떠야 함
        CubeRecommender.EquipOpts eo = CubeRecommender.analyze(
                m, current, slots, curDraws, "x", true, 1.0, Map.of("CRIT_RATE", new double[]{-100, -100}), null);
        checkTrue("range.bestEffort", !eo.shown().isEmpty());
    }

    /** 슬롯 잠금: 잠긴 슬롯은 추천이 돌리지 못하므로 그대로 유지된다(크확 슬롯 잠그면 크확 보존). */
    private static void testSlotLock() {
        Map<String, StatDef> stats = makeStats(
                new StatDef("ATK_FLAT", "공", "FLAT", StatDef.Combine.SUM, 0, false),
                new StatDef("CRIT_RATE", "크확", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("CRIT_DMG", "크뎀", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("DMG", "뎀", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("MIN_DMG_MULT", "최소", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("MAX_DMG_MULT", "최대", "PCT", StatDef.Combine.SUM, 0, false));
        DamageModel m = new DamageModel(stats, neutral());
        StatBlock current = baseBlock(100).with("CRIT_RATE", 50).with("CRIT_DMG", 100);
        List<List<PotentialDraw>> slots = List.of(
                List.of(new PotentialDraw("CRIT_RATE", 20, 50, false, false),
                        new PotentialDraw("DMG", 100, 50, false, false)),
                List.of(new PotentialDraw("DMG", 100, 50, false, false)),
                List.of());
        PotentialDraw[] curDraws = { new PotentialDraw("CRIT_RATE", 20, 50, false, false), null, null };

        // 잠금 없음(범위 없음) → 0번 슬롯의 크확이 더 나은 뎀으로 교체됨(크확 버려짐)
        CubeRecommender.EquipOpts free = CubeRecommender.analyze(m, current, slots, curDraws, "x", true, 1.0);
        checkTrue("lock.freeDropsCrit", !topShownHasStat(free, "CRIT_RATE"));

        // 0번 슬롯 잠금 → 크확 고정 → 추천이 크확 유지
        CubeRecommender.EquipOpts locked = CubeRecommender.analyze(
                m, current, slots, curDraws, "x", true, 1.0, Map.of(), new boolean[]{true, false, false});
        checkTrue("lock.keepsCrit", topShownHasStat(locked, "CRIT_RATE"));
    }

    /** ranges.json(컨텐츠별) 저장/로드 라운드트립 + enabledOffsets 필터 + 구버전 마이그레이션. */
    private static void testRangeIO() throws Exception {
        java.nio.file.Path tmp = Files.createTempDirectory("mekicalc-range");
        Map<String, Map<String, RangeIO.Range>> all = new LinkedHashMap<>();
        Map<String, RangeIO.Range> cube = new LinkedHashMap<>();
        cube.put("CRIT_RATE", new RangeIO.Range(true, -10, -5));
        cube.put("ATK_SPEED", new RangeIO.Range(false, 0, 20));
        all.put(RangeIO.CUBE, cube);
        RangeIO.save(tmp, all);

        Map<String, RangeIO.Range> loaded = RangeIO.load(tmp, RangeIO.CUBE);
        checkTrue("range.io.critEnabled", loaded.get("CRIT_RATE").enabled());
        check("range.io.critLo", loaded.get("CRIT_RATE").lo(), -10);
        check("range.io.critHi", loaded.get("CRIT_RATE").hi(), -5);
        checkTrue("range.io.speedDisabled", !loaded.get("ATK_SPEED").enabled());

        Map<String, double[]> off = RangeIO.enabledOffsets(tmp, RangeIO.CUBE);
        checkTrue("range.io.enabledOnly", off.containsKey("CRIT_RATE") && !off.containsKey("ATK_SPEED"));
        check("range.io.offLo", off.get("CRIT_RATE")[0], -10);

        // 제한없음(±무한대) 라운드트립 + 엔진 오프셋
        Map<String, Map<String, RangeIO.Range>> all2 = new LinkedHashMap<>();
        Map<String, RangeIO.Range> cube2 = new LinkedHashMap<>();
        cube2.put("DEF_PEN", new RangeIO.Range(true, 0, 10, true, false));   // 하한 없음 ~ +10
        all2.put(RangeIO.CUBE, cube2);
        RangeIO.save(tmp, all2);
        RangeIO.Range pen = RangeIO.load(tmp, RangeIO.CUBE).get("DEF_PEN");
        checkTrue("range.io.noLow", pen.noLow() && !pen.noHigh());
        double[] po = RangeIO.enabledOffsets(tmp, RangeIO.CUBE).get("DEF_PEN");
        checkTrue("range.io.infLo", po[0] == Double.NEGATIVE_INFINITY);
        check("range.io.infHi", po[1], 10);

        // 구버전 플랫 형식 → cube로 마이그레이션
        Files.writeString(tmp.resolve(RangeIO.FILE),
                "{ \"CRIT_RATE\": { \"enabled\": true, \"lo\": 0, \"hi\": 0 } }");
        checkTrue("range.io.migrate", RangeIO.load(tmp, RangeIO.CUBE).get("CRIT_RATE").enabled());

        Files.deleteIfExists(tmp.resolve(RangeIO.FILE));
        Files.deleteIfExists(tmp);
    }

    /** 큐브 프리셋 저장/로드 라운드트립(부위·슬롯 보존). */
    private static void testCubePresetIO() throws Exception {
        java.nio.file.Path tmp = Files.createTempDirectory("mekicalc-cubepreset");
        var up = new mekicalc.config.CubeStateIO.Line("MYTHIC", new mekicalc.config.CubeStateIO.Slot[]{
                new mekicalc.config.CubeStateIO.Slot("DMG", 35), null,
                new mekicalc.config.CubeStateIO.Slot("FINAL_DMG", 12)});
        Map<String, mekicalc.config.CubeStateIO.PartCube> cubesA = new LinkedHashMap<>();
        cubesA.put("장갑", new mekicalc.config.CubeStateIO.PartCube(up, null));
        Map<String, mekicalc.config.CubeStateIO.PartCube> cubesB = new LinkedHashMap<>();

        // 프리셋마다 추천 옵션 수치 범위를 따로 보관 → 라운드트립 보존 확인
        Map<String, RangeIO.Range> rangesA = new LinkedHashMap<>();
        rangesA.put("CRIT_RATE", new RangeIO.Range(true, -10, 5, false, true));

        java.util.List<mekicalc.config.CubePresetIO.CubePreset> presets = new ArrayList<>();
        presets.add(new mekicalc.config.CubePresetIO.CubePreset("기본", cubesA, rangesA));
        presets.add(new mekicalc.config.CubePresetIO.CubePreset("보스", cubesB, new LinkedHashMap<>()));
        mekicalc.config.CubePresetIO.save(tmp, new mekicalc.config.CubePresetIO.Store(presets, "보스"));

        mekicalc.config.CubePresetIO.Store loaded = mekicalc.config.CubePresetIO.load(tmp);
        checkTrue("cubepreset.count", loaded.presets().size() == 2);
        checkTrue("cubepreset.active", "보스".equals(loaded.active()));
        mekicalc.config.CubeStateIO.PartCube pc = findCubePreset(loaded, "기본").cubes().get("장갑");
        checkTrue("cubepreset.slot0", pc.upper().slots()[0].stat().equals("DMG"));
        check("cubepreset.slot0v", pc.upper().slots()[0].value(), 35);
        checkTrue("cubepreset.slot1null", pc.upper().slots()[1] == null);
        RangeIO.Range rr = findCubePreset(loaded, "기본").ranges().get("CRIT_RATE");
        checkTrue("cubepreset.range.enabled", rr.enabled());
        check("cubepreset.range.lo", rr.lo(), -10);
        checkTrue("cubepreset.range.noHigh", rr.noHigh());
        checkTrue("cubepreset.range.empty", findCubePreset(loaded, "보스").ranges().isEmpty());
        Files.deleteIfExists(tmp.resolve(mekicalc.config.CubePresetIO.FILE));
        Files.deleteIfExists(tmp);
    }

    private static mekicalc.config.CubePresetIO.CubePreset findCubePreset(
            mekicalc.config.CubePresetIO.Store s, String name) {
        for (mekicalc.config.CubePresetIO.CubePreset p : s.presets()) if (p.name().equals(name)) return p;
        throw new IllegalStateException("큐브 프리셋 없음: " + name);
    }

    /** 공속: 1타 데미지엔 미포함, 효율/비교엔 DPS 배수(1+공속/100)로 반영. 누적은 DIMINISHING(cap150). */
    private static void testAttackSpeed() {
        Map<String, StatDef> stats = makeStats(
                new StatDef("ATK_SPEED", "공속", "PCT", StatDef.Combine.DIMINISHING, 150, false));
        DamageModel m = new DamageModel(stats, neutral());
        // 1타 데미지엔 공속 미포함 → 그대로 100
        check("speed.perHit", m.finalDamage(baseBlock(100).with("ATK_SPEED", 50)), 100.0);
        // 현재 공속 0에서 +50 추가(DIMINISHING: 0+(150-0)×50/150=50) → DPS배수 1.5 → 효율 +50%
        check("speed.dpsInc", m.increase(baseBlock(100), new StatBlock().with("ATK_SPEED", 50)), 0.5);
        // 누적 체감: 현재 75에서 +75 추가 → 75+(150-75)×75/150 = 112.5 (DPS배수 2.125)
        check("speed.diminish",
                m.combine(baseBlock(100).with("ATK_SPEED", 75), new StatBlock().with("ATK_SPEED", 75)).get("ATK_SPEED"),
                112.5);
    }

    private static void testFinalAndSkillLevels() {
        DamageModel m = new DamageModel(Map.of(), neutral()); // 기본공격계수 중립(1.0)
        // 최종 100% → (1+1)=2 → 200
        check("final", m.finalDamage(baseBlock(100).with("FINAL_DMG", 100)), 200.0);

        // 기본 공격 계수 = 2.9 × (1 + 0.004 × 스킬레벨), 결과를 그대로 곱함
        DamageModel s = new DamageModel(Map.of(), new Profile(0, 2.9, 0.004, false));
        check("skill.lv0", s.finalDamage(baseBlock(100)), 290.0);                      // 2.9
        check("skill.lv50", s.finalDamage(baseBlock(100).with("SKILL_LEVELS", 50)), 348.0); // 2.9×1.2

        // 일반/보스 따로 계산, 스킬데미지%는 미적용
        DamageModel m2 = new DamageModel(Map.of(), neutral());
        StatBlock t = baseBlock(100).with("NORMAL_DMG", 50).with("BOSS_DMG", 200);
        check("target.normal", m2.finalDamageNormal(t), 150.0);  // 100×1.5
        check("target.boss", m2.finalDamageBoss(t), 300.0);      // 100×3
        check("target.default", m2.finalDamage(t), 150.0);       // 기본=일반
        check("skilldmg.ignored", m2.finalDamage(baseBlock(100).with("SKILL_DMG", 500)), 100.0);
    }

    /**
     * 레벨/전직 차수 기반 유효 스킬 레벨 → 기본 공격 계수.
     * 계수 = 2.9 × (1 + 0.004 × (레벨로인한현재차수스킬 + 현재차수스킬레벨 + 모든스킬레벨)).
     */
    private static void testSkillLevelByTier() {
        Map<String, StatDef> stats = makeStats(
                new StatDef("LEVEL", "레벨", "LEVEL", StatDef.Combine.SUM, 0, false),
                new StatDef("SKILL_LEVELS", "모든스킬", "LEVEL", StatDef.Combine.SUM, 0, false),
                new StatDef("SKILL_LEVEL_1", "1차", "LEVEL", StatDef.Combine.SUM, 0, false),
                new StatDef("SKILL_LEVEL_2", "2차", "LEVEL", StatDef.Combine.SUM, 0, false),
                new StatDef("SKILL_LEVEL_3", "3차", "LEVEL", StatDef.Combine.SUM, 0, false),
                new StatDef("SKILL_LEVEL_4", "4차", "LEVEL", StatDef.Combine.SUM, 0, false));
        DamageModel m = new DamageModel(stats, new Profile(0, 2.9, 0.004, false));

        // 미전직(레벨 5): 스킬 레벨 의미 없음 → 290%
        check("skill.tier.preJob", m.finalDamage(baseBlock(100).with("LEVEL", 5)), 290.0);
        // 막 전직(레벨 100, 4차): 현재차수 스킬 0 → 290%
        check("skill.tier.justAdvanced", m.finalDamage(baseBlock(100).with("LEVEL", 100)), 290.0);
        // 4차 레벨 110: 3×(110-100)=30 → 2.9×(1+0.004×30)=2.9×1.12=3.248 → 324.8
        check("skill.tier.lv110", m.finalDamage(baseBlock(100).with("LEVEL", 110)), 324.8);
        // 4차 레벨 110 + 4차 스킬 20 + 모든 스킬 5 → 30+20+5=55 → 2.9×1.22=3.538 → 353.8
        check("skill.tier.lv110bonus", m.finalDamage(baseBlock(100)
                .with("LEVEL", 110).with("SKILL_LEVEL_4", 20).with("SKILL_LEVELS", 5)), 353.8);
        // 2차 레벨 45: 3×(45-30)=45 → +2차 스킬 10 = 55 → 353.8
        check("skill.tier.2nd", m.finalDamage(baseBlock(100)
                .with("LEVEL", 45).with("SKILL_LEVEL_2", 10)), 353.8);
        // 차수 격리: 2차에서 4차 스킬은 무시 → 레벨분 45만 → 2.9×1.18=3.422 → 342.2
        check("skill.tier.isolated", m.finalDamage(baseBlock(100)
                .with("LEVEL", 45).with("SKILL_LEVEL_4", 100)), 342.2);
    }

    private static void testDamageRange() {
        DamageModel m = new DamageModel(Map.of(), neutral());
        // 최소80 최대160 → (80+160)/200 = 1.2 → 120
        check("range.avg", m.finalDamage(
                new StatBlock().with("ATK_FLAT", 100).with("MIN_DMG_MULT", 80).with("MAX_DMG_MULT", 160)), 120.0);
        // 최소150 > 최대120 → 최댓값(천장)으로 고정 = 120/100 = 1.2 → 120
        check("range.fixed", m.finalDamage(
                new StatBlock().with("ATK_FLAT", 100).with("MIN_DMG_MULT", 150).with("MAX_DMG_MULT", 120)), 120.0);
    }

    // ---- combine 규칙 ----

    private static void testCombineModes() {
        Map<String, StatDef> stats = makeStats(
                new StatDef("ATK_PCT", "공%", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("ATK_SPEED", "공속", "PCT", StatDef.Combine.DIMINISHING, 150, false),
                new StatDef("DEF_PEN", "방관", "PCT", StatDef.Combine.DIMINISHING, 100, false),
                new StatDef("FINAL_DMG", "최종", "PCT", StatDef.Combine.MULT_PCT, 0, false));
        DamageModel m = new DamageModel(stats, neutral());

        // SUM
        check("combine.sum",
                m.combine(new StatBlock().with("ATK_PCT", 100), new StatBlock().with("ATK_PCT", 50)).get("ATK_PCT"),
                150.0);
        // DIMINISHING 공속: 0 + 30 → 30, 다시 +30 → 54
        check("combine.dim1",
                m.combine(new StatBlock(), new StatBlock().with("ATK_SPEED", 30)).get("ATK_SPEED"),
                30.0);
        check("combine.dim2",
                m.combine(new StatBlock().with("ATK_SPEED", 30), new StatBlock().with("ATK_SPEED", 30)).get("ATK_SPEED"),
                54.0);
        // DIMINISHING 방관: 40 + 10 → 40 + 60*0.1 = 46
        check("combine.pen",
                m.combine(new StatBlock().with("DEF_PEN", 40), new StatBlock().with("DEF_PEN", 10)).get("DEF_PEN"),
                46.0);
        // MULT_PCT 최종: 80 + 10 → 1.8*1.1 = 1.98 → 98
        check("combine.mult",
                m.combine(new StatBlock().with("FINAL_DMG", 80), new StatBlock().with("FINAL_DMG", 10)).get("FINAL_DMG"),
                98.0);

        // 큐브 슬롯 집계(aggregate): 한 부위 안의 여러 줄을 하나의 델타값으로 모을 때의 규칙.
        // 방관(DIMINISHING)은 슬롯끼리도 곱연산(12%+12% → 22.56%, 24% 아님) — 인게임과 일치.
        check("aggregate.pen", m.aggregate("DEF_PEN", m.aggregate("DEF_PEN", 0, 12), 12), 22.56);
        // 최종뎀(MULT_PCT)·공%(SUM)은 종전처럼 슬롯끼리 단순 합산.
        check("aggregate.final", m.aggregate("FINAL_DMG", m.aggregate("FINAL_DMG", 0, 10), 10), 20.0);
        check("aggregate.sum", m.aggregate("ATK_PCT", m.aggregate("ATK_PCT", 0, 100), 50), 150.0);
        // 방관 25.3% 기존 + 큐브 방관 12%·12% → 25.3 ⊕ (12⊕12) = 42.15%(인게임 실측과 일치).
        check("aggregate.penWithBase",
                m.combine(new StatBlock().with("DEF_PEN", 25.3),
                        new StatBlock().with("DEF_PEN", m.aggregate("DEF_PEN", m.aggregate("DEF_PEN", 0, 12), 12))).get("DEF_PEN"),
                42.15232);
    }

    /** uncombine = combine 역연산: combine(cur,delta)에서 delta를 제거하면 cur 복원(공격력 가산 포함). */
    private static void testUncombine() {
        Map<String, StatDef> stats = makeStats(
                new StatDef("ATK_FLAT", "공격력", "FLAT", StatDef.Combine.SUM, 0, false),
                new StatDef("MAIN_STAT_FLAT", "주스탯깡", "FLAT", StatDef.Combine.SUM, 0, false),
                new StatDef("MAIN_STAT_PCT", "주스탯%", "PCT", StatDef.Combine.SUM, 0, false),
                new StatDef("FINAL_DMG", "최종", "PCT", StatDef.Combine.MULT_PCT, 0, false),
                new StatDef("ATK_SPEED", "공속", "PCT", StatDef.Combine.DIMINISHING, 150, false),
                new StatDef("DMG", "데미지", "PCT", StatDef.Combine.SUM, 0, false));
        DamageModel m = new DamageModel(stats, neutral());
        StatBlock cur = baseBlock(627413)
                .with("MAIN_STAT_FLAT", 34741).with("MAIN_STAT_PCT", 233)
                .with("FINAL_DMG", 65).with("ATK_SPEED", 40).with("DMG", 600);
        StatBlock[] deltas = {
                new StatBlock().with("MAIN_STAT_PCT", 15),
                new StatBlock().with("MAIN_STAT_FLAT", 1000),
                new StatBlock().with("FINAL_DMG", 12),
                new StatBlock().with("ATK_SPEED", 30),
                new StatBlock().with("DMG", 25).with("MAIN_STAT_PCT", 10),
                new StatBlock().with("ATK_FLAT", 500).with("MAIN_STAT_FLAT", 200),
        };
        List<String> ids = List.of("ATK_FLAT", "MAIN_STAT_FLAT", "MAIN_STAT_PCT", "FINAL_DMG", "ATK_SPEED", "DMG");
        for (int i = 0; i < deltas.length; i++) {
            StatBlock back = m.uncombine(m.combine(cur, deltas[i]), deltas[i]);
            boolean ok = true;
            for (String id : ids) if (Math.abs(back.get(id) - cur.get(id)) > 1e-6) ok = false;
            checkTrue("uncombine.roundtrip" + i, ok);
        }
        // 교체 시나리오: 옵션을 빼면 output(DPS 포함)이 정확히 원복
        StatBlock d = new StatBlock().with("MAIN_STAT_PCT", 15).with("FINAL_DMG", 10);
        check("uncombine.output", m.output(m.uncombine(m.combine(cur, d), d)), m.output(cur));
    }

    private static void testAdditiveVsMultiplicative() {
        StatBlock b = baseBlock(100).with("DMG", 100).with("BASIC_ATK_DMG", 100);
        // mode A: 데미지=2, 기본공격뎀=2 → 100×2×2 = 400
        DamageModel a = new DamageModel(Map.of(), new Profile(0, 1.0, 0.0, false));
        check("modeA", a.finalDamage(b), 400.0);
        // mode B: sum = 데미지 100 + 기본공격뎀 100 = 200% → 블록 3 → 100×3 = 300
        DamageModel bb = new DamageModel(Map.of(), new Profile(0, 1.0, 0.0, true));
        check("modeB", bb.finalDamage(b), 300.0);
    }

    private static void testIncreaseEndToEnd() {
        Map<String, StatDef> stats = makeStats(
                new StatDef("FINAL_DMG", "최종", "PCT", StatDef.Combine.MULT_PCT, 0, false));
        DamageModel m = new DamageModel(stats, neutral());
        StatBlock cur = baseBlock(100);                 // finalDamage 100
        StatBlock delta = new StatBlock().with("FINAL_DMG", 100); // → fFinal 2 → 200
        check("increase", m.increase(cur, delta), 1.0); // +100%
    }

    private static void testLoadRealDataConsistency() throws Exception {
        GameData g = GameData.load(Path.of("data"));
        // character.json은 유저가 편집하므로 값-무관 검증: 양수·유한 + 분해 곱 == 최종값
        double normal = g.model.finalDamageNormal(g.current);
        double boss = g.model.finalDamageBoss(g.current);
        checkTrue("realdata.positive", normal > 0 && boss > 0 && Double.isFinite(normal) && Double.isFinite(boss));
        // 공통배수 × (1 + 일반몹데미지/100) == 일반몹 데미지
        double prod = 1.0;
        for (double f : g.model.breakdown(g.current).values()) prod *= f;
        check("realdata.breakdownProduct", prod * (1 + g.current.get("NORMAL_DMG") / 100.0), normal);

        Recommender rec = new Recommender(g.model, g.sources, g.currencyValues);
        for (AcquisitionOption o : rec.byEfficiency(g.current)) {
            // 증가량은 유한·음수 아님(크리확률 상한 초과 등으로 0일 수 있음 — 정상).
            checkTrue("realdata.posInc:" + o.sourceId + ":" + o.label,
                    Double.isFinite(o.efficiency()) && o.damageIncrease >= 0);
        }
    }

    /** 장비 잠재 생성기가 사용자가 붙여준 실제 표 숫자를 재현하는지 대조 검증. */
    private static void testEquipmentPotential() throws Exception {
        EquipmentPotential ep = EquipmentPotential.load(Path.of("data"));

        // 미스틱 장갑(그외 그룹, 특옵 CRIT_DMG) 2슬롯
        List<PotentialDraw> g2 = ep.slots("장갑", "MYTHIC").get(1);
        check("eq.dmg.w", prob(g2, "DMG", 25, false), 3.04);
        check("eq.dmg.y", prob(g2, "DMG", 35, false), 0.96);
        check("eq.crit.w", prob(g2, "CRIT_RATE", 12, false), 1.9);
        check("eq.crit.y", prob(g2, "CRIT_RATE", 15, false), 0.6);
        check("eq.mainflat.w", prob(g2, "MAIN_STAT_FLAT", 600, false), 7.03);
        check("eq.mainflat.y", prob(g2, "MAIN_STAT_FLAT", 1000, false), 2.22);
        check("eq.special.w", prob(g2, "CRIT_DMG", 30, true), 0.76);
        check("eq.special.y", prob(g2, "CRIT_DMG", 50, true), 0.24);
        // 부스탯+/부스탯%는 주스탯+/주스탯%와 확률·수치 완전 동일(모든 부위·등급·슬롯) — 큐브 대칭 불변식
        check("eq.substat.flat.w", prob(g2, "SUB_STAT_FLAT", 600, false), prob(g2, "MAIN_STAT_FLAT", 600, false));
        check("eq.substat.flat.y", prob(g2, "SUB_STAT_FLAT", 1000, false), prob(g2, "MAIN_STAT_FLAT", 1000, false));
        check("eq.substat.pct.w", prob(g2, "SUB_STAT_PCT", 12, false), prob(g2, "MAIN_STAT_PCT", 12, false));
        check("eq.substat.pct.y", prob(g2, "SUB_STAT_PCT", 15, false), prob(g2, "MAIN_STAT_PCT", 15, false));

        // 에픽 하의(첫 특옵 등급 → 노란만) 2슬롯
        List<PotentialDraw> e2 = ep.slots("하의", "EPIC").get(1);
        check("eq.firstSpecial.y", prob(e2, "FINAL_DMG", 3, true), 0.24);
        checkTrue("eq.firstSpecial.noWhite", countSpecial(e2) == 1);
        check("eq.epic.mainflat.w", prob(e2, "MAIN_STAT_FLAT", 100, false), 7.22); // 흰 특옵 없어 미차감
        check("eq.epic.mainflat.y", prob(e2, "MAIN_STAT_FLAT", 200, false), 2.22); // 노란만 차감

        // 노말(3슬롯 동일, 특옵 없음)
        List<PotentialDraw> n0 = ep.slots("장갑", "NORMAL").get(0);
        check("eq.normal.dmg", prob(n0, "DMG", 5, false), 4.0);
        check("eq.normal.mainflat", prob(n0, "MAIN_STAT_FLAT", 50, false), 9.5);
        checkTrue("eq.normal.noSpecial", countSpecial(n0) == 0);

        // 상의(특옵 유니크부터) 미스틱 1슬롯: 특옵 노란만, 주스탯+ 1슬롯 카브
        List<PotentialDraw> top1 = ep.slots("상의", "MYTHIC").get(0);
        check("eq.top.special1", prob(top1, "BASIC_ATK_TARGETS", 3, true), 1.0);
        check("eq.top.mainflat1", prob(top1, "MAIN_STAT_FLAT", 1000, false), 9.25);

        // 어깨장식 유니크(첫 특옵 등급) 2슬롯: DEF_PEN 노란만 0.24
        List<PotentialDraw> sh2 = ep.slots("어깨장식", "UNIQUE").get(1);
        check("eq.shoulder.special", prob(sh2, "DEF_PEN", 8, true), 0.24);
    }

    /** 큐브 기댓값 수학 검증: 노말 장갑 데미지5는 3슬롯 각 4% → P=1-0.96^3, 기댓값 1/P. */
    private static void testCubeMath() throws Exception {
        GameData g = GameData.load(Path.of("data"));
        EquipmentPotential ep = EquipmentPotential.load(Path.of("data"));
        List<CubeEvaluator.Result> res = CubeEvaluator.evaluate(ep, g.model, g.current, "장갑", "NORMAL");

        CubeEvaluator.Result dmg = findRes(res, "DMG", 5);
        double p = 1.0 - Math.pow(1.0 - 0.04, 3);
        check("cube.p", dmg.pPerCube(), p);
        check("cube.expected", dmg.expectedCubes(), 1.0 / p);
        checkTrue("cube.dmg.numeric", !dmg.qualitative());

        // appearProb(한계 등장확률)가 CubeEvaluator.pPerCube와 일치 — 전 경로(기댓값·옵션비교·추천) 일관성.
        check("appearProb.matchesEvaluator",
                CubeRecommender.appearProb(List.of(new PotentialDraw("DMG", 5, 0, false, false)),
                        ep.slots("장갑", "NORMAL")), p);

        // 사용자 보고 버그: 특옵 노란 한 줄(상의 MYTHIC, 슬롯 1.0/0.24/0.08%).
        //   한계확률(정답) = 1−(0.99)(0.9976)(0.9992) ≈ 1.317%.  arrangeSum(정확구성)은 ≈0.39%로 과소.
        List<List<PotentialDraw>> topM = ep.slots("상의", "MYTHIC");
        List<PotentialDraw> sp = List.of(new PotentialDraw("BASIC_ATK_TARGETS", 3, 0, false, false));
        double spAppear = CubeRecommender.appearProb(sp, topM);
        check("appearProb.special", spAppear, 1 - (1 - 0.01) * (1 - 0.0024) * (1 - 0.0008));
        checkTrue("appearProb.special.gtArrange", spAppear > CubeRecommender.arrangeSum(sp, topM) * 2);

        // 공속은 이제 정성적 아님 → DPS로 딜 환산됨(양수)
        CubeEvaluator.Result spd = findRes(res, "ATK_SPEED", 3);
        checkTrue("cube.speed.numeric", !spd.qualitative() && spd.damageIncrease() > 0);
    }

    /** 유저 스탯 저장/로드 라운드트립(임시 폴더). */
    private static void testCharacterIO() throws Exception {
        java.nio.file.Path tmp = Files.createTempDirectory("mekicalc-test");
        StatBlock b = new StatBlock().with("ATK_FLAT", 123).with("FINAL_DMG", 45);
        CharacterIO.save(tmp, b, List.of("ATK_FLAT", "FINAL_DMG", "DMG"));
        StatBlock loaded = CharacterIO.load(tmp);
        check("chario.atk", loaded.get("ATK_FLAT"), 123);
        check("chario.final", loaded.get("FINAL_DMG"), 45);
        check("chario.zero", loaded.get("DMG"), 0); // 0도 기록되어 로드됨
        Files.deleteIfExists(tmp.resolve("character.json"));
        Files.deleteIfExists(tmp);
    }

    /** 스탯 프리셋 저장/로드 + character.json 마이그레이션 라운드트립(임시 폴더). */
    private static void testPresetIO() throws Exception {
        java.nio.file.Path tmp = Files.createTempDirectory("mekicalc-preset");
        List<String> order = List.of("ATK_FLAT", "BOSS_DMG", "DMG");

        // 1) presets.json 없음 + character.json 있음 → "기본" 1개로 마이그레이션
        CharacterIO.save(tmp, new StatBlock().with("ATK_FLAT", 555), order);
        var migrated = mekicalc.config.PresetIO.load(tmp);
        checkTrue("preset.migrate.one", migrated.presets().size() == 1);
        checkTrue("preset.migrate.active", migrated.active().equals(migrated.presets().get(0).name()));
        check("preset.migrate.stat", migrated.presets().get(0).stats().get("ATK_FLAT"), 555);

        // 2) 여러 프리셋 저장 → 재로드 시 개수·활성·스탯·0값 보존
        List<mekicalc.config.PresetIO.Preset> list = new ArrayList<>();
        list.add(new mekicalc.config.PresetIO.Preset("기본", new StatBlock().with("ATK_FLAT", 100)));
        list.add(new mekicalc.config.PresetIO.Preset("보스", new StatBlock().with("ATK_FLAT", 200).with("BOSS_DMG", 300)));
        mekicalc.config.PresetIO.save(tmp, new mekicalc.config.PresetIO.Store(list, "보스"), order);
        checkTrue("preset.exists", mekicalc.config.PresetIO.exists(tmp));

        var r = mekicalc.config.PresetIO.load(tmp);
        checkTrue("preset.count", r.presets().size() == 2);
        checkTrue("preset.active", r.active().equals("보스"));
        check("preset.boss.boss", findPreset(r, "보스").stats().get("BOSS_DMG"), 300);
        check("preset.base.atk", findPreset(r, "기본").stats().get("ATK_FLAT"), 100);
        check("preset.base.zero", findPreset(r, "기본").stats().get("DMG"), 0); // 0도 기록·로드

        // 3) 알 수 없는 active는 첫 프리셋으로 폴백
        mekicalc.config.PresetIO.save(tmp, new mekicalc.config.PresetIO.Store(list, "없는이름"), order);
        check("preset.activeFallback", 0,
                mekicalc.config.PresetIO.load(tmp).active().equals("기본") ? 0 : 1);

        Files.deleteIfExists(tmp.resolve(mekicalc.config.PresetIO.FILE));
        Files.deleteIfExists(tmp.resolve("character.json"));
        Files.deleteIfExists(tmp);
    }

    private static mekicalc.config.PresetIO.Preset findPreset(mekicalc.config.PresetIO.Store s, String name) {
        for (mekicalc.config.PresetIO.Preset p : s.presets()) if (p.name().equals(name)) return p;
        throw new IllegalStateException("프리셋 없음: " + name);
    }

    private static CubeEvaluator.Result findRes(List<CubeEvaluator.Result> res, String stat, double value) {
        for (CubeEvaluator.Result r : res) {
            if (r.statId().equals(stat) && Math.abs(r.value() - value) < 1e-9) return r;
        }
        throw new IllegalStateException("결과 없음: " + stat + " " + value);
    }

    private static double prob(List<PotentialDraw> slot, String stat, double value, boolean special) {
        for (PotentialDraw d : slot) {
            if (d.statId().equals(stat) && Math.abs(d.value() - value) < 1e-9 && d.special() == special) {
                return d.prob();
            }
        }
        return -999; // 못 찾으면 확실히 실패
    }

    private static int countSpecial(List<PotentialDraw> slot) {
        int c = 0;
        for (PotentialDraw d : slot) if (d.special()) c++;
        return c;
    }

    /** 내 큐브 옵션(윗잠+아랫잠) 저장/로드 라운드트립(비움 슬롯 포함). */
    private static void testCubeStateIO() throws Exception {
        java.nio.file.Path tmp = Files.createTempDirectory("mekicalc-cube");
        var up = new mekicalc.config.CubeStateIO.Line("MYTHIC", new mekicalc.config.CubeStateIO.Slot[]{
                new mekicalc.config.CubeStateIO.Slot("DMG", 35), null,
                new mekicalc.config.CubeStateIO.Slot("FINAL_DMG", 12)},
                new boolean[]{true, false, false});   // 0번 슬롯 잠금
        var lo = new mekicalc.config.CubeStateIO.Line("UNIQUE", new mekicalc.config.CubeStateIO.Slot[]{
                null, new mekicalc.config.CubeStateIO.Slot("CRIT_DMG", 20), null});
        Map<String, mekicalc.config.CubeStateIO.PartCube> m = new java.util.LinkedHashMap<>();
        m.put("장갑", new mekicalc.config.CubeStateIO.PartCube(up, lo));
        mekicalc.config.CubeStateIO.save(tmp, m);

        Map<String, mekicalc.config.CubeStateIO.PartCube> loaded = mekicalc.config.CubeStateIO.load(tmp);
        mekicalc.config.CubeStateIO.PartCube pc = loaded.get("장갑");
        checkTrue("cubestate.upGrade", "MYTHIC".equals(pc.upper().grade()));
        checkTrue("cubestate.upSlot0", pc.upper().slots()[0].stat().equals("DMG"));
        check("cubestate.upSlot0v", pc.upper().slots()[0].value(), 35);
        checkTrue("cubestate.upSlot1null", pc.upper().slots()[1] == null);
        checkTrue("cubestate.upLock0", pc.upper().lockedAt(0));        // 잠금 라운드트립
        checkTrue("cubestate.upLock1", !pc.upper().lockedAt(1));
        checkTrue("cubestate.loGrade", "UNIQUE".equals(pc.lower().grade()));
        checkTrue("cubestate.loSlot1", pc.lower().slots()[1].stat().equals("CRIT_DMG"));
        Files.deleteIfExists(tmp.resolve(mekicalc.config.CubeStateIO.FILE));
        Files.deleteIfExists(tmp);
    }

    /** 첫 실행 판별: 마커 없으면 false, 저장 후 true. */
    private static void testFirstRunMarker() throws Exception {
        java.nio.file.Path tmp = Files.createTempDirectory("mekicalc-fr");
        checkTrue("firstrun.none", !CharacterIO.isUserEntered(tmp));
        CharacterIO.save(tmp, new StatBlock().with("ATK_FLAT", 1), List.of("ATK_FLAT"));
        checkTrue("firstrun.saved", CharacterIO.isUserEntered(tmp));
        Files.deleteIfExists(tmp.resolve("character.json"));
        Files.deleteIfExists(tmp);
    }

    /** 비교 화면 로직: 옵션 줄 선택 → 딜 증가량. 한 장비 내 최종뎀은 줄끼리 합연산 후 곱연산, 정성적·빈슬롯은 0. */
    private static void testCompareDraws() throws Exception {
        AppModel m = new AppModel(Path.of("data"));
        // 최종뎀 +10 두 줄 → 줄끼리 먼저 합산(10+10=20) → 현재에 ×1.20 → 정확히 +20%
        // (곱연산 누적 1.1×1.1=1.21이 아니라, 한 장비 내 큐브 줄은 합연산이 먼저)
        double finalStack = m.increaseForDraws(List.of(
                new PotentialDraw("FINAL_DMG", 10, 0, false, true),
                new PotentialDraw("FINAL_DMG", 10, 0, false, true)));
        check("compare.finalStack", finalStack, 0.20);
        // 방관(정성적, 데미지 미환산) → 0
        check("compare.qual0", m.increaseForDraws(List.of(
                new PotentialDraw("DEF_PEN", 10, 0, false, true))), 0.0);
        // 빈 슬롯(null) → 0
        check("compare.empty0", m.increaseForDraws(java.util.Arrays.asList(
                (PotentialDraw) null, null, null)), 0.0);
        // 노란/흰 색 플래그: 미스틱 장갑 2슬롯 데미지 35=노란, 25=흰
        List<PotentialDraw> slot2 = m.slotDraws("장갑", "MYTHIC").get(1);
        checkTrue("compare.yellow", findDraw(slot2, "DMG", 35).yellow());
        checkTrue("compare.white", !findDraw(slot2, "DMG", 25).yellow());
    }

    private static PotentialDraw findDraw(List<PotentialDraw> slot, String stat, double value) {
        for (PotentialDraw d : slot) {
            if (d.statId().equals(stat) && Math.abs(d.value() - value) < 1e-9) return d;
        }
        throw new IllegalStateException("draw 없음: " + stat + " " + value);
    }

    /** UI 모델 계층(헤드리스): 데미지·평가·스탯변경·등급라벨. */
    private static void testAppModel() throws Exception {
        AppModel m = new AppModel(Path.of("data"));
        checkTrue("ui.damage", m.currentDamage() > 0 && Double.isFinite(m.currentDamage()));

        List<CubeEvaluator.Result> res = m.evaluate("장갑", "MYTHIC");
        checkTrue("ui.eval.nonempty", !res.isEmpty());
        checkTrue("ui.eval.sortedTop", res.get(0).damageIncrease() >= 0.10); // 주스탯+1000 ~+29%

        double before = m.currentDamage();
        m.setValue("FINAL_DMG", m.value("FINAL_DMG") + 50);
        checkTrue("ui.setvalue", m.currentDamage() > before);

        checkTrue("ui.grades", m.gradeLabels().contains("미스틱"));
        checkTrue("ui.gradeCode", "MYTHIC".equals(m.gradeLabelToCode().get("미스틱")));
    }

    private static void testJsonParser() {
        Map<String, Object> m = Json.asMap(Json.parse(
                "{ \"a\": 1, \"b\": [true, false, null, \"x\"], \"c\": {\"d\": -2.5e1} }"));
        check("json.a", Json.num(m, "a"), 1.0);
        List<Object> b = Json.asList(m.get("b"));
        checkTrue("json.b0", Boolean.TRUE.equals(b.get(0)));
        checkTrue("json.b2null", b.get(2) == null);
        checkTrue("json.b3x", "x".equals(b.get(3)));
        check("json.c.d", Json.num(Json.asMap(m.get("c")), "d"), -25.0);
    }

    // ---- 헬퍼 ----

    /** 배수 항목이 모두 1이 되도록 데미지범위만 중립(100/100)으로 채운 기본 블록. */
    private static StatBlock baseBlock(double atkFlat) {
        return new StatBlock()
                .with("ATK_FLAT", atkFlat)
                .with("MIN_DMG_MULT", 100)
                .with("MAX_DMG_MULT", 100);
    }

    private static Profile neutral() {
        // skillRatio=0, bossRatio=0, enemyDefense=0, 기본공격계수 base=1·perLv=0(중립), modeA
        return new Profile(0, 1.0, 0.0, false);
    }

    private static Map<String, StatDef> makeStats(StatDef... defs) {
        Map<String, StatDef> m = new LinkedHashMap<>();
        for (StatDef d : defs) m.put(d.id, d);
        return m;
    }

    private static void check(String name, double actual, double expected) {
        if (Math.abs(actual - expected) <= EPS * Math.max(1.0, Math.abs(expected))) pass(name);
        else fail(name + " : 기대=" + expected + " 실제=" + actual);
    }

    private static void checkTrue(String name, boolean cond) {
        if (cond) pass(name); else fail(name + " : 조건 거짓");
    }

    private static void pass(String name) { passed++; System.out.println("  PASS  " + name); }
    private static void fail(String name) { failed++; System.out.println("  FAIL  " + name); }
}
