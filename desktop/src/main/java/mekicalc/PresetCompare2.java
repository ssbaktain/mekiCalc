package mekicalc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mekicalc.config.GameData;
import mekicalc.engine.sim2.Content2;
import mekicalc.engine.sim2.SimConfig;
import mekicalc.engine.sim2.SkillEngine2;
import mekicalc.engine.sim2.TimelineSimulator2;
import mekicalc.json.Json;
import mekicalc.model.StatBlock;

/**
 * sim2 실 프리셋 전 직업 대조: presets.json 활성 프리셋 + 챕터 방어력으로 14직업 sim2 DPS를 조/경 출력.
 * (engine.sim 기반 PresetCompare의 sim2판 — 시간제 버프 가동률·발동조건·damage 합산 반영.)
 * 사용: gradlew :desktop:presetCompare2 -Pchapter=35
 */
public final class PresetCompare2 {

    private static final String[] JOBS = {"hero", "paladin", "darkknight", "archmagetc", "archmagefp",
            "bishop", "bowmaster", "marksman", "nightlord", "shadower", "viper", "captain", "nightwalker", "windbreaker"};

    public static void main(String[] args) throws Exception {
        Map<String, Object> d = Json.asMap(Json.parse(Files.readString(Path.of("data/presets.json"))));
        String active = Json.str(d, "active");
        Map<String, Object> preset = null;
        for (Object o : Json.asList(d.get("presets"))) {
            Map<String, Object> p = Json.asMap(o);
            if (active.equals(Json.str(p, "name"))) { preset = p; break; }
        }
        if (preset == null) preset = Json.asMap(Json.asList(d.get("presets")).get(0));
        Map<String, Object> stats = Json.asMap(preset.get("stats"));

        StatBlock b = new StatBlock();
        for (Map.Entry<String, Object> e : stats.entrySet())
            if (e.getValue() instanceof Number n) b.set(e.getKey(), n.doubleValue());
        if (b.get("LEVEL") <= 0) b.set("LEVEL", 140);
        if (b.get("MIN_DMG_MULT") == 0) b.set("MIN_DMG_MULT", 100);
        if (b.get("MAX_DMG_MULT") == 0) b.set("MAX_DMG_MULT", 100);
        int lv = (int) b.get("LEVEL");

        int chapter = Integer.parseInt(System.getProperty("chapter", "0"));
        double bossDef = 0, huntDef = 0;
        if (chapter > 0) {
            Map<String, Object> chs = Json.asMap(Json.asMap(Json.parse(Files.readString(Path.of("data/monster_defense.json")))).get("chapters"));
            Map<String, Object> ch = Json.asMap(chs.get(String.valueOf(chapter)));
            if (ch != null) {
                bossDef = Json.num(ch, "boss", 0);
                Map<String, Object> stages = Json.asMap(ch.get("stages"));
                double sum = 0; int cnt = 0;
                if (stages != null) for (Object v : stages.values()) if (v instanceof Number nn) { sum += nn.doubleValue(); cnt++; }
                huntDef = cnt > 0 ? sum / cnt : 0;
            }
        }
        GameData g = GameData.load(Path.of("data"));
        TimelineSimulator2 sim = new TimelineSimulator2(g.model);
        // ★DPS는 단일 대상 기준(maxTargets=1). 광역 대상수를 곱하면 각 적이 받은 딜을 전부 더한 '총딜'이 되어
        //   광역기 많은 직업이 부당하게 커진다(잘못된 지표). 사냥/보스 차이는 방어력+일반몹뎀/보스뎀만.
        Content2 boss = Content2.boss1(bossDef);
        Content2 hunt = new Content2(false, huntDef, 0, 0, 0, 1.0, 1, false);
        SimConfig cfg = SimConfig.auto(90);

        String defLabel = chapter > 0 ? String.format("챕터%d(보스%.0f/사냥%.0f)", chapter, bossDef, huntDef) : "방어력0 상대지표";
        System.out.printf("=== sim2 프리셋 '%s' lv%d 전직업 DPS (%s) ===%n", active, lv, defLabel);
        System.out.printf("%-13s %14s %14s%n", "직업", "챕터보스", "챕터사냥");
        for (int i = 0; i < JOBS.length; i++) {
            String job = JOBS[i];
            List<Object> skills = new ArrayList<>(Json.asList(Json.asMap(
                    Json.parse(Files.readString(Path.of("data/skills_v2/" + job + ".json")))).get("skills")));
            SkillEngine2 eng = new SkillEngine2(skills, i + 1);
            List<String> actives = new ArrayList<>();
            for (Object o : skills) {
                Map<String, Object> s = Json.asMap(o);
                Integer open = s.get("open") == null ? null : (int) ((Number) s.get("open")).doubleValue();
                String idx = Json.str(s, "index", "");
                if ("active".equals(s.get("type")) && !idx.endsWith("010") && (open == null || open <= lv))
                    actives.add(idx);
            }
            String basic = (i + 1) * 10000 + 4010 + "";
            SkillEngine2.PlanResult pr = eng.plan(b, basic, actives);
            double db = sim.run(pr.plans(), b, boss, cfg).dps();
            double dh = sim.run(pr.plans(), b, hunt, cfg).dps();
            System.out.printf("%-13s %14s %14s%n", job, fmt(db), fmt(dh));
        }
    }

    private static String fmt(double v) {
        if (v >= 1e16) return String.format("%.2f경", v / 1e16);
        if (v >= 1e12) return String.format("%.2f조", v / 1e12);
        if (v >= 1e8) return String.format("%.3f억", v / 1e8);
        return String.format("%.3e", v);
    }
}
