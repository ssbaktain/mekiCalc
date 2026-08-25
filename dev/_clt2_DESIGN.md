# clt2 — 스킬 데이터 파이프라인 재작성 설계도 (파악 완료 2026-08-08)

지난 파이프라인 실패 원인: ①79종 op를 5분류로 압축 → FollowLevelIndex 등 누락, ②천분율/백분율 혼용.
아래는 원본 SkillTable 전수 규명 결과 + 새 설계 원칙. (본체 = 유저 직업 스킬, 동료/몬스터/특수 제외)

## 원칙 (확정)
1. 5분류 폐기 → **원본 op 충실 보존**. 분류는 버리는 필터 아닌 덧붙이는 태그.
2. **천분율 정수 원본 유지**. 변환(÷10)은 UI 표시에서만. (부동소수점 오류·게임 결정론 일치)
3. **커버리지 100% 게이트**: 원본 모든 op가 IR에 반영됐는지 기계 대조, 미반영 op 1개라도 있으면 빌드 실패(화이트리스트 예외만 허용).
4. 관계 3종 분리: **소유(트리)/참조(강화)/전역(State)**. + 동료·특수는 분리 태그.
5. **조건 파서 + 함수형 값 파서**를 일급 컴포넌트로.
6. 대상수 = **딜 op의 MaxHitCount**(확정, 검증됨). ParentOperationNo(409) op 의존 보존.
7. **Flag = 효과 활성/지속/조건 제어자**(단순보존 아님). 8. **선반영 규약 명시**(이중계산 방지). 9. **확률발동 = 데이터에 값+조건만, 판정은 엔진 ProcGate**.

## 스킬 id 체계 (검증) — ★id패턴에 의존 금지
- FollowLevelIndex 부모는 끝자리 0(100%). 단 **자식 offset은 +0~+5+ 다양**(자기참조 +0 포함, +3/+4/+5 실존: 12032→12035 등). op호출 체인의 **중간노드는 x1·x2도 또 자식을 호출**(다층 트리). 5자리뿐 아니라 **6자리 그룹(107쌍)**도 트리 형성.
- **트리는 반드시 실제 참조(FollowLevelIndex + op호출)로 구성**. id offset/자릿수 규칙은 힌트일 뿐 신뢰 금지. 자기참조(CreateProjectile이 자기 인덱스 생성, 예 퍼지 94040)·순환은 방문표시(seen)로 처리.
- 본체 = HeroSkillTable 등록 정식(5자리) + 소유 자식 클로저(참조기반 확장) = **549 스킬**(5자리 339 + 6자리 207 등).
- **80대역 = 서포터(동료)** 스킬(SupporterTable, CreatureIndex 9409 등). 본체와 분리.
- **7~8자리(905/999/950/960 등) = 몬스터/보스/특수컨텐츠**(CampFamilyTable/DungeonTable 참조). 본체 클로저 미도달 → 무시.

