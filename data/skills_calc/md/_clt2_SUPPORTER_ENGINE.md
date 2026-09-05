# clt2 동료(서포터) 엔진 계약 (신엔진 v3)

동료 소환딜을 신엔진(`mekicalc.timeline`)에서 계산하는 방식. **입력 = `data/skills_calc/supporter_manual.json`(딜 IR) + `supporter_seed.json.inheritFactor`(계승/옵션 성장 테이블).** 규약 정본 = [[_clt2_SUPPORTER_TAGS]], 런타임 규칙 = [[_clt2_SIM_MODEL]].

> **위상**: 현재 동료는 구 `engine.skill.CompanionDps`(해석식, 보스70/사냥90초 블렌딩, [[engine-migration-state]])로 계산 중. 이 계약이 그걸 **신엔진 타임라인 소스로 대체**한다. ★manual에 **충실** — 엔진은 manual의 role/target/trigger/cond를 그대로 소비, 임의 해석 금지.

> ★**참조 = 직업(스킬) 타임라인 엔진**(유물 엔진 아님 — 동료는 유물과 달리 **직접 딜**). 동료는 **타임라인 엔진의 두 번째 인스턴스**: 기존 기계장치(`Scheduler`·`DealEvent`·`BuffSource`·`ModState`·`Analyzer`·`DamageFormula`)를 그대로 재사용하되 **base 프리셋=계승 스탯**, 스킬=동료 IR. **스킬별 딜 개별 수집**은 직업 엔진 방식 그대로(`DealEvent.skillIdx` 태깅 → `Analyzer.perSkillDamage()`).

---

## 0. 대원칙

- **동료 = 독립 딜 소스**(플레이어와 분리 집계). 소환 개체가 **자기 스킬**로 딜 + 플레이어 공격 스탯 **계승**.
- **manual 결정론 소비**: 엔진은 `supporter_manual.json`의 effect(role·stat·value·target·trigger·cond·coeff·hits·targetCount)를 그대로 적용. 값=천분율(÷10=%, FinalDamage /1000=배율).
- **소환 = 한시(30초) + 쿨(90초)**, 가동률 스칼라 곱 금지 — 시뮬 구간에 실제 소환 시점마다 창 배치(§3).
- **★동료 = 병렬 서브 타임라인**(윈도우 분리 — 통합 아님): `DamageFormula.dealDamage(base,…)`가 base 프리셋 1개로 계산하는데 동료 base(계승)≠플레이어 base라, 동료 딜을 플레이어 타임라인에 넣으면 base 충돌. → **소환마다 동료 전용 `Scheduler`/`Timeline`/`Analyzer` 인스턴스**(base=계승 스냅샷). 메인 타임라인엔 **소환 마커**만(스냅샷+버프주입). 동료는 액션슬롯 미점유(소환 모션 없음)라 플레이어 로테와 **무간섭 병렬**.
- **★소환 순간 동적 스냅샷(B 확정)**: 계승 스탯 = **소환 그 시점 플레이어 실시간 스탯**(그때 켜져 있는 플레이어 버프 포함). 소환 후엔 고정(플레이어 스탯 변화 무영향, 동료 자기버프만 얹힘). → 플레이어 타임라인을 **먼저 계산**해 각 소환 시점의 플레이어 ModState를 읽어 base 조립(2단계).

---

## 1. 입력

| 입력 | 출처 | 비고 |
|---|---|---|
| 선택 동료 | code(SUPPORTER_{class}_{step}) | grade·creatureIndex 파생 |
| 동료 레벨 | 유저 입력(≤ grade 캡) | 계승·옵션 성장·스킬 openLevel 게이트 |
| 플레이어 스탯 | 프리셋(소환 순간) | 계승 소스 |
| 전투창 T | 대상 모드 | 보스 70초·사냥 90초(구 규칙, 신엔진 시뮬시간과 통일) |
| manual | `supporter_manual.json[code]` | skills·options·inherit·_gradeBonus |
| inheritFactor | `supporter_seed.json.inheritFactor` | `Factor[동료레벨]` 배열(계승 8~12열·옵션 1/4/5/6/7/13열) |

