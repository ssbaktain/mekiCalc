# 유물 IR 설계 확정 2탄: UsePassiveSkill 스택·컨텐츠 condTree·잠재확률·시스템 메타
# -*- coding: utf-8 -*-
import json, io, os, sys
from collections import Counter, defaultdict
sys.stdout.reconfigure(encoding='utf-8')
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
def L(fn): return json.load(io.open(os.path.join(D, fn), encoding='utf-8'))
ART = L('ArtifactTable.json')
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in L(fn): ST[str(r.get('SkillIndex'))] = r

# 전 유물 EquipSkill 스킬인덱스 모음 (각성 6단계 전부)
eq_all = set()
for a in ART:
    for si in (a.get('EquipSkillIndices') or []):
        if str(si) in ST: eq_all.add(str(si))

print('=== ④ EquipSkill op 전종 분포 ===')
optypes = Counter(); opstats = Counter(); trig = Counter()
enablecond = []
for si in eq_all:
    s = ST[si]
    trig[s.get('TriggerType')] += 1
    for op in (s.get('Operations') or []):
        if not isinstance(op, dict): continue
        t = op.get('Type'); optypes[t] += 1
        if t in ('ModStat','ModStatR','ModStatOnTick') and op.get('Values'):
            opstats[str(op['Values'][0])] += 1
        if op.get('EnableCondition'):
            enablecond.append((si, t, op.get('EnableCondition'), op.get('Values')))
print('op.type:', dict(optypes.most_common()))
print('\nModStat 대상 스탯:', dict(opstats.most_common()))
print('\nEquipSkill TriggerType:', dict(trig))

print('\n=== ⑤ 컨텐츠 분류용 EnableCondition(조건부 발동 = 계산대상) ===')
print(f'조건부 op {len(enablecond)}건 (중복스킬 각성포함)')
seen=set()
for si,t,ec,vals in enablecond:
    key=(t,str(ec))
    if key in seen: continue
    seen.add(key)
    print(f'  {si} {t} cond={ec} vals={vals}')

print('\n=== ⑥ UsePassiveSkill 연계(스택 중첩 추적) ===')
for si in sorted(eq_all):
    s = ST[si]
    for op in (s.get('Operations') or []):
        if isinstance(op, dict) and op.get('Type')=='UsePassiveSkill':
            child = str(op['Values'][0]) if op.get('Values') else None
            cs = ST.get(child)
            info=[]
            if cs:
                for cop in (cs.get('Operations') or []):
                    if isinstance(cop,dict):
                        info.append(f"{cop.get('Type')}{cop.get('Values')} st={cop.get('State')} mh={cop.get('MaxHitCount')}")
                st_max = cs.get('StateMaxStack') or cs.get('MaxStack')
            print(f'  {si} -> UsePassive {child}: {info}')

print('\n=== ⑦ 잠재 확률(Prob) 분포 ===')
HF = L('ArtifactHiddenForceOptionTable.json')
print('필드:', list(HF[0].keys()))
probs = Counter(); slots = Counter(); gr = Counter()
hfstats = Counter()
for h in HF:
    probs[str(h.get('Prob'))] += 1
    slots[str(h.get('SlotIndex'))] += 1
    gr[h.get('GradeType')] += 1
    sd = h.get('StatData') or {}
    hfstats[str(sd.get('Type'))] += 1
print('Prob 분포:', dict(probs.most_common()))
print('SlotIndex 분포:', dict(slots))
print('Grade 분포:', dict(gr))
print('잠재 스탯종류:', dict(hfstats.most_common()))

print('\n=== ⑧ 시스템 메타 테이블 구조 ===')
for fn in ['ArtifactSlotTable','ArtifactTotalAwakeTable','ArtifactAwakeCostTable',
           'ArtifactUpgradeTable','ArtifactPowerLevelUpCostTable','ArtifactDefuseRewardTable',
           'ArtifactPowerTable','ArtifactLevelStatFactorTable','ArtifactOptionTable']:
    d = L(fn+'.json')
    print(f'\n--- {fn} ({len(d)}행) 필드={list(d[0].keys()) if d else "빈"}')
    for r in d[:3]:
        print('   ', {k:(str(v)[:60]) for k,v in r.items()})
