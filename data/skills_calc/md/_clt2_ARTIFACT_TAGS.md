# clt2 유물(아티팩트) 계산 IR 태그 레퍼런스

`data/skills_calc/artifact_manual.json`(손작업본)에 쓰는 태그 정의. 자동추출 참고본=`artifact_seed.json`(빌드2694, name·desc·원본 stages). 스킬 태그([[_clt2_IR_TAGS]])·엔진([[_clt2_SIM_MODEL]])·유물 IR([[mekicalc-artifact-clt2-ir]]) 계승.

## 0. 대원칙
- **유물 = 스탯 기여 소스. 직접 타격딜(coeff) 0.** 전 유물이 스탯 버프/디버프/조건부스탯. → 엔진은 유물을 공식 스탯 항으로만 얹는다(딜계수 아님).
- **효과 = [스탯 골격] + [조건 태그 0~N개].** 조건은 **AND 합성**(여러 개 붙으면 전부 충족해야 발동) → "다중조건"은 별도 문법 없이 태그 나열.
- **각성 스케일**: 스킬의 `growthSlot`(스킬레벨 성장) 대신 **`values:[6]`**(0~5각 천분율 배열). 스킬처럼 자라지 않고 각성 슬롯 선택.
- **조건은 전역 계산컨텍스트 입력으로 데이터 구동**(하드코딩 금지): **M(순간 마리수)·K(분당 처치수)·C(현재 컨텐츠)** 3종(§4).
- **값 스케일(필드/스탯명으로 결정, 스킬 규약 그대로)**:
  - **천분율**(÷10=표시%, FinalDamage만 /1000=배율): `values`(스탯이 %/비율일 때)·`chance`·`derived.ratio`·`mpAtLeast`.
  - **절대 ms**: `durationMs`·`periodMs`·`delayMs`·`tickMs`.
  - **정수 개수**: `maxStacks`·`minEnemies`·`perEnemy.max`.
  - ★엔진이 숫자가 아니라 **어느 필드/스탯에 있는지로 스케일 구분**(스킬 `route()` 재사용: FinalDamage÷1000·그외%스탯÷10).

---

## 1. 공통 골격 태그 (모든 effect)

| 태그 | 의미 | 재활용 |
|---|---|---|
| `stat` | 대상 스탯명(ATK_PCT·CriticalChance·CriticalPower·FinalDamage·AttackPowerToBoss·MaxDamageRatio·PiercePower·SkillPower·BaseAttackPower·RECV_DMG(약화)·ATK_SPEED·cooldown 등) | ♻️ 스킬 stat명 |

★**clt stat → 우리 stat 매핑 주의**(StatType enum + 데미지 파이프라인 근거, dump.cs):
- `Attack`(7,공격력)→**ATK_PCT**(공% baseAtk 합-풀).
- **`AttackPower`(9)→`DMG`(데미지증가%)** ★**인게임 실측 확정**(육각 스택이 스탯창 "데미지%"에 반영). "최종 공격력" 라벨은 오해유발 — AttackPower 패밀리(`AttackPowerToBoss`보스뎀·`ExcludeBoss`일반뎀·`BaseAttackPower`평타뎀·`SkillPower`스킬뎀)가 전부 **피해량 증가 계열**. `ModStatR`(비율)=**`dmgMult`**(데미지% ×(1+v×스택), 육각) / `ModDamagePipelineStat`·`ModStat`=DMG 합(머쉬맘 assumeMax). ★공격력(ATK_PCT)·FinalDamage 아님. 옛 검증엔진 DMG dmgMult와 일치.
- ★**스킬 IR와 stat명 통일**(엔진 route()가 stat명으로 라우팅 → 반드시 동일): clt 원본 StatType명 **그대로 유지** — `AttackPowerToBoss`(44)·`AttackPowerExcludeBoss`(45)·`AttackSpeed`(11)·`BaseAttackPower`(46)·`SkillPower`(47)·`CriticalChance`(13)·`CriticalPower`(14)·`MaxDamageRatio`(65)·`Weakness`(24)·`PiercePower`(42)·`FinalDamage`(80). 단 **공격력=`ATK_PCT`·주스탯=`MAIN_STAT_FLAT`·데미지증가=`DMG`**만 preset-key(공식항). 육각/머쉬맘 clt `AttackPower`(9)는 실측상 데미지증가→`DMG`. (`MainDamagePower`68=주데미지증가·`GiveDamagePower1~9`=별도 데미지증가.)
- ★`ModStatR`=곱(unit:pct), `ModStat`=합. FinalDamage는 stat명이 고유곱이라 unit 불요.
| `values` | **[6각] 천분율 배열**(0~5각) | 🆕 유물전용(growthSlot 대체) |
| `unit` | `flat`(합)/`pct`(곱). 생략=스탯명 기본. ★클뜯 op가 `ModStatR`=곱(pct)·`ModStat`=합/스탯명규약 | ♻️ 최근 확정 규약 |
| `target` | `player`(기본)·`companion`·`enemy` | ♻️ |
| `role` | `statUp`(자기)·`debuff`(적, RECV_DMG 등) | ♻️ (deal 없음) |

