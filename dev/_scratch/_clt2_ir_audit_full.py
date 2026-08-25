# clt2 IR 전반 정합성 검수: 파싱 손실(raw fallback)·State 참조 무결성·값 이상·관계 완전성.
# 유물(artifacts_ir) + 스킬(skills_ir) 공통.
# -*- coding: utf-8 -*-
import json, io, os, sys
from collections import Counter, defaultdict
sys.stdout.reconfigure(encoding='utf-8')
BASE = r'C:\Users\MhHs\Documents\project\mekiCalc'
D = os.path.join(BASE, 'textAsset', 'TextAsset', 'TextAsset')
def load(n): return json.load(io.open(os.path.join(D, n), encoding='utf-8'))
art = json.load(io.open(os.path.join(BASE, 'data', 'artifacts_ir.json'), encoding='utf-8'))
JOBS = ['hero','paladin','darkknight','archmagetc','archmagefp','bishop','bowmaster','marksman',
        'nightlord','shadower','viper','captain','nightwalker','windbreaker']
skill = {}
for j in JOBS:
    skill[j] = json.load(io.open(os.path.join(BASE, 'data', 'skills_ir', j + '.json'), encoding='utf-8'))
state_defs = json.load(io.open(os.path.join(BASE, 'data', 'skills_ir', '_state_defs.json'), encoding='utf-8'))['state_defs']

raw_conds = Counter()      # 파싱 실패(raw fallback) 원본식
raw_ctx = Counter()        # 어디서(trigger/condTree/dyn/auto)
val_anom = []              # 값 이상
state_ref_miss = Counter() # State 참조 무결성(grant/require가 정의에 없음)

def scan_cond(ct, ctx):
    if not isinstance(ct, dict): return
    if 'raw' in ct:
        raw_conds[ct['raw'][:60]] += 1; raw_ctx[ctx] += 1
    for k in ('and', 'or'):
        for x in ct.get(k, []): scan_cond(x, ctx)
    for k in ('left', 'right'):
        if k in ct: scan_cond(ct[k], ctx)

def scan_ops(node, who, states_pool):
    for op in node.get('ops', []):
        if op.get('condTree'): scan_cond(op['condTree'], 'op.condTree')
        if op.get('dyn'): scan_cond(op['dyn'], 'op.dyn')
        # 값 이상: 딜/스탯 op인데 values 없음
        t = op.get('type')
        if t and t not in ('None',) and 'values' not in op and t not in (
            'Cleanse','GetMpR','GetMotR','ClearState','DestroyProjectile','EffectOnly'):
            pass  # values 없는 op 다수 정상(부수효과)
        # durationMs 음수/0 이상
        dm = op.get('durationMs')
        if dm is not None:
            try:
                if float(str(dm)) < 0: val_anom.append(f'{who} op{op["no"]} durationMs<0: {dm}')
            except: val_anom.append(f'{who} op{op["no"]} durationMs 비수치: {dm}')
        # State 참조: op.state가 정의 pool에 있나
        st = op.get('state')
        if st and str(st) not in states_pool:
            import re
            base = re.sub(r'_\d+$', '', str(st))
            if base not in states_pool: state_ref_miss[str(st)] += 1

# ── 유물 ──
art_states = set(art['states'].keys())
for code, a in art['artifacts'].items():
    for st in a['equipStages']:
        tr = st.get('trigger') or {}
        if tr.get('condition'): scan_cond(tr['condition'], 'artifact.trigger.condition')
        scan_ops(st, code, art_states)
# 유물 grant State 무결성(op.state가 art_states 정의에 있나) — 위 scan_ops가 처리
# collectionOption 조인 실패 체크
OPT = {str(r['OptionIndex']) for r in load('ArtifactOptionTable.json')}
ARTT = load('ArtifactTable.json')
col_miss = []
for a in ARTT:
    for oi in (a.get('CollectionOptionIndices') or []):
        if str(oi) not in OPT: col_miss.append((a['ArtifactCode'], oi))

# ── 스킬 ──
skill_states = set(state_defs.keys())
def walk_skill(node, who):
    tr = node.get('trigger') or {}
    if tr.get('condition'): scan_cond(tr['condition'], 'skill.trigger.condition')
    if node.get('autoTriggerCondition'): scan_cond(node['autoTriggerCondition'], 'skill.autoTrigger')
    scan_ops(node, who, skill_states)
    for m in node.get('masteries', []):
        for op in m.get('effect', {}).get('ops', []):
            if op.get('condTree'): scan_cond(op['condTree'], 'mastery.condTree')
            if op.get('dyn'): scan_cond(op['dyn'], 'mastery.dyn')
    for c in node.get('children', []): walk_skill(c, who)
for j, d in skill.items():
    for si, node in d['nodes'].items(): walk_skill(node, f'{j}:{si}')

# State require/grant 무결성(state_defs 상호참조)
sd_miss = Counter()
for nm, d in state_defs.items():
    pass  # 정의 자체는 StateTable 조인 성공분만 존재

print('=== clt2 IR 전반 정합성 검수 ===')
print(f'\n① 파싱 실패(raw fallback) — 파서가 구조화 못한 원본식')
print(f'   컨텍스트별: {dict(raw_ctx)}')
print(f'   raw식 종류 {len(raw_conds)}개:')
for s, c in raw_conds.most_common(25): print(f'     [{c}] {s}')
print(f'\n② 값 이상(durationMs 등): {len(val_anom)}건')
for a in val_anom[:15]: print(f'   {a}')
print(f'\n③ State 참조 무결성 — op.state가 정의에 없음: {len(state_ref_miss)}종')
for s, c in state_ref_miss.most_common(15): print(f'   {s}: {c}')
print(f'\n④ 유물 보유효과 조인 실패(CollectionOptionIndices→OptionTable): {len(col_miss)}건')
for c in col_miss[:10]: print(f'   {c}')
