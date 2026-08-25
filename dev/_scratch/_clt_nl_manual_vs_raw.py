# -*- coding: utf-8 -*-
# manual 수치를 원본 클뜯과 직접 대조(seed 무시) — 오기입 검증
import json, io, os, sys
sys.stdout.reconfigure(encoding="utf-8")
D = r"C:\Users\MhHs\Documents\project\mekiCalc\data\skills_calc"
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
man = json.load(io.open(os.path.join(D,"nightlord_manual.json"),encoding="utf-8"))
st1 = {str(r.get("SkillIndex")): r for r in json.load(io.open(os.path.join(NEW,"SkillTable1.json"),encoding="utf-8"))}
proj = {str(r.get("ProjectileIndex")): r for r in json.load(io.open(os.path.join(NEW,"SkillProjectileTable.json"),encoding="utf-8"))}
state = {str(r.get("StateName")): r for r in json.load(io.open(os.path.join(NEW,"StateTable.json"),encoding="utf-8"))}
hm = {str(r.get("MasteryIndex")): r for r in json.load(io.open(os.path.join(NEW,"HeroSkillMasteryTable.json"),encoding="utf-8"))}

def ops(idx):
    r = st1.get(str(idx))
    return [o for o in (r.get("Operations") or []) if o] if r else []

print("========== 마스터리 op values 직접 대조 (58개) ==========")
print("각 줄: [masteryIdx] manual(type=value unit) || 원본 op\n")
for m in man["masteries"]:
    mi = m["masteryIdx"]
    o = ops(mi)
    # manual 표현
    if "effects" in m:
        mv = "effects=" + ";".join(f"{e.get('type')}:{e.get('stat') or ''}={e.get('value')}{e.get('unit') or ''}" for e in m["effects"])
    else:
        mv = f"{m.get('type')}:{m.get('stat') or ''}={m.get('value')}{m.get('unit') or ''}"
    # 원본 op 요약
    if o:
        raw = " | ".join(f"{op.get('Type')}[{','.join(map(str,(op.get('Values') or [])))}]"
                         + (f" Dur{op['DurationMs']}" if op.get('DurationMs') else "")
                         + (f" Tick{op['TickTimeMs']}" if op.get('TickTimeMs') else "")
                         for op in o)
    else:
        # StatData 마스터리
        sd = hm.get(mi, {}).get("StatData")
        raw = f"StatData={sd}" if sd else "(op없음/StatData없음)"
    print(f"[{mi}] {mv}")
    print(f"      원본: {raw}")
