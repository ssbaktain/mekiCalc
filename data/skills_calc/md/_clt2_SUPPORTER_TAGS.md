# clt2 동료(서포터) IR 태그 레퍼런스 (착시 방지 사전)

`data/skills_calc/supporter_manual.json`(손작업본)에 쓸 태그 정의. 자동추출 참고본=`supporter_seed.json`(빌드2694). 스킬 태그([[_clt2_IR_TAGS]])·유물 태그([[_clt2_ARTIFACT_TAGS]])·엔진([[_clt2_SIM_MODEL]]) 계승. 진실원천 [[clt-dump-sources]] `textAsset/dump_2694`.

> **위상**: 이 문서는 동료 IR 저술 **신규 계약**(2026-08-28 착수). 예전 `dev/_clt2_SUPPORTER_DESIGN.md`(구엔진 시절, 08-08)는 참고용일 뿐 정본 아님. **빌드2694 SupporterTable 등에서 새로 규명**(방법론 [[working-principles]] §6).

---

## 0. 대원칙

- **★★착수 전 문서 정독(최우선).** 동료 작업 시작 전 이 문서 + [[_clt2_IR_TAGS]](특히 **trigger 표**)·[[_clt2_SIM_MODEL]]·[[_clt2_SUPPORTER_ENGINE]]를 전부 정독하고 **기존 선례와 대조**한다. 임의 판단·임의 분류 금지. (이 규칙을 어겨 나이트워커 onActiveSkill 오구현이 발생 — 2026-09-11.)
- **★트리거 계약(스킬과 동일, [[_clt2_IR_TAGS]] §trigger 표 따름)**:
  - `basicAttack`=평타(ReplaceBaseAttack, 행동슬롯 점유) · `active`=쿨 있는 액티브(점유) · `enter`=소환 순간 1회 · `onStart`=소환중 상시 패시브(점유X) · `periodic`=주기(periodMs).
  - **`onActiveSkill`=내가 스킬을 발동하는 것에 편승 = ★평타 포함 모든 액티브 발동마다(쿨만 없을뿐 평타도 액티브). 단 `type:passive`로 자동 발동하는 패시브 proc은 "액티브 발동"이 아니라 제외.** (clt 근거: OnActiveSkill 트리거는 `SlotIndex`·`IsBaseAttack` 필드=슬롯 능동 사용 / 선례: 나로 아드레날린 93080 count5. 엔진: 평타+액티브 시전 스트림, proc 제외.)
  - **`onAttack`=내 공격이 적을 때리는 것에 편승 = 공격 액션당 1번(히트/다단타 무관), chance·triggerBy 필터.** (엔진 attackTimes=액션당 1회.)
  - ★`active`(cd 있는 clt 스킬)를 `onStart`/패시브로 임의 재분류 금지 — 딜/스탯 무관 유틸 액티브라도 clt 타입(`active`) 유지(IgnoreBaseAttackDamage 등).
- **★★누락 방지 — 엔진 커버리지 규칙(2026-09-11, 반복 누락 근본원인)**: 엔진은 **(role × trigger × target × stat) 조합마다 전용 경로**가 있고, authoring이 미처리 조합에 떨어지면 **조용히 드롭**된다(과거 누락: periodic 디버프·버프-only onActiveSkill·routeSelf 미매핑 stat·ModStatOnTick). 저술 시 아래 경로·매핑 안에 있는지 확인:
  - **deal**: trigger ∈ {basicAttack, active, enter, periodic, onAttack, onActiveSkill}만 방출(onStart deal 없음). 투사체/채널링/도트는 form·tickMs·tickCount로.
  - **statUp/debuff → player/enemy/both**: `SupporterEngine.toBuffSource` 매핑 stat만(FinalDamage·ATK_PCT·DMG·Critical*·MaxDamageRatio·AttackPower*·SkillPower·BaseAttackPower·Weakness·Defence·EnemyCritResist/CriticalResist·EnemyRecvCritDmg·AttackSpeed). 트리거 수집경로: onStart(§상시루프)·periodic(collectPeriodicPlayerBuffs, **statUp+debuff 둘 다**)·enter/active(collectPlayerBuffs)·onAttack/onActiveSkill(확률누산/편승 루프).
  - **statUp → companion(self)**: `SupporterTimeline.routeSelf` 매핑 stat만(+PiercePower=방관). applySelfBuffs가 상수/램프(periodMs+maxStacks)/시간창 적용. **routeSelf에 없는 stat은 동료 self에서 드롭** → 필요시 routeSelf에 추가.
  - **채널링(tickCount)**: 채널 지속(tickStart+(count-1)*tickMs, tickBySpeed면 공속압축)만큼 **로테이션 잠금**(그동안 다른 액티브·평타 불가, channelLockMs). 송오브스카이·스톰애로우류.
