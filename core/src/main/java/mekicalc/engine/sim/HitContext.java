package mekicalc.engine.sim;

import mekicalc.model.StatBlock;

/**
 * 스케줄러 → 데미지 엔진 순수 입력. 데미지 엔진은 이 값만 받아 넥슨 공식으로 1타 데미지를
 * 반환한다(상태 없음·부수효과 없음 → 단위 테스트·골든마스터 용이).
 *
 * @param stats   시각 t의 활성 버프 스냅샷이 반영된 유효 스탯(플레이어 또는 동료).
 * @param cast    타격 스펙(계수·타수·대상수·태그·보너스).
 * @param content 컨텐츠 프로파일(대상 방어/HP/보정식).
 * @param boss    보스 대상 여부(보스/일반몹 데미지 항 선택).
 */
public record HitContext(StatBlock stats, Cast cast, ContentProfile content, boolean boss) {}
