package mekicalc.engine.sim;

/**
 * 도트(지속 피해) 스펙 — {@code tickSec}마다 1틱, {@code durationSec} 동안 유지.
 * 즉발 타격이면 {@link Cast#dot()}가 null이다.
 */
public record Dot(double tickSec, double durationSec) {}