- **★저술 후 필수 검증 3종(매 동료)**: ①트리거 감사(manual trigger vs clt TriggerType, UsePassiveSkill/OnHit/ModStatOnTick 예외 포함) ②커버리지 감사(모든 used:true effect가 위 경로·매핑 안인지 = 잠재 드롭 0) ③계수 대조(coeff/hits/maxHitCount/버프값/쿨 vs clt, 투사체·틱 대괄호 발동수 포함). (dev/_scratch/_audit_triggers·_audit_coverage·_verify_* )
- **동료 = 소환 개체로 직접 딜을 낸다.** (유물=스탯소스·딜0과 다름. 스킬과 유사하게 자기 스킬로 타격.)
- **동료 딜의 두 축**:
  1. **계승(inheritance)** — 소환 시 플레이어 공격 스탯의 일정 비율을 물려받아 자기 공격력으로 삼음(§4).
  2. **자기 스킬** — 동료의 평타/패시브/액티브가 스킬 IR 규약([[_clt2_IR_TAGS]])대로 딜(§3).
- **값·필드·의미는 클뜯 원본 결정론.** 내가 붙이는 건 라벨(role 분류)+근사방식(gatedBy/refresh/chance/count)뿐. 임의 개명·값 변경 금지.
- **스케일 규약은 스킬/유물과 동일**: %/비율 stat `value`·`coeff`·`chance`=천분율(÷10=표시%, /1000=배율) · `durationMs`/`tickMs`/`cooldown`/`ms`=절대 · `hits`/`targetCount`/`count`/`lv`=개수 그대로 · 깡스탯(`ATK_FLAT` 등)=절대.
- **옵션(Collection/Equip)은 계정 전역 상시 스탯 → 소환딜과 분리**(§5). 이미 프리셋 스탯창에 반영되므로 **이중계산 방지**(동료 소환딜 계산엔 넣지 않음).
- **소환 = 한시 가동(30초) + 쿨**. 가동률을 스칼라로 곱하지 않고(§SIM §0) 타임라인에서 소환창 on/off. 전투창(보스70/사냥90초) 안의 소환 횟수·부분가동은 이산 시뮬로.

---

## 1. 진실 원천 테이블 (빌드2694, 재현 경로)

| 테이블 | 역할 | 핵심 필드 |
|---|---|---|
| **`SupporterTable.json`** | 동료 마스터(61종) | §2 |
| `CreatureTable.json` | 동료 개체 → 실제 공격 스킬 | `BaseAttack.SkillIndex`(딜 스킬), 패시브 skill idx |
| `SkillTable.json`(+1/2) | 스킬 op 원본 | `GetDamageR.Values`(coeff)·`CoolTimeMs`·`DurationTimeMs`·`TickTimeMs`·`TargetCount` |
| `SupporterLevelStatFactorTable.json` | 계승 성장 factor(§4) | 열=levelFactor(8~12) × 행=동료레벨 |
| `SupporterOptionTable.json` | Collection/Equip 옵션(§5) | `StatType`·`Value`·`Factor` |
| `SupporterGetEffectTable`·`SupporterStatTypes` | UI연출·스탯타입 목록 | (딜 무관) |
| `Localization_ko.json` | 이름/설명(AES 암호문) | `_clt_decrypt.py` 경유 |

