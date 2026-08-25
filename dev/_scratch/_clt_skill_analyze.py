# 클뜯 스킬 5분류 분석기 → 스킬 마스터 데이터(JSON) 자동 생성
# 5분류: 딜 / 버프(딜) / 디버프(딜) / 간접딜(변환경유) / 딜무관(유틸)
# 기능: 성장률 태깅 · Attack=공격력 · 소환수 자동판정 · 마스터리 수치 파싱
# 사용: python _clt_skill_analyze.py <CI> [--json]
# -*- coding: utf-8 -*-
import io, json, os, sys, re
sys.stdout.reconfigure(encoding='utf-8')
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))

ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r
HS = load('HeroSkillTable.json')
HM = load('HeroSkillMasteryTable.json')
SP = {str(r.get('ProjectileIndex')): r for r in load('SkillProjectileTable.json')}
INDEP = {str(r['SkillIndex']) for r in HS}   # 독립 스킬(HeroSkillTable 등록) = 습득레벨 있음
STATE = {str(r.get('StateName')): r for r in load('StateTable.json')}   # 스택 시스템(콤보 ComboAttack MaxStack5 등)

# 소환수 AI 명령·표식 OnAttack 등 정적추적 불가 딜스킬 → 실제 딜 SkillIndex 수동 매핑(부모→[딜들])
# 직업 확장 시 여기에 추가. 자동추적이 실패한 스킬만 등록됨(본체 dmg/summon 없을 때만 적용).
DEAL_OVERRIDE = {
    '32030': ['32021'],   # 다크나이트 비홀더 쇼크(비홀더 소환수 명령) → 70%×6
    '33040': ['33041'],   # 다크나이트 비홀더 도미넌트(소환수 활성화 ModSkillEnabled) → 소환수 dot딜 60%
    '34060': ['34062'],   # 다크나이트 비홀더스 리벤지(OnAttack 낙인) → 850%×2
    '92040': ['92041'],   # 나이트로드 마크 오브 어쌔신(표식 OnAttack) → 300%
}
SDBY = {}
for r in load('SkillDescParamTable.json'):
    SDBY.setdefault(str(r.get('SkillIndex')), []).append(r)

PROC_TYPES = ('OnAttack', 'OnHit', 'OnDamage', 'OnActiveSkill')

def build_summon_map():
    """소환 종속 매핑(§⑤ 소환 가동률). 소환수 proj(지속 3~99998s) 소환 스킬 + 종속 딜 스킬 → {summonSkill, summonDurMs}.
    소환 스킬=cd 있는 CreateProjectile 주체. 종속=①소환 시 함께 생성되는 딜(도미넌트: proj번호=skill번호) ②FireProjectile로 소환수 부리는 딜(쇼크).
    가동률은 신엔진이 summonSkill의 effCd(마스터리+스탯 쿨감)로 런타임 계산 → 정적 오판 방지(비홀더 마스터리 재사용 -30%)."""
    owner = {}   # proj_idx -> (summon_si, dur_ms)  소환수 proj를 만드는 cd 있는 스킬
    for si, s in ST.items():
        cd = str(s.get('CoolTimeMs'))
        if not (cd.isdigit() and int(cd) > 0):
            continue
        for o in (s.get('Operations') or []):
            if isinstance(o, dict) and o.get('Type') == 'CreateProjectile':
                pv = o.get('Values') or []
                p = str(pv[0]) if pv else ''
                pr = SP.get(p)
                dur = str(pr.get('DurationTimeMs')) if pr else ''
                if dur.isdigit() and 3000 <= int(dur) < 99999999:
                    owner.setdefault(p, (si, int(dur)))
    deps = {}    # dep_skill_idx -> {summonSkill, summonDurMs}
    # ① 소환 스킬이 CreateProjectile로 함께 만드는 딜 스킬(proj번호=skill번호, 비홀더 도미넌트 33040)
    for p, (osi, dur) in owner.items():
        if p != osi and p in ST:
            deps[p] = {'summonSkill': osi, 'summonDurMs': dur}
    # ② 딜 스킬이 FireProjectile로 소환수 proj를 부림(비홀더 쇼크 32030)
    for si, s in ST.items():
        for o in (s.get('Operations') or []):
            if isinstance(o, dict) and o.get('Type') == 'FireProjectile':
                pv = o.get('Values') or []
                p = str(pv[0]) if pv else ''
                if p in owner and si != owner[p][0]:
                    osi, dur = owner[p]
                    deps.setdefault(si, {'summonSkill': osi, 'summonDurMs': dur})
    return deps

SUMMON_DEPS = build_summon_map()

