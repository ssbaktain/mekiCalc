# -*- coding: utf-8 -*-
# 나이트로드(CI9) 마스터리 전수 변경 확인: HeroSkillMasteryTable 매핑 + SkillTable1 op old/new 비교
import json, io, sys
sys.stdout.reconfigure(encoding="utf-8")

NEW3 = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
OLD3 = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\bak_TextAsset_pre2694"

def load_list(base, name):
    return json.load(io.open(base+"\\"+name, encoding="utf-8"))
def load_st1(base):
    return {str(r.get("SkillIndex")): r for r in load_list(base, "SkillTable1.json")}

hm_new = load_list(NEW3, "HeroSkillMasteryTable.json")
hm_old = load_list(OLD3, "HeroSkillMasteryTable.json")
st_new = load_st1(NEW3)
st_old = load_st1(OLD3)

# CI9 마스터리
nl_new = [r for r in hm_new if str(r.get("CreatureIndex")) == "9"]
nl_old = [r for r in hm_old if str(r.get("CreatureIndex")) == "9"]
print(f"나이트로드 마스터리 수: old {len(nl_old)} / new {len(nl_new)}")

def sig(r): return json.dumps(r, ensure_ascii=False, sort_keys=True)
old_by = {str(r["MasteryIndex"]): r for r in nl_old}
new_by = {str(r["MasteryIndex"]): r for r in nl_new}

# 1) HeroSkillMasteryTable 레코드 자체 변경(이름/레벨/StatData/parent 등)
print("\n=== [A] HeroSkillMasteryTable 레코드 변경(매핑/이름/StatData) ===")
mchg = 0
for mi in sorted(set(old_by)|set(new_by), key=lambda x:int(x)):
    o, n = old_by.get(mi), new_by.get(mi)
    if sig(o) != sig(n):
        mchg += 1
        st = "삭제" if n is None else ("신규" if o is None else "변경")
        print(f"  {st} {mi}")
        if o and n:
            for k in sorted(set(o)|set(n)):
                if json.dumps(o.get(k),ensure_ascii=False)!=json.dumps(n.get(k),ensure_ascii=False):
                    print(f"      {k}: {o.get(k)} -> {n.get(k)}")
print(f"  레코드 변경 {mchg}개")

# 2) 마스터리 op 실체(SkillTable1의 SkillIndex) old/new 비교
print("\n=== [B] 마스터리 op(SkillTable1) 값 변경 ===")
def walk(o,n,path=""):
    d=[]
    if type(o)!=type(n): d.append((path,o,n)); return d
    if isinstance(o,dict):
        for k in sorted(set(o)|set(n)): d+=walk(o.get(k),n.get(k),f"{path}.{k}")
    elif isinstance(o,list):
        if len(o)!=len(n): d.append((path+"[len]",len(o),len(n)))
        for i in range(min(len(o),len(n))): d+=walk(o[i],n[i],f"{path}[{i}]")
    else:
        if o!=n: d.append((path,o,n))
    return d

opchg = 0
for mi in sorted(new_by, key=lambda x:int(x)):
    si = str(new_by[mi].get("SkillIndex"))
    nm = new_by[mi].get("NameLocal","")
    o, n = st_old.get(si), st_new.get(si)
    diffs = walk(o, n)
    if diffs:
        opchg += 1
        print(f"  마스터리 {mi} (op SkillIndex {si}) {nm}")
        for p,ov,nv in diffs:
            print(f"      {p}: {ov} -> {nv}")
print(f"  op 변경 마스터리 {opchg}개")
