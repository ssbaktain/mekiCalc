package mekicalc.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;

/**
 * 잠깐 떴다가 스르륵 사라지는 알림(토스트).
 *
 * <p>둥근 모서리의 회색 사각형 + 가운데 흰 글씨. 처음엔 약간 투명한 상태로 떠서
 * 점차 불투명(100%)해진 뒤(페이드 인), 잠시 머물고 다시 사라진다(페이드 아웃).
 * 창 자체의 per-pixel 투명도 위에 {@link AlphaComposite}로 부드럽게 알파를 조절한다.
 */
public final class Toast {

    private Toast() {}

    public static void show(Window owner, String message) {
        final JWindow w = new JWindow(owner);
        try {
            w.setBackground(new Color(0, 0, 0, 0)); // per-pixel 투명(둥근 모서리 밖은 완전 투명)
        } catch (Exception ignored) {
            // 투명 미지원 환경 → 불투명으로 폴백
        }
        final ToastPanel panel = new ToastPanel(message);
        w.setContentPane(panel);
        w.pack();

        if (owner != null && owner.isShowing()) {
            Point c = owner.getLocationOnScreen();
            int x = c.x + (owner.getWidth() - w.getWidth()) / 2;
            int y = c.y + (owner.getHeight() - w.getHeight()) / 2;
            w.setLocation(x, y);
        }
        w.setVisible(true);

        final long start = System.currentTimeMillis();
        final long fadeIn = 200, hold = 700, fadeOut = 520, total = fadeIn + hold + fadeOut;
        final float startAlpha = 0.25f;
        Timer timer = new Timer(15, null);
        timer.addActionListener(e -> {
            long t = System.currentTimeMillis() - start;
            float a;
            if (t < fadeIn) a = startAlpha + (1f - startAlpha) * (t / (float) fadeIn);
            else if (t < fadeIn + hold) a = 1f;
            else if (t < total) a = 1f - (t - fadeIn - hold) / (float) fadeOut;
            else { ((Timer) e.getSource()).stop(); w.dispose(); return; }
            panel.alpha = Math.max(0f, Math.min(1f, a));
            panel.repaint();
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    private static final class ToastPanel extends JPanel {
        private final String msg;
        private float alpha = 0.25f;

        ToastPanel(String msg) {
            this.msg = msg;
            setOpaque(false);
            setFont(getFont().deriveFont(Font.BOLD, 14f));
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = fm.stringWidth(msg) + 52;
            int h = fm.getHeight() + 26;
            return new Dimension(Math.max(w, 150), Math.max(h, 46));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            int w = getWidth(), h = getHeight(), arc = Math.min(h, 26);
            g2.setColor(new Color(0x4A, 0x4F, 0x57)); // 회색 배경
            g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            g2.setColor(Color.WHITE);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int tx = (w - fm.stringWidth(msg)) / 2;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(msg, tx, ty);
            g2.dispose();
        }
    }
}