def trigger_require_state(si):
    """②축 발동 게이팅(sim2): TriggerCondition $CreatureStateCount(...;State) >=N/>N → requireState 'State:N'(스택≥N).
    114090 어썰트모드(>0)→'AssaultMode'·113030 서펜트스톤(>=5)→'SerpentStone:5'. ==0(배제)은 requireState 아님."""
    tc = str((ST.get(str(si)) or {}).get('TriggerCondition') or '')
    m = re.search(r'StateCount\([^;]+;(\w+)\)\s*(>=|>)\s*(\d+)', tc)
    if m:
        stnm, op, n = m.group(1), m.group(2), int(m.group(3))
        thr = n if op == '>=' else n + 1   # >N → N+1(>0 → 1)
        return f"{stnm}:{thr}" if thr > 1 else stnm
    # OnState 조건: $Trigger.StateName == 'X' → requireState X(어드밴스드 홀리 차지 24090 HolyCharge).
    m2 = re.search(r"StateName\s*==\s*'(\w+)'", tc)
    return m2.group(1) if m2 else None

def trigger_consume(si, reqstate):
    """발동 시 requireState 스택 전소모: op ClearState가 그 State를 지우면 True(서펜트스톤 '모든 스톤 소모')."""
    if not reqstate:
        return False
    stnm = reqstate.split(':')[0]
    for o in ((ST.get(str(si)) or {}).get('Operations') or []):
        if isinstance(o, dict) and o.get('Type') == 'ClearState' \
                and stnm in [str(x) for x in (o.get('Values') or [])]:
            return True
    return False

def trigger_counter(si):
    """②축 카운터 발동(sim2): OnActiveSkill/OnAttack + 자기부여 State(MaxStack N) + ClearState(같은 State)
    = N회마다 발동. counterEvery=MaxStack, counterOn(IsBaseAttack→basicAttack). 디바인저지먼트 24070 '기본공격 10회마다'."""
    s = ST.get(str(si)) or {}
    tt = s.get('TriggerType')
    if tt not in ('OnActiveSkill', 'OnAttack', 'OnHit'):
        return None
    given, cleared = None, set()
    for o in (s.get('Operations') or []):
        if not isinstance(o, dict):
            continue
        if o.get('Type') == 'State' and o.get('State'):
            given = o.get('State')
        if o.get('Type') == 'ClearState':
            cleared.update(str(x) for x in (o.get('Values') or []))
    if not (given and given in cleared):
        return None
    mx = STATE.get(given, {}).get('MaxStack')
    if not (mx and str(mx).isdigit() and int(mx) > 1):
        return None
    tc = str(s.get('TriggerCondition') or '')
    con = 'basicAttack' if 'IsBaseAttack' in tc else ('activeSkill' if tt == 'OnActiveSkill' else 'basicAttack')
    return {'counterEvery': int(mx), 'counterOn': con}

def parse_dmg_cond(ec):
    """damage 엔트리 조건(EnableCondition) → sim2 cond. IsBoss→bossOnly·StateCount==N→requireState('State:N')·==0→requireStateNot.
    조건없음(None)=합산(전부 발동). 씨서펜트버스트(어썰트모드 배타)·피스트인레이지(보스추가타)."""
    if not ec:
        return None
    ec = str(ec)
    if 'IsBoss' in ec:
        return {'bossOnly': True}
    m = re.search(r"StateCount\([^;]+;(\w+)\)\s*==\s*(\d+)", ec)
    if m:
        st, n = m.group(1), int(m.group(2))
        if n == 0:
            return {'requireStateNot': st}
        return {'requireState': f"{st}:{n}" if n > 1 else st}
    return None

def buff_exclusive_group(si):
    """배타 버프(sim2 exclusiveGroup): op ModSkillDisabled[self, 1..N] N개 = N면 배타(럭키다이스 6면 주사위).
    같은 그룹 버프 중 1개만 발동. 없으면 None."""
    s = ST.get(str(si)) or {}
    n = sum(1 for o in (s.get('Operations') or [])
            if isinstance(o, dict) and o.get('Type') == 'ModSkillDisabled'
            and str((o.get('Values') or [None])[0]) == str(si))
    return f"excl_{si}" if n >= 2 else None

def trigger_cond_cd(si, depth=0):
    """②축 발동: TriggerCondition '$Trigger.SkillIndex == N' = 특정 스킬 N 발동에 종속 → 발동주기 = N의 cd.
    바이퍼 인레이지(114080=피스트인레이지 16s에만, 114090=114080에만)가 상시 발동으로 과대되던 것 정정.
    N의 cd가 없으면 N의 TriggerCondition을 재귀 추적(114090→114080→114020=16s)."""
    s = ST.get(str(si)) or {}
    m = re.search(r'\$Trigger\.SkillIndex\s*==\s*(\d+)', str(s.get('TriggerCondition') or ''))
    if not m or depth > 4:
        return None
    dep = m.group(1)
    dcool = str((ST.get(dep) or {}).get('CoolTimeMs'))
    if dcool.isdigit() and int(dcool) > 0:
        return int(dcool)
    return trigger_cond_cd(dep, depth + 1)

