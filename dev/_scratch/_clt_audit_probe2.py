# 2차 탐색: class→ClassType, Grade2+ 스킬필드, CreateProjectile op, SkillProjectileTable.SkillIndex
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
PROJ = {str(r.get("ProjectileIndex")): r for r in PROJL}
SUP = load("SupporterTable.json")
SUPI = {str(s.get("SupporterIndex")): s for s in SUP}

print("=== class -> ClassType (Grade1 대표) ===")
for s in SUP:
    if s.get("GradeType") == "Grade1" or s.get("SupporterCode", "").endswith("_4"):
        pass
seen = {}
for s in SUP:
    code = s.get("SupporterCode", "")  # SUPPORTER_{class}_{grade}
    parts = code.split("_")
    if len(parts) == 3:
        cls = parts[1]
        if cls not in seen:
            seen[cls] = s.get("ClassType")
for cls in sorted(seen, key=lambda x: int(x)):
    print(" class %s = %s" % (cls, seen[cls]))

print("\n=== Grade5(LEG) supporter 레코드 스킬 필드 (index 401 = class1 LEG) ===")
s401 = SUPI.get("401")
print("keys:", list(s401.keys()))
for k in s401:
    if "Skill" in k or "Open" in k or "Level" in k:
        print("  ", k, "=", s401[k])

print("\n=== CreateProjectile op 예시 탐색 ===")
cnt = 0
for si, r in SK.items():
    for op in (r.get("Operations") or []):
        if isinstance(op, dict) and "Projectile" in str(op.get("Type", "")):
            print("Skill", si, "op:", json.dumps(op, ensure_ascii=False)[:300])
            cnt += 1
            break
    if cnt >= 8: break

print("\n=== SkillProjectileTable: SkillIndex 가진 레코드 존재? 전체 키 합집합 ===")
keyset = set()
for r in PROJL: keyset |= set(r.keys())
print("union keys:", keyset)
# SkillIndex 유사 필드 값 예시
for r in PROJL[:5]:
    print("  ", json.dumps(r, ensure_ascii=False)[:200])