## 연결 관계 (전수 1487 참조)
- **소유(트리에 중첩)**: `FollowLevelIndex`(120, 자식→부모) + `UseSkill·UsePassiveSkill·UseSkillToTarget(InArea)·CreateProjectile·FireProjectile·CreateServantProjectile`(384) + NextSkillIndex(콤보). ★**중첩 조건: 자식이 HeroSkillTable 정식스킬이 아닐 것(전용 하위기만)**.
- ★**정식스킬을 호출하는 경우(11개)=소환 재활용/다중 발동소스**(비홀더32020를 쇼크·자기가 소환·발사, 포이즌미스트53020를 플레임헤이즈가 재소환). 이중계산 아님! 처리: **①딜은 그 정식스킬 루트에서 한 번만 계산(중복금지) ②다른 스킬의 호출은 "발동 링크"=발동빈도 기여로 합산**(무시하면 소환·재소환 발동소스 손실). IR: 정식→정식 호출은 children 중첩 아닌 `invokes:[{skill, via, freqSource}]` 참조. ※대부분(33040·32020·33060)은 딜없는 소환/활성화(ModSkillEnabled/CreateProjectile)라 딜중복 자체가 없음.
- **참조(강화/제어 링크, 안 묶음)**: `ModSkillStat·ModSkill·ModSkillR·ModSkillEnabled·ModSkillDisabled·ModSkillProjectile(R)`(874). 부모가 외부 독립 스킬을 강화. 2패스: targetSkill→boost 맵 후 조회.
- **State(전역 StateStore)**: 생산 스킬≠소비 스킬(서펜트스톤: 113030 grant AssaultMode → 112030 branch), 트리 경계 넘음 → 전역. ★State는 단순플래그 아닌 **삼위일체=StateTable정의+종속효과+연결**(본체 182종):
  - **StateTable(626종) 정의**: MaxStack(SerpentStone5·ComboAttack5, 대부분1)·StackRule(Override/FollowOperationByFiredCreature)·Buff/Debuff/CC분류·행동제약(CanMove/Attack/Cast=CC표현)·CcPower·StateHostileType(자기/적).
  - **종속효과**: op의 `State` 필드로 효과가 State에 귀속(ModStat107·ModStatR54·GetDotR14 등, 자기145/적43/부모49). "State활성/스택당 이 효과 적용" → **딜 직접관여**(스택스케일=딜배수·State조건딜·종속스탯버프).
  - **스택 2패턴**: ①`State_N` 분해(LuckyDice_1 Attack+0 ~ _6 +50; DarkSight_2; 베놈93060_2) — 레벨별 개별State+개별값. ②MaxStack>1(SerpentStone5) — 스택수×값.
  - 연결 op: `State`(grant, Values=None 상태만 켬)·`ClearState`(consume)·`ModCurrentStateStack`·`ModStateMaxStack`·`ModFiredStateMaxStack`. 조건: `$CreatureStateCount/MaxCount`.
- **CC = State로 표현**: Stun/Frozen op → StateTable 행동제약(CanAttack=False 등). `AttackPowerInCc`=CC상태 대상 조건딜(히어로13070·바이퍼113080).

## op → IR 카테고리 (79종)
- 딜: GetDamageR·GetDamage·GetDotR·GetDot·GetDamageByMaxHpR·SetDamage
- 스탯변경: ModStat·ModStatR·ModStatOnTick·ModDamagePipelineStat·ModAttrDamagePipelineStat
- 스킬강화: ModSkillStat·ModSkill(R)·ModSkillProjectile(R)  |  스킬제어: ModSkillEnabled·Disabled·BlockSkill·SetSkill·ChangeSkill
- 스킬호출: UseSkill·UsePassiveSkill·UseSkillToTarget(InArea)  |  투사체: CreateProjectile·FireProjectile·Destroy·Lock*·TeleportProjectile·ModProjectileDir
- 상태: State·ClearState·ModCurrentStateStack·ModStateMaxStack·ModStateDurationR·ModFiredStateMaxStack
- CC: Stun·Frozen·Knockback  |  정화: Dispel·Cleanse  |  회복/실드/자원: GetHealByMaxHpR·GetHotByMaxHpR·CreateShieldByMaxHpR·GetMpR·ConsumeMpR
- 소환: SpawnCamp·SpawnCreatureByTarget·SpawnServant·SetServantSkill·RemoveCreature  |  이동: Dash*·ManualJump·EnableDoubleJump
- 쿨타임/시간: ModCoolTime(R)·ModRemainTriggerTime·SetRemainTriggerTime·ModBattleTime  |  연출: EffectOnly·None·SetExpressionVariable

