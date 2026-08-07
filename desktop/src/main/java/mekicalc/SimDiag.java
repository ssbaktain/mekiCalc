package mekicalc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import mekicalc.config.GameData;
import mekicalc.engine.skill.SkillDps;
import mekicalc.engine.skill.SkillData;
import mekicalc.engine.skill.SkillDpsModel;
import mekicalc.engine.sim.Content;
import mekicalc.engine.sim.ContentProfile;
import mekicalc.engine.sim.SimResult;
import mekicalc.engine.sim.SkillDataV2;
import mekicalc.engine.sim.SkillEngineImpl;
import mekicalc.engine.sim.SkillSelection;
import mekicalc.engine.sim.TimelineSimulator;
import mekicalc.model.StatBlock;

/**
 * 시뮬 진단: 지정 직업의 신엔진 스킬별 DPS 기여(도넛 분해)를 출력한다.
 * 값 변화의 원인(어느 스킬이 낮은지·발동이 정확한지)을 스킬 단위로 추적하기 위한 개발 도구.
 * 사용: gradlew :desktop:simDiag -Pjob=darkknight -Plv=140
 */
public final class SimDiag {

    public static void main(String[] args) throws Exception {
        String job = System.getProperty("job", args.length > 0 ? args[0] : "darkknight");
        int lv = Integer.parseInt(System.getProperty("lv", args.length > 1 ? args[1] : "140"));
        GameData g = GameData.load(Path.of("data"));
        TimelineSimulator sim = new TimelineSimulator(g.model);
        ContentProfile cont = ContentProfile.plain(Content.CHAPTER_BOSS, 0, 0);
        StatBlock b = new StatBlock().set("LEVEL", lv).set("ATK_FLAT", 1_000_000)
                .set("CRIT_RATE", 100).set("CRIT_DMG", 300).set("MIN_DMG_MULT", 100).set("MAX_DMG_MULT", 100);

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
        SimResult res = sim.run(plans, b, cont, 90);

        // 구엔진 대조(비율만 참고 — 클뜯이 정답, 구엔진은 검증 도구). 미지원 직업은 신엔진만.
        SkillDps old = null;
        double oldDps = 0;
        try {
            SkillData sd = SkillData.load(Path.of("data"), job);
            old = new SkillDps(g.model, new SkillDpsModel(sd));
            oldDps = old.totalDps(b, true);
        } catch (Exception ex) {
            System.out.println("(구엔진 미지원 직업 — 신엔진만 표시: " + ex.getMessage() + ")");
        }
        double ratio = oldDps > 0 ? res.dps() / oldDps : 0;

        System.out.printf("=== %s lv%d ===%n", job, lv);
        System.out.printf("신엔진 dps=%.3e | 구엔진 dps=%.3e | ratio=%.3f%n", res.dps(), oldDps, ratio);
        System.out.println("--- 신엔진 스킬별 기여 (dps) ---");
        res.perSkill().entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .forEach(e -> System.out.printf("  %6.2f%%  dps=%.3e  %s%n",
                        100 * e.getValue() / res.totalDamage(), e.getValue() / 90.0, e.getKey()));
        if (old != null) {
            System.out.println("--- 구엔진 스킬별 (dps) ---");
            for (Map<String, Object> row : old.dpsBySkill(b, true))
                System.out.printf("  dps=%.3e  %s%n", (double) row.get("dps"), row.get("name"));
        }
    }

    private static boolean isBasicAtk(String idx) {
        if (idx == null || idx.length() < 4) return false;
        int suf = Integer.parseInt(idx.substring(idx.length() - 4));
        return suf % 1000 == 10 && suf / 1000 >= 1 && suf / 1000 <= 4;
    }
}
