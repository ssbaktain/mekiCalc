package mekicalc.engine.sim2;

/**
 * "조용한 기본값 금지" 원칙의 구현 — 엔진이 가정/생략한 모든 지점의 구조적 기록.
 * UI는 이를 "이 계산에 포함된 가정" 목록으로 노출할 수 있다.
 *
 * @param code   분류 코드(FUNC_VALUE_SKIPPED, CD0_ACTIVE_GCD, EXCLUSIVE_UNMARKED, ...).
 * @param skill  관련 스킬명.
 * @param detail 상세 설명(무엇을 어떤 가정으로 처리했는지).
 */
public record Warn(String code, String skill, String detail) {}
