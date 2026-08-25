# clt2 IR 태그 완전 레퍼런스 (착시 방지용 사전)

`data/skills_calc/<job>.json`(전 직업 공용)에 쓰이는 **모든 태그의 의미·값·엔진 작용**. 라벨 사전([[mekicalc-skills-calc-ir]])의 정본 표. 런타임 동작은 `_clt2_SIM_MODEL.md`.
- 태그·값·enum은 nightlord manual 전수 추출로 확정(2026-08-14). **직업 무관 공용** — 특정 직업 예시는 넣지 않는다.
- **canonical**: 현재 나온 직업엔 없지만 다른 직업이 골라 쓸 수 있는 값(새 메커니즘만 신규 추가).

## 0. 대원칙
- **값·필드·의미는 클뜯 원본 결정론.** 내가 붙인 건 라벨(분류명)+근사방식(gatedBy/refresh/chance/count)뿐.
- **스케일(천분율/절대)은 stat 이름·필드로 결정** — `unit`은 스케일 판별에 쓰지 않는다.
  - **천분율(÷10=표시%, /1000=배율)** ○: `coeff`·%/비율 스탯 `value`(공격력%·크확·크뎀·보스뎀·최종뎀·약화 등)·`chance`.
  - **천분율 아님(값 그대로)** ✕: `hits`·`targetCount`·`count`·`BaseAttackMaxHitCount`(개수) · `periodMs`·`durationMs`·`tickMs`·`cooldown`·`mp`(ms·절대) · `requireLevel`·`lv`·`growthSlot`·`idx` · 깡스탯 `ATK_FLAT`·`MAIN_STAT_FLAT`(절대).
- **`unit`은 flat/pct 판별자** — 값만 봐선 못 정하는 자리에만 붙인다:
  - **시간계열 강화**(`duration`/`period`/`tick`/`cooldown`): `flat`=정수 그대로 더함(+N ms), `pct`=천분율 비율(×(1+N%)). ★여기가 unit의 주 용도(같은 `duration`이라도 flat이면 +ms·pct면 +%).
  - **합/곱 override**: damage %스탯은 스탯명이 합/곱을 이미 정하므로 unit 생략(=스탯명 기본). 스탯명 기본과 달라야 하는 예외에만 `flat`(합)/`pct`(곱). ★현재 나이트로드엔 없음. skillDamage·보스뎀·크리 등은 unit 없이 스탯명으로.
- **`role`은 화이트리스트**(블랙리스트 아님) — "이런 성격도 저런 성격도 있다"의 나열. utility만 있으면 로테이션 제외, 딜 관련 효과가 하나라도 있으면 반영.
- 구조: `skills[]`(스킬 본체) + `masteries[]`(캐릭레벨 해금 강화, 별도 섹션).

---

## 1. SKILL 필드 (top-level)

