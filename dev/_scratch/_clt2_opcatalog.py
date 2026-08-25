# 새 파이프라인(clt2) 1단계 도구: 79종 op 전수 카탈로그 + IR 카테고리 매핑.
# 원본 SkillTable op 각각의 빈도·Values 구조·부가필드를 뽑아 IR 처리 규칙 설계 근거로.
# -*- coding: utf-8 -*-
import json, io, os, sys
from collections import Counter, defaultdict
sys.stdout.reconfigure(encoding='utf-8')
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in json.load(io.open(os.path.join(D, fn), encoding='utf-8')):
        ST[str(r.get('SkillIndex'))] = r

info = defaultdict(lambda: {'n': 0, 'vals': [], 'fields': Counter()})
for s in ST.values():
    for op in (s.get('Operations') or []):
        if not isinstance(op, dict):
            continue
        t = op.get('Type')
        d = info[t]
        d['n'] += 1
        if len(d['vals']) < 3 and op.get('Values') is not None:
            d['vals'].append(op.get('Values'))
        for k in op:
            if k not in ('Type', 'TargetType', 'Values'):
                d['fields'][k] += 1

# IR 카테고리 매핑 초안 (확실치 않은 건 미분류)
CAT = {
 'GetDamageR': '딜', 'GetDamage': '딜', 'SetDamage': '딜', 'GetDamageByMaxHpR': '딜(체력비례)',
 'GetDotR': '딜(도트)', 'GetDot': '딜(도트)',
 'ModStat': '스탯변경', 'ModStatR': '스탯변경', 'ModStatOnTick': '스탯변경',
 'ModAttrDamagePipelineStat': '스탯변경', 'ModDamagePipelineStat': '스탯변경',
 'ModSkillStat': '스킬강화', 'ModSkill': '스킬강화', 'ModSkillR': '스킬강화',
 'ModSkillProjectile': '스킬강화', 'ModSkillProjectileR': '스킬강화',
 'ModSkillEnabled': '스킬제어', 'ModSkillDisabled': '스킬제어', 'BlockSkill': '스킬제어',
 'SetSkill': '스킬제어', 'ChangeSkill': '스킬제어',
 'UseSkill': '스킬호출', 'UsePassiveSkill': '스킬호출', 'UseSkillToTarget': '스킬호출',
 'UseSkillToTargetInArea': '스킬호출',
 'CreateProjectile': '투사체', 'FireProjectile': '투사체', 'ModProjectileDirectionByAngle': '투사체',
 'DestroyProjectile': '투사체', 'LockProjectileDirection': '투사체', 'LockProjectilePosition': '투사체',
 'TeleportProjectileToTarget': '투사체', 'CreateServantProjectile': '투사체',
 'State': '상태', 'ClearState': '상태', 'ModCurrentStateStack': '상태',
 'ModStateMaxStack': '상태', 'ModStateDurationR': '상태', 'ModFiredStateMaxStack': '상태',
 'Stun': 'CC', 'Frozen': 'CC', 'Knockback': 'CC', 'Dispel': '정화', 'Cleanse': '정화',
 'GetHealByMaxHpR': '회복', 'GetHotByMaxHpR': '회복', 'CreateShieldByMaxHpR': '실드',
 'GetMpR': '자원', 'ConsumeMpR': '자원',
 'SpawnCamp': '소환', 'SpawnCreatureByTarget': '소환', 'SpawnServant': '소환',
 'SpawnSupportCreature': '소환', 'SetServantSkill': '소환', 'RemoveCreature': '소환',
 'DashFromMyself': '이동', 'DashToTarget': '이동', 'ManualJump': '이동', 'EnableDoubleJump': '이동',
 'ModCoolTime': '쿨타임', 'ModCoolTimeR': '쿨타임', 'ModRemainTriggerTime': '쿨타임',
 'SetRemainTriggerTime': '쿨타임', 'ModBattleTime': '시간',
 'EffectOnly': '연출', 'None': '무효', 'SetExpressionVariable': '연출',
}

print('=== 79종 op -> IR 카테고리 (미분류=??? 규명필요) ===')
un = []
for t, _ in sorted(info.items(), key=lambda x: -x[1]['n']):
    cat = CAT.get(t, '??? 규명필요')
    if cat.startswith('???'):
        un.append(t)
    v = info[t]['vals'][:1]
    print('  {:30} {:4}  [{}]  vals={}'.format(str(t), info[t]['n'], cat, v))
print()
print('=== 미분류/불확실 op ({} 종) ==='.format(len(un)))
for t in un:
    print('  {:28} n={}  vals={}  fields={}'.format(t, info[t]['n'], info[t]['vals'][:2], dict(info[t]['fields'].most_common(5))))
