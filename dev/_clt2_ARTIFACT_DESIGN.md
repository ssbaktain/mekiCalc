# clt2 유물(Artifact) 설계도 — 딜 기여 소스 2/3 (스킬 IR과 별도)

유물의 모든 구조·값·관계·계산규칙 전수 파악. 규칙 원천=[[mekicalc-artifact-spec]](32종 전수감사 완료), 데이터=textAsset 클뜯. 본체 스킬 IR과 완전 분리(유물=90대역, 본체 클로저 미포함 확인됨).

> ★핵심 성격(실측 확정): **유물은 "스탯 기여 소스"지 직접 타격딜(GetDamageR)이 없다.** 전32종 EquipSkill에 딜 계수 op 0건 — 전부 스탯 버프/디버프/조건부 스탯. 유일한 CreateProjectile(5_18 동맹의 증표)도 투사체 dealSkill이 `ModStatR[Attack]`(공격력% 버프 필드)이라 딜 아님. → 엔진은 유물을 **딜 계수가 아니라 스탯 버킷(공식 항)**으로만 처리(계산 단순).

> ★★계층 분리(스킬 IR 원칙 계승): 이 문서는 두 층을 담는다.
> - **IR 데이터 스펙(§0~3·§7·§8·§9)**: 클뜯 충실 보존. 원본 op·값·관계·State조인·주기(TriggerTimeMs)·지속(op.DurationMs)·조건(condTree)까지 **데이터만**. Stage가 산출.
> - **계산 명세(§4·§5·§6)**: 데이터를 딜로 해석하는 **정책/규칙**(다중첩=선형·조건부=최대치·시간버프=리셋·컨텐츠 발동판정·성배 처치율 등). **IR 산출물에 넣지 않고**, 엔진 구현부가 참고·구현. 사람이 감사. → "적어둔 명세"이지 IR 필드가 아님.
> - **엔진(코드)**: 명세를 실행. 추후 제작.

## 0. 유물 구조 (32종) — ★실측 확정
- **등급**: Grade3(에픽)4 · Grade4(유니크)8 · Grade5(레전)20 = 32. ⚠️Grade6 테이블(HiddenForce/AwakeCost 등)은 존재하나 **유물 0개=미래 확장용**(무시).
- **코드 규칙**: `ARTIFACT_g_n` (g=등급숫자 3/4/5, n=순번). EquipSkillIndex = `9`+`0g`+`nn`+`a`+`0` (a=각성0~5). 예 903 01 4 0 = 유물301의 4각.
- **각성 0~5각**: EquipSkillIndices 6개 = **각성 단계별 완성값을 각각 별도 스킬로 저장**(값은 강화되나 스킬성장이 아니라 슬롯 선택. 예 301 Attack 100→120→140→160→180→200 = 0~5각). 유저 각성레벨로 `EquipSkillIndices[awaken]` 하나만 적용. ★전32종 6칸 고정(전 등급 각성 0~5 통일, AwakeCostTable 확인).
- **4종 효과**: ①장착효과(EquipSkill, 각성별) ②보유효과(CollectionOption, 1~2개) ③잠재(HiddenForceOption, 확률줄) ④파워(PowerTable).
- **잠재 슬롯**(ArtifactHiddenForceTable, 등급별 확정): 에픽 2슬롯(각성2·4 해금)·유니크 2슬롯(각성1·3)·레전 3슬롯(각성1·3·5).

