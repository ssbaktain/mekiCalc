# clt2 Stage A (원본 SkillTable → IR) 골격.
# 기본필드 + ops 원본보존 + 성장(SkillLevelFactorTable 직접참조) + children(소유) + summon(ProjectileTable 조인).
# State/마스터리/조건파서/#함수파서는 다음 단계. 커버리지 1:1 매핑 추적.
# 사용: python _clt2_stage_a.py [스킬idx...]  (인자 없으면 검증 샘플 출력)
# -*- coding: utf-8 -*-
import json, io, os, sys
from collections import defaultdict
sys.stdout.reconfigure(encoding='utf-8')
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'

def load(n): return json.load(io.open(os.path.join(D, n), encoding='utf-8'))
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r
HS = {str(r['SkillIndex']) for r in load('HeroSkillTable.json')}
HST_STEP = {}   # 스킬 → 전직 차수(JobStep 0=전직전 임시·1~4=실제). 0차=최종빌드 미사용 구분용
for r in load('HeroSkillTable.json'):
    HST_STEP.setdefault(str(r['SkillIndex']), r.get('JobStep'))
PROJ = {str(r['ProjectileIndex']): r for r in load('SkillProjectileTable.json')}
LF = {str(r['Level']): r['Factor'] for r in load('SkillLevelFactorTable.json')}
# name/desc: 복호화된 skills_v2에서 조인(원본 SkillTable엔 LocalKey만) — 검증/UI용
NM = {}
_sv = r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_v2'
MMAP = {}  # (부모스킬idx, 해금레벨) -> (마스터리 name, desc)
for _j in ['hero','paladin','darkknight','archmagetc','archmagefp','bishop','bowmaster','marksman','nightlord','shadower','viper','captain','nightwalker','windbreaker']:
    try:
        for _s in json.load(io.open(os.path.join(_sv, _j + '.json'), encoding='utf-8'))['skills']:
            NM[str(_s['index'])] = (_s.get('name'), _s.get('desc'))
            for _m in _s.get('masteries', []):
                if _m.get('open') is not None:
                    MMAP[(str(_s['index']), int(_m['open']))] = (_m.get('name'), _m.get('desc'))
    except Exception:
        pass
# ★공용 스킬 민첩한 몸놀림(퀵네스): 9000=CI1~12·9001=CI13~14 동일 스킬(공속+15%/이속+10%, 60초쿨).
# 9000=SkillNameLocalKey없어 skills_v2 누락, 9001=skills_v2가 '엘리멘탈 하모니'로 오매핑(실존X) → 둘 다 강제정정.
# 이름 출처=아이콘 ICON_QUICKNESS + 가이드 GUIDE_LEARNSKILL_QUICKNESS 복호화.
NM['9000'] = ('민첩한 몸놀림', None)
NM['9001'] = ('민첩한 몸놀림', None)

def is_sup(i): return len(i) == 6 and i.startswith('8')

# ── 성장: 계수 = base × Factor[Y][level]/1000 (Y=ValueLevelFactors[-1]=슬롯) ──
def growth_factor(vlf, level):
    if not vlf: return 1.0
    slot = int(str(vlf[-1]))
    f = LF.get(str(min(level, 1000)))
    if not f or slot >= len(f): return 1.0
    return int(f[slot]) / 1000.0

def parse_num(v):
    try: return float(str(v))
    except (ValueError, TypeError): return None

