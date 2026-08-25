# 스킬 데이터 변환 스키마 (클뜯 → skills_v2)

**목적**: 클뜯 원본(SkillTable 등)에서 skills_v2로의 변환을 **결정론적 규칙**으로 고정한다.
케이스별 판단(AI 착시의 원인)을 제거하고, 이 문서의 규칙대로만 기계적으로 이행한다.
새 스킬 유형이 나와도 "필드를 놓쳐서" 생기는 버그가 구조적으로 재발하지 않게 한다.

**원칙**: analyze는 스킬마다 아래 6축 메타를 **전부** 추출한다(부분 추출 금지). 규칙에 없는 값은
`unknown`으로 표시해 검증에서 걸리게 한다(조용히 1/기본값으로 넘어가지 않는다).

---

## 입력 테이블 (textAsset/)
- **SkillTable / SkillTable1**: 스킬 정의(Operations + 스킬레벨 메타). key=SkillIndex.
- **SkillProjectileTable**: 발사체/소환수 정의. key=ProjectileIndex. `SkillIndex`(자식 딜), `MaxTargetCount`.
- **HeroSkillTable**: 직업별 습득 스킬(CreatureIndex 1~14, RequireLevel).
- **StateTable**: 상태(표식·스택). `StateName`, `MaxStack`.
- **SkillDescParamTable**: 함수형 계수(#FlashSlash 등) Calculate 정의.

---

## 딜 추적 (본체 vs 간접 연계)
스킬의 딜은 다음 순서로 찾는다:
1. **본체 딜**: Operations에 `GetDamageR`/`GetDotR` op가 있으면 그것이 딜.
2. **간접 연계**(본체 딜 op 없을 때): 아래 5종 op의 Values(자식 index)를 **재귀 추적**해 자식 딜을 찾는다.
   자식의 발동·대상·틱은 **자식 스킬/projectile 메타**에서 가져온다(부모 것 아님).
   - `CreateProjectile [projIdx]` → PROJ[projIdx].SkillIndex 자식 (소환수/발사체). 대부분.
   - `FireProjectile [projIdx]` → PROJ[projIdx].SkillIndex 자식.
   - `ChangeSkill [skillIdx...]` → 변경 스킬 딜(비홀더스 리벤지 34060→34062).
   - `ModSkillEnabled [skillIdx]` → 활성화 스킬 딜(비홀더 도미넌트 33040→33041).
   - `UseSkill / UsePassiveSkill [skillIdx]` → 자식 딜.
3. **표식형**(State 부여 + 별도 딜 스킬): `State` op로 표식을 걸고 표식 딜은 별도 스킬. → §표식형(TODO 연결규칙).
   현재 마크 오브 어쌔신(92040 OnTimer State → 92041 OnAttack 딜). [수동매핑 잔존, 자동화 대상]

---

## ①축 타격 반복 (hits / dot)
스킬레벨 필드 `TickTimeMs`(배열) + op 종류로 결정. **GetDamage Values[2]는 tick 배열 있으면 무시**.
- **`GetDot`**: 지속 도트. op의 `DurationMs`/`TickTimeMs` = 지속/주기. dot{tickSec, durSec}.
- **`TickTimeMs` 순수숫자 배열 N개**(예 `['600','800',...]`): **각 틱 1타 → hits = N** (연타).
  - 폭풍의시 74020: 20개 → 20타. 44030: 3개 → 3타. 124040: 7개 → 7타.
- **`TickTimeMs` 표기틱 `시각[인덱스]`**(예 `'600[1:3]'`): **hits = 고유 대상 인덱스 개수**.
  - 리트리트샷 72020: `[2],[1:3]×3` → 고유{1,2,3}=3타. (평타=basic hits 별도)
- **`TickTimeMs` [1개] 또는 없음**: hits = GetDamage Values[2] (즉발 N타).

## ②축 발동 (trigger)
`TriggerType`으로 분기. 확률=`TriggerRatio÷10`%.
- **`None`**: 액티브(유저/자동). `CoolTimeMs` 있으면 쿨마다 1회, 없으면 평타(공속 초당).
- **`OnTimer`**: 주기 발동. 주기 = **`TriggerTimeMs`**(함수형 `#FastByAttackSpeed`=공속비례).
- **`OnAttack`/`OnHit`**: 공격 시 proc. freq = ratio% × 공격빈도, **내부쿨 = `CoolTimeMs`**.
- **`OnDamage`**: 피격 시 proc. 내부쿨 = `CoolTimeMs`.
- **`OnActiveSkill`**: 액티브 사용당 proc.
- **`OnStart`**: 상시(습득 시 발동, 패시브 버프/딜강화).
- **`OnState`**: 상태 진입 시.
- ★`TriggerTimeMs`는 **OnTimer 주기 전용**. proc 내부쿨과 혼동 금지(=CoolTimeMs).

### ★연계 딜 발동 (부모→자식 = deal_trigger 규칙)
부모 스킬이 자식(projectile/UsePassiveSkill/ModSkillEnabled/DEAL_OVERRIDE)으로 딜을 트리거할 때,
**자식 자체 trigger가 아니라 부모 기준**으로 실질 발동주기를 정한다(자식 OnAttack은 대개 "부모가 건 표식/State 있을 때만"):
1. **부모 `CoolTimeMs` 있으면 그게 지배**(쿨마다 1회). 부모가 proc(OnAttack/OnActiveSkill/OnDamage)이면 procPct 유지+내부쿨=CoolTimeMs.
   - 트라이엄프페더(OnAttack15%+18s)·엘리다크(None+28s)·스톰브링어(OnActiveSkill+25s)·비홀더리벤지(OnStart+5s, State소모).
2. **없고 부모 `OnTimer`**: `ModRemainTriggerTime`(무한화)=소환 셋업→**자식 proc**(쉐도우파트너 분신 25%). 아니면 **OnTimer 주기**(마크 표식 5s, ClearState로 소모→5초1회).
3. **부모 쿨·타이머 없음**: 자식 발동 그대로. 자식 `None`+Dot=소환수 상시 지속딜(초당 1회 근사, 비홀더 도미넌트).
- 구현: `_clt_skill_analyze.deal_trigger(parent, child)`. dealTrigger.coolTimeMs → skills_v2 cd → 엔진 proc 내부쿨/액티브 쿨.

### ★TriggerCondition 종속 (특정 스킬 발동에만)
스킬 `TriggerCondition` 필드가 발동을 제한한다. proc이라도 상시 아님:
- **`$Trigger.SkillIndex == N`**: 스킬 N 발동 시에만 → **발동주기 = N의 cd**(N cd 없으면 N의 TriggerCondition 재귀).
  - 바이퍼 인레이지: 114080=피스트인레이지(114020, 16s)에만, 114090=114080→114020 재귀 16s. 상시발동 61%→17% 정정.
- **`$Trigger.IsBaseAttack == 1`** / `DamageFlag.BaseAttack`: 평타 시에만(OnAttack 취급). 디바인저지먼트·씨서펜트버스트.
- **`$CreatureStateCount(...;State) > 0`**: 특정 State일 때만 → State 가동률 곱(어썰트모드 등). [미해결 §State 가동률]
- 구현: `trigger_cond_cd(si)` → skills_v2 cooldownMs(deal_trigger 다음 우선순위).

## ③축 대상 수 (targets)
`TargetCount`는 거의 1(무의미). 실제 대상은 아래로 판정.
- **광역** = `MaxRangeMm` 4개값(사각범위) **AND** `SkillTargetType=Enemy` → 컨텐츠 마리수까지(min).
  - rangeType 무관(Rect든 None이든). 궁니르34020·써든94030·래피드124040(None+range4)도 광역.
- **단일 1**: `TargetType`=TriggerTarget/TriggerSelf(proc) / RandomEnemy(랜덤) / MaxRangeMm 1개(직선).
- **소환수 딜**: 딜 스킬이 담긴 **projectile의 MaxTargetCount**.
- **평타(basic X010)**: 1 + `BASIC_ATK_TARGETS` 스탯.

## ④축 계수·성장 (coeff / growth)
- **계수**: GetDamage/GetDot `Values[0]` ÷10 = %. 함수형 `#Func(X)`는 SkillDescParam Calculate(Const X Div D)로 해석, 실패 시 unknown.
- **성장**: op `ValueLevelFactors` 마지막값 → GROWTH{'12':0.5,'21':0.4,'22':0.3,'3':3.0}. 계수 = base×(1+growth/100×스킬레벨).
- **skillBoost**(특정스킬 최종뎀강화, 메용·어드밴스드파이널어택·쇼크강화): ModSkillStat `[targetSkill, opNo, stat, value]` + growth.
  - 메용(CI×10000+4100): 특수 성장 mult = 1+0.05×min(lv,120)+0.01×max(0,lv-120).
  - 그 외: 일반 성장 = value×(1+growth/100×스킬레벨). (어드밴스드 파이널어택·마크 나이트로드 등 성장 O)

## ⑤축 소환/연계 → §딜 추적 참고(재귀).

### 소환 가동률 (✅구현 2026-08-07)
소환수 종속 딜(소환 지속 동안만 존재)은 **가동률(uptime)을 딜에 곱**. 구현: analyze `build_summon_map`(종속 매핑)→skills_v2 `summonSkill`/`summonDurMs`→신엔진 `summonUptime()`(런타임 effCd). 비홀더 도미넌트·쇼크 ×0.952(마스터리만)~상시(스탯쿨감). ★summon 필드 소환수(피닉스 등)는 dot 주기로 이미 자동 반영(별도 경로).
- **★uptime = min(1, 소환수 proj `DurationTimeMs` / 소환스킬 effCd)**. effCd는 **런타임**(마스터리 쿨감+스탯 쿨감 반영, `SkillDpsModel.cooldown`). **정적 CoolTimeMs로 박으면 오판**.
  - 비홀더: 소환 32020(cd 30s), proj 지속 20s. 마스터리 [104] "재사용 30% 감소" → effCd 21s → **uptime 20/21=0.95**. **스탯 쿨감 더하면 effCd≤20 → 상시(1)**.
  - ⚠️ 지속(DurationTimeMs)도 지속연장 마스터리(durMs/durPct) 있으면 런타임 반영 필요.
  - **전수 조사(2026-08-07)**: 소환수별 재사용 마스터리 상이 → 정적 오판. 피닉스 -50%(60→30s, uptime 0.67)·비홀더 -30%(30→21, 0.95+스탯시 상시)·바하뮤트 -30%(80→56, 0.54)·프리져 -50%. 지속연장: 이프리트 +10s·바이퍼 어썰트모드 +5s(State도!)·캡틴 크루 지속=**버프지속 스탯 의존**.
- **종속 판정**: 소환 스킬 op `CreateProjectile [자식...]`가 생성하는 딜 스킬 + `FireProjectile`로 소환수에 명령하는 스킬.
  - 비홀더 32020 op → CreateProjectile 33040(도미넌트). 비홀더 쇼크 32030 → FireProjectile 32020(소환수 명령). 둘 다 소환 종속.
- **구현안**: analyze가 종속 딜에 `summonSkill`(소환 스킬 index)·소환 proj 지속 부여 → 신엔진이 소환스킬 effCd로 **런타임 uptime 계산** → `Cast.coeffFrac × uptime`(발동방식 불변). ★정적 uptime 금지(마스터리·스탯 쿨감 놓침).

### State 조건부 발동 가동률 (미구현 — ★조사결과: 단순 일반화 불가)
`TriggerCondition`에 `$CreatureStateCount(...;State) 조건`이 있는 딜. **소환 가동률처럼 단일 uptime 공식으로 안 됨**(2026-08-07 전수조사).
State마다 메커니즘·조건이 제각각(`==0`/`>1`/`>=5`/`HpRatio<10`)이라 기계적 일반화 시 새 착시 위험:
- **콤보/스택형**: ComboAttack≥5(인핸스레이징블로우)·Assassination>1(어쌔신)·SerpentStone≥5→AssaultMode(바이퍼)·Meso스택(쉐도워). → **콤보/스택 시스템 모델링 선행 필요**(정상상태 스택수·유지율).
- **소환 단계형**: BattleShip_1/2(캡틴 소환 단계 전환).
- **발동 체인형**: 디바인저지먼트(24070, 팔라딘 딜 28.84% — OnActiveSkill로 State 켜고 IsBaseAttack 소환딜. 발동 체인·5초 버프 지속 조사 필요. **과대 가능성**).
- **조건부**: Reincarnation(HpRatio<10 부활)·Fake_Off(페이크 반격).
- 바이퍼 인레이지(114080/114090)는 `TriggerCondition SkillIndex` 종속으로 이미 처리(cd 16s). AssaultMode State 게이팅은 추가 미반영(과소평가 방향이라 안전).
- **접근**: State 시스템을 6축과 별개 스키마로 설계(State별 정상상태 가동률/스택수). 콤보 시스템과 통합.

#### State/스택 시스템 설계 골격 (착수 2026-08-07)
★**근본 성격**: 발동·소환·마스터리는 클뜯 데이터→기계적 반영 가능(결정론). 그러나 State/스택 **정상상태 스택수·유지율은 게임플레이 의존**(공속·소모빈도·전투흐름)→정적 데이터만으로 결정 불가. 게임 가정+시뮬, 이상적으론 인게임 실측 필요. **다른 축과 성격이 다름을 명시**.
- **쌓기 추출**(규명 2026-08-07): op의 `State`=StateName + 부여 스킬 `TriggerType`으로 쌓기 빈도 판정. 대부분 **OnAttack=평타마다 +1**.
  - ComboAttack: 12030 OnAttack→평타마다+1, MaxStack 5(+마스터리 ModStateMaxStack 10245+1·14060+2), 스택당 방관10. 인핸스레이징블로우 14020=`ComboAttack≥5`(평타 5회 후 상시 충족).
  - Meso: 103031 OnAttack 평타마다+1(MaxStack10). Assassination: 104020 cd13s부여(MaxStack2). BloodyPocket: 104052.
- **★핵심 통찰**: 스택형은 **평타 지속 시 정상상태=만스택**(램프업만 초반, 90s 전투서 무시 가능). 시뮬 스택상태 넣어도 정상상태값=만스택 수렴 → **만스택 근사=시뮬과 실질 동일**. 조건 딜(StateCount≥N)은 정상상태서 상시 충족 가정 타당.
- **딜 방식 유형**: ①스택 비례 딜(스택수×계수) ②스택 소모 강타(N스택 도달→1회 강타, 발동빈도=도달빈도) ③State 게이팅(State 켜진 동안만 발동, uptime).
- **정상상태 모델**: 스택증가율(쌓기 trigger 빈도)·소모율·지속→평균 스택수. 시뮬레이터(TimelineSimulator)에 스택 상태 추가가 정석. 정적 근사는 상한/가정 명시.
- **검증 곤란**: 구엔진도 콤보는 comboReq 게이팅 근사(정확값 아님). 인게임 실측이 유일한 절대 검증.
- **1차 목표**: 명확한 케이스(Meso·Assassination)부터 정상상태 모델→시뮬 반영. 인핸스레이징블로우(ComboAttack)는 쌓기 로직 규명 선행.

## ⑥축 쿨 (cooldown)
- `CoolTimeMs`÷1000 = cd(초). `InitialMinCoolTimeMs`/`InitialMaxCoolTimeMs` = 첫 사용 쿨(있으면).
- 쿨감: 쿨감%(100 상한 체감) → 쿨감초(7초까지 1:1, 밑 0.5배, 하한 4).

---

## ⑦축 마스터리 반영 (type별 결정론 규칙)
마스터리(레벨 보너스)는 `type`(build `mastery_norm`)별로 반영 위치가 고정. **규칙에 없으면 미지원 명시**(조용한 무시 금지).
검증 장치 `_clt_verify.py ⑦축`이 신엔진 반영 type(`MASTERY_REFLECTED`)과 대조 → 미반영·딜영향 자동 플래그.

**반영 O (신엔진 `masterySum`/`masteryStatAdd`)**:
- `dmgPct` → 피해량%(typeFactor 합산) · `hitPlus` → 타수+ · `targetPlus`/`summonTargetPlus` → 대상+
- `cdrPct` → effCd 쿨감 · `tickPct`/`summonTickMs` → 소환/도트 주기 단축 · `stackPlus` → 콤보 최대스택+
- `statAdd` → **스킬별 보스/일반몹뎀**(`stat`=AttackPowerToBoss/ExcludeBoss → BOSS_DMG/NORMAL_DMG, `Cast.bossBonus/normalBonus`, hitDamage서 컨텐츠 stat 합산). ★스킬 한정이라 스탯창 미포함 → 엔진 반영.
- `ratioBoost`(7) → skillBoost 최종뎀 배수(어드밴스드 파이널어택 +50% → skillBoost value ×1.5, `collectBoost`). 부모 op=ModSkillStat FinalDamage.
- `ratioValue`(4) → 버프/디버프 스탯 값 배수(크로스오버 공퍼 +100%·인사이징 받피증 +50%, `masteryRatioValue`, addGrants서 clt stat 매칭 곱). 부모 op=ModStatR.
- `summonFirePlus`(3) → 소환 발사/폭발/설치 수+ (buildCast hits에 가산, 추가 타격 근사).
- `cdFlatMs`(1) → 쿨 고정감소(cross-skill: 씨서펜트강화→씨서펜트버스트, `crossCdFlatSec` 전 스킬 스캔 → effCd cdrFlat).
- `finalDmgPct` → 최종뎀 자버프(현재 2개 모두 동적 val=None).

★ratioValue는 부모 op type으로 분기(ModSkillStat/Pipeline=ratioBoost 최종뎀 / ModStatR=ratioValue 버프·디버프). analyze `mastery_effect` ratio가 parentType·parentStat·targetSkill 담음.

**~ 간접/조건부 (딜 직접 아님, 별도 취급)**: `enableDeal`(조건부 딜/버프 강화)·`selfStat`(조건부 자체스탯)·`durPct`/`durMs`(버프 지속=시간제 버프 가동률)·`procAdd`/`procRatio`/`fireRatio`(발동확률)·`setSkill`/`changeSkill`(스킬 교체).

**★값 성장**: `mastery_norm` value는 대부분 고정(피해+50%). `masterySum`은 open≤charLv 게이팅 후 단순 합.

---

## 스킬레벨 계산
`effLevel(tier)` = (min(charLv, 다음전직) − 전직레벨)×3 + SKILL_LEVELS + SKILL_LEVEL_tier.
전직: 1차10 / 2차30 / 3차60 / 4차100.

## 선반영 규약(사용자 스탯창)
- **스킬 공퍼(Attack)·주스탯%(MAIN_STAT_PCT)**: 스탯창 미포함 → 엔진이 더함. 공퍼는 **별도 곱**(SKILL_ATK_PCT).
- **나머지 상시 패시브**(기본공격뎀·보스뎀·크뎀·최소뎀·상시 최종뎀 등): 스탯창 선반영 → 엔진이 안 더함.
- **시간제 버프**(아드레날린·퍼지 등 유한 지속): 미포함 → 가동률 반영해 더함.

## 넥슨 공식 곱 구조(데미지엔진)
피해 = 공격력 × 5000/(방관후방어+6000) × 받피증감 × (1+데미지)(1+증폭) × (1+보스/일반) × **(1+평타/스킬뎀 + 피해량마스터리)** × 크뎀 × 최소~최대 × 최종뎀 × 컨텐츠보정 × 스킬계수.
- 피해량 마스터리(dmgPct)는 typeFactor에 **합산**(별도 곱 아님).

---

## 미해결/예외 (검증에서 unknown/플래그 처리)
- **State 조건부 발동 가동률**(★검증도구가 3건 플래그): 강화 상태(인레이지·디바인저지먼트)에서만 발동하는 딜을
  신엔진이 상시 발동으로 처리 → **바이퍼 인레이지 61% 과대**. State uptime 계산해 발동 스케일 필요(소환 가동률과 동성격).
  - 씨서펜트 인레이지(114080 OnActiveSkill)·서펜트어썰트 인레이지(114090 OnAttack)·디바인저지먼트(24070 버프기=오탐).
- **소환 가동률**: 비홀더 dur/cd(20/30) 등 소환 지속/재소환 자동추출 미확정(현재 상시 가정).
- **표기틱 → 고유 인덱스 규칙**: 리트리트샷 검증됨. 다른 표기틱 스킬 추가 검증 필요.
- **콤보 소모 스킬**(인핸스 레이징블로우 등): 신엔진 콤보 스택 처리 저평가(hero 0.18) — 발동 아닌 콤보 시스템 이슈.
- **함수형 계수**($CreatureStateCount·#TriflingWhim 등): 런타임/게임내부함수, 정적 해석 불가 → unknown.
