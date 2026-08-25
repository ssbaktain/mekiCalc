# clt2 커버리지 게이트: 분모(본체 클로저 + 마스터리 효과스킬)의 모든 op가
# 처리 카테고리에 매핑되는지 검증. 미매핑 op=게이트 실패(빌드 중단 대상).
# Stage A 구현 전 "무엇을 처리해야 하는가"의 완전한 목록이자, 이후 누락 방지 장치.
# -*- coding: utf-8 -*-
import json, io, os, sys
from collections import Counter, defaultdict
sys.stdout.reconfigure(encoding='utf-8')
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in json.load(io.open(os.path.join(D, fn), encoding='utf-8')):
        ST[str(r.get('SkillIndex'))] = r
HS = {str(r['SkillIndex']) for r in json.load(io.open(os.path.join(D, 'HeroSkillTable.json'), encoding='utf-8'))}

def is_sup(i):
    return len(i) == 6 and i.startswith('8')

# 본체 클로저 (설계: 소유 = FollowLevelIndex + OWN op호출, 정식스킬 자식중첩 금지는 IR단계, 여기선 op수집이 목적이라 전부 확장)
OWN = {'UseSkill', 'UsePassiveSkill', 'UseSkillToTarget', 'UseSkillToTargetInArea',
       'CreateProjectile', 'FireProjectile', 'CreateServantProjectile', 'SetServantSkill'}
PROJ = {str(r['ProjectileIndex']): r for r in json.load(io.open(os.path.join(D, 'SkillProjectileTable.json'), encoding='utf-8'))}
fc = defaultdict(list)
for k, s in ST.items():
    if s.get('FollowLevelIndex'):
        fc[str(s['FollowLevelIndex'])].append(k)

def children(si):
    s = ST.get(si); o = set()
    if not s:
        return o
    for op in (s.get('Operations') or []):
        if isinstance(op, dict) and op.get('Type') in OWN and (op.get('Values') or []):
            for v in op['Values']:
                if str(v) in ST:
                    o.add(str(v)); break
            # ProjectileTable 경유 딜스킬
            if op.get('Type') in ('CreateProjectile', 'FireProjectile'):
                pi = str(op['Values'][0])
                if pi in PROJ and PROJ[pi].get('SkillIndex'):
                    for x in (PROJ[pi]['SkillIndex'] if isinstance(PROJ[pi]['SkillIndex'], list) else [PROJ[pi]['SkillIndex']]):
                        if str(x) in ST:
                            o.add(str(x))
    if s.get('NextSkillIndex') and str(s['NextSkillIndex']) in ST:
        o.add(str(s['NextSkillIndex']))
    return o

seed = [i for i in HS if not is_sup(i)]
seen = set(seed); st = list(seed)
while st:
    c = st.pop()
    for n in children(c) | set(fc.get(c, [])):
        if n not in seen:
            seen.add(n); st.append(n)
# 마스터리 효과스킬 추가
mt = json.load(io.open(os.path.join(D, 'HeroSkillMasteryTable.json'), encoding='utf-8'))
msi = {str(r.get('SkillIndex')) for r in mt if str(r.get('SkillIndex')) in ST}
POOL = seen | msi

# ── op 처리 카테고리 (설계 _clt2_DESIGN.md 반영) ──
CATEGORY = {
 # 딜생성
 'GetDamageR': 'deal', 'GetDamage': 'deal', 'GetDotR': 'deal', 'GetDot': 'deal',
 'GetDamageByMaxHpR': 'deal', 'SetDamage': 'deal',
 # 스탯변경(딜증폭/버프디버프)
 'ModStat': 'stat', 'ModStatR': 'stat', 'ModStatOnTick': 'stat',
 'ModDamagePipelineStat': 'stat', 'ModAttrDamagePipelineStat': 'stat', 'ModStatAddValue': 'stat',
 # 스킬강화/제어
 'ModSkillStat': 'skillmod', 'ModSkill': 'skillmod', 'ModSkillR': 'skillmod',
 'ModSkillProjectile': 'skillmod', 'ModSkillProjectileR': 'skillmod',
 'ModSkillEnabled': 'skillctrl', 'ModSkillDisabled': 'skillctrl', 'BlockSkill': 'skillctrl',
 'SetSkill': 'skillctrl', 'ChangeSkill': 'skillctrl',
 # 스킬호출/소환
 'UseSkill': 'invoke', 'UsePassiveSkill': 'invoke', 'UseSkillToTarget': 'invoke', 'UseSkillToTargetInArea': 'invoke',
 'CreateProjectile': 'summon', 'FireProjectile': 'summon', 'CreateServantProjectile': 'summon',
 'SetServantSkill': 'summon', 'SpawnCamp': 'summon', 'SpawnCreatureByTarget': 'summon',
 'SpawnServant': 'summon', 'RemoveCreature': 'summon',
 # 투사체 제어
 'DestroyProjectile': 'projctrl', 'LockProjectileDirection': 'projctrl', 'LockProjectilePosition': 'projctrl',
 'TeleportProjectileToTarget': 'projctrl', 'ModProjectileDirectionByAngle': 'projctrl',
 # 상태
 'State': 'state', 'ClearState': 'state', 'ModCurrentStateStack': 'state', 'ModStateMaxStack': 'state',
 'ModStateDurationR': 'state', 'ModFiredStateMaxStack': 'state',
 # CC/정화
 'Stun': 'cc', 'Frozen': 'cc', 'Knockback': 'cc', 'Dispel': 'cleanse', 'Cleanse': 'cleanse',
 # 회복/실드/자원
 'GetHealByMaxHpR': 'recover', 'GetHotByMaxHpR': 'recover', 'CreateShieldByMaxHpR': 'shield',
 'GetMpR': 'resource', 'ConsumeMpR': 'resource',
 # 쿨타임/이동/연출
 'ModCoolTime': 'cooldown', 'ModCoolTimeR': 'cooldown', 'ModRemainTriggerTime': 'cooldown',
 'SetRemainTriggerTime': 'cooldown', 'ModBattleTime': 'time',
 'DashFromMyself': 'move', 'DashToTarget': 'move', 'ManualJump': 'move', 'EnableDoubleJump': 'move',
 'EffectOnly': 'fx', 'None': 'noop', 'SetExpressionVariable': 'fx',
 'RemovePassiveSkill': 'util', 'SaveWarpSpot': 'util', 'LoadWarpSpot': 'util',
}
# 본체무관(몬스터/보스/특수컨텐츠) — 화이트리스트 명시 제외
WHITELIST = {'SetMonsterTier', 'AddCreatureScore', 'ModCreatureScale', 'ModSight', 'BlockPotion',
             'ChangeCreatureIndex', 'GetMotR', 'BlockCampSpawn', 'BlockManualInput', 'GetMtR'}

