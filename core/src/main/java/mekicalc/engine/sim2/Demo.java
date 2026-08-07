package mekicalc.engine.sim2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mekicalc.engine.DamageModel;
import mekicalc.json.Json;
import mekicalc.model.Profile;
import mekicalc.model.StatBlock;
import mekicalc.model.StatDef;
import mekicalc.model.StatDef.Combine;

/**
 * sim2 검증 데모 — 팔라딘 vs 바이퍼(README의 3배 편차 사례)를 동일 스탯으로 시뮬레이션한다.
 *
 * <p>SCHEMA_PATCH.md의 확장 필드(counterEvery·requireState·exclusiveGroup)는 파이프라인 반영 전이므로
 * 여기서 데이터에 직접 주입해 시연한다(원문 근거 주석 참조).
 */
public final class Demo {

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        String dataDir = args.length > 0 ? args[0] : "data";
        double durSec = 300;

        Map<String, StatDef> defs = statDefs();
        DamageModel dm = new DamageModel(defs, Profile.defaults());
        TimelineSimulator2 sim = new TimelineSimulator2(dm);
        Content2 content = Content2.boss1(3000);
        SimConfig cfg = SimConfig.auto(durSec);

        double paladin = runJob(sim, dataDir + "/paladin.json", 2, "24010", defs, content, cfg, "팔라딘");
        double viper = runJob(sim, dataDir + "/viper.json", 12, "114010", defs, content, cfg, "바이퍼");

        System.out.printf("%n=== 직업 편차 ===%n팔라딘/바이퍼 DPS 비율(신엔진) = %.2f  (기존 엔진 ≈ 3.0)%n",
                paladin / viper);
    }

    private static double runJob(TimelineSimulator2 sim, String path, int creatureIndex, String basicId,
            Map<String, StatDef> defs, Content2 content, SimConfig cfg, String label) throws Exception {
        Map<String, Object> root = Json.asMap(Json.parse(Files.readString(Path.of(path))));
        List<Object> skills = new ArrayList<>(Json.asList(root.get("skills")));
        patchData(skills);   // SCHEMA_PATCH 확장 필드 주입(파이프라인 반영 전 시연)

        SkillEngine2 eng = new SkillEngine2(skills, creatureIndex);
        StatBlock stats = referenceStats();
        List<String> actives = new ArrayList<>();
        for (Object o : skills) {
            Map<String, Object> s = Json.asMap(o);
            Integer open = s.get("open") == null ? null : (int) ((Number) s.get("open")).doubleValue();
            String idx = Json.str(s, "index", "");
            boolean tierBasic = idx.endsWith("010");   // 차수 평타(강화 평타) — 평타는 basicId 하나만 사용
            if ("active".equals(s.get("type")) && !tierBasic
                    && (open == null || open <= (int) stats.get("LEVEL")))
                actives.add(idx);
        }
        SkillEngine2.PlanResult pr = eng.plan(stats, basicId, actives);
        Sim2Result r = sim.run(pr.plans(), stats, content, cfg);

        System.out.printf("%n########## %s (Lv140, 보스1, %ds) ##########%n", label, (int) cfg.durationSec());
        System.out.printf("DPS = %,.0f%n", r.dps());
        System.out.println("-- 스킬 기여 상위 --");
        r.perSkill().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(8)
                .forEach(e -> System.out.printf("  %-16s %5.1f%%  %,.0f%n",
                        e.getKey(), e.getValue() / r.total() * 100, e.getValue()));
        System.out.println("-- 버프 실측 가동률(관측값) --");
        r.buffReport().forEach((k, v) -> System.out.printf("  %-34s uptime=%5.1f%%  avg=%.1f (%s)%n",
                k, v.uptimePct(), v.avgValue(), v.stat()));
        System.out.println("-- 엔진 가정/데이터 갭(Warn) --");
        List<Warn> ws = new ArrayList<>(pr.warnings());
        ws.addAll(r.warnings());
        ws.forEach(w -> System.out.printf("  [%s] %s: %s%n", w.code(), w.skill(), w.detail()));
        return r.dps();
    }

    /**
     * SCHEMA_PATCH 확장 필드 주입(클뜯 원문 근거):
     * <ul>
     *   <li>디바인 저지먼트(24070): 원문 "기본 공격 효과 스킬을 <b>10회 사용할 때마다</b>" →
     *       counterEvery=10, counterOn=basicAttack(기본 공격 효과 근사). 현 데이터는 OnActiveSkill 100%로
     *       조건이 소실돼 있었다(상시 과대의 주범).</li>
     *   <li>어드밴스드 홀리 차지(24090): OnState의 조건 State = HolyCharge(홀리 차지 강화판).</li>
     *   <li>럭키 다이스(113060): 6면 주사위 — 6개 버프는 상호배타(1개만 발동).</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static void patchData(List<Object> skills) {
        for (Object o : skills) {
            Map<String, Object> s = Json.asMap(o);
            String idx = Json.str(s, "index", "");
            switch (idx) {
                case "24070" -> {
                    Map<String, Object> tg = Json.asMap(s.get("trigger"));
                    tg.put("counterEvery", 10.0);
                    tg.put("counterOn", "basicAttack");
                }
                case "24090" -> Json.asMap(s.get("trigger")).put("requireState", "HolyCharge");
                // 113030 서펜트스톤(SerpentStone:5+consume)·114090 서펜트어썰트(AssaultMode) requireState는
                // analyze(trigger_require_state/trigger_consume) 자동추출로 이관 — patchData 불필요.
                case "113060" -> {
                    for (Object bo : Json.asList(s.get("buff")))
                        Json.asMap(bo).put("exclusiveGroup", "LuckyDice");
                }
                default -> { }
            }
        }
    }

    /** 동일 조건 비교용 기준 스탯(두 직업 공통). */
    private static StatBlock referenceStats() {
        return new StatBlock()
                .with("LEVEL", 140)
                .with("ATK_FLAT", 1_000_000)
                .with("ATK_PCT", 100)
                .with("MAIN_STAT_FLAT", 40_000)
                .with("DMG", 50)
                .with("DMG_AMP", 20)
                .with("CRIT_RATE", 100)
                .with("CRIT_DMG", 150)
                .with("MIN_DMG_MULT", 80)
                .with("MAX_DMG_MULT", 120)
                .with("FINAL_DMG", 30)
                .with("BOSS_DMG", 80)
                .with("BASIC_ATK_DMG", 60)
                .with("SKILL_DMG", 60)
                .with("ATK_SPEED", 50)
                .with("STAT_PROP_DMG", 40);
    }

    /** 스탯 합성 규칙(넥슨 공식 1.1): 최종뎀=효과별 곱, 공속·방관·쿨감%=체감 누적, 그 외 합산. */
    private static Map<String, StatDef> statDefs() {
        Map<String, StatDef> m = new HashMap<>();
        m.put("FINAL_DMG", new StatDef("FINAL_DMG", "최종 데미지", "PCT", Combine.MULT_PCT, 0, false));
        m.put("ATK_SPEED", new StatDef("ATK_SPEED", "공격 속도", "PCT", Combine.DIMINISHING, 150, true));
        m.put("DEF_PEN", new StatDef("DEF_PEN", "방어 관통력", "PCT", Combine.DIMINISHING, 100, true));
        m.put("COOLDOWN_REDUCTION_PCT",
                new StatDef("COOLDOWN_REDUCTION_PCT", "재사용 감소%", "PCT", Combine.DIMINISHING, 100, true));
        return m;
    }
}
