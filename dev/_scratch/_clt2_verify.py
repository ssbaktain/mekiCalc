# clt2 IR 전수 검증: 자체 누락(name/계수/성장/소환/마스터리) + skills_v2(검증된 기준) 계수 대조.
# -*- coding: utf-8 -*-
import json, io, os, sys, re
from collections import defaultdict
sys.stdout.reconfigure(encoding='utf-8')
IR = r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_ir'
SV = r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_v2'
JOBS = ['hero','paladin','darkknight','archmagetc','archmagefp','bishop','bowmaster','marksman',
        'nightlord','shadower','viper','captain','nightwalker','windbreaker']
DEAL = {'GetDamageR', 'GetDamage', 'GetDotR', 'GetDot', 'GetDamageByMaxHpR', 'SetDamage'}

# skills_v2 기준: index -> 주 딜 계수(%), name
V2 = {}
for j in JOBS:
    for s in json.load(io.open(os.path.join(SV, j + '.json'), encoding='utf-8'))['skills']:
        dmg = s.get('damage') or []
        coeff = None
        for d in dmg:
            c = d.get('coeff')
            if isinstance(c, (int, float)): coeff = float(c); break
        V2[str(s['index'])] = {'name': s.get('name'), 'coeff': coeff, 'desc': s.get('desc') or ''}

def ir_coeff(op):
    """IR 딜 op 계수(%) = 천분율/10 or #func.coeff/10. dynamic($)은 None."""
    if op.get('func'):
        c = op['func'].get('coeff')
        return c / 10.0 if isinstance(c, (int, float)) else None
    vals = op.get('values') or []
    if vals and str(vals[0]).lstrip('-').isdigit():
        return int(vals[0]) / 10.0
    return None

issues = defaultdict(list)
stat = {'nodes': 0, 'deal_ops': 0, 'checked_coeff': 0}

def walk(node, job):
    stat['nodes'] += 1
    si = node['id']
    if node.get('name') is None:
        issues['name누락'].append(f'[{job}] {si}')
    for op in node.get('ops', []):
        if op.get('type') in DEAL:
            stat['deal_ops'] += 1
            c = ir_coeff(op)
            if c is None and not any(str(v).startswith('$') for v in (op.get('values') or [])):
                issues['계수파싱불가'].append(f'[{job}] {si}.op{op["no"]} {op.get("values")}')
            if op.get('growthSlot') is None and op.get('func') is None:
                issues['성장슬롯없음'].append(f'[{job}] {si}.op{op["no"]}')
            # skills_v2 대조 (같은 스킬, 계수 둘다 있을때)
            v2 = V2.get(si)
            if v2 and v2['coeff'] is not None and c is not None:
                stat['checked_coeff'] += 1
                if abs(c - v2['coeff']) > 0.15:
                    issues['계수불일치(vs v2)'].append(f'[{job}] {si} {v2["name"]}: clt2={c} vs v2={v2["coeff"]}')
    for s in node.get('summon', []):
        if s.get('dealSkill') is None and s.get('projMaxTargetCount') is None:
            issues['소환불완전'].append(f'[{job}] {si} {s}')
    for m in node.get('masteries', []):
        if m.get('name') is None:
            issues['마스터리name누락'].append(f'[{job}] {si} 마스터리{m.get("idx")}')
    for c in node.get('children', []):
        walk(c, job)

for j in JOBS:
    d = json.load(io.open(os.path.join(IR, j + '.json'), encoding='utf-8'))
    for si, node in d['nodes'].items():
        walk(node, j)

print('=== clt2 IR 전수 검증 ===')
print(f'노드 {stat["nodes"]} | 딜op {stat["deal_ops"]} | v2계수대조 {stat["checked_coeff"]}')
print(f'\n이슈 유형별:')
for k in ['name누락', '계수파싱불가', '성장슬롯없음', '소환불완전', '마스터리name누락', '계수불일치(vs v2)']:
    v = issues.get(k, [])
    print(f'  {k}: {len(v)}건')
    for x in v[:10]: print(f'      {x}')
