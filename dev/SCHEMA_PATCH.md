# SCHEMA_PATCH — skills_v2 확장 필드 (sim2 엔진용)

기존 `SCHEMA.md`(6축)를 대체하지 않고 **추가**한다. 목적: 이번 재설계에서 확인된
"발동 조건 소실"을 파이프라인(`_clt_build_skilldata.py`) 레벨에서 막는다.
엔진(sim2)은 이 필드들을 이미 읽는다 — 필드가 없으면 Warn으로 표면화된다(조용한 강등 없음).

## 1. trigger 확장 (②축 발동 조건)

| 필드 | 타입 | 의미 | 클뜯 근원 | 사례 |
|---|---|---|---|---|
| `counterEvery` | int | N회마다 발동(확률과 별개, 카운터 먼저) | TriggerCondition의 카운트 조건 | 디바인 저지먼트 24070 "기본 공격 효과 스킬을 **10회 사용할 때마다**" → `10` |
| `counterOn` | str | 카운터 소스: `basicAttack` \| `activeSkill` | 조건의 대상 이벤트 | 디바인 저지먼트 → `basicAttack`(기본 공격 효과 근사) |
| `requireState` | str | `"Name"`(스택≥1) 또는 `"Name:N"`(스택≥N)일 때만 발동 | `$CreatureStateCount(...;State) >= N` | 서펜트 스톤 113030 "[서펜트 스톤 5개 필요]" → `"SerpentStone:5"` / 서펜트 어썰트 인레이지 114090 "어썰트 모드에서" → `"AssaultMode"` / 어드밴스드 홀리 차지 24090 OnState → `"HolyCharge"` |
| `consumeRequire` | bool | 발동 시 requireState 스택 전부 소모 | "모든 ~을 소모하여" | 서펜트 스톤 → `true` |

★ 지금까지 이 조건들이 소실되어 `OnActiveSkill 100%` 등으로 강등된 것이
팔라딘 과대(디바인 저지먼트 상시화)의 직접 원인이었다.

## 2. buff/debuff 확장

| 필드 | 타입 | 의미 | 사례 |
|---|---|---|---|
| `exclusiveGroup` | str | 같은 그룹 중 1개만 발동(엔진: 결정론 라운드로빈 = 균등 기댓값) | 럭키 다이스 113060 6면 → 전부 `"LuckyDice"` |
| (기존) `state`+`stateMax` | | 스택형: 수치는 **스택 1개당**, `stateMax`=상한 | 씨 서펜트 버스트 SerpentStone 5 |

- 럭키 다이스가 그룹 없이 6개 전부 합산되던 것(과대 ×6)이 수정된다.
- **중복 엔트리 주의**: 씨 서펜트 버스트에 동일 (stat·val·state·dur) buff가 2개 존재.
  구간 강화판 중복으로 추정 — 엔진은 1개만 반영하고 `DUPLICATE_BUFF_ENTRY` 경고.
  파이프라인에서 근원 확인 후 규칙화 필요.

## 3. damage 다중 엔트리 선택 규칙 (미해결 — SCHEMA 확장 필요)

sim2가 `MULTI_DAMAGE_ENTRIES` 경고로 표면화한 데이터 갭. 현재 첫 엔트리만 사용(기존 엔진과 동일):

- 씨 서펜트 버스트: `[130×2, 430×2, 430×2, 480×3]` — 스킬레벨 **구간 강화판**으로 추정.
  «» 표기 현재값과 대조해 "현재 레벨에 맞는 엔트리 선택" 규칙 필요.
- 피스트 인레이지: `[900×3, 900×2]` — 두 번째는 "대상이 **보스라면** 동일 피해 2회 더".
  구간판이 아니라 **보스 조건부 추가타** → `bossExtra` 같은 별도 필드로 분리해야 함.

두 유형(구간판 vs 조건부 추가타)의 구분 규칙을 클뜯 op 레벨에서 규명해야 한다.

## 4. 선반영 규약 (사용자 확정 — 재확인 2026-08-08)

**상시 패시브는 사용자 스탯창에 기본 포함(선반영)**. 단 **공격력%(clt `Attack` → SKILL_ATK_PCT)와
주스탯%(MAIN_STAT_PCT)는 스탯창에 미포함** → 엔진이 나중에 계산(공퍼는 장비 공퍼와 **별도 곱** 단계).

sim2의 판정 기준(기존 대비 수정):

| 버프 유형 | 기존 엔진 | sim2 |
|---|---|---|
| OnStart + 무한 + 무스택 (무조건 상시) | 제거(선반영) | 제거(선반영) — 단 SKILL_ATK_PCT·MAIN_STAT_PCT 유지 |
| 무한이지만 **스택 조건부**(씨 서펜트 버스트) | **오삭제** ← 버그 | **유지**(스탯창에 못 들어감) |
| 시간제·proc·타이머 | 유지 | 유지(타임라인 실발동) |

## 5. 엔진 계약 변경 요약 (sim → sim2)

- `applyProcGrants`(정적 uptime=min(1,freq×dur)) **폐기** → proc/카운터/State 전부 타임라인 실발동.
- 확률: 시드 고정 RNG(SplitMix64) — 같은 입력이면 항상 같은 출력이면서 발동 간격 분산이
  실제 베르누이와 동일(누적 pity 방식은 uptime 과대: 홀리 차지 실측 0.913 vs pity 0.99).
- cd=0 액티브: 재발동 불능(유효 0%) 대신 `SimConfig.gcdSec` 간격 재사용 + `CD0_ACTIVE_GCD` 경고.
- ON_ATTACK proc은 **평타 태그 타격**에만 후킹(홀리 차지 원문 "기본 공격 시").
  proc으로 발생한 타격의 proc 재귀는 기본 차단(`SimConfig.procHitsTriggerProcs`).
- OnState = "State 지속 동안": State 부여·갱신마다 연동 버프를 함께 갱신(구간 일치).
- 동시각 이벤트 seq 전순서화(PriorityQueue 동점 비결정성 제거).
- 모든 가정·데이터 갭은 `Sim2Result.warnings`로, 모든 버프의 실측 가동률/평균 스택은
  `Sim2Result.buffReport`로 구조적 노출.

## 6. 타임라인 스케줄러 훅 (사용자 빌드 재현)

`SimConfig.schedule: Map<스킬id, double[] 시전시각>` — 지정된 스킬은 쿨 자동 사용 대신
지정 시각에만 시전. 비어 있으면 전 스킬 "쿨 오는 대로" 자동. UI의 타임라인 배치 기능이
이 훅에 그대로 연결된다.