**동료 스킬 4소스**(빌드2694 실측):
1. **평타** = `CreatureIndex` → `CreatureTable.BaseAttack.SkillIndex`(+`ExtraPassiveSkills[]`). 예: NightLord G2(9109) BaseAttack=10002.
2. **소환 시전** = `SupporterTable.EnterSkillIndex`(소환 순간 1회 발동 딜/버프). 예: NL G2=809100·G3=809200.
3. **액티브** = `SupporterTable.MainActiveSkillIndices[]` (+`OpenActiveSkillLevel[]`=각 해금 동료레벨). 예: NL G3=[809210, 809220].
4. **패시브** = `SupporterTable.MainPassiveSkillIndices[]` (+`OpenPassiveSkillLevel[]`). 예: NL G2=[809120].
- 각 skill idx → `SkillTable.json`(+1/2)에서 op 조회. 스킬 idx 규칙 ≈ `809{등급스텝}{슬롯}` (등급별 상이).
- ★`SpawnSkillIndex`(=상수 **5**)는 **범용 소환 트리거**(op `SpawnSupportCreature`, CoolTime 90000=소환쿨, 딜 없음) — 딜 원천 아님.
- **coeff 스케일**: op `GetDamageR` `Values:[coeff, "None", hits]` — coeff는 **천분율**(÷10=표시%, 스킬 IR와 동일). ★단 구 `companions.json`과 값 대조 필수(추출 스냅샷 차이 가능, §아래).

---

## 2. SUPPORTER 필드 (top-level, `SupporterTable`)

| 필드 | 의미 | 값/규칙 | 엔진 작용 |
|---|---|---|---|
| `SupporterIndex` | 동료 인덱스 | 정수(1·101·201·301·401=Hero 5등급) | 식별 |
| `SupporterCode` | 코드 | `SUPPORTER_{직업}_{등급스텝}` 스텝 **0~4 = Grade1~5** | 프리셋 매핑 |
| `ClassType` | 직업 | Hero·ArchMageIL·BowMaster·Shadower·Captain·Paladin·DarkKnight·ArchMageFP·Bishop·Marksman·NightLord·Viper·NightWalker·WindBreaker (14종) | 표시·딜스킬 성격 |
| `GradeType` | 등급 | `Grade1`~`Grade5` | coeff·factor·옵션 스케일 |
| `CreatureIndex` | 개체 인덱스 | **`9{직업}{등급:02d}`** (9001=Hero G1, 9401=Hero G5) | → CreatureTable 딜 스킬 |
| `SpawnSkillIndex` | 소환 트리거 | **상수 5**(딜 아님, §1) | 소환 발동만 |
| `SpawnDurationTimeMs` | 소환 지속 | **상수 30000**(30초) | 소환창 길이 |
| `EnterSkillIndex` | 소환 시전 스킬 | skill idx(809x00) | §3 딜소스②(소환 순간 1회) |
| `MainActiveSkillIndices[]` | 액티브 스킬들 | skill idx[] | §3 딜소스③ |
| `OpenActiveSkillLevel[]` | 액티브 해금 동료레벨 | 정수[](Active와 짝) | 동료레벨≥값이면 사용 |
| `MainPassiveSkillIndices[]` | 패시브 스킬들 | skill idx[] | §3 딜소스④ |
| `OpenPassiveSkillLevel[]` | 패시브 해금 동료레벨 | 정수[](Passive와 짝) | 동료레벨≥값이면 적용 |
| `LinkedAttackStatBaseRatio` | 계승 기본비율 | **상수 1000** | §4 |
| `LinkedAttackStatLevelFactor` | 계승 성장열 | **Grade1~5 = 8·9·10·11·12** | §4 factor 열 선택 |
| `CollectionOptionIndices[]` | 도감 옵션 | 10xxx~14xxx | §5(계정전역) |
| `EquipOptionIndices[]` | 장착 옵션 | 20xxx~24xxx | §5(계정전역) |
| `NameLocalKey`/`DescLocalKey` | 로컬키 | 복호화 필요 | 표시 |

- **총 61종** = 14직업 × 등급. ★**등급 비균일**: `GradeType` Grade1~5 = 절대등급 NORMAL/RARE/EPIC/UNIQUE/LEGENDARY이나 **직업마다 보유 등급 부분집합**(예 NightLord=Grade2~5 4등급, Grade1 없음 / Grade1(NORMAL)은 주로 직업군 Cadet 5종). seed로 전수 확정, 균일 가정 금지.
- **소환 쿨**: SpawnSkill(5) `CoolTimeMs 90000`(90초). 구엔진 기본 쿨 90초와 일치. (쿨감은 동료 미적용 §4, 소환지속% 반영은 §6.)

