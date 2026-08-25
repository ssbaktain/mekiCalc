# -*- coding: utf-8 -*-
# manual 스킬 본체 수치 + StatData마스터리 + 90105 원본 직접 대조
import json, io, os, sys
sys.stdout.reconfigure(encoding="utf-8")
D = r"C:\Users\MhHs\Documents\project\mekiCalc\data\skills_calc"
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
man = json.load(io.open(os.path.join(D,"nightlord_manual.json"),encoding="utf-8"))
st1 = {str(r.get("SkillIndex")): r for r in json.load(io.open(os.path.join(NEW,"SkillTable1.json"),encoding="utf-8"))}
proj = {str(r.get("ProjectileIndex")): r for r in json.load(io.open(os.path.join(NEW,"SkillProjectileTable.json"),encoding="utf-8"))}
state = {str(r.get("StateName")): r for r in json.load(io.open(os.path.join(NEW,"StateTable.json"),encoding="utf-8"))}
hm = {str(r.get("MasteryIndex")): r for r in json.load(io.open(os.path.join(NEW,"HeroSkillMasteryTable.json"),encoding="utf-8"))}
sk = {s["idx"]: s for s in man["skills"]}

print("=== [A] StatData 마스터리 값 대조 ===")
for m in man["masteries"]:
    if m.get("type")=="stat" and not st1.get(m["masteryIdx"],{}).get("Operations",[None,None])[1:]:
        pass
for mi in ["90015","90025","90055","90065","90115","90125","90175"]:
    sd = hm.get(mi,{}).get("StatData")
    mv = next((m for m in man["masteries"] if m["masteryIdx"]==mi), {})
    print(f"  [{mi}] manual={mv.get('stat')}={mv.get('value')}  원본StatData={sd}")

print("\n=== [B] 90105 쉐도우러쉬(92030 op2 활성화 대상값) ===")
for op in (st1.get("92030",{}).get("Operations") or []):
    if op: print(f"  92030 op: {op.get('Type')} {op.get('Values')}")

print("\n=== [C] 스킬 본체 수치 대조 ===")
def gdeals(idx):
    out=[]
    for op in (st1.get(str(idx),{}).get("Operations") or []):
        if op and op.get("Type") in ("GetDamageR","GetDotR"):
            v=op.get("Values") or []
            out.append((op["Type"], v))
    return out
def child_of(idx):
    return [k for k,r in st1.items() if str(r.get("FollowLevelIndex"))==str(idx)]

for s in man["skills"]:
    idx=s["idx"]; rec=st1.get(idx,{})
    # 딜 소스: 본체 op + 자식 + 투사체 참조
    srcs = gdeals(idx)
    for c in child_of(idx): srcs += [(f"child{c}:"+t,v) for t,v in gdeals(c)]
    meta=[]
    if rec.get("TriggerRatio") and rec.get("TriggerRatio")!="1000": meta.append(f"TriggerRatio={rec['TriggerRatio']}")
    if rec.get("CoolTimeMs"): meta.append(f"CoolTimeMs={rec['CoolTimeMs']}")
    if rec.get("TriggerTimeMs"): meta.append(f"TriggerTimeMs={rec['TriggerTimeMs']}")
    if rec.get("Mp"): meta.append(f"Mp={rec['Mp']}")
    # manual deal
    md=[f"coeff{e.get('coeff')}/h{e.get('hits')}/tc{e.get('targetCount')}" for e in s.get("effects",[]) if e.get("role")=="deal"]
    print(f"[{idx} {s['name']}] chance={s.get('chance')} cooldown={s.get('cooldown')} period={s.get('periodMs')} count={s.get('count')}")
    print(f"    manual deal: {md}")
    print(f"    원본 딜op: {srcs}")
    print(f"    원본 meta: {meta}")
