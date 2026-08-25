package mekicalc.timeline;

/**
 * ModState 합연산 풀의 키. 버프/디버프가 프리셋 스탯 위에 더하는 항들만 모았다.
 *
 * <p>여기 없는 항은 결합 규칙이 달라 별도로 처리한다:
 * <ul>
 *   <li>최종뎀 = 소스별 곱연산 → {@link ModState#finalDmgSources}
 *   <li>공속 = 데미지가 아니라 스케줄링(슬롯 간격) 변수 → 스케줄러에서 처리
 *   <li>피해마스터리 = 스킬 고유값(시간 불변) → {@link DealEvent#skillMastery}
 * </ul>
 *
 * <p>단위는 전부 "퍼센트 포인트"(예: 데미지 +12.3% → 12.3). 약화(WEAKNESS)도 동일하게 % 포인트.
 */
public enum Stat {
    ATK_PCT,        // 공격력%(곱 pool) — 스킬/유물 공%. 프리셋 공격력 위에 별도 ×(1+합/100). 기본
    ATK_PCT_ADD,    // 공격력%(합 pool) — unit:flat 예외만. 프리셋 공%에 합산(현재 미사용)
    DMG,            // 데미지%
    DMG_AMP,        // 데미지 증폭%
    STAT_PROP,      // 스탯 비례 데미지%
    CRIT_RATE,      // 크리티컬 확률%
    CRIT_DMG,       // 크리티컬 데미지%  (퍼지 '적 받는 크뎀 +30'도 여기로 합산)
    BASIC_ATK_DMG,  // 평타 데미지%
    SKILL_DMG,      // 스킬 데미지%
    NORMAL_DMG,     // 일반몹 데미지%
    BOSS_DMG,       // 보스몹 데미지%
    WEAKNESS,       // 약화%(적이 받는 피해 증가) — 소스끼리 합산 후 (1+합/100) 곱
    // ── 크리티컬 디버프(분류=디버프, 크확/크뎀 아님). 최종 fCrit 계산 때만 크확/크뎀에 가산 — 파생·증폭(육각목류)에서는 제외 ──
    CRIT_RATE_ENEMY, // 적 크리티컬 저항 감소% → 최종 크확에 합류(크확 파생·증폭 입력엔 미포함)
    CRIT_DMG_ENEMY,  // 적 받는 크리티컬 데미지 증가% → 최종 크뎀에 합류(크뎀 파생·증폭 입력엔 미포함)
    DEF_PEN,        // 방어 관통%(합 pool) — 프리셋 방관에 합산. defF의 몹방어 감쇠에 반영
    MAX_DMG_MULT,           // 최대 데미지 배율%(합 pool) — 프리셋 최대뎀에 합산. fRange(최소/최대 평균)에 반영
    // ── 아래는 ★표시 전용(딜 공식 미사용). 실제 계산은 스케줄/지속 경로(artSpeed·artCdr·artBuffDur·artSummonDur)에서 별도 처리 ──
    ATK_SPEED,              // 공격 속도% — 실제 공속은 스케줄(공격 빈도)
    COOLDOWN_REDUCTION_PCT, // 쿨타임 감소% — 실제는 스케줄(발동 빈도)
    BUFF_DURATION,          // 버프 지속시간% — 실제는 버프 창 길이
    SUMMON_DURATION         // 동료 소환 지속% — 실제는 동료 엔진(미조립)
}