## 1. 데이터 소스 (12테이블)
| 테이블 | 역할 | 딜 |
|---|---|---|
| **ArtifactTable**(32) | 마스터: Index·ArtifactCode(ARTIFACT_g_n)·GradeType·EquipSkillIndices(6=0~5각)·CollectionOptionIndices·NameLocalKey | ✅ |
| **ArtifactOptionTable**(34) | 보유효과: {OptionIndex, StatData{Type,Value,Factor}} | ✅ |
| **ArtifactHiddenForceOptionTable**(216) | 잠재: {Index,GradeType,SlotIndex,StatData{Type,Value},Prob} | ✅ |
| **ArtifactHiddenForceTable**(4) | 잠재 슬롯(등급별 MaxSlotCount·SlotRequiredAwake) | 구조 |
| **ArtifactPowerTable**(2) | 파워 StatData | ✅ |
| **ArtifactLevelStatFactorTable**(1001) | 성장 factor(Level→Factor) | ✅ |
| **ArtifactTotalAwakeTable**(201) | 각성총합→ArtifactPowerMaxLevel | 성장상한 |
| **SkillDescParamTable**(3433) | ★op·위치별 표시배수: {SkillIndex,ParamNo,Calculate:[Div,N],Step2:[Operations,opNo],Step3:[Values,위치]} | ✅배수 |
| Slot·Upgrade·AwakeCost·PowerLevelUpCost·DefuseReward | 비용/슬롯/보상 | ❌무관 |

## 2. 장착효과(EquipSkill) op 전종 — ★실측 확정 (전32유물×6각 EquipSkill)
- **op.type 분포**: ModStat 192(%스탯)·UsePassiveSkill 48(연계/중첩)·ModDamagePipelineStat 30(조건부딜)·State 30·ModStatR 24(기저%배율)·GetHealByMaxHpR 12·ClearState 12·CreateProjectile 12·GetMpR 6·GetMotR 6·Cleanse 6. **전부 스킬 IR op 카테고리로 커버**(신규 op 없음).
- **ModStat 대상 스탯 분포**: FinalDamage 78(최다)·Attack 18·HitChance 12·CriticalChance 12·CriticalPower 12·HpPotionCoolTimeReduceRatio 12·Weakness 12·AttackPowerToBoss 12·BuffDurationRatio 6·AttackPower(데미지) 6·SupporterDurationRatio 6·DebuffResist 6·AttackSpeed 6·MaxDamageRatio 6·CoolTimeReduceRatio 6·HpRecoverPower 6. 스탯→공식항은 [[스킬 스탯매핑=_clt2_ENGINE_SPEC.md §1]] 동일(÷10 스케일, ModStatR=기저%배율).
- **EquipSkill TriggerType 분포**: OnStart 132(상시=선반영 후보)·OnAttack 12·OnWaveStart 12·OnTimer 12·OnKill 6·OnDamage 6·OnState 6·OnSpawnSupporterSkill 6(=계산대상 발동). ★§4 선반영/계산 판정 근거.
- op 계산동작=스킬과 동일. UsePassiveSkill=자식스킬 연계(스택 중첩·뿔피리 상태해제) → §8-①.
- **★ModDamagePipelineStat 30건 = "조건부 딜 스탯"(직접딜 아니나 딜 직접 영향, 계산대상)**: 구조 `Values=[0, 0, 스탯종류, 값/동적식]`. **네번째 인자가 `$동적식` 또는 `#함수`**라 IR이 파싱·원본보존 필수(스킬 condTree/func 파서 재활용):
  - `$CreatureIsBoss(TargetCreature)*80` (3_4): 보스 대상이면 크확+8% (동적, 대상조건×계수)
  - `#Artifact_MushmomHead(200)` (4_4 머쉬맘갓): 회피초과명중 데미지 함수
  - `#IfShield(600;0)`·`#IfDebuff(80;0)`·`#IfBuffExceptShield(40;0)` (5_13): 실드/디버프/버프 상태별 최종뎀(참값;거짓값). 예 실드시 최종뎀 +60%.
  - ★유물 전용 #함수(IfShield/IfDebuff/IfBuffExceptShield/MushmomHead)의 인자 규칙(참;거짓 or 계수)은 IR 구현 시 확정. IR엔 func{name,args} 원본 보존, 해석은 엔진.

