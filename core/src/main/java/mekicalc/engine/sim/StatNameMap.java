package mekicalc.engine.sim;

import java.util.Map;

/**
 * 클라이언트 원본 스탯명(skills_v2) → {@link mekicalc.model.StatBlock} id 매핑.
 *
 * <p>기존 검증 엔진(인게임 실측 완료)의 필드 매핑을 계승한다. 예: clt {@code AttackPower} →
 * 기존 {@code atkPct} → {@code ATK_PCT}. 수치는 1000분율이라 <b>clt값 ÷ 10 = 표시%</b>
 * (예: FinalDamage 120 → 최종 데미지 +12%).
 *
 * <p>딜 무관(Toughness=받피감·생존)·컨텐츠 전용(AttackPowerInCc=콜로세움)은 매핑하지 않는다(null).
 */
public final class StatNameMap {

    private StatNameMap() {}

    /**
     * clt 스탯명 → StatBlock id. 없으면 null(딜 무관·미지원).
     *
     * <p>clt 공식 정의(ENUM.STATTYPE, 로컬라이징 검증 2026-08-06):
     * Attack="공격력", <b>AttackPower="데미지"</b>, <b>BaseAttackPower="기본 공격 데미지"</b>,
     * SkillPower="스킬 데미지", Weakness="받는 피해 증가", AttackPowerToBoss/ExcludeBoss="보스/일반 몬스터 데미지",
     * AttackPower2="데미지 증폭", AttackPowerInCc="상태이상 데미지". 넥슨 공식 곱연산 항과 정확 대응.
     */
    public static final Map<String, String> ID = Map.ofEntries(
            Map.entry("FinalDamage", "FINAL_DMG"),
            Map.entry("Attack", "SKILL_ATK_PCT"),           // "공격력%" 스킬버프 — 공식상 장비공퍼와 별도 곱(스킬%배율 단계). 스탯창 미선반영.
            Map.entry("AttackPower", "DMG"),                // "데미지" (공격력 아님!) — (1+데미지) 항
            Map.entry("AttackPower2", "DMG_AMP"),           // "데미지 증폭"
            Map.entry("BaseAttackPower", "BASIC_ATK_DMG"),  // "기본 공격 데미지"
            Map.entry("SkillPower", "SKILL_DMG"),           // "스킬 데미지"
            Map.entry("AttackSpeed", "ATK_SPEED"),
            Map.entry("CriticalPower", "CRIT_DMG"),
            Map.entry("CriticalChance", "CRIT_RATE"),
            Map.entry("MinDamageRatio", "MIN_DMG_MULT"),
            Map.entry("MaxDamageRatio", "MAX_DMG_MULT"),
            Map.entry("PiercePower", "DEF_PEN"),
            Map.entry("Defence", "DEFENSE"),                // 간접딜: 방어력→주스탯 변환 소스
            Map.entry("Str", "MAIN_STAT_FLAT"),
            Map.entry("Int", "MAIN_STAT_FLAT"),
            Map.entry("Dex", "MAIN_STAT_FLAT"),
            Map.entry("Luk", "MAIN_STAT_FLAT"),
            Map.entry("BuffDurationRatio", "BUFF_DURATION"),
            Map.entry("AttackPowerToBoss", "BOSS_DMG"),     // "보스 몬스터 데미지"
            Map.entry("AttackPowerExcludeBoss", "NORMAL_DMG"), // "일반 몬스터 데미지"
            Map.entry("AttackPowerInCc", "STATUS_DMG"),     // "상태이상 데미지"(빙결/기절/무력화 대상). 신규 id
            Map.entry("Weakness", "RECV_AMP"));             // "받는 피해 증가"(디버프, target=ENEMY). 신규 id

    /** clt 원본 수치 → 표시%(1000분율: 100 = 10%). */
    public static double toPct(double cltVal) {
        return cltVal / 10.0;
    }

    /** clt 스탯명의 StatBlock id(없으면 null = 딜 무관·미지원). */
    public static String id(String cltStat) {
        return ID.get(cltStat);
    }
}
