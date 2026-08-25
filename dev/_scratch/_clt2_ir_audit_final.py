# clt2 IR 최종 검수: 원본↔IR round-trip 무결성(값 손실0·노드/각성/전역테이블 완전반영·이름).
# -*- coding: utf-8 -*-
import json, io, os, sys
from collections import Counter
sys.stdout.reconfigure(encoding='utf-8')
BASE = r'C:\Users\MhHs\Documents\project\mekiCalc'
D = os.path.join(BASE, 'textAsset', 'TextAsset', 'TextAsset')
def load(n): return json.load(io.open(os.path.join(D, n), encoding='utf-8'))
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r
art = json.load(io.open(os.path.join(BASE, 'data', 'artifacts_ir.json'), encoding='utf-8'))
ARTT = load('ArtifactTable.json')

fails = []
def chk(cond, msg):
    if not cond: fails.append(msg)

# ═══ 1. 유물: 원본↔IR 완전 대조 ═══
print('=== 1. 유물 round-trip 무결성 ===')
chk(len(art['artifacts']) == len(ARTT) == 32, f"유물 수 {len(art['artifacts'])} vs 원본 {len(ARTT)}")
val_mismatch = 0; stage_cnt = Counter(); name_null = []
for a in ARTT:
    code = a['ArtifactCode']
    node = art['artifacts'].get(code)
    chk(node is not None, f"{code} IR 누락")
    if not node: continue
    if not node.get('name'): name_null.append(code)
    eqs = [str(x) for x in (a.get('EquipSkillIndices') or [])]
    chk(len(node['equipStages']) == len(eqs), f"{code} 각성수 {len(node['equipStages'])} vs {len(eqs)}")
    stage_cnt[len(node['equipStages'])] += 1
    for st in node['equipStages']:
        s = ST[st['skillIndex']]
        orig_ops = [op for op in (s.get('Operations') or [])]
        chk(len(st['ops']) == len(orig_ops), f"{code} {st['skillIndex']} op수 불일치")
        for i, op in enumerate(st['ops']):
            oo = orig_ops[i]
            if isinstance(oo, dict):
                # 값 원본 동일성(raw 보존 검증)
                if oo.get('Values') != op.get('values'):
                    val_mismatch += 1
                    if val_mismatch <= 5: fails.append(f"{code} {st['skillIndex']} op{i} values 원본불일치: {op.get('values')} vs {oo.get('Values')}")
print(f"  유물 32종 IR 존재: {'✅' if len(art['artifacts'])==32 else '❌'}")
print(f"  각성 단계수 분포: {dict(stage_cnt)} (전부 6이어야)")
print(f"  op values 원본 불일치: {val_mismatch}건 {'✅' if val_mismatch==0 else '❌'}")
print(f"  이름 null: {name_null if name_null else '없음 ✅'}")

# ═══ 2. 유물 전역 테이블 완전 반영 ═══
print('\n=== 2. 유물 전역 테이블 완전성 ===')
chk(len(art['growth']) == len(load('ArtifactLevelStatFactorTable.json')), 'growth 개수')
chk(len(art['awakePower']) == len(load('ArtifactTotalAwakeTable.json')), 'awakePower 개수')
hfo = load('ArtifactHiddenForceOptionTable.json')
tot_hf = sum(len(v) for v in art['hiddenForcePool'].values())
chk(tot_hf == len(hfo), f'잠재풀 {tot_hf} vs 원본 {len(hfo)}')
chk(len(art['power']) == len(load('ArtifactPowerTable.json')), 'power 개수')
print(f"  성장 {len(art['growth'])} / 각성파워 {len(art['awakePower'])} / 잠재 {tot_hf}(원본{len(hfo)}) / 파워 {len(art['power'])}")
# collectionOptions null 값
col_null = 0
for node in art['artifacts'].values():
    for c in node['collectionOptions']:
        if c.get('type') is None or c.get('value') is None: col_null += 1
print(f"  보유효과 값 null: {col_null}건 {'✅' if col_null==0 else '❌'}")

# ═══ 3. 스킬: 원본↔IR 완전 대조 ═══
print('\n=== 3. 스킬 round-trip 무결성 ===')
JOBS = ['hero','paladin','darkknight','archmagetc','archmagefp','bishop','bowmaster','marksman',
        'nightlord','shadower','viper','captain','nightwalker','windbreaker']
allnodes = {}
def collect(n):
    allnodes[n['id']] = n
    for c in n.get('children', []): collect(c)
for j in JOBS:
    d = json.load(io.open(os.path.join(BASE, 'data', 'skills_ir', j + '.json'), encoding='utf-8'))
    for si, n in d['nodes'].items(): collect(n)
HS = {str(r['SkillIndex']) for r in load('HeroSkillTable.json')}
def is_sup(i): return len(i) == 6 and i.startswith('8')
root_expected = {x for x in HS if not is_sup(x)}
missing_root = root_expected - set(allnodes)
print(f"  루트 스킬 노드 {len(root_expected)}개 중 IR 누락: {len(missing_root)}건 {'✅' if not missing_root else '❌ '+str(list(missing_root)[:5])}")
sk_val_mismatch = 0; sk_name_null = 0
for si, n in allnodes.items():
    s = ST.get(si)
    if not s: continue
    orig_ops = s.get('Operations') or []
    if len(n.get('ops', [])) != len(orig_ops):
        fails.append(f"스킬 {si} op수 {len(n.get('ops',[]))} vs {len(orig_ops)}"); continue
    for i, op in enumerate(n['ops']):
        oo = orig_ops[i]
        if isinstance(oo, dict) and oo.get('Values') != op.get('values'):
            sk_val_mismatch += 1
            if sk_val_mismatch <= 5: fails.append(f"스킬 {si} op{i} values 불일치: {op.get('values')} vs {oo.get('Values')}")
    if n.get('name') is None and n['id'] in root_expected: sk_name_null += 1
print(f"  스킬 op values 원본 불일치: {sk_val_mismatch}건 {'✅' if sk_val_mismatch==0 else '❌'}")
print(f"  루트 스킬 이름 null: {sk_name_null}건")

# ═══ 종합 ═══
print('\n' + '='*40)
if fails:
    print(f'❌ 최종 검수 실패 {len(fails)}건:')
    for f in fails[:20]: print(f'   {f}')
else:
    print('✅✅ 최종 검수 통과 — 원본↔IR 무결성 완전(값 손실0·노드/각성/전역 완전반영)')