## 3. 값 배수·성장·발동시간 (★스킬과 공통 원칙, raw 보존)
- **배수**: IR엔 **원본 raw 정수 보존**(변환 금지). 표시%/계산배수는 **SkillDescParamTable.Calculate=[Div,N]**로 엔진이 op·Value위치별 적용(AttackSpeed÷10·FinalDamage÷1 등 스탯/op마다 다름).
- **성장(★실측)**: 성장하는 건 **보유효과(StatData.Factor=9/10)·파워(Factor=1)뿐**. Factor=ArtifactLevelStatFactorTable **열 인덱스**, Level=유물 파워레벨(0~ArtifactPowerMaxLevel). **장착효과(EquipSkill)는 op에 Factor 없음 = 파워레벨 성장 안 하고 오직 각성으로만 값 변경. 잠재는 Factor 없음 = 고정값.** ※스킬 SkillLevelFactorTable과 별개 테이블.
- **발동주기·지속(★실측)**: 조건부 유물의 **발동주기=노드 TriggerTimeMs**(OnTimer, 예 4_2=20000ms), **버프 지속=op.DurationMs**(예 4_2 ModStatR DurationMs=30000ms=30초). State Dur이 아님. `99999999`=무한(상시). §5-4 상시화 판정(지속≥주기)의 데이터 출처. IR이 trigger.periodMs + op.durationMs로 이미 담음.

## 4. 선반영 vs 계산대상 (★핵심, 이중계산 방지) 〔계산 명세 — 엔진 참고, IR 미포함〕
- ★유물은 전부 **스탯 기여**(직접딜 0). 문제는 "그 스탯을 유저 스탯창이 이미 포함했나"뿐.
- **선반영(계산 안 함, 유저 프리셋 스탯창 포함)**: 항상 발동(TriggerType=OnStart 132건) 장착효과·보유효과·잠재옵션·파워. → 엔진 미적용.
- **계산 대상(엔진 계산)**: **조건/트리거 발동 유물만**(스탯창 미반영, TriggerType=OnTimer/OnAttack/OnKill/OnDamage/OnState/OnWaveStart/OnSpawnSupporterSkill). 가동률=지속(op.DurationMs) vs 주기(TriggerTimeMs)로 산출. + 예외: 공격력 유물(감정의물병 Attack%)=선반영 아님→공퍼버킷 실시간합산(used:true, 물병 최종뎀 파생 공속60초과분은 선반영).

## 5. 계산 규칙 (전수감사 확정) 〔계산 명세 — 엔진 참고, IR 미포함〕
0. **컨텐츠 탭 모델**: 계산 대상 컨텐츠는 **탭 단위**(현재 생성=[챕터 사냥]·[챕터 보스] 2종, 추후 확장 가능). "챕터 사냥"은 **전역이 아니라 하나의 탭**. 유물별로 각 탭에서 발동하는지가 계산 입력.
1. **컨텐츠 한정 발동**: [성장던전]·[레이드]·[월드보스]·[아레나]·[길드토벌전] 조건 유물 = 챕터 사냥/보스 탭에서 미발동(0). [챕터 사냥] 한정 유물(영혼의계약서 등)은 **챕터 사냥 탭에서만** 적용(챕터 보스 탭에선 0). 즉 각 유물의 발동 조건($CheckDungeonFlag 등)을 탭별로 판정.
2. **다중첩=선형**: 육각수정목걸이 DMG×(1+X%×3중첩)(5각×1.9)·화염초 perEnemy 최종뎀 선형(5각 일반10명+20%/보스+2%).
3. **조건부=기본 최대치 가정**: 머쉬맘갓/저주인형(회피초과명중 최대)·천도나무명약(딜최대)·세이람(대상별 일/보뎀 자동). **불타는용암 디버프판정**: 버프케이스 바닥(X), 디버프케이스(2X)는 내직업/소환동료가 실제 적 디버프 줄때만.
4. **시간/트리거 버프=재발동시 지속리셋**(은펜던트"개별적용"만 예외). 지속≥발동주기면 상시100%. 육각목걸이(20초마다30초)=상시3중첩·죽은자부적(10초마다5초)=50%·양초(0~30초최종뎀). **onKill 트리거(성배)**: 일반몹만(보스전0), 처치율=분당110마리 가정, 가동률=1−e^(−λ·dur), λ=(110/60)×확률(성배 2%·dur30→약67%).
5. 뿔피리=보스전 플레이어자신만2배(동료그대로)·동맹의증표=솔로본인적용(멀티중첩무시).

