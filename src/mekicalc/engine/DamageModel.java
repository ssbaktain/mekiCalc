package mekicalc.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import mekicalc.model.Profile;
import mekicalc.model.StatBlock;
import mekicalc.model.StatDef;

/**
 * 메이플 키우기 데미지 모델.
 *
 * <p>스탯 묶음(표시값 기준)으로부터 상대적인 최종 데미지 지표를 계산한다.
 * 절대 데미지가 아니라 "현재 대비 비교"를 위한 값이다.
 *
 * <h3>공식 (mode A: 카테고리끼리 곱연산, 기본값)</h3>
 * <pre>
 *   주스탯      = 주스탯깡 × (1 + 주스탯%/100)                  // 스탯비례 계산에만 사용
 *   기본공격력  = 공격력깡 × (1 + 공격력%/100)                  // 공격력(깡)에 주스탯이 이미 포함(표시값)
 *
 *   f_데미지    = (1 + 데미지/100) × (1 + 데미지증폭/100)        // 증폭은 데미지를 곱연산
 *   f_스탯비례  = 1 + 주스탯/10000                              // 스탯비례데미지%(=주스탯/100)의 숨은-100% factor
 *   f_기본공격뎀 = 1 + 기본공격데미지/100                        // 기본 공격이므로 스킬데미지%는 제외
 *   f_크리      = 1 + min(크리확률/100, 1) × (크리데미지/100)
 *   f_데미지범위 = (최소 + 최대) / 200   // 평균(숨은 100% 없음). 단 최소>최대면 최소/100 로 고정
 *   f_최종      = (1 + 최종데미지/100)
 *   f_기본공격계수 = 기본계수(2.9) × (1 + 0.004 × 스킬레벨)   // 0이면 0데미지, 결과를 그대로 곱함
 *   공통배수    = 기본공격력 × f_데미지 × f_스탯비례 × f_기본공격뎀 × f_크리 × f_데미지범위 × f_최종 × f_기본공격계수
 *
 *   일반몹 데미지 = 공통배수 × (1 + 일반몹데미지/100)
 *   보스몹 데미지 = 공통배수 × (1 + 보스몹데미지/100)
 *   (예상 데미지 = 일반몹 대상 기본 공격)
 * </pre>
 *
 * <p><b>공격속도·방어관통은 데미지로 환산하지 않는다.</b> 정확한 식과 몬스터별 방어력이
 * 불명확하기 때문이다(stats.json에서 {@code qualitative:true}). 이런 옵션은 수치 대신
 * "정성적 고효율 후보"로 따로 표시한다({@link #hasQualitative}).
 *
 * <h3>mode B (profile.additiveDamage=true)</h3>
 * 데미지·스탯비례·공격타입·대상을 하나로 합연산하고 최종 데미지만 따로 곱한다.
 */
public final class DamageModel {

    // 스탯 id 상수 (오타 방지)
    private static final String ATK_FLAT = "ATK_FLAT";
    private static final String ATK_PCT = "ATK_PCT";
    private static final String CRIT_RATE = "CRIT_RATE";
    private static final String CRIT_DMG = "CRIT_DMG";
    private static final String MAIN_STAT_FLAT = "MAIN_STAT_FLAT";
    private static final String MAIN_STAT_PCT = "MAIN_STAT_PCT";
    private static final String STAT_PROP_DMG = "STAT_PROP_DMG";
    private static final String DMG = "DMG";
    private static final String DMG_AMP = "DMG_AMP";
    private static final String BASIC_ATK_DMG = "BASIC_ATK_DMG";
    private static final String SKILL_DMG = "SKILL_DMG";
    private static final String BOSS_DMG = "BOSS_DMG";
    private static final String NORMAL_DMG = "NORMAL_DMG";
    private static final String MIN_DMG_MULT = "MIN_DMG_MULT";
    private static final String MAX_DMG_MULT = "MAX_DMG_MULT";
    private static final String FINAL_DMG = "FINAL_DMG";
    private static final String SKILL_LEVELS = "SKILL_LEVELS";
    private static final String ATK_SPEED = "ATK_SPEED";

    private final Map<String, StatDef> stats;
    private final Profile profile;

