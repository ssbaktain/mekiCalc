# artifact_seed.json → artifact_manual.json 스캐폴드(손작업용 초안).
# idx/grade/name/desc/descMax + 유물레벨 트리거(trigger/periodMs/durationMs/chance/content) + effects(stat/values[6]/unit/_op/_raw).
# 기계추출만: 시맨틱 조건(bossOnly/minEnemies/derived 등)은 유저가 _raw 보고 채움. 재복호화 불필요.
# -*- coding: utf-8 -*-
import io, json, os, sys, re
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
import _clt2_stage_a as SA   # 자식스킬 재귀용 SkillTable(SA.ST)/ir_op (복호화 불필요)
SEEDP = r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_calc\artifact_seed.json'
seed = json.load(io.open(SEEDP, encoding='utf-8'))
_TA = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
STATE_MAXSTACK = {}   # StateName → MaxStack(정수). 육각 Artifact_4_2=3, 은펜던트=5 등
for _r in json.load(io.open(os.path.join(_TA, 'StateTable.json'), encoding='utf-8')):
    try: STATE_MAXSTACK[str(_r.get('StateName'))] = int(_r.get('MaxStack'))
    except Exception: pass

STAT_MAP = {'Attack': 'ATK_PCT',            # 공격력(StatType7) → 공%(baseAtk 합-풀)
            'AttackPower': 'DMG',           # StatType9 "최종공격력"=실제 데미지(피해량)증가 계열(스탯창 데미지% 실측확인). 육각·머쉬맘. ModStatR=dmgMult(데미지%×(1+v))
            'FinalDamage': 'FinalDamage', 'PiercePower': 'PiercePower',
            'CriticalChance': 'CriticalChance', 'CriticalPower': 'CriticalPower',
            'SkillPower': 'SkillPower', 'BaseAttackPower': 'BaseAttackPower',
            'MaxDamageRatio': 'MaxDamageRatio', 'MinDamageRatio': 'MinDamageRatio',
            'CoolTimeReduceRatio': 'COOLDOWN_REDUCTION_PCT'}   # 스킬 쿨감(영혼의 계약서). 나머지(AttackPowerToBoss·AttackPowerExcludeBoss·AttackSpeed·PiercePower·SupporterDurationRatio·BuffDurationRatio)=clt명 그대로(스킬 IR와 통일)
STAT_OPS = {'ModStat', 'ModStatR', 'ModDamagePipelineStat', 'ModAttrDamagePipelineStat'}
# 비딜(survival/유틸) 스탯 → effects 제외, _survival로 이름만 노출(단 명중은 파생 소스이므로 desc 참고)
NONDEAL = {'HpPotionCoolTimeReduceRatio', 'DebuffResist', 'HpRecoverPower', 'MaxHp', 'Hp', 'Mp',
           'HitChance', 'Accuracy', 'Acc', 'MpRecoverPower', 'HpMaxRatio'}
# 클뜯 TriggerType → 트리거 태그
TRIG = {'OnStart': 'onStart', 'OnTimer': 'period', 'OnAttack': 'onAttack', 'OnTryAttack': 'onAttack',
        'OnWaveStart': 'combatStart', 'OnKill': 'onKill', 'OnDamage': 'onDamage', 'OnState': 'onState',
        'OnSpawnSupporterSkill': 'companionSummoned'}
PROC_TRIG = {'OnAttack', 'OnTryAttack', 'OnKill', 'OnDamage'}   # 자식 op가 이 트리거면 effect레벨 chance/trigger 부여
# TriggerCondition → content 이름
CONTENT = [('GrowthDungeon', '성장던전'), ('BossRaid_Zakum', '레이드-자쿰'), ('GuildRaid_Zakum', '레이드-자쿰'),
           ('BossRaid_Horntail', '레이드-혼테일'), ('GuildRaid_Horntail', '레이드-혼테일'),
           ('WorldBoss', '월드보스'), ('WorldArena', '월드아레나'), ('Arena', '아레나'),
           ('GuildBoss', '길드토벌전'), ('Campaign', '챕터사냥')]
DEFER = {'onState', 'onDamage'}   # 피격/상태의존 → 보류