# ── op → IR op (원본 1:1 보존, 값 천분율 유지) ──
DEAL = {'GetDamageR', 'GetDamage', 'GetDotR', 'GetDot', 'GetDamageByMaxHpR', 'SetDamage'}
def ir_op(op, no):
    if not isinstance(op, dict):
        return {'no': no, 'type': None}  # null op
    o = {'no': no, 'type': op.get('Type'), 'target': op.get('TargetType'),
         'values': op.get('Values')}
    for src, dst in [('ValueLevelFactors', 'valueLevelFactors'), ('MaxHitCount', 'maxHitCount'),
                     ('State', 'state'), ('Flag', 'flag'), ('ParentOperationNo', 'parentOpNo'),
                     ('WatchOperationNo', 'watchOpNo'), ('EnableCondition', 'enableCondition'),
                     ('DurationMs', 'durationMs'), ('ModStats', 'modStats'),          # ★op 부가스탯(보스뎀 등, 딜)
                     ('TickTimeMs', 'opTickMs'), ('AddStateCount', 'addStateCount'), ('FireRatio', 'fireRatio'),
                     ('ChainTargetInfo', 'chainTargetInfo'), ('ReverseCheck', 'reverseCheck'),  # 딜 세부(연쇄/조건반전)
                     ('QueueDelayMs', 'queueDelayMs')]:  # ★시전시간(발동제약, SyncQueueDelayBySkillSpeed면 공속단축)=DPS빈도
        if op.get(src) is not None: o[dst] = op.get(src)
    if op.get('ValueLevelFactors'):
        o['growthSlot'] = int(str(op['ValueLevelFactors'][-1]))
    if op.get('EnableCondition'):
        o['condTree'] = parse_condition(op['EnableCondition'])          # 조건 트리
    for v in (op.get('Values') or []):
        hf = parse_hashfunc(v)
        if hf: o['func'] = hf; break                                    # #함수 딜계수
        if isinstance(v, str) and v.strip().startswith('$'):            # ★Values 내 $동적식(ModDamagePipelineStat 등)
            o['dyn'] = parse_condition(v); break                        #   예 $CreatureIsBoss(Target)*80 → {fn,args,mul}
    return o

# ── 소환: CreateProjectile → ProjectileTable 조인 ──
def summon_of(s):
    out = []
    for op in (s.get('Operations') or []):
        if isinstance(op, dict) and op.get('Type') in ('CreateProjectile', 'FireProjectile') and (op.get('Values') or []):
            pi = str(op['Values'][0])
            p = PROJ.get(pi)
            if p:
                tt = p.get('TickTimeMs')
                out.append({'projIndex': pi,
                            'projDurationMs': parse_num(p.get('DurationTimeMs')),
                            'projTickMs': parse_num(tt[0]) if isinstance(tt, list) and tt else None,
                            'projMaxTargetCount': parse_num(p.get('MaxTargetCount')),
                            'projCount': parse_num(p.get('MaxProjectileCount')),   # ★투사체 개수(딜 배수 가능성, 본체 이그나이트/섀도어)
                            'projFlag': p.get('Flag'),                             # 소환 지속조건(ExpireOnCreatureDie 등)
                            'followTargetType': p.get('FollowTargetType'),         # 이동방식(메타)
                            'dealSkill': (p.get('SkillIndex') or [None])[0] if isinstance(p.get('SkillIndex'), list) else p.get('SkillIndex')})
    return out

# ── children: 소유(FollowLevelIndex + OWN op호출, 정식스킬 자식중첩 금지) ──
OWN = {'UseSkill', 'UsePassiveSkill', 'UseSkillToTarget', 'UseSkillToTargetInArea',
       'CreateProjectile', 'FireProjectile', 'CreateServantProjectile', 'SetServantSkill'}
fc = defaultdict(list)
for k, s in ST.items():
    if s.get('FollowLevelIndex'): fc[str(s['FollowLevelIndex'])].append(k)

def own_children(si):
    s = ST.get(si); out = set()
    for op in (s.get('Operations') or []):
        if not isinstance(op, dict) or op.get('Type') not in OWN or not (op.get('Values') or []):
            continue
        for v in op['Values']:                                    # OWN op의 첫 ST 매치(SetServantSkill 등 대응)
            sv = str(v)
            if sv in ST and sv not in HS and not is_sup(sv):      # 정식스킬 중첩금지(발동링크는 invokes)
                out.add(sv); break
        if op.get('Type') in ('CreateProjectile', 'FireProjectile'):   # ProjectileTable 딜스킬 경로
            pi = str(op['Values'][0])
            if pi in PROJ and PROJ[pi].get('SkillIndex'):
                for x in (PROJ[pi]['SkillIndex'] if isinstance(PROJ[pi]['SkillIndex'], list) else [PROJ[pi]['SkillIndex']]):
                    if str(x) in ST and str(x) not in HS and not is_sup(str(x)): out.add(str(x))
    for c in fc.get(si, []):
        if c not in HS: out.add(c)                                # FollowLevel 자식(전용 하위기)
    return out

