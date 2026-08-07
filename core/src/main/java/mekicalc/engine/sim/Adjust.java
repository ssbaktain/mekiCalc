package mekicalc.engine.sim;

/**
 * 컨텐츠별 "스킬 계수 직전" 피해 조정(넥슨 공식 2.1).
 * <ul>
 *   <li>{@code NONE} : 조정 없음(챕터 사냥·보스).</li>
 *   <li>{@code SQRT} : 제곱근 조정(아레나·월드아레나·콜로세움·파티퀘·보스/길드레이드).</li>
 *   <li>{@code CBRT} : 세제곱근 조정(길드 수련장).</li>
 * </ul>
 */
public enum Adjust { NONE, SQRT, CBRT }