# State/라이브 기반 조건(op에 없어 재귀로 못 잡음) — desc 근거 수기 보정. effects=index별 병합, drop=제거, note=유물주석.
OVERRIDE = {
    '512': {'drop': [1], 'effects': {0: {'values': [400, 480, 560, 640, 720, 800], 'mpAtLeast': None}},
            'desc': '2초마다 MP를 1% 회복합니다. MP 50% 이상 시 크리티컬 데미지가 20% 증가하고, 75% 이상이면 40%(2배)로 적용됩니다.',
            'descMax': '2초마다 MP를 1% 회복합니다. MP 50% 이상 시 크리티컬 데미지가 40% 증가하고, 75% 이상이면 80%(2배)로 적용됩니다.',
            'note': '얼음: ★MP≥75% 가정(최대효율) → 크뎀 40%[5각80%](2스택 합=단일 effect 통합). 추후 MP 선택지. 원식=MP≥50%→20%, ≥75%→+20%(mpAtLeast 500/750)'},
    '514': {'effects': {0: {'targetMode': 'multi'}, 1: {'targetMode': 'single'}},
            'desc': '적이 2명 이상일 때 일반 몬스터 데미지가 30% 증가합니다. 적이 1명일 때는 보스 몬스터 데미지가 10% 증가합니다.',
            'descMax': '적이 2명 이상일 때 일반 몬스터 데미지가 60% 증가합니다. 적이 1명일 때는 보스 몬스터 데미지가 20% 증가합니다.',
            'note': '세이람: 적2+→일반뎀(multi)·적1→보스뎀(single). 배타(M으로 갈림)'},
    '519': {'drop': [1], 'trigger': 'companionSummoned',
            'effects': {0: {'maxStacks': None, 'target': 'self',
                            'contentMult': [{'content': ['챕터보스'], 'ratio': 2}]}},
            'addEffects': [{'stat': 'FinalDamage', 'values': [100, 120, 140, 160, 180, 200],
                            'target': 'companion', '_op': 'ModStat'}],
            'note': '뿔피리: FinalDamage 10%[5각20%]를 자신+동료 **양쪽** 부여. trigger=동료소환중(companionSummoned). ★유일하게 "이미 소환돼 있는 동료"에게 스탯을 주는 유물(동료 소환된 동안 상시). clt op=Myself(op1)+Parent(op3) 두 개라 effect도 분리: effect0 target:self(+챕터보스 selfMult ×2), effect1 target:companion(동료 각 개체 스탯풀에 FinalDamage 주입, selfMult 없음=동료는 배수 안받음). aw0 챕터보스=자신20%/동료10%, 타컨텐츠=둘다10%. ★2배는 clt op에 없음—desc 유래 컨텐츠 로직. ★동료몫 실제 주입은 동료엔진 조립 시(현재 유일 케이스 특례).'},
    '503': {'effects': {0: {'individualDur': True}},
            'note': '은펜던트: onAttack 15% Weakness 5초, 최대5중첩·개별지속(individualDur=발동마다 독립창 겹쳐쌓기 non-pause. refresh:false 아님). 효과=v×min(5,겹치는창수)'},
    '520': {'desc': '명중이 15, 최종 데미지가 7% 증가합니다. 자신의 공격을 대상이 회피하면 5초간 최종 데미지 효과가 사라지고, 명중이 3배로 적용됩니다.',
            'descMax': '명중이 30, 최종 데미지가 14% 증가합니다. 자신의 공격을 대상이 회피하면 5초간 최종 데미지 효과가 사라지고, 명중이 3배로 적용됩니다.',
            'note': '저주인형: FinalDamage 7%[5각14%]. 회피시 최종뎀 사라짐→명중 충분 시 상시 근사(피격/회피 미모델). 명중(HitChance)은 비딜(자식+본체 2건이나 _survival 중복제거)'},
    '523': {'meta': {'startDelayMs': 5000, 'refresh': True},
            'effects': {0: {'critGte': 1000},
                        1: {'critGte': 1000, 'critExcess': {'step': 100, 'maxCount': 10}, '_raw': None},
                        2: {'critLt': 1000}},
            'note': '순환의고리: trigger=period 5초 + ★startDelayMs 5000(게임시작 5초후 첫발동). ★refresh:true=매 발동 자기 이전효과 **제거 후 재적용(비누적)** — clt op1~3 ClearState(Artifact_5_23_1/2/3) 대변. 없으면 clt DurationMs 99999999(무한)라 매 5초 누적폭주. 순서: 자기효과 제거→고리 뺀 라이브 크확 재측정→조건버프 부여(제거가 먼저라 자기 크확버프 자동 제외). ★배타 regime(크확 100%기준 파티션, 하나만): 크확<100%(critLt:1000)→크확+30%[5각60%](CriticalChance) / 크확≥100%(critGte:1000)→스킬뎀+40%[5각80%](SkillPower) AND 평타뎀 critExcess(초과 10%=step100마다 +6%[5각12%], 최대10회, BaseAttackPower). ★조건=플랫태그 critLt/critGte(천분율, 1000=100%)로 minEnemies·targetMode와 동일 양식. 엔진 특수(라이브스탯 의존)'},
    '516': {'drop': [1],
            'desc': '공격 시 20% 확률로 5초간 대상의 받는 피해를 15% 증가시킵니다.',
            'descMax': '공격 시 20% 확률로 5초간 대상의 받는 피해를 30% 증가시킵니다.',
            'note': '천도명약: ★대상 버프없음 가정 → effect1(전투중1회 require:buff) 제외(drop). effect0=onAttack 20% Weakness 5초만 적용. 추후 컨텐츠모드에서 effect1 복원'},
    '515': {'effects': {1: {'values': None, '_raw': None,
                            'derived': {'from': 'AttackSpeed', 'threshold': 600, 'step': 30,
                                        'perStep': [5, 6, 7, 8, 9, 10], 'maxCount': 20}}},
            'note': '물병: FinalDamage=초과분 derived. 공속 60%(threshold600) 초과분을 3%(step30)마다 perStep‰씩(0.5%[5각1%]) 최대 maxCount20회(cap=perStep×20=10%[5각20%]). 라이브 공속 의존(tick200ms). ★공속60% 미만이면 0'},
    '401': {'effects': {1: {'durationMs': 15000}},
            'note': '무지개달팽이: combatStart 15초 창. 크확15%[5각30%]+크뎀20%[5각40%] 둘 다 durationMs 15000(op2 크뎀은 클라 durationMs 생략됐으나 desc상 15초 동일)'},
    '404': {'note': '머쉬맘: 명중초과 데미지 최대치 가정(assumeMax)'},
    '513': {'effects': {0: {'priorityGroup': 'burn', 'priority': 1}, 1: {'priorityGroup': 'burn', 'priority': 2}, 2: {'priorityGroup': 'burn', 'priority': 3}},
            'note': '불타는용암: ★대상 상태 우선순위 배타(보호막>디버프>버프, 최고 하나만). priorityGroup:burn 내 condition(require) 충족 중 최고 priority만 적용(가산 아님). shield60%[5각120%]>debuff8%[16%]>buff4%[8%](require=IfShield/IfDebuff/IfBuffExceptShield). ★require=대상상태 의존→컨텐츠모드 보류(현재 0). 우선순위 배타는 유저 케이스 근거(디버프=8% 버프무관), clt/실측 최종확인 필요'},
    '501': {'meta': {'chance': 20},
            'note': '성배: onKill 2%(chance:20‰. TriggerRatio가 #Artifact_HolyGrail 함수라 자동추출 안 됨→수기). FinalDamage 15%[5각30%] 30초. K(분당처치수)로 구동. ★"보스 처치 시 반드시 발동"은 보스처치=전투종료라 무의미(보스탭 K=0)'},
    '517': {'effects': {1: {'delayMs': 20000, 'durationMs': 20000}},
            'note': '양초: 최종뎀 idx0=전투시작~40초(durationMs40000). 보스뎀 idx1=20초후 시작(delayMs20000)~40초 종료(durationMs20000). 전투시작 40초에 전체 소멸(combatStart 기준)'},
    '518': {'addEffects': [{'stat': 'ATK_PCT', 'values': [100, 120, 140, 160, 180, 200], 'unit': 'pct'}],
            'desc': '아군 플레이어(본인 포함)의 공격력을 10% 증가시킵니다. 이 유물을 장착한 다른 플레이어의 효과와 중복 적용됩니다.',
            'descMax': '아군 플레이어(본인 포함)의 공격력을 20% 증가시킵니다. 이 유물을 장착한 다른 플레이어의 효과와 중복 적용됩니다.',
            'note': '동맹증표: 자식 9051801 ModStatR Attack → ATK_PCT 곱(unit:pct=ModStatR 반영, 별도 태그 불요). 다인플 미가정이라 target 제외(본인만). 10%[5각20%]. 멀티 중복(타 플레이어)은 미모델'},
    '521': {'effects': {1: {'targetMode': 'multi'}, 2: {'targetMode': 'single', 'bossZero': True}, 3: {'targetMode': 'single', 'bossOnly': True}},
            'note': '레인디어: 공격력 5%[5각10%](Attack=ATK_PCT, effect0=조건없음 **무조건 상시**, 방관분기와 독립). 방관은 마리수·보스 ★배타분기(하나만): 적2+(multi)→방관5%[10%] / 적1 비보스(single+bossZero)→10%[20%](2배) / 적1 보스(single+bossOnly)→15%[30%](3배). ★조건 자체가 마리수×보스 파티션이라 **priorityGroup 불요**(항상 정확히 하나만 참, default 가산으로 OK)—불타는용암(buff/debuff/shield 겹침→우선순위 필요)과 다름. ★clt op엔 조건 안 보이나 desc상 배타(2/3배 대체). idx1/2/3=op2/3/4'},
    '522': {'drop': [2],
            'effects': {1: {'minEnemies': 10, 'contentMult': [{'content': ['성장던전'], 'ratio': 3}]}},
            'note': '비밀지도: 공격력 5%[5각10%](effect0 항상). 최종뎀=적10명이상(minEnemies10) base 5%[10%], [성장던전]에서 ×3=15%[30%]. ★뿔피리처럼 contentMult(base×ratio): 성장던전은 "적10명" 공간의 **부분집합**이라 base가 거기서도 켜짐→pre-split이면 5%+15% 중복버그. 그래서 base 단일 effect+contentMult로. clt op은 FinalDamage 50+150 두개(둘다 Myself·무조건)라 150(op3)=drop, 50에 ratio3 부여. 우리 챕터탭=성장던전 아님→base 5%만(M≥10시)'},
}

