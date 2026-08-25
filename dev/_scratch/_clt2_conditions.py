# $조건 파서 설계용: 본체 클로저의 EnableCondition/TriggerCondition 전수 → 연산자·구조·함수인자 통계.
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
def is_sup(i): return len(i) == 6 and i.startswith('8')
OWN = {'UseSkill','UsePassiveSkill','UseSkillToTarget','UseSkillToTargetInArea','CreateProjectile','FireProjectile','CreateServantProjectile'}
follow_child = defaultdict(list)
for k, s in ST.items():
    if s.get('FollowLevelIndex'): follow_child[str(s['FollowLevelIndex'])].append(k)
def children(si):
    s = ST.get(si); out = set()
    if not s: return out
    for op in (s.get('Operations') or []):
        if isinstance(op, dict) and op.get('Type') in OWN and (op.get('Values') or []):
            t = str(op['Values'][0])
            if t in ST: out.add(t)
    if s.get('NextSkillIndex') and str(s['NextSkillIndex']) in ST: out.add(str(s['NextSkillIndex']))
    return out
seed = [i for i in HS if not is_sup(i)]; seen = set(seed); stack = list(seed)
while stack:
    cur = stack.pop()
    for n in children(cur) | set(follow_child.get(cur, [])):
        if n not in seen: seen.add(n); stack.append(n)
BODY = seen

conds = []
for si in BODY:
    s = ST[si]
    for op in (s.get('Operations') or []):
        if isinstance(op, dict) and op.get('EnableCondition'):
            conds.append(str(op['EnableCondition']))
    tc = s.get('TriggerCondition')
    if isinstance(tc, str): conds.append(tc)
    elif isinstance(tc, list): conds += [str(x) for x in tc]

print('본체 조건식 총:', len(conds), '| 고유:', len(set(conds)))
# 연산자 통계
ops = Counter()
for c in conds:
    for o in ['&&', '||', '>=', '<=', '==', '!=', '>', '<', '+', '-', '*']:
        ops[o] += c.count(o)
print('\n=== 연산자 빈도 ===')
for o, n in ops.most_common():
    if n: print('  {:4} {}'.format(o, n))
# 함수 인자 개수(세미콜론/콤마 구분) 패턴
fnarg = defaultdict(Counter)
for c in conds:
    for m in re.finditer(r'\$(\w+)\(([^)]*)\)', c):
        fn = m.group(1); args = m.group(2)
        n = 0 if not args.strip() else (args.count(';') + args.count(',') + 1)
        fnarg[fn][n] += 1
print('\n=== $함수 인자개수 분포 ===')
for fn in sorted(fnarg, key=lambda x: -sum(fnarg[x].values())):
    print('  {:34} {}'.format(fn, dict(fnarg[fn])))
# 비교 대상(우변) 종류: 숫자 / 함수 / State명
print('\n=== 대표 조건식 15 ===')
for c in list(dict.fromkeys(conds))[:15]:
    print('  ', c[:100])