### ★op 딜 관여도 태그 (스킬 아닌 op 단위! 없애지 말고 전부 보존 후 태그)
- **딜생성**: GetDamage*·GetDot*·SetDamage
- **딜빈도**(간접 딜영향, 반영필수): 쿨타임 op(ModCoolTime(R)·Mod/SetRemainTriggerTime, 본체 12개) → 발동주기 단축 = DPS↑
- **딜조건**: CC(Stun·Frozen, 본체 17개)는 `AttackPowerInCc` 조건딜과 연결(히어로13070·바이퍼113080) / State·EnableCondition
- **딜증폭**: ModStat*·ModSkillStat·ModSkill*(스탯/부스트)
- **순수유틸**(딜계산 스킵, 단 보존): 연출(EffectOnly)·순수회복(GetHeal/Hot)·자원(GetMpR)·정화(Dispel/Cleanse)·이동(Dash*)·워프(Save/LoadWarpSpot). ★단 이동/정화 op가 딜op와 같은 스킬에 공존 흔함(이동29중8·정화5중3 딜동반) → **스킬 통째 유틸분류 금지, op단위로**.
- **본체 무관(화이트리스트 명시제외, 게이트에서 예외등록)**: 몬스터/보스/특수컨텐츠 전용 op(SetMonsterTier·AddCreatureScore·ModCreatureScale·ModSight·BlockPotion·ChangeCreatureIndex[본체無]·GetMotR 등, 전부 7~8자리/특수대역). RemovePassiveSkill(나워134031) 본체1개는 유틸보존.
- 원칙: **"없는 게 나은 op"는 없다. 커버리지 게이트 위해 전부 보존, 딜 관여도만 태그.**

## 마스터리 = 제2의 스킬 소스 (★클로저 밖, 별도수집 필수) — 2종류!
`HeroSkillMasteryTable`(812) = **① SkillData 마스터리(714) + ② StatData 마스터리(98)** 두 종류. ★둘 다 반영 필수.
- **① SkillData(714)**: 효과가 테이블이 아니라 **SkillIndex가 가리키는 별도 스킬(90xxx 대역)의 op**(ModSkillStat376·ModSkill216·ModSkillEnabled63·ModSkillR62 등=부모강화). 효과스킬은 **HeroSkillTable에 0개=본체 클로저 완전 밖** → 별도수집. 구조 {MasteryIndex, SkillIndex=효과스킬, ParentSkillIndex=대상, RequireCreatureLevel, RequireMasteryIndex=선행, CostData}. 효과=효과스킬 op 파싱(dmgPct/targetPlus/growth/cdr/finalDmg…).
- **② StatData(98=7스탯×14직업)**: SkillIndex 없이 **직접 스탯 부여** `{Type, Value}`. 딜영향 스탯: MainStat·BaseAttackPower·SkillPower·CriticalChance·MaxDamageRatio + BaseAttackMaxHitCount(평타 대상수) + HitChance. ★이것도 놓쳤던것(재검증 발견) → 반영.
- IR: skill_node에 `masteries:[{idx, requireLevel, requires, kind(skill|stat), effect(skill=효과스킬op / stat={type,value})}]`. 유저 장착여부=Stage B/엔진 입력.
- ★마스터리 효과스킬 op에도 **#함수 존재**(EnhanceRagingBlow_Mastery 등, 예전 인핸스레이징블로우 저평가 이슈) → #함수 파서를 마스터리 op에도 적용.
- ★커버리지 게이트가 **SkillData 효과스킬 op + StatData 둘 다 포함**(안하면 812개 누락). 예전 skills_v2엔 있었으나 clt2 설계서 누락됐던 것 → 편입.

## 평타(기본공격) — 공속 기반 딜
x4010 평타는 전직업 HeroSkillTable 등록=클로저 포함(내 초기 오판 정정). 대상수=`$CreatureFinalStat(;BaseAttackMaxHitCount)+1`. ★딜 빈도=공속(AttackSpeed) 함수 → 평타 DPS=공속×계수×대상수. trigger.fastByAtkSpeed + AttackSpeed 스탯으로 엔진 계산.

