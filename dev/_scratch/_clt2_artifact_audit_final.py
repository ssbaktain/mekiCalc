# artifact_manual 전수 점검 — 완성도/이상 플래그
# -*- coding: utf-8 -*-
import io, json, sys
sys.stdout.reconfigure(encoding='utf-8')
A = json.load(io.open(r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_calc\artifact_manual.json', encoding='utf-8'))['artifacts']
DEAL_STATS = {'ATK_PCT','FinalDamage','DMG','CriticalChance','CriticalPower','AttackPowerToBoss','AttackPowerExcludeBoss',
              'BaseAttackPower','SkillPower','MaxDamageRatio','MinDamageRatio','PiercePower','Weakness',
              'AttackSpeed','COOLDOWN_REDUCTION_PCT','SupporterDurationRatio','BuffDurationRatio'}
print(f'총 {len(A)}종\n')
flags = []
for x in A:
    idx, name = x['idx'], x['name']
    for i, e in enumerate(x['effects']):
        for hint in ('_raw','_child','_cltStat','_op'):
            pass  # 힌트는 정상(확정시 삭제용)
        if '_raw' in e: flags.append(f'{idx} {name} eff{i}: _raw 미변환({e.get("stat")})')
        st = e.get('stat')
        if st and st not in DEAL_STATS: flags.append(f'{idx} {name} eff{i}: 미등록 stat "{st}"')
        # 값/파생 둘 다 없으면 이상
        if 'values' not in e and 'derived' not in e: flags.append(f'{idx} {name} eff{i}: values/derived 둘다 없음')
        # values 천분율 정수 체크(음수/과대 허용, None만 플래그)
        if 'values' in e and any(v is None for v in e['values']): flags.append(f'{idx} {name} eff{i}: values에 None')
    if '_child' in x: flags.append(f'{idx} {name}: _child 미해결')

print('=== 유물별 요약 ===')
for x in A:
    tags=[]
    if x.get('content'): tags.append('content='+','.join(x['content']))
    if x.get('trigger'): tags.append('trig='+x['trigger'])
    if x.get('cooldownMs'): tags.append('cd=%d'%x['cooldownMs'])
    if x.get('_defer'): tags.append('DEFER')
    es=[]
    for e in x['effects']:
        c=[k for k in ('bossOnly','bossZero','minEnemies','perEnemy','mpAtLeast','targetMode','derived','dmgMult','maxStacks','individualDur','assumeMax','require','critLt','critGte','critExcess','target','contentMult','chance','durationMs','delayMs') if k in e]
        es.append(f"{e.get('stat')}{'['+str(e['values'][0])+'..'+str(e['values'][5])+']' if 'values' in e else '(derived)'}{'+'+','.join(c) if c else ''}")
    print(f"  {x['idx']:4} {name:16} {' '.join(tags):30} | {' / '.join(es) if es else '(effects 0)'}")

print('\n=== ⚠️ 플래그 ===')
if flags:
    for f in flags: print('  '+f)
else:
    print('  없음 ✅')
print('\neffects 0개:', [x['idx']+':'+x['name'] for x in A if not x['effects']])
