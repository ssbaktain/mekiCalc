package mekicalc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import mekicalc.config.GameData;
import mekicalc.engine.sim.Content;
import mekicalc.engine.sim.ContentProfile;
import mekicalc.engine.sim.SkillDataV2;
import mekicalc.engine.sim.SkillEngineImpl;
import mekicalc.engine.sim.SkillSelection;
import mekicalc.engine.sim.TimelineSimulator;
import mekicalc.json.Json;
import mekicalc.model.StatBlock;

/**
 * 실 프리셋 절대값 대조: data/presets.json의 활성 프리셋 스탯으로 전 14직업 신엔진 DPS를 조/경 단위 출력.
 * golden(구엔진 이중계상)이 무의미하므로 사용자 실제 스탯 절대값으로 인게임 실측과 대조하기 위한 도구.
 * 사용: gradlew :desktop:presetCompare
 */
public final class PresetCompare {

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

        GameData g = GameData.load(Path.of("data"));
        TimelineSimulator sim = new TimelineSimulator(g.model);
        ContentProfile boss = ContentProfile.plain(Content.CHAPTER_BOSS, 0, 0);
        ContentProfile hunt = ContentProfile.plain(Content.CHAPTER_HUNT, 0, 0);

        System.out.printf("=== 프리셋 '%s' lv%d 전직업 절대 DPS (방어력0 상대지표) ===%n", active, lv);
        System.out.printf("%-13s %14s %14s%n", "직업", "챕터보스", "챕터사냥");
        for (String job : JOBS) {
            double db = simDps(g, sim, job, b, lv, boss);
            double dh = simDps(g, sim, job, b, lv, hunt);
            System.out.printf("%-13s %14s %14s%n", job, fmt(db), fmt(dh));
        }
    }

    private static String fmt(double v) {
        if (v >= 1e16) return String.format("%.2f경", v / 1e16);
        if (v >= 1e12) return String.format("%.2f조", v / 1e12);
        if (v >= 1e8) return String.format("%.3f억", v / 1e8);
        return String.format("%.3e", v);
    }

    private static double simDps(GameData g, TimelineSimulator sim, String job, StatBlock b, int lv, ContentProfile cont) throws Exception {
        SkillDataV2 dv2 = SkillDataV2.load(Files.readString(Path.of("data/skills_v2/" + job + ".json")));
        Set<String> actives = new LinkedHashSet<>();
        for (Map<String, Object> s : dv2.all()) {
            if (!"active".equals(s.get("type"))) continue;
            int op = (s.get("open") instanceof Number n) ? n.intValue() : 0;
            String idx = (String) s.get("index");
            if (op <= lv && !isBasicAtk(idx)) actives.add(idx);
        }
        String basic = dv2.creatureIndex() * 10000 + 4 * 1000 + 10 + "";
        var plans = new SkillEngineImpl(dv2).plan(b, new SkillSelection(actives, basic));
        return sim.run(plans, b, cont, 90).dps();
    }

    private static boolean isBasicAtk(String idx) {
        if (idx == null || idx.length() < 4) return false;
        int suf = Integer.parseInt(idx.substring(idx.length() - 4));
        return suf % 1000 == 10 && suf / 1000 >= 1 && suf / 1000 <= 4;
    }
}
