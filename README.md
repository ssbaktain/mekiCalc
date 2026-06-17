# MekiCalc — 메이플 키우기 스탯 효율 계산기

장비 윗잠/아랫잠·어빌리티·용사의 여정·유물 잠재·주흔작 등 **여러 스탯 획득 수단**을
실제 게임 데미지 공식으로 비교해, 다음 세 가지를 계산한다.

1. **단순 비교** — 현재 대비 최종 데미지 증가량 (확률·비용 무시)
2. **기댓값 포함 비교** — 원하는 옵션을 뽑기까지의 기대 시도/기대 비용 포함
3. **우선순위 추천** — 효율(= 데미지 증가율 / 기대 비용)로 정렬

외부 라이브러리 의존성이 **전혀 없다.** (JSON 파서까지 자체 구현 → 순수 `javac`로 빌드)

---

## 빠른 시작

```bash
javac -encoding UTF-8 -d out $(find src -name "*.java")   # 컴파일
java -cp out mekicalc.ui.App                               # ★ GUI 실행 (Swing)
java -Dfile.encoding=UTF-8 -cp out mekicalc.Main           # CLI 데모
java -Dfile.encoding=UTF-8 -cp out mekicalc.Main --selftest # 자체 검증

# 내 스탯 입력(대화형) → data/character.json 저장
java -Dfile.encoding=UTF-8 -cp out mekicalc.Main --input

# 장비 잠재 표 보기 / 큐브 평가(딜 증가량 + 큐브 기댓값)
java -Dfile.encoding=UTF-8 -cp out mekicalc.Main --equip 장갑 MYTHIC
java -Dfile.encoding=UTF-8 -cp out mekicalc.Main --equip-eval 장갑 MYTHIC 윗
```
Windows PowerShell: `.\build.ps1` (CLI) / `.\build.ps1 -SelfTest` (검증) / `.\build.ps1 -Ui` (GUI)

### GUI (Swing, 의존성 0)
`mekicalc.ui.App` — 왼쪽에 내 스탯 입력(적용 & 저장), 오른쪽에 부위·등급·윗/아랫 선택 →
각 잠재 옵션의 **딜 증가량 + 큐브 기댓값** 표. 엔진(`engine`)은 그대로 재사용하고
`ui.AppModel`(순수 로직, 헤드리스 테스트됨) + `ui.MainWindow`(Swing)로 분리.

### 장비 큐브 평가 (v1.0)
- 입력: `data/character.json`(내 스탯). `--input`으로 대화형 입력 가능.
- `--equip-eval <부위> <등급> [윗|아랫]` → 각 잠재 옵션의 **딜 증가량**과 **큐브 기댓값** 출력.
  - 큐브: 윗잠=미라클 큐브, 아랫잠=에디셔널 큐브(1개/굴림).
  - 큐브 기댓값 = 3줄 중 한 줄에라도 그 옵션이 뜰 때까지 평균 큐브 수 `1/P`, `P = 1−Π_slot(1−p_slot)`.
  - 딜 증가량 = 그 옵션 한 줄을 얻었다고 가정한 최종 데미지 증가율(공속/방관은 '정성적').

---

## 데미지 공식

`data/character.json`의 스탯은 **게임 UI 표시값** 기준이다. % 스탯은 "숨은 100%" 위에
표시값이 더해진다고 본다(예: 데미지 300% = 배수 4.0... 가 아니라 표시값 300 → `1+300/100`).

### 핵심 규칙 (사용자 제공 정보 반영)

| 항목 | 처리 |
|------|------|
| % 스탯 | 숨은 100% 기준 → factor `(1 + 표시값/100)` |
| 깡스탯 | % 연산 이전에 더해짐 |
| 주스탯 | 데미지에 **직접 항목 없음**. `주스탯깡 ×(1+주스탯%)` → **깡공 1:1로 공격력에 선반영** + **100당 스탯비례뎀 1%** 로만 작용 |
| 데미지 증폭 | 데미지 옵션을 **곱연산** (`데미지 ×(1+증폭/100)`) |
| 공격속도 / 방어관통 | **데미지 미환산**(정성적 고효율 후보로만 표시). 누적은 cap 향한 체감(`현재+(max−현재)×옵션/max`, 공속 max150·방관 max100) |
| 최종 데미지 | **표시%끼리 곱연산** (80%에 +10% → 98%, 즉 `1.8×1.1`) |
| 최소/최대 데미지 배율 | 숨은 100% 없음. 평균 `(최소+최대)/2`. 단 **최소>최대면 최소뎀으로 고정** |
| 모든 스킬 레벨 | 1레벨당 최종 데미지 약 0.5% (profile에서 조정) |

### 곱연산 구조 (mode A, 기본값)

```
기본공격력  = (공격력깡 + 주스탯) × (1 + 공격력%/100)
최종데미지지표 = 기본공격력
   × [데미지 ×(1+증폭)]        (데미지)
   × (1 + 스탯비례데미지)        (스탯비례,  + 주스탯/100)
   × (1 + 기본/스킬데미지)       (공격타입,  스킬비중 가중)
   × (1 + 보스/일반데미지)       (대상,      보스비중 가중)
   × (1 + 크리확률 × 크리데미지)   (크리티컬, 둘 다 스탯)
   × (최소+최대)/2              (데미지범위)
   × (1 + 최종데미지) ×(1+스킬레벨뎀) (최종)
```

