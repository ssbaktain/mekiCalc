# 유물 엔진 데이터 계약 (clt2, 확정 2026-08-20)

`artifact_manual.json`(35종)을 타임라인 엔진(`mekicalc.timeline.*`)이 소비하는 규격. 스킬 IR와 같은 엔진·같은 stat명·같은 route()를 공유한다.

## 1. 필드 규약
- **`_`-접두 필드**(`_op`·`_cltStat`·`_survival`·`_raw`·`_child`) = 사람 참고용. **엔진은 전부 무시.**
- **`_defer`**(유물레벨/effect레벨) = 의미 있는 지시자. **엔진은 해당 유물/effect를 기본 계산에서 0으로 건너뛴다**(컨텐츠모드 전까지). 힌트 아님.
- **stat명** = 스킬 IR와 동일(clt 원본 + 공격력`ATK_PCT`·주스탯`MAIN_STAT_FLAT`·데미지증가`DMG`만 preset-key). route()가 stat명→`Stat` enum.
- **값 스케일**: %스탯=천분율(÷10 표시%), `FinalDamage`=÷1000 배율, 시간=ms, 카운트=정수. (스킬 IR와 동일)

## 2. 트리거 모델
- **유물레벨 `trigger` = 기본**(모든 effect 공유). **effect레벨 `trigger` = 오버라이드**(그 effect만). 엔진: effect.trigger 있으면 그것, 없으면 유물.trigger.
- 트리거 종류: `period`(+`periodMs`)·`onAttack`·`onKill`·`onDamage`·`onState`·`combatStart`·`companionSummoned`. + `startDelayMs`(첫 발동 지연)·`cooldownMs`·`chance`(천분율)·`refresh`.
- **`refresh:true`**(유물레벨) = 매 발동 시 자기 이전효과 제거 후 재적용(비누적). clt ClearState 대변. 순환(무한지속 폭주 방지 + 조건 스탯 자기제외).

## 3. effect 3분류 (엔진 처리 골격)
| 분류 | 판별(조건/트리거 태그) | 처리 |
|---|---|---|
| **상시 base** | 조건·트리거 없음 | 전 구간 [0,T) BuffSource(또는 Preset 주입) |
| **조건 게이팅**(시간트리거 X) | `targetMode`·`minEnemies`·`perEnemy`·`critLt`/`critGte`·`contentMult`·`require`·`mpAtLeast`·`assumeMax` | 전역입력(M/K/C)·라이브값으로 매 계산 평가, 참이면 적용 |
| **시간 트리거** | `trigger`+`chance`/`periodMs`+`durationMs`(+`delayMs`/`maxStacks`/`individualDur`) | 타임라인 창(§SIM 2.7) |

## 4. 결합 규약 (전역 규칙 금지, 유물별)
- **다른 스탯** → 가산(독립). 자쿰·무지개·고대의책.
- **배타 조건**(파티션, 항상 하나만 참) → pre-split, 태그 불요. 레인디어(마리수×보스)·세이람·순환(크확 100%).
- **겹침+같은 스탯**(부분집합 가능) → `priorityGroup`+`priority`(최고 하나) 또는 `contentMult`(base×배수). 불타는용암(priorityGroup)·뿔피리·비밀지도(contentMult).

## 5. 특수 처리 (단계별)
- **derived**(`derived:{from,ratio}` 비율형 / `{from,threshold,step,perStep,maxCount}` 초과분형) = 라이브 스탯 후처리. 헬레나(공속→최대뎀)·물병(공속초과)·고대의책(크확→크뎀)·순환(critExcess). → **v2**.
- **`target:"companion"`** = 이미 소환된 동료 각 개체 스탯풀 주입. 뿔피리 유일. → **v3(동료엔진)**.
- **`_defer`·`priorityGroup`·`require`** = 대상상태 의존(컨텐츠모드). → **v4**.
- **전역입력**: `M`(순간 마리수)·`K`(분당 처치수)·`C`(현재 컨텐츠).

## 6. 엔진 확장 필요분 (ModState/route)
- **route() 추가**: SkillPower→SKILL_DMG·BaseAttackPower→BASIC_ATK_DMG·AttackPowerToBoss→BOSS_DMG·AttackPowerExcludeBoss→NORMAL_DMG·DMG→DMG. (v1)
- **새 풀 필요**(현재 ModState 없음): 방관(DEF_PEN)·최대뎀배율(MaxDamageRatio)·공속(스케줄러)·쿨감/동료지속/버프지속. → 해당 유물은 후속 sub-step.

## 7. 단계 계획
- **v1**: IR 로더 + route 확장 + 상시/조건게이팅/시간트리거(데미지% 계열, 비-defer·비-동료·비-derived) 조립·검증.
- **v2**: derived(라이브스탯 후처리) + 방관/최대뎀 풀.
- **v3**: 동료 target(동료엔진).
- **v4**: 컨텐츠모드(_defer·priorityGroup·require·M/K/C 입력).
