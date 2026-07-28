package mekicalc.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import mekicalc.engine.equip.PotentialDraw;

/**
 * 검색 가능한 드롭다운(잠재 옵션 선택용) — 클릭 선택 방식.
 *
 * <p>편집형 JComboBox의 고질적 문제(첫 클릭 무시·선택 후 색 사라짐)를 피하려고,
 * 어두운 칩 형태의 라벨 + 클릭 시 뜨는 팝업(검색창 + 색상 리스트)으로 직접 구현했다.
 * 노란 옵션은 노란색, 흰 옵션은 흰색으로 표시(어두운 배경이라 둘 다 잘 보임).
 */
public final class SearchableCombo extends JLabel {

    public record Item(PotentialDraw draw, String label, boolean yellow) {
        @Override public String toString() { return label; }
    }

    private static final Item EMPTY = new Item(null, "(비움)", false);
    private static final Color DARK = new Color(0x2B, 0x2F, 0x36);
    private static final Color SEL = new Color(0x3A, 0x44, 0x55);
    private static final Color LINE = new Color(0x47, 0x4D, 0x57);
    private static final Color YEL = new Color(0xEC, 0xC9, 0x4B);
    private static final Color WHT = new Color(0xF1, 0xF1, 0xF1);
    private static final Color GRY = new Color(0x9A, 0xA0, 0xA8);

    private List<Item> all = new ArrayList<>(List.of(EMPTY));
    private Item current = EMPTY;
    /** 사용자가 직접 선택을 바꿀 때만 호출되는 콜백(프로그램적 setItems/select에서는 미호출). */
    private Runnable onChange;

    private final JPopupMenu popup = new JPopupMenu();
    private final JTextField search = new JTextField();
    private final DefaultListModel<Item> listModel = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(listModel);

    public SearchableCombo() {
        super(EMPTY.label);
        setOpaque(true);
        setBackground(DARK);
        setForeground(GRY);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE),
                BorderFactory.createEmptyBorder(4, 10, 4, 18)));
        setPreferredSize(new Dimension(300, 30));

        list.setBackground(DARK);
        list.setCellRenderer(new R());
        list.setFixedCellHeight(24);
        JScrollPane sc = new JScrollPane(list);
        sc.setBorder(null);
        sc.setPreferredSize(new Dimension(320, 280));

        JPanel pp = new JPanel(new BorderLayout(0, 4));
        pp.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        pp.add(search, BorderLayout.NORTH);
        pp.add(sc, BorderLayout.CENTER);
        popup.setLayout(new BorderLayout());
        popup.add(pp, BorderLayout.CENTER);

        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { showPopup(); }
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refilter(); }
            @Override public void removeUpdate(DocumentEvent e) { refilter(); }
            @Override public void changedUpdate(DocumentEvent e) { refilter(); }
        });
        search.addActionListener(e -> chooseHighlighted());
        search.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DOWN && listModel.getSize() > 0) {
                    int i = Math.min(list.getSelectedIndex() + 1, listModel.getSize() - 1);
                    list.setSelectedIndex(Math.max(0, i));
                    list.ensureIndexIsVisible(list.getSelectedIndex());
                } else if (e.getKeyCode() == KeyEvent.VK_UP && list.getSelectedIndex() > 0) {
                    list.setSelectedIndex(list.getSelectedIndex() - 1);
                    list.ensureIndexIsVisible(list.getSelectedIndex());
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx >= 0) choose(listModel.get(idx));
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int x = getWidth() - 14, y = getHeight() / 2 - 2;
        g2.setColor(GRY);
        g2.fillPolygon(new Polygon(new int[]{x, x + 8, x + 4}, new int[]{y, y, y + 5}, 3));
        g2.dispose();
    }

    private void showPopup() {
        search.setText("");
        rebuild(all);
        popup.setPreferredSize(new Dimension(Math.max(getWidth(), 300), 320));
        popup.show(this, 0, getHeight());
        SwingUtilities.invokeLater(search::requestFocusInWindow);
    }

    private void refilter() {
        String low = search.getText().toLowerCase();
        List<Item> f = new ArrayList<>();
        for (Item it : all) if (it.label.toLowerCase().contains(low)) f.add(it);
        rebuild(f);
    }

    private void rebuild(List<Item> items) {
        listModel.clear();
        for (Item it : items) listModel.addElement(it);
        if (listModel.getSize() > 0) list.setSelectedIndex(0);
    }

    private void chooseHighlighted() {
        if (listModel.getSize() == 0) return;
        int i = list.getSelectedIndex();
        choose(listModel.get(i < 0 ? 0 : i));
    }

    /** 변경 콜백 등록(사용자 선택 변경 시에만 호출). */
    public void setOnChange(Runnable r) { this.onChange = r; }

    /** 사용자 선택 경로: 콜백 발생. */
    private void choose(Item it) { choose(it, true); }

    private void choose(Item it, boolean notify) {
        current = it;
        setText(it.label);
        setForeground(it.draw == null ? GRY : (it.yellow ? YEL : WHT));
        popup.setVisible(false);
        if (notify && onChange != null) onChange.run();
    }

    /** 후보 목록 교체(맨 앞 비움 자동). 선택은 비움으로 초기화(프로그램적 → 콜백 없음). */
    public void setItems(List<Item> items) {
        all = new ArrayList<>();
        all.add(EMPTY);
        all.addAll(items);
        choose(EMPTY, false);
    }

    /** 저장된 stat+value 항목을 선택(프로그램적 → 콜백 없음). */
    public void select(String stat, double value) {
        for (Item it : all) {
            if (it.draw != null && it.draw.statId().equals(stat)
                    && Math.abs(it.draw.value() - value) < 1e-9) {
                choose(it, false);
                return;
            }
        }
    }

    /** 현재 선택된 옵션(없으면 null). */
    public PotentialDraw selected() {
        return current.draw;
    }

    private static final class R extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object v, int idx,
                                                      boolean sel, boolean foc) {
            super.getListCellRendererComponent(l, v, idx, sel, foc);
            setBackground(sel ? SEL : DARK);
            setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            if (v instanceof Item it) {
                setText(it.label);
                setForeground(it.draw == null ? GRY : (it.yellow ? YEL : WHT));
            }
            return this;
        }
    }
}
