# 유물 누락 전수 검수: 12테이블 전 필드 + EquipSkill op 전 필드 커버리지 + 조건부딜 규명
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

# ── 1. 12 테이블 전 필드 인벤토리 ──
TABLES = ['ArtifactTable','ArtifactOptionTable','ArtifactHiddenForceOptionTable','ArtifactHiddenForceTable',
          'ArtifactPowerTable','ArtifactLevelStatFactorTable','ArtifactTotalAwakeTable','ArtifactSlotTable',
          'ArtifactAwakeCostTable','ArtifactUpgradeTable','ArtifactPowerLevelUpCostTable','ArtifactDefuseRewardTable']
print('=== 1. 12테이블 전 필드 인벤토리 ===')
for t in TABLES:
    d = L(t+'.json')
    fields = set()
    for r in d: fields |= set(r.keys())
    print(f'  {t}: {sorted(fields)}')

# ── 2. EquipSkill 노드/op 전 필드 (스킬 게이트 대조) ──
NODE_DONE = {'SkillIndex','SkillTargetType','TargetCount','TriggerType','TriggerRatio','TriggerTimeMs',
             'Operations','DurationTimeMs','TickTimeMs','CoolTimeMs','InitialMinCoolTimeMs','Mp',
             'TriggerCondition','FollowLevelIndex','NextSkillIndex','AttrFlag','AutoTriggerCondition'}
NODE_META = {'ActionName','SkillIconPath','MaxRangeMm','SkillRangeType','IgnoreCc','TriggerFlag',
             'MaxFireRangeMm','RetargetOnFire','RetargetOnDead','InitialMaxCoolTimeMs','SkillNameLocalKey',
             'SkillDescLocalKey','SkillTargetValue','TargetFilterState','TargetFilterBoss'}
OP_DONE = {'Type','TargetType','Values','ValueLevelFactors','MaxHitCount','State','Flag','ParentOperationNo',
           'WatchOperationNo','EnableCondition','DurationMs','ModStats','TickTimeMs','AddStateCount','FireRatio',
           'ChainTargetInfo','ReverseCheck','QueueDelayMs'}
OP_META = {'DelayMs','DistanceDelayMs','RectAreaMm','AreaOffsetMm','RadiusMm','WatchDeltaMs','FireChance'}

eq_all = set()
for a in ART:
    for si in (a.get('EquipSkillIndices') or []):
        if str(si) in ST: eq_all.add(str(si))

node_unmapped = Counter(); op_unmapped = Counter(); optypes = Counter()
for si in eq_all:
    s = ST[si]
    for k in s:
        if k not in NODE_DONE and k not in NODE_META: node_unmapped[k] += 1
    for op in (s.get('Operations') or []):
        if isinstance(op, dict):
            optypes[op.get('Type')] += 1
            for k in op:
                if k not in OP_DONE and k not in OP_META: op_unmapped[k] += 1
print('\n=== 2. EquipSkill 필드 커버리지(스킬 게이트 대조) ===')
print('  미매핑 노드필드:', dict(node_unmapped) if node_unmapped else '없음 ✅')
print('  미매핑 op필드:', dict(op_unmapped) if op_unmapped else '없음 ✅')
print('  op.type:', dict(optypes.most_common()))

# ── 3. ModDamagePipelineStat 30건 = 진짜 딜? (조건부 스탯 vs 딜계수) ──
print('\n=== 3. ModDamagePipelineStat 전건 규명 (딜? 스탯?) ===')
eq2art = {}
for a in ART:
    for si in (a.get('EquipSkillIndices') or []): eq2art[str(si)] = a['ArtifactCode']
seen = set()
for si in sorted(eq_all):
    for op in (ST[si].get('Operations') or []):
        if isinstance(op, dict) and op.get('Type') == 'ModDamagePipelineStat':
            key = (eq2art[si], str(op.get('Values')), str(op.get('EnableCondition')))
            if key in seen: continue
            seen.add(key)
            print(f"  {eq2art[si]} vals={op.get('Values')} cond={op.get('EnableCondition')} flag={op.get('Flag')}")

# ── 4. 보유효과/파워/잠재 스탯 전종 (딜 관련 누락 스탯?) ──
print('\n=== 4. 보유(Option)/파워/잠재 스탯 전종 ===')
def statset(rows, key='StatData'):
    c = Counter()
    for r in rows:
        sd = r.get(key) or {}
        c[str(sd.get('Type'))] += 1
    return c
print('  보유효과 스탯:', dict(statset(L('ArtifactOptionTable.json')).most_common()))
print('  파워 스탯:', dict(statset(L('ArtifactPowerTable.json')).most_common()))
print('  잠재 스탯:', dict(statset(L('ArtifactHiddenForceOptionTable.json')).most_common()))

# ── 5. 각성 6단계가 값만 바뀌나, op 구조/개수도 바뀌나 ──
print('\n=== 5. 각성단계 op 구조 일관성(값만 vs 구조도) ===')
for a in ART:
    eqs = [str(x) for x in (a.get('EquipSkillIndices') or [])]
    sigs = []
    for si in eqs:
        s = ST.get(si, {})
        sig = tuple((op.get('Type'), len(op.get('Values') or [])) for op in (s.get('Operations') or []) if isinstance(op, dict))
        sigs.append(sig)
    consistent = len(set(sigs)) == 1
    if not consistent:
        print(f'  ⚠️ {a["ArtifactCode"]}: 각성단계별 op구조 상이! {sigs}')
print('  (위에 출력 없으면 전 유물 각성=값만 변경, 구조 동일 ✅)')