---

## 2. 스탯 조립 (계승) — [[_clt2_SUPPORTER_TAGS]] §4

- **공격력만 부분 계승**: `동료ATK = player.ATK × (1 + player.ATK_PCT) × 계승%`.
  - `계승% = inheritFactor[동료레벨][levelFactor] / 10` (levelFactor=grade별 8/9/10/11/12, 만렙 90%캡).
  - 플레이어 차수레벨 < compareLevel(EPIC30/UNI60/LEG100)이면 × (플레이어레벨/compareLevel) 감산. NORMAL/RARE 보정 없음.
- **나머지 스탯 100% 계승**: CriticalChance·CriticalPower·AttackSpeed·MAIN_STAT·STAT_PROP_DMG·DMG·BASIC_ATK_DMG·SKILL_DMG·DEF_PEN·BOSS_DMG·NORMAL_DMG·MIN/MAX_DMG_MULT·FinalDamage 등 = 소환 순간 플레이어 값 그대로.
- **★미적용**(곱하지 말 것): **쿨감**(동료 스킬 쿨 원본 유지) · **1~4스렙·모스렙**(동료 스킬 coeff 불변, growthSlot 곱 금지).
- **옵션(Collection/Equip)은 계승/딜에 안 들어감** — 계정전역 스탯(이미 프리셋 반영). 소환딜 조립에서 제외.
- 동료 자기버프(target companion, §5)는 이 base 위에 소환창 동안 얹힘 → `ModState`.

---

## 3. 소환 스케줄 (★유저 설정)

- **시뮬 시간 `simMs` = 유저 설정**(플레이어 타임라인과 동일 축).
- **소환창 `W = 30000 × (1 + 소환지속%)`** — `SUMMON_DURATION%`(소환 지속 증가, **플레이어 스탯**)로 창 확장(확인됨). 값=소환 시점 플레이어 스탯(동적 스냅샷 B). ★버프지속%(BUFF_DURATION)와는 **별개 스탯**(버프지속%는 버프 durationMs용, v3.2에서 동료 버프 적용 여부 확인).
- **소환 쿨 `cd = 90000`** — ★쿨감 **미적용**(§2·§6).
- **자동 모드**: 유저가 **첫 소환 타이밍 `t0`** 지정 → 소환 시점 = `t0, t0+cd, t0+2cd, …` (< simMs 인 것 전부). 예: simMs=600s·t0=5s → 소환 5·95·185·275·… 각각.
- **수동 모드**: 유저가 소환을 **스킬처럼 임의 타이밍에 배치**(여러 번 가능). ★**소환 모션 소모 시간 0** → 다른 스킬과 **동시 사용 가능**(플레이어 액션슬롯 미점유). 자동/수동 공통으로 소환 이벤트가 플레이어 로테를 밀지 않음.
- **각 소환 창** = `[s, min(s+W, simMs)]` — 시뮬 끝 넘으면 잘림(그 안의 딜만 집계). 소환마다 §0의 동료 서브 타임라인 1개.
- **집계**: 동료 총딜 = Σ(각 소환 서브타임라인 딜). DPS = 동료 총딜 / (simMs/1000). (구 해석식 frac → 실제 이산 창으로 대체.)

---

## 4. 딜 소스 (manual skills 소비)

각 skill의 `role:"deal"` effect를 소스로. 딜 공식 골격(구엔진 검증, 신엔진 이식):
```
딜 = 동료스탯배수(globalMultiplier: 공격력·크리·보스뎀·최종뎀…) × 계열뎀팩터 × coeff/1000 × hits × targetCount × recv
```
- `계열뎀팩터` = 평타면 BASIC_ATK_DMG, 스킬이면 SKILL_DMG 등 stat 계열(route by 소스 종류).
- `recv` = 적 받피증 = `1 + Σ(동료 target:enemy 디버프) + 플레이어 받피증 공유`(§5).