---

## 3. 딜 모델 (동료 스킬 = 스킬 IR 태그 재사용)

동료의 평타/패시브/액티브는 **스킬 IR([[_clt2_IR_TAGS]]) 규약 그대로** IR화한다(별도 문법 최소화). 동료 IR은 동료별로:
```
supporter { code, class, grade, creatureIndex,
  spawnDurationMs, cooldownMs, linkedAttack{baseRatio, levelFactor},
  skills[ (스킬 IR와 동일: idx·role·type·trigger·coeff·hits·targetCount·growthSlot·durationMs·tickMs·effects[] …) ],
  options{ collection[], equip[] }  // §5, 딜 분리
}
```
- **clt `SkillTriggerType` enum(dump.cs 확정)** → 태그 매핑:
  - `None`(0) = 액티브 직접시전(쿨 있음). `TriggerFlag:"ReplaceBaseAttack"`면 평타 대체형.
  - `OnStart`(16) = 소환 상시(패시브 버프/디버프, DurationMs 99999999=소환창 내내).
  - `OnTimer`(17) = 주기(periodMs) 발동.
  - `OnActiveSkill`(14)·`OnSpawnSupporterSkill`(15) = 편승형.
  - `EnterSkillIndex` 스킬 = 소환 순간 1회.
  - ★구엔진 우리 라벨(`companionAttack`·`companionBasicAttack`·`companionHit`·`period`)의 정확한 clt TriggerType은 **seed 시 per-skill 대조로 확정**(agent 미확정, 임의 신설 금지).
- **coeff/hits/targetCount는 등급별 상이** → 등급마다 Enter/Active/Passive/BaseAttack 스킬을 각각 추출(§1). 공용화하지 말 것.
- **★자식 스킬 = 딜 본체 별도**(직업 스킬과 동일 구조, 빌드2694 확정): 부모 스킬 idx는 **끝자리 0**, 자식은 **끝자리 1~9**.
  - 부모(끝0) = **런처**: `TriggerType:None`+`TriggerFlag:ReplaceBaseAttack`, 최소 GetDamageR(단일).
  - 자식(끝1~9) = **딜 본체**: `TriggerType:OnTryAttack` + `TriggerCondition:"$Trigger.SkillIndex == {부모idx}"`. 실제 `MaxHitCount`(=대상수)·AoE(RectAreaMm)·본체 coeff/hits 보유. (직업 스킬은 자식에 `FollowLevelIndex:{부모}` 역참조도 있음.)
  - **명시 ChildSkillIndex 필드 없음** → 추출기는 "끝자리 형제 + OnTryAttack TriggerCondition 매칭"으로 자식 발견. **자식 본체값을 부모 effect로 통합**(경량화). ★검증: 나로 슈리켄버스트 92010(부모, 단일) + 92011(자식 MaxHitCount 4) → seed 92010 effect `coeff 400·hits 3·targetCount 4` = 자식 본체 반영됨. 동료도 동일(809210+809211).
- **★자원(resource) 형식 스킬**: `StateTable`의 maxStack 상태를 **생성**(op `ModStatR`/`State`, State명 지정)하고 다른 스킬이 **소비/참조**(`$CreatureStateCount(...;State) ×N`로 딜 스케일)하는 스택형(히어로 콤보 등). op `ModStateMaxStack`=스택 상한 변경. → 스킬 IR **`role:"resource"`**(action generate/consume·name·maxStack, [[_clt2_IR_TAGS]] §2) 재사용. **딜에 필수일 때만 반영**, 아니면 슬롯만.
- **동료 자기버프**(공속·최종뎀 등)는 소환창 안에서만 on. 스킬 effect 규약(role `statUp`/`deal`/`debuff`) 그대로.
- **★동료→적 디버프(받피증 등)**: 내 딜에도 공유되는지(구엔진 playerRecvBonus 공유)·동료 딜에만인지 clt로 확인. (§6에서 결선.)

---

## 4. 계승(inheritance) — ★확정(우리 추출·인게임 검증)

★**정본 데이터 = `data/companions.json`**(구엔진 시절 이미 클뜯 추출+인게임 검증 완료, 커밋 `69eb979`). 신엔진도 이 산출을 재사용/재추출한다(임의 재계산 금지).

