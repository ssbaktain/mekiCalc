# clt2 Stage A (유물) — ArtifactTable → 유물 IR.
# 스킬 Stage A(_clt2_stage_a)의 파서(ir_op/summon_of/build_state_defs/parse) 재사용.
# 유물=스탯 기여 소스(직접딜 0). 원본 op raw 보존 + 이름 복호화 조인 + 커버리지 1:1.
# 사용: python _clt2_stage_a_artifact.py [--build | 코드...]
# -*- coding: utf-8 -*-
import json, io, os, sys
from collections import defaultdict
sys.stdout.reconfigure(encoding='utf-8')
DEV = r'C:\Users\MhHs\Documents\project\mekiCalc\dev'
sys.path.insert(0, DEV)
import _clt2_stage_a as SA   # ST/PROJ/STT + ir_op/summon_of/build_state_defs/parse_num 재사용

D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
def load(n): return json.load(io.open(os.path.join(D, n), encoding='utf-8'))

ART = load('ArtifactTable.json')
OPT = {str(r['OptionIndex']): r.get('StatData') for r in load('ArtifactOptionTable.json')}   # 보유효과
HFO = load('ArtifactHiddenForceOptionTable.json')                                            # 잠재(등급별)
PW  = load('ArtifactPowerTable.json')                                                        # 파워(전역공통)
LSF = load('ArtifactLevelStatFactorTable.json')                                              # 성장
TAW = load('ArtifactTotalAwakeTable.json')                                                   # 각성총합→PowerMaxLv
# 시스템 메타(딜무관, 참조)
SLOT = load('ArtifactSlotTable.json'); AWC = load('ArtifactAwakeCostTable.json')
UPG = load('ArtifactUpgradeTable.json'); PLC = load('ArtifactPowerLevelUpCostTable.json')
DEF = load('ArtifactDefuseRewardTable.json'); HFT = load('ArtifactHiddenForceTable.json')
try:
    NAMES = json.load(io.open(os.path.join(DEV, '_clt2_artifact_names.json'), encoding='utf-8'))
except Exception:
    NAMES = {}

GRADE = {'Grade3': 'epic', 'Grade4': 'unique', 'Grade5': 'legend', 'Grade6': 'mythic'}

def trigger_of(s):
    tt = s.get('TriggerTimeMs')
    return {'type': s.get('TriggerType'), 'ratio': SA.parse_num(s.get('TriggerRatio')),
            'periodMs': SA.parse_num(tt[0]) if isinstance(tt, list) and tt else None,
            'condition': SA.parse_condition(s.get('TriggerCondition'))}   # ★컨텐츠/디버프 발동 게이트

def build_artifact(a):
    code = a['ArtifactCode']
    stages = []
    for aw, si in enumerate(a.get('EquipSkillIndices') or []):
        s = SA.ST.get(str(si))
        if not s:
            continue
        st = {'awaken': aw, 'skillIndex': str(si), 'trigger': trigger_of(s),
              'cooldownMs': SA.parse_num(s.get('CoolTimeMs')),          # ★발동 쿨(빈도)
              'targetType': s.get('SkillTargetType'),                   # 효과 대상(Myself/TriggerTarget)
              'ops': [SA.ir_op(op, i) for i, op in enumerate(s.get('Operations') or [])],
              'meta': {'ignoreCc': s.get('IgnoreCc'), 'targetCount': s.get('TargetCount')}}  # 메타(딜무관)
        summ = SA.summon_of(s)
        if summ: st['summon'] = summ
        stages.append(st)
    cols = []
    for oi in (a.get('CollectionOptionIndices') or []):
        sd = OPT.get(str(oi))
        if sd: cols.append({'type': sd.get('Type'), 'value': sd.get('Value'), 'factor': sd.get('Factor')})
    return {'index': a['Index'], 'code': code, 'grade': GRADE.get(a['GradeType']),
            'gradeType': a['GradeType'], 'name': NAMES.get(code, {}).get('name'),
            'equipStages': stages, 'collectionOptions': cols}