def deal_trigger(parent_si, child_si):
    """②축 연계 딜 발동(SCHEMA 결정론 규칙). 부모→자식 연계 딜의 '진짜 발동 주기'를 부모 기준으로 판정.
    ① 부모 CoolTimeMs 있으면 그게 지배(쿨마다 1회, 부모 proc이면 내부쿨=CoolTimeMs로 procPct 유지).
       - 트라이엄프페더(부모 OnAttack15%+Cool18s)·엘리다크(None+28s)·스톰브링어(OnActiveSkill+25s)·비홀더리벤지(OnStart+5s).
    ② 없고 부모 OnTimer: ModRemainTriggerTime(무한화)면 소환셋업→자식 proc, 아니면 OnTimer 주기(마크 표식 5s).
    ③ 부모 쿨·타이머 없음: 자식 발동 그대로. 자식 None+Dot=소환수 상시 지속딜(초당 1회 근사)."""
    ps = ST.get(str(parent_si)) or {}
    cs = ST.get(str(child_si)) or {}
    pcool = str(ps.get('CoolTimeMs'))
    ptt = ps.get('TriggerType')
    modremain = any(isinstance(o, dict) and o.get('Type') == 'ModRemainTriggerTime'
                    for o in (ps.get('Operations') or []))
    # ① 부모 CoolTimeMs 지배
    if pcool.isdigit() and int(pcool) > 0:
        proc = ptt in PROC_TYPES
        return {'triggerType': ptt if proc else 'None',
                'triggerRatio': ps.get('TriggerRatio') if proc else None,
                'coolTimeMs': int(pcool)}
    # ② 부모 OnTimer
    if ptt == 'OnTimer':
        if modremain:   # 소환 셋업(분신 무한화) → 자식 proc(쉐도우 파트너)
            return {'triggerType': cs.get('TriggerType'), 'triggerRatio': cs.get('TriggerRatio'),
                    'coolTimeMs': cs.get('CoolTimeMs')}
        return {'triggerType': 'OnTimer', 'triggerTimeMs': ps.get('TriggerTimeMs')}   # 마크: 표식 주기 딜
    # ③ 부모 쿨·타이머 없음 → 자식 발동. 자식 None+Dot = 소환수 상시 지속딜(비홀더 도미넌트).
    ctt = cs.get('TriggerType')
    cdot = any('GetDot' in str(o.get('Type', '')) or 'Dot' in str(o.get('Values'))
               for o in (cs.get('Operations') or []) if isinstance(o, dict))
    if ctt in (None, 'None') and cdot:
        return {'triggerType': 'OnTimer', 'triggerTimeMs': 1000}
    return {'triggerType': ctt, 'triggerRatio': cs.get('TriggerRatio'),
            'triggerTimeMs': cs.get('TriggerTimeMs'), 'coolTimeMs': cs.get('CoolTimeMs')}

def resolve_func(si, funcval):
    """#Func(X) → 스킬의 SkillDescParam Calculate(Const X ... Div D)로 X/D 해석."""
    m = re.match(r'#\w+\((\d+)\)', str(funcval))
    if not m: return None
    X = int(m.group(1))
    for p in SDBY.get(str(si), []):
        calc = p.get('Calculate') or []
        if len(calc) >= 2 and calc[0] == 'Const' and calc[1] == str(X):
            v = float(X)
            if 'Div' in calc: v /= float(calc[calc.index('Div') + 1])
            return v
    return None

GROWTH = {'12': 0.5, '21': 0.4, '22': 0.3, '3': 3.0}
BUFF_STATS = {'FinalDamage', 'AttackPower', 'Attack', 'BaseAttackPower', 'AttackPowerToBoss',
    'AttackPowerExcludeBoss', 'AttackPowerInCc', 'AttackSpeed', 'CriticalPower', 'CriticalChance',
    'MinDamageRatio', 'MaxDamageRatio', 'SkillPower', 'PiercePower', 'HealPower', 'Str', 'Int', 'Dex', 'Luk',
    'BuffDurationRatio'}
DEBUFF_DEAL = {'Weakness'}
UTIL_STATS = {'Toughness', 'DebuffResist', 'AvoidChance', 'HpRegenRatio', 'MpRegenRatio',
    'MpConsumeRatio', 'MinHpRatio', 'HitChance', 'RunSpeedMms', 'MaxHp', 'MaxMp'}

def growth_of(op):
    vlf = op.get('ValueLevelFactors')
    return GROWTH.get(str(vlf[-1])) if vlf else None

def coeff_num(c, si=None):
    """GetDamage 계수 raw → %. 함수형(#..)은 SkillDescParam으로 해석, 동적($..)은 원문 유지."""
    s = str(c)
    if s.lstrip('-').isdigit(): return int(s) / 10
    if s.startswith('#') and si is not None:
        r = resolve_func(si, s)
        if r is not None: return r
    return s   # $CreatureStateCount 등 동적