| 필드 | 의미 | 값 | 엔진 작용 |
|---|---|---|---|
| `idx` | 스킬 인덱스(클뜯 SkillIndex) | 문자열 | 소스 식별·발동 우선순위(오름차순)·skillBoost/gatedBy 참조 대상 |
| `name` | 스킬명(한글) | 문자열 | 표시용 |
| `desc` | 게임 설명(마스터리 포함 최종설명) | 문자열 | 표시·검증 참고(desc≠기본스킬 주의) |
| `role[]` | 스킬 성격(복수, 화이트리스트) | `deal`·`buff`·`debuff`·`utility`·`skillBoost`·(`resource` canonical) | utility만이면 로테이션 제외. 딜관련 성격 있으면 반영 |
| `type` | 대분류 | `active`·`passive` | active+평타=행동슬롯(모션) 점유 / passive=배경 트리거(점유X) |
| `trigger` | 발동 계기 | `basicAttack`·`active`·`onStart`·`onActiveSkill`·`onAttack`·`periodic`·(`onHit` canonical) | ↓ trigger 표 |
| `triggerBy` | onAttack 유발 소스 | `anyHit`·`basicAttack`·`[idx]` | onAttack 빈도 산정 소스 필터 |
| `chance` | 확률 발동(천분율) | 정수(250=25%) | 트리거마다 확률 판정(시드 베르누이/기댓값). 없으면 100% |
| `count` | N회 발동마다 1회 | 정수(개수) | 트리거 count회 누적→발동+리셋(count 버프) |
| `refresh` | 지속 중 재발동 처리 | `true`(갱신)·`false`(정지) | false=지속중 카운트/재발동 정지, true=재발동해 지속 리셋 |
| `gatedBy` | 다른 스킬 가동 종속 | 스킬 idx | 그 스킬 가동률을 이 효과에 곱(경량화). ★참조 스킬 skills[]에 실재해야(dangling 금지) |
| `periodMs` | periodic 발동 주기 | ms | periodMs마다 배경 발동 |
| `requireLevel` | 습득 캐릭레벨 | 정수(1=기본습득) | 로테이션/평타 선택(최고레벨 basicAttack=실제 평타) |
| `cooldown` | 쿨타임 | ms | 재발동 대기(쿨감 공식 §SIM_MODEL 3.1). active만 보유 |
| `mp` | 마나 소모 | 정수 | 현재 딜 미반영(정보 저장) |
| `summon` | 설치기 여부 | `true`/생략 | 설치기(다크플레어·퍼지 등)는 **버프지속%(벞지, `BUFF_DURATION`)를 안 받는다** → 이 스킬의 버프 윈도우에 벞지 미적용. 디버프도 원래 벞지 안 받음(대상=적). ★현존 '비-설치기 & 버프+디버프 혼재' 스킬 없음 → 스킬 단위 처리(설치기 아님 && 자기버프 있음일 때만 벞지). 그런 혼재 스킬 생기면 버프/디버프 윈도우 분리 필요 |
| `effects[]` | 효과 배열 | 객체[] | §2 |
| `note` | 비고 | 문자열 | 보류사항·원본 특이 기록 |

### trigger 값별 작용
| trigger | 의미 | 모션 | 발동 |
|---|---|---|---|
| `basicAttack` | 평타(=[기본 공격 효과]) | 점유 | 최고 requireLevel 1개만 실제 평타, 빈 행동슬롯 채움 |
| `active` | 쿨 있는 액티브 직접시전 | 점유 | 쿨 돌면 idx순 발동 |
| `onStart` | 상시(전투 내내) | 없음 | 선반영 스탯이면 프리셋 포함(엔진 skip), skillBoost면 상시 적용 |
| `onActiveSkill` | **내가 액티브 스킬을 발동**하는 것에 편승 | 없음 | **평타 포함** 모든 액티브 발동마다 트리거(쿨만 없을뿐 평타도 액티브) |
| `onAttack` | **내 공격이 적을 때리는 것**에 편승 | 없음 | **공격 액션당 1번**(히트/다단타 무관), triggerBy 소스 필터 |
| `periodic` | 주기 타이머 | 없음 | periodMs마다 |
| `onHit`(canonical) | 피격/명중 편승 | 없음 | |

---

## 2. EFFECT 필드 (effect.role별)

효과 배열. `role`=**기전(무엇을 하는가)**. 상시/한시/딜무관 구분은 여기 안 넣고 skill 맥락(type/trigger/preReflected/stat종류)으로 판단.

### role: `deal` (직접 딜)
| 필드 | 의미 | 작용 |
|---|---|---|
| `coeff` | 스킬 계수(천분율) | 딜 = 공격력×배율들×coeff%×hits×targetCount |
| `hits` | 타격 횟수(개수) | 딜 배수(발동빈도엔 무관) |
| `targetCount` | 대상 수(개수) | 다중몹 딜 배수(단일보스=1) |
| `growthSlot` | 성장 슬롯 | base×Factor[slot][스킬레벨]/1000 (§3) |
| `form` | 딜 형태 | `dot`=durationMs 동안 tickMs마다 딜. ★clt Dot분류≠인게임(즉발이면 미부착) |
| `durationMs` | 지속(dot/소환) | dot·소환 tick딜 지속 |
| `tickMs` | 틱 간격(소환/dot) | durationMs 동안 tickMs마다 딜 |

### role: `statUp`(자기 스탯↑) / `debuff`(적 스탯)
| 필드 | 의미 | 작용 |
|---|---|---|
| `stat` | 스탯 이름 | §3 stat 표 |
| `value` | 값(%=천분율 / 깡=절대) | 스탯명이 합/곱·스케일 결정(§3) |
| `target` | 대상 | `enemy`(debuff). 없으면 자기(statUp) |
| `growthSlot` | 성장 | 버프 자체 성장(§3) |
| `durationMs` | 지속 | 한시버프만(상시=없음) |
| `preReflected` | 선반영 여부 | `true`/생략=프리셋 스탯창 포함(엔진 skip), `false`=엔진이 딜계산때 곱. **onStart에서만 의미** |
| `sustain`(canonical) | 지속 종속 | `summon`/`toggle` |

