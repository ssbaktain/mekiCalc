# -*- coding: utf-8 -*-
# 나이트로드 변경 스킬의 필드/값 단위 diff
import json, io, sys
sys.stdout.reconfigure(encoding="utf-8")

OLD = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset\SkillTable1.json"
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\dump_2694\SkillTable1.json"

def load(p):
    return {str(r.get("SkillIndex")): r for r in json.load(io.open(p, encoding="utf-8"))}

old, new = load(OLD), load(NEW)
targets = ["90075","90215","90255","90295","92020","92040","92041","93041",
           "93060","93080","94030","94060","94070","94071","94080"]

def walk(o, n, path=""):
    diffs = []
    if type(o) != type(n):
        diffs.append((path, o, n)); return diffs
    if isinstance(o, dict):
        for k in sorted(set(o)|set(n)):
            diffs += walk(o.get(k), n.get(k), f"{path}.{k}")
    elif isinstance(o, list):
        if len(o) != len(n):
            diffs.append((path+"[len]", len(o), len(n)))
        for i in range(min(len(o),len(n))):
            diffs += walk(o[i], n[i], f"{path}[{i}]")
    else:
        if o != n:
            diffs.append((path, o, n))
    return diffs

for t in targets:
    o, n = old.get(t), new.get(t)
    nm = (n or o or {}).get("NameKey") or (n or o or {}).get("Name") or ""
    print(f"\n### {t} {nm}")
    for path, ov, nv in walk(o, n):
        print(f"   {path}: {ov} -> {nv}")