# ── 관계 인덱싱: boosts_in(강화 역참조)/invokes(발동링크)/states(grant·require·consume) ──
BOOSTS = defaultdict(list)   # targetSkill -> [강화 op]
for _si, _s in ST.items():
    if is_sup(_si): continue
    for _i, _op in enumerate(_s.get('Operations') or []):
        if isinstance(_op, dict) and _op.get('Type') in ('ModSkillStat', 'ModSkill', 'ModSkillR', 'ModSkillProjectile', 'ModSkillProjectileR') and (_op.get('Values') or []):
            _b = {'fromSkill': _si, 'opNo': _i, 'type': _op['Type'], 'values': _op.get('Values')}
            if _op.get('ValueLevelFactors'): _b['growthSlot'] = int(str(_op['ValueLevelFactors'][-1]))
            BOOSTS[str(_op['Values'][0])].append(_b)

INVOKE_OPS = {'UseSkill', 'UsePassiveSkill', 'UseSkillToTarget', 'UseSkillToTargetInArea', 'CreateProjectile', 'FireProjectile'}
def invokes_of(si):
    out = []
    for op in (ST[si].get('Operations') or []):
        if isinstance(op, dict) and op.get('Type') in INVOKE_OPS and (op.get('Values') or []):
            tgt = str(op['Values'][0])
            if tgt in HS and not is_sup(tgt) and tgt != si:   # 정식스킬 호출 = 발동링크(딜은 대상루트서 단일계산)
                out.append({'skill': tgt, 'via': op['Type']})
    return out

def states_of(si):
    s = ST[si]; grant, consume, require = [], [], []
    for op in (s.get('Operations') or []):
        if not isinstance(op, dict): continue
        if op.get('State'): grant.append(str(op['State']))          # State 필드 = 상태 부여(Type 무관, ModStat State=X 포함)
        if op.get('Type') == 'ClearState' and (op.get('Values') or []): consume.append(str(op['Values'][0]))
        ec = op.get('EnableCondition')
        if ec:
            for m in _re.finditer(r'CreatureStateCount\w*\([^;,]+[;,]\s*(\w+)\)', str(ec)): require.append(m.group(1))
    tc = s.get('TriggerCondition')
    if tc:
        for m in _re.finditer(r'CreatureStateCount\w*\([^;,]+[;,]\s*(\w+)\)', json.dumps(tc, ensure_ascii=False)): require.append(m.group(1))
    r = {}
    if grant: r['grant'] = sorted(set(grant))
    if require: r['require'] = sorted(set(require))
    if consume: r['consume'] = sorted(set(consume))
    return r or None

def build_node(si, depth=0, seen=None):
    if seen is None: seen = set()
    if si in seen or si not in ST or depth > 6: return None
    seen.add(si)
    s = ST[si]
    tr = s.get('TriggerType')
    nm = NM.get(si, (None, None))
    node = {'id': si,
            'name': nm[0],
            'desc': nm[1],
            'jobStep': HST_STEP.get(si),                    # ★전직 차수(0=공용/전직전, 1~4=차수별). 0이라도 공용스킬(민첩한몸놀림)은 실사용
            'type': 'active' if s.get('CoolTimeMs') else 'passive',
            'cooldownMs': parse_num(s.get('CoolTimeMs')),
            'initialCoolMs': parse_num(s.get('InitialMinCoolTimeMs')),          # ★첫 발동 쿨(딜)
            'attrFlag': s.get('AttrFlag'),                                       # ★속성(Fire/Ice/Poison/Lightning, 속성딜)
            'replaceBaseAttack': 'ReplaceBaseAttack' in str(s.get('TriggerFlag') or ''),  # ★평타 대체(발동빈도)
            'autoTriggerCondition': parse_condition(s.get('AutoTriggerCondition')),  # ★자동발동(소환유지)
            'targetType': s.get('SkillTargetType'),                              # ★딜/버프 대상(Enemy/Myself/Ally/TriggerTarget)
            'meta': {'actionName': s.get('ActionName'), 'iconPath': s.get('SkillIconPath'),
                     'maxRangeMm': s.get('MaxRangeMm'), 'rangeType': s.get('SkillRangeType'),
                     'triggerFlag': s.get('TriggerFlag'), 'ignoreCc': s.get('IgnoreCc'),
                     'maxFireRangeMm': s.get('MaxFireRangeMm'), 'retargetOnFire': s.get('RetargetOnFire'),
                     'retargetOnDead': s.get('RetargetOnDead'), 'skillTargetValue': s.get('SkillTargetValue'),
                     'initialMaxCoolMs': parse_num(s.get('InitialMaxCoolTimeMs'))},  # UI/사거리/재타겟 참조 메타
            'mp': parse_num(s.get('Mp')),
            'durationTimeMs': parse_num(s.get('DurationTimeMs')),
            'tickTimeMs': (parse_num(s.get('TickTimeMs')[0]) if isinstance(s.get('TickTimeMs'), list) and s.get('TickTimeMs') else None),
            'trigger': {'type': tr, 'ratio': parse_num(s.get('TriggerRatio')),
                        'periodMs': (parse_num(s.get('TriggerTimeMs')[0]) if isinstance(s.get('TriggerTimeMs'), list) and s.get('TriggerTimeMs') else None),  # ★OnTimer 발동주기
                        'fastByAtkSpeed': ('FastByAttackSpeed' in json.dumps(s.get('TriggerTimeMs'), ensure_ascii=False)) if s.get('TriggerTimeMs') else False,
                        'condition': parse_condition(s.get('TriggerCondition'))},  # ★발동 게이트(상태분기/편승/컨텐츠/속성)
            'targetCount': s.get('TargetCount'),
            'ops': [ir_op(op, i) for i, op in enumerate(s.get('Operations') or [])],
            'summon': summon_of(s),
            'children': [n for n in (build_node(c, depth+1, seen) for c in sorted(own_children(si))) if n]}
    if BOOSTS.get(si): node['boosts_in'] = BOOSTS[si]          # 강화 역참조
    iv = invokes_of(si)
    if iv: node['invokes'] = iv                                # 발동 링크(정식스킬)
    stx = states_of(si)
    if stx: node['states'] = stx                               # grant/require/consume
    return node

