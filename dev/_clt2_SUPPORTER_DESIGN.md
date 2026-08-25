# clt2 동료(Supporter) 설계도 — 딜 기여 소스 3/3 (스킬·유물 IR과 별도)

동료의 모든 구조·값·관계·계산규칙을 순수 클뜯 데이터로 전수 규명(기존 companions.json은 12동료만 정답지로 교차검증, 신규 2종은 배제하고 독립 규명). 규칙 원천=[[mekicalc-companion-model]](딜 모델 인게임 검증)+[[mekicalc-skill-reversing]], 데이터=textAsset 클뜯. 스킬 IR과 op 파서 공유(신규 op 0), 인덱스는 80대역.

> ★핵심 성격: **동료는 "소환되어 스스로 딜/버프하는 개체"**다. 유물(스탯 기여)과 달리 **자체 딜 스킬을 가진 소환수**이며, 플레이어 스탯을 **계승**받아 싸운다. 스킬 구조는 유물(6칸 고정)보다 복잡한 **명시 목록 + 다단계 재귀 트리**.

> ★★계층 분리(스킬·유물 IR 원칙 계승):
> - **IR 데이터 스펙(§0~4·§6·§7)**: 클뜯 충실 보존. SupporterTable 명시 스킬목록·개방레벨·계승factor·op raw·재귀트리·로컬라이징. Stage가 산출.
> - **계산 명세(§5)**: 소환 가동률·발동빈도·계승 적용·bossOnly 등 **정책**. IR에 넣지 않고 엔진이 구현. 사람이 감사.
> - **엔진(코드)**: 명세 실행. 추후 제작.

## 0. 동료 구조 (61종) — ★실측 확정
- **61 = 14클래스 × 등급**. Grade1(Cadet 지망생) 5종(대표직업만) + Grade2~5 각 14종 = 5 + 56.
- **14클래스(직업)**: 01 Hero·02 Paladin·03 DarkKnight·04 ArchMageIL(썬콜)·05 ArchMageFP(불독)·06 Bishop·07 BowMaster·08 Marksman·09 NightLord·10 Shadower·11 Viper·12 Captain·13 NightWalker·14 WindBreaker.
- **등급(=차수)**: Grade1(Cadet)·Grade2(RARE=1차)·Grade3(EPIC=2차)·Grade4(UNIQUE=3차)·Grade5(LEGENDARY=4차). ⚠️Bishop·Bulldog은 RARE 없이 EPIC부터.
- **코드/인덱스 규칙(클뜯 역산)**:
  - SupporterCode `SUPPORTER_{class}_{gradeStep}` (gradeStep 0~4).
  - CreatureIndex `9{gradeStep}{class:02d}` (Hero LEG=9401).
  - 동료 스킬 `8{class:02d}{grade}{slot:02d}` (grade 1~4=RARE~LEG, class01~14). 800000=공통.

## 1. 데이터 소스 (테이블)
| 테이블 | 역할 | 딜 |
|---|---|---|
| **SupporterTable**(61) | 마스터: class/grade·CreatureIndex·**EnterSkillIndex·MainActiveSkillIndices·MainPassiveSkillIndices·OpenActive/PassiveSkillLevel**·CollectionOption/EquipOptionIndices·**LinkedAttackStatBaseRatio/LevelFactor**·SpawnSkillIndex/DurationTimeMs·NameLocalKey | ✅ |
| **SkillTable**(80대역 504) | 동료 스킬 op(스킬 IR과 동일 구조). 이름키 없음(무명), 설명=SKILL_DESC_{idx} | ✅ |
| **SkillProjectileTable** | 소환 투사체(지속/틱/대상수/딜스킬), 재귀 딜 | ✅ |
| **SupporterLevelStatFactorTable**(300) | 계승 factor(Level→Factor[8~12열]) | ✅계승 |
| **SupporterOptionTable**(183) | Collection/Equip 옵션 StatData(계정 전역 스탯) | ✅ |
| **CreatureTable** | 동료 크리처(BaseAttack 50%·공통패시브 9·11020) | 평타 |
| **SkillDescParamTable** | 스킬 설명 {N}→계수 치환 | 표시 |
| SupporterLevelUpCost·SlotCondition·LevelGacha | 비용/슬롯/가챠 | ❌무관 |

