package mekicalc.engine.sim2;

/**
 * sim2 계약 모음의 안내 문서(코드 없음).
 *
 * <h3>재설계 원칙 (SCHEMA.md의 엔진 확장)</h3>
 * <ol>
 *   <li><b>단일 경로</b>: 모든 발동(액티브·평타·타이머·proc·State·카운터)은 타임라인 이벤트로만
 *       처리한다. 정적 가동률 근사(applyProcGrants)는 폐기 — 같은 물리량을 두 경로로 계산하는
 *       하이브리드가 착오의 온상이었다.</li>
 *   <li><b>결정론</b>: 확률 발동은 누적 게이트({@link ProcGate})로, 동시각 이벤트는 seq로
 *       전순서화한다. 같은 입력 → 항상 같은 출력.</li>
 *   <li><b>조용한 기본값 금지</b>: 해석 불가(함수형 값·미지원 조건·cd=0 액티브·배타 그룹 미표기)를
 *       만나면 0/상시로 조용히 강등하지 않고 {@link Warn}으로 결과에 기록한다.</li>
 *   <li><b>발동 조건은 데이터</b>: "N회마다"(counterEvery)·State 게이팅(requireState)·
 *       상호배타(exclusiveGroup)·스택(stackState/stackMax)을 스키마 필드로 보존한다.
 *       엔진이 추측하지 않는다.</li>
 *   <li><b>관측 가능성</b>: 모든 버프의 실측 가동률/평균 스택을 {@link Sim2Result}로 보고한다.
 *       가동률이 눈에 보이면 착시가 숨을 곳이 없다.</li>
 * </ol>
 */
final class Contracts {
    private Contracts() {}
}
