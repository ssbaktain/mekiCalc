package mekicalc.timeline;

/**
 * 한 시각에 발생하는 1개의 딜 이벤트. 발동에 고유하고 시간에 불변이라 스케줄링 때 미리 계산해 둔다.
 *
 * <p>한 슬롯(시각)에 여러 DealEvent가 겹칠 수 있다(스킬 시전 + 도트 틱 + onAttack 프록).
 * 각 이벤트는 {@link #skillIdx}를 태그로 들고 있어 스킬별 분석 때 골라낼 수 있다.
 *
 * @param skillIdx     소속 스킬 idx(역인덱스·귀속용)
 * @param dmgType      평타/스킬 타입뎀 구분
 * @param coeffFrac    계수 × 성장 반영 최종 분율(2.90 × growth = 예: 5.127)
 * @param hits         타수
 * @param skillMastery 이 스킬 고유 Σ피해마스터리 분율(0.xx). tf = (1+타입뎀)×(1+skillMastery)
 * @param dotTick      도트/주기 틱이면 true
 */
public record DealEvent(
        int skillIdx,
        DmgType dmgType,
        double coeffFrac,
        int hits,
        double skillMastery,
        boolean dotTick
) {}
