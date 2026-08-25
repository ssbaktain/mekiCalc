# _child 6 + _raw 3 유물 자식스킬 재귀 추적 → effects 구성 근거. aw0/aw5 대조.
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
import _clt2_stage_a as SA
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
ART = json.load(io.open(os.path.join(D, 'ArtifactTable.json'), encoding='utf-8'))
STATE = {str(r.get('StateName')): r for r in json.load(io.open(os.path.join(D, 'StateTable.json'), encoding='utf-8'))}
STAT_OPS = {'ModStat', 'ModStatR', 'ModDamagePipelineStat', 'ModAttrDamagePipelineStat'}
BY_CODE = {a['ArtifactCode']: a for a in ART}
NAME = {'ARTIFACT_4_4': '머쉬맘', 'ARTIFACT_4_8': '혼테일', 'ARTIFACT_5_12': '얼음영혼석', 'ARTIFACT_5_14': '세이람',
        'ARTIFACT_5_15': '물병', 'ARTIFACT_5_16': '천도명약', 'ARTIFACT_5_19': '뿔피리', 'ARTIFACT_5_20': '저주인형',
        'ARTIFACT_5_23': '순환의고리'}

def walk(si, depth, seen):
    s = SA.ST.get(si)
    if not s or si in seen or depth > 3: return
    seen.add(si)
    for i, op in enumerate(s.get('Operations') or []):
        if not isinstance(op, dict): continue
        t = op.get('Type')
        if t in STAT_OPS:
            v = op.get('Values')
            state = op.get('State'); ms = STATE.get(str(state), {})
            cond = ms.get('StatType')  # 상태 조건 힌트
            extra = ''
            if op.get('DurationMs') and op.get('DurationMs') != '99999999': extra += f" dur={op.get('DurationMs')}"
            if op.get('DelayMs'): extra += f" delay={op.get('DelayMs')}"
            print(f"{'  '*depth}op{i} {t} {v}{extra} state={state}")
        elif t == 'UsePassiveSkill':
            for cv in (op.get('Values') or []):
                if str(cv).isdigit():
                    print(f"{'  '*depth}op{i} → 자식 {cv}")
                    walk(str(cv), depth+1, seen)
        elif t in ('State', 'ClearState'):
            st = op.get('State') or (op.get('Values') or [None])[0]
            print(f"{'  '*depth}op{i} {t} {st}")

for code in ['ARTIFACT_4_4','ARTIFACT_4_8','ARTIFACT_5_12','ARTIFACT_5_14','ARTIFACT_5_15','ARTIFACT_5_16','ARTIFACT_5_19','ARTIFACT_5_20','ARTIFACT_5_23']:
    a = BY_CODE[code]; sis = a.get('EquipSkillIndices') or []
    print(f'\n########## {code} ({NAME[code]}) ##########')
    for aw in (0, 5):
        if aw < len(sis):
            print(f'--- aw{aw} (si={sis[aw]}) ---')
            walk(str(sis[aw]), 0, set())
