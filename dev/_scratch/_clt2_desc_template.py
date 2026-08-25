# SKILL_DESC + SkillDescParamTable → descTemplate(우리 변수+단위포맷+초록bold).
# {N} → <b>{var|fmt}</b>. 변수=op/field 매핑, fmt=Calculate([Div,10]=pct·[Div,1000]=sec).
# -*- coding: utf-8 -*-
import json, io, os, sys, re
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
from _clt_decrypt import decrypt, kv
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
def load(n): return json.load(io.open(os.path.join(D, n), encoding='utf-8'))
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r
from collections import defaultdict
DP = defaultdict(list)
for r in load('SkillDescParamTable.json'): DP[str(r.get('SkillIndex'))].append(r)

def fmt_of(calc):
    if calc == ['Div', '10']: return '|pct'
    if calc == ['Div', '100']: return '|pct100'
    if calc == ['Div', '1000']: return '|sec'
    return ''

def var_of(si, param):
    s2 = param.get('Step2') or []; s3 = param.get('Step3') or []
    field = s3[0] if s3 else None
    pos = s3[1] if len(s3) > 1 else None
    opno = int(s2[1]) if len(s2) > 1 and str(s2[1]).lstrip('-').isdigit() else None
    # 대상 op의 Type(딜 vs 도트 구분)
    optype = None
    if opno is not None:
        ops = ST.get(si, {}).get('Operations') or []
        if 0 <= opno < len(ops) and isinstance(ops[opno], dict): optype = ops[opno].get('Type')
    isdot = optype in ('GetDotR', 'GetDot')
    if field == 'MaxHitCount': return 'targetCount'
    if field == 'DurationMs': return 'dotDuration' if isdot else 'duration'
    if field == 'TickTimeMs': return 'dotTick'
    if field == 'Values':
        if pos == '0': return 'dotCoeff' if isdot else 'coeff'
        if pos == '2': return 'hits'
        return f'v{pos}'
    if field == 'MaxProjectileCount': return 'projCount'
    return (field or 'x').lower()

def make_template(si):
    k = 'SKILL_DESC_' + si
    if k not in kv: return None
    raw = decrypt(k, kv.get(k))
    if not raw: return None
    # 색상/스타일 태그 제거([#White] [#Red] [-] 등) — 변수는 어차피 <b>로 감쌈
    raw = re.sub(r'\[#\w+\]|\[-\]', '', raw)
    # {N} → <b>{var|fmt}</b>
    for p in DP.get(si, []):
        n = str(p.get('ParamNo'))
        var = var_of(si, p); fmt = fmt_of(p.get('Calculate'))
        raw = raw.replace('{' + n + '}', f'<b class="skv">{{{var}{fmt}}}</b>')
    return raw

if __name__ == '__main__':
    # 나이트로드 계산 IR 스킬 idx
    calc = json.load(io.open(r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_calc\nightlord.json', encoding='utf-8'))
    out = {}
    for sk in calc['skills']:
        si = sk['idx']
        t = make_template(si)
        out[si] = t
        print(f'{si} {sk["name"]}:\n   {t}')
    json.dump(out, io.open(r'C:\Users\MhHs\Documents\project\mekiCalc\dev\_nightlord_desctemplates.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