def indirect_src(ci):
    srcs = set()
    for r in HS:
        if str(r.get('CreatureIndex')) != ci: continue
        s = ST.get(str(r['SkillIndex']))
        if not s: continue
        for op in (s.get('Operations') or []):
            if not isinstance(op, dict): continue
            vals = op.get('Values') or []
            if op.get('Type', '').startswith('ModStat') and vals and vals[0] in BUFF_STATS:
                for v in vals[1:]:
                    if isinstance(v, str) and 'CreatureFinalStat' in v:
                        m = re.search(r';(\w+)\)', v)
                        if m and m.group(1) != vals[0]: srcs.add(m.group(1))
    return srcs

def stat_and_val(op):
    t = op.get('Type', ''); vals = op.get('Values') or []; ms = op.get('ModStats') or []
    if ms and isinstance(ms[0], dict): return ms[0].get('StatType'), ms[0].get('StatValue')
    if t in ('ModStat', 'ModStatR') and vals: return vals[0], (vals[1] if len(vals) > 1 else None)
    if t == 'ModSkillStat' and len(vals) >= 4: return vals[2], vals[3]
    if t == 'ModStatAddValue' and len(vals) >= 2: return vals[0], vals[1]
    return None, None

def summon_meta(op):
    """CreateProjectile → 자식 계수 추적. 자식 독립이면 None(별도 스킬로 처리), 종속이면 summon 딜."""
    vals = op.get('Values') or []
    proj = SP.get(str(vals[0])) if vals else None
    if not proj: return None
    childs = [str(x) for x in (proj.get('SkillIndex') or [])]
    child = next((c for c in childs if c not in INDEP), None)   # 종속 자식(피닉스형)
    if not child: return None   # 자식이 전부 독립(비홀더형) → 소환상태만, 자식은 별도 스킬
    cs = ST.get(child)
    dmg = None
    for cop in (cs.get('Operations') or []):
        if isinstance(cop, dict) and 'GetDamage' in cop.get('Type', ''):
            cv = cop.get('Values') or []
            dmg = {'coeff': coeff_num(cv[0], child) if cv else '?', 'hits': cv[2] if len(cv) > 2 else '1',
                   'growth': growth_of(cop), 'dot': 'Dot' in str(cv)}
    tick = proj.get('TickTimeMs')
    return {'child': child, 'durationMs': proj.get('DurationTimeMs'),
            'tickMs': (tick[0] if isinstance(tick, list) else tick), 'dmg': dmg,
            'maxTarget': proj.get('MaxTargetCount')}

