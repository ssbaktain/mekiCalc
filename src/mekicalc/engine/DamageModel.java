package mekicalc.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
 *   f_데미지범위 = (최소 + 최대) / 200   // 평균(숨은 100% 없음). 단 최소>최대면 최대/100 로 고정
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
public final class DamageModel implements DealModel {

    // 스탯 id 상수 (오타 방지)
    private static final String ATK_FLAT = "ATK_FLAT";
    private static final String ATK_PCT = "ATK_PCT";
    private static final String CRIT_RATE = "CRIT_RATE";
    private static final String CRIT_DMG = "CRIT_DMG";
    private static final String MAIN_STAT_FLAT = "MAIN_STAT_FLAT";
    private static final String MAIN_STAT_PCT = "MAIN_STAT_PCT";
    private static final String SUB_STAT_FLAT = "SUB_STAT_FLAT";   // 부스탯: 주스탯의 1/4 효과(공격력 4당 1, 스탯비례 0.25%/개)
    private static final String SUB_STAT_PCT = "SUB_STAT_PCT";     // 부스탯%(큐브 전용 — 스탯 프리셋엔 없음). 없으면 0.
    private static final String DEFENSE = "DEFENSE";               // 방어력: 일부 직업 패시브로 방어력의 일정 %만큼 주스탯 증가(직업별 계수)
    private static final String JOB = "JOB";
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
    private static final String LEVEL = "LEVEL";
    private static final String MAIN_STAT_PER_LEVEL = "MAIN_STAT_PER_LEVEL";
    private static final String SKILL_LEVEL_1 = "SKILL_LEVEL_1";
    private static final String SKILL_LEVEL_2 = "SKILL_LEVEL_2";
    private static final String SKILL_LEVEL_3 = "SKILL_LEVEL_3";
    private static final String SKILL_LEVEL_4 = "SKILL_LEVEL_4";

    /**
     * 곱배수 스탯: {@link #globalMultiplier}에 균일하게 곱해지는(모든 1타에 동일 비율) 스탯들.
     * 이 스탯들만으로 된 델타는 종합 DPS가 정확히 globalMultiplier 비율만큼 스케일되므로,
     * 전체 모델(시뮬·스킬 루프) 없이 globalMultiplier 비율 차이(닫힌식)로 증가율을 구할 수 있다(재사용용).
     * 평타뎀·스킬뎀(부분 곱)·스킬레벨·공속·쿨감·대상수·버프지속은 제외(전체 모델 필요).
     */
    public static final Set<String> MULT_STATS = Set.of(
            ATK_FLAT, ATK_PCT, MAIN_STAT_FLAT, MAIN_STAT_PCT, DMG, DMG_AMP, STAT_PROP_DMG,
            CRIT_RATE, CRIT_DMG, MIN_DMG_MULT, MAX_DMG_MULT, FINAL_DMG, BOSS_DMG, NORMAL_DMG,
            DEFENSE);   // 방어력은 주스탯으로 환산(mainStat) → 곱배수처럼 균일 반영. 닫힌식 경로 허용.

    /** delta가 곱배수 스탯({@link #MULT_STATS})으로만 이루어졌는지(닫힌식 빠른 경로 가능). 빈 델타는 false. */
    public boolean isPureMult(StatBlock delta) {
        Map<String, Double> m = delta.asMap();
        if (m.isEmpty()) return false;
        for (String id : m.keySet()) if (!MULT_STATS.contains(id)) return false;
        return true;
    }

    private final Map<String, StatDef> stats;
    private final Profile profile;
    /** 커스텀 환산 사용 스탯 → 1단위당 딜 증가율(%). 비어 있으면 모두 디폴트 처리. */
    private final Map<String, Double> customConv;

    public DamageModel(Map<String, StatDef> stats, Profile profile) {
        this(stats, profile, Map.of());
    }

    public DamageModel(Map<String, StatDef> stats, Profile profile, Map<String, Double> customConv) {
        this.stats = stats;
        this.profile = profile;
        this.customConv = (customConv == null) ? Map.of() : customConv;
    }

    /** 해당 스탯이 커스텀 환산(체크 ON)으로 설정됐는지. */
    private boolean custom(String id) {
        return customConv.containsKey(id);
    }

    /**
     * 커스텀 환산 곱연산 배수 = ∏ (1 + perUnit/100 × 스탯값). 환산 ON인 스탯만 곱한다.
     * 정성적 스탯(방관/대상수/쿨감 등)·스킬 레벨을 유저 지정 효율로 데미지에 반영하는 자리.
     */
    public double conversionFactor(StatBlock b) {
        double f = 1.0;
        for (Map.Entry<String, Double> e : customConv.entrySet()) {
            f *= 1 + e.getValue() / 100.0 * v(b, e.getKey());
        }
        return f;
    }

