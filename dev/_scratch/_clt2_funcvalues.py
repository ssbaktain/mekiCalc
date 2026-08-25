# 함수형 값(#함수) / 동적 값($함수) 전수 규명: 본체 클로저 op Values·조건에서 수집.
# 딜 관여 여부 + SkillDescParamTable 해석 가능성 판별.
# -*- coding: utf-8 -*-
import json, io, os, sys, re
from collections import defaultdict, Counter
sys.stdout.reconfigure(encoding='utf-8')
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in json.load(io.open(os.path.join(D, fn), encoding='utf-8')):
        ST[str(r.get('SkillIndex'))] = r
HS = {str(r['SkillIndex']) for r in json.load(io.open(os.path.join(D, 'HeroSkillTable.json'), encoding='utf-8'))}

def is_sup(i):
    return len(i) == 6 and i.startswith('8')

OWN = {'UseSkill', 'UsePassiveSkill', 'UseSkillToTarget', 'UseSkillToTargetInArea',
       'CreateProjectile', 'FireProjectile', 'CreateServantProjectile'}
follow_child = defaultdict(list)
for k, s in ST.items():
    if s.get('FollowLevelIndex'):
        follow_child[str(s['FollowLevelIndex'])].append(k)

def children(si):
    s = ST.get(si); out = set()
    if not s:
        return out
    for op in (s.get('Operations') or []):
        if isinstance(op, dict) and op.get('Type') in OWN and (op.get('Values') or []):
            t = str(op['Values'][0])
            if t in ST:
                out.add(t)
    if s.get('NextSkillIndex') and str(s['NextSkillIndex']) in ST:
        out.add(str(s['NextSkillIndex']))
    return out

seed = [i for i in HS if not is_sup(i)]
seen = set(seed); stack = list(seed)
while stack:
    cur = stack.pop()
    for n in children(cur) | set(follow_child.get(cur, [])):
        if n not in seen:
            seen.add(n); stack.append(n)
BODY = seen

# SkillDescParamTable: #함수 정의 확인
try:
    dp = json.load(io.open(os.path.join(D, 'SkillDescParamTable.json'), encoding='utf-8'))
    dpkeys = set()
    for r in (dp if isinstance(dp, list) else [dp]):
        for v in r.values():
            if isinstance(v, str):
                for m in re.findall(r'#(\w+)', v):
                    dpkeys.add(m)
except Exception:
    dpkeys = set()

hash_fn = defaultdict(lambda: {'n': 0, 'ops': Counter(), 'ex': [], 'deal': 0})
dollar_fn = defaultdict(lambda: {'n': 0, 'where': Counter(), 'ex': []})
DEAL_OPS = {'GetDamageR', 'GetDamage', 'GetDotR', 'GetDot', 'GetDamageByMaxHpR', 'SetDamage'}

def scan_val(v, optype):
    if not isinstance(v, str):
        return
    for m in re.finditer(r'#(\w+)\(([^)]*)\)', v):
        fn = m.group(1); d = hash_fn[fn]
        d['n'] += 1; d['ops'][optype] += 1
        if optype in DEAL_OPS:
            d['deal'] += 1
        if len(d['ex']) < 2:
            d['ex'].append(m.group(0))
    for m in re.finditer(r'\$(\w+)\(([^)]*)\)', v):
        fn = m.group(1); d = dollar_fn[fn]
        d['n'] += 1; d['where'][optype] += 1
        if len(d['ex']) < 2:
            d['ex'].append(m.group(0))

for si in BODY:
    s = ST[si]
    for op in (s.get('Operations') or []):
        if not isinstance(op, dict):
            continue
        t = op.get('Type')
        for v in (op.get('Values') or []):
            scan_val(v, t)
        scan_val(op.get('EnableCondition'), t + '.EnableCond' if t else 'EnableCond')
    tc = s.get('TriggerCondition')
    if isinstance(tc, str):
        scan_val(tc, 'TriggerCondition')
    elif isinstance(tc, list):
        for x in tc:
            scan_val(x, 'TriggerCondition')

print('본체 클로저 스킬:', len(BODY))
print()
print('=== #함수형 값 (정적 계수/계산식) — {} 종 ==='.format(len(hash_fn)))
for fn, d in sorted(hash_fn.items(), key=lambda x: -x[1]['n']):
    mark = ' <<딜계수!' if d['deal'] else ''
    inparam = 'ParamTbl' if fn in dpkeys else 'NO-DEF'
    print('  {:32} n={:3} deal={:3} [{}]{}  ex={}'.format(fn, d['n'], d['deal'], inparam, mark, d['ex'][:1]))
print()
print('=== $동적 값 (런타임 의존) — {} 종 ==='.format(len(dollar_fn)))
for fn, d in sorted(dollar_fn.items(), key=lambda x: -x[1]['n']):
    print('  {:34} n={:3}  where={}  ex={}'.format(fn, d['n'], dict(d['where'].most_common(3)), d['ex'][:1]))
