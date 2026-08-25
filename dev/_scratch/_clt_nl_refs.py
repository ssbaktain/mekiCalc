# -*- coding: utf-8 -*-
# 나이트로드 스킬 op에서 참조하는 하위 인덱스(투사체/소환/자식) 전수 수집 + 최신 대조
import json, io, os, sys
sys.stdout.reconfigure(encoding="utf-8")
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
OLD = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\bak_TextAsset_pre2694"
def load(b, n): return json.load(io.open(os.path.join(b, n), encoding="utf-8"))

st_new = {str(r.get("SkillIndex")): r for r in load(NEW, "SkillTable1.json")}
st_old = {str(r.get("SkillIndex")): r for r in load(OLD, "SkillTable1.json")}
proj_new = {str(r.get("ProjectileIndex")): r for r in load(NEW, "SkillProjectileTable.json")}
proj_old = {str(r.get("ProjectileIndex")): r for r in load(OLD, "SkillProjectileTable.json")}

# 나이트로드 스킬 대역
nl = [k for k in st_new if k.isdigit() and 90000 <= int(k) < 95000]

# op에서 참조 인덱스 수집(CreateProjectile/Summon/CreateCreature/ModSkill의 Values[0])
refs = {"projectile": set(), "child_skill": set()}
CREATE_OPS = {"CreateProjectile", "CreateCreature", "Summon", "SummonCreature"}
for k in nl:
    rec = st_new[k]
    for op in (rec.get("Operations") or []):
        if not op: continue
        t = op.get("Type", "")
        vals = op.get("Values") or []
        if t in CREATE_OPS and vals:
            refs["projectile"].add(str(vals[0]))
    # FollowLevelIndex 자식
for k, r in st_new.items():
    if str(r.get("FollowLevelIndex")) in nl:
        refs["child_skill"].add(k)

print("=== 나이트로드 op가 생성하는 투사체/소환 idx ===")
print(sorted(refs["projectile"]))
print("=== FollowLevelIndex 자식 스킬 ===")
print(sorted(refs["child_skill"]))

def walk(o, n, path=""):
    d = []
    if type(o) != type(n): return [(path, o, n)]
    if isinstance(o, dict):
        for kk in sorted(set(o)|set(n)): d += walk(o.get(kk), n.get(kk), f"{path}.{kk}")
    elif isinstance(o, list):
        if len(o) != len(n): return [(path+"[len]", len(o), len(n))]
        for i in range(len(o)): d += walk(o[i], n[i], f"{path}[{i}]")
    else:
        if o != n: d.append((path, o, n))
    return d

print("\n=== 참조 투사체 8-4 vs 8-13 ===")
for pi in sorted(refs["projectile"]):
    diffs = walk(proj_old.get(pi), proj_new.get(pi))
    tag = "변경" if diffs else "동일"
    print(f"  투사체 {pi}: {tag}")
    for p, o, n in diffs: print(f"      {p}: {o} -> {n}")

print("\n=== 자식 스킬 8-4 vs 8-13 ===")
for ci in sorted(refs["child_skill"]):
    diffs = walk(st_old.get(ci), st_new.get(ci))
    tag = "변경" if diffs else "동일"
    print(f"  자식 {ci}: {tag}")
    for p, o, n in diffs: print(f"      {p}: {o} -> {n}")