- 조건 태그가 하나도 없으면 = **상시**(엔진이 전 구간 [0,T] 주입). 클뜯 `OnStart`+조건없음이 여기.

---

## 2. 트리거 (clt TriggerType → 태그, ★빌드2694 실측)

| clt TriggerType | 태그 | 의미 | 엔진 처리 | 유물(수) |
|---|---|---|---|---|
| `OnStart` | (없음=상시 / content / op내 조건) | 전투 내내(조건 충족 가정) | base 주입 or 게이트 | 24 |
| `OnTimer` | `trigger:"period"` +`periodMs`+`durationMs` | periodMs마다 durationMs 발동 | 창 모델(§SIM 2.7) | 죽은자·육각·순환 |
| `OnAttack` | `trigger:"onAttack"` +`chance`+`durationMs`+`refresh` | **플레이어 공격 시** 확률 발동 | **확률 누산기**(§SIM 2.7) | 돼지리본·**은펜던트** |
| `OnWaveStart` | `trigger:"combatStart"` + effect `durationMs`(+`delayMs`) | 전투/웨이브 시작 기준(단계형 가능) | 창 모델(ms) | 무지개달팽이(15000)·양초(최종뎀 dur40000 / 보스뎀 delay20000) |
| `OnKill` | `trigger:"onKill"` +`chance`+`durationMs` | 적 처치 시 확률 발동 | **K 합성이벤트→확률 누산기**(보스=0) | 성배 |
| `OnState` | `require:*`(보류) | 상태변화(디버프 걸림 등) | 피격의존→컨텐츠모드 보류 | 낡은오르골 |
| `OnDamage` | `require:*`(보류) | 피해 입으면 | 피격의존→보류(우리 가정 피격x) | 월로 |
| `OnSpawnSupporterSkill` | `trigger:"companionSummoned"` | 동료 소환 시 | 동료 가동률 종속 | 뿔피리 |

- `chance`(천분율)=클뜯 `TriggerRatio`(돼지리본 200=20%, 은펜던트 150=15%). 확률형은 §SIM 확률 누산기 그대로(계수/값 full, 창 밀도가 확률 표현·이중할인 금지).
- `refresh`=재발동 시 지속 리셋 여부. 개별중첩(은펜던트 individualDur)은 §3.

---

## 3. 조건 태그 (복수 = AND 합성)

