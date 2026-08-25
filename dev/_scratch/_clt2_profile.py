# clt2 IR 전수 프로파일링: 모든 노드/op 필드 종류·값 분포·특이값 조사.
# -*- coding: utf-8 -*-
import json, io, os, sys
from collections import Counter, defaultdict
sys.stdout.reconfigure(encoding='utf-8')
IR = r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_ir'
JOBS = ['hero','paladin','darkknight','archmagetc','archmagefp','bishop','bowmaster','marksman',
        'nightlord','shadower','viper','captain','nightwalker','windbreaker']

node_fields = Counter()
node_null = Counter()              # 필드가 null인 횟수
trigger_types = Counter()
trigger_ratio = Counter()
op_types = Counter()
op_fields = Counter()
op_target = Counter()
op_flag = Counter()
growth_slots = Counter()
maxhit = Counter()
op_state = Counter()               # op.state 값
summon_field_vals = defaultdict(Counter)
mastery_kind = Counter()
mastery_effstat = Counter()
states_keys = Counter()
states_names = Counter()
boost_type = Counter()
cond_fns = Counter()
func_names = Counter()
node_types = Counter()
depth_dist = Counter()
anomalies = []
NUM = 0

def collect_cond(ct, acc):
    if not isinstance(ct, dict): return
    if 'fn' in ct: acc.add(ct['fn'])
    for key in ('and', 'or'):
        for x in ct.get(key, []): collect_cond(x, acc)
    for key in ('left', 'right'):
        if key in ct: collect_cond(ct[key], acc)

def walk(node, depth, job):
    global NUM
    NUM += 1
    depth_dist[depth] += 1
    node_types[node.get('type')] += 1
    for k, v in node.items():
        node_fields[k] += 1
        if v is None: node_null[k] += 1
    tr = node.get('trigger') or {}
    trigger_types[tr.get('type')] += 1
    trigger_ratio[str(tr.get('ratio'))] += 1
    # 특이값: name 없는데 children/summon 있는 루트급? (자식은 정상)
    if node.get('cooldownMs') and node.get('cooldownMs') > 300000:
        anomalies.append(f'[{job}] {node["id"]} 쿨탐 과대: {node["cooldownMs"]}')
    for op in node.get('ops', []):
        for k in op: op_fields[k] += 1
        op_types[op.get('type')] += 1
        if op.get('target'): op_target[op['target']] += 1
        if op.get('flag'):
            for f in str(op['flag']).split(','): op_flag[f.strip()] += 1
        if op.get('growthSlot') is not None:
            growth_slots[op['growthSlot']] += 1
            if not (0 <= op['growthSlot'] <= 23): anomalies.append(f'[{job}] {node["id"]}.op{op["no"]} 성장슬롯 범위밖: {op["growthSlot"]}')
        if op.get('maxHitCount') is not None: maxhit[str(op['maxHitCount'])] += 1
        if op.get('state'): op_state[str(op['state'])] += 1
        ct = op.get('condTree')
        if ct:
            acc = set(); collect_cond(ct, acc)
            for fn in acc: cond_fns[fn] += 1
        if op.get('func'): func_names[op['func'].get('func')] += 1
    for s in node.get('summon', []):
        for k, v in s.items(): summon_field_vals[k][str(v)] += 1
    for m in node.get('masteries', []):
        mastery_kind[m.get('kind')] += 1
        if m.get('kind') == 'stat': mastery_effstat[m.get('effect', {}).get('type')] += 1
    stx = node.get('states') or {}
    for k in stx: states_keys[k] += 1
    for k in ('grant', 'require', 'consume'):
        for nm in stx.get(k, []): states_names[nm] += 1
    for b in node.get('boosts_in', []):
        boost_type[b.get('type')] += 1
    for c in node.get('children', []):
        walk(c, depth + 1, job)

for j in JOBS:
    d = json.load(io.open(os.path.join(IR, j + '.json'), encoding='utf-8'))
    for si, node in d['nodes'].items():
        walk(node, 0, j)

def show(title, counter, n=30):
    print(f'\n=== {title} ({len(counter)}종) ===')
    for k, v in counter.most_common(n): print(f'  {k}: {v}')

print(f'총 노드(children 재귀): {NUM}')
show('노드 필드 인벤토리', node_fields)
print('\n=== 노드 필드 null 빈도 ===')
for k, v in node_null.most_common(): print(f'  {k}: {v} null')
show('노드 type', node_types)
show('트리 depth 분포', depth_dist)
show('trigger.type', trigger_types)
show('trigger.ratio', trigger_ratio, 12)
show('op 필드 인벤토리', op_fields)
show('op.type', op_types, 80)
show('op.target', op_target)
show('op.flag', op_flag)
show('growthSlot 값 분포', growth_slots)
show('maxHitCount 값 분포', maxhit)
show('op.state 값(상위)', op_state, 15)
show('마스터리 kind', mastery_kind)
show('마스터리 stat 종류', mastery_effstat)
show('states 키', states_keys)
show('states 참조 State명(상위)', states_names, 15)
show('boosts_in op type', boost_type)
show('조건 $함수', cond_fns)
show('#함수', func_names)
print('\n=== summon 하위 필드 값 ===')
for k, c in summon_field_vals.items():
    print(f'  {k}: {dict(c.most_common(6))}')
print(f'\n=== 특이값/이상 ({len(anomalies)}건) ===')
for a in anomalies[:20]: print(' ', a)