## 6. 시뮬식 vs 해석식 (스킬/동료 공통 원칙) 〔계산 명세 — 엔진 참고, IR 미포함〕
- **시뮬식**: 단일 타임라인(전투70/90초)에서 플레이어스킬+동료+유물 함께진행 → 상호작용(유물발동중 동료소환 계승·불타는용암 디버프창·성배 처치) 일관처리.
- **해석식**: 각 성분 가동률 곱 효율근사(디버프 가동률로 X~2X 시간가중).
- **동료 계승 전파**: 유물버프(육각목걸이 DMG×1.9 등)를 fullBuffStats 스냅샷에 포함 → 발동상태서 동료소환시 계승 반영.

## 7. 유물 IR 스키마 (Stage 산출) — ★확정 〔순수 데이터, 정책 태그 없음〕
```
artifact_node {
  index, code(ARTIFACT_g_n), grade(epic/unique/legend), name,   # name=Localization_ko 복호화
  equipStages: [ {                                    # EquipSkillIndices 6개(각성 0~5)
    awaken:0~5, skillIndex, cooldownMs, targetType,            # 대상(Myself/TriggerTarget), 발동쿨
    trigger:{type, ratio, periodMs,
             condition(TriggerCondition파싱)},                 # ★컨텐츠/디버프 발동게이트(자쿰레이드·성장던전·Campaign·Debuff)
    ops:[ 원본op raw(정수보존) + durationMs + condTree(EnableCondition) + func(#) + dyn($동적식) + grantState ],
    meta:{ignoreCc, targetCount}
  } ],
  collectionOptions: [ {type, value, factor} ],       # ArtifactOptionTable (보유)
  hiddenForce: [ {slotIndex, grade, type, value, prob} ],  # 잠재(확률줄, prob=기댓값용)
  power: [ {type, value} ],                            # ArtifactPowerTable
}
전역:
  states: {StateName: {maxStack, buff, debuff, cc, statType}}  # 유물 grant State StateTable 조인(스택수)
  growth: ArtifactLevelStatFactorTable(Level→Factor/1000)
  awakePower: ArtifactTotalAwakeTable(각성총합→PowerMaxLv)
  systemMeta: {slot, awakeCost, upgrade, powerCost, defuse}    # §9 참조용(딜무관)
```
- ★**IR엔 정책 태그(calcClass·선반영여부·가동률) 안 넣음** — 데이터-정책 분리(스킬 IR 원칙). 선반영판정/계산분류/컨텐츠매핑은 **엔진 구현부의 별도 정책 맵**(§4·§5 명세를 코드로)에서 처리. IR은 판정 근거 데이터(trigger.type·condTree·durationMs·periodMs)만 제공.
- **커버리지 게이트**: ArtifactTable 32 × EquipSkill 6각 op 전부 IR 반영(op·필드 1:1, 미반영=실패). op 카테고리는 스킬 게이트(_clt2_gate.py) 재활용(신규 op 0). 표시배수=SkillDescParamTable 조인.
- ★**노드필드 실제반영 검증**(게이트 맹점 보강): 원본 노드필드가 "분류됐나"가 아니라 **IR에 실제로 담겼나**를 대조(`_clt2_artifact_ir_profile.py` 파트2·3). 이 검증으로 TriggerCondition·SkillTargetType 누락 발견·수정. 유물·스킬 노드필드 100% 반영 확인.