    public DamageModel(Map<String, StatDef> stats, Profile profile) {
        this.stats = stats;
        this.profile = profile;
    }

    /** 기준 데미지 = 일반몹 대상 기본 공격(예상 데미지 기준). */
    public double finalDamage(StatBlock b) {
        return finalDamageNormal(b);
    }

    /** 일반 몬스터 대상 데미지 = 공통배수 × (1 + 일반몹데미지/100). */
    public double finalDamageNormal(StatBlock b) {
        return commonProduct(b) * (1 + v(b, NORMAL_DMG) / 100.0);
    }

    /** 보스 몬스터 대상 데미지 = 공통배수 × (1 + 보스몹데미지/100). */
    public double finalDamageBoss(StatBlock b) {
        return commonProduct(b) * (1 + v(b, BOSS_DMG) / 100.0);
    }

    /** 대상(일반/보스)을 제외한 공통 배수들의 곱. */
    private double commonProduct(StatBlock b) {
        double product = 1.0;
        for (double f : breakdown(b).values()) product *= f;
        return product;
    }

    /**
     * 대상(일반/보스)을 제외한 공통 배수 항목들을 순서대로 반환.
     * 모두 곱한 뒤 (1 + 일반몹/보스몹 데미지/100)을 곱하면 각 대상 데미지가 된다.
     * 기준 시나리오가 기본 공격이라 스킬 데미지%는 포함하지 않는다.
     */
    public Map<String, Double> breakdown(StatBlock b) {
        double mainStat = mainStat(b);
        // 공격력(깡)에는 주스탯이 이미 포함(표시값)이라 여기서 또 더하지 않는다.
        // (주스탯 옵션을 더할 때의 깡공 상승분은 combine()에서 ATK_FLAT에 반영된다.)
        double baseAtk = v(b, ATK_FLAT) * (1 + v(b, ATK_PCT) / 100.0);

        // 스탯 비례 데미지% = 주스탯/100, 숨은 100% 방식 → factor = 1 + 스탯비례%/100 = 1 + 주스탯/10000.
        double statPropFrac = mainStat / 10000.0;
        double basicPct = v(b, BASIC_ATK_DMG); // 기본 공격 데미지%만 (스킬 데미지% 제외)

        double fCrit = 1 + Math.min(v(b, CRIT_RATE) / 100.0, 1.0) * (v(b, CRIT_DMG) / 100.0);
        // 데미지범위: 최소·최대의 평균. 단 최소>최대면 데미지가 최소뎀으로 고정.
        double minMult = v(b, MIN_DMG_MULT);
        double maxMult = v(b, MAX_DMG_MULT);
        double fRange = (minMult > maxMult) ? minMult / 100.0 : (minMult + maxMult) / 200.0;

        double fFinal = 1 + v(b, FINAL_DMG) / 100.0;
        // 기본 공격 계수 = base × (1 + 레벨당증가 × 스킬레벨). 결과를 그대로 직접 곱함.
        double fSkillLevel = profile.skillLevelBase * (1 + profile.skillLevelPerLevel * v(b, SKILL_LEVELS));

        Map<String, Double> out = new LinkedHashMap<>();
        out.put("기본공격력", baseAtk);

        if (profile.additiveDamage) {
            // mode B: 데미지 옵션 카테고리를 모두 합연산(대상은 별도)
            double sumFrac = v(b, DMG) / 100.0 * (1 + v(b, DMG_AMP) / 100.0)
                    + statPropFrac + basicPct / 100.0;
            out.put("데미지블록(합연산)", 1 + sumFrac);
        } else {
            // mode A: 카테고리끼리 곱연산
            out.put("데미지", (1 + v(b, DMG) / 100.0) * (1 + v(b, DMG_AMP) / 100.0));
            out.put("스탯비례", 1 + statPropFrac);
            out.put("기본공격뎀", 1 + basicPct / 100.0);
        }

        out.put("크리티컬", fCrit);
        out.put("데미지범위", fRange);
        out.put("최종데미지", fFinal);
        out.put("기본공격계수", fSkillLevel);
        return out;
    }

