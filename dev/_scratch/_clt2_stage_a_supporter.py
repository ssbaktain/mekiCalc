# clt2 Stage A (동료) — SupporterTable → 동료 IR.
# 스킬 Stage A(_clt2_stage_a) 파서(ir_op/summon_of/build_state_defs/parse) 재사용.
# 동료=소환개체(계승). SupporterTable 명시(Enter/MainActive/MainPassive+개방레벨)+다단계 재귀트리.
# 사용: python _clt2_stage_a_supporter.py [--build | 코드...]
# -*- coding: utf-8 -*-
import json, io, os, sys
from collections import defaultdict
sys.stdout.reconfigure(encoding='utf-8')
DEV = r'C:\Users\MhHs\Documents\project\mekiCalc\dev'
sys.path.insert(0, DEV)
import _clt2_stage_a as SA   # ST/PROJ/STT + ir_op/summon_of/build_state_defs/parse_num

D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
def load(n): return json.load(io.open(os.path.join(D, n), encoding='utf-8'))
SUP = load('SupporterTable.json')
OPT = {str(r['OptionIndex']): r.get('StatData') for r in load('SupporterOptionTable.json')}
LSF = load('SupporterLevelStatFactorTable.json')
CT = {str(r['CreatureIndex']): r for r in load('CreatureTable.json')}
DESC_KEYS = set(x.get('Key') for x in load('Localization_ko.json') if x.get('Key'))
try:
    NAMES = json.load(io.open(os.path.join(DEV, '_clt2_supporter_names.json'), encoding='utf-8'))
except Exception:
    NAMES = {}
GRADE = {'Grade1': 'cadet', 'Grade2': 'rare', 'Grade3': 'epic', 'Grade4': 'unique', 'Grade5': 'legend'}

def children_of(si):
    out = []
    for o in (SA.ST.get(si, {}).get('Operations') or []):
        if not isinstance(o, dict) or not o.get('Values'):
            continue
        if o.get('Type') in ('CreateProjectile', 'FireProjectile'):
            p = SA.PROJ.get(str(o['Values'][0]), {}); sk = p.get('SkillIndex')
            for x in (sk if isinstance(sk, list) else [sk] if sk else []):
                if str(x) in SA.ST: out.append(str(x))
        if o.get('Type') in ('UseSkill', 'UsePassiveSkill', 'UseSkillToTarget', 'UseSkillToTargetInArea'):
            for v in o['Values']:
                if str(v) in SA.ST: out.append(str(v)); break
    return out

def skill_node(si, seen, openLevel=None):
    si = str(si)
    s = SA.ST.get(si)
    if not s:
        return {'skillIndex': si, 'missing': True}
    tt = s.get('TriggerTimeMs')
    node = {'skillIndex': si,
            'actionName': s.get('ActionName'),                              # 영문 코드명(무명 대체 식별)
            'descKey': ('SKILL_DESC_' + si) if ('SKILL_DESC_' + si) in DESC_KEYS else None,
            'replaceBaseAttack': 'ReplaceBaseAttack' in str(s.get('TriggerFlag') or ''),
            'targetType': s.get('SkillTargetType'),
            'cooldownMs': SA.parse_num(s.get('CoolTimeMs')),
            'durationMs': SA.parse_num(s.get('DurationTimeMs')),
            'tickMs': (SA.parse_num(s.get('TickTimeMs')[0]) if isinstance(s.get('TickTimeMs'), list) and s.get('TickTimeMs') else None),
            'trigger': {'type': s.get('TriggerType'), 'ratio': SA.parse_num(s.get('TriggerRatio')),
                        'periodMs': (SA.parse_num(tt[0]) if isinstance(tt, list) and tt else None),
                        'condition': SA.parse_condition(s.get('TriggerCondition'))},
            'ops': [SA.ir_op(op, i) for i, op in enumerate(s.get('Operations') or [])]}
    if openLevel is not None:
        node['openLevel'] = openLevel
    summ = SA.summon_of(s)
    if summ: node['summon'] = summ
    if si not in seen:                                                       # 재귀(다단계, 순환방지)
        seen2 = seen | {si}
        kids = [skill_node(c, seen2) for c in children_of(si)]
        if kids: node['children'] = kids
    return node