def analyze(si, isrc, depth=0):
    s = ST.get(si)
    if not s: return None
    out = {'dmg': [], 'buff': [], 'debuff': [], 'indirect': [], 'util': [], 'summon': None, 'skillBoost': []}
    ops = s.get('Operations') or []
    # 스킬 대표 지속시간 = 실제 지속 op의 최대 DurationMs. 상시 마커(99999999↑)는 제외(대표로 부적절).
    real_durs = [op.get('DurationMs') for op in ops
                 if isinstance(op, dict) and op.get('DurationMs') and int(op.get('DurationMs')) < 99999999]
    skill_dur = max(real_durs, key=int, default=None)
    for op in ops:
        if not isinstance(op, dict): continue
        t = op.get('Type', ''); tgt = op.get('TargetType', ''); g = growth_of(op)
        if 'GetDamage' in t or 'GetDot' in t:
            vals = op.get('Values') or []
            hits = vals[2] if len(vals) > 2 else '1'
            # ①축 타격(SCHEMA): 순수숫자 tick 배열=각 틱 1타 → hits=배열길이(Values[2] 무시).
            # 표기틱('시각[인덱스]')=고유 대상 인덱스 개수. GetDot(지속)은 제외.
            stk = s.get('TickTimeMs')
            if 'GetDot' not in t and isinstance(stk, list) and len(stk) > 1:
                if all(str(x).isdigit() for x in stk):
                    hits = str(len(stk))                          # 순수숫자 = N타
                else:
                    idx = set()
                    for x in stk:
                        mm = re.search(r'\[([\d:]+)\]', str(x))
                        if mm: idx.update(mm.group(1).split(':'))
                    if idx: hits = str(len(idx))                  # 표기틱 = 고유 인덱스 수
            item = {'coeff': coeff_num(vals[0], si) if vals else '?',
                'hits': hits, 'growth': g,
                'dot': ('GetDot' in t) or ('Dot' in str(vals))}
            # damage 조건(§3): op EnableCondition → cond(bossOnly/requireState/requireStateNot). 조건없음=합산.
            _c = parse_dmg_cond(op.get('EnableCondition'))
            if _c: item.update(_c)
            # 스킬 고유 보스/일반몹뎀(op ModStats 기본값, 스탯창 미포함): 인핸스레이징블로우 보스뎀+100% 등. 마스터리 statAdd와 합산.
            for msx in (op.get('ModStats') or []):
                sv = msx.get('StatValue')
                if sv and str(sv) not in ('0',):
                    if msx.get('StatType') == 'AttackPowerToBoss': item['bossDmg'] = float(sv) / 10
                    elif msx.get('StatType') == 'AttackPowerExcludeBoss': item['normalDmg'] = float(sv) / 10
            if 'GetDot' in t:   # 지속 독/도트(베놈 등): op의 Dur/Tick = 실제 지속/주기(1틱=coeff, dur/tick 회 반복)
                tick = op.get('TickTimeMs')
                item['dotDurMs'] = op.get('DurationMs')
                item['dotTickMs'] = tick[0] if isinstance(tick, list) else tick
            out['dmg'].append(item)
        elif 'GetHeal' in t or 'GetHot' in t:
            vals = op.get('Values') or []
            out['util'].append({'stat': 'Heal', 'val': coeff_num(vals[0], si) if vals else '?', 'growth': g})
        elif t == 'CreateProjectile':
            sm = summon_meta(op)
            if sm and sm.get('dmg'): out['summon'] = sm
        elif t in ('UseSkillToTarget', 'UseSkill', 'UsePassiveSkill') and depth < 4:
            vals = op.get('Values') or []
            child = str(vals[0]) if vals else None
            if child and child in ST:
                ca = analyze(child, isrc, depth + 1)   # 자식 스킬(제네시스→64031 등) 딜 병합
                if ca:
                    # 연계 딜 발동 = 부모 기준 결정론 규칙(deal_trigger). 부모 쿨/타이머가 실질 발동주기.
                    if ca['dmg'] and not out.get('dealTrigger'):
                        out['dealTrigger'] = deal_trigger(si, child)
                        out['dealChild'] = child   # 딜 자식 index(메용 등 특정스킬강화 대상 매핑용)
                    # damage 조건(§3) 전파: 부모 UseSkill op EnableCondition을 자식 딜에(씨서펜트버스트 어썰트모드 배타 UseSkill 112031/113051).
                    _pc = parse_dmg_cond(op.get('EnableCondition'))
                    if _pc:
                        for _d in ca['dmg']:
                            if not any(k in _d for k in ('bossOnly', 'requireState', 'requireStateNot')):
                                _d.update(_pc)
                    # 자식의 모든 효과 병합(다크사이트 91030→91031 버프 등, 딜만이 아니라 전체).
                    out['dmg'].extend(ca['dmg'])
                    out['buff'].extend(ca['buff'])
                    out['debuff'].extend(ca['debuff'])
                    out['util'].extend(ca['util'])
                    out['indirect'].extend(ca['indirect'])
                    out['skillBoost'].extend(ca.get('skillBoost', []))
                    if ca.get('summon') and not out['summon']: out['summon'] = ca['summon']
        elif t == 'FireProjectile':   # 소환수 발사(비홀더쇼크→비홀더 프로젝타일 32023 등): 프로젝타일 자식 GetDamage
            vals = op.get('Values') or []
            proj = SP.get(str(vals[0])) if vals else None
            for ch in ((proj.get('SkillIndex') if proj else []) or []):
                cs = ST.get(str(ch))
                for cop in ((cs.get('Operations') if cs else []) or []):
                    if isinstance(cop, dict) and 'GetDamage' in cop.get('Type', ''):
                        cv = cop.get('Values') or []
                        out['dmg'].append({'coeff': coeff_num(cv[0], str(ch)) if cv else '?',
                            'hits': cv[2] if len(cv) > 2 else '1', 'growth': growth_of(cop), 'dot': 'Dot' in str(cv)})
        elif t == 'ModSkillStat':   # 특정 스킬 강화(부모SI, opNo, stat, value): 메이플 용사 등 '스킬별 최종뎀'
            vals = op.get('Values') or []
            if len(vals) >= 4:
                out['skillBoost'].append({'targetSkill': str(vals[0]), 'stat': vals[2], 'value': vals[3], 'growth': g})
        elif t in ('ModStat', 'ModStatR', 'State', 'ModStatAddValue', 'ModStatOnTick'):
            stat, val = stat_and_val(op)
            if stat is None: continue
            # 버프/디버프 지속시간(ms). op에 DurationMs 없으면 상시(패시브)로 간주 → None.
            # 지속시간: ① op 자신 → ② 같은 State 접두어(예: HolySymbol_*)의 지속 op → ③ 스킬 대표.
            dur = op.get('DurationMs')
            if not dur and op.get('State'):
                pre = op.get('State').split('_')[0]
                same = [o.get('DurationMs') for o in ops if isinstance(o, dict) and o.get('DurationMs')
                        and int(o.get('DurationMs')) < 99999999
                        and o.get('State') and o.get('State').split('_')[0] == pre]
                if same: dur = max(same, key=int)
            item = {'stat': stat, 'val': val, 'growth': g, 'tgt': tgt,
                    'durationMs': dur or skill_dur, 'state': op.get('State')}
            # 스택 시스템: state의 MaxStack>1이면 스택형(콤보 등). val=스택당, 정상상태=val×maxStack(엔진서).
            st_nm = op.get('State')
            if st_nm and STATE.get(st_nm):
                try:
                    mx = int(STATE[st_nm].get('MaxStack') or 1)
                    if mx > 1: item['stateMax'] = mx
                except (ValueError, TypeError):
                    pass
            # 함수형 값 $CreatureStateCount(...;State)[*N]: 참조 State 스택수×N. 정상상태=만스택(State MaxStack+마스터리 stackPlus)×N (엔진서 치환).
            mref = re.search(r'StateCount\([^;]+;(\w+)\)\s*(?:\*\s*(\d+))?', str(val))
            if mref:
                item['stackRef'] = mref.group(1)
                item['stackMult'] = int(mref.group(2)) if mref.group(2) else 1
                item['stackRefMax'] = int(STATE.get(mref.group(1), {}).get('MaxStack') or 1)
            if stat in isrc: out['indirect'].append(item)
            elif stat in DEBUFF_DEAL or (tgt in ('Target', 'Enemy') and stat not in UTIL_STATS and stat not in BUFF_STATS):
                out['debuff'].append(item)
            elif stat in BUFF_STATS: out['buff'].append(item)
            else: out['util'].append(item)
        elif t in ('Stun', 'Dispel', 'Cleanse'):
            out['util'].append({'type': t, 'tgt': tgt})
    if not out['dmg'] and not out['summon'] and si in DEAL_OVERRIDE:   # 자동추적 실패 시 수동 매핑
        for ov in DEAL_OVERRIDE[si]:
            ovs = ST.get(str(ov))
            for cop in ((ovs.get('Operations') if ovs else []) or []):
                if isinstance(cop, dict) and 'GetDamage' in cop.get('Type', ''):
                    cv = cop.get('Values') or []
                    out['dmg'].append({'coeff': coeff_num(cv[0], str(ov)) if cv else '?',
                        'hits': cv[2] if len(cv) > 2 else '1', 'growth': growth_of(cop),
                        'dot': 'Dot' in str(cv), 'override': True})
            # override 연계 딜도 동일 규칙(deal_trigger). 부모(si) 쿨/타이머/State가 실질 발동주기.
            # 마크(부모 OnTimer5s)·비홀더리벤지(부모 Cool5s)·비홀더도미넌트(자식 None+Dot=소환지속) 자동 분기.
            if ovs and out['dmg'] and not out.get('dealTrigger'):
                out['dealTrigger'] = deal_trigger(si, ov)
                out['dealChild'] = str(ov)
        # 소환수 딜 광역 대상수(projectile MaxTargetCount): 컨텐츠 마리수까지 스케일(도미넌트 6·쇼크 6대상).
        # override 딜 스킬(ov)이 담긴 projectile의 maxHit를 찾는다(도미넌트 proj33040·쇼크 proj32021).
        ovset = set(str(x) for x in DEAL_OVERRIDE[si])
        for pr in SP.values():
            sidx = set(str(x) for x in (pr.get('SkillIndex') or []))
            if ovset & sidx and pr.get('MaxTargetCount'):
                try:
                    mh = int(pr['MaxTargetCount'])
                    if mh > 1: out['overrideTargets'] = mh
                    break
                except (ValueError, TypeError):
                    pass
        # override 딜 스킬을 dealChild로 → 그 딜 대상 skillBoost(비홀더 쇼크 강화 33050=32021 최종뎀+100%) 연결.
        if out['dmg'] and not out.get('dealChild'):
            out['dealChild'] = str(DEAL_OVERRIDE[si][0])
    return out