def num(x):
    try:
        f = float(x); return int(f) if f == int(f) else f
    except Exception:
        return None

def op_stat(op):
    """stat op → raw stat명. ModStat/R=values[0], Pipeline=values[2]."""
    v = op.get('values') or []
    if op.get('type') in ('ModStat', 'ModStatR'):
        return v[0] if len(v) > 0 else None
    if op.get('type') in ('ModDamagePipelineStat', 'ModAttrDamagePipelineStat'):
        return v[2] if len(v) > 2 else None
    return None

def op_value(op):
    """op의 값(천분율). $dyn.mul / #func.coeff / 평범한 숫자 우선순위."""
    if op.get('dyn'): return num(op['dyn'].get('mul'))
    if op.get('func'): return num(op['func'].get('coeff'))
    v = op.get('values') or []
    if op.get('type') in ('ModStat', 'ModStatR'):
        return num(v[1]) if len(v) > 1 else None
    return num(v[3]) if len(v) > 3 else None

def fn_of(op):
    """게이트/파생 함수명 (dyn.fn 또는 func.func)."""
    if op.get('dyn'): return op['dyn'].get('fn')
    if op.get('func'): return op['func'].get('func')
    return None

def fn_cond(fn):
    """함수명 → (조건태그 dict, 보류bool). None=미인식(raw 유지)."""
    if not fn: return None, False
    if fn == 'CreatureIsBoss': return {'bossOnly': True}, False
    m = re.match(r'GetEnemyCountMax(\d+)', fn)
    if m: return {'perEnemy': {'max': int(m.group(1))}}, False
    if fn == 'IfDebuff': return {'require': 'debuff'}, True
    if fn == 'IfShield': return {'require': 'shield'}, True
    if fn == 'IfBuffExceptShield': return {'require': 'buff'}, True
    if fn == 'Artifact_MushmomHead': return {'assumeMax': True}, False   # 명중초과딜 최대치 가정
    return None, False

