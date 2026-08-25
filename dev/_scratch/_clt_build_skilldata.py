# 빌드: skill_master/ci{N}_full.json → data/skills_v2/{job}.json (엔진 소비 정본, 배포용)
# 5분류 유지 + 마스터리 effect 정제(피해%/타수+/대상+/쿨감/최종뎀 등) + 이름/설명.
# 사용: python _clt_build_skilldata.py [CI]  (인자 없으면 전 14직업)
# -*- coding: utf-8 -*-
import io, json, os, re, sys
sys.stdout.reconfigure(encoding='utf-8')

def target_count(tc):
    """대상 수 정규화. 숫자→int(광역), BaseAttackMaxHitCount→'basic'(평타대상수 스탯), 기타→1."""
    if tc is None: return 1
    s = str(tc)
    if 'BaseAttackMaxHitCount' in s: return 'basic'
    try: return int(float(s))
    except (ValueError, TypeError): return 1   # StateCount 등 동적 함수형은 근사 1

def trigger(s):
    """발동 메커니즘 정규화. type(Active/OnTimer/OnAttack…)·proc%·주기ms·공속비례 여부."""
    tt = s.get('triggerType')
    out = {'type': tt}
    tr = s.get('triggerRatio')
    if tr not in (None, '0', '0.0', 0):
        try: out['procPct'] = float(tr) / 10.0     # TriggerRatio ÷10 = 발동 확률%
        except (ValueError, TypeError): pass
    ttm = s.get('triggerTimeMs')
    if ttm:
        v = str(ttm[0] if isinstance(ttm, list) else ttm)
        out['fastByAtkSpeed'] = 'FastByAttackSpeed' in v
        m = re.search(r'(\d+)', v)
        if m: out['periodMs'] = int(m.group(1))
    # sim2 발동 게이팅(SCHEMA_PATCH §1): requireState('State:N')·consumeRequire·counterEvery(N회마다).
    if s.get('requireState'): out['requireState'] = s['requireState']
    if s.get('consumeRequire'): out['consumeRequire'] = True
    if s.get('counterEvery'): out['counterEvery'] = s['counterEvery']; out['counterOn'] = s.get('counterOn')
    return out
SRC = "skill_master"
OUT = r"C:\Users\MhHs\Documents\project\mekiCalc\data\skills_v2"
os.makedirs(OUT, exist_ok=True)

JOBS = {'1':'hero','2':'paladin','3':'darkknight','4':'archmagetc','5':'archmagefp','6':'bishop',
        '7':'bowmaster','8':'marksman','9':'nightlord','10':'shadower','11':'viper','12':'captain',
        '13':'nightwalker','14':'windbreaker'}

def num(v):
    try: return float(v)
    except: return None

