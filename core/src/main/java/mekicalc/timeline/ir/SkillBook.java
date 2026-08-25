package mekicalc.timeline.ir;

import mekicalc.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 직업 1개의 IR 전체(skills_calc/{job}_manual.json 로드본). 스킬·마스터리 목록 + idx 인덱스.
 */
public final class SkillBook {

    private final String job;
    private final List<SkillIR> skills;
    private final List<MasteryIR> masteries;
    private final Map<Integer, SkillIR> byIdx;

    private SkillBook(String job, List<SkillIR> skills, List<MasteryIR> masteries) {
        this.job = job;
        this.skills = List.copyOf(skills);
        this.masteries = List.copyOf(masteries);
        Map<Integer, SkillIR> idx = new LinkedHashMap<>();
        for (SkillIR s : skills) idx.put(s.idx(), s);
        this.byIdx = Map.copyOf(idx);
    }

    @SuppressWarnings("unchecked")
    public static SkillBook load(Path file) {
        String src;
        try {
            src = Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException("IR 파일 읽기 실패: " + file, e);
        }
        Map<String, Object> root = (Map<String, Object>) Json.parse(src);
        String job = IRRead.str(root, "job");

        List<SkillIR> sk = new ArrayList<>();
        for (Map<String, Object> m : IRRead.mapList(root, "skills")) sk.add(SkillIR.from(m));

        List<MasteryIR> ms = new ArrayList<>();
        for (Map<String, Object> m : IRRead.mapList(root, "masteries")) ms.add(MasteryIR.from(m));

        return new SkillBook(job, sk, ms);
    }

    public String job()               { return job; }
    public List<SkillIR> skills()     { return skills; }
    public List<MasteryIR> masteries(){ return masteries; }
    public SkillIR skill(int idx)     { return byIdx.get(idx); }
}