### role: `cc` (제어)
| 필드 | 의미 |
|---|---|
| `mode` | 제어 종류: `stun`·(`knockback`/`slow`/`freeze` canonical) |
| `durationMs` | 지속 (딜 무관, 상태이상 데미지 미적용) |

### role: `skillBoost` (타 스킬 강화)
| 필드 | 의미 | 작용 |
|---|---|---|
| `target` | 대상 스킬 idx | 그 스킬 딜에 적용(자식노드 가리킬 수 있음) |
| `type` | 강화 종류 | `finalDmg`=대상 딜에 최종뎀 곱 / `targetCount`=대상수 증가 |
| `value` | 값(천분율) | |
| `growthSlot` | 성장 | slot23=특수 성장 |

### role: `resource` (canonical)
`action`(generate/consume)·`name`·`target`·`amount`/`maxStack`. 스택/소비가 딜에 필수일 때만.

---

## 3. 값 규약

### growthSlot (성장, 선형 %/레벨)
| slot | 증가율 | 비고 |
|---|---|---|
| `12` | 0.5%/렙 | 액티브 딜류 |
| `21` | 0.4%/렙 | 패시브/기본 딜류 |
| `22` | 0.3%/렙 | 스탯 버프류 |
| `23` | 특수 | 최종뎀 스킬강화류(1+0.05×min(L,120)+0.01×max(0,L−120)) |
- 최종 = base × Factor[slot][스킬레벨]/1000. **스킬레벨=차수**(jobStep 1~4). **마스터리는 성장 안 따름**(고정).

### stat 이름 (★프리셋키 vs 원본이름)
**공격력·주스탯만 프리셋키로 깡/퍼 분리**, 나머지는 **원본 게임 이름 그대로**(임의 개명 금지).

| stat(원본명) | 진짜 의미 | 공식 항 | 합산 방식 |
|---|---|---|---|
| `ATK_FLAT` | 깡공격력 (프리셋키) | 공격력 | 깡합 |
| `ATK_PCT` | 공격력% (프리셋키) | 공격력 | 프리셋 공격력 = 깡공×(1+프리셋공%)로 **이미 resolved**(스킬 제외값). 스킬/유물 공%는 그 위에 **곱**(§3), 예외만 `unit:flat`(합) |
| `MAIN_STAT_FLAT` | 깡주스탯 (프리셋키) | 주스탯 | 깡합 |
| `MAIN_STAT_PCT` | 주스탯% (프리셋키) | 주스탯 → 스탯비례 데미지 | 프리셋 주스탯 = 깡주×(1+프리셋주스탯%) resolved. 스킬/유물 주스탯%는 그 위에 곱 → 스탯비례 데미지 경유(§3) |
| `AttackPowerToBoss` | 보스몹 데미지 | 보스뎀항 | %합산 |
| `AttackPowerExcludeBoss` | 일반몹 데미지 | 일반몹항 | %합산 |
| `BaseAttackPower` | 기본공격 데미지(평타뎀) | 평타뎀항 | %합산 |
| `SkillPower` | 스킬 데미지 | 스킬뎀항 | %합산 |
| `CriticalChance` | 크리티컬 확률 | 크리 | min(크확,100%) cap |
| `CriticalPower` | 크리티컬 데미지 | 크리 | **합산**(crit=1+min(크확)×크뎀, 곱 아님!) |
| `MinDamageRatio` / `MaxDamageRatio` | 최소/최대 데미지 배율 | (최소+최대)/200 | 체감 |
| `FinalDamage` | 최종 데미지 | 최종뎀 | ★고유 |
| `AttackSpeed` | 공격 속도 | (행동슬롯·발동빈도) | ★고유 |
| `Weakness` | 적 받는피해 증가(디버프) | 받피증 | ★고유 |
| `EnemyCritResist` | 적 크리티컬 저항 감소(디버프) | 최종 크확에 합류 | ★분류=디버프. `CRIT_RATE_ENEMY`. **크확 파생·증폭 제외** — 최종 fCrit 때만 크확에 +가산 |
| `EnemyRecvCritDmg` | 적 받는 크리티컬 데미지 증가(디버프) | 최종 크뎀에 합류 | ★분류=디버프. `CRIT_DMG_ENEMY`. **크뎀 파생·증폭 제외** — 최종 fCrit 때만 크뎀에 +가산 |
| `PiercePower` | 방어 관통(방관) | 방어배수 | ★고유 |
| `HitChance` | 명중 | — | 딜 거의 무관 |
| `BaseAttackMaxHitCount` | 기본공격 대상 수 | 평타 targetCount+ | 다중몹만(단일보스 무관), 개수 |
| `RunSpeedMms` | 이동 속도 | — | **딜 무관(엔진 무시)** |
| `MaxHp` | 최대 HP | — | **딜 무관(엔진 무시)** |
| `CriticalResist`(canonical) | 적 크리티컬 저항 | 크리 | → `EnemyCritResist`로 구현(위 표). 최종 크확 합류, 파생·증폭 제외 |
| `AttackPowerInCc`(canonical) | 상태이상 데미지 | 상태이상항 | 현재 미적용(컨텐츠 모드 시) |