### 공격력 계승비율(%) — 확정 공식
```
계승비율%(공격력) = SupporterLevelStatFactorTable.Factor[levelFactor][동료레벨] / 10
```
- **검증**: 섀도어 레전더리(Grade5=levelFactor 12) 동료레벨 4 → **72%** (Factor[12][4]=720). ✓ 인게임 일치.
- `levelFactor` = 등급별: **NORMAL 8 / RARE 9 / EPIC 10 / UNIQUE 11 / LEGENDARY 12** (=Grade1~5, `SupporterTable.LinkedAttackStatLevelFactor`와 동일).
- **level-1 base**(=`inheritBaseRatio`): NORMAL 25 / RARE 50 / EPIC 50 / UNIQUE 50 / LEGENDARY 60 (%).
- **만렙 캡 = 전 등급 90%**. 동료레벨 상한(`gradeLevelCap`) = NORMAL 100 / RARE 50 / EPIC 30 / UNIQUE 10 / LEGENDARY 16.
- **전개표 = `companions.json.inheritByLevel[grade][동료레벨-1]`**(등급별 레벨→% 배열, 이미 /10 반영·캡 적용된 최종값). 엔진은 이 배열을 직접 참조.
- **플레이어 레벨 보정**: 플레이어 차수레벨 < `compareLevel`(EPIC 30 / UNIQUE 60 / LEGENDARY 100)이면 계승비율 × (플레이어레벨 / compareLevel) 추가 감산. **NORMAL/RARE는 보정 없음**. (예: 레전 compareLevel 100, 플레이어 28렙 → ×0.28.)
- ★`LinkedAttackStatBaseRatio=1000`은 이 /10 정규화의 분모 역할(즉 Factor/1000=배율=Factor/10 %). ~~(구 후보 "(1000+Factor)/1000"은 오독 — 폐기)~~.

### 계승 대상 스탯 (`companions.json._statRefNote`)
- **공격력(ATK)만 위 비율로 부분 계승**: `동료ATK = player.ATK × (1+player.ATK_PCT) × 계승비율%`.
- **나머지 스탯은 온전히(100%) 계승** — 소환 당시 플레이어 값 그대로: CRIT_RATE·CRIT_DMG·ATK_SPEED·MAIN_STAT·STAT_PROP_DMG·DMG·DMG_AMP·BASIC_ATK_DMG·SKILL_DMG·DEF_PEN·BOSS_DMG·NORMAL_DMG·MIN_DMG_MULT·MAX_DMG_MULT·FINAL_DMG.
- **소환 후 플레이어 스탯 변화는 동료에 무영향**(스냅샷) — 이후엔 동료 자기버프만 얹힘.

### ★미계승·미적용 (확인됨 — 명심)
- **쿨감(cooldown reduction) 미적용**: 동료는 플레이어 쿨감을 **적용받지 않는다**(자기 스킬 쿨은 원본 그대로). ※내부적으로 안 받는지/받고 안 쓰는지는 불명이나 **결과적으로 미적용** = 엔진은 동료 스킬 쿨에 쿨감 곱 금지.
- **1/2/3/4스렙·모스렙(스킬레벨) 미적용**: 동료 스킬 계수(coeff)는 스킬레벨로 **변하지 않는다**. ※받는지 여부 불명이나 **결과적으로 계수 불변** = 동료 딜에 성장(growthSlot/스킬레벨) 곱 금지.
- ∴ 계승 스탯 목록(위)에 **쿨감·스킬레벨 계열 없음** — 명시적으로 제외.

---

## 5. 옵션(Collection/Equip) — 계정 전역, 딜 분리

