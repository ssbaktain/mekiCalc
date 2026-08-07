package mekicalc.engine.sim;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * skills_v2/{job}.json 로더(5분류 스킬 정본). raw Map 형태로 스킬을 보관하고 id로 조회한다.
 * 스킬 스펙 산출(계수·쿨·버프)은 {@link SkillEngineImpl}이 담당한다.
 *
 * <p>스킬 항목 스키마: {@code {index,name,open,type,cd,desc,damage[],summon,buff[],debuff[],
 * indirect[],util[],masteries[]}}. buff/debuff 항목은 {@code {stat,val,growth,tgt,durationMs,state}}.
 */
public final class SkillDataV2 {

    private final int job;
    private final int creatureIndex;
    private final List<String> indirectSrc;
    private final Map<String, Map<String, Object>> byId = new LinkedHashMap<>();

    private SkillDataV2(int job, int creatureIndex, List<String> indirectSrc) {
        this.job = job;
        this.creatureIndex = creatureIndex;
        this.indirectSrc = indirectSrc;
    }

    /** JSON 문자열(파일 내용)로부터 로드. */
    public static SkillDataV2 load(String json) {
        Map<String, Object> root = Json.asMap(Json.parse(json));
        int job = (int) Json.num(root, "job", 0);
        int ci = (int) Json.num(root, "creatureIndex", 0);
        List<String> isrc = new java.util.ArrayList<>();
        List<Object> il = Json.asList(root.get("indirectSrc"));
        if (il != null) for (Object o : il) isrc.add(String.valueOf(o));
        SkillDataV2 d = new SkillDataV2(job, ci, isrc);
        List<Object> sk = Json.asList(root.get("skills"));
        if (sk != null) for (Object o : sk) {
            Map<String, Object> s = Json.asMap(o);
            d.byId.put(Json.str(s, "index"), s);
        }
        return d;
    }

    public int job() { return job; }

    public int creatureIndex() { return creatureIndex; }

    /** 방어력→주스탯 변환 소스 스탯(간접딜 판정). */
    public List<String> indirectSrc() { return indirectSrc; }

    /** id로 스킬 raw 조회(없으면 null). */
    public Map<String, Object> skill(String id) { return byId.get(id); }

    public Collection<Map<String, Object>> all() { return byId.values(); }
}