## 값 규약
- **모든 수치 천분율 정수 원본 유지**(120=12%, 3000=300%). op Values[i] 그대로.
- **성장**: `ValueLevelFactors[1]`=성장코드 Y=**SkillLevelFactorTable Factor 슬롯 인덱스**(24슬롯). ★값=`base × Factor[Y][스킬레벨]/1000`. **GROWTH 하드코딩 상수 폐기→테이블 직접참조**(더 견고, 메이플용사 슬롯23도 자동=예전 '23'누락버그 원천차단). 검증: 슬롯12/21/22=Lv100 1500/1400/1300(0.5/0.4/0.3%선형)·슬롯23=6000/7000/7670(메이플용사 특수곡선, Lv100/120/187). 슬롯0-11=타시스템(지수). X=op카테고리(성장무관, 5직업재현확인). 상세 [[mekicalc-skill-growth-code]].
- **#함수 값(7종, SkillDescParamTable 정의 없음)**: `#함수(계수;부가...)` → **딜 계수 = 첫 인자(천분율)**. 함수명=특수거동(ShadowBat 20%소환·TriflingWhim 25%2배·Element_Darkness_Dot 표식중첩도트). 딜함수 4종: FlashSlash·TriflingWhim·ShadowBat·Element_Darkness_Dot. 발동/가동률은 함수별 처리.

## Flag = 효과 활성/지속/조건 제어자 (★단순보존 아님, 처리규칙 필수)
op Flag는 효과의 라이프사이클을 제어(본체): **IsSticky(309)**=영구지속 · **Disabled(75)**=초기비활성(마스터리 등으로 켜짐 전엔 딜계산 제외; 퍼지94041 크리저항) · **WatchCondition(35)+ExpireOnConditionFail(17)**=조건 감시·실패시 만료(조건 참일 때만 지속; 퍼지 최종뎀=결계 있는동안) · ExcludeTarget(11) · SyncQueueDelayBySkillSpeed(9)=큐딜레이 공속동기 · RecalculateValueOnTick(1)=틱마다 재계산. → IR op에 flag파싱 + 엔진이 활성/조건부지속 반영.

## op간 의존 (ParentOperationNo 409·WatchOperationNo 146)
같은 스킬 내 op끼리 의존: **parentOpNo=부모 성립 시 자식 적용**(13030: GetDamageR맞으면 Stun / State하 스탯). ★**조건부 딜 주의**: GetDamageR·GetDotR이 parentOpNo 소지(부모 조건하만 발생, 놓치면 안됨). **watchOpNo=감시 대상 op 상태 유지동안 지속**(WatchCondition/ExpireOnConditionFail Flag와 짝). → IR에 parentOpNo·watchOpNo 보존 + 엔진이 "부모성립→자식적용"·"감시상태 유지동안 지속" 처리(단순보존 아님).

## 대상수 모델 (검증 완료)
★**대상수 = op·스킬 2단 관리(전직업 규명)**: `op.MaxHitCount`(op별 대상상한, 우선) / `skill.TargetCount`(스킬 폴백, op에 MaxHitCount 없을때만). 전직업 딜스킬 desc"N명" 대조: MaxHitCount일치71·TargetCount일치0(딜스킬 TargetCount는 거의 1=껍데기). 예외=op에 MaxHitCount없으면 TargetCount사용(63052 딜 mh None→tc7·마크어쌔신92040 State표식 mh None→tc7). 즉 TargetCount는 껍데기 아니라 "op가 대상수 지정 안할때의 스킬기본".
딜 op의 **MaxHitCount = 스킬 동시타격 대상수**(럭키세븐3·슈리켄4·써든레이드8 desc일치, 검증됨). 우선순위: ①**소환딜=ProjectileTable.MaxTargetCount**(프리징브레스10) → ②딜 op MaxHitCount → ③없으면 TargetCount(마크어쌔신7) → ④결계형 area(MaxRangeMm 범위전체) → ⑤평타만 `$CreatureFinalStat(;BaseAttackMaxHitCount)+1`. 마스터리 "대상+"=op MaxHitCount 가산. ※예전 "럭키3=BaseAttackMaxHitCount 런타임" 결론은 일반스킬엔 오류(정정). 단일대상DPS엔 무관(clamp1), 멀티타겟 지표때 필수.