| 태그 | 의미 | 구동 입력 | 유물 예 |
|---|---|---|---|
| `content` | 컨텐츠 전용 **리스트**(값=`챕터사냥·챕터보스·레이드-자쿰·레이드-혼테일·월드보스·아레나·월드아레나·길드토벌전·성장던전`). ★C가 **리스트에 속하면** 활성(membership). 단일도 1원소 리스트(`["레이드-자쿰"]`), 복수는 `["월드아레나","아레나"]`(영혼주머니) | **C** | 자쿰돌조각·영혼계약서·영혼주머니… |
| `bossOnly`/`bossZero` | 보스 전용/제외(진짜 보스플래그, op내 `$CreatureIsBoss`) | (보스여부) | 어둠의계약서 |
| `minEnemies:N` | 적 N명 이상일 때 | **M** | 비밀지도(10) |
| `targetMode:"single"\|"multi"` | 적 1명 / 2명이상 분기 | **M** | 세이람 |
| `perEnemy:{max:N}` | 적 1명당 선형, 최대 N | **M** | 화염초(10) |
| `mpAtLeast:N` | MP ≥ N(**천분율**, 500=50%) | (MP 가정) | 얼음영혼석(500=50%, 750=75%) |
| `trigger:"combatStart"`+`durationMs`(+`delayMs`) | 전투시작 기준 구간(ms, 단계형) | — | 양초·무지개달팽이 |
| `maxStacks`+`periodMs`+`durationMs` | 주기 스택 램프 | — | **육각(period20000/dur30000/max3)** |
| `maxStacks`+`durationMs`+`individualDur:true` | 확률발동마다 **독립창 겹쳐쌓기(non-pause)** | — | **은펜던트(chance15%/dur5s/max5)** |
| `require:"buff"\|"debuff"\|"shield"` | 대상 상태 분기 ★**보류(컨텐츠모드 때 구현)** | — | 불타는용암·낡은오르골 |

- **육각 스택**: `periodMs`+`durationMs`+`maxStacks`(20초마다 +1, 30초지속). 일반사냥(무한)=상시 max, 보스전=시간 램프(0→max). §5 `dmgMult`와 결합.
- **★스택 vs 단일**: `maxStacks=1`+`refresh:false`=단일 상태, 활성 중 재발동 무시(**pause**, 베놈류) / `maxStacks>1`+`individualDur:true`=발동마다 독립 지속창 **겹쳐쌓기(non-pause)**, 효과=`값×min(maxStacks, 겹치는창수)`(은펜던트). ★은펜던트엔 refresh:false 넣지 말 것(individualDur가 "기존 리셋 안 함" 내포, pause와 상반).

---

## 4. 전역 계산컨텍스트 입력 (신규 3종)

| 입력 | 의미 | 구동 | 비고 |
|---|---|---|---|
| **M** 순간 마리수 | 동시에 타격되는 적 수 | `minEnemies`·`targetMode`·`perEnemy` + **스킬 targetCount(다중몹 딜)** | 보스전 M=1(single/perEnemy 자동 보스거동). 유물+스킬 마리수 통일 |
| **K** 분당 처치수 | 일반몹 처치율 | `trigger:onKill` | 60/K초마다 합성 처치이벤트→확률 누산기. 보스전 K기여 0 |
| **C** 현재 컨텐츠 | 계산 대상 컨텐츠(탭) | `content` | 현재 {챕터사냥, 챕터보스}. 추후 탭 추가시 content 유물 자동 활성 |

---

## 5. 파생·특수곱

- **스탯 비례형** `derived`(라이브 소스 의존 → 엔진 후처리, 유물 미포함 프리셋이라 소스 전량이 유물 기여):
  - 비율형 `{"from":"AttackSpeed", "ratio":[6각]}` — 라이브 소스 × ratio%(÷1000). 헬레나(공속→최대뎀 25%[5각50%]), 고대의책(크확→크뎀 30%[5각60%]). ★고대의책은 크확 먼저 적용된 **전체 라이브 크확** 기준.
  - 초과분형 `{"from":"AttackSpeed", "threshold":600, "step":30, "perStep":[6각], "maxCount":20}` — (라이브−threshold)를 step마다 나눠 스텝수=`min(maxCount, floor(excess/step))`, 결과=`스텝수×perStep`. 물병(공속 60%초과 3%마다 0.5%[5각1%], 최대20스텝=10%[5각20%]). 초과 없으면 0.
