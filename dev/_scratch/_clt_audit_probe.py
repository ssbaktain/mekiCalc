# 감사용 구조 탐색: SupporterTable / SkillProjectileTable / SkillTable Operations 구조 확인
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))

SK = {}
for fn in os.listdir(NEW):
    if fn.startswith("SkillTable") and fn.endswith(".json"):
        for r in load(fn): SK[str(r.get("SkillIndex"))] = r
PROJL = load("SkillProjectileTable.json")
SUP = load("SupporterTable.json")

print("=== SupporterTable[0] keys ===")
print(list(SUP[0].keys()))
print(json.dumps(SUP[0], ensure_ascii=False)[:800])

print("\n=== SupporterTable codes (all) ===")
for s in SUP:
    print(s.get("SupporterIndex"), s.get("SupporterCode"), s.get("GradeType"), s.get("CreatureIndex"))

print("\n=== SkillProjectileTable[0] keys ===")
print(list(PROJL[0].keys()))
print(json.dumps(PROJL[0], ensure_ascii=False)[:600])

print("\n=== A skill record with Operations (find one having GetDamageR) ===")
shown = 0
for si, r in SK.items():
    ops = r.get("Operations") or []
    for op in ops:
        if isinstance(op, dict):
            s = json.dumps(op, ensure_ascii=False)
            if "Damage" in s or "Projectile" in s:
                print("SkillIndex", si, "CoolTimeMs", r.get("CoolTimeMs"), "op keys", list(op.keys()))
                print("  ", s[:300])
                shown += 1
                break
    if shown >= 6: break