total = 0
mapped = 0
unmapped = Counter()
unmapped_skills = defaultdict(set)
for si in POOL:
    for op in (ST[si].get('Operations') or []):
        if not isinstance(op, dict):
            total += 1; mapped += 1; continue  # null op = noop
        t = op.get('Type')
        total += 1
        if t is None:  # Type 없는 op(Flag만, 예 {"Flag":"IsSticky"}) = 무효 noop
            mapped += 1; continue
        if t in CATEGORY or t in WHITELIST:
            mapped += 1
        else:
            unmapped[t] += 1
            unmapped_skills[t].add(si)

# ── 필드 커버리지: 원본 노드/op 필드가 IR반영/메타/무관 중 하나에 매핑되나 (필드 누락 감지) ──
NODE_DONE = {'SkillIndex','SkillTargetType','TargetCount','TriggerType','TriggerRatio','TriggerTimeMs',
             'Operations','DurationTimeMs','TickTimeMs','CoolTimeMs','InitialMinCoolTimeMs','Mp',
             'TriggerCondition','FollowLevelIndex','NextSkillIndex','AttrFlag','AutoTriggerCondition'}
NODE_META = {'ActionName','SkillIconPath','MaxRangeMm','SkillRangeType','IgnoreCc','TriggerFlag',
             'MaxFireRangeMm','RetargetOnFire','RetargetOnDead','InitialMaxCoolTimeMs','SkillNameLocalKey',
             'SkillDescLocalKey','SkillTargetValue','TargetFilterState','TargetFilterBoss'}
OP_DONE = {'Type','TargetType','Values','ValueLevelFactors','MaxHitCount','State','Flag','ParentOperationNo',
           'WatchOperationNo','EnableCondition','DurationMs','ModStats','TickTimeMs','AddStateCount','FireRatio',
           'ChainTargetInfo','ReverseCheck','QueueDelayMs'}   # 딜 세부 + 시전시간(DPS빈도) 담김
OP_META = {'DelayMs','DistanceDelayMs','RectAreaMm','AreaOffsetMm','RadiusMm','WatchDeltaMs','FireChance'}  # 범위/op내지연=딜무관/시뮬
node_unmapped = Counter(); op_unmapped = Counter()
for si in POOL:
    s = ST[si]
    for k in s:
        if k not in NODE_DONE and k not in NODE_META: node_unmapped[k] += 1
    for op in (s.get('Operations') or []):
        if isinstance(op, dict):
            for k in op:
                if k not in OP_DONE and k not in OP_META: op_unmapped[k] += 1

print('=== clt2 커버리지 게이트 ===')
print('--- 필드 커버리지(누락 사각지대) ---')
print(f'미분류 노드필드: {dict(node_unmapped)}' if node_unmapped else '노드필드 100% 매핑 ✅')
print(f'미분류 op필드: {dict(op_unmapped)}' if op_unmapped else 'op필드 100% 매핑 ✅')
print()
print(f'분모 스킬: {len(POOL)} (본체 {len(seen)} + 마스터리효과 {len(msi)})')
print(f'전체 op: {total} | 매핑: {mapped} | 미매핑: {total-mapped}')
print(f'커버리지: {mapped*100/total:.2f}%')
if unmapped:
    print('\n❌ 미매핑 op (처리규칙 없음 → 게이트 실패):')
    for t, n in unmapped.most_common():
        print(f'  {t}: {n}회  (예: {list(unmapped_skills[t])[:3]})')
else:
    print('\n✅ 게이트 통과: 모든 op가 처리 카테고리/화이트리스트에 매핑됨')