## 8. ★미확정 5개 — 실측 규명 완료 (설계 확정)
1. **UsePassiveSkill 연계/스택**: 중첩 = **State_N 분해 슬롯 패턴**(스킬 IR과 동일). 각 유물 State(Artifact_g_n_k)는 전부 `MaxStack=1 / StackRule=Override` → **서로 다른 State 슬롯 개수 = 최대 중첩수**. 예 5_12=State _2,_3 2슬롯(CriticalPower 200×2)·5_14=ExcludeBoss/ToBoss 각 슬롯. **StateTable 조인으로 자동 규명**(MaxStack·Buff/Debuff·StatType). → IR은 grant State명 보존, 엔진이 StateTable 조인.
2. **각성 슬롯/PowerMaxLevel**: (a)잠재슬롯=ArtifactHiddenForceTable.SlotRequiredAwake(등급별 확정 §0). (b)ArtifactPowerMaxLevel=ArtifactTotalAwakeTable(**전 유물 각성총합 0~200 → 파워최대레벨 30~**). (c)장착슬롯 해금=ArtifactSlotTable(SlotReqMissionIndex 미션). → 시스템 메타 §9.
3. **잠재 확률(Prob)**: ArtifactHiddenForceOptionTable = {GradeType, SlotIndex(0/1/2), StatData, **Prob**}. 등급×슬롯별 스탯 티어 출현확률(레전=[90000,60000,40000,14700,1500,500] 6티어). 잠재 스탯 12종(MainStatR·DefenceR·Toughness·HitChance·CriticalChance·Min/MaxDamageRatio·To/ExcludeBoss·InCc·AttackPower·PiercePower). **선반영이라 계산제외**, IR엔 raw+Prob 보존(기댓값/옵션비교용).
4. **컨텐츠 분류 자동화**: **부분 자동**. op EnableCondition의 `$CheckDungeonFlag(IsCampaignBoss)` → 보스/일반 자동판정. `$CreatureStateCount(...)==0/>0` → 스택조건. 단 [성장던전]·[레이드]·[아레나] 등 세부 컨텐츠한정은 **op에 없음**(넥슨 표시텍스트/보유효과 기반) → 계산규칙 §5-1([[mekicalc-artifact-spec]] 전수감사) 수동유지. IR엔 calcClass를 감사결과로 태깅.
5. **이름 매핑 — ★복호화 완료**: ArtifactTable.NameLocalKey(ARTIFACT_NAME_g_n) → Localization_ko AES복호화([[mekicalc-localization-decrypt]], `_clt_decrypt.py` 재사용)로 **32종 전부 실제 한글명 확보**(→ `dev/_clt2_artifact_names.json`). 유물 **설명 텍스트는 로컬라이징에 없음**(효과 설명은 게임이 스탯/op에서 동적 생성) → IR엔 **이름만** 붙임(스킬과 달리 desc 없음). §5 규칙↔코드 교차검증 완료(4_2 육각목걸이·5_1 성배·5_13 불타는용암·4_4 머쉬맘갓·5_20 저주인형·3_1 죽은자부적·5_18 동맹의증표 등 일치). 프리셋 키(hexcrystal_necklace 등)↔코드는 이름으로 자동 연결.

## 9. 시스템 메타 테이블 (딜 무관, IR 참조용 보존 — "클뜯 대체 정리본" 목적)
| 테이블 | 내용 | IR 활용 |
|---|---|---|
| ArtifactSlotTable(1) | 장착슬롯 해금 미션(230~234) | 슬롯 개수 |
| ArtifactTotalAwakeTable(201) | 각성총합(0~200)→ArtifactPowerMaxLevel(30~) | 파워 성장상한 |
| ArtifactAwakeCostTable(36) | 등급×각성단계별 RequireCount(재료수) | 각성 비용 |
| ArtifactUpgradeTable(2) | 승급 개수(UpgradeRequiredCount, 유일 소스). 에픽→유니크 20개·유니크→레전 15개 **(클뜯 원본 그대로)**. ※게임 실측은 유니크→레전 20(패치로 데이터가 구버전인 듯)이나 IR은 원본 보존, 실측 조정은 엔진/표시 레이어 몫. RewardIndex=승급 후 랜덤유물 풀(개수 무관) | 등급 승급 |
| ArtifactPowerLevelUpCostTable(900) | 파워레벨업 STONE_ARTIFACT 비용(레벨당) | 파워 비용 |
| ArtifactDefuseRewardTable(4) | 분해 보상(등급별 STONE_ARTIFACT) | 분해 |
| ArtifactHiddenForceTable.ChangeCosts | 잠재 재설정 비용(Gold+STONE_ARTIFACT, WithCache 1.5배) | 잠재 변경 |
- **성장**: ArtifactLevelStatFactorTable(1001행, Level 0~1000 → Factor 배열/1000). 유물 파워레벨(ArtifactPowerMaxLevel 상한)로 스탯 스케일. ArtifactPowerTable(2행: MainStat100·MaxHp1000=파워 기본효과, 선반영).