def collect_ops(si, seen):
    """스킬 si의 stat op을 재귀 수집(UsePassiveSkill 자식 추적). SA.ir_op으로 파싱($dyn/#func 포함)."""
    s = SA.ST.get(si); out = []
    if not s or si in seen: return out
    seen.add(si)
    trig = {'type': s.get('TriggerType'), 'ratio': s.get('TriggerRatio'),
            'condition': s.get('TriggerCondition'), 'cooldownMs': s.get('CoolTimeMs')}
    for i, op in enumerate(s.get('Operations') or []):
        if not isinstance(op, dict): continue
        t = op.get('Type')
        if t in STAT_OPS:
            o = SA.ir_op(op, i); o['_trig'] = trig      # 소속 스킬 트리거(chance/지속 근거)
            out.append(o)
        elif t == 'UsePassiveSkill':
            for cv in (op.get('Values') or []):
                if str(cv).isdigit():
                    out += collect_ops(str(cv), seen)
    return out

def content_of(cond):
    """TriggerCondition → content 리스트(다중 매칭, 중복제거). 예 아레나||월드아레나 → ['아레나','월드아레나']."""
    if not cond: return None
    s = json.dumps(cond, ensure_ascii=False)
    out = []
    for key, ko in CONTENT:
        if key in s and ko not in out: out.append(ko)
    return out or None

