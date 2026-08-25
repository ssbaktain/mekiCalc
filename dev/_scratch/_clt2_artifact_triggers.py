# 전 유물 aw0 트리거 타입·조건·op 전수 — 유물 태그 md 트리거 분류 근거(복호화 불필요, 빠름)
# -*- coding: utf-8 -*-
import io, json, os, sys
from collections import Counter, defaultdict
sys.stdout.reconfigure(encoding='utf-8')
DEV = r'C:\Users\MhHs\Documents\project\mekiCalc\dev'
sys.path.insert(0, DEV)
import _clt2_stage_a as SA
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
def load(n): return json.load(io.open(os.path.join(D, n), encoding='utf-8'))
ART = load('ArtifactTable.json')
try:
    NAMES = json.load(io.open(os.path.join(DEV, '_clt2_artifact_names.json'), encoding='utf-8'))
except Exception:
    NAMES = {}

seed = json.load(io.open(r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_calc\artifact_seed.json', encoding='utf-8'))
NM = {a['code']: a['name'] for a in seed['artifacts']}

tc = Counter(); by_trigger = defaultdict(list)
print('=== 유물별 aw0 트리거/조건/op ===')
for a in ART:
    code = a['ArtifactCode']; name = NM.get(code, NAMES.get(code, {}).get('name', code))
    si = str((a.get('EquipSkillIndices') or ['?'])[0])
    s = SA.ST.get(si, {})
    tt = s.get('TriggerType'); tr = s.get('TriggerRatio'); cond = s.get('TriggerCondition'); cd = s.get('CoolTimeMs')
    ops = [op.get('Type') for op in (s.get('Operations') or []) if isinstance(op, dict)]
    tc[tt] += 1; by_trigger[tt].append(f'{code}:{name}')
    extra = []
    if tr and str(tr) != '1000': extra.append(f'ratio={tr}')
    if cd: extra.append(f'cd={cd}')
    if cond: extra.append(f'cond={json.dumps(cond, ensure_ascii=False)}')
    print(f'  {code:14} {name:14} {tt:12} {" ".join(extra)}')
    print(f'                 ops={ops}')

print('\n=== 트리거 타입 분포 ===')
for t, n in tc.most_common():
    print(f'  {t}: {n}  → {", ".join(x.split(":")[1] for x in by_trigger[t])}')
