# -*- coding: utf-8 -*-
# seed(최신 참고본) vs manual(작업본) 필드 정밀 diff — 옮기며 누락/변형 탐지
import json, io, os, sys
sys.stdout.reconfigure(encoding="utf-8")
D = r"C:\Users\MhHs\Documents\project\mekiCalc\data\skills_calc"
seed = json.load(io.open(os.path.join(D,"nightlord_seed.json"),encoding="utf-8"))
man = json.load(io.open(os.path.join(D,"nightlord_manual.json"),encoding="utf-8"))

# 비교시 무시할 필드(작업본에서 의도적으로 넣거나 뺀 것): note, preReflected, mode 등은 표시만
IGNORE = {"note"}

def norm_eff(e):
    return {k:v for k,v in e.items() if k not in IGNORE}

def cmp_skill(a, b):
    diffs=[]
    keys = (set(a)|set(b)) - IGNORE - {"effects"}
    for k in sorted(keys):
        if a.get(k)!=b.get(k): diffs.append(f"{k}: seed={a.get(k)} manual={b.get(k)}")
    ea, eb = a.get("effects",[]), b.get("effects",[])
    if len(ea)!=len(eb):
        diffs.append(f"effects 개수: seed={len(ea)} manual={len(eb)}")
    else:
        for i,(x,y) in enumerate(zip(ea,eb)):
            ks=(set(x)|set(y))-IGNORE
            for k in sorted(ks):
                if x.get(k)!=y.get(k): diffs.append(f"effects[{i}].{k}: seed={x.get(k)} manual={y.get(k)}")
    return diffs

print("=== 스킬 seed↔manual 차이 ===")
sm={s["idx"]:s for s in man["skills"]}
for s in seed["skills"]:
    d=cmp_skill(s, sm.get(s["idx"],{}))
    if d:
        print(f"[{s['idx']} {s['name']}]")
        for x in d: print(f"    {x}")

print("\n=== 마스터리 seed↔manual 차이 ===")
mm={m["masteryIdx"]:m for m in man["masteries"]}
for m in seed["masteries"]:
    b=mm.get(m["masteryIdx"],{})
    ks=(set(m)|set(b))-IGNORE-{"effects"}
    d=[]
    for k in sorted(ks):
        if m.get(k)!=b.get(k): d.append(f"{k}: seed={m.get(k)} manual={b.get(k)}")
    ea,eb=m.get("effects",[]),b.get("effects",[])
    if len(ea)!=len(eb): d.append(f"effects개수 seed={len(ea)} manual={len(eb)}")
    else:
        for i,(x,y) in enumerate(zip(ea,eb)):
            for k in sorted((set(x)|set(y))-IGNORE):
                if x.get(k)!=y.get(k): d.append(f"effects[{i}].{k}: seed={x.get(k)} manual={y.get(k)}")
    if d:
        print(f"[{m['masteryIdx']} lv{m['lv']} {m['masteryName']}]")
        for x in d: print(f"    {x}")
