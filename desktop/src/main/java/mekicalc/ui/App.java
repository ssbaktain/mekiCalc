package mekicalc.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * GUI 진입점.
 *
 * 실행: java -cp out mekicalc.ui.App [dataDir]
 */
public final class App {

    public static void main(String[] args) {
        Path dataDir = args.length > 0 ? Path.of(args[0]) : resolveDataDir();
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 시스템 룩앤필 실패 시 기본값 사용
        }
        MainWindow.applyKoreanFont();
        SwingUtilities.invokeLater(() -> {
            try {
                new MainWindow(new AppModel(dataDir)).setVisible(true);
            } catch (Throwable e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "시작 실패: " + e.getMessage(),
                        "오류", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /**
     * data 폴더를 찾는다. 개발 환경에서는 현재 작업 디렉터리의 data/,
     * 패키징(.exe)된 환경에서는 실행 파일 위치 기준으로 탐색한다.
     */
    private static Path resolveDataDir() {
        Path cwd = Path.of("data");
        if (Files.isDirectory(cwd)) {
            return cwd;
        }
        try {
            // 실행 중인 jar/클래스의 위치 (패키징 시 <앱>/app/mekiCalc.jar)
            Path codeBase = Path.of(App.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path baseDir = Files.isDirectory(codeBase) ? codeBase : codeBase.getParent();
            for (Path dir = baseDir; dir != null; dir = dir.getParent()) {
                Path candidate = dir.resolve("data");
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            // 위치 탐색 실패 시 기본값으로 폴백
        }
        return cwd;
    }
}
