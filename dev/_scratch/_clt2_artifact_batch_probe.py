# 물병·양초·동맹증표·레인디어·비밀지도·고대의책·순환 op/child/func 정밀 — 배치 수정 근거
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
import _clt2_stage_a as SA
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
ART = json.load(io.open(os.path.join(D, 'ArtifactTable.json'), encoding='utf-8'))
BY = {a['ArtifactCode']: a for a in ART}
NAMES = {'ARTIFACT_5_15':'물병','ARTIFACT_5_17':'양초','ARTIFACT_5_18':'동맹증표','ARTIFACT_5_21':'레인디어',
         'ARTIFACT_5_22':'비밀지도','ARTIFACT_5_5':'고대의책'}

def dump(si, depth=0, seen=None):
    if seen is None: seen=set()
    s=SA.ST.get(si)
    if not s or si in seen: return
    seen.add(si)
    tr={k:s.get(k) for k in ['TriggerType','TriggerTimeMs','TriggerRatio','TriggerCondition','CoolTimeMs'] if s.get(k) is not None}
    print('  '*depth+f'{si} {json.dumps(tr,ensure_ascii=False)}')
    for i,op in enumerate(s.get('Operations') or []):
        if not isinstance(op,dict): continue
        keep={k:op.get(k) for k in ['Type','Values','DurationMs','DelayMs','TickTimeMs','State','TriggerCondition'] if op.get(k) is not None}
        print('  '*depth+f'  op{i} {json.dumps(keep,ensure_ascii=False)}')
        if op.get('Type')=='UsePassiveSkill':
            for cv in (op.get('Values') or []):
                if str(cv).isdigit(): dump(str(cv),depth+2,seen)

for code in ['ARTIFACT_5_5','ARTIFACT_5_15','ARTIFACT_5_17','ARTIFACT_5_18','ARTIFACT_5_21','ARTIFACT_5_22']:
    a=BY[code]; sis=a.get('EquipSkillIndices') or []
    print(f'\n===== {code} ({NAMES[code]}) aw0={sis[0]} / aw5={sis[5]} =====')
    print('--aw0--'); dump(str(sis[0]))
    print('--aw5--'); dump(str(sis[5]))
