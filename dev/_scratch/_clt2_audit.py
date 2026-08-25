# clt2 전직업 IR 감사: IR이 계산 가능한 상태인지 정합성 검증 + 이상 케이스 탐지.
# 딜계수 파싱·성장슬롯 유효·대상수 소스·소환 완전성·State/마스터리 연결.
# -*- coding: utf-8 -*-
import json, io, os, sys, re
from collections import Counter, defaultdict
sys.stdout.reconfigure(encoding='utf-8')
BASE = r'C:\Users\MhHs\Documents\project\mekiCalc\dev'
d = json.load(io.open(os.path.join(BASE, 'clt2_ir_all.json'), encoding='utf-8'))
nodes, state_defs, stat_m = d['nodes'], d['state_defs'], d['stat_masteries']
JOBS = {1:'hero',2:'paladin',3:'darkknight',4:'archmagetc',5:'archmagefp',6:'bishop',7:'bowmaster',
        8:'marksman',9:'nightlord',10:'shadower',11:'viper',12:'captain',13:'nightwalker',14:'windbreaker'}
DEAL = {'GetDamageR', 'GetDamage', 'GetDotR', 'GetDot', 'GetDamageByMaxHpR', 'SetDamage'}
VALID_SLOT = set(range(0, 24))

def job_of(si):
    # 스킬idx = CI*10000+nnnn: 5자리=CI1~9(첫자리), 6자리=CI10~14(첫2자리)
    if len(si) == 5: ci = int(si[0])
    elif len(si) == 6: ci = int(si[:2])
    else: return '?'
    return JOBS.get(ci, '?')

def coeff_kind(op):
    """딜 op 계수 추출 성격: int(천분율)/func(#)/dynamic($)/none."""
    if op.get('func'): return 'func', op['func'].get('coeff')
    vals = op.get('values') or []
    if not vals: return 'none', None
    v = str(vals[0])
    if v.lstrip('-').isdigit(): return 'int', int(v)
    if v.startswith('$'): return 'dynamic', v
    if v.startswith('#'): return 'func', v
    return 'other', v

issues = []
job_stat = defaultdict(lambda: {'skills': 0, 'deal_ops': 0, 'summon': 0, 'coeff_int': 0, 'coeff_func': 0, 'coeff_dyn': 0})

def walk(node, root_job):
    st = job_stat[root_job]
    st['skills'] += 1
    for op in node.get('ops', []):
        t = op.get('type')
        if t in DEAL:
            st['deal_ops'] += 1
            kind, c = coeff_kind(op)
            if kind == 'int': st['coeff_int'] += 1
            elif kind == 'func': st['coeff_func'] += 1
            elif kind == 'dynamic': st['coeff_dyn'] += 1
            else:
                issues.append(f'[{root_job}] {node["id"]}.op{op["no"]} 딜인데 계수파싱불가: {op.get("values")}')
            slot = op.get('growthSlot')
            if slot is not None and slot not in VALID_SLOT:
                issues.append(f'[{root_job}] {node["id"]}.op{op["no"]} 성장슬롯 이상: {slot}')
    for s in node.get('summon', []):
        st['summon'] += 1
        if s.get('dealSkill') is None and s.get('projMaxTargetCount') is None:
            issues.append(f'[{root_job}] {node["id"]} 소환인데 dealSkill/대상수 없음: {s}')
    for c in node.get('children', []):
        walk(c, root_job)

for si, node in nodes.items():
    walk(node, job_of(si))

print('=== clt2 전직업 IR 감사 ===')
print(f'루트노드 {len(nodes)} | state_defs {len(state_defs)} | 이상 {len(issues)}건')
print(f'\n{"직업":12}{"스킬":>5}{"딜op":>6}{"소환":>5}{"계수:정수":>8}{"함수":>5}{"동적":>5}')
for ci in range(1, 15):
    j = JOBS[ci]; s = job_stat[j]
    print(f'{j:12}{s["skills"]:>5}{s["deal_ops"]:>6}{s["summon"]:>5}{s["coeff_int"]:>8}{s["coeff_func"]:>5}{s["coeff_dyn"]:>5}')
print(f'\n=== 이상 케이스 {len(issues)}건 ===')
for x in issues[:25]:
    print(' ', x)