- `CollectionOptionIndices`(10xxx~14xxx)·`EquipOptionIndices`(20xxx~24xxx) → `SupporterOptionTable`(`StatData{Type, Value, Factor}`).
- ★★**성장 확정·인게임 검증완료**(2026-08-28): 옵션 `Value`는 **Lv1 base**, 실제값은 **동료레벨**로 성장한다. **계승과 같은 `SupporterLevelStatFactorTable`**(컬럼만 다름).
  - **공식**: `옵션 실제값(천분율) = base_Value × SupporterLevelStatFactorTable.Factor[옵션Factor][동료레벨] / 1000`, %스탯은 ÷10=표시%.
  - ★**검증 앵커**(인게임 일치): **레전 비숍(SUPPORTER_6_4) 동료레벨 6** → Factor[6][6]=2030(2.03×)·Factor[7][6]=1500(1.5×) → 보유 데미지증가 base100→**20.3%**·최대HP base150→**30.45%** / 장착 스킬뎀 base80→**12.0%**. ✓ (%=천분율÷10 확정)
  - **컬럼 이원화**(핵심 증거): 계승=컬럼 **8/10/12**(250/500/600 시작→900 캡) / 옵션=컬럼 **1·4·5·6·7·13**(전부 **Lv1=1000=1.0×** 시작→성장). 옵션 `Factor` 필드값이 곧 이 컬럼 인덱스. Lv1에서 1000이라 base×1.0=base(seed값)로 자기정합.
  - **양상**: 장착(col7/13)=선형(≈1+0.1×(레벨−1), lv30 3.9×·lv100 10.9×) / 도감(col1/4/5/6)=지수(lv30 9.5×·lv100 68×). ★옵션은 계승과 달리 **90% 캡 없음** — 동료레벨 캡(grade별 100/50/30/10/16)까지 계속 성장. 표가 lv300까지지만 동료 만렙 범위만 사용.
  - ★**소환딜엔 무관**(옵션=계정전역·딜분리·used:false). 성장 공식은 "옵션→플레이어 스탯 기여" 기능 때 사용.
- `StatType`은 **다양**(17종): Attack·MaxHp·MainStat(R)·AttackPower·AttackPowerToBoss·ExcludeBoss·BaseAttackPower·SkillPower·CriticalChance·CriticalPower·MaxDamageRatio·MinDamageRatio·AttackSpeed·HitChance 등. 등급↑=스탯 종류·값 좋아짐(예 G1 Collection Attack+25 → G3 MainStat+70 → G5 AttackPower+100; Equip G3+ MaxDamageRatio 등).
- **성격 = 계정 전역 패시브**(동료 보유/장착으로 항상 켜짐, 소환과 무관). ∴ **프리셋 스탯창에 이미 반영** → **동료 소환딜 계산엔 넣지 않는다**(이중계산 방지). 별도 풀로 두되 딜엔 소환 스킬+계승만.
- 딜무관 스탯(MaxHp 등)은 엔진 무시(스킬 규약 동일). stat명은 clt 원본 유지(§7-8).

---

## 6. 엔진 단계 (v3) — 신엔진 조립 + 구엔진서 보존할 것

**신엔진 타임라인 조립(v3)**:
- 소환창 = `SpawnDurationTimeMs × (1+소환지속%)`, 소환 쿨 = 90000 × (1−쿨감). 전투창[0,T] 안에서 소환 시점마다 창 배치, 경계는 부분가동(frac).
- 동료 딜 소스는 **플레이어와 분리 집계**(딜 비중 탭 기여 측정 = leave-one-out, [[_clt2_SIM_MODEL]] §8).
- 계승 공격력(§4 확정식) → 동료 스킬 coeff에 곱. **쿨감·스킬레벨은 동료에 미적용**(§4).
- 옵션(§5)은 딜 파이프라인 제외(프리셋 반영분).

**구 `CompanionDps`/`CompanionEngine`에서 보존할 검증된 메커니즘**(임의 폐기 금지, [[engine-migration-state]]):
- 소환창 DT=0.1 이산 적분 = `dpsOverSeconds(T)` / 폐형 `dpsOverSecondsAnalytic(T)`(보스70·사냥90초, 부분가동 frac).
- 계승 = `player.ATK×(1+ATK_PCT/100)×비율`(공격력만 부분, 나머지 스탯 100% 계승). ★비율=§4 확정식(companions.json inheritByLevel). 쿨감·스렙 미적용.
- 등급별 gradeBasicAttack(coeff/hits/targets) — ★이번엔 CreatureTable 실추출로 대체(구엔진 하드코딩 대신 데이터 구동).
- passiveProcRate trigger(period·companionBasicAttack·companionAttack ×chance, 내부쿨 캡), Viper burst→assault 치환 등 직업 특이.
- 받피증 공유 `recv = 1 + companionRecvSum + playerRecvBonus`, 공속 체감 상한 150%.
- UNIQUE/LEG 레벨 보너스(+최종뎀/레벨티어) — clt 근거 재확인 후 유지/수정.

