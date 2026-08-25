# 동료 IR 전체 검수: round-trip 무결성·재귀 완전성·파싱·State·이름·개방레벨·계승/옵션.
# -*- coding: utf-8 -*-
import json, io, os, sys, re
from collections import Counter, defaultdict
sys.stdout.reconfigure(encoding='utf-8')
BASE = r'C:\Users\MhHs\Documents\project\mekiCalc'
D = os.path.join(BASE, 'textAsset', 'TextAsset', 'TextAsset')
def load(n): return json.load(io.open(os.path.join(D, n), encoding='utf-8'))
ir = json.load(io.open(os.path.join(BASE, 'data', 'supporters_ir.json'), encoding='utf-8'))
SUP = load('SupporterTable.json')
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r
PROJ = {str(r['ProjectileIndex']): r for r in load('SkillProjectileTable.json')}
STT = {str(r.get('StateName')): r for r in load('StateTable.json')}
sups = ir['supporters']
fails = []
def chk(c, m):
    if not c: fails.append(m)

# ── 1. 동료 완전성(61·이름·계승·옵션) ──
chk(len(sups) == len(SUP) == 61, f"동료 수 {len(sups)} vs {len(SUP)}")
name_null = [c for c, s in sups.items() if not s.get('name')]
inherit_bad = [c for c, s in sups.items() if not s.get('inherit', {}).get('levelFactor')]
print('=== 1. 동료 완전성 ===')
print(f"  61종 IR: {'✅' if len(sups)==61 else '❌'} | 이름 null: {name_null or '없음 ✅'}")
print(f"  계승 필드 누락: {inherit_bad or '없음 ✅'} | 계승 factor 레벨: {len(ir['inheritFactor'])}")

# ── 2. round-trip: 각 동료 스킬(명시+재귀) IR 노드화 + op values 원본 일치 ──
def children_of(si):
    out = []
    for o in (ST.get(si, {}).get('Operations') or []):
        if not isinstance(o, dict) or not o.get('Values'): continue
        if o.get('Type') in ('CreateProjectile', 'FireProjectile'):
            p = PROJ.get(str(o['Values'][0]), {}); sk = p.get('SkillIndex')
            for x in (sk if isinstance(sk, list) else [sk] if sk else []):
                if str(x) in ST: out.append(str(x))
        if o.get('Type') in ('UseSkill', 'UsePassiveSkill', 'UseSkillToTarget', 'UseSkillToTargetInArea'):
            for v in o['Values']:
                if str(v) in ST: out.append(str(v)); break
    return out
# 원본 기대 스킬 집합(동료별)
def expect(s):
    roots = ([str(s['EnterSkillIndex'])] if s.get('EnterSkillIndex') else []) + \
            [str(x) for x in (s.get('MainActiveSkillIndices') or [])] + [str(x) for x in (s.get('MainPassiveSkillIndices') or [])]
    if not roots:
        from_ct = None  # Cadet BaseAttack은 CreatureTable(별도), 여기선 스킵
    seen = set(); stk = list(roots)
    while stk:
        c = stk.pop()
        if c in seen: continue
        seen.add(c); stk += children_of(c)
    return seen
# IR에 담긴 스킬 노드 수집
def ir_skills(sup):
    out = {}
    def rec(n):
        if not isinstance(n, dict) or 'skillIndex' not in n: return
        out[n['skillIndex']] = n
        for c in n.get('children', []): rec(c)
    for k in ('basicAttack', 'enterSkill'):
        if sup.get(k): rec(sup[k])
    for lst in ('activeSkills', 'passiveSkills'):
        for n in sup.get(lst, []): rec(n)
    return out
