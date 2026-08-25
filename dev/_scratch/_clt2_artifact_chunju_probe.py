# 천도명약(5_16) 부모+자식 트리거/op 전수 → chance·durationMs·구조 규명
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
import _clt2_stage_a as SA

def dump(si):
    s = SA.ST.get(si)
    if not s: print(f'  {si}: 없음'); return
    tr = {k: s.get(k) for k in ['TriggerType','TriggerRatio','TriggerCondition','CoolTimeMs','TriggerTimeMs'] if s.get(k) is not None}
    print(f'  {si}: {json.dumps(tr, ensure_ascii=False)}')
    for i, op in enumerate(s.get('Operations') or []):
        if isinstance(op, dict):
            keep = {k: op.get(k) for k in ['Type','Values','DurationMs','State','DelayMs'] if op.get(k) is not None}
            print(f'      op{i}: {json.dumps(keep, ensure_ascii=False)}')

for aw, base in [(0, '9051600'), (5, '9051650')]:
    print(f'=== aw{aw} 부모 {base} ===')
    dump(base)
    s = SA.ST.get(base, {})
    for op in (s.get('Operations') or []):
        if isinstance(op, dict) and op.get('Type') == 'UsePassiveSkill':
            for v in (op.get('Values') or []):
                if str(v).isdigit():
                    print(f'  -- 자식 {v} --'); dump(str(v))
