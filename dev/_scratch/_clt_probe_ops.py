# 특정 스킬들의 Operations 구조 조회(부모→자식 연결 op 확인). -*- coding: utf-8 -*-
import io, json, os, sys
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r

for si in sys.argv[1:]:
    s = ST.get(si)
    if not s:
        print(f"[{si}] 없음"); continue
    print(f"\n=== {si} {s.get('SkillName','')} ===")
    print(f"  TriggerType={s.get('TriggerType')} TriggerRatio={s.get('TriggerRatio')} TriggerTimeMs={s.get('TriggerTimeMs')}")
    print(f"  CoolTimeMs={s.get('CoolTimeMs')} SkillTargetType={s.get('SkillTargetType')} MaxRangeMm={s.get('MaxRangeMm')}")
    for i, o in enumerate(s.get('Operations') or []):
        if isinstance(o, dict):
            print(f"  op[{i}] Type={o.get('Type')} Values={o.get('Values')} TargetType={o.get('TargetType')} State={o.get('StateName') or o.get('State')}")