# ── State: 전역 state_defs (StateTable 정의 + op의 State필드 종속효과 + grant/clear 연결) ──
import re as _re
STT = {str(r.get('StateName')): r for r in load('StateTable.json')}
def _truthy(v): return str(v).lower() == 'true'
def build_state_defs(pool):
    defs = {}
    def ensure(name):
        base = _re.sub(r'_\d+$', '', name)          # State_N 분해 → base로 StateTable 조회 fallback
        t = STT.get(name) or STT.get(base)
        if not t: return None
        if name not in defs:
            kind = 'cc' if _truthy(t.get('CC')) else ('debuff' if _truthy(t.get('Debuff')) else 'buff')
            restr = {k: t.get(k) for k in ('CanMove', 'CanAttack', 'CanCast', 'CanJump') if not _truthy(t.get(k))}
            m = _re.search(r'_(\d+)$', name)
            defs[name] = {'maxStack': int(t.get('MaxStack', 1) or 1), 'stackRule': t.get('StackRule'),
                          'kind': kind, 'hostile': t.get('StateHostileType'), 'restrictions': restr,
                          'ccPower': t.get('CcPower'), 'stackLevel': int(m.group(1)) if m else None,
                          'effects': [], 'grantedBy': [], 'clearedBy': []}
        return defs[name]
    for si in pool:
        for i, op in enumerate(ST[si].get('Operations') or []):
            if not isinstance(op, dict): continue
            st = op.get('State')
            if st:
                d = ensure(str(st))
                if d is not None:
                    d['grantedBy'].append(f'{si}.{i}')                         # State필드 있는 op = 상태 부여(grant)
                    if op.get('Type') != 'State':                              # 순수 grant(Type=State)가 아니면 종속효과도
                        d['effects'].append({'skill': si, 'opNo': i, 'type': op.get('Type'),
                                             'values': op.get('Values'), 'valueLevelFactors': op.get('ValueLevelFactors')})
            if op.get('Type') == 'ClearState' and (op.get('Values') or []):
                d = ensure(str(op['Values'][0]))
                if d is not None: d['clearedBy'].append(f'{si}.{i}')           # 소비(consume)
    return defs

# ── #함수 파서: #함수(계수;부가) → 딜계수=첫인자(천분율) ──
def parse_hashfunc(v):
    m = _re.match(r'#(\w+)\(([^)]*)\)', str(v))
    if not m: return None
    args = [a.strip() for a in _re.split(r'[;,]', m.group(2)) if a.strip()]
    coeff = int(args[0]) if args and args[0].lstrip('-').isdigit() else (args[0] if args else None)
    return {'func': m.group(1), 'coeff': coeff, 'args': args}   # 딜계수=coeff(천분율), func=특수거동