def mastery_norm(e):
    """마스터리 effect → {type, value, ...} 정제. 미지원은 raw 유지."""
    if not e: return {'type': 'none'}
    k = e.get('kind')
    key = e.get('key') or e.get('stat')
    v = num(e.get('value'))
    if k == 'skillStat' and key == 'GiveDamagePower1':
        return {'type': 'dmgPct', 'value': v / 10 if v is not None else None}      # 피해량 +N%
    if k == 'modSkill':
        if key == 'Value':        # 부모 op가 딜(GetDamage/GetDot)이면 타수+, 아니면 딜무관(받피증/버프 강화 등)
            pt = e.get('parentType', '')
            if 'GetDamage' in pt or 'GetDot' in pt:
                return {'type': 'hitPlus', 'value': int(v) if v else None}
            return {'type': 'none'}
        if key == 'MaxHitCount':  return {'type': 'targetPlus', 'value': int(v) if v else None}     # 대상+
        if key == 'ModStatAddValue': return {'type': 'statAdd', 'value': v / 10 if v is not None else None, 'stat': e.get('parentStat')}  # 스킬별 스탯 가산(보스뎀/일반몹뎀), stat=대상 스탯
        if key == 'SkillCoolTimeMs': return {'type': 'cdFlatMs', 'value': v, 'targetSkill': e.get('targetSkill')}  # cross-skill 쿨 감소
        if key == 'DurationMs':   return {'type': 'durMs', 'value': v}
        if key == 'SkillTriggerRatio': return {'type': 'procAdd', 'value': v / 10 if v is not None else None}
        if key == 'FireRatio':    return {'type': 'fireRatio', 'value': v}
    if k == 'ratio':   # 값÷10 = %(비율). 음수=감소
        if key == 'SkillCoolTimeMs': return {'type': 'cdrPct', 'value': abs(v) / 10 if v is not None else None}
        if key == 'TickTimeMs':   return {'type': 'tickPct', 'value': abs(v) / 10 if v is not None else None}
        if key == 'DurationMs' or key == 'DurationTimeMs': return {'type': 'durPct', 'value': v / 10 if v is not None else None}
        if key == 'Value':        # 대상 op Value 배수(증가량 %증가). 부모 op type으로 구분: 최종뎀(skillBoost) vs 버프/디버프 스탯.
            pt = str(e.get('parentType') or '')
            if 'ModSkillStat' in pt or 'Pipeline' in pt: return {'type': 'ratioBoost', 'value': v / 10 if v is not None else None}  # skillBoost 최종뎀 배수(어드밴스드 파이널어택 등)
            return {'type': 'ratioValue', 'value': v / 10 if v is not None else None, 'stat': e.get('parentStat')}  # 버프/디버프 스탯 값 배수
        if key == 'SkillTriggerRatio': return {'type': 'procRatio', 'value': v}
    if k == 'pipelineStat':      return {'type': 'finalDmgPct', 'value': v / 10 if v is not None else None}   # 최종뎀 +N%
    if k == 'projectile':
        if key == 'MaxTargetCount': return {'type': 'summonTargetPlus', 'value': int(v) if v else None}
        if key == 'TickTimeMs':   return {'type': 'summonTickMs', 'value': v}
        if key == 'MaxFireCount' or key == 'MaxProjectileCount': return {'type': 'summonFirePlus', 'value': int(v) if v else None}
    if k == 'stat':              return {'type': 'selfStat', 'stat': key, 'value': v}      # 자체 스탯(크확강화 등)
    if k == 'stateMaxStack':     return {'type': 'stackPlus', 'state': e.get('state'), 'value': int(v) if v else None}
    if k == 'enable':            return {'type': 'enableDeal', 'value': e.get('value')}
    if k == 'changeSkill':       return {'type': 'changeSkill', 'to': e.get('to')}
    if k == 'setSkill':          return {'type': 'setSkill', 'key': e.get('key'), 'value': e.get('value')}
    return {'type': 'raw', 'effect': e}

def build(ci):
    src = json.load(io.open(f"{SRC}/ci{ci}_full.json", encoding='utf-8'))
    job = JOBS.get(str(ci), f"ci{ci}")
    out = {'job': job, 'creatureIndex': int(ci), 'indirectSrc': src.get('indirectSrc', []), 'skills': []}
    for s in src['skills']:
        cl = s['classes']
        sk = {
            'index': s['index'], 'name': s.get('name'), 'open': int(s['open']) if s.get('open') else None,
            'type': s['type'], 'cd': (int(s['cooldownMs']) / 1000 if s.get('cooldownMs') else 0),
            'desc': s.get('desc'),
            'targetCount': cl.get('overrideTargets') or target_count(s.get('targetCount')), 'area': bool(s.get('area')), 'trigger': trigger(s),
            'dealChild': s.get('dealChild'),
            # 소환 종속(§⑤): 신엔진이 summonSkill effCd로 런타임 가동률(uptime) 계산 → 딜에 곱.
            'summonSkill': s.get('summonSkill'), 'summonDurMs': s.get('summonDurMs'),
            'damage': cl['dmg'], 'summon': cl['summon'], 'skillBoost': cl.get('skillBoost', []),
            'buff': cl['buff'], 'debuff': cl['debuff'], 'indirect': cl['indirect'], 'util': cl['util'],
            'masteries': [{'open': int(m['open']) if str(m.get('open')).isdigit() else m.get('open'),
                           'name': m.get('name'), 'desc': m.get('desc'), **mastery_norm(m.get('effect'))}
                          for m in s.get('masteries', [])],
        }
        out['skills'].append(sk)
    io.open(f"{OUT}/{job}.json", 'w', encoding='utf-8').write(json.dumps(out, ensure_ascii=False, indent=1))
    return job, len(out['skills'])

def main():
    cis = [sys.argv[1]] if len(sys.argv) > 1 else [str(i) for i in range(1, 15)]
    for ci in cis:
        try:
            job, n = build(ci)
            print(f"CI{ci} → {job}.json ({n}스킬)")
        except Exception as e:
            print(f"CI{ci} 실패: {e}")

main()