    /** 스탯 id가 커스텀 환산(체크 ON)으로 설정됐는지(외부 딜 모델용 공개). */
    public boolean isCustomConv(String id) { return customConv.containsKey(id); }

    /** 스탯 id 하나의 커스텀 환산 곱연산 배수(= 1 + 단위당%/100 × 값). custom 아니면 1.0. */
    public double customConvFactor(StatBlock b, String id) {
        Double per = customConv.get(id);
        return per == null ? 1.0 : 1 + per / 100.0 * v(b, id);
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
        // 공격력(깡)에는 주스탯이 이미 포함(표시값)이라 여기서 또 더하지 않는다.
        // (주스탯 옵션을 더할 때의 깡공 상승분은 combine()에서 ATK_FLAT에 반영된다.)
        // 단, 방어력 유래 주스탯(다크나이트 아이언 월 등)은 입력 공격력에 빠져 있으므로 여기서 유효주스탯을 더한다.
        double baseAtk = (v(b, ATK_FLAT) + defenseMainStat(b)) * (1 + v(b, ATK_PCT) / 100.0);

        // 스탯 비례 데미지%는 유저 입력값을 그대로 사용한다(패시브 등으로 주스탯/100과 소폭 다를 수 있음).
        // 숨은 100% 방식 → factor = 1 + 스탯비례%/100. 주스탯이 옵션으로 늘면 combine()에서 함께 보정한다.
        double statPropFrac = v(b, STAT_PROP_DMG) / 100.0;
        double basicPct = v(b, BASIC_ATK_DMG); // 기본 공격 데미지%만 (스킬 데미지% 제외)

        double fCrit = 1 + Math.min(v(b, CRIT_RATE) / 100.0, 1.0) * (v(b, CRIT_DMG) / 100.0);
        // 데미지범위: 최소·최대의 평균. 단 최소>최대면 데미지가 최댓값(천장)으로 고정.
        double minMult = v(b, MIN_DMG_MULT);
        double maxMult = v(b, MAX_DMG_MULT);
        double fRange = (minMult > maxMult) ? maxMult / 100.0 : (minMult + maxMult) / 200.0;

        double fFinal = 1 + v(b, FINAL_DMG) / 100.0;
        // 기본 공격 계수 = base × (1 + 레벨당증가 × 유효 스킬 레벨). 결과를 그대로 직접 곱함.
        double fSkillLevel = profile.skillLevelBase * (1 + profile.skillLevelPerLevel * effectiveSkillLevel(b));

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
        // 커스텀 환산(정성적·스킬레벨 유저 지정 효율)이 있으면 곱연산으로 반영.
        double conv = conversionFactor(b);
        if (Math.abs(conv - 1.0) > 1e-12) out.put("커스텀환산", conv);
        return out;
    }