- **특수곱** `dmgMult:true`+`maxStacks`: 데미지=DMG×(1+값×스택). 일반 가산과 달라 플래그(육각).
- **적용 대상** `target:"self"|"companion"|"enemy"` — 효과가 붙는 주체(self=본인·companion=동료·enemy=적/Weakness류). **없으면 self**. ★자신+동료 양쪽 부여는 **effect를 둘로 분리**(clt op이 Myself/Parent로 나뉘므로 그대로). 뿔피리=`target:self`+`target:companion` 2개. **companion=이미 소환된 동료에게 상시 주입**(현재 뿔피리만, 동료엔진 조립 시 각 동료 스탯풀에).
- **컨텐츠 조건부 배수** `contentMult:[{"content":[...],"ratio":N}]` — **그 effect 값**이 C∈content일 때 ×ratio(매칭 없으면 ×1). 붙은 effect에만 작용(자기효과 한정은 그 effect에만 붙여서 달성). 리스트=컨텐츠별 다른 비율 확장 가능.
  - 뿔피리 self effect=`[{content:["챕터보스"],ratio:2}]`→챕터보스 자신20%/동료10%(동료 effect엔 없음=배수 X), 타컨텐츠 둘다10%.
  - 비밀지도 최종뎀 effect=`[{content:["성장던전"],ratio:3}]`→base 5%, 성장던전 15%.
  - ★배수는 clt op에 없음(op엔 base·×N 값이 별도로 있거나 base만)—desc 유래 컨텐츠 로직. "boss"=챕터보스탭이지 전 보스 아님. (구 `bossSelfMult`·`appliesTo`·`selfMult` 폐지→`target`분리+`contentMult`로 통일)
- **`assumeMax:true`** — 조건부 스탯 최대치 가정(머쉬맘 명중초과딜).
- **시간 단계** `delayMs`(발동 후 지연 시작)+`durationMs`(그 후 지속) — 양초 보스뎀(delay20000+dur20000=20~40초). `startDelayMs`(첫 발동 지연) — 순환(5000, 게임시작 5초후).
- **재적용(비누적)** `refresh:true`(유물레벨) — 매 발동 시 **자기 이전효과 제거 후 재적용**(누적 안 됨). clt의 ClearState op 대변. 순환(clt op1~3=ClearState Artifact_5_23_1/2/3 → op4~6 재부여 DurationMs 99999999). ★없으면 무한지속으로 매 주기 누적폭주. 제거가 재측정보다 먼저라 조건 스탯(크확)의 자기버프 자동 제외.
- **라이브 크확 분기**(플랫 태그, `minEnemies`·`targetMode`와 동일 양식) `critLt:N`/`critGte:N`(천분율, 1000=100%) + `critExcess:{step,maxCount}` — 순환(크확<100%→크확 / ≥100%→스킬뎀+평타뎀 critExcess). ★배타 regime(크확 100% 파티션, 하나만 참→pre-split), 라이브 크확(고리 자기버프 제외) 5초마다 재측정. (구 중첩 `condition:{...}` 폐지→플랫)
- **effect레벨 `content`** — 특정 effect만 컨텐츠 전용(비밀지도 op3=성장던전 3배). 유물레벨 content와 별개.
- **강제소멸 `fixedDuration:true`**(유물레벨) — 절대시점에 **전 효과가 강제 소멸**(양초 "40초 후 모든 효과 사라집니다"). 벞지(BUFF_DURATION) 미적용(늘려도 강제로 사라짐). ↓벞지 규칙.

### 벞지(버프지속%, BUFF_DURATION) 적용 규칙 — 태그로 일괄 판단(2026-08-24)
`windows()`의 시한부 자기버프 `durationMs`에 **총벞지(유저 프리셋 + 유물 BuffDurationRatio[무녀구슬] 사전합산)**를 곱한다. **제외는 태그로 일괄**(유물마다 개별대응 금지):
- **디버프**: `target:"enemy"`/`stat:"Weakness"`(대상=적). 은펜던트·천도명약은 onAttack uptime 경로라 별도.
- **상시**: `durationMs` 없음 → 늘릴 것 없음.
- **순환형** `isCycle`(순환의고리): 5초마다 강제 재부여라 고정. windows()·사전합산 모두 스킵.
- **강제소멸형** `fixedDuration:true`(양초): 절대소멸이라 벞지 무관.
- **적용(정상 시한부 버프)**: 달팽이(15s)·죽은자(5s주기)·오르골(25s, 계산되면 자동). 스킬 쪽은 `summon`(설치기) 제외 [[mekicalc-damage-formula-rules]].

