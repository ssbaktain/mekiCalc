package mekicalc.ui;

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
        Path dataDir = Path.of(args.length > 0 ? args[0] : "data");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 시스템 룩앤필 실패 시 기본값 사용
        }
        MainWindow.applyKoreanFont();
        SwingUtilities.invokeLater(() -> {
            try {
                new MainWindow(new AppModel(dataDir)).setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "시작 실패: " + e.getMessage(),
                        "오류", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
