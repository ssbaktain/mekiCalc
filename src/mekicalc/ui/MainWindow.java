package mekicalc.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import mekicalc.config.CubeStateIO;
import mekicalc.engine.equip.PotentialDraw;
import mekicalc.model.StatDef;

/**
 * 메이플 키우기 스탯 효율 계산기 — Swing GUI (시안 기반 화면 전환형).
 *
 * 화면: 홈 / 스탯입력 / 큐브메뉴 / 큐브-딜증가량비교 / 내큐브옵션(부위 그리드+모달) / 준비중.
 * 첫 실행(스탯 저장 이력 없음)이면 바로 스탯입력으로 진입.
 */
public final class MainWindow extends JFrame {

    /** 시안의 부위 배치 순서(좌열 위→아래, 우열 위→아래; 2열 row-major). */
    private static final String[] PART_ORDER = {
            "모자", "망토", "상의", "어깨장식", "하의", "벨트",
            "장갑", "신발", "반지", "목걸이", "눈장식", "얼굴장식", "귀고리"};

    private final AppModel model;
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final Map<String, String> gradeLabelToCode;

    // 스탯 입력
    private final Map<String, JTextField> statFields = new LinkedHashMap<>();
    private final JLabel statDamage = new JLabel();

    // 큐브 딜 증가량 비교 (좌/우 각각 부위·등급 따로 선택)
    private final JComboBox<String> leftPart = new JComboBox<>();
    private final JComboBox<String> leftGrade = new JComboBox<>();
    private final JComboBox<String> rightPart = new JComboBox<>();
    private final JComboBox<String> rightGrade = new JComboBox<>();
    private final SearchableCombo[] leftSlots = {new SearchableCombo(), new SearchableCombo(), new SearchableCombo()};
    private final SearchableCombo[] rightSlots = {new SearchableCombo(), new SearchableCombo(), new SearchableCombo()};
    private final JLabel leftResult = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel rightResult = new JLabel(" ", SwingConstants.CENTER);

    // 큐브 기댓값
    private final JComboBox<String> expPart = new JComboBox<>();
    private final JComboBox<String> expGrade = new JComboBox<>();
    private final JComboBox<String> expSort = new JComboBox<>(
            new String[]{"효율 순", "딜 증가량 순", "등장 확률 순"});

    /** 효율 점수 환산 배수: 등장확률 × 딜증가량(둘 다 분수)에 곱해 읽기 좋은 점수로. */
    private static final double SCORE_SCALE = 10000.0;
    /** 추천 탭 단독 효율(딜증가량×확률÷가격) 환산 배수. */
    private static final double REC_EFF_SCALE = 1_000_000.0;
    private final ExpTableModel expModel = new ExpTableModel();

    private final JLabel placeholderTitle = new JLabel("", SwingConstants.CENTER);
    /** 준비중 화면에 들어오기 직전 화면(뒤로 버튼이 돌아갈 곳). */
    private String placeholderBack = "home";

    // 큐브 우선순위 추천
    private final JCheckBox recUpper = new JCheckBox("윗잠", true);
    private final JCheckBox recLower = new JCheckBox("아랫잠", true);
    private final JComboBox<String> recSort = new JComboBox<>(
            new String[]{"전체 딜 증가 확률합", "남은 딜 증가 확률합", "딜 증가량", "효율"});
    private final JSlider recSlider = new JSlider(0, 1, 0);
    private final JPanel recList = new JPanel();

    public MainWindow(AppModel model) {
        super("MekiCalc");
        this.model = model;
        this.gradeLabelToCode = model.gradeLabelToCode();
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        root.setBackground(Theme.BG);
        root.add(buildHome(), "home");
        root.add(buildStatInput(), "stat");
        root.add(buildCubeMenu(), "cubeMenu");
        root.add(buildCubeCompare(), "cubeCompare");
        root.add(buildCubeExpected(), "cubeExpected");
        root.add(buildCubeRecommend(), "cubeRecommend");
        root.add(buildCubeInput(), "cubeInput");
        root.add(buildPlaceholder(), "placeholder");
        add(root);

        setMinimumSize(new Dimension(440, 560));
        setSize(470, 700);
        setLocationRelativeTo(null);

        if (model.isUserEntered()) {
            show("home");
        } else {
            populateStatFields();
            show("stat");
        }
    }

    private void show(String name) {
        cards.show(root, name);
        // 화면별 권장 크기: 추천(좌우 비교)은 더 넓게, 표/비교는 넓게, 나머지는 좁게.
        int w, h;
        if (name.equals("cubeRecommend")) { w = 860; h = 760; }
        else if (name.equals("cubeCompare") || name.equals("cubeExpected")) { w = 900; h = 740; }
        else { w = 470; h = 700; }
        if (getWidth() != w || getHeight() != h) setSize(w, h);
    }

    // ──────────────────── 홈 (시안: 메인 화면) ────────────────────
    private JPanel buildHome() {
        JPanel p = screen(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(22, 26, 16, 26));

        JLabel title = new JLabel("MekiCalc", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 36f));
        title.setForeground(Theme.TEXT);
        title.setBorder(BorderFactory.createEmptyBorder(8, 0, 22, 0));
        p.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(9, 9, 9, 9);
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1; c.ipady = 28;
        addBtn(center, c, button("큐브"), 0, 0, () -> { populateCompare(); show("cubeMenu"); });
        addBtn(center, c, button("어빌리티"), 1, 0, () -> goPlaceholder("어빌리티", "home"));
        addBtn(center, c, button("주흔작"), 0, 1, () -> goPlaceholder("주흔작", "home"));
        addBtn(center, c, button("유물 잠재"), 1, 1, () -> goPlaceholder("유물 잠재", "home"));
        addBtn(center, c, button("용사의 여정"), 0, 2, () -> goPlaceholder("용사의 여정", "home"));

        JLabel credit = new JLabel("made by 탱구링", SwingConstants.CENTER);
        credit.setFont(credit.getFont().deriveFont(Font.BOLD | Font.ITALIC, 14f));
        credit.setForeground(Theme.MUTED);
        c.gridx = 0; c.gridy = 3; c.gridwidth = 2; c.ipady = 0;
        c.insets = new Insets(20, 9, 4, 9);
        center.add(credit, c);
        p.add(center, BorderLayout.CENTER);