def build_full():
    arts = {a['ArtifactCode']: build_artifact(a) for a in ART}
    # ── 전역 ──
    hf_pool = defaultdict(list)
    for h in HFO:
        sd = h.get('StatData') or {}
        hf_pool[h.get('GradeType')].append({'slotIndex': h.get('SlotIndex'), 'type': sd.get('Type'),
                                            'value': sd.get('Value'), 'prob': h.get('Prob')})
    power = [{'type': (r.get('StatData') or {}).get('Type'), 'value': (r.get('StatData') or {}).get('Value'),
              'factor': (r.get('StatData') or {}).get('Factor')} for r in PW]
    growth = {str(r['Level']): r['Factor'] for r in LSF}
    awake_power = {str(r['TotalAwake']): r['ArtifactPowerMaxLevel'] for r in TAW}
    # 유물 grant State 정의(StateTable 조인) — EquipSkill 전 각성 pool
    eq_pool = {str(si) for a in ART for si in (a.get('EquipSkillIndices') or []) if str(si) in SA.ST}
    states = SA.build_state_defs(eq_pool)
    # 잠재 슬롯(등급별 해금)
    hf_slots = {r['GradeType']: {'maxSlot': r.get('MaxSlotCount'), 'reqAwake': r.get('SlotRequiredAwake'),
                                 'changeCosts': r.get('ChangeCosts')} for r in HFT}
    system_meta = {
        'slot': [{'index': r.get('Index'), 'reqMission': r.get('SlotReqMissionIndex')} for r in SLOT],
        'awakeCost': [{'grade': r['GradeType'], 'awake': r['Awake'], 'require': r.get('RequireCount')} for r in AWC],
        'upgrade': [{'from': r['Grade'], 'to': r['UpgradeGradeType'], 'count': r['UpgradeRequiredCount']} for r in UPG],
        'defuse': [{'grade': r['Grade'], 'reward': r['DefuseReward']} for r in DEF],
        'hiddenForceSlots': hf_slots,
    }
    # ── 커버리지 1:1 (EquipSkill 전 op가 IR 반영됐나) ──
    all_ops = {(si, i) for si in eq_pool for i, op in enumerate(SA.ST[si].get('Operations') or []) if isinstance(op, dict)}
    ir_ops = set()
    for a in arts.values():
        for st in a['equipStages']:
            for op in st['ops']:
                ir_ops.add((st['skillIndex'], op['no']))
    for d in states.values():
        for e in d['effects']:
            ir_ops.add((e['skill'], e['opNo']))
    missing = all_ops - ir_ops
    return {'artifacts': arts, 'hiddenForcePool': dict(hf_pool), 'power': power,
            'growth': growth, 'awakePower': awake_power, 'states': states,
            'systemMeta': system_meta}, all_ops, missing

if __name__ == '__main__':
    if sys.argv[1:] == ['--build']:
        ir, all_ops, missing = build_full()
        from collections import Counter
        optypes = Counter()
        for a in ir['artifacts'].values():
            for st in a['equipStages']:
                for op in st['ops']:
                    if op.get('type'): optypes[op['type']] += 1
        print('=== 유물 IR 빌드 ===')
        print(f"유물: {len(ir['artifacts'])} | equipStage op분모: {len(all_ops)} | 미반영: {len(missing)}")
        print(f"잠재풀(등급): {[(k, len(v)) for k, v in ir['hiddenForcePool'].items()]}")
        print(f"파워: {ir['power']} | 유물 State정의: {len(ir['states'])}종")
        print(f"op.type 분포: {dict(optypes.most_common())}")
        # 스킬 게이트 CATEGORY 대조(신규 op 있으면 경고)
        import importlib.util
        SKILL_CATS = {'GetDamageR','GetDamage','GetDotR','GetDot','GetDamageByMaxHpR','SetDamage','ModStat','ModStatR',
            'ModStatOnTick','ModDamagePipelineStat','ModAttrDamagePipelineStat','ModStatAddValue','ModSkillStat','ModSkill',
            'ModSkillR','ModSkillProjectile','ModSkillProjectileR','ModSkillEnabled','ModSkillDisabled','BlockSkill','SetSkill',
            'ChangeSkill','UseSkill','UsePassiveSkill','UseSkillToTarget','UseSkillToTargetInArea','CreateProjectile',
            'FireProjectile','CreateServantProjectile','SetServantSkill','SpawnCamp','SpawnCreatureByTarget','SpawnServant',
            'RemoveCreature','DestroyProjectile','LockProjectileDirection','LockProjectilePosition','TeleportProjectileToTarget',
            'ModProjectileDirectionByAngle','State','ClearState','ModCurrentStateStack','ModStateMaxStack','ModStateDurationR',
            'ModFiredStateMaxStack','Stun','Frozen','Knockback','Dispel','Cleanse','GetHealByMaxHpR','GetHotByMaxHpR',
            'CreateShieldByMaxHpR','GetMpR','ConsumeMpR','ModCoolTime','ModCoolTimeR','ModRemainTriggerTime',
            'SetRemainTriggerTime','ModBattleTime','DashFromMyself','DashToTarget','ManualJump','EnableDoubleJump','EffectOnly',
            'SetExpressionVariable','RemovePassiveSkill','SaveWarpSpot','LoadWarpSpot','GetMotR','GetMtR'}
        newops = set(optypes) - SKILL_CATS
        print(f"신규 op(스킬 카테고리 밖): {newops if newops else '없음 ✅'}")
        if missing:
            tc = Counter(SA.ST[si].get('Operations')[i].get('Type') for si, i in missing)
            print(f"  ⚠️ 미반영 op type: {dict(tc.most_common())}")
        else:
            print("  ✅ 모든 EquipSkill op가 IR에 1:1 반영됨")
        outp = r'C:\Users\MhHs\Documents\project\mekiCalc\data\artifacts_ir.json'
        json.dump(ir, io.open(outp, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print(f"저장: {outp}")
        sys.exit()
    # 샘플 출력
    ir, _, _ = build_full()
    targets = sys.argv[1:] or ['ARTIFACT_4_2', 'ARTIFACT_5_13', 'ARTIFACT_3_4']  # 육각목걸이/불타는용암/어둠의계약서
    for t in targets:
        a = ir['artifacts'].get(t)
        print(f'===== {t} ({a["name"] if a else "?"}) =====')
        print(json.dumps(a, ensure_ascii=False, indent=1)[:1600])
        print()
