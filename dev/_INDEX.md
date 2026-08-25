# dev 문서·스크립트 INDEX (헷갈림 방지용, 2026-08-14)

현재 유효한 것과 참고/구본을 한눈에. **엔진·IR 작업 시 아래 "정본"만 신뢰.**

## 현재 유효 IR (데이터)
- **`data/skills_calc/nightlord_manual.json`** ★★ 유일한 유효 작업본 (나이트로드만 진행 중).
- `data/skills_calc/nightlord_seed.json` — 원본충실 참고본(재작성 시 참고용, 검토 기준 아님).
- **`data/skills_calc/md/`** — IR 옆 정본 문서: `_clt2_SIM_MODEL.md`(★엔진 런타임 동작)·`_clt2_IR_TAGS.md`(★IR 전체 태그 의미·값·작용 사전, 착시방지).
- ⛔ `data/_deprecated/` — 폐기(구 clt2 초기 IR `skills_ir`). 참조 금지.

## 엔진 설계 문서 (계층별 역할)
| 문서 | 역할 | 상태 |
|---|---|---|
| **`_clt2_SIM_MODEL.md`** ⚠️**`data/skills_calc/md/`로 이동됨** | ★**엔진 런타임 동작 정본** (시뮬 실행모델·발동순서·버프 on/off·passive 트리거·확률/count/dot/소환tick·skillBoost·쿨감·유물 marginal·DPS집계) | **최신 정본 (2026-08-14)** |
| `_clt2_ENGINE_SPEC.md` | IR op → 넥슨 공식항 **정적 매핑** 청사진 | 보조 유효 (가동률 잔재는 SIM_MODEL이 상위) |
| `_clt2_DESIGN.md` | 스킬 데이터 op 전수규명(79종·천분율·조건파서) | 참고 유효. ※자동생성 파이프라인 부분은 manual 수작업으로 대체됨 |
| `_clt2_ARTIFACT_DESIGN.md` | 유물 소스 설계(스탯버킷·계층분리) | 미착수, 유물 작업 시 참고 |
| `_clt2_SUPPORTER_DESIGN.md` | 동료 소스 설계(소환개체·계승) | 미착수, 동료 작업 시 참고 |

> ★"엔진이 실제로 어떻게 도는가"는 항상 **SIM_MODEL**을 먼저 본다. 나머지는 데이터 이해용 보조.

## 스크립트 분류
- **재사용 도구(dev 루트, 패치마다 씀)**: `_clt_unpack.py`(번들 언팩) · `_clt_diff.py`(구/신 대조) · `_clt_findaddr.py`(복호화 주소 자동재획득) · `_clt_decrypt.py`(로컬라이징 복호화).
- **일회성 조사·검증(→ `dev/_scratch/`로 물리 격리, 59개)**: `_clt_nl_*` · `_clt_probe_*` · `_clt_audit*` · `_clt_job_*` · `_clt2_*_probe/audit/profile/stage_a` 등. 재실행 가능하나 상시 도구 아님.
- 복호화 이름 산출물(dev 루트 보존): `_clt2_artifact_names.json` · `_clt2_supporter_names.json`.

## 클뜯 원본 위치
- 로더 읽는 최신 테이블: `mekiCalc/textAsset/TextAsset/TextAsset/*.json` (빌드 2694, 8-13 패치).
</content>
