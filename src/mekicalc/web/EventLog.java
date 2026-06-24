package mekicalc.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import mekicalc.json.Json;

/**
 * 운영 로그(접속/에러)를 data 폴더에 JSONL(한 줄 = 한 레코드)로 누적한다.
 *
 * <p>의존성 없이 append-only로 기록하며, 동시 요청(스레드풀 16)을 대비해 파일별 락으로 직렬화한다.
 * 로그 파일은 개인 식별정보(이메일·IP)를 포함할 수 있어 .gitignore로 로컬에만 보관한다.
 * 로그 기록 자체가 실패해도(디스크 가득 등) 본 요청 처리를 막지 않도록 예외를 삼킨다.
 */
public final class EventLog {

    private final Path accessFile;
    private final Path errorFile;
    private final Object lock = new Object();

    public EventLog(Path dataDir) {
        this.accessFile = dataDir.resolve("access.jsonl");
        this.errorFile = dataDir.resolve("error.jsonl");
    }

    /** API 요청 1건: 시각·메서드·경로·상태코드·처리시간(ms)·유저(이메일)·클라이언트 IP. */
    public void access(String method, String path, int status, long ms, String user, String ip) {
        String line = "{\"ts\":" + j(Instant.now().toString())
                + ",\"method\":" + j(method)
                + ",\"path\":" + j(path)
                + ",\"status\":" + status
                + ",\"ms\":" + ms
                + ",\"user\":" + j(user)
                + ",\"ip\":" + j(ip) + "}\n";
        append(accessFile, line);
    }

    /** 500 에러 1건: 시각·메서드·경로·유저·예외 메시지·스택트레이스 전문. */
    public void error(String method, String path, String user, Throwable t) {
        String line = "{\"ts\":" + j(Instant.now().toString())
                + ",\"method\":" + j(method)
                + ",\"path\":" + j(path)
                + ",\"user\":" + j(user)
                + ",\"error\":" + j(String.valueOf(t))
                + ",\"stack\":" + j(stackToString(t)) + "}\n";
        append(errorFile, line);
    }

    private void append(Path f, String line) {
        synchronized (lock) {
            try {
                Files.writeString(f, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("로그 기록 실패(" + f.getFileName() + "): " + e);
            }
        }
    }

    private static String stackToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /** null·문자열을 JSON 값으로 안전하게 직렬화(한 줄). companion_ratios.jsonl과 동일 규약. */
    private static String j(Object o) {
        return Json.write(o).strip();
    }
}
