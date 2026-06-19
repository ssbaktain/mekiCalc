package mekicalc.engine.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 직업별 스킬 데이터(DPS 모델용). 현재 나이트로드만(skills_nightlord.json).
 *
 * <p>숫자/표는 JSON에, 메용 2중·DoT 가동률·레벨 구간 보정 같은 로직은 {@link SkillDpsModel}(Java)에서
 * 처리한다(하이브리드). 규칙·검산값은 메모리 스펙(mekicalc-dps-model-spec) 참조.
 */
public final class SkillData {

    /** 한 스킬의 원본 데이터. 자주 쓰는 키는 필드로, 나머지는 raw 맵에서 꺼내 쓴다. */
    public static final class Skill {
        public final String id;
        public final String name;
        public final String kind;   // basic / dealActive / dealPassiveCd / dealPassiveNoCd
        public final int tier;      // 전직 차수(1~4)
        public final int open;      // 개방 레벨
        public final Map<String, Object> raw;

        Skill(Map<String, Object> m) {
            this.raw = m;
            this.id = Json.str(m, "id");
            this.name = Json.str(m, "name", id);
            this.kind = Json.str(m, "kind", "");
            this.tier = (int) Json.num(m, "tier", 0);
            this.open = (int) Json.num(m, "open", 0);
        }

        /** raw 수치(없으면 def). */
        public double num(String key, double def) { return Json.num(raw, key, def); }
        public boolean has(String key) { return raw.containsKey(key); }
    }

    private final Map<Integer, Integer> advanceLevels;  // 차수 -> 전직레벨
    private final Map<String, Double> growth;           // kind -> 상승비(%)
    private final List<Skill> skills;
    private final List<Map<String, Object>> buffs;
    private final List<Map<String, Object>> debuffs;
    private final List<Map<String, Object>> statPassives;
    private final int meyongTier;
    private final int meyongOpen;

    private SkillData(Map<Integer, Integer> advanceLevels, Map<String, Double> growth,
                      List<Skill> skills, List<Map<String, Object>> buffs,
                      List<Map<String, Object>> debuffs, List<Map<String, Object>> statPassives,
                      int meyongTier, int meyongOpen) {
        this.advanceLevels = advanceLevels;
        this.growth = growth;
        this.skills = skills;
        this.buffs = buffs;
        this.debuffs = debuffs;
        this.statPassives = statPassives;
        this.meyongTier = meyongTier;
        this.meyongOpen = meyongOpen;
    }

    public Map<Integer, Integer> advanceLevels() { return advanceLevels; }

    /** kind의 상승비(%). 없으면 예외. */
    public double growth(String kind) {
        Double g = growth.get(kind);
        if (g == null) throw new IllegalArgumentException("알 수 없는 상승비 종류: " + kind);
        return g;
    }

    public List<Skill> skills() { return skills; }

    /** id로 스킬 찾기(없으면 null). */
    public Skill skill(String id) {
        for (Skill s : skills) if (s.id.equals(id)) return s;
        return null;
    }

    public List<Map<String, Object>> buffs() { return buffs; }
    public List<Map<String, Object>> debuffs() { return debuffs; }
    public List<Map<String, Object>> statPassives() { return statPassives; }
    public int meyongTier() { return meyongTier; }
    public int meyongOpen() { return meyongOpen; }

    /** data/skills_nightlord.json 로드. */
    public static SkillData load(Path dir) throws IOException {
        Map<String, Object> root = Json.asMap(Json.parse(Files.readString(dir.resolve("skills_nightlord.json"))));

        Map<Integer, Integer> adv = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.asMap(root.get("advanceLevels")).entrySet()) {
            adv.put(Integer.parseInt(e.getKey()), (int) (double) (Double) e.getValue());
        }

        Map<String, Double> growth = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.asMap(root.get("growth")).entrySet()) {
            growth.put(e.getKey(), (Double) e.getValue());
        }

        List<Skill> skills = new ArrayList<>();
        for (Object o : Json.asList(root.get("skills"))) skills.add(new Skill(Json.asMap(o)));

        Map<String, Object> mey = root.containsKey("meyong") ? Json.asMap(root.get("meyong")) : new LinkedHashMap<>();
        int meyongTier = (int) Json.num(mey, "tier", 4);
        int meyongOpen = (int) Json.num(mey, "open", 100);

        return new SkillData(adv, growth, skills,
                rawList(root.get("buffs")), rawList(root.get("debuffs")), rawList(root.get("statPassives")),
                meyongTier, meyongOpen);
    }

    private static List<Map<String, Object>> rawList(Object o) {
        List<Map<String, Object>> r = new ArrayList<>();
        if (o != null) for (Object x : Json.asList(o)) r.add(Json.asMap(x));
        return r;
    }
}
