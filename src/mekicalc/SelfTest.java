package mekicalc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import mekicalc.config.CharacterIO;
import mekicalc.config.GameData;
import mekicalc.engine.AcquisitionOption;
import mekicalc.engine.DamageModel;
import mekicalc.engine.Recommender;
import mekicalc.engine.equip.CubeEvaluator;
import mekicalc.engine.equip.EquipmentPotential;
import mekicalc.engine.equip.PotentialDraw;
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
        testMainStatConversion();
        testMainStatBoostsAttack();
        testCrit();
        testQualitativeStats();
        testAttackSpeed();
        testFinalAndSkillLevels();
        testDamageRange();
        testCombineModes();
        testUncombine();
        testAdditiveVsMultiplicative();
        testIncreaseEndToEnd();
        testLoadRealDataConsistency();
        testEquipmentPotential();
        testCubeMath();
        testCharacterIO();
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

    private static void testMainStatConversion() {
        DamageModel m = new DamageModel(Map.of(), neutral());
        // 주스탯깡100×(1+100%)=200. 공격력엔 주스탯 미가산(baseAtk=100). 스탯비례=1+200/10000=1.02 → 102
        StatBlock b = baseBlock(100).with("MAIN_STAT_FLAT", 100).with("MAIN_STAT_PCT", 100);
        check("mainstat", m.finalDamage(b), 102.0);
    }

    /**
     * 옵션 비교 관점: 주스탯이 "늘어나면" 스탯비례뿐 아니라 깡공(1:1)도 올라야 한다.
     * 단 raw 입력 블록의 예상 데미지는 입력 공격력에 현재 주스탯이 이미 포함이라 baseline은 그대로.
     */
    private static void testMainStatBoostsAttack() {
        DamageModel m = new DamageModel(Map.of(), neutral());
        // current: 깡공 10000, 주스탯 100(=깡100×(1+0%)) → 예상 10000×(1+100/10000)=10100
        StatBlock cur = baseBlock(10000).with("MAIN_STAT_FLAT", 100).with("MAIN_STAT_PCT", 0);
        check("mainstat.baseline", m.finalDamage(cur), 10100.0); // 입력 그대로(깡공에 주스탯 재가산 X)

        // 주스탯깡 +100 → 주스탯 200. 늘어난 100은 깡공에도 더해짐 → 10100×(1+200/10000)=10302
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

    private static void testDamageRange() {
        DamageModel m = new DamageModel(Map.of(), neutral());
        // 최소80 최대160 → (80+160)/200 = 1.2 → 120
        check("range.avg", m.finalDamage(
                new StatBlock().with("ATK_FLAT", 100).with("MIN_DMG_MULT", 80).with("MAX_DMG_MULT", 160)), 120.0);
        // 최소150 > 최대120 → 최소뎀으로 고정 = 150/100 = 1.5 → 150
        check("range.fixed", m.finalDamage(
                new StatBlock().with("ATK_FLAT", 100).with("MIN_DMG_MULT", 150).with("MAX_DMG_MULT", 120)), 150.0);
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
                new mekicalc.config.CubeStateIO.Slot("FINAL_DMG", 12)});
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