def build_supporter(s):
    code = s['SupporterCode']
    ma = [str(x) for x in (s.get('MainActiveSkillIndices') or [])]
    oa = [int(x) for x in (s.get('OpenActiveSkillLevel') or [])]
    mp = [str(x) for x in (s.get('MainPassiveSkillIndices') or [])]
    op = [int(x) for x in (s.get('OpenPassiveSkillLevel') or [])]
    en = s.get('EnterSkillIndex')
    ci = str(s['CreatureIndex'])
    node = {'index': s['SupporterIndex'], 'code': code, 'class': s['ClassType'],
            'grade': GRADE.get(s['GradeType']), 'gradeType': s['GradeType'],
            'name': NAMES.get(code, {}).get('name'),
            'creatureIndex': ci, 'spawnDurationMs': SA.parse_num(s.get('SpawnDurationTimeMs')),
            'inherit': {'baseRatio': SA.parse_num(s.get('LinkedAttackStatBaseRatio')),
                        'levelFactor': SA.parse_num(s.get('LinkedAttackStatLevelFactor'))}}
    # 평타: MainActive[0](ReplaceBaseAttack) / Cadet은 CreatureTable BaseAttack
    if ma:
        node['basicAttack'] = skill_node(ma[0], set(), oa[0] if oa else None)
        node['activeSkills'] = [skill_node(ma[i], set(), oa[i] if i < len(oa) else None) for i in range(1, len(ma))]
    else:
        ba = CT.get(ci, {}).get('BaseAttack')
        node['basicAttack'] = skill_node(str(ba['SkillIndex']), set()) if ba and ba.get('SkillIndex') else None
        node['activeSkills'] = []
    if en: node['enterSkill'] = skill_node(str(en), set())                  # 소환딜
    node['passiveSkills'] = [skill_node(mp[i], set(), op[i] if i < len(op) else None) for i in range(len(mp))]
    node['collectionOptions'] = [{'type': (OPT.get(str(x)) or {}).get('Type'), 'value': (OPT.get(str(x)) or {}).get('Value'),
                                  'factor': (OPT.get(str(x)) or {}).get('Factor')} for x in (s.get('CollectionOptionIndices') or [])]
    node['equipOptions'] = [{'type': (OPT.get(str(x)) or {}).get('Type'), 'value': (OPT.get(str(x)) or {}).get('Value'),
                             'factor': (OPT.get(str(x)) or {}).get('Factor')} for x in (s.get('EquipOptionIndices') or [])]
    return node

def all_skill_ids(s):
    """커버리지: 이 동료가 참조하는 전 스킬(명시 루트 + 재귀 자식)"""
    roots = ([str(s['EnterSkillIndex'])] if s.get('EnterSkillIndex') else []) + \
            [str(x) for x in (s.get('MainActiveSkillIndices') or [])] + \
            [str(x) for x in (s.get('MainPassiveSkillIndices') or [])]
    if not roots:
        ba = CT.get(str(s['CreatureIndex']), {}).get('BaseAttack')
        if ba and ba.get('SkillIndex'): roots = [str(ba['SkillIndex'])]
    seen = set(); stk = list(roots)
    while stk:
        c = stk.pop()
        if c in seen: continue
        seen.add(c)
        stk += children_of(c)
    return seen

def build_full():
    sups = {s['SupporterCode']: build_supporter(s) for s in SUP}
    inherit_factor = {str(r['Level']): r['Factor'] for r in LSF}
    # 동료 State 정의(StateTable 조인)
    pool = set()
    for s in SUP: pool |= all_skill_ids(s)
    states = SA.build_state_defs(pool)
    ir = {'supporters': sups, 'inheritFactor': inherit_factor, 'states': states}
    # 커버리지 1:1
    all_ops = {(si, i) for si in pool for i, op in enumerate(SA.ST[si].get('Operations') or []) if isinstance(op, dict)}
    ir_ops = set()
    def collect(node):
        if not isinstance(node, dict): return
        for op in node.get('ops', []): ir_ops.add((node['skillIndex'], op['no']))
        for c in node.get('children', []): collect(c)
    for sup in sups.values():
        for key in ('basicAttack', 'enterSkill'):
            if sup.get(key): collect(sup[key])
        for lst in ('activeSkills', 'passiveSkills'):
            for n in sup.get(lst, []): collect(n)
    for d in states.values():
        for e in d['effects']: ir_ops.add((e['skill'], e['opNo']))
    missing = all_ops - ir_ops
    return ir, all_ops, missing

if __name__ == '__main__':
    if sys.argv[1:] == ['--build']:
        ir, all_ops, missing = build_full()
        from collections import Counter
        print('=== 동료 IR 빌드 ===')
        print(f"동료: {len(ir['supporters'])} | 스킬 op 분모: {len(all_ops)} | 미반영: {len(missing)}")
        print(f"State 정의: {len(ir['states'])}종 | 계승 factor 레벨: {len(ir['inheritFactor'])}")
        # op 커버리지(신규 op)
        newops = set()
        for sup in ir['supporters'].values():
            def scan(n):
                if not isinstance(n, dict): return
                for op in n.get('ops', []):
                    if op.get('type'): newops.add(op['type'])
                for c in n.get('children', []): scan(c)
            for k in ('basicAttack', 'enterSkill'):
                if sup.get(k): scan(sup[k])
            for lst in ('activeSkills', 'passiveSkills'):
                for n in sup.get(lst, []): scan(n)
        if missing:
            tc = Counter(SA.ST[si].get('Operations')[i].get('Type') for si, i in missing)
            print(f"  ⚠️ 미반영 op: {dict(tc.most_common())}")
        else:
            print("  ✅ 모든 스킬 op가 IR에 1:1 반영됨(재귀 포함)")
        outp = r'C:\Users\MhHs\Documents\project\mekiCalc\data\supporters_ir.json'
        json.dump(ir, io.open(outp, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print(f"저장: {outp}")
        sys.exit()
    ir, _, _ = build_full()
    targets = sys.argv[1:] or ['SUPPORTER_9_4']
    for t in targets:
        s = ir['supporters'].get(t)
        print(f'===== {t} ({s["name"] if s else "?"}) =====')
        print(json.dumps(s, ensure_ascii=False, indent=1)[:2000])