        p.add(bottomBig("스탯 입력", () -> { populateStatFields(); show("stat"); }), BorderLayout.SOUTH);
        return p;
    }

    // ──────────────────── 큐브 메뉴 (시안: 큐브) ────────────────────
    private JPanel buildCubeMenu() {
        JPanel p = screen(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 24, 16, 24));
        p.add(topBar("home", "큐브"), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(10, 8, 10, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1; c.ipady = 22; c.gridx = 0;
        c.gridy = 0; center.add(wideRounded("옵션 비교", () -> { populateCompare(); show("cubeCompare"); }), c);
        c.gridy = 1; center.add(wideRounded("기댓값", () -> { populateExpected(); show("cubeExpected"); }), c);
        c.gridy = 2; center.add(wideRounded("우선 순위 추천", () -> {
            model.buildRecommendations();
            recSlider.setMaximum(Math.max(1, model.maxDivisions() - 1));
            recSlider.setValue(0);
            refreshRecommend();
            show("cubeRecommend");
        }), c);
        JPanel wrap = screen(new BorderLayout());
        wrap.add(center, BorderLayout.NORTH);
        p.add(wrap, BorderLayout.CENTER);

        p.add(bottomBig("내 큐브 옵션 입력 / 수정", () -> show("cubeInput")), BorderLayout.SOUTH);
        return p;
    }

    // ──────────────────── 내 큐브 옵션 입력/수정 (시안: 부위 그리드 + 모달) ────────────────────
    private JPanel buildCubeInput() {
        JPanel grid = new JPanel(new GridLayout(0, 2, 12, 12));
        grid.setOpaque(false);
        for (String part : PART_ORDER) {
            StyledButton b = new StyledButton(part, false);
            b.setFont(b.getFont().deriveFont(Font.BOLD, 15f));
            b.setPreferredSize(new Dimension(0, 52));
            b.addActionListener(e -> openCubeDialog(part));
            grid.add(b);
        }
        JPanel gridWrap = screen(new BorderLayout());
        gridWrap.add(grid, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(gridWrap);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        sp.getVerticalScrollBar().setUnitIncrement(16);

        JLabel hint = new JLabel("부위를 선택하세요", SwingConstants.CENTER);
        hint.setForeground(Theme.MUTED);
        JPanel top = screen(new BorderLayout());
        top.add(topBar("cubeMenu", "큐브 옵션 입력 / 수정"), BorderLayout.NORTH);
        top.add(hint, BorderLayout.SOUTH);

        JPanel p = screen(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 16, 16, 16));
        p.add(top, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    /** 부위 클릭 시: 윗잠/아랫잠 각각 등급+3슬롯을 고르는 모달. 저장 시 mycube.json에 반영. */
    private void openCubeDialog(String part) {
        JDialog d = new JDialog(this, part, true);

        JComboBox<String> upGrade = gradeCombo();
        JComboBox<String> loGrade = gradeCombo();
        SearchableCombo[] upSlots = {new SearchableCombo(), new SearchableCombo(), new SearchableCombo()};
        SearchableCombo[] loSlots = {new SearchableCombo(), new SearchableCombo(), new SearchableCombo()};

        CubeStateIO.PartCube saved = model.cubeOf(part);
        initSection(part, upGrade, upSlots, saved != null ? saved.upper() : null);
        initSection(part, loGrade, loSlots, saved != null ? saved.lower() : null);
        upGrade.addActionListener(e -> repopulateSlots(part, upGrade, upSlots));
        loGrade.addActionListener(e -> repopulateSlots(part, loGrade, loSlots));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Theme.BG);
        form.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        c.insets = new Insets(6, 6, 6, 6);

        JLabel ttl = new JLabel(part, SwingConstants.CENTER);
        ttl.setForeground(Theme.TEXT);
        ttl.setFont(ttl.getFont().deriveFont(Font.BOLD, 19f));
        c.gridy = 0; form.add(ttl, c);
        c.gridy = 1; form.add(potSection("윗잠 (미라클 큐브)", upGrade, upSlots), c);
        c.gridy = 2; form.add(potSection("아랫잠 (에디셔널 큐브)", loGrade, loSlots), c);

        JButton save = new JButton("저장");
        save.setFont(save.getFont().deriveFont(Font.BOLD, 15f));
        save.addActionListener(e -> {
            try {
                model.saveCube(part, new CubeStateIO.PartCube(
                        lineFrom(upGrade, upSlots), lineFrom(loGrade, loSlots)));
                d.dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "저장 실패: " + ex.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
            }
        });
        JPanel sbar = new JPanel(); sbar.setBackground(Theme.BG); sbar.add(save);
        c.gridy = 3; c.fill = GridBagConstraints.NONE; form.add(sbar, c);

        d.setContentPane(form);
        d.pack();
        d.setMinimumSize(new Dimension(420, Math.min(760, d.getHeight())));
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    private JComboBox<String> gradeCombo() {
        JComboBox<String> g = new JComboBox<>();
        for (String gl : model.gradeLabels()) g.addItem(gl);
        styleGradeCombo(g);
        return g;
    }

    private void repopulateSlots(String part, JComboBox<String> grade, SearchableCombo[] slots) {
        String code = gradeLabelToCode.get((String) grade.getSelectedItem());
        List<List<PotentialDraw>> sl = model.slotDraws(part, code);
        for (int i = 0; i < 3; i++) slots[i].setItems(toItems(sl.get(i)));
    }

    private void initSection(String part, JComboBox<String> grade, SearchableCombo[] slots, CubeStateIO.Line saved) {
        grade.setSelectedItem(saved != null
                ? AppModel.GRADE_KO.getOrDefault(saved.grade(), saved.grade()) : "미스틱");
        repopulateSlots(part, grade, slots);
        if (saved != null && saved.slots() != null) {
            for (int i = 0; i < 3 && i < saved.slots().length; i++) {
                CubeStateIO.Slot s = saved.slots()[i];
                if (s != null) slots[i].select(s.stat(), s.value());
            }
        }
    }

    private JPanel potSection(String title, JComboBox<String> grade, SearchableCombo[] slots) {
        JPanel sec = new JPanel(new GridBagLayout());
        sec.setBackground(Theme.CARD);
        sec.setBorder(BorderFactory.createTitledBorder(title));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        c.insets = new Insets(5, 10, 5, 10);
        c.gridy = 0; sec.add(grade, c);
        for (int i = 0; i < 3; i++) { c.gridy = i + 1; sec.add(slots[i], c); }
        return sec;
    }

    private CubeStateIO.Line lineFrom(JComboBox<String> grade, SearchableCombo[] slots) {
        String code = gradeLabelToCode.get((String) grade.getSelectedItem());
        CubeStateIO.Slot[] arr = new CubeStateIO.Slot[3];
        for (int i = 0; i < 3; i++) {
            PotentialDraw pd = slots[i].selected();
            arr[i] = (pd == null) ? null : new CubeStateIO.Slot(pd.statId(), pd.value());
        }
        return new CubeStateIO.Line(code, arr);
    }

    // ──────────────────── 큐브: 딜 증가량 비교 ────────────────────
    private JPanel buildCubeCompare() {
        JPanel p = screen(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        p.add(topBar("cubeMenu", "큐브 옵션 비교"), BorderLayout.NORTH);

        JPanel mid = new JPanel(new GridLayout(1, 2, 16, 0));
        mid.setOpaque(false);
        mid.add(buildSide("왼쪽", leftPart, leftGrade, leftSlots, leftResult));
        mid.add(buildSide("오른쪽", rightPart, rightGrade, rightSlots, rightResult));
        p.add(mid, BorderLayout.CENTER);

        StyledButton compare = new StyledButton("비교", true);
        compare.setFont(compare.getFont().deriveFont(Font.BOLD, 24f));
        compare.setPreferredSize(new Dimension(0, 64));
        compare.addActionListener(e -> doCompare());

        JLabel note = new JLabel("<html><div style='text-align:center'>"
                + "효율 = 등장확률 × 딜증가량 점수 (큐브 1개당 기대 딜 증가량, 클수록 우선)<br>"
                + "※ '계산 불가' = 데미지 환산에 필요한 데이터가 아직 없는 옵션 (방어 관통력 · 대상 수 · 쿨타임 감소 등)"
                + "</div></html>", SwingConstants.CENTER);
        note.setForeground(Theme.MUTED);
        note.setBorder(BorderFactory.createEmptyBorder(8, 4, 0, 4));

        JPanel south = screen(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(12, 40, 4, 40));
        south.add(compare, BorderLayout.CENTER);
        south.add(note, BorderLayout.SOUTH);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildSide(String title, JComboBox<String> partC, JComboBox<String> gradeC,
                             SearchableCombo[] slots, JLabel result) {
        for (String part : PART_ORDER) partC.addItem(part);
        for (String g : model.gradeLabels()) gradeC.addItem(g);
        styleGradeCombo(gradeC);
        gradeC.setSelectedItem("미스틱");
        partC.addActionListener(e -> populateSide(partC, gradeC, slots, result));
        gradeC.addActionListener(e -> populateSide(partC, gradeC, slots, result));

        JPanel side = new JPanel(new GridBagLayout());
        side.setBackground(Theme.CARD);
        side.setBorder(BorderFactory.createTitledBorder(title));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(7, 12, 7, 12);
        c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; c.gridx = 0;

        JPanel sel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        sel.setBackground(Theme.CARD);
        sel.add(new JLabel("부위")); sel.add(partC);
        sel.add(new JLabel("등급")); sel.add(gradeC);
        c.gridy = 0; side.add(sel, c);

        for (int i = 0; i < 3; i++) { c.gridy = i + 1; side.add(slots[i], c); }

        result.setFont(result.getFont().deriveFont(Font.PLAIN, 15f));
        result.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));
        c.gridy = 4; side.add(result, c);
        return side;
    }

    /** 좌/우 양쪽 슬롯을 각자의 부위·등급으로 채운다. */
    private void populateCompare() {
        populateSide(leftPart, leftGrade, leftSlots, leftResult);
        populateSide(rightPart, rightGrade, rightSlots, rightResult);
    }

    private void populateSide(JComboBox<String> partC, JComboBox<String> gradeC,
                             SearchableCombo[] slots, JLabel result) {
        if (partC.getSelectedItem() == null || gradeC.getSelectedItem() == null) return;
        String part = (String) partC.getSelectedItem();
        String grade = gradeLabelToCode.get((String) gradeC.getSelectedItem());
        List<List<PotentialDraw>> sl = model.slotDraws(part, grade);
        for (int i = 0; i < 3; i++) slots[i].setItems(toItems(sl.get(i)));
        result.setText(" ");
    }

    private List<SearchableCombo.Item> toItems(List<PotentialDraw> draws) {
        List<SearchableCombo.Item> items = new ArrayList<>();
        for (PotentialDraw d : draws) {
            String suffix = model.isPercent(d.statId()) ? "%" : "";
            String label = (d.special() ? "★ " : "") + model.statName(d.statId())
                    + " +" + fmt(d.value()) + suffix;
            items.add(new SearchableCombo.Item(d, label, d.yellow()));
        }
        return items;
    }

    /** 좌/우 조합의 딜 증가량·등장 확률·큐브 기댓값·효율을 계산해 표시.
     *  확률·효율은 양쪽을 비교 그룹으로 묶어, 같아 보이면 처음 달라지는 자리까지 늘려 표기한다. */
    private void doCompare() {
        List<PotentialDraw> ld = collect(leftSlots), rd = collect(rightSlots);
        double li = model.increaseForDraws(ld), ri = model.increaseForDraws(rd);
        double lp = singleProb(leftPart, leftGrade, ld), rp = singleProb(rightPart, rightGrade, rd);
        boolean lq = model.hasQualitative(ld), rq = model.hasQualitative(rd); // 공속/방관 등 포함 시 효율 환산 불가

        double[] pv = {lp * 100, rp * 100};
        String[] probs = formatCompareGroup(pv, new boolean[]{lp > 0, rp > 0},
                new int[]{baseDecimalsPercent(pv[0]), baseDecimalsPercent(pv[1])},
                "%", new String[]{"-", "-"});

        double[] sv = {lp * li * SCORE_SCALE, rp * ri * SCORE_SCALE};
        String[] scores = formatCompareGroup(sv,
                new boolean[]{lp > 0 && !lq, rp > 0 && !rq},
                new int[]{baseDecimalsScore(sv[0]), baseDecimalsScore(sv[1])},
                "", new String[]{(lp > 0 && lq) ? "계산 불가" : "-", (rp > 0 && rq) ? "계산 불가" : "-"});

        String lSum = probSumStr(leftPart, leftGrade, ld);
        String rSum = probSumStr(rightPart, rightGrade, rd);
        leftResult.setText(sideHtml(li, lq, lp, probs[0], lSum, scores[0]));
        rightResult.setText(sideHtml(ri, rq, rp, probs[1], rSum, scores[1]));
    }

    /** 한 측의 동일 옵션 등장 확률 총합 표시 문자열. */
    private String probSumStr(JComboBox<String> partC, JComboBox<String> gradeC, List<PotentialDraw> draws) {
        if (partC.getSelectedItem() == null || gradeC.getSelectedItem() == null) return "-";
        String part = (String) partC.getSelectedItem();
        String grade = gradeLabelToCode.get((String) gradeC.getSelectedItem());
        double s = model.probSumForDraws(part, grade, draws);
        return s > 0 ? fmtPercentCore(s * 100) + "%" : "-";
    }

    /** 한 측의 단일 등장 확률(빈 슬롯은 빈 확률 반영). 미선택이면 0. */
    private double singleProb(JComboBox<String> partC, JComboBox<String> gradeC, List<PotentialDraw> draws) {
        if (partC.getSelectedItem() == null || gradeC.getSelectedItem() == null) return model.probForDraws(draws);
        String part = (String) partC.getSelectedItem();
        String grade = gradeLabelToCode.get((String) gradeC.getSelectedItem());
        return model.probSingleForDraws(part, grade, draws);
    }

    private static String sideHtml(double inc, boolean qual, double p, String prob, String probSum, String score) {
        String incText = qual ? "계산 불가" : fmtSignedPercent(inc);
        String exp = (p <= 0) ? "-" : String.format("%,.1f개", 1.0 / p);
        return String.format(
                "<html><div style='text-align:center'>"
                + "딜 증가량: <b>%s</b><br>"
                + "등장 확률: %s<br>"
                + "동일 옵션 등장 확률 총합: %s<br>"
                + "큐브 기댓값: %s<br>"
                + "효율: <b>%s</b></div></html>",
                incText, prob, probSum, exp, score);
    }

    private List<PotentialDraw> collect(SearchableCombo[] slots) {
        List<PotentialDraw> out = new ArrayList<>();
        for (SearchableCombo s : slots) out.add(s.selected());
        return out;
    }

    // ──────────────────── 큐브: 기댓값 계산 ────────────────────
    private JPanel buildCubeExpected() {
        for (String part : PART_ORDER) expPart.addItem(part);
        for (String g : model.gradeLabels()) expGrade.addItem(g);
        styleGradeCombo(expGrade);
        expGrade.setSelectedItem("미스틱");
        expPart.addActionListener(e -> populateExpected());
        expGrade.addActionListener(e -> populateExpected());
        expSort.addActionListener(e -> populateExpected());

        JPanel controls = screen(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 4));
        controls.add(new JLabel("부위")); controls.add(expPart);
        controls.add(new JLabel("등급")); controls.add(expGrade);
        controls.add(new JLabel("정렬")); controls.add(expSort);

        JPanel north = screen(null);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(topBar("cubeMenu", "큐브 기댓값"));
        north.add(controls);

        JTable table = new JTable(expModel);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setMaxWidth(30);
        table.getColumnModel().getColumn(1).setCellRenderer(new OptionCellRenderer());
        for (int col = 2; col < 7; col++) {
            table.getColumnModel().getColumn(col).setCellRenderer(rightAlign());
        }
        JScrollPane sp = new JScrollPane(table);

        JLabel note = new JLabel("<html>큐브 기댓값 = 3줄 중 한 줄에라도 그 옵션이 뜰 때까지 평균 큐브 수(1/P)  ·  ★=특옵"
                + "<br>효율 = 등장확률 × 딜증가량 점수 (큐브 1개당 기대 딜 증가량, 클수록 우선)"
                + "<br>※ '계산 불가' = 데미지 환산에 필요한 데이터가 아직 없는 옵션 (방어 관통력 · 대상 수 · 쿨타임 감소 등)</html>");
        note.setForeground(Theme.MUTED);
        note.setBorder(BorderFactory.createEmptyBorder(6, 4, 2, 4));

        JPanel p = screen(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        p.add(north, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(note, BorderLayout.SOUTH);
        return p;
    }

    private void populateExpected() {
        if (expPart.getSelectedItem() == null || expGrade.getSelectedItem() == null) return;
        String part = (String) expPart.getSelectedItem();
        String grade = gradeLabelToCode.get((String) expGrade.getSelectedItem());
        List<mekicalc.engine.equip.CubeEvaluator.Result> res =
                new ArrayList<>(model.evaluate(part, grade));
        switch (expSort.getSelectedIndex()) {
            case 2 -> // 등장 확률 내림차순 (뽑기 쉬운 것=확률 높은 것이 위로)
                    res.sort((a, b) -> Double.compare(a.pPerCube(), b.pPerCube()) * -1);
            case 1 -> // 딜 증가량 내림차순 (계산 불가=NaN은 뒤로)
                    res.sort((a, b) -> Double.compare(dmgKey(b), dmgKey(a)));
            default -> // 효율 내림차순 (등장확률×딜증가량, 기본값. 계산 불가는 뒤로)
                    res.sort((a, b) -> Double.compare(scoreKey(b), scoreKey(a)));
        }
        expModel.setRows(res);
    }

    private static double dmgKey(mekicalc.engine.equip.CubeEvaluator.Result r) {
        return r.qualitative() ? Double.NEGATIVE_INFINITY : r.damageIncrease();
    }

    /** 효율 정렬 키 = 등장확률 × 딜증가량(분수). 정성적(계산 불가)은 맨 뒤로. */
    private static double scoreKey(mekicalc.engine.equip.CubeEvaluator.Result r) {
        return r.qualitative() ? Double.NEGATIVE_INFINITY : r.pPerCube() * r.damageIncrease();
    }

    /** 기댓값 표 모델: CubeEvaluator 결과를 행으로. */
    private final class ExpTableModel extends javax.swing.table.AbstractTableModel {
        private final String[] cols = {"", "옵션", "수치", "딜 증가량", "등장 확률", "큐브 기댓값", "효율"};
        private List<mekicalc.engine.equip.CubeEvaluator.Result> rows = List.of();
        // 확률·효율은 컬럼 전체를 비교 그룹으로 묶어, 같아 보이면 처음 달라지는 자리까지 늘려 사전 포맷.
        private String[] probStr = new String[0];
        private String[] scoreStr = new String[0];

        void setRows(List<mekicalc.engine.equip.CubeEvaluator.Result> r) {
            rows = r;
            int n = r.size();
            double[] pv = new double[n]; int[] pbd = new int[n];
            String[] pPh = new String[n];
            double[] sv = new double[n]; int[] sbd = new int[n];
            boolean[] sInc = new boolean[n]; String[] sPh = new String[n];
            for (int i = 0; i < n; i++) {
                var x = r.get(i);
                pv[i] = x.pPerCube() * 100;
                pbd[i] = baseDecimalsPercent(pv[i]);
                pPh[i] = "-";
                sInc[i] = !x.qualitative();
                sv[i] = sInc[i] ? x.pPerCube() * x.damageIncrease() * SCORE_SCALE : 0;
                sbd[i] = baseDecimalsScore(sv[i]);
                sPh[i] = x.qualitative() ? "계산 불가" : "-";
            }
            boolean[] allInc = new boolean[n]; java.util.Arrays.fill(allInc, true);
            probStr = formatCompareGroup(pv, allInc, pbd, "%", pPh);
            scoreStr = formatCompareGroup(sv, sInc, sbd, "", sPh);
            fireTableDataChanged();
        }
        mekicalc.engine.equip.CubeEvaluator.Result rowAt(int r) { return rows.get(r); }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public boolean isCellEditable(int r, int c) { return false; }

        @Override
        public Object getValueAt(int r, int c) {
            var x = rows.get(r);
            switch (c) {
                case 0: return x.special() ? "★" : "";
                case 1: return model.statName(x.statId());
                case 2: return fmt(x.value()) + (model.isPercent(x.statId()) ? "%" : "");
                case 3: return x.qualitative() ? "계산 불가" : fmtSignedPercent(x.damageIncrease());
                case 4: return probStr[r];
                case 5: return Double.isInfinite(x.expectedCubes()) ? "-" : String.format("%,.1f개", x.expectedCubes());
                case 6: return scoreStr[r];
                default: return "";
            }
        }
    }

    /** 옵션 이름 셀: 노란 옵션은 금색, 흰 옵션은 기본색. */
    private final class OptionCellRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                                boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, foc, row, col);
            if (!sel) {
                var x = expModel.rowAt(row);
                setForeground(x.yellow() ? new Color(0xB8, 0x86, 0x0B) : Theme.TEXT);
            }
            setFont(getFont().deriveFont(Font.BOLD));
            return this;
        }
    }

    private static javax.swing.table.DefaultTableCellRenderer rightAlign() {
        javax.swing.table.DefaultTableCellRenderer r = new javax.swing.table.DefaultTableCellRenderer();
        r.setHorizontalAlignment(SwingConstants.RIGHT);
        return r;
    }

    // ──────────────────── 큐브: 우선 순위 추천 ────────────────────
    private JPanel buildCubeRecommend() {
        JPanel p = screen(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));

        JPanel north = screen(null);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(topBar("cubeMenu", "큐브 우선 순위 추천"));

        recUpper.setOpaque(false); recLower.setOpaque(false);
        recUpper.addActionListener(e -> refreshRecommend());
        recLower.addActionListener(e -> refreshRecommend());
        recSort.setSelectedIndex(3); // 기본: 효율
        recSort.addActionListener(e -> refreshRecommend());
        JPanel checks = screen(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 14, 2));
        checks.add(recUpper); checks.add(recLower);
        checks.add(new JLabel("  정렬")); checks.add(recSort);
        north.add(checks);

        JLabel left = new JLabel("최소 딜 증가량");
        JLabel right = new JLabel("최대 딜 증가량");
        left.setForeground(Theme.MUTED); right.setForeground(Theme.MUTED);
        left.setFont(left.getFont().deriveFont(Font.BOLD, 13f));
        right.setFont(right.getFont().deriveFont(Font.BOLD, 13f));
        recSlider.setOpaque(false);
        recSlider.setPreferredSize(new Dimension(0, 40));
        recSlider.setUI(new AccentSliderUI(recSlider));   // 둥근 트랙 + 깔끔한 thumb
        recSlider.addChangeListener(e -> refreshRecommend());
        JPanel sl = screen(new BorderLayout(6, 0));
        sl.setBorder(BorderFactory.createEmptyBorder(2, 6, 8, 6));
        sl.add(left, BorderLayout.WEST);
        sl.add(recSlider, BorderLayout.CENTER);
        sl.add(right, BorderLayout.EAST);
        north.add(sl);

        JLabel note = new JLabel("<html><div style='text-align:center'>"
                + "※ 슬라이더 = 목표 딜 순위 (왼쪽 쉬운 개선 → 오른쪽 종결)<br>"
                + "· 딜 증가량 차이 = 추천 딜 증가량 − 기존 딜 증가량<br>"
                + "· 최종 효율 = 딜 증가량 차이 × 동일 옵션 등장 확률 총합 ÷ 큐브 가격<br>"
                + "· 전체 딜 증가 확률합 = 한 큐브에 현재보다 나은 옵션이 나올 확률 (고정)<br>"
                + "· 남은 딜 증가 확률합 = 슬라이더 딜 이상이 나올 확률<br>"
                + "※ 아랫잠은 큐브 가격 2배 → 효율·정렬에 반영</div></html>", SwingConstants.CENTER);
        note.setForeground(Theme.MUTED);
        note.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 8));
        note.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        note.setMaximumSize(new Dimension(Integer.MAX_VALUE, note.getPreferredSize().height));
        north.add(note);
        p.add(north, BorderLayout.NORTH);

        recList.setLayout(new BoxLayout(recList, BoxLayout.Y_AXIS));
        recList.setOpaque(false);
        JPanel wrap = screen(new BorderLayout());
        wrap.add(recList, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrap);
        sp.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    /** 슬라이더·정렬·체크박스 기준으로 추천 목록을 다시 그린다(캐시 기반이라 즉각 반응). */
    private void refreshRecommend() {
        List<mekicalc.engine.equip.CubeRecommender.Rec> recs = model.recommend(
                recSlider.getValue(), recSort.getSelectedIndex(), recUpper.isSelected(), recLower.isSelected());
        recList.removeAll();
        if (recs.isEmpty()) {
            JLabel empty = new JLabel("<html><div style='text-align:center'>추천할 항목이 없습니다.<br>"
                    + "내 큐브 옵션을 입력했는지, 현재보다 나은 조합이 있는지 확인하세요.</div></html>",
                    SwingConstants.CENTER);
            empty.setForeground(Theme.MUTED);
            empty.setBorder(BorderFactory.createEmptyBorder(30, 8, 8, 8));
            empty.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            recList.add(empty);
        } else {
            for (int i = 0; i < recs.size(); i++) {
                recList.add(recBox(recs.get(i)));
                if (i < recs.size() - 1) recList.add(downArrow());
            }
        }
        recList.revalidate();
        recList.repaint();
    }

    /** '다음 이걸로' 우선순위 이정표(아래 화살표). */
    private JLabel downArrow() {
        JLabel a = new JLabel("↓", SwingConstants.CENTER);
        a.setForeground(Theme.MUTED);
        a.setFont(a.getFont().deriveFont(Font.BOLD, 22f));
        a.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        a.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        a.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return a;
    }

    /** 추천 한 박스: 부위·층 + [기존 옵션] → [추천 옵션] 좌우 비교 + 아래 딜 차이. */
    private JPanel recBox(mekicalc.engine.equip.CubeRecommender.Rec r) {
        JPanel box = new JPanel(new BorderLayout(0, 6));
        box.setBackground(Theme.CARD);
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(r.part() + " (" + (r.upper() ? "윗잠" : "아랫잠") + ")"),
                BorderFactory.createEmptyBorder(4, 8, 8, 8)));

        JPanel cols = new JPanel(new GridBagLayout());
        cols.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH; c.gridy = 0; c.insets = new Insets(0, 4, 0, 4);
        c.gridx = 0; c.weightx = 1;
        cols.add(recColumn("기존", r, false), c);
        c.gridx = 1; c.weightx = 0;
        JLabel arrow = new JLabel("→");
        arrow.setFont(arrow.getFont().deriveFont(Font.BOLD, 30f));
        arrow.setForeground(Theme.ACCENT);
        arrow.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        cols.add(arrow, c);
        c.gridx = 2; c.weightx = 1;
        cols.add(recColumn("추천", r, true), c);
        box.add(cols, BorderLayout.CENTER);

        JLabel diff = new JLabel(String.format(
                "<html><div style='text-align:center'>딜 증가량 차이  <b>%s</b><br>최종 효율  <b>%s</b></div></html>",
                fmtSignedPercent(r.dealDiff()), fmtScoreCore(r.gainEff() * REC_EFF_SCALE)),
                SwingConstants.CENTER);
        diff.setFont(diff.getFont().deriveFont(Font.BOLD, 15f));
        diff.setForeground(Theme.ACCENT);
        box.add(diff, BorderLayout.SOUTH);

        JPanel wrap = screen(new BorderLayout());
        wrap.add(box, BorderLayout.CENTER);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrap.getPreferredSize().height));
        wrap.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        return wrap;
    }

    /** 추천 박스의 한 쪽(기존/추천): 헤더 + 3슬롯 옵션 + 딜/확률/큐브기댓값/효율. */
    private JPanel recColumn(String header, mekicalc.engine.equip.CubeRecommender.Rec r, boolean rec) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(Theme.BG);
        col.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD5, 0xD9, 0xDF)),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JLabel h = new JLabel(header);
        h.setFont(h.getFont().deriveFont(Font.BOLD, 13f));
        h.setForeground(rec ? Theme.ACCENT : Theme.MUTED);
        h.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        col.add(h);
        col.add(Box.createVerticalStrut(3));

        for (var pk : r.picks()) {
            PotentialDraw d = rec ? pk.recommended() : pk.current();
            JLabel s = new JLabel(d == null ? "(비어 있음)" : (rec && pk.changed() ? "▶ " : "") + optionLabel(d));
            if (d == null) s.setForeground(Theme.MUTED);
            else s.setForeground(d.yellow() ? new Color(0xB8, 0x86, 0x0B) : Theme.TEXT);
            // 노란 옵션은 가시성 위해 굵게(변경된 추천 슬롯도 굵게).
            boolean bold = (d != null && d.yellow()) || (rec && pk.changed());
            s.setFont(s.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, 14f));
            s.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            col.add(s);
        }

        col.add(Box.createVerticalStrut(5));
        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        col.add(sep);
        col.add(Box.createVerticalStrut(4));

        double deal = rec ? r.recDeal() : r.curDeal();
        double probSingle = rec ? r.recProbSingle() : r.curProbSingle();
        double probSum = rec ? r.recProbSum() : r.curProbSum();
        double cubes = rec ? r.recCubes() : r.curCubes();
        double eff = rec ? r.recEff() : r.curEff();
        String cubesS = Double.isInfinite(cubes) ? "-" : String.format("%,.1f개", cubes);
        String top = String.format(
                "딜 증가량 <b>%s</b><br>등장 확률 %s<br>동일 옵션 등장 확률 총합 %s<br>큐브 기댓값 %s<br>효율 <b>%s</b>",
                fmtSignedPercent(deal), fmtPercentCore(probSingle * 100) + "%",
                fmtPercentCore(probSum * 100) + "%", cubesS, fmtScoreCore(eff * REC_EFF_SCALE));
        String html = rec
                ? "<html>" + top + "<br><br>남은 딜 증가 확률합 " + fmtPercentCore(r.remainProb() * 100) + "%"
                  + "<br>전체 딜 증가 확률합 " + fmtPercentCore(r.totalProb() * 100) + "%</html>"
                : "<html>" + top + "</html>";
        JLabel m = new JLabel(html);
        m.setForeground(Theme.TEXT);
        m.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        col.add(m);
        return col;
    }

    private String optionLabel(PotentialDraw d) {
        String suffix = model.isPercent(d.statId()) ? "%" : "";
        return (d.special() ? "★ " : "") + model.statName(d.statId()) + " +" + fmt(d.value()) + suffix;
    }

    /** 깔끔한 슬라이더: 둥근 트랙 + 액센트 채움 + 흰 둥근 thumb(액센트 테두리). */
    private static final class AccentSliderUI extends javax.swing.plaf.basic.BasicSliderUI {
        AccentSliderUI(JSlider s) { super(s); }

        @Override protected Dimension getThumbSize() { return new Dimension(20, 26); }
        @Override public void paintFocus(java.awt.Graphics g) { }

        @Override public void paintTrack(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int h = 6;
            int y = trackRect.y + (trackRect.height - h) / 2;
            g2.setColor(Theme.BORDER);
            g2.fillRoundRect(trackRect.x, y, trackRect.width, h, h, h);
            int cx = thumbRect.x + thumbRect.width / 2;
            g2.setColor(Theme.ACCENT);
            g2.fillRoundRect(trackRect.x, y, Math.max(0, cx - trackRect.x), h, h, h);
            g2.dispose();
        }

        @Override public void paintThumb(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.Rectangle k = thumbRect;
            g2.setColor(Color.WHITE);
            g2.fillRoundRect(k.x, k.y, k.width, k.height, 12, 12);
            g2.setColor(Theme.ACCENT);
            g2.setStroke(new java.awt.BasicStroke(2.5f));
            g2.drawRoundRect(k.x + 1, k.y + 1, k.width - 3, k.height - 3, 11, 11);
            g2.dispose();
        }
    }

    // ──────────────────── 스탯 입력 ────────────────────
    private JPanel buildStatInput() {
        JPanel p = screen(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        statDamage.setFont(statDamage.getFont().deriveFont(Font.BOLD, 14f));
        statDamage.setBorder(BorderFactory.createEmptyBorder(6, 4, 4, 4));
        JPanel north = screen(new BorderLayout());
        north.add(topBar("home", "스탯 입력 (표시값 기준)"), BorderLayout.NORTH);
        north.add(statDamage, BorderLayout.SOUTH);
        p.add(north, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 6, 2, 6);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        for (StatDef def : model.stats()) {
            c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            JLabel lbl = new JLabel(def.name + (def.derived ? " (자동)" : ""));
            if (def.qualitative) lbl.setForeground(new Color(120, 120, 120));
            form.add(lbl, c);
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            JTextField tf = new JTextField(7);
            if (def.derived) {
                tf.setEditable(false);
                tf.setForeground(Theme.MUTED);
                tf.setBackground(new Color(0xEE, 0xF0, 0xF3));
                tf.setToolTipText("주스탯에서 자동 계산됩니다 (주스탯 100당 1%)");
            }
            statFields.put(def.id, tf);
            form.add(tf, c);
            row++;
        }
        // 주스탯+/주스탯% 변경 시 스탯 비례 데미지 실시간 재계산
        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { recomputeDerived(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { recomputeDerived(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { recomputeDerived(); }
        };
        JTextField mf = statFields.get("MAIN_STAT_FLAT");
        JTextField mp = statFields.get("MAIN_STAT_PCT");
        if (mf != null) mf.getDocument().addDocumentListener(dl);
        if (mp != null) mp.getDocument().addDocumentListener(dl);

        JScrollPane sp = new JScrollPane(form);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        p.add(sp, BorderLayout.CENTER);

        JButton save = new JButton("적용 & 저장");
        save.addActionListener(e -> saveStats());
        JPanel south = screen(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        south.add(save);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private void populateStatFields() {
        for (Map.Entry<String, JTextField> e : statFields.entrySet()) {
            e.getValue().setText(fmt(model.value(e.getKey())));
        }
        recomputeDerived();
        updateStatDamage();
    }

    /** 예상 데미지(기본 공격) — 일반몹/보스몹 3줄로 표시. */
    private void updateStatDamage() {
        statDamage.setText(String.format(
                "<html>예상 데미지 (기본 공격)<br>일반몹: <b>%,.0f</b><br>보스몹: <b>%,.0f</b></html>",
                model.currentDamageNormal(), model.currentDamageBoss()));
    }

    /** 스탯 비례 데미지 = 주스탯깡 ×(1+주스탯%/100) / 100. 입력창에서 실시간 표시(읽기 전용). */
    private void recomputeDerived() {
        JTextField sp = statFields.get("STAT_PROP_DMG");
        if (sp == null) return;
        double flat = parseField(statFields.get("MAIN_STAT_FLAT"));
        double pct = parseField(statFields.get("MAIN_STAT_PCT"));
        double mainStat = flat * (1 + pct / 100.0);
        sp.setText(fmt(Math.round(mainStat / 100.0 * 100.0) / 100.0));
    }

    private static double parseField(JTextField tf) {
        if (tf == null) return 0;
        try { String t = tf.getText().trim(); return t.isEmpty() ? 0 : Double.parseDouble(t); }
        catch (NumberFormatException e) { return 0; }
    }

    private void saveStats() {
        try {
            for (Map.Entry<String, JTextField> e : statFields.entrySet()) {
                String t = e.getValue().getText().trim();
                model.setValue(e.getKey(), t.isEmpty() ? 0 : Double.parseDouble(t));
            }
            model.save();
            updateStatDamage();
            JOptionPane.showMessageDialog(this, "저장되었습니다.", "완료", JOptionPane.INFORMATION_MESSAGE);
            show("home");
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "숫자가 아닌 값이 있습니다.", "입력 오류", JOptionPane.WARNING_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "저장 실패: " + ex.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ──────────────────── 준비중 ────────────────────
    private JPanel buildPlaceholder() {
        JPanel p = screen(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 16, 16, 16));
        p.add(topBar(() -> show(placeholderBack), placeholderTitle), BorderLayout.NORTH);
        JLabel msg = new JLabel("준비 중입니다.", SwingConstants.CENTER);
        msg.setForeground(Theme.MUTED);
        p.add(msg, BorderLayout.CENTER);
        return p;
    }

    private void goPlaceholder(String title, String back) {
        placeholderTitle.setText(title);
        placeholderBack = back;
        show("placeholder");
    }

    // ──────────────────── 공통 위젯 ────────────────────
    /** 배경이 테마 BG인 화면 패널. */
    private JPanel screen(java.awt.LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setBackground(Theme.BG);
        return p;
    }

    private StyledButton button(String text) {
        StyledButton b = new StyledButton(text, false);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 16f));
        return b;
    }

    private StyledButton wideRounded(String text, Runnable onClick) {
        StyledButton b = button(text);
        b.addActionListener(e -> onClick.run());
        return b;
    }

    private void addBtn(JPanel grid, GridBagConstraints c, StyledButton b, int x, int y, Runnable onClick) {
        b.addActionListener(e -> onClick.run());
        c.gridx = x; c.gridy = y; c.gridwidth = 1;
        grid.add(b, c);
    }

    /** 하단: 구분선 + 큰 액센트 버튼(주요 액션). */
    private JPanel bottomBig(String text, Runnable onClick) {
        JPanel south = screen(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(14, 0, 2, 0));
        StyledButton big = new StyledButton(text, true);
        big.setFont(big.getFont().deriveFont(Font.BOLD, 22f));
        big.setPreferredSize(new Dimension(0, 66));
        big.addActionListener(e -> onClick.run());
        south.add(big, BorderLayout.CENTER);
        return south;
    }

    /** 등급 콤보를 등급색(진하게)으로 스타일링. 밝은 배경에 등급색 굵은 글자. */
    private void styleGradeCombo(JComboBox<String> combo) {
        combo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setHorizontalAlignment(SwingConstants.CENTER);
                setFont(getFont().deriveFont(Font.BOLD, 14f));
                String code = gradeLabelToCode.get(String.valueOf(value));
                if (!isSelected) setForeground(Theme.gradeColor(code));
                return this;
            }
        });
    }

    /** 화면 상단 공통 바: 좌측 [← 뒤로] + 가운데 굵은 타이틀(우측 여백으로 정확히 중앙 정렬). */
    private JPanel topBar(String backTarget, String title) {
        return topBar(() -> show(backTarget), new JLabel(title));
    }

    private JPanel topBar(String backTarget, JLabel title) {
        return topBar(() -> show(backTarget), title);
    }

    private JPanel topBar(Runnable onBack, JLabel title) {
        JPanel bar = screen(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
        JButton back = new JButton("← 뒤로");
        back.addActionListener(e -> onBack.run());

        title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setForeground(Theme.TEXT);

        // 좌측 버튼 폭만큼 우측에 빈 여백을 둬 타이틀이 화면 정중앙에 오게 한다.
        JLabel spacer = new JLabel();
        spacer.setPreferredSize(back.getPreferredSize());

        bar.add(back, BorderLayout.WEST);
        bar.add(title, BorderLayout.CENTER);
        bar.add(spacer, BorderLayout.EAST);
        return bar;
    }

    private static String fmt(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /**
     * 확률(%) 표시. 부동소수점 노이즈를 12자리로 정리한 뒤,
     * 딱 떨어지는(유효숫자 ≤3) 짧은 값은 그대로, 그보다 긴 값은
     * 0이 반복되지 않는 첫 유효숫자부터 2자리(2 유효숫자)로 표시한다.
     * 예: 9.5 → "9.5%", 12.5 → "12.5%", 0.066424 → "0.066%", 0.00012345 → "0.00012%".
     */
    private static String fmtPercentCore(double pct) {
        // 1) 부동소수점 노이즈는 12자리 반올림으로 정리(보이지 않는 끝자리라 표시엔 영향 X).
        java.math.BigDecimal bd = new java.math.BigDecimal(pct)
                .round(new java.math.MathContext(12))
                .stripTrailingZeros();
        // 2) 표시용으로 2 유효숫자까지 줄일 땐 반올림이 아니라 버림(truncate)한다.
        if (bd.precision() > 3) {
            bd = bd.round(new java.math.MathContext(2, java.math.RoundingMode.DOWN)).stripTrailingZeros();
        }
        return bd.toPlainString();
    }

    /** 부호 있는 퍼센트(소수 2자리, 버림). FP 노이즈는 먼저 정리. 예: 0.05019 → "+5.01%". */
    private static String fmtSignedPercent(double frac) {
        java.math.BigDecimal bd = new java.math.BigDecimal(frac * 100)
                .round(new java.math.MathContext(12))                 // FP 노이즈 제거
                .setScale(2, java.math.RoundingMode.DOWN);            // 소수 2자리 버림(0 방향)
        return (bd.signum() >= 0 ? "+" : "") + bd.toPlainString() + "%";
    }

    /**
     * 효율 점수 표시. 계산 불가(NaN)는 "계산 불가".
     * 0.01 이상이면 소수 2자리 버림, 그보다 작으면(노란 3줄 조합 등) 0.00 대신
     * 첫 유효숫자부터 2자리(2 유효숫자, 버림)로 실제 작은 값을 보여준다.
     */
    private static String fmtScoreCore(double score) {
        java.math.BigDecimal bd = new java.math.BigDecimal(score)
                .round(new java.math.MathContext(12)).stripTrailingZeros();
        if (bd.signum() == 0) return "0";
        java.math.BigDecimal two = bd.setScale(2, java.math.RoundingMode.DOWN);
        if (two.signum() != 0) return two.toPlainString();             // 0.01 이상 → 2자리 버림
        return bd.round(new java.math.MathContext(2, java.math.RoundingMode.DOWN))
                .stripTrailingZeros().toPlainString();                  // 더 작으면 2 유효숫자
    }

    // ── 비교용 적응 자릿수 포맷: 서로 다른 값이 같게 보이면 처음 달라지는 자리까지 늘린다 ──

    /** v를 소수 d자리에서 버림(FP 노이즈 정리). */
    private static String truncStr(double v, int d) {
        return new java.math.BigDecimal(v).round(new java.math.MathContext(12))
                .setScale(d, java.math.RoundingMode.DOWN).toPlainString();
    }

    /** 문자열의 소수 자릿수. */
    private static int decimalsOf(String s) {
        int dot = s.indexOf('.');
        return dot < 0 ? 0 : s.length() - dot - 1;
    }

    /** 서로 다른 a,b가 처음으로 달라지는 소수 자릿수(0~12). */
    private static int firstDiffDecimals(double a, double b) {
        for (int d = 0; d <= 12; d++) if (!truncStr(a, d).equals(truncStr(b, d))) return d;
        return 12;
    }

    private static int baseDecimalsPercent(double pv) { return decimalsOf(fmtPercentCore(pv)); }
    private static int baseDecimalsScore(double v) { return decimalsOf(fmtScoreCore(v)); }

    /**
     * 비교 그룹 포맷: 각 값을 기본 자릿수(baseDec)로 버림 표기하되, 서로 다른 두 값이
     * 같은 문자열이 되면 처음 달라지는 자리까지 양쪽 자릿수를 늘린다(나머지 값은 기본 자릿수 유지).
     * @return 각 값의 숫자 문자열(접미사 없음)
     */
    private static String[] formatComparable(double[] v, int[] baseDec) {
        int n = v.length;
        int[] dd = baseDec.clone();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (Math.abs(v[i] - v[j]) <= 1e-12) continue; // 실제로 같은 값은 통과
                int m = Math.max(baseDec[i], baseDec[j]);
                if (truncStr(v[i], m).equals(truncStr(v[j], m))) { // 기본 자릿수에서 충돌
                    int fd = firstDiffDecimals(v[i], v[j]);
                    dd[i] = Math.max(dd[i], fd);
                    dd[j] = Math.max(dd[j], fd);
                }
            }
        }
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = truncStr(v[i], dd[i]);
        return out;
    }

    /** include[i]가 true인 값만 비교 그룹으로 포맷(접미사 붙임), 나머지는 placeholder[i]. */
    private static String[] formatCompareGroup(double[] vals, boolean[] include,
            int[] baseDec, String suffix, String[] placeholder) {
        int n = vals.length, m = 0;
        for (boolean b : include) if (b) m++;
        double[] sv = new double[m]; int[] sb = new int[m]; int[] map = new int[m];
        int k = 0;
        for (int i = 0; i < n; i++) if (include[i]) { sv[k] = vals[i]; sb[k] = baseDec[i]; map[k] = i; k++; }
        String[] sf = formatComparable(sv, sb);
        String[] out = placeholder.clone();
        for (int j = 0; j < m; j++) out[map[j]] = sf[j] + suffix;
        return out;
    }

    public static void applyKoreanFont() {
        Font font = new Font("Malgun Gothic", Font.PLAIN, 13);
        if (font.canDisplay('가')) {
            for (Object key : javax.swing.UIManager.getLookAndFeelDefaults().keySet().toArray()) {
                if (key instanceof String s && s.endsWith(".font")) {
                    javax.swing.UIManager.put(key, font);
                }
            }
        }
    }
}