val_mismatch = 0; miss_node = 0; openlv_bad = 0
SUPmap = {s['SupporterCode']: s for s in SUP}
for code, sup in sups.items():
    s = SUPmap[code]
    exp = expect(s)
    got = ir_skills(sup)
    # 명시+재귀 스킬 전부 IR에 (Cadet은 BaseAttack 별도라 exp 비면 스킵)
    if exp:
        missing = exp - set(got)
        miss_node += len(missing)
        if missing and len(fails) < 20: fails.append(f"{code} IR 누락 스킬: {sorted(missing)[:3]}")
    # op values 원본 일치
    for si, node in got.items():
        oo = ST.get(si, {}).get('Operations') or []
        for op in node.get('ops', []):
            i = op['no']
            if i < len(oo) and isinstance(oo[i], dict) and oo[i].get('Values') != op.get('values'):
                val_mismatch += 1
                if val_mismatch <= 5: fails.append(f"{code} {si} op{i} values 불일치")
    # 개방레벨 매칭(activeSkills[1:]=oa[1:], passiveSkills=op)
    oa = [int(x) for x in (s.get('OpenActiveSkillLevel') or [])]
    op_ = [int(x) for x in (s.get('OpenPassiveSkillLevel') or [])]
    act = sup.get('activeSkills', [])
    if oa and len(act) != len(oa) - 1: openlv_bad += 1  # [0]은 basicAttack
    if len(sup.get('passiveSkills', [])) != len(op_): openlv_bad += 1
print('\n=== 2. round-trip 무결성 ===')
print(f"  명시+재귀 스킬 IR 누락: {miss_node}건 {'✅' if miss_node==0 else '❌'}")
print(f"  op values 원본 불일치: {val_mismatch}건 {'✅' if val_mismatch==0 else '❌'}")
print(f"  개방레벨 구조 불일치: {openlv_bad}건 {'✅' if openlv_bad==0 else '❌'}")

# ── 3. 파싱 raw fallback + State 무결성 + 옵션 null ──
raw_cnt = 0; state_miss = set(); opt_null = 0
def scan_cond(ct):
    global raw_cnt
    if not isinstance(ct, dict): return
    if 'raw' in ct: raw_cnt += 1
    for k in ('and', 'or'):
        for x in ct.get(k, []): scan_cond(x)
    for k in ('left', 'right'):
        if k in ct: scan_cond(ct[k])
def scan_node(n):
    global opt_null
    if not isinstance(n, dict): return
    if (n.get('trigger') or {}).get('condition'): scan_cond(n['trigger']['condition'])
    for op in n.get('ops', []):
        if op.get('condTree'): scan_cond(op['condTree'])
        if op.get('dyn'): scan_cond(op['dyn'])
        st = op.get('state')
        if st and str(st) not in STT and re.sub(r'_\d+$', '', str(st)) not in STT: state_miss.add(str(st))
    for c in n.get('children', []): scan_node(c)
for sup in sups.values():
    for k in ('basicAttack', 'enterSkill'):
        if sup.get(k): scan_node(sup[k])
    for lst in ('activeSkills', 'passiveSkills'):
        for n in sup.get(lst, []): scan_node(n)
    for o in sup.get('collectionOptions', []) + sup.get('equipOptions', []):
        if o.get('type') is None: opt_null += 1
print('\n=== 3. 파싱·State·옵션 ===')
print(f"  파싱 raw fallback: {raw_cnt}건 {'✅' if raw_cnt==0 else '⚠️'}")
print(f"  State 정의 없음: {len(state_miss)}건 {'✅' if not state_miss else state_miss}")
print(f"  옵션 type null: {opt_null}건 {'✅' if opt_null==0 else '❌'}")

# ── 4. 자식전용 딜 42개 IR 포함 확인 ──
all_deal = set()
for s in SUP:
    for si in expect(s):
        if any(isinstance(o, dict) and o.get('Type') in ('GetDamageR', 'GetDotR', 'GetDamageByMaxHpR') for o in (ST[si].get('Operations') or [])):
            all_deal.add(si)
ir_all_skills = set()
for sup in sups.values(): ir_all_skills |= set(ir_skills(sup))
deal_missing = all_deal - ir_all_skills
print('\n=== 4. 딜 완전성(자식전용 포함) ===')
print(f"  딜 스킬 {len(all_deal)}개 중 IR 누락: {len(deal_missing)}건 {'✅' if not deal_missing else deal_missing}")

print('\n' + '='*40)
print(f'❌ 검수 실패 {len(fails)}건:' if fails else '✅✅ 동료 IR 검수 통과 — 무결성·재귀·파싱·State 완전')
for f in fails[:15]: print('  ', f)
