package mekicalc.timeline;

/**
 * 성장 배수. 유효스킬레벨(eff)에 대해 성장 슬롯별 배율을 준다.
 * <pre>
 *   12 → 1 + 0.005·eff      (0.5%/레벨)
 *   21 → 1 + 0.004·eff      (0.4%/레벨)
 *   22 → 1 + 0.003·eff      (0.3%/레벨)
 *   23 → 메이플 용사: 1 + 0.05·min(eff,120) + 0.01·max(0,eff−120)
 *   그 외/null → 1.0 (성장 없음)
 * </pre>
 */
public final class Growth {

    private Growth() {}

    public static double factor(Integer slot, int eff) {
        if (slot == null) return 1.0;
        return switch (slot) {
            case 12 -> 1 + 0.005 * eff;
            case 21 -> 1 + 0.004 * eff;
            case 22 -> 1 + 0.003 * eff;
            case 23 -> 1 + 0.05 * Math.min(eff, 120) + 0.01 * Math.max(0, eff - 120);
            default -> 1.0;
        };
    }
}
