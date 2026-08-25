# 9030100(죽은자부적 0각) SkillDescParamTable 매핑 + op/trigger 구조 덤프 → desc 필러 설계용
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

for si in ['9030100', '9052100', '9052300']:  # 죽은자부적 / 레인디어창 / 순환의고리
    print(f'\n===== {si} =====')
    s = ST.get(si, {})
    print('trigger:', json.dumps({k: s.get(k) for k in ['TriggerType','TriggerTimeMs','TriggerRatio','TriggerCondition','CoolTimeMs']}, ensure_ascii=False))
    print('Operations:')
    for i, op in enumerate(s.get('Operations') or []):
        if isinstance(op, dict):
            print(f'  op{i}:', json.dumps({k: op.get(k) for k in ['Type','Target','Values','DurationMs','TickTimeMs','State'] if op.get(k) is not None}, ensure_ascii=False))
    print('DescParams:')
    for p in DP.get(si, []):
        print(f'  ParamNo={p.get("ParamNo")} Step1={p.get("Step1")} Step2={p.get("Step2")} Step3={p.get("Step3")} Calc={p.get("Calculate")}')