**소스별 발동**:
| type/trigger | 발동 | 비고 |
|---|---|---|
| **평타**(basicAttack) | **초당 `atkRate` 회** = `1 + AttackSpeed/100`(체감상한 150%) | 소환창 내내. coeff×hits×targetCount |
| **액티브**(active) | 소환창 내 쿨마다 이산 발동 `ct=0,cd,2cd,…<W` | dealPerCast |
| **소환시전**(enter) | 소환 순간 1회 | dealPerCast(비숍 enter=회복이라 딜0) |
| **proc**(onAttack) | **동료 공격당** chance 발동, 내부쿨 캡 | 발동률 = `동료공격/초 × chance/1000`, cd 있으면 `min(1/cd, …)`. targetCount 적용 |
| **dot**(form:dot) | durationMs 동안 tickMs마다 | dotPerSec |

- **동료 공격/초** = `atkRate(평타) + 액티브·소환 시전/초`(proc 트리거 빈도용, 구 `skillCastsPerSec`).
- ★**proc 편승 소스** = 동료 자기 공격만(triggerBy companionAttack/companionBasicAttack). **플레이어 공격엔 안 걸림**(§6).
- 자식 딜본체는 manual에서 이미 부모 effect로 통합됨(targetCount=maxHitCount) — 엔진은 통합값 그대로.
- ★**설치기 절단**([[_clt2_SUPPORTER_TAGS]] §7-15): `expiresOnSummonEnd:true`(바닥 장판·소환체=CreateProjectile/ExpireOnCreatureDie) 도트는 **소환창(windowMs)에서 틱 절단**(`emitDeals` cap=min(windowMs,simCap)). 비-설치기(적 상태 도트 GetDotR·버프·디버프)는 simCap까지 잔존(소환 종료 후에도 적/플레이어 상태칸에 유지).

---

## 5. 버프/디버프 라우팅 (★target 기준)

manual effect의 `target`으로 적용처 분기:
- **`target:"player"`**(소환주 버프, 예 비숍 MaxDamageRatio·ATK·FinalDamage) → **플레이어 타임라인에 BuffSource 창** 주입. 소환창 동안(+effect durationMs·refresh) 활성 → **플레이어 딜 증가**. ★이게 구 블렌딩을 대체하는 핵심 — 동료가 플레이어를 버프.
- **`target:"companion"`**(동료 자기, 예 자기 FinalDamage) → 동료 `ModState`에 얹어 §4 딜에 반영.
- **`target:"both"`** → player+companion 양쪽(effect 2분기).
- **`target:"enemy"`**(받피증 Weakness/RECV_DMG 등) → 적 받피증 창. **플레이어+동료 딜 양쪽에 곱**(적에 붙는 디버프라 모든 공격자 수혜). `recv`·플레이어 receivedDmg 항에 합류.
- 버프 지속: effect `durationMs`(+`refresh`)로 창. `cond`는 §6(기본 상시). 주기형(periodic)은 periodMs마다 재부여(dur>period면 상시 유지).
- `used:false`(회복·클렌즈·이속 등) = 딜 무관, 스킵.

---

## 6. 조건·미적용 규칙 (표준, [[_clt2_SUPPORTER_TAGS]] §7)

- **cond 기본 상시**: `cond`(hpRatio>=30·mpRatio>=50 등)는 **충족 가정**으로 효과 활성. 주체=effect target(플레이어 HP/MP). 예외(적용 안 함)만 개별 지정.
- **perHpRatio+assumeMax**: 현재HP 비례 스탯(크뎀/공속)은 **HP100%=최댓값**(manual value가 이미 HP100% 환산값).
- **동료 공격 = 별개 스트림**: 플레이어측 `onAttack`/`onHit` 판정(유물 은펜던트·돼지리본, 직업 '공격/타격 시')은 **동료 공격에 발동 안 함**. 역으로 동료 proc은 동료 자기 공격만.
- **쿨감·스킬레벨 미적용**(§2).
- **옵션 딜분리**(§2).

---

## 7. 등급 보너스 (`_gradeBonus`)

- **유니크/레전 동료 자기 최종뎀**: 동료레벨 5/8/10 각각 FinalDamage +25%(target companion, 누적 최대 +75%). manual `_gradeBonus.uniqueLegendaryOwnFinalDamage`에서 일괄 — 동료레벨로 게이트해 §4 딜의 동료 FinalDamage에 곱(고유곱).

