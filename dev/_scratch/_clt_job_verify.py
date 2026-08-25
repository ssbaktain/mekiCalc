# 직업 의심스킬 desc 키/구조 확인 + 마스터리 원본 검증 (34100/64080/64100/63020/63050 등)
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))
SK = {}
for fn in os.listdir(NEW):
    if fn.startswith("SkillTable") and fn.endswith(".json"):
        for r in load(fn): SK[str(r.get("SkillIndex"))] = r
HS = load("HeroSkillTable.json")
HM = load("HeroSkillMasteryTable.json")

# HeroSkillTable에 desc/name/openlevel 필드 있나
print("=== HeroSkillTable 레코드 전체 필드 (한 개) ===")
print(json.dumps(HS[0], ensure_ascii=False))
# SkillTable 레코드 전체 필드
print("\n=== SkillTable[34100] 전체 ===")
print(json.dumps(SK.get("34100"), ensure_ascii=False))
print("\n=== SkillTable[64080] 전체 ===")
print(json.dumps(SK.get("64080"), ensure_ascii=False))
print("\n=== SkillTable[64100] 전체 ===")
print(json.dumps(SK.get("64100"), ensure_ascii=False))
print("\n=== SkillTable[63020] 전체 (홀리파운틴) ===")
print(json.dumps(SK.get("63020"), ensure_ascii=False))
print("\n=== SkillTable[63021] 전체 (홀파 child) ===")
print(json.dumps(SK.get("63021"), ensure_ascii=False))
print("\n=== SkillTable[63050] 전체 (트페 트리거, cd18?) ===")
print(json.dumps(SK.get("63050"), ensure_ascii=False))
print("\n=== SkillTable[63051],[63052] (트페 딜) ===")
print(json.dumps(SK.get("63051"), ensure_ascii=False))
print(json.dumps(SK.get("63052"), ensure_ascii=False))

# 34100/64080/64100 이 HeroSkillTable에서 어느 JobStep/Type/오픈인지 + RequireLevel 소스
print("\n=== HeroSkill 매핑: 34100/64080/64100 ===")
for r in HS:
    if str(r.get("SkillIndex")) in ("34100","64080","64100","63050","63020"):
        print(" ", json.dumps(r, ensure_ascii=False))
# 마스터리로 오픈되는지 (RequireCreatureLevel)
print("\n=== HeroSkillMastery에서 parent=63050/63020 or index 관련 오픈레벨 ===")
for m in HM:
    if str(m.get("SkillIndex")) in ("34100","64080","64100") or str(m.get("ParentSkillIndex")) in ("34100","64080","64100"):
        print("  ", m.get("MasteryIndex"), "lv", m.get("RequireCreatureLevel"), "parent", m.get("ParentSkillIndex"), "skill", m.get("SkillIndex"))
