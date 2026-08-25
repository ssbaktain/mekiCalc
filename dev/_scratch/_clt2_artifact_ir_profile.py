# 유물 IR 전수 프로파일 + 원본 대비 누락(특히 노드레벨 필드) 검수.
# -*- coding: utf-8 -*-
import json, io, os, sys
from collections import Counter, defaultdict
sys.stdout.reconfigure(encoding='utf-8')
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
def load(n): return json.load(io.open(os.path.join(D, n), encoding='utf-8'))
ir = json.load(io.open(r'C:\Users\MhHs\Documents\project\mekiCalc\data\artifacts_ir.json', encoding='utf-8'))
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r

# ── 1. IR 필드 인벤토리 (모든 계층) ──
art_fields = Counter(); stage_fields = Counter(); op_fields = Counter()
op_types = Counter(); op_target = Counter(); op_flag = Counter(); op_state = Counter()
op_dur = Counter(); func_names = Counter(); dyn_fns = Counter(); cond_fns = Counter()
trig_types = Counter(); trig_period = Counter(); col_stats = Counter()
modstat_stats = Counter(); modstatr_stats = Counter(); modpipe_stats = Counter()
summon_fields = Counter()

def walk_cond(ct, acc):
    if not isinstance(ct, dict): return
    if 'fn' in ct: acc.add(ct['fn'])
    for k in ('and', 'or'):
        for x in ct.get(k, []): walk_cond(x, acc)
    for k in ('left', 'right'):
        if k in ct: walk_cond(ct[k], acc)

for code, a in ir['artifacts'].items():
    for k in a: art_fields[k] += 1
    for st in a['equipStages']:
        for k in st: stage_fields[k] += 1
        trig_types[(st.get('trigger') or {}).get('type')] += 1
        trig_period[str((st.get('trigger') or {}).get('periodMs'))] += 1
        for s in st.get('summon', []):
            for k in s: summon_fields[k] += 1
        for op in st['ops']:
            for k in op: op_fields[k] += 1
            op_types[op.get('type')] += 1
            if op.get('target'): op_target[op['target']] += 1
            if op.get('flag'):
                for f in str(op['flag']).split(','): op_flag[f.strip()] += 1
            if op.get('state'): op_state[str(op['state'])] += 1
            if op.get('durationMs'): op_dur[str(op['durationMs'])] += 1
            if op.get('func'): func_names[op['func'].get('func')] += 1
            if op.get('dyn'):
                acc = set(); walk_cond(op['dyn'], acc)
                for fn in acc: dyn_fns[fn] += 1
            if op.get('condTree'):
                acc = set(); walk_cond(op['condTree'], acc)
                for fn in acc: cond_fns[fn] += 1
            vals = op.get('values') or []
            if op.get('type') == 'ModStat' and vals: modstat_stats[str(vals[0])] += 1
            if op.get('type') == 'ModStatR' and vals: modstatr_stats[str(vals[0])] += 1
            if op.get('type') == 'ModDamagePipelineStat' and len(vals) >= 3: modpipe_stats[str(vals[2])] += 1
    for c in a['collectionOptions']:
        col_stats[c.get('type')] += 1

def show(t, c, n=40):
    print(f'\n--- {t} ({len(c)}종) ---')
    for k, v in c.most_common(n): print(f'   {k}: {v}')

print('=== 유물 IR 전수 프로파일 ===')
show('유물 노드 필드', art_fields)
show('equipStage 필드', stage_fields)
show('op 필드', op_fields)
show('op.type', op_types)
show('op.target', op_target)
show('op.flag', op_flag)
show('op.state(상위)', op_state, 20)
show('op.durationMs 값', op_dur)
show('trigger.type', trig_types)
show('trigger.periodMs 값', trig_period)
show('#func 종류', func_names)
show('$dyn fn 종류', dyn_fns)
show('condTree $fn 종류', cond_fns)
show('ModStat 대상스탯', modstat_stats)
show('ModStatR 대상스탯', modstatr_stats)
show('ModDamagePipelineStat 대상스탯', modpipe_stats)
show('보유효과 스탯', col_stats)
show('summon 필드', summon_fields)

# ── 2. 원본 EquipSkill 노드 필드 vs IR stage 담긴 필드 (노드레벨 누락) ──
print('\n\n=== 2. 유물 원본 노드필드 → IR 반영 여부 (노드레벨 누락 검수) ===')
# IR stage가 실제 추출하는 원본키(수정 후)
STAGE_COVERS = {'SkillIndex','TriggerType','TriggerRatio','TriggerTimeMs','TriggerCondition','Operations',
                'CoolTimeMs','SkillTargetType','IgnoreCc','TargetCount'}
orig_node_fields = Counter(); orig_node_vals = defaultdict(Counter)
eq_all = [str(si) for a in load('ArtifactTable.json') for si in (a.get('EquipSkillIndices') or []) if str(si) in ST]
for si in eq_all:
    s = ST[si]
    for k, v in s.items():
        orig_node_fields[k] += 1
        if k not in STAGE_COVERS:
            orig_node_vals[k][str(v)[:40]] += 1
print('원본 EquipSkill 노드 전 필드:', dict(orig_node_fields))
if orig_node_vals:
    print('★IR 미반영 원본 노드필드(누락):')
    for k, c in orig_node_vals.items():
        print(f'   {k}: {dict(c.most_common(6))}')
else:
    print('★유물 노드필드 100% IR 반영 ✅')

# ── 3. 스킬 IR도 노드필드 실제반영 검증 (게이트 맹점 보강: 분류가 아니라 실제 담김) ──
print('\n=== 3. 스킬 본체 원본 노드필드 → build_node 반영 여부 ===')
import sys as _s; _s.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
import _clt2_stage_a as SA
HS = {str(r['SkillIndex']) for r in load('HeroSkillTable.json')}
# build_node가 s.get()으로 읽는 원본키 전부
SKILL_READS = {'SkillIndex','CoolTimeMs','InitialMinCoolTimeMs','AttrFlag','TriggerFlag','AutoTriggerCondition',
    'ActionName','SkillIconPath','MaxRangeMm','SkillRangeType','IgnoreCc','Mp','DurationTimeMs','TickTimeMs',
    'TriggerType','TriggerRatio','TriggerTimeMs','TriggerCondition','TargetCount','Operations',
    'FollowLevelIndex','NextSkillIndex','SkillTargetType','MaxFireRangeMm','RetargetOnFire','RetargetOnDead',
    'SkillTargetValue','InitialMaxCoolTimeMs'}  # own_children/gate가 쓰는 관계키 + 대상/메타 포함
# 본체 클로저(게이트와 동일)
seen = {x for x in HS if not SA.is_sup(x)}; stk = list(seen)
while stk:
    c = stk.pop()
    for x in SA.own_children(c):
        if x not in seen: seen.add(x); stk.append(x)
skill_miss = defaultdict(Counter)
for si in seen:
    for k, v in ST.get(si, {}).items():
        if k not in SKILL_READS:
            skill_miss[k][str(v)[:40]] += 1
if skill_miss:
    print('★스킬 IR 미반영 원본 노드필드(누락 후보):')
    for k, c in skill_miss.items():
        print(f'   {k}: {sum(c.values())}건 {dict(c.most_common(4))}')
else:
    print('★스킬 노드필드 100% 반영 ✅')
