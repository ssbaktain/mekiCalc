# 미분류 13종 op이 어느 스킬에 쓰이는지 규명: 본체 클로저 vs 몬스터/보스 vs 특수컨텐츠.
# -*- coding: utf-8 -*-
import json, io, os, sys
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

# 본체 클로저 재계산 (정식 5자리 + 소유 자식)
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
BODY = seen  # 본체 클로저

UNCLASS = ['GetMotR', 'RemovePassiveSkill', 'ChangeCreatureIndex', 'BlockCampSpawn',
           'SetMonsterTier', 'AddCreatureScore', 'ModSight', 'BlockPotion',
           'ModCreatureScale', 'BlockManualInput', 'SaveWarpSpot', 'LoadWarpSpot']

def classify(si):
    if si in BODY:
        return '본체(딜계산대상)'
    if is_sup(si):
        return '동료'
    if si in HS:
        return '정식(비클로저)'
    if len(si) >= 7:
        return '특수대역(7+자리)'
    return '기타(' + str(len(si)) + '자리)'

for op_t in UNCLASS:
    hosts = [si for si, s in ST.items()
             for op in (s.get('Operations') or [])
             if isinstance(op, dict) and op.get('Type') == op_t]
    cats = Counter(classify(si) for si in hosts)
    body_hosts = [si for si in hosts if si in BODY]
    print('{:22} n={:2}  분류={}'.format(op_t, len(hosts), dict(cats)))
    if body_hosts:
        print('   -> 본체 사용 스킬:', body_hosts[:6])
