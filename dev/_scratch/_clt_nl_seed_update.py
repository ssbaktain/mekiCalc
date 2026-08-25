# -*- coding: utf-8 -*-
# 나이트로드 seed를 8-13 패치 클뜯 기준으로 갱신
import json, io, shutil, sys
sys.stdout.reconfigure(encoding="utf-8")

P = r"C:\Users\MhHs\Documents\project\mekiCalc\data\skills_calc\nightlord_seed.json"
BAK = r"C:\Users\MhHs\Documents\project\mekiCalc\data\skills_calc\nightlord_seed_pre2694.json"
shutil.copy(P, BAK)

seed = json.load(io.open(P, encoding="utf-8"))
sk = {s["idx"]: s for s in seed["skills"]}
ms = {m["masteryIdx"]: m for m in seed["masteries"]}

# ---------- SKILLS ----------
# 92020 윈드 탈리스만: 대상 6→8, 기절 1.5→2s
s = sk["92020"]
s["desc"] = "전방에 부적을 던져 적 8명에게 400% 피해를 입히고, 2초간 기절시킵니다."
s["effects"][0]["targetCount"] = 8
s["effects"][1]["durationMs"] = 2000

# 92040 마크 오브 어쌔신: 주기 5→2.5s, 딜 300→190%
s = sk["92040"]
s["desc"] = "2.5초마다 주변 임의의 적 7명에게 암살자의 표식을 새깁니다. 표식이 새겨진 적을 공격하면 190% 피해를 입히고 표식이 사라집니다."
s["periodMs"] = 2500
s["effects"][0]["coeff"] = 1900

# 93040 다크 플레어: 딜 400→330%
s = sk["93040"]
s["desc"] = "20초간 어두운 기운을 방출하는 검을 생성하여 5초마다 주변 적 5명에게 330% 피해를 2회 입힙니다."
s["effects"][0]["coeff"] = 3300

# 93060 베놈: 발동 30→50%
s = sk["93060"]
s["desc"] = "공격한 대상을 50% 확률로 10초간 중독시켜 1초마다 45% 지속 피해를 줍니다."
s["chance"] = 500

# 93080 아드레날린: 최종뎀 10→15%
s = sk["93080"]
s["desc"] = "스킬을 7번 발동할 때마다 10초간 최종 데미지 15%, 공격 속도 8% 증가합니다."
s["effects"][0]["value"] = 150

# 94060 다크 세레니티: 최종뎀 15→12%
s = sk["94060"]
s["desc"] = "최종 데미지 12%, 최소 데미지 배율 20% 증가."
s["effects"][0]["value"] = 120

# 94080 페이탈 베놈: 피해가 Dot 유형으로 (즉발이나 도트 분류)
s = sk["94080"]
s["effects"][0]["damageType"] = "dot"
s["note"] = "8-13 패치: 추가피해가 Dot 데미지타입으로 변경(즉발·durationMs없음, GetDamageR Values=[6000,Dot,1]). 도트 증폭/약화 적용대상 여부 엔진 확인 필요."

# ---------- MASTERIES ----------
# 90075 lv39: 기절지속 → 윈드탈리스만 딜증가(GiveDamagePower1 800)
m = ms["90075"]
m.clear()
m.update({
    "lv": 39, "skill": "92020",
    "masteryName": "윈드 탈리스만 - 피해(변경)",
    "type": "skillStat", "stat": "GiveDamagePower1", "value": 800, "unit": "pct",
    "masteryIdx": "90075",
    "note": "8-13 패치: 기절지속+50% → 윈드탈리스만(92020)에 GiveDamagePower1 800 상시부여로 교체(ModSkillStat). 이름 인게임 재확인 필요."
})

# 90165 lv82: 베놈 약화 120→150 (본체 93060 Weakness 소스값)
m = ms["90165"]
m["value"] = 150
m["note"] = "기본 Flag:Disabled, 마스터리로 활성화(적 받는피해15%)"

# 90215 lv104: 다크플레어 타격주기 -400→-250
m = ms["90215"]
m["value"] = -250

# 90255 lv122: 발동주기 → 약화 부여(Weakness 80, 92041 발동시)
m = ms["90255"]
m.clear()
m.update({
    "lv": 122, "skill": "92040",
    "masteryName": "마크 오브 나이트로드 - 약화(변경)",
    "type": "stat", "stat": "Weakness", "value": 80, "unit": "pct",
    "role": "debuff", "target": "enemy",
    "trigger": "onAttack", "gatedBy": "92041", "durationMs": 2000,
    "masteryIdx": "90255",
    "note": "8-13 패치: 발동주기단축 → 약화 부여로 교체(구 90295 역할, Weakness 150→80). 92041(마크 발동딜) 공격시 대상 2초 약화. 이름 재확인 필요."
})

# 90295 lv138: 약화 → 마크 발동주기/지속 단축
m = ms["90295"]
m.clear()
m.update({
    "lv": 138, "skill": "92040",
    "masteryName": "마크 오브 나이트로드 - 발동 주기(변경)",
    "effects": [
        {"type": "period", "value": -500, "unit": "ms", "note": "마크 발동주기 -0.5초(딜 자주)"},
        {"type": "duration", "value": -500, "unit": "ms", "note": "마크 표식 지속 -0.5초(딜무관)"}
    ],
    "masteryIdx": "90295",
    "note": "8-13 패치: 약화 → 마크 발동주기/지속 단축으로 교체(구 90255 역할). 이름 재확인 필요."
})

with io.open(P, "w", encoding="utf-8") as f:
    json.dump(seed, f, ensure_ascii=False, indent=2)
    f.write("\n")

print("seed 갱신 완료. 백업:", BAK)