def mastery_effect(msi):
    """마스터리 SkillIndex → 효과 수치 파싱. 부모강화(ModSkillStat/ModSkill)·자체스탯(ModStat)·비율(ModSkillR)."""
    s = ST.get(msi)
    if not s: return None
    for op in (s.get('Operations') or []):
        if not isinstance(op, dict): continue
        t = op.get('Type', ''); vals = op.get('Values') or []
        if t == 'ModSkillStat' and len(vals) >= 4:            # 부모 스탯 강화: [부모, opNo, stat, value]
            return {'kind': 'skillStat', 'stat': vals[2], 'value': vals[3], 'growth': growth_of(op)}
        if t == 'ModSkill' and len(vals) >= 4:                 # 부모 op의 key 수정: [부모, opNo, key, value]
            pop = None                                          # 부모 op(Value 의미가 op에 따라 다름 — 딜=타수, 받피증=디버프값)
            ps = ST.get(str(vals[0]))
            if ps and str(vals[1]).lstrip('-').isdigit():
                pops = ps.get('Operations') or []
                i = int(vals[1])
                if 0 <= i < len(pops) and isinstance(pops[i], dict): pop = pops[i]
            pms = (pop.get('ModStats') or []) if pop else []   # 딜 op의 스킬고유 스탯 슬롯(보스뎀/일반몹뎀 등)
            return {'kind': 'modSkill', 'key': vals[2], 'value': vals[3],
                    'parentType': pop.get('Type', '') if pop else '',
                    'parentStat': pms[0].get('StatType') if pms else None,   # statAdd 대상 스탯(AttackPowerToBoss 등)
                    'targetSkill': str(vals[0]),   # cdFlatMs 등 cross-skill 대상(씨서펜트강화→씨서펜트버스트)
                    'growth': growth_of(op)}
        if t in ('ModSkillR', 'ModSkillProjectileR') and len(vals) >= 3:  # 쿨감/틱주기 비율(값/10=%)
            key = vals[2] if t == 'ModSkillR' else vals[1]
            v = vals[3] if t == 'ModSkillR' else vals[2]
            # ratioValue(key='Value'): 대상 op의 Value 배수. 부모 op로 대상 판별(skillBoost 최종뎀 / buff·debuff 스탯).
            pop = None
            if t == 'ModSkillR' and len(vals) >= 2 and str(vals[1]).lstrip('-').isdigit():
                ps = ST.get(str(vals[0])); pops = (ps.get('Operations') or []) if ps else []
                i = int(vals[1])
                if 0 <= i < len(pops) and isinstance(pops[i], dict): pop = pops[i]
            pt = pop.get('Type', '') if pop else ''
            pvals = (pop.get('Values') or []) if pop else []
            # stat 위치: ModSkillStat=[스킬,opNo,stat,val]→[2] / ModStatR·ModStat=[stat,val]→[0]
            pstat = (pvals[2] if len(pvals) > 2 else None) if ('ModSkillStat' in pt or 'Pipeline' in pt) else (pvals[0] if pvals else None)
            return {'kind': 'ratio', 'key': key, 'value': v, 'parentType': pt,
                    'parentStat': pstat, 'targetSkill': str(vals[0]) if t == 'ModSkillR' else None}
        if t in ('ModStat', 'ModStatR') and vals:              # 자체 스탯(크확강화 등)
            return {'kind': 'stat', 'stat': vals[0], 'value': vals[1] if len(vals) > 1 else None, 'growth': growth_of(op)}
        if t == 'ModSkillEnabled':                             # 조건부 딜 활성
            return {'kind': 'enable', 'value': vals}
        if t == 'ModSkillProjectile' and len(vals) >= 3:       # 소환체 대상수 등: [소환, key, value]
            return {'kind': 'projectile', 'key': vals[1], 'value': vals[2], 'growth': growth_of(op)}
        if t == 'ModStateMaxStack' and len(vals) >= 2:         # 콤보 최대중첩+N
            return {'kind': 'stateMaxStack', 'state': vals[0], 'value': vals[1]}
        if t == 'ModDamagePipelineStat' and len(vals) >= 4:    # 최종뎀 등(함수형 가능): [부모, opNo, stat, value]
            return {'kind': 'pipelineStat', 'stat': vals[2], 'value': vals[3], 'growth': growth_of(op)}
        if t == 'ChangeSkill' and len(vals) >= 2:              # 스킬 교체(134렙 강화)
            return {'kind': 'changeSkill', 'from': vals[0], 'to': vals[1]}
        if t == 'SetSkill' and len(vals) >= 4:                 # 스킬 값 설정
            return {'kind': 'setSkill', 'key': vals[2], 'value': vals[3]}
    return {'kind': 'unknown', 'op': (s.get('Operations') or [None, {}])[1].get('Type') if len(s.get('Operations') or []) > 1 else None}

