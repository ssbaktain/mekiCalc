package mekicalc.engine.sim2;

/**
 * 발동 스펙 — 발동 "조건"을 데이터로 보존한다(SCHEMA ②축 + 조건 확장).
 *
 * @param kind          발동 방식.
 * @param procPct       발동 확률%(proc류). 아니면 100.
 * @param periodSec     주기(초, ON_TIMER). 아니면 0.
 * @param fastByAtkSpeed ON_TIMER 주기가 공속 비례 단축인지.
 * @param counterEvery  N회마다 발동(디바인 저지먼트 "기본 공격 효과 스킬 10회마다"). 0이면 미사용.
 *                      counterOn 이벤트가 N회 누적될 때 1회 발동한다(확률과 별개, 둘 다 있으면 카운터 후 확률).
 * @param counterOn     카운터 소스 이벤트: "basicAttack" | "activeSkill". counterEvery>0일 때만 의미.
 * @param requireState  이 State가 조건을 만족할 때만 발동. 표기: "Name"(스택≥1) 또는 "Name:N"(스택≥N).
 *                      null이면 무조건. 서펜트 스톤 "5개 필요" = "SerpentStone:5".
 * @param consumeRequire 발동 시 requireState 스택을 전부 소모하는지(서펜트 스톤 "모든 스톤 소모").
 */
public record Trigger2(TriggerKind2 kind, double procPct, double periodSec, boolean fastByAtkSpeed,
                       int counterEvery, String counterOn, String requireState, boolean consumeRequire) {

    public static final Trigger2 ACTIVE =
            new Trigger2(TriggerKind2.ACTIVE, 100, 0, false, 0, null, null, false);

    /** requireState의 State 이름 부분. */
    public String requireStateName() {
        if (requireState == null) return null;
        int i = requireState.indexOf(':');
        return i < 0 ? requireState : requireState.substring(0, i);
    }

    /** requireState의 최소 스택 수(미표기 시 1). */
    public int requireStacks() {
        if (requireState == null) return 0;
        int i = requireState.indexOf(':');
        return i < 0 ? 1 : Integer.parseInt(requireState.substring(i + 1));
    }
}