# ── 조건 파서: $함수/비교/논리 → 조건 트리 ──
def parse_operand(s):
    s = s.strip()
    m = _re.match(r'\$(\w+)\(([^)]*)\)\s*(.*)$', s)             # $함수(인자) [산술tail]
    if m:
        d = {'fn': m.group(1), 'args': [a.strip() for a in _re.split(r'[;,]', m.group(2)) if a.strip()]}
        tail = m.group(3).strip()
        if tail:
            mm = _re.fullmatch(r'\*\s*(-?\d+)', tail)
            if mm: d['mul'] = int(mm.group(1))                 # 단순 × N
            else: d['expr'] = tail                             # 복합 산술(* N / M 등) 원본식 보존(파생스탯)
        return d
    m = _re.match(r'\$Trigger\.(\w+)\s*&\s*[\w.]+\.(\w+)$', s)  # $Trigger.X & Enum.Y 비트플래그(평타편승/속성/CC 매칭)
    if m: return {'trigger': m.group(1), 'flag': m.group(2)}
    m = _re.match(r'\$Trigger\.(\w+)$', s)                      # $Trigger.X
    if m: return {'trigger': m.group(1)}
    hf = parse_hashfunc(s)                                      # 조건 내 #함수(#HpRatioMoreOrEqual 등)
    if hf: return {'func': hf}
    if s.lstrip('-').isdigit(): return {'int': int(s)}
    if len(s) >= 2 and s[0] == "'" and s[-1] == "'": return {'str': s[1:-1]}  # 문자열 리터럴(상태명 등)
    return {'raw': s}

def parse_cmp(s):
    for c in ['>=', '<=', '==', '!=', '>', '<']:
        if c in s:
            l, r = s.split(c, 1)
            return {'cmp': c, 'left': parse_operand(l), 'right': parse_operand(r)}
    return parse_operand(s)

def _split_top(s, sep):                # 괄호 depth 0에서만 sep로 분리(중첩조건 보존)
    parts = []; depth = 0; cur = ''; i = 0
    while i < len(s):
        c = s[i]
        if c == '(': depth += 1
        elif c == ')': depth -= 1
        if depth == 0 and s[i:i+len(sep)] == sep:
            parts.append(cur); cur = ''; i += len(sep); continue
        cur += c; i += 1
    parts.append(cur)
    return parts

def _strip_outer_paren(s):             # 전체를 감싼 괄호만 벗기기(( A && B ) → A && B)
    s = s.strip()
    while s.startswith('(') and s.endswith(')'):
        depth = 0; outer = True
        for i, c in enumerate(s):
            if c == '(': depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0 and i != len(s) - 1: outer = False; break
        if outer: s = s[1:-1].strip()
        else: break
    return s

def parse_condition(cond):
    if not cond: return None
    cond = _strip_outer_paren(str(cond).strip())
    ors = _split_top(cond, '||')
    if len(ors) > 1: return {'or': [parse_condition(p) for p in ors]}
    ands = _split_top(cond, '&&')
    if len(ands) > 1: return {'and': [parse_condition(p) for p in ands]}
    return parse_cmp(cond)

# ── 마스터리 2종: SkillData(714, ParentSkillIndex 귀속) + StatData(98, 캐릭터 전역 CreatureIndex별) ──
MT = load('HeroSkillMasteryTable.json')
def build_masteries():
    by_parent = defaultdict(list)   # SkillData → 부모 스킬 귀속
    by_job = defaultdict(list)      # StatData → 직업(CreatureIndex) 전역
    for r in MT:
        idx = r.get('MasteryIndex'); req = r.get('RequireCreatureLevel'); reqm = r.get('RequireMasteryIndex')
        if r.get('StatData'):
            for sd in (r['StatData'] if isinstance(r['StatData'], list) else [r['StatData']]):
                by_job[str(r.get('CreatureIndex'))].append(
                    {'idx': idx, 'name': f"{sd.get('Type')} +{sd.get('Value')}", 'desc': f"{sd.get('Type')} +{sd.get('Value')}",
                     'requireLevel': req, 'requires': reqm, 'kind': 'stat',
                     'effect': {'type': sd.get('Type'), 'value': sd.get('Value')}})
        else:
            msi = str(r.get('SkillIndex'))
            if msi in ST:
                parent = str(r.get('ParentSkillIndex'))
                nm = MMAP.get((parent, int(req)), (None, None)) if req and str(req).isdigit() else (None, None)
                by_parent[parent].append(
                    {'idx': idx, 'name': nm[0], 'desc': nm[1], 'requireLevel': req, 'requires': reqm, 'kind': 'skill',
                     'effect': {'skill': msi, 'ops': [ir_op(op, i) for i, op in enumerate(ST[msi].get('Operations') or [])]}})
    return by_parent, by_job

