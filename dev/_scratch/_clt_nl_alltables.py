# -*- coding: utf-8 -*-
# 나이트로드 관련 전 테이블 8-4(bak) vs 8-13(신) 전수 diff
# SkillProjectileTable, StateTable, StateActionTable, SkillEffectTable, SkillTable(1,2)
import json, io, sys
sys.stdout.reconfigure(encoding="utf-8")

NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
OLD = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\bak_TextAsset_pre2694"

def load(base, name):
    return json.load(io.open(base+"\\"+name, encoding="utf-8"))

def walk(o, n, path=""):
    d = []
    if type(o) != type(n): d.append((path, o, n)); return d
    if isinstance(o, dict):
        for k in sorted(set(o)|set(n)):
            d += walk(o.get(k), n.get(k), f"{path}.{k}")
    elif isinstance(o, list):
        if len(o) != len(n): d.append((path+"[len]", o, n)); return d
        for i in range(len(o)): d += walk(o[i], n[i], f"{path}[{i}]")
    else:
        if o != n: d.append((path, o, n))
    return d

# 나이트로드 대역 판정: 9로 시작하는 5자리(90000~94999) 또는 관련
def is_nl(idx):
    s = str(idx)
    return s.isdigit() and 90000 <= int(s) < 95000

def diff_table(fname, keyfield):
    try:
        on = {str(r.get(keyfield)): r for r in load(OLD, fname)}
        nn = {str(r.get(keyfield)): r for r in load(NEW, fname)}
    except FileNotFoundError:
        print(f"[{fname}] 파일없음"); return
    keys = [k for k in set(on)|set(nn) if is_nl(k)]
    print(f"\n########## {fname} (key={keyfield}) 나이트로드 대역 ##########")
    cnt = 0
    for k in sorted(keys, key=lambda x:int(x)):
        o, n = on.get(k), nn.get(k)
        diffs = walk(o, n)
        if diffs:
            cnt += 1
            st = "삭제" if n is None else ("신규" if o is None else "변경")
            print(f"  [{st}] {k}")
            for p, ov, nv in diffs[:12]:
                print(f"       {p}: {ov} -> {nv}")
    print(f"  → 변경 {cnt}개")

diff_table("SkillProjectileTable.json", "ProjectileIndex")
diff_table("StateTable.json", "StateIndex")
diff_table("StateActionTable.json", "StateActionIndex")
diff_table("SkillEffectTable.json", "SkillEffectIndex")
diff_table("SkillTable.json", "SkillIndex")
diff_table("SkillTable2.json", "SkillIndex")
