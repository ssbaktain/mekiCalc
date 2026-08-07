package mekicalc.engine.sim;

/**
 * 타임라인 이벤트 종류(이벤트 기반 시뮬 = "알람 시각표").
 * <ul>
 *   <li>{@code CAST}     : 스킬 시전 — HIT 스케줄 + 버프 부여 + 다음 쿨 후 CAST 재삽입.</li>
 *   <li>{@code HIT}      : 실제 타격 — 활성 버프 스냅샷으로 {@link HitContext} 구성 → 데미지엔진.</li>
 *   <li>{@code BUFF_START}/{@code BUFF_END} : 버프 구간 시작·종료(타임라인 갱신).</li>
 *   <li>{@code SUMMON}/{@code DESUMMON} : 동료 소환(본체버프 스냅샷×계승)·해제.</li>
 *   <li>{@code RESPAWN}  : 챕터 사냥 대상 리젠(지금은 훅만, 미사용).</li>
 * </ul>
 */
public enum EventType { CAST, HIT, BUFF_START, BUFF_END, SUMMON, DESUMMON, RESPAWN }
