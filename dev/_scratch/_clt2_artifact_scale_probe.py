# window(양초·무지개달팽이)·mpAtLeast(얼음영혼석) 실제 저장값 확인 → 단위 확정
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
import _clt2_stage_a as SA

def dump(si, label):
    s = SA.ST.get(si, {})
    if not s:
        print(f'\n===== {si} ({label}) — 없음 ====='); return
    print(f'\n===== {si} ({label}) =====')
    print('  Trigger:', json.dumps({k: s.get(k) for k in ['TriggerType','TriggerTimeMs','TriggerRatio','TriggerCondition','CoolTimeMs']}, ensure_ascii=False))
    for i, op in enumerate(s.get('Operations') or []):
        if isinstance(op, dict):
            keep = {k: op.get(k) for k in ['Type','Values','DurationMs','TickTimeMs','State','StartDelayMs','DelayMs','Condition'] if op.get(k) is not None}
            print(f'  op{i}:', json.dumps(keep, ensure_ascii=False))

# 양초(전투시작 창: 0/20/40초) + 자식들
for si in ['9051700']:
    dump(si, '양초 aw0')
# 양초 자식스킬 추적(UsePassiveSkill Values)
s = SA.ST.get('9051700', {})
for op in (s.get('Operations') or []):
    if isinstance(op, dict) and op.get('Type') == 'UsePassiveSkill':
        for v in (op.get('Values') or []):
            if str(v).isdigit(): dump(str(v), '양초 자식')

dump('9040100', '무지개달팽이 aw0 (15초)')
dump('9051200', '얼음영혼석 aw0')
dump('9051201', '얼음영혼석 자식(MP문턱/크뎀)')
# 얼음 자식의 자식까지
for parent in ['9051200', '9051201']:
    s = SA.ST.get(parent, {})
    for op in (s.get('Operations') or []):
        if isinstance(op, dict) and op.get('Type') == 'UsePassiveSkill':
            for v in (op.get('Values') or []):
                if str(v).isdigit() and str(v) not in ('9051201',): dump(str(v), f'{parent} 자식')