## 선반영 규약 (★이중계산 방지, 설계 필수)
상시패시브(OnStart) 스탯 변경 = 유저 스탯창에 이미 반영될 수 있음 → 엔진이 또 더하면 이중계산. 판정: **공퍼(Attack→SKILL_ATK_PCT)·주스탯%(MAIN_STAT_PCT)=스탯창 미포함→엔진유지 / 그외 상시패시브=선반영→엔진제거**. 상세 [[mekicalc-damage-formula-rules]].
- **OnStart 상시패시브 스탯 35종 전수**(선반영 후보): FinalDamage173·RunSpeedMms56·AttackSpeed45·CriticalChance44·Attack42·MaxHp31·CriticalPower23·HitChance20·MaxDamageRatio19·AttackPowerToBoss17·MinDamageRatio15·Defence14·DebuffResist13·BaseAttackPower13·SkillPower11·PiercePower9·BuffDurationRatio9·AttackPower7·Weakness6·CoolTimeReduceRatio6·AttackPowerExcludeBoss3·AttackPowerInCc2·Str/Int/Dex/Luk 등. **주의: 조건부(State/조건부 op)는 선반영 아님=엔진유지**(다크사이트 등). Stage B에서 스탯별+조건유무로 태그.

## 소환/투사체 가동률 (예전 최대난제) ★소환 딜 파라미터 전부 ProjectileTable — 전면 재정립
부모(발동주기=cd or OnTimer periodMs) → `CreateProjectile(투사체pi)` → **SkillProjectileTable[pi]가 소환 딜의 모든 것**을 담음(SkillTable 최상위 Dur/Tick은 소환딜과 **완전 무관**, 오류였음):
- **딜스킬 = ProjTable[pi].SkillIndex**(예 44020→44021, GetDamageR 계수). ★FollowLevelIndex로 클로저 이미 포함(딜스킬 누락은 아님).
- **지속 = ProjTable[pi].DurationTimeMs** (프리징브레스 5000=5초·엘퀴네스 30000=30초, desc검증)
- **틱(딜주기) = ProjTable[pi].TickTimeMs[0]** (형태 [N,N] 두값동일, 프리징브레스 [500,500]=0.5초 desc일치, 165/210행 보유)
- **대상수 = ProjTable[pi].MaxTargetCount** (프리징브레스 10, 101/210행) ★소환은 op MaxHitCount 아님!
- **소환 딜 = 딜스킬계수 × (지속/틱) × 대상수 × 가동률**. 가동률 = min(1, 지속/발동주기)=결정론적(ProcGate 불필요).
- ※실증: 프리징브레스 우리모델(SkillTable Dur1500/Tick1000=2회) vs 실제(ProjTable 5000/500=10회) = **5배 과소평가** 버그였음.
- **자기참조 소환**(93030→93030): 스킬이 자기인덱스를 투사체로 생성=**반복딜**(순환 아님).
- IR: 소환노드 {sourceSkill, periodMs/cd, projIndex, projDurationMs, projTickMs, projMaxTargetCount, dealSkill}. ★Stage A는 CreateProjectile→ProjectileTable 조인 필수.

## 발동(확률/트리거) — 데이터 vs 계산 경계
데이터엔 **값+조건만**: procPct(원본 TriggerRatio, 확률<100% 47개), periodMs, fastByAtkSpeed, counterEvery/requireState/exclusiveGroup. **발동 판정은 엔진 ProcGate**(시드고정 베르누이+icd). 정적기댓값 min(1,freq×dur) 지양(가동률 과대=홀리차지 0.91실제 vs 0.99pity). 원칙: 런타임불변=데이터, 상호작용의존=계산.
- ★**trigger type 11종 시맨틱**(발동빈도 계산 핵심): None(252)=직접시전(쿨/수동) · OnStart(200)=상시패시브(가동1.0) · OnTimer(21)=주기(periodMs, 공속비례시 fastByAtkSpeed) · **OnAttack/OnHit/OnTryAttack/OnDamage(60)=공격/피격 편승 → 빈도=공격횟수 의존**(평타·타스킬 발동수×procPct) · OnActiveSkill(11)=액티브 시전편승 · OnState(3)=상태진입 · OnEnemyDie/OnWaveStart=이벤트. → 편승형은 상위 발동수를 엔진이 집계해 빈도 산출.

