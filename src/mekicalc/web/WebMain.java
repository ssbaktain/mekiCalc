package mekicalc.web;

import java.nio.file.Path;

/**
 * 웹 서버 진입점.
 *
 * 실행:
 *   java -cp out mekicalc.web.WebMain [port] [dataDir] [webDir]
 *   기본값: port=8080, dataDir=data, webDir=web
 */
public final class WebMain {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path dataDir = Path.of(args.length > 1 ? args[1] : "data");
        Path webDir = Path.of(args.length > 2 ? args[2] : "web");

        GameContent content = GameContent.load(dataDir);
        CalcService service = new CalcService(content);
        WebServer server = new WebServer(port, service, webDir);
        server.start();

        System.out.println("========================================");
        System.out.println(" MekiCalc 웹 서버 시작");
        System.out.println(" 주소: http://localhost:" + port);
        System.out.println(" 데이터: " + dataDir.toAbsolutePath());
        System.out.println(" 정적:   " + webDir.toAbsolutePath());
        System.out.println(" 종료: Ctrl + C");
        System.out.println("========================================");
    }
}