# ── 전 직업 IR 빌드 + 커버리지 1:1(각 원본 op가 IR에 반영됐나) ──
def collect_opids(node, acc):
    for op in node.get('ops', []): acc.add((node['id'], op['no']))
    for m in node.get('masteries', []):
        for op in m.get('effect', {}).get('ops', []): acc.add((m['effect']['skill'], op['no']))
    for c in node.get('children', []): collect_opids(c, acc)

def build_full_ir():
    by_parent, by_job = build_masteries()
    nodes = {}
    for si in sorted(x for x in HS if not is_sup(x)):   # HS 정식 전부(비동료) = 게이트 seed와 동일
        n = build_node(si)
        if n: n['masteries'] = by_parent.get(si, []); nodes[si] = n
    # ★분모 = 게이트와 동일 클로저(OWN + FollowLevel + ProjectileTable.SkillIndex, 중첩허용) + 마스터리효과
    def gate_children(si):
        s = ST.get(si); o = set()
        for op in (s.get('Operations') or []):
            if isinstance(op, dict) and op.get('Type') in OWN and (op.get('Values') or []):
                for v in op['Values']:
                    if str(v) in ST: o.add(str(v)); break
                if op.get('Type') in ('CreateProjectile', 'FireProjectile'):
                    pi = str(op['Values'][0])
                    if pi in PROJ and PROJ[pi].get('SkillIndex'):
                        for x in (PROJ[pi]['SkillIndex'] if isinstance(PROJ[pi]['SkillIndex'], list) else [PROJ[pi]['SkillIndex']]):
                            if str(x) in ST: o.add(str(x))
        if s.get('NextSkillIndex') and str(s['NextSkillIndex']) in ST: o.add(str(s['NextSkillIndex']))
        for c in fc.get(si, []): o.add(c)
        return o
    seen = {x for x in HS if not is_sup(x)}; stk = list(seen)
    while stk:
        c = stk.pop()
        for x in gate_children(c):
            if x not in seen: seen.add(x); stk.append(x)
    defs = build_state_defs(seen)
    msi = {str(r.get('SkillIndex')) for r in MT if str(r.get('SkillIndex')) in ST}
    pool = seen | msi
    # 커버리지: 게이트 분모 전 op vs IR 반영 op
    all_ops = {(si, i) for si in pool for i, op in enumerate(ST[si].get('Operations') or []) if isinstance(op, dict)}
    ir_ops = set()
    for n in nodes.values(): collect_opids(n, ir_ops)
    for d in defs.values():
        for e in d['effects']: ir_ops.add((e['skill'], e['opNo']))
    missing = all_ops - ir_ops
    return nodes, defs, by_job, all_ops, ir_ops, missing