---

## 8. DPS 집계 · 타임라인 통합 ([[_clt2_SIM_MODEL]] §8)

- **★계산 순서(2~3단계, 순환 방지)**:
  1. **플레이어 타임라인**(직업/유물 버프만) 계산 → 각 소환 시점 `s`의 플레이어 실시간 ModState 확보.
  2. **소환마다**: `s`의 플레이어 스탯 스냅샷(B)으로 동료 base 조립 → 동료 서브타임라인 실행 → 동료 딜(§4) + 동료→플레이어 버프(§5 target player) 수집.
  3. **동료→플레이어 버프 + 동료→적 디버프(받피증)를 플레이어 타임라인에 `BuffSource` 주입** → 최종 플레이어 딜 재계산. (적 디버프는 소스 무관하게 적 피격 시 딜 가산 — 특별 처리 불요, 적에 붙은 디버프라 플레이어·동료 둘 다 그 창 동안 수혜.)
  - ★**계승 스냅샷엔 동료 자신 버프 미포함**(1단계=직업/유물만) — 소환 90초 간격·창 30초라 서로 다른 소환 버프 미중첩 → **순환 없음**. 메인 동료 1기만 계산.
- **스킬별 개별 수집**(직업 엔진 방식 그대로): 각 동료 딜은 `DealEvent(skillIdx=동료스킬idx, coeffFrac, hits, dmgType, dotTick)`로 슬롯에 태깅 → `Analyzer.perSkillDamage()`/`skillDamage(idx)`로 스킬별 총딜·타수·순간DPS 추출. 평타/각 액티브/proc/dot 전부 개별 idx로 분리 집계. (동료 전용 `Analyzer`+base=계승 프리셋.)
- **전체 출력**: 플레이어 DPS(동료 player-버프 반영됨) + 동료 DPS. 딜 비중 탭에 **동료 기여**(구 `renderCompanionSummary` 대체).
- **큐브·스탯 환산**: 동료 기여 포함(스탯 변화 시 동료 계승·딜도 재계산). 구엔진이 임시로 하던 걸 신엔진 일관 처리.
- 기여 측정 = leave-one-out(동료 빼고 재계산).

---

## 9. 단계 계획

- **v3.1**(코어): 계승 스탯 조립(§2) + 평타·액티브·소환 딜(§4) + player/companion 버프 라우팅(§5) + 소환창 스케줄(§3) + gradeBonus(§7). 비숍으로 검증(manual↔출력 대조).
- **v3.2**: proc(onAttack chance+내부쿨) + dot + enemy 받피증 공유 + perHp assumeMax. 전 직업 특이(바이퍼 버스트→어썰트 등) 반영.
- **v3.3**: 나머지 13동료 manual 채우며 엔진 일반화 검증. 구 `CompanionDps` 블렌딩 제거.
- **v4**(후속): 옵션→플레이어 스탯 기여 기능(옵션 성장 §옵션식), 컨텐츠 모드(M/K/C).

---

## 10. 구엔진서 보존 / 대체

- **보존(검증된 메커니즘)**: 소환창 W·전투창 T 부분가동(frac) · 평타 rate=atkRate=1+공속/100 · dealPerCast 이산 본딜 · proc rate·내부쿨 캡 · `recv=1+동료받피증+플레이어받피증공유` · 공속 체감 상한 150%.
- **대체/개선**: 등급 하드코딩(`gradeBasicAttack`) → **manual/seed 데이터 구동**. 유저 입력 계승비율 → **inheritFactor 자동**. 플레이어 버프 블렌딩 → **타임라인 BuffSource 창**(정합).
- **확인됨**: 소환지속%(SUMMON_DURATION)로 창 W 확장(§3) · 계승 동적 스냅샷 B · 유저 소환 스케줄.
- **재검증 필요(v3.2)**: proc 편승 정확한 clt trigger(§4) · 버프지속%(BUFF_DURATION)가 동료 버프 durationMs에 적용되는지 · UNIQUE/LEG 자기최종뎀 gradeBonus 이관 중복 주의(seed엔 스킬로도 존재).
