# clt2 유물 seed 생성 — ArtifactTable → 이름(복호화)+desc(SKILL_DESC 렌더, 0각/5각)+원본 stages.
# 스킬 seed→manual 흐름과 동일: 이 파일은 자동추출 참고본, 유저가 artifact_manual을 손작업.
# 유물=스탯 기여 소스(직접딜 0). 사용: python _clt2_artifact_seed.py
# -*- coding: utf-8 -*-
import json, io, os, sys, re
sys.stdout.reconfigure(encoding='utf-8')
DEV = r'C:\Users\MhHs\Documents\project\mekiCalc\dev'
sys.path.insert(0, DEV)
import _clt2_stage_a as SA            # ST / ir_op / parse_num / parse_condition 재사용
from _clt_decrypt import decrypt, kv  # 이름·SKILL_DESC 복호화

D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
def load(n): return json.load(io.open(os.path.join(D, n), encoding='utf-8'))
ART = load('ArtifactTable.json')
from collections import defaultdict
DP = defaultdict(list)
for r in load('SkillDescParamTable.json'): DP[str(r.get('SkillIndex'))].append(r)
STATE_MAXSTACK = {str(r.get('StateName')): SA.parse_num(r.get('MaxStack')) for r in load('StateTable.json')}
GRADE = {'Grade3': 'epic', 'Grade4': 'unique', 'Grade5': 'legend', 'Grade6': 'mythic'}
SCALAR_FIELDS = {'TriggerRatio', 'CoolTimeMs', 'TargetCount'}   # Step3 없는 스칼라 스킬 필드(확률/쿨/대상수)

def fmt_num(x):
    try: x = float(x)
    except Exception: return str(x)
    return str(int(x)) if x == int(x) else f'{x:g}'

def apply_calc(val, calc):
    """Calculate 체인 적용. ['Const',80,'Div',10]=8, ['Div',1000]=val/1000. 좌→우 (op,operand) 쌍."""
    calc = list(calc or [])
    i = 0
    if calc and calc[0] == 'Const':
        val = float(calc[1]); i = 2
    while i < len(calc):
        op = calc[i]; operand = float(calc[i + 1]) if i + 1 < len(calc) else 0.0
        if op == 'Div':   val = val / operand if operand else val
        elif op == 'Mul': val = val * operand
        elif op == 'Add': val = val + operand
        elif op == 'Sub': val = val - operand
        elif op == 'Const': val = operand
        i += 2
    return val

def resolve(si, p):
    """DescParam 1건 → 채울 문자열 or None(미해결=placeholder 유지).
    Const=리터럴 / TriggerTimeMs·스칼라필드(확률·쿨) / Operations 값 / State→최대중첩. UsePassiveSkill(자식포인터)=미해결.
    Calculate는 체인(Const 80 Div 10=8 등) 전체 적용."""
    calc = p.get('Calculate') or []
    s2 = p.get('Step2'); s3 = p.get('Step3')
    if calc and calc[0] == 'Const':
        return fmt_num(apply_calc(0.0, calc))
    s = SA.ST.get(si, {})
    val = None
    if s2 and s2[0] == 'TriggerTimeMs':
        arr = s.get('TriggerTimeMs') or []
        idx = int(s2[1]) if len(s2) > 1 else 0
        val = SA.parse_num(arr[idx]) if idx < len(arr) else None
    elif s2 and len(s2) == 1 and s2[0] in SCALAR_FIELDS:            # (A) 스칼라 필드(TriggerRatio=확률, CoolTimeMs=쿨)
        val = SA.parse_num(s.get(s2[0]))
    elif s2 and s2[0] == 'Operations':
        ops = s.get('Operations') or []
        opno = int(s2[1]) if len(s2) > 1 else -1
        op = ops[opno] if 0 <= opno < len(ops) and isinstance(ops[opno], dict) else {}
        if op.get('Type') == 'UsePassiveSkill':                    # (C) 자식스킬 포인터 → 미해결
            return None
        field = s3[0] if s3 else None
        if field == 'State':                                       # (B) 상태 최대중첩(StateTable.MaxStack)
            return fmt_num(STATE_MAXSTACK.get(str(op.get('State')))) if STATE_MAXSTACK.get(str(op.get('State'))) is not None else None
        v = op.get(field)
        if isinstance(v, list):
            pos = int(s3[1]) if len(s3) > 1 else 0
            v = v[pos] if pos < len(v) else None
        val = SA.parse_num(v)                                      # 함수형(#...)이면 None
    if val is None:
        return None
    return fmt_num(apply_calc(val, calc))

def fill(template, si):
    txt = template
    for p in sorted(DP.get(si, []), key=lambda p: -int(p.get('ParamNo', 0))):
        r = resolve(si, p)
        if r is not None:
            txt = txt.replace('{' + str(p.get('ParamNo')) + '}', r)   # 미해결은 {N} 그대로(오값 방지)
    return txt

def template_of(si0):
    k = 'SKILL_DESC_' + str(si0)
    if k not in kv: return None
    raw = decrypt(k, kv.get(k))
    if not raw: return None
    return re.sub(r'\[#\w+\]|\[-\]', '', raw)   # 색상 태그 제거

def build_artifact(a):
    code = a['ArtifactCode']
    nk = a.get('NameLocalKey')
    name = decrypt(nk, kv.get(nk)) if nk in kv else None
    sis = [str(x) for x in (a.get('EquipSkillIndices') or []) if str(x) in SA.ST]
    stages = []
    for aw, si in enumerate(sis):
        s = SA.ST[si]
        tt = s.get('TriggerTimeMs')
        stages.append({
            'awaken': aw, 'skillIndex': si,
            'trigger': {'type': s.get('TriggerType'), 'ratio': SA.parse_num(s.get('TriggerRatio')),
                        'periodMs': SA.parse_num(tt[0]) if isinstance(tt, list) and tt else None,
                        'condition': SA.parse_condition(s.get('TriggerCondition'))},
            'cooldownMs': SA.parse_num(s.get('CoolTimeMs')),
            'targetType': s.get('SkillTargetType'),
            'ops': [SA.ir_op(op, i) for i, op in enumerate(s.get('Operations') or []) if isinstance(op, dict)],
        })
    # desc: 템플릿 1회 복호화(각성 공통) → 0각/5각 값으로 채움
    desc = descMax = None
    if sis:
        tmpl = template_of(sis[0])
        if tmpl:
            desc = fill(tmpl, sis[0])
            descMax = fill(tmpl, sis[-1])
    return {'code': code, 'index': a['Index'], 'grade': GRADE.get(a['GradeType']),
            'name': name, 'desc': desc, 'descMax': descMax, 'stages': stages}

if __name__ == '__main__':
    arts = sorted((build_artifact(a) for a in ART), key=lambda x: int(x['index']))
    out = {'_comment': '유물 계산 IR seed(자동추출, 빌드2694). name·desc(0각)·descMax(5각)=클라 SKILL_DESC 렌더. '
                       '스킬 seed→manual과 동일: 이 파일=참고본, artifact_manual=손작업본. 유물=스탯소스(직접딜0).',
           'artifacts': arts}
    outp = r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_calc\artifact_seed.json'
    json.dump(out, io.open(outp, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(f'유물 {len(arts)}종 저장: {outp}')
    for a in arts:
        print(f"  {a['code']:16} [{a['grade']:6}] {a['name']}")
        print(f"      0각: {a['desc']}")
        print(f"      5각: {a['descMax']}")
