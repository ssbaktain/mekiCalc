# 9030400(어둠계약서) descparam + op raw + parse_num 결과 확인 → desc 80% 원인
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
import _clt2_stage_a as SA
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
def load(n): return json.load(io.open(os.path.join(D, n), encoding='utf-8'))
from collections import defaultdict
DP = defaultdict(list)
for r in load('SkillDescParamTable.json'): DP[str(r.get('SkillIndex'))].append(r)

si = '9030400'
s = SA.ST.get(si, {})
print('Operations:')
for i, op in enumerate(s.get('Operations') or []):
    if isinstance(op, dict):
        print(f'  op{i}:', json.dumps(op, ensure_ascii=False))
print('DescParams:')
for p in DP.get(si, []):
    print(f'  P{p.get("ParamNo")} S2={p.get("Step2")} S3={p.get("Step3")} Calc={p.get("Calculate")}')
# parse_num이 dyn 문자열에 뭘 주나
raw = (s.get('Operations') or [None, {}])[1].get('Values', [None,None,None,None])[3]
print(f'\nraw values[3] = {raw!r}')
print(f'SA.parse_num(raw) = {SA.parse_num(raw)!r}')
