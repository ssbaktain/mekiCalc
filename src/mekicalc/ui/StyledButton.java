package mekicalc.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JButton;

/**
 * 테마에 맞춘 플랫 버튼(살짝 둥근 모서리, 호버/프레스 상태색).
 * primary=true면 액센트 채움+흰 글자(주요 액션), 아니면 카드형(흰 채움+얇은 테두리).
 */
public final class StyledButton extends JButton {

    private final boolean primary;
    private int radius = 12;

    public StyledButton(String text, boolean primary) {
        super(text);
        this.primary = primary;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setForeground(primary ? Color.WHITE : Theme.TEXT);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    public StyledButton radius(int r) { this.radius = r; return this; }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth() - 2, h = getHeight() - 2;
        boolean armed = getModel().isArmed();
        boolean hover = getModel().isRollover();

        Color fill;
        if (primary) {
            fill = armed ? Theme.ACCENT_DARK : hover ? Theme.ACCENT.brighter() : Theme.ACCENT;
        } else {
            fill = armed ? new Color(0xE3, 0xEA, 0xF7) : hover ? Theme.ACCENT_SOFT : Theme.CARD;
        }
        g2.setColor(fill);
        g2.fillRoundRect(1, 1, w, h, radius, radius);

        if (!primary) {
            g2.setColor(hover ? Theme.ACCENT : Theme.BORDER);
            g2.setStroke(new BasicStroke(1.4f));
            g2.drawRoundRect(1, 1, w - 1, h - 1, radius, radius);
        }
        g2.dispose();
        super.paintComponent(g);
    }
}
