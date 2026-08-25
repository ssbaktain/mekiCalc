# 직업 스킬 테이블 구조 확인: HeroSkillTable / HeroSkillMasteryTable / SkillLevelFactorTable
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))

HS = load("HeroSkillTable.json")
HM = load("HeroSkillMasteryTable.json")
LF = load("SkillLevelFactorTable.json")
HT = load("HeroTable.json")

print("=== HeroSkillTable[0] keys ===")
print(list(HS[0].keys()))
print(json.dumps(HS[0], ensure_ascii=False)[:500])
print("\n=== HeroSkillMasteryTable[0] keys ===")
print(list(HM[0].keys()))
print(json.dumps(HM[0], ensure_ascii=False)[:500])
print("\n=== SkillLevelFactorTable[0] keys ===")
print(list(LF[0].keys()))
print(json.dumps(LF[0], ensure_ascii=False)[:300])
print("\n=== HeroTable: CreatureIndex 목록 ===")
for h in HT:
    ci = h.get("CreatureIndex") or h.get("HeroIndex")
    print(" ", {k: h.get(k) for k in list(h.keys())[:8]})
    if HT.index(h) > 20: break

print("\n=== HeroSkillTable: CreatureIndex별 스킬 수 ===")
from collections import Counter, defaultdict
byci = defaultdict(list)
for r in HS:
    byci[str(r.get("CreatureIndex"))].append(r)
for ci in sorted(byci, key=lambda x: (len(x), x)):
    rows = byci[ci]
    print("  CI %s : %d skills" % (ci, len(rows)))

print("\n=== HeroSkillMasteryTable: CreatureIndex별 마스터리 수 ===")
bym = defaultdict(list)
for r in HM:
    bym[str(r.get("CreatureIndex"))].append(r)
for ci in sorted(bym, key=lambda x: (len(x), x)):
    print("  CI %s : %d masteries" % (ci, len(bym[ci])))
