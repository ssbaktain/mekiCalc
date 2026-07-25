package mekicalc.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 임시 유틸: 워크플로우 서브에이전트가 만든 결과 파일(data/mig/out-*.json)을 읽어 store에 저장한다.
 * 각 out 파일 = JSON 배열, 원소 = {threadId, data:{구조화 snake_case 객체}}.
 * stripBoilerplate(결정론적, API 무관)만 적용하고 saveData로 반영. 서버 정지 상태에서 실행.
 */
public final class MigApply {
    public static void main(String[] a) throws Exception {
        Path d = Path.of("data");
        NoticeStore st = new NoticeStore(d);
        Path mig = d.resolve("mig");
        long now = System.currentTimeMillis() / 1000;
        List<Path> outs = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(mig, "out-*.json")) {
            for (Path p : s) outs.add(p);
        }
        outs.sort(Comparator.comparing(Path::getFileName));
        int files = 0, applied = 0, fail = 0;
        for (Path p : outs) {
            files++;
            try {
                Object parsed = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
                for (Object o : Json.asList(parsed)) {
                    Map<String, Object> m = Json.asMap(o);
                    Object tidO = m.get("threadId");
                    Object dataObj = m.get("data");
                    if (tidO == null || !(dataObj instanceof Map)) { fail++; continue; }
                    NoticeData nd = NoticeData.fromMap(Json.asMap(dataObj));
                    if (nd == null) { fail++; continue; }
                    NoticeSummarizer.stripBoilerplate(nd);
                    st.saveData(tidO.toString(), nd, now);
                    applied++;
                }
            } catch (Exception e) {
                System.out.println("[FILE FAIL] " + p.getFileName() + " : " + e.getMessage());
            }
        }
        st.save();
        System.out.println("files=" + files + " applied=" + applied + " fail=" + fail);
    }
}