### ★"고유 계산식 3종" 의미
- 대부분의 %스탯은 **끼리 합산**된 뒤 공식의 해당 항에 **한 번 곱**해진다(보스뎀·크뎀·평타/스킬뎀·`Weakness`(적 받피증) 등).
  - ★`Weakness`도 특별한 게 아님 — **합산되어 자기 항(받피증)으로 곱**하는 평범한 곱 스탯. "적에게 붙는 디버프"지만 연산상 **내 딜에 곱해지는 스탯 하나**일 뿐(스탯끼리는 곱이라 그런 것). 별도 고유식 아님.
- **`FinalDamage`·`AttackSpeed`·`PiercePower` 이 3종만 고유 특수식**(단순 합산 아님):
  - `FinalDamage`(최종뎀) = **효과별로 각각 곱**(1.2×1.3=1.56, 풀 합산 아님).
  - `AttackSpeed`(공속) = **체감 누적, 상한 150%**.
  - `PiercePower`(방관) = **체감 누적, 상한 100%**.
- ∴ 이 3종만 **stat 이름으로 엔진이 특수식 자동 적용**. 나머지 %스탯(Weakness 포함)은 합산 후 자기 항 곱.

### ★스킬/유물로 얻는 공격력% · 주스탯% (프리셋 resolved값 위에 곱)
프리셋 공격력·주스탯은 **깡×(1+프리셋%)로 이미 계산되어 온다**(스킬 제외값 — 게임 스탯 설명상 공격력/주스탯엔 스킬분 미포함). 스킬/유물분은 그 resolved값과 **합산하지 않고 그 위에 곱**한다(예외만 `unit:flat`으로 합류):
- **공격력%**(스킬/유물): `공격력 = 프리셋공격력 × Π(1+스킬공%) + Σ스킬깡공`. 늘어난 공격력이 공식 공격력 항으로 직접 곱.
- **주스탯%**(스킬/유물): `주스탯 = 프리셋주스탯 × Π(1+스킬주스탯%) + Σ스킬깡주스탯`. 늘어난 주스탯이 **스탯비례 데미지 `(1+스탯비례)` 항을 키워** 딜 증가(최종딜 단순곱 아님, 스탯비례 경유).
- ∴ 데미지에 **직접** 닿는 비-% 스탯은 **공격력뿐**(주스탯은 스탯비례 경유 간접).

---

## 4. MASTERY 필드 (masteries[], 캐릭레벨 해금)