## 2. 스킬 구조 (★SupporterTable 명시 — 인덱스 추정 불필요)
동료 스킬은 **SupporterTable에 명시**돼 있다(slot 위치 규칙 아님):
- **EnterSkillIndex**: **소환 순간 1회 발동 광역딜**(일반딜 + 보스3배딜). 예 nightLord LEG 809400 = 800%4타8명 + 2400%(×3). 개방레벨 없음(항상).
- **MainActiveSkillIndices + OpenActiveSkillLevel**: 액티브 스킬 + 개방레벨(1:1 매칭).
  - **[0]번은 항상 평타**(TriggerFlag=ReplaceBaseAttack, cd없음). 전 56동료 100% 일관. 등급별 계수=gradeBasicAttack(RARE26%2타·EPIC40%3타·UNIQUE80%5타·LEG290%5타). companions는 이걸 전역 처리.
  - [1:]번은 쿨 있는 액티브 딜(예 809420 cd11s).
- **MainPassiveSkillIndices + OpenPassiveSkillLevel**: 패시브(버프·디버프·편승딜). 개방레벨 1:1.
  - UNIQUE/LEG 공통 최종뎀(개방lv 5/8/10의 별도 3스킬 FinalDamage 25%씩)=companions `uniqueLegendaryFinalDmg` 전역.
- **평타(Cadet)**: Grade1은 MainActive 없음 → CreatureTable **BaseAttack**(50% 단타) 사용.
- **개방레벨**: 스킬은 동료 **레벨 10에 전부 개방**(최대 개방레벨 10). 11~16레벨은 스킬 개방 없음(옵션만 성장).
- **스킬 수(Enter+Active+Passive)**: RARE 3 · EPIC 5 · UNIQUE 10(11) · LEG 12(13). (11·13=Shadower·Captain).

### 2.1 재귀 트리 (★딜 완전성 — 자식 42개)
- 명시 스킬이 **CreateProjectile/UseSkill/UsePassiveSkill/UseSkillToTarget**로 자식 스킬 호출 → 자식이 실제 딜.
- **다단계 재귀 depth 3**(자식의 자식의 자식). 예 캡틴 812320>812361>812362>812363.
- **자식전용 딜 42개**: 명시 목록엔 없고 재귀로만 나오는 딜(예 다크나이트 803462 290%6타, 불독 804441). **재귀 안 하면 통째 누락**([[mekicalc-companion-model]] 비홀더·폭발딜 교훈).
- 딜 노드 총 181 = 명시루트 139 + 자식전용 42.

## 3. 계승 (LinkedAttackStat) — ★공식 검증 완료
- **계승비율(%) = SupporterLevelStatFactorTable.Factor[levelFactor][동료레벨] / 10**.
- levelFactor(등급별 열): NORMAL 8·RARE 9·EPIC 10·UNIQUE 11·LEG 12 (SupporterTable.LinkedAttackStatLevelFactor). BaseRatio=1000(공통).
- **90% 캡**: NORMAL L100·RARE L50·EPIC L30·UNIQUE L10·LEG L10 도달. 상위등급일수록 빨리 90%.
- **공격력만 이 비율로 계승**, 나머지(크리·데미지·최종뎀·보스뎀·데미지범위)는 **풀 계승**([[mekicalc-companion-model]] 인게임 검증). compareLevel 보정(플레이어 레벨 미달 시 스케일).

## 4. 옵션 (Collection/Equip) — 계정 전역 스탯
- **CollectionOptionIndices**(2개, 보유효과) + **EquipOptionIndices**(1개, 장착효과) → SupporterOptionTable StatData{Type,Value,Factor}.
- 스탯: Attack·MaxHp·AttackPower(데미지)·MaxHpR·MainStat·ToBoss·MaxDamageRatio·SkillPower·CriticalChance/Power 등. Factor로 동료레벨 성장.
- ★**계정 전역 스탯**(유저 스탯창에 이미 반영) → 소환딜 계산과 **분리**(이중계산 방지). 소환딜은 계승만.

## 5. 계산 명세 (엔진 참고, IR 미포함) 〔정책〕
- **소환 모델**: SpawnSkillIndex=5(SpawnSupportCreature) → CreatureIndex 크리처 소환, **SpawnDurationTimeMs=30초** 지속. 소환 주기 vs 지속으로 가동률.
- **발동빈도**: EnterSkill=소환 순간 1회. 평타(MainActive[0])=공속. 액티브[1:]=cd 주기. 패시브=상시/편승(OnAttack/OnTimer). 자식=부모 발동 편승.
- **계승 적용**: 공격력=계승비율×플레이어공격력, 나머지 풀계승. 발동 상태 유물버프도 계승(fullBuffStats 스냅샷).
- **조건**: bossOnly(보스전용 딜/디버프), TriggerCondition/EnableCondition(State·컨텐츠). CC(Stun/Frozen)·힐·실드=딜무관.
- **시뮬 vs 해석식**: 스킬·유물과 공통(단일 타임라인 상호작용 vs 가동률 곱 근사). [[mekicalc-companion-model]] 검증 로직.

