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
import mekicalc.config.ConversionIO;
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
    // 딜 환산 설정(정성적·스킬레벨): 스탯별 체크박스 + 값 입력칸
    private final Map<String, JCheckBox> convChecks = new LinkedHashMap<>();
    private final Map<String, JTextField> convFields = new LinkedHashMap<>();
    // 스탯 프리셋 선택 (스탯만 프리셋, 큐브는 단일)
    private final JComboBox<String> presetCombo = new JComboBox<>();
    /** 프리셋 콤보를 코드로 갱신할 때 선택 이벤트(전환)를 무시하기 위한 가드. */
    private boolean presetSwitching = false;
    /** 프리셋 추가 다이얼로그의 시작값 옵션 라벨. */
    private static final String BLANK_SOURCE = "빈 값으로 시작";
    private static final String COPY_PREFIX = "복사: ";
    /** 스탯 저장 버튼(변경 없을 때 비활성화). */
    private StyledButton saveStatsBtn;
    /** 딜 환산 설정 저장 버튼(변경 없을 때 비활성화). */
    private StyledButton convSaveBtn;

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

    /** 홈 화면 배경 이미지(없으면 null). */
    private java.awt.Image homeBg;

    public MainWindow(AppModel model) {
        super("MekiCalc");
        this.model = model;
        this.gradeLabelToCode = model.gradeLabelToCode();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        loadIconAssets();

        root.setBackground(Theme.BG);
        root.add(buildHome(), "home");
        root.add(buildStatInput(), "stat");
        root.add(buildCubeMenu(), "cubeMenu");
        root.add(buildCubeCompare(), "cubeCompare");
        root.add(buildCubeExpected(), "cubeExpected");
        root.add(buildCubeRecommend(), "cubeRecommend");
        root.add(buildCubeInput(), "cubeInput");
        root.add(buildConversionPage(), "conversion");
        root.add(buildPlaceholder(), "placeholder");
        add(root);

        setMinimumSize(new Dimension(440, 560));
        setSize(470, 700);
        setLocationRelativeTo(null);

        if (model.isUserEntered()) {
            show("home");
        } else {
            refreshPresetCombo();
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
        else if (name.equals("conversion")) { w = 500; h = 700; }
        else { w = 470; h = 700; }
        if (getWidth() != w || getHeight() != h) setSize(w, h);
    }

    /** 아이콘/배경 이미지 로드(icon 폴더). 창 아이콘은 maple.png. */
    private void loadIconAssets() {
        java.nio.file.Path dir = model.iconDir();
        homeBg = loadImage(dir, "background.png");
        java.awt.Image icon = loadImage(dir, "maple.png");
        if (icon != null) setIconImage(icon);
    }

    private static java.awt.Image loadImage(java.nio.file.Path dir, String name) {
        try {
            java.nio.file.Path f = dir.resolve(name);
            if (java.nio.file.Files.exists(f)) return javax.imageio.ImageIO.read(f.toFile());
        } catch (Exception ignored) {
            // 이미지 없거나 읽기 실패 → null (배경/아이콘 생략)
        }
        return null;
    }

    /** 이미지를 지정 박스에 비율 유지로 맞춰 반투명하게 그리는 컴포넌트(고정 크기). */
    private static final class ImageView extends javax.swing.JComponent {
        private final java.awt.Image img;
        private final float alpha;
        private final int w, h;

        ImageView(java.awt.Image img, float alpha, int maxW, int maxH) {
            this.img = img;
            this.alpha = alpha;
            int iw = Math.max(1, img.getWidth(null)), ih = Math.max(1, img.getHeight(null));
            double s = Math.min((double) maxW / iw, (double) maxH / ih);
            this.w = (int) Math.round(iw * s);
            this.h = (int) Math.round(ih * s);
            setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        }

        @Override public Dimension getPreferredSize() { return new Dimension(w, h); }
        @Override public Dimension getMaximumSize() { return new Dimension(w, h); }
        @Override public Dimension getMinimumSize() { return new Dimension(w, h); }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha));
            int x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
            g2.drawImage(img, x, y, w, h, this);
            g2.dispose();
        }
    }

    // ──────────────────── 홈 (시안: 메인 화면) ────────────────────
    private JPanel buildHome() {
        JPanel p = screen(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(18, 26, 14, 26));

        JLabel title = new JLabel("MekiCalc", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 36f));
        title.setForeground(Theme.TEXT);

        // 타이틀 우측 '딜 환산 설정' — 메인 버튼과 구분되는 작은 아웃라인(설정류) 디자인.
        JButton convBtn = settingsLink("딜 환산 설정");
        convBtn.addActionListener(e -> { populateConversion(); show("conversion"); });
        JPanel rightWrap = screen(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        rightWrap.add(convBtn);
        JPanel leftWrap = screen(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        JLabel spacer = new JLabel();
        spacer.setPreferredSize(convBtn.getPreferredSize());   // 좌우 대칭 → 타이틀은 화면 정중앙 유지
        leftWrap.add(spacer);

        JPanel header = screen(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(6, 0, 26, 0));
        header.add(leftWrap, BorderLayout.WEST);
        header.add(title, BorderLayout.CENTER);
        header.add(rightWrap, BorderLayout.EAST);
        p.add(header, BorderLayout.NORTH);

        // 버튼 그리드(위로)
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(8, 9, 8, 9);
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1; c.ipady = 24;
        addBtn(grid, c, button("큐브"), 0, 0, () -> { populateCompare(); show("cubeMenu"); });
        addBtn(grid, c, button("어빌리티"), 1, 0, () -> goPlaceholder("어빌리티", "home"));
        addBtn(grid, c, button("주흔작"), 0, 1, () -> goPlaceholder("주흔작", "home"));
        addBtn(grid, c, button("유물 잠재"), 1, 1, () -> goPlaceholder("유물 잠재", "home"));
        addBtn(grid, c, button("용사의 여정"), 0, 2, () -> goPlaceholder("용사의 여정", "home"));

        // 가운데: 버튼만(위쪽)
        JPanel center = screen(new BorderLayout());
        center.setOpaque(false);
        center.add(grid, BorderLayout.NORTH);
        p.add(center, BorderLayout.CENTER);

        // 하단(묶음): 이미지 → made by → 스탯 입력 버튼 (서로 붙여 배치)
        JPanel south = screen(new BorderLayout());
        JPanel imgCredit = screen(new BorderLayout());
        imgCredit.setOpaque(false);
        if (homeBg != null) {
            JPanel imgWrap = screen(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 4));
            imgWrap.setOpaque(false);
            imgWrap.add(new ImageView(homeBg, 0.4f, 192, 200));
            imgCredit.add(imgWrap, BorderLayout.CENTER);
        }
        JLabel credit = new JLabel("made by 탱구링", SwingConstants.CENTER);
        credit.setFont(credit.getFont().deriveFont(Font.BOLD | Font.ITALIC, 14f));
        credit.setForeground(Theme.MUTED);
        credit.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
        imgCredit.add(credit, BorderLayout.SOUTH);
        south.add(imgCredit, BorderLayout.NORTH);
        south.add(bottomBig("스탯 입력", () -> { refreshPresetCombo(); populateStatFields(); show("stat"); }), BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
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

        p.add(bottomBig("내 큐브 옵션 입력", () -> show("cubeInput")), BorderLayout.SOUTH);
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
        top.add(topBar("cubeMenu", "큐브 옵션 입력", true), BorderLayout.NORTH);
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

        StyledButton save = new StyledButton("저장", true);
        save.setFont(save.getFont().deriveFont(Font.BOLD, 15f));
        save.setPreferredSize(new Dimension(120, 40));
        save.setEnabled(false);   // 변경 없으면 비활성화
        Runnable updateDirty = () -> save.setEnabled(cubePartChanged(saved, upGrade, upSlots, loGrade, loSlots));
        save.addActionListener(e -> {
            try {
                model.saveCube(part, new CubeStateIO.PartCube(
                        lineFrom(upGrade, upSlots), lineFrom(loGrade, loSlots)));
                d.dispose();
                Toast.show(this, "저장되었습니다");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "저장 실패: " + ex.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
            }
        });
        // 등급/슬롯 변경 시 dirty 갱신(초기화 이후 등록 → 초기 설정값으로는 미발생)
        upGrade.addActionListener(e -> { repopulateSlots(part, upGrade, upSlots); updateDirty.run(); });
        loGrade.addActionListener(e -> { repopulateSlots(part, loGrade, loSlots); updateDirty.run(); });
        for (SearchableCombo s : upSlots) s.setOnChange(updateDirty);
        for (SearchableCombo s : loSlots) s.setOnChange(updateDirty);

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

    /** 현재 다이얼로그 선택이 저장된 부위 큐브와 다른지(윗/아랫 중 하나라도 변경). */
    private boolean cubePartChanged(CubeStateIO.PartCube saved, JComboBox<String> ug, SearchableCombo[] us,
                                    JComboBox<String> lg, SearchableCombo[] ls) {
        return !sameLineNorm(lineFrom(ug, us), saved == null ? null : saved.upper())
                || !sameLineNorm(lineFrom(lg, ls), saved == null ? null : saved.lower());
    }

    /** 잠재 줄 비교. 슬롯이 모두 비면 '없음(null)'과 같게 취급(등급만 고른 빈 줄 = 미입력). */
    private static boolean sameLineNorm(CubeStateIO.Line a, CubeStateIO.Line b) {
        boolean ea = isEmptyLine(a), eb = isEmptyLine(b);
        if (ea || eb) return ea && eb;
        if (!java.util.Objects.equals(a.grade(), b.grade())) return false;
        for (int i = 0; i < 3; i++) if (!slotEq(slotAt(a, i), slotAt(b, i))) return false;
        return true;
    }

    private static boolean isEmptyLine(CubeStateIO.Line line) {
        if (line == null) return true;
        for (int i = 0; i < 3; i++) if (slotAt(line, i) != null) return false;
        return true;
    }

    private static CubeStateIO.Slot slotAt(CubeStateIO.Line line, int i) {
        return (line.slots() != null && i < line.slots().length) ? line.slots()[i] : null;
    }

    private static boolean slotEq(CubeStateIO.Slot a, CubeStateIO.Slot b) {
        if (a == null || b == null) return a == b;
        return java.util.Objects.equals(a.stat(), b.stat()) && Math.abs(a.value() - b.value()) < 1e-9;
    }

    // ──────────────────── 큐브: 딜 증가량 비교 ────────────────────
    private JPanel buildCubeCompare() {
        JPanel p = screen(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        p.add(topBar("cubeMenu", "큐브 옵션 비교", true), BorderLayout.NORTH);

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
        north.add(topBar("cubeMenu", "큐브 기댓값", true));
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
        north.add(topBar("cubeMenu", "큐브 우선 순위 추천", true));

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
        JPanel p = screen(new BorderLayout(10, 10));
        p.setBorder(BorderFactory.createEmptyBorder(10, 14, 12, 14));

        // 상단: 타이틀 + 프리셋 바
        JPanel north = screen(null);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(topBar("home", "스탯 입력"));
        north.add(Box.createVerticalStrut(2));
        north.add(buildPresetBar());
        p.add(north, BorderLayout.NORTH);

        // 가운데: 카드(설명 + 스탯 폼)
        JPanel card = new JPanel(new BorderLayout(0, 0));
        card.setBackground(Theme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(14, 18, 14, 18)));

        // 설명: JEditorPane은 폭에 맞춰 자동 줄바꿈되어 잘리지 않는다.
        javax.swing.JEditorPane desc = new javax.swing.JEditorPane("text/html",
                "<html><body style='margin:0'>"
                + "· 모든 수치는 게임 <b>표시값</b> 기준입니다.<br>"
                + "· <font color='#8A919C'>회색</font> 스탯(방어 관통 · 대상 수 · 쿨타임 감소 등)은 "
                + "기본적으로 비교 · 추천에서 제외되지만, <b>딜 환산 설정</b>에서 환산값을 지정하면 반영됩니다.<br>"
                + "· 아래 <b>예상 데미지</b>는 참고용이며 실제 게임 값과 정확히 일치하지 않을 수 있습니다."
                + "</body></html>");
        desc.setEditable(false);
        desc.setOpaque(false);
        desc.setForeground(Theme.MUTED);
        desc.putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        desc.setFont(new JLabel().getFont().deriveFont(12f));
        desc.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        card.add(desc, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 8);
        c.anchor = GridBagConstraints.WEST;
        javax.swing.event.DocumentListener live = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { onStatFieldsChanged(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { onStatFieldsChanged(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { onStatFieldsChanged(); }
        };
        int row = 0;
        for (StatDef def : model.stats()) {
            if (def.hidden) continue;   // 1레벨당 주스탯 등은 직접 입력 대상 아님(큐브에서 환산)
            c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            JLabel lbl = new JLabel(def.name);
            lbl.setForeground(def.qualitative ? Theme.MUTED : Theme.TEXT);
            if (def.qualitative) lbl.setToolTipText("데미지로 환산되지 않는 스탯(비교 · 추천 제외)");
            form.add(lbl, c);
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            JTextField tf = new JTextField(9);
            tf.setHorizontalAlignment(SwingConstants.RIGHT);
            tf.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER),
                    BorderFactory.createEmptyBorder(4, 9, 4, 9)));
            installComma(tf);                       // 천 단위 쉼표 표시
            tf.getDocument().addDocumentListener(live);
            statFields.put(def.id, tf);
            form.add(tf, c);
            row++;
        }
        JPanel formWrap = new JPanel(new BorderLayout());
        formWrap.setOpaque(false);
        formWrap.add(form, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(formWrap);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        card.add(sp, BorderLayout.CENTER);
        p.add(card, BorderLayout.CENTER);

        // 하단: 예상 데미지(좌, 자체 행) 위 / 초기화·저장(우, 다음 행) — 세로 분리로 단위가 커져도 겹치지 않음
        statDamage.setFont(statDamage.getFont().deriveFont(Font.BOLD, 13f));
        statDamage.setForeground(Theme.TEXT);
        JPanel dmgBox = new JPanel(new BorderLayout());
        dmgBox.setBackground(Theme.ACCENT_SOFT);
        dmgBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xCD, 0xD8, 0xF2)),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        dmgBox.add(statDamage, BorderLayout.CENTER);
        JPanel dmgRow = screen(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
        dmgRow.add(dmgBox);
        JButton reset = new JButton("초기화");
        reset.addActionListener(e -> resetStats());
        saveStatsBtn = standardSaveButton(this::saveStats);
        JPanel btnRow = screen(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        btnRow.add(reset);
        btnRow.add(saveStatsBtn);
        JPanel south = screen(new BorderLayout(0, 6));
        south.add(dmgRow, BorderLayout.NORTH);
        south.add(btnRow, BorderLayout.SOUTH);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private void populateStatFields() {
        for (Map.Entry<String, JTextField> e : statFields.entrySet()) {
            e.getValue().setText(fmtComma(model.value(e.getKey())));
        }
        updateStatDamage();
        updateSaveEnabled();
    }

    /** 입력 변경 시: 예상 데미지 + 저장 버튼 활성/비활성 갱신. */
    private void onStatFieldsChanged() {
        updateStatDamage();
        updateSaveEnabled();
    }

    /** 화면 입력값이 활성 프리셋의 저장값과 하나라도 다르면 변경됨(dirty). */
    private boolean isStatsDirty() {
        for (Map.Entry<String, JTextField> e : statFields.entrySet()) {
            if (Math.abs(parseField(e.getValue()) - model.value(e.getKey())) > 1e-9) return true;
        }
        return false;
    }

    private void updateSaveEnabled() {
        if (saveStatsBtn != null) saveStatsBtn.setEnabled(isStatsDirty());
    }

    /** 예상 데미지(기본 공격) — 현재 입력 필드 값 기준으로 일반몹/보스몹 라이브 표시(저장 전에도 갱신). */
    private void updateStatDamage() {
        Map<String, Double> vals = new LinkedHashMap<>();
        for (Map.Entry<String, JTextField> e : statFields.entrySet()) {
            vals.put(e.getKey(), parseField(e.getValue()));
        }
        double[] d = model.previewDamage(vals);
        statDamage.setText(String.format(
                "<html>예상 데미지 (기본 공격)<br>일반몹 <b>%,.0f</b>%s<br>보스몹 <b>%,.0f</b>%s</html>",
                d[0], koreanUnitSuffix(d[0]), d[1], koreanUnitSuffix(d[1])));
    }

    private static final long[] UNIT_VAL = {10_000_000_000_000_000L, 1_000_000_000_000L, 100_000_000L, 10_000L};
    private static final String[] UNIT_KO = {"경", "조", "억", "만"};

    /** 큰 수의 한글 단위 환산 접미사 " (nnn단위)". 1만 미만은 빈 문자열. 유효숫자 3자리. */
    private static String koreanUnitSuffix(double v) {
        double a = Math.abs(v);
        if (a < 10_000 || Double.isNaN(a) || Double.isInfinite(a)) return "";
        for (int i = 0; i < UNIT_VAL.length; i++) {
            if (a >= UNIT_VAL[i]) return " (" + threeSig(v / UNIT_VAL[i]) + UNIT_KO[i] + ")";
        }
        return "";
    }

    /** 유효숫자 3자리 문자열(끝 0 제거). 예: 626.59→627, 62.659→62.7, 1.2345→1.23. */
    private static String threeSig(double v) {
        return new java.math.BigDecimal(v)
                .round(new java.math.MathContext(3))
                .stripTrailingZeros()
                .toPlainString();
    }

    /** 입력값을 모두 0으로 초기화(확인 후). */
    private void resetStats() {
        int ok = JOptionPane.showConfirmDialog(this,
                "'" + model.activePreset() + "' 프리셋의 입력값을 모두 0으로 초기화할까요?\n(저장 전까지는 디스크에 반영되지 않습니다)",
                "초기화", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        for (JTextField tf : statFields.values()) tf.setText("0");
        updateStatDamage();
    }

    private static double parseField(JTextField tf) {
        if (tf == null) return 0;
        try {
            String t = tf.getText().trim().replace(",", "");
            return (t.isEmpty() || t.equals("-")) ? 0 : Double.parseDouble(t);
        } catch (NumberFormatException e) { return 0; }
    }

    /** 입력 필드의 현재 값들을 모델(current)에 반영. 숫자 아닌 값이 있으면 NumberFormatException. */
    private void commitFields() {
        for (Map.Entry<String, JTextField> e : statFields.entrySet()) {
            String t = e.getValue().getText().trim().replace(",", "");
            model.setValue(e.getKey(), (t.isEmpty() || t.equals("-")) ? 0 : Double.parseDouble(t));
        }
    }

    // ── 천 단위 쉼표 표시(입력창) ──

    /** 텍스트필드에 천 단위 쉼표를 자동 표시하는 DocumentFilter 설치. 값은 표시만, 파싱 시 쉼표 제거. */
    private void installComma(JTextField tf) {
        javax.swing.text.AbstractDocument doc = (javax.swing.text.AbstractDocument) tf.getDocument();
        doc.setDocumentFilter(new javax.swing.text.DocumentFilter() {
            @Override public void insertString(FilterBypass fb, int off, String s, javax.swing.text.AttributeSet a)
                    throws javax.swing.text.BadLocationException { apply(fb, off, 0, s); }
            @Override public void remove(FilterBypass fb, int off, int len)
                    throws javax.swing.text.BadLocationException { apply(fb, off, len, ""); }
            @Override public void replace(FilterBypass fb, int off, int len, String s, javax.swing.text.AttributeSet a)
                    throws javax.swing.text.BadLocationException { apply(fb, off, len, s); }

            private void apply(FilterBypass fb, int off, int len, String s)
                    throws javax.swing.text.BadLocationException {
                javax.swing.text.Document d = fb.getDocument();
                String cur = d.getText(0, d.getLength());
                String ins = (s == null) ? "" : s;
                String left = cur.substring(0, off) + ins;
                String full = left + cur.substring(off + len);
                String formatted = groupNumber(full);
                int sig = sanitizeNumber(left.replace(",", "")).length();
                int caret = caretForSig(formatted, sig);
                fb.replace(0, d.getLength(), formatted, null);
                javax.swing.SwingUtilities.invokeLater(() ->
                        tf.setCaretPosition(Math.max(0, Math.min(caret, tf.getText().length()))));
            }
        });
    }

    private static int caretForSig(String formatted, int sig) {
        int count = 0;
        for (int i = 0; i < formatted.length(); i++) {
            if (formatted.charAt(i) != ',') {
                if (count == sig) return i;
                count++;
            }
        }
        return formatted.length();
    }

    /** 숫자 문자(선두 -, 숫자, 소수점 1개)만 남긴다. */
    private static String sanitizeNumber(String s) {
        StringBuilder b = new StringBuilder();
        boolean dot = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '-' && b.length() == 0) b.append('-');
            else if (ch >= '0' && ch <= '9') b.append(ch);
            else if (ch == '.' && !dot) { dot = true; b.append('.'); }
        }
        return b.toString();
    }

    /** 정수부에 천 단위 쉼표를 넣어 표시 문자열 생성(소수부 보존). */
    private static String groupNumber(String raw) {
        String s = sanitizeNumber(raw.replace(",", ""));
        if (s.isEmpty()) return "";
        boolean neg = s.startsWith("-");
        String body = neg ? s.substring(1) : s;
        int dot = body.indexOf('.');
        String ip = (dot < 0) ? body : body.substring(0, dot);
        String dp = (dot < 0) ? null : body.substring(dot + 1);
        StringBuilder g = new StringBuilder();
        int cnt = 0;
        for (int i = ip.length() - 1; i >= 0; i--) {
            g.append(ip.charAt(i));
            if (++cnt % 3 == 0 && i > 0) g.append(',');
        }
        g.reverse();
        String out = (neg ? "-" : "") + g;
        if (dot >= 0) out += "." + dp;
        return out;
    }

    private static String fmtComma(double v) {
        return groupNumber(fmt(v));
    }

    private void saveStats() {
        try {
            commitFields();
            model.save();
            updateStatDamage();
            updateSaveEnabled();   // 저장 후 변경 없음 → 버튼 비활성화
            Toast.show(this, "저장되었습니다");
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "숫자가 아닌 값이 있습니다.", "입력 오류", JOptionPane.WARNING_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "저장 실패: " + ex.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ──────────────────── 스탯 프리셋 바 ────────────────────

    /** 프리셋 선택 콤보 + 새로/이름변경/삭제 버튼. */
    private JPanel buildPresetBar() {
        JPanel bar = screen(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        JLabel lbl = new JLabel("프리셋");
        lbl.setForeground(Theme.TEXT);
        bar.add(lbl);
        presetCombo.setPreferredSize(new Dimension(130, presetCombo.getPreferredSize().height));
        presetCombo.addActionListener(e -> onPresetSelected());
        bar.add(presetCombo);
        bar.add(presetButton("추가", this::newPreset));
        bar.add(presetButton("이름변경", this::renamePreset));
        bar.add(presetButton("삭제", this::deletePreset));
        return bar;
    }

    private JButton presetButton(String text, Runnable onClick) {
        JButton b = new JButton(text);
        b.setMargin(new Insets(2, 8, 2, 8));
        b.addActionListener(e -> onClick.run());
        return b;
    }

    /** 콤보 항목을 모델의 프리셋 목록으로 다시 채우고 활성 프리셋을 선택(전환 이벤트 억제). */
    private void refreshPresetCombo() {
        presetSwitching = true;
        presetCombo.removeAllItems();
        for (String name : model.presetNames()) presetCombo.addItem(name);
        presetCombo.setSelectedItem(model.activePreset());
        presetSwitching = false;
    }

    /** 콤보에서 다른 프리셋 선택 시 전환하고 입력 필드를 다시 채운다(미저장 편집은 버려짐). */
    private void onPresetSelected() {
        if (presetSwitching) return;
        String name = (String) presetCombo.getSelectedItem();
        if (name == null || name.equals(model.activePreset())) return;
        try {
            model.switchPreset(name);
            populateStatFields();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "프리셋 전환 실패: " + ex.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void newPreset() {
        // 이름 + 시작값 선택(기존 프리셋 복사 / 빈 값)
        JTextField nameF = new JTextField(14);
        JComboBox<String> sourceBox = new JComboBox<>();
        sourceBox.addItem(BLANK_SOURCE);
        for (String n : model.presetNames()) sourceBox.addItem(COPY_PREFIX + n);
        sourceBox.setSelectedItem(COPY_PREFIX + model.activePreset());

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4); g.anchor = GridBagConstraints.WEST;
        g.gridx = 0; g.gridy = 0; panel.add(new JLabel("이름"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; panel.add(nameF, g);
        g.gridx = 0; g.gridy = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0; panel.add(new JLabel("시작값"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; panel.add(sourceBox, g);

        int ok = JOptionPane.showConfirmDialog(this, panel, "프리셋 추가",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        String name = nameF.getText().trim();
        if (name.isEmpty()) return;
        if (model.hasPreset(name)) {
            JOptionPane.showMessageDialog(this, "같은 이름의 프리셋이 이미 있습니다.", "이름 중복", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String src = (String) sourceBox.getSelectedItem();
        try {
            if (src != null && src.startsWith(COPY_PREFIX)) {
                model.addPresetFrom(name, src.substring(COPY_PREFIX.length()));
            } else {
                model.addPresetBlank(name);
            }
            refreshPresetCombo();
            populateStatFields();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "프리셋 추가 실패: " + ex.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void renamePreset() {
        String cur = model.activePreset();
        String name = JOptionPane.showInputDialog(this, "새 이름", cur);
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty() || name.equals(cur)) return;
        if (model.hasPreset(name)) {
            JOptionPane.showMessageDialog(this, "같은 이름의 프리셋이 이미 있습니다.", "이름 중복", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            model.renamePreset(cur, name);
            refreshPresetCombo();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "이름 변경 실패: " + ex.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deletePreset() {
        if (model.presetNames().size() <= 1) {
            JOptionPane.showMessageDialog(this, "마지막 프리셋은 삭제할 수 없습니다.", "삭제 불가", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String cur = model.activePreset();
        int ok = JOptionPane.showConfirmDialog(this, "'" + cur + "' 프리셋을 삭제할까요?",
                "프리셋 삭제", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        try {
            model.deletePreset(cur);
            refreshPresetCombo();
            populateStatFields();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "삭제 실패: " + ex.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ──────────────────── 딜 환산 설정 (정성적 스탯 · 스킬 레벨 커스텀) ────────────────────
    private JPanel buildConversionPage() {
        JPanel p = screen(new BorderLayout(10, 10));
        p.setBorder(BorderFactory.createEmptyBorder(10, 14, 12, 14));
        p.add(topBar("home", "딜 환산 설정"), BorderLayout.NORTH);

        JPanel card = new JPanel(new BorderLayout(0, 0));
        card.setBackground(Theme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(14, 18, 14, 18)));

        javax.swing.JEditorPane desc = new javax.swing.JEditorPane("text/html",
                "<html><body style='margin:0'>"
                + "· <b>체크한</b> 스탯은 입력한 <b>1단위당 딜 증가율</b>로 환산되어 비교 · 추천에 반영됩니다.<br>"
                + "· 체크 해제 시 <b>기본값</b>으로 처리됩니다"
                + "(방어 관통 · 대상 수 · 쿨타임 감소 등은 계산 안 함, 스킬 레벨은 기본 공격 계수 공식)."
                + " 값이 남아 있어도 무시됩니다.<br>"
                + "· 예: <b>방어 관통력</b>에 <b>0.5</b> → 방관 1%당 딜 +0.5%로 환산.<br>"
                + "· 전체 프리셋 <b>공용</b> 설정입니다."
                + "</body></html>");
        desc.setEditable(false);
        desc.setOpaque(false);
        desc.setForeground(Theme.MUTED);
        desc.putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        desc.setFont(new JLabel().getFont().deriveFont(12f));
        desc.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        card.add(desc, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 6);
        c.anchor = GridBagConstraints.WEST;
        javax.swing.event.DocumentListener live = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateConvSaveEnabled(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateConvSaveEnabled(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateConvSaveEnabled(); }
        };
        int row = 0;
        for (StatDef def : model.convertibleStats()) {
            c.gridx = 0; c.gridy = row; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            JCheckBox cb = new JCheckBox(def.name);
            cb.setOpaque(false);
            cb.setForeground(Theme.TEXT);
            form.add(cb, c);

            c.gridx = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            JTextField tf = new JTextField(6);
            tf.setHorizontalAlignment(SwingConstants.RIGHT);
            tf.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER),
                    BorderFactory.createEmptyBorder(4, 9, 4, 9)));
            tf.setDisabledTextColor(Theme.MUTED);
            installComma(tf);
            tf.getDocument().addDocumentListener(live);
            cb.addItemListener(e -> { setConvFieldLocked(tf, cb.isSelected()); updateConvSaveEnabled(); });
            form.add(tf, c);

            c.gridx = 2;
            JLabel suf = new JLabel(perUnitSuffix(def.unit));
            suf.setForeground(Theme.MUTED);
            form.add(suf, c);

            convChecks.put(def.id, cb);
            convFields.put(def.id, tf);
            row++;
        }
        JPanel formWrap = new JPanel(new BorderLayout());
        formWrap.setOpaque(false);
        formWrap.add(form, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(formWrap);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        card.add(sp, BorderLayout.CENTER);
        p.add(card, BorderLayout.CENTER);

        convSaveBtn = standardSaveButton(this::saveConversionsUI);
        JPanel btnRow = screen(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        btnRow.add(convSaveBtn);
        p.add(btnRow, BorderLayout.SOUTH);
        return p;
    }

    /** 화면 입력이 저장된 환산 설정과 하나라도 다르면 변경됨(dirty). */
    private boolean isConvDirty() {
        for (StatDef def : model.convertibleStats()) {
            ConversionIO.Conv saved = model.conversionOf(def.id);
            JCheckBox cb = convChecks.get(def.id);
            JTextField tf = convFields.get(def.id);
            if (cb.isSelected() != saved.enabled()) return true;
            if (Math.abs(parseField(tf) - saved.perUnit()) > 1e-9) return true;
        }
        return false;
    }

    private void updateConvSaveEnabled() {
        if (convSaveBtn != null) convSaveBtn.setEnabled(isConvDirty());
    }

    /** 환산 입력칸 잠금/해제: 해제(체크 OFF)면 회색 배경 + 비활성(입력 불가). */
    private void setConvFieldLocked(JTextField f, boolean unlocked) {
        f.setEnabled(unlocked);
        f.setBackground(unlocked ? Color.WHITE : new Color(0xEC, 0xEF, 0xF3));
    }

    /** 스탯 단위별 '1단위당' 접미사. */
    private static String perUnitSuffix(String unit) {
        switch (unit) {
            case "LEVEL": return "% / 1레벨";
            case "SEC":   return "% / 1초";
            case "FLAT":  return "% / 1개";
            default:       return "% / 1%";   // PCT
        }
    }

    private void populateConversion() {
        for (StatDef def : model.convertibleStats()) {
            ConversionIO.Conv conv = model.conversionOf(def.id);
            JCheckBox cb = convChecks.get(def.id);
            JTextField tf = convFields.get(def.id);
            cb.setSelected(conv.enabled());
            tf.setText(fmtComma(conv.perUnit()));
            setConvFieldLocked(tf, conv.enabled());
        }
        updateConvSaveEnabled();   // 막 불러온 직후엔 변경 없음 → 비활성
    }

    private void saveConversionsUI() {
        try {
            Map<String, ConversionIO.Conv> map = new LinkedHashMap<>();
            for (StatDef def : model.convertibleStats()) {
                boolean en = convChecks.get(def.id).isSelected();
                double v = parseField(convFields.get(def.id));
                map.put(def.id, new ConversionIO.Conv(en, v));
            }
            model.saveConversions(map);
            updateConvSaveEnabled();   // 저장 후 변경 없음 → 버튼 비활성화
            Toast.show(this, "저장되었습니다");
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

    /** 설정류 작은 아웃라인 버튼: 채움 없는 라운드 테두리 + 액센트 글자(메인 버튼과 시각적으로 구분). */
    private JButton settingsLink(String text) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
        b.setForeground(Theme.ACCENT_DARK);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.ACCENT, 1, true),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        return b;
    }

    /** 표준 저장 버튼: 액센트 색·고정 크기. 변경 없으면 비활성, 저장 시 토스트(공통 규칙). */
    private StyledButton standardSaveButton(Runnable onSave) {
        StyledButton b = new StyledButton("저장", true);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 15f));
        b.setPreferredSize(new Dimension(120, 44));
        b.addActionListener(e -> onSave.run());
        return b;
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

    /**
     * 메인에서 2단계 이상 들어간 화면용 상단 바: 좌측 [← 뒤로] + 가운데 타이틀 + 우측 [메인으로].
     * (메인/메인 직속 화면은 home=false로 우측 여백만 둔다.)
     */
    private JPanel topBar(String backTarget, String title, boolean home) {
        JPanel bar = screen(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
        JButton back = new JButton("← 뒤로");
        back.addActionListener(e -> show(backTarget));
        JLabel t = new JLabel(title, SwingConstants.CENTER);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 18f));
        t.setForeground(Theme.TEXT);
        bar.add(back, BorderLayout.WEST);
        bar.add(t, BorderLayout.CENTER);
        if (home) {
            JButton h = new JButton("메인으로");
            h.addActionListener(e -> show("home"));
            bar.add(h, BorderLayout.EAST);
        } else {
            JLabel spacer = new JLabel();
            spacer.setPreferredSize(back.getPreferredSize());
            bar.add(spacer, BorderLayout.EAST);
        }
        return bar;
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