> **공격속도·방어관통은 데미지로 환산하지 않는다.** 정확한 식도, 몬스터별 방어력도
> 알려진 바가 없기 때문. 대신 해당 옵션은 "정성적 고효율 후보"로 따로 표시한다
> (수치가 높게 뜨는 부위/요소면 고효율로 간주). `stats.json`의 `qualitative: true`.

**mode B** (`profile.json`의 `additiveDamage: true`): 데미지·스탯비례·공격타입·대상을
하나로 **합연산**하고 최종 데미지만 따로 곱한다. 게임 실제 동작이 확인되면 토글.

### ※ 확인 필요 상수 (`data/profile.json`)
- `skillRatio` / `bossRatio` — 스킬·보스 비중 시나리오
- `additiveDamage` — 데미지 옵션 합연산 여부(mode A/B)
- `enemyDefense` — 현재 방관 미사용이라 영향 없음(향후 방관 모델 도입 시 사용)

### 데미지로 환산하지 않는 스탯 (정성적 / 별도 모델)
- **공격 속도 · 방어 관통** — 식·적 방어력 불명 → "정성적 고효율 후보"로만 표시.
- **기본 공격 대상 수 증가** (광역/클리어 속도)
- **스킬 재사용 대기시간 감소** (쿨감 → 스킬 가동률)

뒤 둘은 스킬 로테이션/전투 모델이 필요해 단일 대상 데미지 공식에는 0으로 둔다.

---

## 아키텍처

```
src/mekicalc/
  json/Json.java              # 의존성 없는 미니 JSON 파서
  model/
    StatDef.java              # 스탯 정의 + combine 규칙(SUM/DIMINISHING/MULT_PCT)
    Profile.java              # 전투 시나리오 + 미확정 상수
    StatBlock.java            # 스탯 묶음(현재/변화량)
  engine/
    DamageModel.java          # 데미지 공식 + breakdown(분해) + combine(스탯 합치기)
    AcquisitionOption.java    # "한 수단으로 1건 확보" 평가 결과
    Recommender.java          # 기능 1/3 정렬
    source/
      StatSource.java / PotentialSource.java / EnhanceSource.java
  config/GameData.java        # data/*.json 로딩
  Main.java / SelfTest.java

data/                         # ★ 실제 게임 수치로 교체할 부분 (현재 예시값)
  stats.json                  # 스탯 정의(combine 규칙 포함)
  profile.json                # 시나리오 + 상수(※확인필요 포함)
  currency.json               # 재화별 상대 가치
  sources.json                # 획득 수단(잠재류 + 주흔작)
  character.json              # 현재 캐릭터 스탯(표시값)
```

### combine 규칙 (옵션을 현재값에 합칠 때)
- `SUM` — 단순 합산 (대부분의 스탯)
- `DIMINISHING` — cap 향한 체감 (공속/방관)
- `MULT_PCT` — 표시%끼리 곱연산 (최종 데미지)

`DamageModel.combine`이 이 규칙으로 현재+옵션을 합친 뒤 데미지를 비교하므로,
"옵션 추가 시 실제 증가량"이 정확히 계산된다.

### 기댓값
- **잠재 재굴림**: 원하는 옵션 확률 `p` → 1건 확보까지 평균 `1/p`. 비용 = `(1/p)×굴림비용×재화가치`.
- **주흔작**: 레벨별 성공확률 `clamp(base+멤버십+길드+5·10번째보너스)`, 초벌은 1강 확정,
  실패 재시도(레벨손실 없음 가정) → 레벨당 `1/p` 합산.

---

## 실제 값으로 교체하기 (코드 수정 불필요)

- 확률/옵션 → `sources.json`
- 미확정 상수/시나리오 → `profile.json`
- 새 스탯 → `stats.json` (필요시 `DamageModel`의 공식에 연결)
- 현재 스탯 → `character.json`

수정 후 `--selftest` 권장. (`realdata.baseDamage`는 현재 `character.json` 손계산값
`33000×5.2×2.10×2.30×2.00×1.6×1.5×0.7×1.0×2.25 = 6,265,939.68`과 비교하므로,
데이터를 바꾸면 이 기대값도 갱신해야 한다.)

---

## 향후 확장 경로
- **화면 인식 / 스크린샷 자동 입력**: OCR 결과 → `character.json` 작성만 하면 엔진이 그대로 읽음.
- **넥슨 공식 API 연동**: API 응답 → `StatBlock` 매핑 어댑터만 추가.
- **GUI/웹**: 엔진(`engine` 패키지)은 UI 비의존.

## 알려진 단순화/가정
- 주흔작 실패 시 레벨 하락·파괴 없음 가정.
- 잠재 재굴림은 "원하는 한 줄이 뜰 때까지"의 기하분포(여러 줄 동시 고려 미반영).
- 크리 데미지 배율·적 방어력·전투 시나리오는 가정값(`profile.json`, ※확인필요).
- 대상수 증가·쿨감은 단일 대상 데미지에 미반영.
- 모든 수치는 **예시값**이며 실제 게임 밸런스와 무관.
```
