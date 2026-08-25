# clt2 엔진 계산 명세 — IR → DPS 동작 규명 (엔진 제작 청사진)

IR(data/skills_ir/*.json)의 각 구조·값·관계가 실제로 어떻게 딜로 계산되는가. 넥슨 공식([[mekicalc-official-formula]]) + 계산규칙([[mekicalc-damage-formula-rules]]) + clt 데이터 규명 종합.

## 0. 넥슨 기본 전투 공식 (1000분율, 1000=100%)
```
피해 = 공격력                                    # Attack (합+배율: 깡합→%배율→스킬%→스킬깡)
     × 5000/(6000 + 방어력×(1-방관%))            # 방어배수
     × 받피증(Weakness) × 받피감 × 받피조정
     × (1 + 데미지)(1 + 데미지증폭)              # AttackPower, AttackPower2 (%합산)
     × (1 + 보스/일반몹뎀)                        # AttackPowerToBoss / ExcludeBoss
     × (1 + 평타뎀/스킬뎀)                        # SkillPower 등
     × (1 + 스탯비례) × 마스터리 × 기타
     × 크뎀                                       # 1+min(크확,1)×크뎀
     × 최소~최대배율                              # (최소+최대)/200
     × (1 + 상태이상)(대상해당)                   # AttackPowerInCc
     × 최종뎀(FinalDamage, 효과별 곱연산)
     × 콘텐츠보정 × 스킬계수(맨 마지막)           # ← GetDamageR 계수
```

## 1. ★clt 스탯명 → 공식 항 (로컬라이징 복호화 확정, 스케일 clt÷10=%)
| clt 스탯 | 진짜 의미 | 공식 항 | 합산 |
|---|---|---|---|
| Attack | 공격력(깡) | 공격력 | 합+배율(곱) |
| AttackPower | **데미지**(공격력 아님!) | (1+데미지) | %합산 |
| AttackPower2 | 데미지증폭 | (1+증폭) | %합산 |
| BaseAttackPower | 기본공격데미지 | 데미지류 | %합산 |
| SkillPower | 스킬데미지 | (1+스킬뎀) | %합산 |
| FinalDamage | 최종뎀 | 최종뎀 | **효과별 곱** |
| CriticalChance/Power | 크확/크뎀 | 크리티컬 | 크확 min100%cap |
| Weakness | 받는피해증가(적 디버프) | 받피증 | %합산 |
| AttackPowerToBoss/ExcludeBoss | 보스/일반몹뎀 | (1+보/일) | %합산 |
| AttackPowerInCc | 상태이상 데미지 | (1+상태이상) | 대상 CC시만 |
| MinDamageRatio/MaxDamageRatio | 최소/최대 | (최소+최대)/200 | 체감 |

## 2. op별 계산 동작
### 딜 생성
- **GetDamageR [계수, None/Dot, 타수]**: 스킬계수=계수(천분율). 딜=공격력×배율들×계수%×타수×대상수×가동률. Dot이면 도트(지속). R=공격력비율.
- **GetDotR [계수]**: 도트 딜(지속시간 동안 틱마다). 계수%.
- **GetDamage [#함수]**: 함수형(체력비례 등, #파싱).
- **GetDamageByMaxHpR [계수, TrueDamage, 타수]**: 체력비례 고정딜(TrueDamage=방어무시).
### 스탯 변경 (버프/디버프) — 값 clt÷10=%
- **ModStatR [스탯, 값]** (R=Ratio, 값÷10=%): **기저 스탯의 % 배율 증가** — 공격력(Attack)·방어력(Defence)·HP·주스탯. `base×(1+Σ%)`. ★확정: 아이언바디 ModStatR Defence 150="방어력 15%증가". Attack은 항상 ModStatR(공격력 %).
- **ModStat [스탯, 값]** (값÷10=%): **%스탯의 값** — 데미지(AttackPower)·최종뎀(FinalDamage)·받피(Weakness)·보/일몹뎀·크확 등. %합산(최종뎀만 각각곱). 절대스탯 포인트(Defence -9000=몹 방깎)도.
- ★즉 ModStat vs ModStatR = **대상 스탯 종류 차이**(넥슨 합+배율스탯=ModStatR·%스탯=ModStat), 스케일 ÷10 공통. 메모리 계산규칙④와 일치.
- **ModStatOnTick [스탯,값,틱]**: 틱마다 스탯.
- **ModDamagePipelineStat [0,0,스탯,값/$]**: 조건부 데미지 파이프라인(조건 만족시 스탯, $동적).
### 스킬 강화 (boosts_in) — [대상스킬, opNo, 스탯/필드, 값]
- **ModSkillStat**: 대상 스킬의 특정 스탯 강화(FinalDamage 등). 대상 딜에 곱/합.
- **ModSkillR**: 대상 스킬 비율 강화(SkillCoolTimeMs 등).
- **ModSkill**: 대상 스킬 속성 변경(지속/틱 등).
### 상태
- **State**: 상태 부여(state_defs 참조). **ClearState**: 소비. **ModCurrent/MaxStateStack**: 스택.
### 소환 — summon{projDuration, projTick, projMaxTarget, dealSkill}
- 소환딜 = dealSkill계수 × (projDuration/projTick) × projMaxTarget × 가동률. 가동률=min(1, projDuration/발동주기).
### 기타(딜무관 or 간접): 회복/실드/자원/CC/이동/쿨타임/연출 — 딜계산 스킵(단 쿨타임=발동빈도·CC=AttackPowerInCc조건).

## 3. 발동·빈도 (trigger.type)
- **None**: 직접시전(쿨타임 주기 = cooldownMs, 쿨감 적용). 가동=1/cd.
- **OnStart**: 상시패시브(가동 1.0, 선반영 후보).
- **OnTimer [periodMs]**: 주기발동(fastByAtkSpeed면 공속비례).
- **OnAttack/OnHit/OnTryAttack/OnDamage**: 공격/피격 편승 → 빈도=상위 공격횟수×procPct(ProcGate).
- **OnActiveSkill**: 액티브 시전편승. **OnState**: 상태진입. **OnEnemyDie/OnWaveStart**: 이벤트.
- **확률(trigger.ratio<1000)**: ProcGate(시드 베르누이+icd), 정적기댓값 min(1,freq×dur) 지양.

## 3.5 노드 부가 필드 작동 (★버려졌다 복구, 전부 딜 관련)
- **replaceBaseAttack** (TriggerFlag=ReplaceBaseAttack, 56): 이 스킬이 **평타를 대체** → 평타 자리(공속마다) 발동. 아크메이지 [기본공격효과] 스킬(콜드빔·플레임오브 등)이 실제 평타 = 딜 큼. 발동빈도=공속.
- **attrFlag** (Fire/Ice/Poison/Lightning): 스킬 속성 → **ModAttrDamagePipelineStat 속성증폭 매칭**(Lightning딜=대상Ice스택×60, Fire딜=독스택×60). 아크메이지 속성 시너지.
- **trigger.periodMs** (TriggerTimeMs): OnTimer 발동주기(43040=300ms). 발동빈도=1/period. fastByAtkSpeed면 공속비례.
- **initialCoolMs** (InitialMinCoolTimeMs): 전투시작 후 **첫 발동까지 쿨**(이후 cooldownMs). 단기전 영향.
- **autoTriggerCondition** (condTree): 조건 만족시 자동발동(비홀더 $ProjectileCount==0=소환 유지 재소환).
- **op.modStats** [{StatType,StatValue}]: **그 딜 op 한정 부가스탯**(14020 GetDamageR+AttackPowerToBoss 1000=이 딜만 보스뎀+100%). ★대부분 StatValue 0(무의미)이나 일부 딜 결정.
- op.opTickMs(도트 주기)·addStateCount(스택 추가)·fireRatio(발사비율).
- **meta**(딜 무관, 참조): actionName·iconPath·maxRangeMm·rangeType·triggerFlag·ignoreCc.

## 3.6 시전 시간 (★DPS 발동빈도 직접, 클뜯 부분적)
스킬 발동주기 = **max(cooldownMs, 시전시간)**. 시전시간 소스:
- **op.queueDelayMs** (37개): 명시적 시전시간(다음 액션 제약). Flag=**SyncQueueDelayBySkillSpeed면 공속(AttackSpeed)으로 단축**. 84040/124020/143030 등.
- **평타/평타대체(ReplaceBaseAttack)**: queueDelayMs 없음 → **공속 스탯이 평타 속도**(SkillTable 밖, 게임 기본 평타주기÷공속).
- **나머지**: Spine 애니(asset, 클뜯 json 없음) → 즉발 가정 or 실측.
- ★op.delayMs는 op 내 딜 발동지연(시전과 별개, 시뮬 세부). meta.
- ⚠️ 완전한 시전시간은 클뜯 불완전 → 발동빈도 정밀은 엔진(공속+queueDelay)+실측 필요.

## 4. 성장 (op.growthSlot)
계수/값 = base × SkillLevelFactorTable.Factor[growthSlot][스킬레벨]/1000. slot 12/21/22=선형(0.5/0.4/0.3%), 23=메이플용사특수. 마스터리=성장 안 따름(고정).

## 5. 조건 (op.condTree) — 발동/분기 게이팅
$함수 조건 트리 평가: CreatureStateCount(스택), CreatureIsBoss(보스분기 bossExtra), CreatureProjectileCount(결계가동), 대상수 등. 참일때만 그 op 적용. State 분기(씨서펜트 AssaultMode)=op별 condTree.

## 6. 합성 순서
①깡스탯 합 → ②%배율(공격력=곱) → ③버프(조건부=엔진, state_defs 가동률×) → ④강화(boosts_in 곱/합) → ⑤마스터리(고정) → ⑥공식 곱구조 → ⑦스킬계수 → ⑧대상수×가동률 → DPS.

## 7. 선반영 vs 엔진반영 자동판정 (★규명완료)
- **선반영**(프리셋 스탯창 포함, 엔진 미적용): `TriggerType=OnStart` AND condTree없음 AND state없음 AND 조건flag(Disabled/WatchCondition/ExpireOnConditionFail)없음 AND 확률=1000. 166건.
- **엔진반영**(딜계산시 가동률× 적용): 조건/발동(OnAttack등)/확률/조건플래그/액티브(None)/주기(OnTimer). 129+9건.
- **예외**: Attack(공퍼 SKILL_ATK_PCT)·주스탯%(MAIN_STAT_PCT)는 상시라도 스탯창 미포함 → 엔진유지. 힐마스터리8%도 입력미포함→엔진.

## ★데이터로 규명완료 (엔진 청사진 확정)
op동작·스탯→공식항·성장(LevelFactor)·대상수(op.mh>tc>area)·소환구조(ProjTable)·조건파싱(condTree)·ModStat/R(기저%배율 vs %스탯값)·선반영판정. 정적 데이터 규명은 여기까지가 완전.

## ★엔진 시뮬(타임라인)로만 규명 가능 — 엔진 제작 시 (정적데이터 한계)
- **발동 빈도**: OnAttack/OnHit 편승의 "상위 공격횟수"(평타 공속×타수 + 타스킬 발동수)를 타임라인 이벤트로 집계. 정적 불가.
- **버프 가동률**: 시간제 자버프(다크사이트·인레이지 등) 조건부 발동을 이벤트 시뮬로 실측. min(1,freq×dur) 정적근사 지양(과대).
- **ProcGate**: 확률발동 시드고정 베르누이(pity 과대방지).
- **소환 가동률**: 발동주기 vs projDuration 상호작용(min(1,dur/period)).
- **콘텐츠 보정**: 아레나 등 제곱근(공식은 확정, 적용은 컨텐츠 입력).
※ 이 5개는 값이 상호작용(공격빈도·시간·확률)에 의존 → 결정론적 타임라인 시뮬로만 정확. 이게 "엔진"의 핵심 역할.