## 조건 파서 스펙 (본체 167 조건식)
문법: `조건 = 비교식 ((&&|||) 비교식)*`, `비교식 = 피연산자 cmp 피연산자`
- 비교연산: `== >(51) >= != < <=`  논리: `&& ||`
- 피연산자: `$함수(인자)` | `$Trigger.속성` | 정수(천분율) | State명 | 스킬index
- **$함수 21종 시그니처**(인자수): CreatureStateCount(대상;State)=73최다 · CreatureFinalStat(대상,스탯) · CreatureIsBoss(대상)→딜 bossExtra분기 · CreatureProjectileCount(대상,스킬)=결계가동 · CreatureStateCountByFiredCreature(대상;State)=도트스택 · CreatureStateMaxCount · CreatureHasPassiveSkill · CreatureIsGround/IsAir=이동조건 · IsFlyMode() · CheckDungeonType/CheckIsTrial=컨텐츠 · CreatureEnemy/AllyCount · EnemyCreatureCountInArea/InRectArea=대상수 · CreaturePosition/ProjectilePosition
- **$Trigger.속성**: `$Trigger.SkillIndex`(발동 스킬 판정)·`$Trigger.StateFlag & StateAttrFlag.CC`(발동 플래그)

## IR 스키마 (Stage A 산출)
```
# 전역 (skill_node 밖, StateStore)
state_defs: { <name>: {                          # StateTable(626) + 종속효과 결합
  maxStack, stackRule, kind(buff|debuff|cc), hostile(self|enemy),
  restrictions:{canMove,canAttack,canCast,...}, ccPower,   # CC 표현
  effects:[ {op원본: type,target,values천분율,valueLevelFactors,perStack?} ],  # 활성/스택당 적용
  grantedBy:[skillId.opNo], clearedBy:[...] } }

skill_node(부모 x0) {
  id, name, job(creatureIndex), openLevel, type(active/passive), cooldownMs, mp,  # 정수 원본
  durationTimeMs, tickTimeMs,                     # ★비소환 스킬 자체 지속딜용(632개, GetDamageR+Dur/Tick=N초간 틱딜). 소환스킬(CreateProjectile有)은 이 필드 무관→summon 사용
  trigger: {type, ratio, periodMs, fastByAtkSpeed, condition(파싱)},
  ops: [ {no, type, target, values(천분율 정수), valueLevelFactors, growthCode, dealTag(딜생성/빈도/조건/증폭/유틸),
          maxHitCount, state, flag, parentOpNo, watchOpNo, enableCondition(파싱트리), func(#파싱)} ],  # 원본 1:1
  children: [ skill_node ],                       # 소유 트리(재귀, 전용 하위기만)
  summon: [ {projIndex, projDurationMs, projTickMs, projMaxTargetCount, dealSkill} ],  # CreateProjectile→ProjectileTable 조인(소환딜: 지속/틱/대상수 전부 ProjTable, dealSkill계수는 children에)
  invokes: [ {skill, via(CreateProjectile/FireProjectile/UseSkill), freqSource} ],  # 정식스킬 발동링크(소환재활용·재소환), 딜은 대상루트서 단일계산·빈도만 기여
  boosts_in: [ {fromSkill, stat, value, growthCode} ],  # 강화 참조(역인덱싱)
  masteries: [ {idx, requireLevel, requires, kind(skill|stat), effect} ],  # 제2소스(HeroSkillMasteryTable 714+98, 유저 장착). skill=효과스킬op / stat={type,value}
  states: { grant:[name], require:[{name,cmp,n}], consume:[name], branch:[{state,then}] }  # state_defs 참조
}
```
- Stage A(원본→IR): 손실 없이 정규화 + 커버리지 게이트. Stage B(IR→엔진뷰): 분류·파생·선반영태그.
- ★커버리지 게이트 = **단순 op수 비교 아님**: 본체 SkillTable + 마스터리 효과스킬(90xxx)의 **원본 op 각각이 IR의 어느 노드(ops/masteries/state_defs.effects)에 1:1 매핑됐는지 추적**. 미매핑 op=빌드실패(화이트리스트 예외만). 실측 대조(메이플용사·마크·퍼지·서펜트·럭키다이스).
- ★**분모 완전성 = 참조 무결성으로 검증됨**: 분모 op가 참조하는 모든 스킬idx/State/#함수가 아는 테이블서 해결(미해결 State 0·미해결 스킬참조=수치오탐만·서번트경로 클로저포함+0). 놓친 분모 없음 확인. (클로저 OWN에 SetServantSkill/Spawn* 명시추가—안전상, 실제 +0)

