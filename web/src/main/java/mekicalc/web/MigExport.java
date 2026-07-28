package mekicalc.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 임시 유틸: 전체 공지를 배치 파일(data/mig/batch-NN.json)로 내보낸다. 각 원소는
 * {threadId, category, title, sourceUrl, body}. 워크플로우 서브에이전트가 배치를 읽어 구조화 추출한다.
 * 사용: MigExport [배치크기=15]
 */
public final class MigExport {
    public static void main(String[] a) throws Exception {
        int batch = a.length > 0 ? Integer.parseInt(a[0]) : 15;
        Path d = Path.of("data");
        NoticeStore st = new NoticeStore(d);
        List<NoticeStore.Notice> all = st.all();
        all.sort((x, y) -> Long.compare(y.postedAt, x.postedAt));
        Path mig = d.resolve("mig");
        Files.createDirectories(mig);
        try (DirectoryStream<Path> s = Files.newDirectoryStream(mig, "*.json")) {
            for (Path p : s) Files.deleteIfExists(p);   // 이전 실행 잔여 정리
        }
        int n = all.size(), batches = (n + batch - 1) / batch;
        for (int b = 0; b < batches; b++) {
            List<Object> arr = new ArrayList<>();
            for (int i = b * batch; i < Math.min(n, (b + 1) * batch); i++) {
                NoticeStore.Notice x = all.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("threadId", x.threadId);
                m.put("category", x.category);
                m.put("title", x.title);
                m.put("sourceUrl", x.sourceUrl);
                m.put("body", x.body == null ? "" : x.body);
                arr.add(m);
            }
            Files.writeString(mig.resolve(String.format("batch-%02d.json", b)), Json.write(arr), StandardCharsets.UTF_8);
        }
        System.out.println("batches=" + batches + " total=" + n + " size=" + batch);
    }
}
