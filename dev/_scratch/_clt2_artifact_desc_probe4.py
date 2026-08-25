# 실패 유물 descparam/op 원인 규명: 돼지리본(확률/쿨) · 얼음영혼석(905120) · 혼테일(9040.8) · 육각(스택)
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
def load(n): return json.load(io.open(os.path.join(D, n), encoding='utf-8'))
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r
from collections import defaultdict
DP = defaultdict(list)
for r in load('SkillDescParamTable.json'): DP[str(r.get('SkillIndex'))].append(r)

for si in ['9030200', '9051200', '9040800', '9040200']:  # 돼지리본 / 얼음영혼석 / 혼테일 / 육각목걸이
    s = ST.get(si, {})
    print(f'\n===== {si} =====')
    print('  Trigger:', json.dumps({k: s.get(k) for k in ['TriggerType','TriggerTimeMs','TriggerRatio','CoolTimeMs']}, ensure_ascii=False))
    for i, op in enumerate(s.get('Operations') or []):
        if isinstance(op, dict):
            print(f'  op{i}:', json.dumps({k: op.get(k) for k in ['Type','Values','DurationMs','TickTimeMs','State','MaxStack'] if op.get(k) is not None}, ensure_ascii=False))
    for p in DP.get(si, []):
        print(f'  P{p.get("ParamNo")} S1={p.get("Step1")} S2={p.get("Step2")} S3={p.get("Step3")} Calc={p.get("Calculate")}')