---

## 7. 착시 방지 체크리스트

1. **SpawnSkillIndex(5)는 딜 아님** — 동료 스킬 4소스 = BaseAttack(CreatureTable)+Enter/MainActive/MainPassive(SupporterTable, §3). 등급별 상이. ★seed coeff는 구 companions.json과 값 대조(스냅샷 차이 가능).
2. **자식 스킬 = 딜 본체 별도**(§3) — 부모(끝0)는 런처, 자식(끝1~9, OnTryAttack)이 실제 MaxHitCount·AoE·본체. 부모만 추출하면 대상수/딜 누락. 반드시 자식 통합.
3. **자원(resource) 형식**(§3) — StateTable 스택 생성/소비. 딜 필수면 `role:resource`로, 아니면 슬롯만.
4. **%값만 천분율**(coeff·value·chance). ms·개수·레벨·깡스탯은 그대로.
5. **옵션(Collection/Equip)은 계정 전역 → 소환딜에서 제외**(이중계산 방지). 프리셋 반영분.
6. **계승 = Factor/10 %**(§4 확정, companions.json). 만렙 90% 캡 + compareLevel 플레이어보정. ★"(1000+F)/1000" 오독 폐기.
7. **계승은 공격력만 부분·나머지 100%**. **쿨감·1~4스렙·모스렙은 동료 미적용**(쿨 원본·계수 불변) — 곱하지 말 것.
8. **가동률 스칼라 곱 금지** — 소환창은 타임라인 on/off + 부분가동 frac.
9. **등급 상수 vs 가변 구분**: BaseRatio 1000·SpawnDuration 30000·SpawnSkill 5는 상수 / LevelFactor 8~12·coeff·옵션값은 등급 가변.
10. **stat명 클뜯 원본 유지**(임의 개명 금지). 프리셋키 예외 3개(ATK_PCT·MAIN_STAT_FLAT·DMG)만 스킬/유물과 동일 적용.
11. **검토 대상은 manual**(seed 원본 아님) — 경량화 결정 존중, 단 누락·임의변경 금지.
12. **직업별 등급 수 상이**(61종=14직업×비균일 등급) — seed 전수로 확정, 균일 가정 금지.
13. **동료 공격 = 별개 스트림**(예전 확정) — 플레이어측 `onAttack`/`onHit` 판정(유물 은펜던트·돼지리본, 직업 '공격/타격 시' 스킬)은 **동료 공격에 발동 안 함**(플레이어 본인 공격만). 동료 자신의 onAttack proc은 동료 자기 공격만 편승. 상호 미교차.
14. **조건(cond) 기본 상시** — 별도 미지정 조건은 충족 가정(효과 활성). 예외만 개별 명시. 조건 주체=effect target(hp/mpRatio=플레이어).
15. **★설치기(installer)는 소환 해제 시 제거**(2026-09-05 확정, 유저 실측+클뜯) — **바닥 장판·소환체 = `CreateProjectile`로 까는 것**(clt Flag **`ExpireOnCreatureDie`**)은 동료(소환체)가 사라지면 **같이 사라진다**. 따라서 설치기 딜(특히 도트)은 **소환창(windowMs)에서 절단**(simCap까지 끌지 않음). manual effect에 **`expiresOnSummonEnd: true`** 마킹 → 엔진 `emitDeals`가 windowMs에서 컷.
    - **설치기**(=마킹 대상): 장판(포이즌 미스트류)·주기 소환구름(OnTimer 독안개)·소환 정령(이프리트) 등 CreateProjectile 기반. 재설치 시 이전 것 제거(MaxProjectileCount).
    - **비-설치기**(=마킹 안 함, 소환 종료 후 잔존): **적 상태칸 도트**(`GetDotR`/`GetDamageR Dot`가 적 State에 거는 중독 등)·**버프**(플레이어 상태)·**디버프**(적 상태, 약화·방깎)·**즉발 타격**. 적/플레이어에 걸린 거라 소환과 무관하게 지속.
    - ★판별: clt op가 `CreateProjectile`(ExpireOnCreatureDie)면 설치기, `GetDotR`(적 State)·`ModStat`(버프/디버프)면 비-설치기.
