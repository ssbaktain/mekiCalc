package mekicalc.timeline;

/**
 * 쿨다운 계산(인게임 실측 일치, 검증된 SkillDpsModel.cooldown 공식 이식).
 *
 * <pre>
 * ① 쿨감% 먼저(체감누적 실효값, 상한 100%):  cd = rawCd × (1 − cdr%/100)
 * ② flat 감소(특수규칙):
 *      남은 쿨 7초 이상 구간 → flat 1초당 1초 (1:1)
 *      남은 쿨 7초 미만 구간 → flat 1초당 0.5초
 *      하한 4초
 * </pre>
 * 모든 인자·반환은 초 단위.
 */
public final class Cooldown {

    private Cooldown() {}

    public static double cooldown(double rawCd, double cdrPct, double cdrFlat) {
        double cd = rawCd * (1 - Math.min(cdrPct, 100.0) / 100.0);
        if (cdrFlat > 0) {
            double flat = cdrFlat;
            if (cd > 7) {
                double full = Math.min(flat, cd - 7);   // 7초까지는 1:1 감소
                cd -= full;
                flat -= full;
            }
            cd -= flat * 0.5;                            // 7초 밑은 0.5배 체감
        }
        // 하한 4초: 감소가 걸린 경우엔 어떤 소스(%·고정)든 4초 밑으로 못 뚫음.
        // (원래 쿨이 4초 미만인 스킬은 올리지 않음)
        if ((cdrPct > 0 || cdrFlat > 0) && rawCd >= 4 && cd < 4) cd = 4;
        return cd;
    }
}
