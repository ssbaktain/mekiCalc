package mekicalc.ui;

import java.awt.Color;

/** 앱 전반의 색/간격 팔레트. 차분한 라이트 테마 + 인디고 액센트. */
public final class Theme {

    public static final Color BG = new Color(0xF4, 0xF6, 0xF9);
    public static final Color CARD = Color.WHITE;
    public static final Color BORDER = new Color(0xD7, 0xDC, 0xE3);
    public static final Color ACCENT = new Color(0x3B, 0x6F, 0xE0);
    public static final Color ACCENT_DARK = new Color(0x2C, 0x59, 0xBE);
    public static final Color ACCENT_SOFT = new Color(0xEC, 0xF1, 0xFC);
    public static final Color TEXT = new Color(0x23, 0x28, 0x30);
    public static final Color MUTED = new Color(0x8A, 0x91, 0x9C);
    public static final Color GRADE_RED = new Color(0xC8, 0x2A, 0x2A);

    /** 등급별 색(밝은 배경 기준): 노말 진회색 · 레어 파랑 · 에픽 보라 · 유니크 금색 · 레전드리 초록 · 미스틱 빨강. */
    public static Color gradeColor(String code) {
        if (code == null) return TEXT;
        switch (code) {
            case "NORMAL":    return new Color(0x4D, 0x4D, 0x4D);
            case "RARE":      return new Color(0x1D, 0x4E, 0xD8);
            case "EPIC":      return new Color(0x7C, 0x3A, 0xED);
            case "UNIQUE":    return new Color(0xB8, 0x86, 0x0B);
            case "LEGENDARY": return new Color(0x15, 0x80, 0x3D);
            case "MYTHIC":    return new Color(0xD8, 0x2C, 0x2C);
            default:          return TEXT;
        }
    }

    private Theme() {}
}