def build(a):
    stages = a.get('stages') or []
    st0 = stages[0] if stages else {}
    trg = st0.get('trigger') or {}
    ttag = TRIG.get(trg.get('type'), trg.get('type'))
    m = {'idx': a['index'], 'grade': a['grade'], 'name': a['name'],
         'desc': a.get('desc'), 'descMax': a.get('descMax')}
    # 유물레벨 트리거
    if ttag and ttag != 'onStart':
        m['trigger'] = ttag
    if ttag == 'period' and trg.get('periodMs'):
        m['periodMs'] = num(trg['periodMs'])
    ratio = trg.get('ratio')
    if ratio is not None and str(ratio) not in ('1000', '1000.0'):
        m['chance'] = num(ratio) if num(ratio) is not None else str(ratio)   # <1000=실확률, #함수=원문
    c = content_of(trg.get('condition'))
    if c: m['content'] = c
    cd = num(st0.get('cooldownMs'))
    if cd and 0 < cd < 99999999: m['cooldownMs'] = cd   # 재사용 대기(오르골 20000·돼지리본/월로 5000)
    if ttag in DEFER:
        m['_defer'] = '피격/상태의존 → 컨텐츠모드 보류'
    # effects: 각성별 stat op 재귀수집(자식 추적) 후 동일 index로 zip → values[6]
    per_aw = []
    for aw in range(6):
        si = stages[aw].get('skillIndex') if aw < len(stages) else None
        per_aw.append(collect_ops(str(si), set()) if si else [])
    base = per_aw[0]
    effects = []
    survival = []
    def aw_op(aw, k):
        return per_aw[aw][k] if k < len(per_aw[aw]) else None
    for k, op0 in enumerate(base):
        rawstat = op_stat(op0)
        if rawstat in NONDEAL:                       # 비딜 → effects 제외, 이름만 기록
            survival.append(rawstat); continue
        if rawstat == 'Weakness' and str(op0.get('target')) not in ('Target', 'TriggerTarget', 'Enemy'):
            survival.append('Weakness(self=받피증 페널티)'); continue   # 자신 받피증가=비딜(별의돌)
        stat = STAT_MAP.get(rawstat, rawstat)
        eff = {'stat': stat}
        fn = fn_of(op0)
        f0 = op0.get('func') or {}
        if f0.get('func') == 'GetFinalStatRatio':    # 파생: 라이브 스탯 × 비율
            fa = f0.get('args') or []
            eff['derived'] = {'from': STAT_MAP.get(fa[0], fa[0]) if fa else None,
                              'ratio': [num(((aw_op(aw, k) or {}).get('func') or {}).get('args', [None, None])[1]) for aw in range(6)]}
        else:
            eff['values'] = [op_value(aw_op(aw, k) or {}) for aw in range(6)]
            if op0.get('type') == 'ModStatR':
                if stat == 'DMG':
                    eff['dmgMult'] = True            # 데미지% 곱(현재값×(1+v×스택)). 육각
                elif stat != 'FinalDamage':
                    eff['unit'] = 'pct'              # 곱(비율). FinalDamage는 고유곱이라 불요
            cond, defer = fn_cond(fn)
            if cond: eff.update(cond)                # bossOnly/perEnemy/require/assumeMax
            if defer: eff['_defer'] = f'{fn} → 컨텐츠모드 보류'
            if fn and cond is None:                  # 미인식 함수 → raw 노출
                eff['_raw'] = op0.get('values')
        # 소속 스킬(자식)이 프록 트리거면 effect레벨 부여 — 단 유물레벨 트리거와 다를 때만(중복 방지)
        et = op0.get('_trig') or {}
        if et.get('type') in PROC_TRIG:
            tag = TRIG.get(et['type'], et['type'])
            if tag != m.get('trigger'):                       # 자식 트리거≠유물레벨 → effect레벨
                eff['trigger'] = tag
                r = num(et.get('ratio'))
                if r is not None and r != 1000: eff['chance'] = r
            if str(et.get('cooldownMs')) == '99999999': eff['_note'] = '전투중 1회'
        # State 최대중첩(육각 3·은펜던트 5)
        mx = STATE_MAXSTACK.get(str(op0.get('state')))
        if mx and mx > 1: eff['maxStacks'] = mx
        # op의 유한 지속(99999999=상시 제외, <1초=내부tick 제외) — 트리거 무관 일반 반영(오르골 onState 25000 등)
        dm = num(op0.get('durationMs'))
        if dm and 1000 <= dm < 99999999 and 'durationMs' not in eff:
            eff['durationMs'] = dm
        eff['_op'] = op0.get('type')
        if str(rawstat) != str(eff['stat']):
            eff['_cltStat'] = rawstat
        effects.append(eff)
    if survival:
        m['_survival'] = list(dict.fromkeys(survival))   # 비딜 스탯(중복 제거, 참고용)
    # State/라이브 조건 보정(OVERRIDE)
    ov = OVERRIDE.get(m['idx'])
    if ov:
        for i in sorted(ov.get('drop', []), reverse=True):
            if i < len(effects): effects.pop(i)
        for i, patch in (ov.get('effects') or {}).items():
            if i < len(effects):
                for kk, vv in patch.items():
                    if vv is None: effects[i].pop(kk, None)   # None=키 제거
                    else: effects[i][kk] = vv
        effects.extend(ov.get('addEffects', []))     # 자식/특수 effect 수기 주입(동맹증표 등)
        for kk, vv in (ov.get('meta') or {}).items():  # 유물레벨 필드(cooldownMs/content 등)
            if vv is None: m.pop(kk, None)
            else: m[kk] = vv
        if ov.get('trigger'): m['trigger'] = ov['trigger']
        if ov.get('desc'): m['desc'] = ov['desc']
        if ov.get('descMax'): m['descMax'] = ov['descMax']
        if ov.get('note'): m['note'] = ov['note']
    m['effects'] = effects
    return m

arts = [build(a) for a in seed['artifacts']]
out = {'_comment': '유물 계산 IR manual(손작업본). 스캐폴드=기계추출(idx/grade/name/desc/트리거/effects.values). '
                   '_로 시작하는 필드(_op/_raw/_child/_defer/_cltStat)=참고 힌트, 확정 후 삭제. '
                   '조건 태그(bossOnly/minEnemies/perEnemy/mpAtLeast/derived/window)는 _raw·desc 보고 유저가 추가. 규약=_clt2_ARTIFACT_TAGS.md',
       'artifacts': arts}
outp = r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_calc\artifact_manual.json'
json.dump(out, io.open(outp, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(f'스캐폴드 {len(arts)}종 저장: {outp}')
# 샘플: 단순(죽은자)·다중조건(세이람=자식)·파생(헬레나)·확률(은펜던트)·컨텐츠(자쿰)
for want in ['301', '514', '406', '503', '407']:
    a = next((x for x in arts if x['idx'] == want), None)
    if a: print('\n' + json.dumps(a, ensure_ascii=False, indent=1))