15b. **★디버프/버프 갱신(refresh) = op 위치로 판별**(2026-09-10 확정, 썬콜 프리징브레스 804440) — 디버프 op(`ModStat`)이 **틱 딜스킬**(설치기가 0.5s마다 발동하는 자식 딜, 예 804441)에 있으면 **매 타격마다 갱신** → manual effect에 **`refresh: true`**. 실효 창 = **설치dur(같은 스킬 딜 지속, `emitDeals` 기준) + op `durationMs`**(엔진 `collectPlayerBuffs`가 자동 계산, **값 하드코딩 금지**). op이 **액티브 스킬 자체**에 있으면 1회 발동 → `refresh` 없음(창 = `durationMs`).
    - ★`durationMs`에는 **op 원본 지속**(프리징브레스=5000)을 넣고, 설치dur 합산은 엔진이 함(10000으로 미리 계산해 박지 말 것 — 그게 하드코딩). 다음에 같은 매커니즘(틱마다 디버프 갱신)이면 동일하게 `refresh:true`만.
16. **★onAttack 확률 버프 = 확률 누산식**(2026-09-06 확정, [[_clt2_SIM_MODEL]] §79) — 딜 없는 onAttack proc 버프(팔라딘 홀리차지·히어로 콤보 등)는 **시간평균 스칼라(가동률×값) 금지**. 공격 스트림마다 chance(‰) 누산 → 1000 도달 시 발동, **발동마다 durMs 창**. 엔진 `SupporterTimeline.sharedStackSegments`/`procWindows`. manual: `trigger:"onAttack"`+`chance`(‰)+effect `durationMs`(+`maxStacks`). ~~procBuffUptime/procEffective(스칼라)는 폐기.~~
17. **★중첩 지속방식 = 공유 타이머(기본) / 개별은 "개별 적용" 명시분만**(2026-09-06, 인게임 실측+클뜯 확정):
    - **기본(공유 타이머, refresh)**: 재발동 시 타이머 전체 갱신+스택+1(캡 maxStacks), 만료 시 **전 스택 동시 소멸**(히어로 콤보 MaxStack5 실측: 5스택→한 번에 꺼짐). manual `maxStacks`만.
    - **개별 지속(은펜던트식)**: 발동마다 독립 창, 겹침=스택, 개별 소멸(풀중첩 짧음). ★**게임 설명에 "지속시간은 개별 적용됩니다" 명시된 것만** → manual `individualDur:true`. (은펜던트 desc가 유일 근거 — 콤보 등 일반 스택엔 그 문구 없음=공유.)
    - StackRule(None/Override/FollowOperation)은 스택 '개수' 규칙이지 지속 방식 아님(콤보=Override=공유). ★DLL StateChangerList(IStateChanger 리스트)만 보고 '개별'로 오독했다가(2026-09-06) 인게임 실측·게임설명으로 공유 확정 — **표시 테이블·구조보다 인게임 실측·설명 문구 우선**.
18. **★FinalDamage 중첩 = 한 항 합산 vs 항별 곱**(2026-09-06 규명): 최종뎀은 **소스별 곱**(gradeBonus 3개 stateless op = ×1.25³ 인게임 검증)이지만, **같은 스택 상태(MaxStack N) 내 중첩은 한 소스 = value×스택 선형(한 항)**. 예 나로 809470(단일 상태 SupportNightLord_FinalDamageStack MaxStack15)=×(1+0.015×15)=×1.225 (×1.015¹⁵ 아님). ★구분: 스택 상태 내 중첩=한 항 / 별개 op·상태=항별 곱. 엔진 `applySelfBuffs` 스택 램프가 이미 한 항(value×stacks)으로 처리(정확).
19. **동료 스킬명 = clt `SkillTable.ActionName`(내부 액션명) 음차** — 딜 스킬은 ActionName(FlameSweep→플레임스윕 등) 옮김, 액션명 숫자/None(패시브·enter)은 기능으로 명명. ★동료 스킬엔 `NameLocalKey`/`DescLocalKey`가 없어 공식 표기명 불가(내부 액션명이 정본 아님, 표시용). 진실원천은 스킬 idx.