### ★조건 표현 양식(결정) — ★기준=조건이 파티션이냐 부분집합이냐
- **배타 선택**(조건들이 **공간을 완전 분할=파티션**, 항상 하나만 참) = **조건별 pre-split**(effect 여러 개, 각 조건 태그). 레인디어(방관: 마리수×보스로 5/10/15 분할), 세이람(적1↔적2+로 보스뎀/일반뎀 분할). 겹치는 구간 없어 가산해도 하나만 켜짐.
- **조건부 배수**(base가 그 컨텐츠에서도 켜지는 **부분집합**, base×N) = **base 단일 effect + `contentMult`**. 뿔피리(챕터보스 ×2), 비밀지도(성장던전 ×3). ★pre-split 하면 base+×N값이 **중복 가산 버그**(비밀지도 5%+15%=20%≠15%)라 반드시 배수식.
- ★판별: 조건이 **파티션**(레인디어·세이람)→pre-split OK / 조건이 base의 **부분집합**(뿔피리·비밀지도)→contentMult. clt에 op이 여러 개여도(비밀지도 50+150) 의미가 base×N이면 배수로.

### ★다효과 결합(★전역 규칙 금지 — 유물별로 다름)
한 유물의 여러 effect가 어떻게 합쳐지는지는 **유물마다 다르다.** 막 전역 규칙 잡으면 다른 유물 조건/효과가 깨진다:
- **다른 스탯**(자쿰 최종뎀+물약쿨, 무지개 크확+크뎀, 고대의책 크확+크뎀) → **가산**(각자 독립 적용). = default.
- **배타 조건**(동시 참 불가: `targetMode`single/multi, `condition`critLt/critGte) → 조건이 상호배타라 **자연히 하나만** 참. 특별 태그 불필요.
- **겹치는 조건 + 같은 스탯**(불타는용암: 대상이 버프·디버프·보호막 **동시 보유 가능**) → default 가산이면 틀림(중복). **`priorityGroup:"id"`+`priority:N`(높을수록 우선)** 명시 → 엔진은 같은 그룹 내 condition 충족 중 **최고 priority 하나만** 적용. 불타는용암=보호막(3)>디버프(2)>버프(1).
- ★즉 **default=가산, 겹침-같은스탯만 priorityGroup으로 배타 명시**. 이러면 가산형·조건배타형·우선순위배타형이 서로 안 섞여 다 정확.

---

## 6. 엔진 처리 매핑 (§SIM 9)

- **상시**(조건X) → base 스탯에 [0,T] 주입. 공속/쿨감은 **스케줄 전** 주입(발동빈도 영향).
- **주기/확률/구간**(period·onAttack·onKill·combatStart) → **BuffSource 윈도우**(확률=누산기, 보스=K 0).
- **적 대상**(RECV_DMG) → Weakness 윈도우.
- **content/M/K** → §4 전역입력으로 게이트(불일치=0).
- **기여(딜구성 탭)** = leave-one-out marginal(유물 빼고 재계산). 타임라인 불변 스탯=딜이벤트 캐시 후 재계산, 쿨감·공속=재시뮬.

---

## 7. 보류 (컨텐츠모드 때)
- `require:buff/debuff/shield`(불타는용암) · `OnState`(낡은오르골) · `OnDamage`(월로) = **피격/대상상태 의존**. 우리 기본 가정(피격x, 챕터사냥/보스)에선 0 또는 미구현. 컨텐츠모드 확장 시 대상 상태·피격빈도 모델과 함께 구현.
- survival(비딜: 돼지리본 회복·월로 면역) = 항상 0(슬롯만).