| 필드 | 의미 | 작용 |
|---|---|---|
| `lv` | 해금 캐릭레벨 | lv≤캐릭레벨이면 적용 |
| `skill` | 소속 스킬 idx | 그 스킬(또는 자식) 강화. 전역스탯(StatData)은 skill 없음 |
| `masteryName` | 마스터리명 | 표시 |
| `masteryIdx` | 마스터리 인덱스(90xxx) | 식별 |
| `type` | 강화 종류 | ↓ type 표 |
| `value` | 값(스케일=스탯명·필드) | %스탯=천분율 / 개수·시간=정수. 시간계열은 unit이 가감 방식 결정 |
| `unit` | flat/pct 판별 | `flat`=값 정수 그대로 더함(합; +ms·+개수) · `pct`=천분율 비율로 곱(곱). **주 용도=시간계열**(지속/쿨/틱/주기) ms↔% 판별. damage %스탯은 스탯명이 스케일·합/곱 결정 → 생략 |
| `stat` | 스탯 이름(stat/skillStat) | §3 stat 표 |
| `role` | 성격(전역/패시브만) | `statUp`·`debuff`. 대부분 "그 스킬 귀속"이라 생략 |
| `target` | debuff 대상 | `enemy` |
| `gatedBy` | 종속 스킬 | skillStat/debuff 조건부(그 스킬 가동률 곱) |
| `durationMs` | 지속 | 주기딜 종속 디버프(부모 periodMs 대비 가동) |
| `effects[]` | 복합(효과 2+) | 각 원소가 type 보유(레벨엔 type 없음) |
| `note` | 비고 | 원본 op 순서 등 |

### mastery type (누적 합산, §SIM_MODEL 5.2 3단계 계산)
| type | 의미 | 적용 단계 |
|---|---|---|
| `skillDamage` | 그 스킬 피해량↑(GiveDamagePower) | ①스킬스펙 |
| `targetCount` | 대상 수+ | ①스킬스펙 |
| `hit` | 타격 수+ | ①스킬스펙 |
| `duration` | 지속↑(`unit:pct`=비율 / `unit:flat`=정수ms) | ①스킬스펙 |
| `cooldown` | 쿨↓ | ①스킬스펙 |
| `tick` | 틱 주기↓ | ①스킬스펙 |
| `period` | 발동 주기↓ | ①스킬스펙 |
| `probability` | 발동 확률↑ | ①스킬스펙 |
| `skillStat` | 그 스킬 딜 귀속 부가스탯(보스뎀 등) | ①(자기귀속=gatedBy X / 전역조건부=gatedBy O, §SIM_MODEL 5.3) |
| `stat` | 전역 캐릭 스탯(StatData 마스터리) | ②스탯확정 |
- **effects[] 원소 type**(복합): `value`·`duration`·`period`·`skillStat` 등.
- ★**type 3구분 혼동주의**: `value`=그 스킬 기존 버프의 수치 강화(발동조건 원스킬 내장, gatedBy 불필요) / `skillStat`=스킬에 없던 새 조건부 스탯(조건 별개→gatedBy) / `stat`=전역 상시(StatData).

---

## 5. 착시 방지 체크리스트
1. **%값만 천분율** — 500을 "500%"로 읽지 말 것(=50%). 개수/시간/레벨/깡스탯은 그대로.
2. **role은 화이트리스트** — utility만 아니면 딜 반영. utility만=제외.
3. **onActiveSkill/onAttack에 평타 포함**(평타도 액티브, onAttack은 액션당 1번).
4. **skillStat gatedBy** = 자기 딜 귀속이면 없음, 전역/타딜 조건부면 있음.
5. **preReflected는 onStart에서만** 의미(한시/주기형은 항상 엔진계산).
6. **고유 3종**(`FinalDamage`·`AttackSpeed`·`PiercePower`)만 합산 아닌 특수식. **`Weakness`는 고유 아님** — 합산 후 자기 항으로 곱하는 평범한 곱 스탯(적 디버프지만 내 딜에 곱).
7. **`CriticalPower`=크뎀=합산**(크확×크뎀 구조, 곱 착각시 대폭 과대). 크리 스탯은 원본명 그대로.
8. **가동률 안 따짐** — 버프는 시뮬 on/off(§SIM_MODEL §0).
9. **gatedBy 참조 무결성** — 경량화로 통합된 자식 idx를 gatedBy로 쓰면 dangling.
10. **검토 대상은 manual**(원본 아님) — 경량화 결정 존중.
11. **프리셋 공격력/주스탯은 깡×(1+프리셋%)로 resolved**(스킬 제외값). 스킬/유물 공%·주스탯%는 그 위에 **곱**(§3): 공격력%=공격력 항 직접 곱, 주스탯%=**스탯비례 데미지 경유**. `mode:post` 개념 폐지(스킬 공%는 기본이 곱, 예외만 `unit:flat`).