## Stage A가 읽을 원본 테이블 (★소스 완전성 전수검증됨 — 1536테이블 스캔)
**스킬 계수 파이프라인 분모(게이트 100% 대조 대상)**: SkillTable(+1)·HeroSkillTable(정식판별)·**HeroSkillMasteryTable(마스터리 714+98)**·**StateTable(State정의)**·**SkillLevelFactorTable(성장 원본, 24슬롯=성장코드Y, GROWTH상수 대체)**·**SkillProjectileTable(소환 투사체 존속/개수, 소환가동률)**·SkillDescParamTable(#함수)·SupporterTable(동료분리).
- ★**유저 스탯창 소스(선반영, 분모 아님·게이트 대상 아님)**: HeroSkillEnhanceTable(직업강화스탯)·WeaponMasteryTable·HeroPower*·Guild*·Gear*·Artifact* → 유저가 스탯창 값 제공, 완전성은 실측(스탯창 대조).
- **제외**: SkillEffectTable(연출 EffectTemplate)·SkillSound·몬스터 _Attack_Hit류·SkillTable2(97xxxxx 특수, 본체참조0).
- ★**규명완료(소환 딜 파라미터 전부 ProjectileTable)**: 소환 지속=ProjTable.DurationTimeMs·틱=TickTimeMs·대상수=MaxTargetCount·딜스킬=SkillIndex. SkillTable 최상위 Dur/Tick은 소환과 무관(옛 결론 오류). CreateProjectile→ProjectileTable 조인 필수.

## 파악 완료 체크리스트
[x] op 79종 카탈로그 [x] 연결 관계 1487 [x] 본체 클로저 549(id패턴 의존금지) [x] 809=동료 [x] 7-8자리=몬스터/특수
[x] #함수 딜계수 규칙 [x] $조건 파서 스펙 [x] 성장코드 [x] 천분율 규약 [x] op 딜관여도 태그(없애지말고 보존) [x] State 삼위일체(StateTable+효과+연결·스택2패턴)
[x] Flag=활성/지속/조건 제어(Disabled·WatchCondition·ExpireOnConditionFail·IsSticky) [x] 대상수=딜op MaxHitCount(검증·예전결론정정) [x] 선반영 규약(이중계산방지) [x] 발동=데이터값+엔진ProcGate
[x] ★소환딜=ProjectileTable 전면재정립(지속/틱/대상수/딜스킬 전부 ProjTable, SkillTable Dur/Tick 무관·프리징브레스 5배과소평가 정정) [x] op간의존(parentOpNo=부모성립→자식적용·조건부딜주의·watchOpNo=지속감시)
[x] ★마스터리 제2소스(714개 클로저밖·별도수집·커버리지포함) [x] 평타=공속딜(클로저포함) [x] 커버리지게이트=op 1:1매핑추적(카운트아님)
[x] ★트리 소유조건=정식스킬 자식중첩금지(이중계산방지·정식호출=발동링크) [x] trigger type 11종 시맨틱(편승형=공격횟수의존) [x] 동료참조0·boost대상클로저내·마스터리대역5-6자리 안전확인
★본체 파악 완결(11+2 검증). 남음: 선반영 스탯목록 확정 → Stage A 구현 → 커버리지 게이트 → 실측 검증(메이플용사/마크/퍼지/서펜트/럭키다이스). 동료계승(80대역)은 본체 완료 후 별도 트랙. (도구: dev/_clt2_*.py)