if __name__ == '__main__':
    if sys.argv[1:] == ['--build']:
        nodes, defs, by_job, all_ops, ir_ops, missing = build_full_ir()
        print(f'=== 전 직업 IR 빌드 ===')
        print(f'루트 스킬노드: {len(nodes)} | state_defs: {len(defs)} | 직업 stat마스터리: {sum(len(v) for v in by_job.values())}')
        print(f'커버리지 1:1: 분모 op {len(all_ops)} | IR 반영 {len(ir_ops & all_ops)} | 미반영 {len(missing)}')
        if missing:
            from collections import Counter as _C
            tc = _C(ST[si].get('Operations')[i].get('Type') for si, i in missing if isinstance(ST[si].get('Operations')[i], dict))
            print(f'  미반영 op type: {dict(tc.most_common(8))}')
        else:
            print('  ✅ 모든 분모 op가 IR에 1:1 반영됨')
        base = r'C:\Users\MhHs\Documents\project\mekiCalc\dev'
        json.dump({'nodes': nodes, 'state_defs': defs, 'stat_masteries': by_job},
                  io.open(os.path.join(base, 'clt2_ir_all.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        # 직업별 분리 저장 (skills_v2처럼 검토 편의)
        JOBS = {1:'hero',2:'paladin',3:'darkknight',4:'archmagetc',5:'archmagefp',6:'bishop',7:'bowmaster',
                8:'marksman',9:'nightlord',10:'shadower',11:'viper',12:'captain',13:'nightwalker',14:'windbreaker'}
        def _jof(si):
            ci = int(si[0]) if len(si) == 5 else (int(si[:2]) if len(si) == 6 else 0)
            return JOBS.get(ci)
        outdir = r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_ir'
        os.makedirs(outdir, exist_ok=True)
        # ★공용 스킬(_jof 미분류 len4, HeroSkillTable에 다중 CI 등록=전직업 공용 공속/이속 버프) → CI매핑으로 해당 직업들에 포함
        HST = load('HeroSkillTable.json')
        ci_common = defaultdict(set)
        for r in HST:
            csi = str(r.get('SkillIndex'))
            if csi in nodes and _jof(csi) is None:
                ci_common[int(r['CreatureIndex'])].add(csi)
        by_job_nodes = {}
        for si, n in nodes.items():
            j = _jof(si)
            if j: by_job_nodes.setdefault(j, {})[si] = n
        for ci, j in JOBS.items():
            jn = dict(by_job_nodes.get(j, {}))
            for csi in ci_common.get(ci, []): jn[csi] = nodes[csi]   # 공용 스킬 주입
            json.dump({'job': j, 'nodes': jn, 'statMasteries': by_job.get(str(ci), [])},
                      io.open(os.path.join(outdir, j + '.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        json.dump({'state_defs': defs}, io.open(os.path.join(outdir, '_state_defs.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print(f'  전직업 IR: dev/clt2_ir_all.json + 직업별 data/skills_ir/*.json ({len(nodes)}노드)')
        sys.exit()
    targets = sys.argv[1:] or ['91010', '94100', '44020']  # 럭키세븐/메이플용사/프리징브레스
    for t in targets:
        node = build_node(t)
        print(f'===== {t} =====')
        print(json.dumps(node, ensure_ascii=False, indent=1)[:1400])
        print()
    # 성장 검증
    print('=== 성장 검증(럭키세븐 slot21 lv100) ===')
    print('Factor[21][100]/1000 =', growth_factor(['0', '21'], 100), '(예상 1.4)')
    # State 검증: 본체 클로저 기준 state_defs
    seed = [i for i in HS if not is_sup(i)]; seen = set(seed); stk = list(seed)
    while stk:
        c = stk.pop()
        for n in own_children(c):
            if n not in seen: seen.add(n); stk.append(n)
    defs = build_state_defs(seen)
    print(f'\n=== State 검증: state_defs {len(defs)}종 ===')
    for nm in ['SerpentStone', 'AssaultMode', 'LuckyDice_1', 'LuckyDice_6', 'DarkSight']:
        d = defs.get(nm)
        if d:
            print(f'  {nm}: maxStack={d["maxStack"]} kind={d["kind"]} stackLv={d["stackLevel"]} '
                  f'효과{len(d["effects"])}개 grant{len(d["grantedBy"])} clear{len(d["clearedBy"])}')
            if d['effects']: print(f'     효과예: {d["effects"][0]["type"]} {d["effects"][0]["values"]}')
    # 마스터리 검증
    by_parent, by_job = build_masteries()
    print(f'\n=== 마스터리 검증: SkillData(부모귀속) {sum(len(v) for v in by_parent.values())}개, StatData(직업전역) {sum(len(v) for v in by_job.values())}개 ===')
    print(f'  럭키세븐(91010) SkillData 마스터리 {len(by_parent.get("91010", []))}개:')
    for m in by_parent.get('91010', [])[:4]:
        eff = m['effect']['ops']
        types = [o.get('type') for o in eff if o.get('type')]
        print(f'    idx={m["idx"]} 해금lv={m["requireLevel"]} 효과op={types}')
    print(f'  나이트로드(CI9) StatData 전역 마스터리 {len(by_job.get("9", []))}개:')
    for m in by_job.get('9', [])[:4]:
        print(f'    {m["effect"]["type"]} +{m["effect"]["value"]} (해금lv{m["requireLevel"]})')