## 6. 로컬라이징 — ★복호화 확인
- **동료 이름**: NameLocalKey `NAME_SUPPORTER_{class}_{grade}` → "**직업명(차수)**"(예 "나이트로드(4차)"·"히어로(1차)"). 61종 누락 0.
- **동료 설명**: DescLocalKey=`DESC_SUPPORTER_COMMON`(공통), Summary도 공통. 동료별 고유 설명 없음.
- **스킬 설명**: `SKILL_DESC_{index}`(421개), {N}=계수/타수/대상수 파라미터 → SkillDescParamTable 치환(스킬·유물과 동일). 미커버 0.
- **스킬 이름(한글)**: **없음**(SKILL_NAME_80대역 0개, SkillNameLocalKey None). 동료 스킬은 UI 무명(유물과 동일) → IR엔 설명만.
- **ActionName(영문 코드명)**: 139/504 스킬에 존재(StarDust·QuintupleThrow·SuddenRaid·PinPointPierce 등 애니메이션 액션명). 한글 표기명 아닌 **개발용 영문 식별자**(일부는 숫자=애니 인덱스). 스킬 식별·구분에 유용 → IR meta.actionName에 참고 보존(스킬 IR과 동일).

## 7. 동료 IR 스키마 (Stage 산출) — 순수 데이터
```
supporter_node {
  index, code, class(01~14), grade(cadet/rare/epic/unique/legend), name("직업명(차수)"),
  creatureIndex(9{g}{c}), spawnDurationMs,
  inherit: {baseRatio:1000, levelFactor:8~12},          # §3 계승
  basicAttack: {skillIndex, ops, replaceBaseAttack}       # MainActive[0] / Cadet은 BaseAttack
  enterSkill: {skillIndex, ops, summon, desc},            # 소환딜(§2)
  activeSkills:  [{skillIndex, openLevel, cd, trigger, ops+desc, children[재귀]}],
  passiveSkills: [{skillIndex, openLevel, trigger, ops+desc, children[재귀]}],
  collectionOptions: [{type,value,factor}], equipOptions: [{type,value,factor}]  # §4 계정전역
}
전역:
  inheritFactor: SupporterLevelStatFactorTable(Level→Factor[8~12]/10)  # 계승
  optionFactor:  옵션 성장(Factor 열)
  states: {StateName: StateTable 조인(maxStack,buff,debuff,cc,statType)}
```
- **op 파서**: 스킬 IR `ir_op` 100% 재활용(신규 op 0, SpawnSupportCreature만 추가). condTree/dyn/func/durationMs 동일.
- **재귀**: children = CreateProjectile→ProjectileTable.SkillIndex + UseSkill류(depth 3까지).
- **커버리지 게이트**: 61동료 × (Enter+MainActive+MainPassive+재귀자식) op 전부 1:1. desc {N}=SkillDescParamTable 조인. State=StateTable 조인.

## 8. ★규명 완료 (착시 전부 제거)
1. **slot 위치 추정 → SupporterTable 명시**(Enter/MainActive/MainPassive). 위치로 정체 규정 불가, 명시 목록이 정답.
2. **스킬 개수**: 자식·평타·공통 혼동 과대셈 → Enter+Active+Passive(RARE3/EPIC5/UNI10/LEG12).
3. **동일효과 연번**: 병합 오판 → 개방레벨 다른 별도 스킬(FD 5/8/10=누적 75%).
4. **평타 이중**: MainActive[0]=ReplaceBaseAttack(진짜 평타), CreatureTable BaseAttack 50%=Cadet용/대체됨.
5. **신규 2종**: companions 값은 지난 세션 AI 추출(정답 아님) → SupporterTable 명시로 독립 규명(81[34] 71스킬).
6. **최후 검증 6종 전부 0건**: op커버리지·desc파라미터·State무결성·인덱스규칙·61동료필드·투사체조인.

## 9. 시스템 메타 (딜 무관, 참조)
SupporterLevelUpCostTable(레벨업 비용)·SupporterSlotConditionTable(장착슬롯 미션)·SupporterLevelGacha/RewardTable(가챠). IR 참조용 보존.
