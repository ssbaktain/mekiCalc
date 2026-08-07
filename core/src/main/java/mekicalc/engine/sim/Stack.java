package mekicalc.engine.sim;

/**
 * 스택형 버프 스펙.
 *
 * @param maxStack 최대 중첩 수.
 * @param rampUp   true면 시간에 따라 점증(램프업), false면 즉시 최대.
 */
public record Stack(int maxStack, boolean rampUp) {}