    /** delta 안에 데미지로 환산하지 않는 정성적 스탯(공속/방관 등)이 포함됐는지. */
    public boolean hasQualitative(StatBlock delta) {
        for (String id : delta.asMap().keySet()) {
            StatDef def = stats.get(id);
            if (def != null && def.qualitative) return true;
        }
        return false;
    }

    /**
     * 공속 DPS 배수 = 1 + 공속/100. 공속은 초당 타수를 늘리므로 1타 데미지(finalDamage)엔
     * 넣지 않고, 효율/비교(increase)에서만 곱한다. 검증한 1타 절대값을 보존하기 위함.
     */
    public double attackSpeedFactor(StatBlock b) {
        return 1 + v(b, ATK_SPEED) / 100.0;
    }

    /**
     * delta 만큼 스탯을 추가했을 때의 데미지 출력(DPS) 증가율(0.05 == +5%).
     * 스탯별 combine 규칙(합산/체감/곱연산)을 반영해 합친 뒤, 공속 DPS 배수까지 포함해 비교한다.
     */
    public double increase(StatBlock current, StatBlock delta) {
        double before = output(current);
        double aft = output(combine(current, delta));
        if (before <= 0) return 0;
        return aft / before - 1.0;
    }

    /** current 에 delta 를 각 스탯의 combine 규칙대로 합친 새 StatBlock. */
    public StatBlock combine(StatBlock current, StatBlock delta) {
        StatBlock r = new StatBlock(current);
        for (Map.Entry<String, Double> e : delta.asMap().entrySet()) {
            StatDef def = stats.get(e.getKey());
            double cur = current.get(e.getKey());
            double merged = (def != null)
                    ? def.merge(cur, e.getValue())
                    : cur + e.getValue(); // 정의 없으면 합산 폴백
            r.set(e.getKey(), merged);
        }
        // 주스탯(주스탯깡/주스탯%)이 바뀌면 게임처럼 늘어난 주스탯만큼 공격력(깡)에도 1:1 반영한다.
        // 입력 공격력은 현재 주스탯이 이미 포함된 표시값이라, '증가분'만 더하면 baseline은 그대로 유지된다.
        double mainDelta = mainStat(r) - mainStat(current);
        if (mainDelta != 0) {
            r.set(ATK_FLAT, r.get(ATK_FLAT) + mainDelta);
        }
        return r;
    }

    /**
     * combine 의 역연산: combine(current, delta) == r 인 current 를 복원한다.
     * 주스탯 변화로 인한 공격력 가산까지 되돌린다. 부위 옵션 제거(교체 비교)에 쓰인다.
     */
    public StatBlock uncombine(StatBlock r, StatBlock delta) {
        double mainAfter = mainStat(r);
        StatBlock before = new StatBlock(r);
        // 공격력(ATK_FLAT)을 제외한 모든 delta 스탯을 역연산(주스탯 포함 → before 주스탯 복원).
        for (Map.Entry<String, Double> e : delta.asMap().entrySet()) {
            String id = e.getKey();
            if (id.equals(ATK_FLAT)) continue;
            StatDef def = stats.get(id);
            double un = (def != null) ? def.unmerge(r.get(id), e.getValue()) : r.get(id) - e.getValue();
            before.set(id, un);
        }
        // 주스탯 증가분만큼 더해졌던 공격력 가산을 되돌린 뒤, ATK 옵션(SUM)도 제거.
        double mainDelta = mainAfter - mainStat(before);
        before.set(ATK_FLAT, r.get(ATK_FLAT) - mainDelta - delta.get(ATK_FLAT));
        return before;
    }

    /** 출력 데미지(DPS 기준) = 1타 데미지 × 공속 배수. 효율/비교의 기준값. */
    public double output(StatBlock b) {
        return finalDamage(b) * attackSpeedFactor(b);
    }

    /** 표시 주스탯 = 주스탯깡 × (1 + 주스탯%/100). 스탯비례 데미지·공격력 가산에 쓰인다. */
    private double mainStat(StatBlock b) {
        return v(b, MAIN_STAT_FLAT) * (1 + v(b, MAIN_STAT_PCT) / 100.0);
    }

    private double v(StatBlock b, String id) {
        return b.get(id);
    }

    public Map<String, StatDef> stats() {
        return stats;
    }

    public Profile profile() {
        return profile;
    }
}
