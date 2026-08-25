package mekicalc.timeline;

import java.util.HashMap;
import java.util.Map;

/**
 * 사용자 스탯 프리셋(선반영 버프까지 녹아 있는 base). 불변으로 취급하고 계산 시 주입만 한다.
 *
 * <p>내부는 문자열 키 → double 맵. 키 이름은 엔진 정규 이름(ATK_FLAT, ATK_PCT ...)을 쓴다.
 * 없는 키는 0. 앱 프리셋(JSON) → 이 정규 키로의 매핑은 로더가 담당(후속).
 */
public final class Preset {

    private final Map<String, Double> s;

    public Preset(Map<String, Double> stats) {
        this.s = new HashMap<>(stats);
    }

    public double get(String key) {
        return s.getOrDefault(key, 0.0);
    }

    // ── 공식 인자 접근자 ────────────────────────────────
    public double atkFlat()        { return get("ATK_FLAT"); }
    public double atkPct()         { return get("ATK_PCT"); }
    public double defense()        { return get("DEFENSE"); }
    public double critRate()       { return get("CRIT_RATE"); }
    public double critDmg()        { return get("CRIT_DMG"); }
    public double atkSpeed()       { return get("ATK_SPEED"); }
    public double statProp()       { return get("STAT_PROP_DMG"); }
    public double dmg()            { return get("DMG"); }
    public double dmgAdd()         { return get("DMG_ADD"); }
    public double dmgAmp()         { return get("DMG_AMP"); }
    public double basicAtkDmg()    { return get("BASIC_ATK_DMG"); }
    public double skillDmg()       { return get("SKILL_DMG"); }
    public double defPen()         { return get("DEF_PEN"); }
    public double bossDmg()        { return get("BOSS_DMG"); }
    public double normalDmg()      { return get("NORMAL_DMG"); }
    public double minMult()        { return get("MIN_DMG_MULT"); }
    public double maxMult()        { return get("MAX_DMG_MULT"); }
    public double finalDmg()       { return get("FINAL_DMG"); }
    public int    basicAtkTargets(){ return (int) get("BASIC_ATK_TARGETS"); }
    public int    level()          { return (int) get("LEVEL"); }
    public int    job()            { return (int) get("JOB"); }

    private static final int[] ACQ  = {0, 10, 30, 60, 100};      // 차수별 해금 레벨(1~4)
    private static final int[] NEXT = {0, 30, 60, 100, 999999};  // 다음 차수 해금(4차는 상한 없음)

    /**
     * 스킬 습득 차수별 유효 스킬레벨(성장 입력). ★스킬 습득 차수 계산법:
     * <pre>유효레벨 = 3×(min(캐릭레벨, 다음차수해금) − 이번차수해금) + SKILL_LEVEL_[차수] + SKILL_LEVELS</pre>
     * 각 스킬은 자기 차수 구간에서만 3레벨/캐릭레벨씩 쌓고 다음 차수에서 멈춘다.
     * (인게임 검증: 다크사이트 1차=98, 숙련/아드 3차=193, 쇼다운 4차=192)
     */
    public int effectiveSkillLevel(int tier) {
        int t = Math.max(1, Math.min(4, tier));
        int lv = level();
        int derived = Math.max(0, 3 * (Math.min(lv, NEXT[t]) - ACQ[t]));
        return (int) (derived + get("SKILL_LEVEL_" + t) + get("SKILL_LEVELS"));
    }

    /** 하위호환: 4차 기준 유효레벨. */
    public int effectiveSkillLevel() {
        return effectiveSkillLevel(4);
    }

    /** 기본 행동 슬롯 간격(ms) = 1000 / (1 + 공속%/100). 공속 버프는 스케줄러에서 별도 반영. */
    public double baseSlotMs() {
        return 1000.0 / (1.0 + atkSpeed() / 100.0);
    }
}