def masteries_of(ci):
    m = {}
    for r in HM:
        if str(r.get('CreatureIndex')) != ci: continue
        p = str(r.get('ParentSkillIndex'))
        m.setdefault(p, []).append({'open': r.get('RequireCreatureLevel'), 'msi': str(r.get('SkillIndex')),
                                    'effect': mastery_effect(str(r.get('SkillIndex')))})
    for p in m: m[p].sort(key=lambda x: int(x['open']) if str(x['open']).isdigit() else 999)
    return m

def main():
    ci = sys.argv[1] if len(sys.argv) > 1 else '1'
    isrc = indirect_src(ci)
    mast = masteries_of(ci)
    skills = [r for r in HS if str(r.get('CreatureIndex')) == ci]
    rows = []
    for r in sorted(skills, key=lambda x: int(x['SkillIndex'])):
        si = str(r['SkillIndex']); typ = r.get('HeroSkillType', '')
        if si == '9000': continue
        a = analyze(si, isrc)
        if not a: continue
        rows.append((si, r, typ, a))
    if '--json' in sys.argv:
        out = {'creatureIndex': ci, 'indirectSrc': sorted(isrc), 'skills': []}
        for si, r, typ, a in rows:
            sk = ST[si]
            dt = a.get('dealTrigger') or {}   # 연계 딜 발동(deal_trigger 규칙): 부모 쿨/타이머가 실질 발동주기
            eg = buff_exclusive_group(si)     # 배타 버프(럭키다이스 6면): 그룹 내 1개만 발동
            if eg:
                for b in a.get('buff', []): b['exclusiveGroup'] = eg
            out['skills'].append({'index': si, 'open': r.get('RequireLevel'),
                'type': 'active' if typ == 'ActiveSkill' else 'passive',
                # 발동주기 지배 쿨: ①연계딜 deal_trigger coolTimeMs → ②TriggerCondition 종속 스킬 cd(인레이지) → ③스킬 쿨.
                'cooldownMs': (dt.get('coolTimeMs') if dt.get('coolTimeMs') is not None
                               else trigger_cond_cd(si) if trigger_cond_cd(si) is not None
                               else sk.get('CoolTimeMs')),
                # 대상 수: 숫자(광역), 또는 함수형($..BaseAttackMaxHitCount=평타대상수). raw 저장→스킬엔진서 해석.
                'targetCount': sk.get('TargetCount'),
                # ③축 대상(SCHEMA): 광역 = MaxRangeMm 4개값(사각범위) AND SkillTargetType=Enemy → 컨텐츠 마리수.
                # rangeType(Rect/None) 무관 — 궁니르·써든레이드는 rangeType None인데 사각광역.
                'area': isinstance(sk.get('MaxRangeMm'), list) and len(sk.get('MaxRangeMm')) >= 4 and sk.get('SkillTargetType') == 'Enemy',
                # 발동 메커니즘: Active(쿨)/OnTimer(주기·공속비례)/OnAttack(proc)/OnDamage 등. 딜 자식 있으면 그 trigger 우선.
                'triggerType': dt.get('triggerType') or sk.get('TriggerType'),
                'triggerRatio': dt.get('triggerRatio') or sk.get('TriggerRatio'),
                'triggerTimeMs': dt.get('triggerTimeMs') or sk.get('TriggerTimeMs'),
                # sim2 발동 게이팅(SCHEMA_PATCH §1): TriggerCondition StateCount → requireState, op ClearState → consumeRequire.
                'requireState': trigger_require_state(si),
                'consumeRequire': trigger_consume(si, trigger_require_state(si)),
                # 카운터 발동: 자기부여 State MaxStack + ClearState = N회마다(디바인저지먼트 10회).
                **(trigger_counter(si) or {}),
                'dealChild': a.get('dealChild'),
                # 소환 종속(§⑤ 가동률): summonSkill의 effCd(런타임 마스터리+스탯 쿨감)로 uptime 계산 → 딜에 곱.
                **(SUMMON_DEPS.get(si, {})),
                'classes': a, 'masteries': mast.get(si, [])})
        print(json.dumps(out, ensure_ascii=False, indent=1))
        return
    print(f"### CI{ci} 간접딜 소스: {isrc or '(없음)'}")
    for si, r, typ, a in rows:
        tags = [n for n, k in [('딜','dmg'),('버프','buff'),('디버프','debuff'),('간접딜','indirect'),('유틸','util')] if a[k]]
        if a['summon']: tags.append('소환딜')
        act = 'A' if typ == 'ActiveSkill' else 'P'
        print(f"[{act}] {si} lv{r.get('RequireLevel','-'):>4} {'+'.join(tags) or '(효과없음)':<16} 마스터리{len(mast.get(si,[]))}")
        for d in a['dmg']: print(f"       딜: {d['coeff']}% ×{d['hits']}타 성장{d['growth']}{' [dot]' if d['dot'] else ''}")
        if a['summon']:
            sm = a['summon']; d = sm['dmg']
            print(f"       소환딜: {d['coeff']}% (소환 {int(sm['durationMs'])/1000:.0f}초 / {int(sm['tickMs'])/1000:.1f}초주기 / {sm['maxTarget']}명)")
        for b in a['buff']: print(f"       버프: {b['stat']} {b['val']} 성장{b['growth']} [{b['tgt']}]")
        for b in a['debuff']: print(f"       디버프: {b['stat']} {b['val']} 성장{b['growth']}")
        for b in a['indirect']: print(f"       간접딜: {b['stat']} {b['val']} 성장{b['growth']}")
        for u in a['util']: print(f"       유틸: {u.get('stat', u.get('type'))} {u.get('val','')}")

main()