    /**
     * delta 안에 데미지로 환산하지 않는 정성적 스탯(공속/방관 등)이 포함됐는지.
     * 단, 커스텀 환산이 켜진(custom) 정성적 스탯은 이제 수치로 환산되므로 제외한다.
     */
    public boolean hasQualitative(StatBlock delta) {
        for (String id : delta.asMap().keySet()) {
            StatDef def = stats.get(id);
            if (def != null && def.qualitative && !custom(id)) return true;
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

    /**
     * 같은 스탯 옵션값 여러 개를 하나의 델타값으로 모을 때의 규칙(큐브 한 부위 내 여러 줄 집계).
     * 체감(DIMINISHING) 스탯(방관·공속)은 슬롯끼리도 merge(곱연산)로 접어야 인게임과 일치한다
     * (방관 12+12 → 22.56, 24 아님). SUM·MULT_PCT(최종뎀)는 단순 합산 유지.
     */
    @Override
    public double aggregate(String statId, double a, double b) {
        StatDef def = stats.get(statId);
        if (def != null && def.combine == StatDef.Combine.DIMINISHING) return def.merge(a, b);
        return a + b;
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
        // '1레벨당 주스탯' 옵션은 누적 스탯이 아니라, 즉시 (레벨 × 값)만큼 깡주스탯으로 환산한다.
        double perLevelDelta = delta.get(MAIN_STAT_PER_LEVEL);
        if (perLevelDelta != 0) {
            r.set(MAIN_STAT_PER_LEVEL, current.get(MAIN_STAT_PER_LEVEL)); // 누적 취소(기준 유지)
            r.set(MAIN_STAT_FLAT, r.get(MAIN_STAT_FLAT) + current.get(LEVEL) * perLevelDelta);
        }
        // 주스탯(주스탯깡/주스탯%)이 바뀌면 게임처럼 늘어난 주스탯만큼 공격력(깡)에도 1:1 반영한다.
        // 입력 공격력은 현재 주스탯이 이미 포함된 표시값이라, '증가분'만 더하면 baseline은 그대로 유지된다.
        double mainDelta = mainStat(r) - mainStat(current);
        if (mainDelta != 0) {
            r.set(ATK_FLAT, r.get(ATK_FLAT) + mainDelta);
            // 늘어난 주스탯은 스탯 비례 데미지%도 함께 올린다(주스탯 100당 1%).
            r.set(STAT_PROP_DMG, r.get(STAT_PROP_DMG) + mainDelta / 100.0);
        }
        // 부스탯: 주스탯의 정확히 1/4(공격력 4당 1 → ×0.25, 스탯 비례 데미지 개당 0.25% → ×0.0025).
        // 주스탯과 동일하게 '증가분'만 더해 입력 표시값(공격력·스탯비례)의 baseline은 유지한다.
        double subDelta = subStat(r) - subStat(current);
        if (subDelta != 0) {
            r.set(ATK_FLAT, r.get(ATK_FLAT) + subDelta / 4.0);
            r.set(STAT_PROP_DMG, r.get(STAT_PROP_DMG) + subDelta * 0.0025);
        }
        // 방어력 유래 주스탯이 옵션으로 늘면(큐브/장비) 증가분을 스탯비례에 반영한다.
        // 공격력 기여는 breakdown/globalMultiplier가 DEFENSE로 직접 반영하므로 여기선 ATK_FLAT을 건드리지 않는다.
        double defMainDelta = defenseMainStat(r) - defenseMainStat(current);
        if (defMainDelta != 0) {
            r.set(STAT_PROP_DMG, r.get(STAT_PROP_DMG) + defMainDelta / 100.0);
        }
        return r;
    }

    /**
     * combine 의 역연산: combine(current, delta) == r 인 current 를 복원한다.
     * 주스탯 변화로 인한 공격력 가산까지 되돌린다. 부위 옵션 제거(교체 비교)에 쓰인다.
     */
    public StatBlock uncombine(StatBlock r, StatBlock delta) {
        double mainAfter = mainStat(r);             // r의 주스탯(레벨당 환산 반영 후)
        double subAfter = subStat(r);               // r의 부스탯(옵션 반영 후)
        double level = r.get(LEVEL);                // 레벨은 combine으로 변하지 않음
        double perLevelDelta = delta.get(MAIN_STAT_PER_LEVEL);
        StatBlock before = new StatBlock(r);
        // 공격력(ATK_FLAT)/레벨당주스탯을 제외한 모든 delta 스탯을 역연산(주스탯 포함 → before 주스탯 복원).
        for (Map.Entry<String, Double> e : delta.asMap().entrySet()) {
            String id = e.getKey();
            if (id.equals(ATK_FLAT) || id.equals(MAIN_STAT_PER_LEVEL)) continue;
            StatDef def = stats.get(id);
            if (id.equals(MAIN_STAT_FLAT)) {
                // 레벨당 환산으로 더해졌던 깡주스탯을 먼저 빼고, MAIN_STAT_FLAT 옵션(SUM)을 역연산.
                double flat = r.get(MAIN_STAT_FLAT) - level * perLevelDelta;
                before.set(MAIN_STAT_FLAT, def != null ? def.unmerge(flat, e.getValue()) : flat - e.getValue());
            } else {
                double un = (def != null) ? def.unmerge(r.get(id), e.getValue()) : r.get(id) - e.getValue();
                before.set(id, un);
            }
        }
        // delta에 MAIN_STAT_FLAT가 없는데 레벨당 옵션만 있었다면 환산분을 따로 제거.
        if (perLevelDelta != 0 && !delta.asMap().containsKey(MAIN_STAT_FLAT)) {
            before.set(MAIN_STAT_FLAT, before.get(MAIN_STAT_FLAT) - level * perLevelDelta);
        }
        // 주스탯·부스탯 증가분만큼 더해졌던 공격력 가산·스탯비례 가산을 되돌린 뒤, ATK 옵션(SUM)도 제거.
        double mainDelta = mainAfter - mainStat(before);
        double subDelta = subAfter - subStat(before);
        // 방어력 옵션 제거분: before의 DEFENSE는 위 for에서 SUM 역연산으로 복원됨 → 스탯비례 가산을 되돌린다.
        double defMainDelta = defenseMainStat(r) - defenseMainStat(before);
        before.set(ATK_FLAT, r.get(ATK_FLAT) - mainDelta - subDelta / 4.0 - delta.get(ATK_FLAT));
        before.set(STAT_PROP_DMG, before.get(STAT_PROP_DMG) - mainDelta / 100.0 - subDelta * 0.0025 - defMainDelta / 100.0);
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

    /**
     * 방어력 유래 유효주스탯 = 방어력 × 직업계수 × (1 + 주스탯%/100).
     *
     * <p>일부 직업 패시브(다크나이트 아이언 월)는 '방어력의 n%만큼 주스탯이 증가'한다 → 진짜 깡주스탯이라
     * 주스탯%까지 받은 뒤 공격력(1:1)·스탯비례(÷100)에 기여한다(부스탯과 달리 주스탯% 적용).
     * <p>입력 공격력·주스탯엔 이 방어력 효과가 빠져 있다고 보고, 공격력 기여는 {@link #breakdown}/
     * {@link #globalMultiplier}가 baseAtk에 직접 더한다(baseline·델타 공통). 스탯비례 기여는 baseline은
     * 프론트가 STAT_PROP에 선반영하고, 델타(방어력 옵션 추가)는 {@link #combine}이 반영한다.
     */
    private double defenseMainStat(StatBlock b) {
        // 아이언 월 주스탯 증가분은 "선반영" — 주스탯% 곱셈의 바깥에 붙는 최종 flat 값이라 주스탯%를 곱하지 않는다.
        // (인게임 실측: 깡주스탯40303·주스탯%121·방어력34802·계수0.1366 → 스탯비례 방어력분 47.54%p, 주스탯% 미적용 시에만 일치)
        return v(b, DEFENSE) * defenseToMainRatio(b);
    }

    /**
     * 직업별 방어력→주스탯 변환 계수(방어력 1당 주스탯 몇).
     * <ul>
     *   <li>다크나이트: 아이언 월(2차 패시브) — 방어력의 10%를 주스탯으로 환산하되, 스킬 레벨당 +0.3%p 비례
     *       (base 10% × growth 0.3). 총스킬레벨 = 2차 레벨 파생분 + 모스렙 + 2스렙.
     *       검증(인게임 표시=소수 2째자리 버림): 108렙·2스렙32 → 13.66%(표시 13.6), 2스렙43 → 13.99%(표시 13.9).</li>
     *   <li>그 외(나이트로드 등): 0 — 해당 패시브 없음 → 방어력이 딜에 영향 없음.</li>
     * </ul>
     * TODO: 직업별 패시브 계수는 추후 스킬 데이터 구동으로 일반화(현재 다크나이트만 하드코딩).
     */
    private double defenseToMainRatio(StatBlock b) {
        // 다크나이트 아이언 월 / 비숍 인빈서블 — 둘 다 2차 패시브(open 38), '방어력의 10%만큼 주스탯' 동일 계수.
        int job = (int) v(b, JOB);
        if (job != JOB_DARK_KNIGHT && job != JOB_BISHOP) return 0.0;
        // 2차 스킬 레벨 파생분: 30(2차 전직)~59렙은 레벨당 3, 60렙(3차)부터 90 고정.
        double baseSkillLevel = Math.max(0.0, (Math.min(v(b, LEVEL), 60) - 30) * 3);
        double totalSkillLevel = baseSkillLevel + v(b, SKILL_LEVELS) + v(b, SKILL_LEVEL_2);
        return IRON_WALL_BASE * (1 + IRON_WALL_GROWTH * totalSkillLevel);
    }

    private static final int JOB_DARK_KNIGHT = 2;
    private static final int JOB_BISHOP = 3;
    private static final double IRON_WALL_BASE = 0.10;      // 아이언 월 명목 계수(방어력의 10%)
    private static final double IRON_WALL_GROWTH = 0.003;   // 스킬 레벨당 상승비(growth.statPassive 0.3 → 0.003)

    /** 유효 부스탯 = 부스탯(+) × (1 + 부스탯%/100). 스탯 프리셋엔 +만 있고 %는 큐브 계산 시에만 들어온다(없으면 0). */
    private double subStat(StatBlock b) {
        return v(b, SUB_STAT_FLAT) * (1 + v(b, SUB_STAT_PCT) / 100.0);
    }

    /**
     * 기본 공격 스킬 데미지 계수에 쓰이는 '유효 스킬 레벨'.
     *
     * <p>= 레벨로 인한 현재 차수 기본 공격 스킬 레벨 + 현재 차수 스킬 레벨(+) + 모든 스킬 레벨(+).
     * <ul>
     *   <li>전직 차수: ≤9 미전직 / 10~29 1차 / 30~59 2차 / 60~99 3차 / 100~ 4차.</li>
     *   <li>레벨업마다 현재 차수 스킬이 3씩 오르고, 전직 직후 레벨(예: 100)에는 현재 차수 스킬이 0이다.
     *       → 레벨로 인한 현재 차수 스킬 레벨 = 3 × (레벨 − 차수시작레벨).</li>
     *   <li>미전직(≤9)은 스킬 레벨 의미가 없어 레벨/차수분은 0(모든 스킬 레벨만 합산).</li>
     * </ul>
     */
    private double effectiveSkillLevel(StatBlock b) {
        double level = v(b, LEVEL);
        double levelDerived; String tierId;
        if (level >= 100)     { levelDerived = 3 * (level - 100); tierId = SKILL_LEVEL_4; }
        else if (level >= 60) { levelDerived = 3 * (level - 60);  tierId = SKILL_LEVEL_3; }
        else if (level >= 30) { levelDerived = 3 * (level - 30);  tierId = SKILL_LEVEL_2; }
        else if (level >= 10) { levelDerived = 3 * (level - 10);  tierId = SKILL_LEVEL_1; }
        else                  { levelDerived = 0;                 tierId = null; } // 미전직
        // 커스텀 환산이 켜진 스킬 레벨 스탯은 기본공격계수에서 빼고(conversionFactor로 반영) 중복 계산을 막는다.
        // 레벨로 인한 현재 차수 스킬 레벨(levelDerived)은 LEVEL에서 파생되므로 항상 기본 공식에 둔다.
        double tierStat = (tierId != null && !custom(tierId)) ? v(b, tierId) : 0;
        double allLevels = custom(SKILL_LEVELS) ? 0 : v(b, SKILL_LEVELS);
        return levelDerived + tierStat + allLevels;
    }

    private double v(StatBlock b, String id) {
        return b.get(id);
    }

    // ---- 나이트로드 DPS 모델용 G 훅 (스킬 무관 공통 배수) ----

    /**
     * 스킬 무관 공통 배수 G = 공격력 × 데미지 × 스탯비례 × 크리 × 데미지범위 × 최종데미지 × 대상(보스/일반몹).
     * <p>스킬별로 다른 항(기본 공격 계수, 기본공격뎀%, 스킬뎀%)과 커스텀환산은 제외한다.
     * 각 스킬의 계수·타입별 데미지%는 {@link mekicalc.engine.skill.NightlordDps}가 따로 곱한다. mode A 기준.
     */
    public double globalMultiplier(StatBlock b, boolean boss) {
        // 방어력 유래 유효주스탯을 공격력에 더한다(입력 공격력엔 빠져 있음). breakdown과 동일 규칙.
        double baseAtk = (v(b, ATK_FLAT) + defenseMainStat(b)) * (1 + v(b, ATK_PCT) / 100.0);
        double fDmg = (1 + v(b, DMG) / 100.0) * (1 + v(b, DMG_AMP) / 100.0);
        double fStatProp = 1 + v(b, STAT_PROP_DMG) / 100.0;
        double fCrit = 1 + Math.min(v(b, CRIT_RATE) / 100.0, 1.0) * (v(b, CRIT_DMG) / 100.0);
        double minMult = v(b, MIN_DMG_MULT), maxMult = v(b, MAX_DMG_MULT);
        double fRange = (minMult > maxMult) ? maxMult / 100.0 : (minMult + maxMult) / 200.0;
        double fFinal = 1 + v(b, FINAL_DMG) / 100.0;
        double target = 1 + (boss ? v(b, BOSS_DMG) : v(b, NORMAL_DMG)) / 100.0;
        return baseAtk * fDmg * fStatProp * fCrit * fRange * fFinal * target;
    }

    /** 기본 공격 데미지% 배수(평타 한정). */
    public double basicAtkDmgFactor(StatBlock b) { return 1 + v(b, BASIC_ATK_DMG) / 100.0; }

    /** 스킬 데미지% 배수(비평타 스킬용). */
    public double skillDmgFactor(StatBlock b) { return 1 + v(b, SKILL_DMG) / 100.0; }

    public Map<String, StatDef> stats() {
        return stats;
    }

    public Profile profile() {
        return profile;
    }
}
