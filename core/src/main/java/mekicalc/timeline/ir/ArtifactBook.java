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
 * 유물 IR 전체(skills_calc/artifact_manual.json 로드본). 유물 목록 + idx 인덱스.
 * 스킬의 {@link SkillBook}과 대칭.
 */
public final class ArtifactBook {

    private final List<ArtifactIR> artifacts;
    private final Map<Integer, ArtifactIR> byIdx;

    private ArtifactBook(List<ArtifactIR> artifacts) {
        this.artifacts = List.copyOf(artifacts);
        Map<Integer, ArtifactIR> idx = new LinkedHashMap<>();
        for (ArtifactIR a : artifacts) idx.put(a.idx(), a);
        this.byIdx = Map.copyOf(idx);
    }

    @SuppressWarnings("unchecked")
    public static ArtifactBook load(Path file) {
        String src;
        try {
            src = Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException("유물 IR 파일 읽기 실패: " + file, e);
        }
        Map<String, Object> root = (Map<String, Object>) Json.parse(src);
        List<ArtifactIR> list = new ArrayList<>();
        for (Map<String, Object> m : IRRead.mapList(root, "artifacts")) list.add(ArtifactIR.from(m));
        return new ArtifactBook(list);
    }

    public List<ArtifactIR> artifacts()   { return artifacts; }
    public ArtifactIR artifact(int idx)    { return byIdx.get(idx); }
}
