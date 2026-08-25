# 스키마(SCHEMA.md) 기반 자동 검증: 클뜯 원본 → 기대 타수/대상 vs skills_v2.
# 불일치를 전부 출력(analyze 재작성/회귀의 안전망). -*- coding: utf-8 -*-
import io, json, os, glob, re
from collections import Counter
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r
PROJ = {str(r.get('ProjectileIndex')): r for r in load('SkillProjectileTable.json')}

def isbasic(si):
    try: suf = int(si) % 10000; return suf % 1000 == 10 and 1 <= suf // 1000 <= 4
    except (ValueError, TypeError): return False

def dmg_op(s):
    for o in (s.get('Operations') or []):
        if isinstance(o, dict) and ('GetDamage' in str(o.get('Type', '')) or 'GetDot' in str(o.get('Type', ''))):
            return o
    return None

def expect_hits(s, op):
    """①축 타격: 순수숫자 tick=N타 / 표기틱=고유인덱스 / GetDot=dot / else=Values[2]."""
    t = op.get('Type', '')
    if 'GetDot' in t:
        return 'dot'
    tk = s.get('TickTimeMs')
    if isinstance(tk, list) and len(tk) > 1:
        if all(str(x).isdigit() for x in tk):
            return len(tk)                               # 순수숫자 = N타
        idx = set()
        for x in tk:
            m = re.search(r'\[([\d:]+)\]', str(x))
            if m: idx.update(m.group(1).split(':'))
        if idx: return len(idx)                          # 표기틱 = 고유 인덱스 수
    v = op.get('Values') or []
    try: return int(v[2]) if len(v) > 2 else 1
    except (ValueError, TypeError): return 1

def expect_area(s):
    """③축 대상: MaxRangeMm 4개 + Enemy = 광역, else 단일."""
    mr = s.get('MaxRangeMm')
    return isinstance(mr, list) and len(mr) >= 4 and s.get('SkillTargetType') == 'Enemy'

# skills_v2 로드
mism = []
for f in glob.glob('data/skills_v2/*.json'):
    job = f.replace('\\', '/').split('/')[-1][:-5]
    for sk in json.load(open(f, encoding='utf-8'))['skills']:
        si = sk['index']
        s = ST.get(si)
        if not s: continue
        op = dmg_op(s)
        if not op: continue                              # 간접딜(override)은 별도 — 여기선 본체딜만
        dm = (sk.get('damage') or [{}])
        if not dm or not dm[0]: continue
        # 타수 대조 (평타 X010은 basic hits라 제외)
        eh = expect_hits(s, op)
        ah = dm[0].get('hits')
        if eh != 'dot' and not isbasic(si):
            try:
                if int(ah) != int(eh): mism.append((job, sk.get('name'), si, f'hits 기대{eh} != v2 {ah}'))
            except (ValueError, TypeError): pass
        # 대상(광역) 대조 — v2 targetCount가 광역이면 크거나 rangeType Rect
        ea = expect_area(s)
        av = sk.get('targetCount')
        area_v2 = bool(sk.get('area')) or (av not in (1, '1', None))
        if ea and not area_v2:
            mism.append((job, sk.get('name'), si, f'광역인데 v2 단일(tc={av} rt={sk.get("rangeType")})'))

print(f"=== 스키마 검증: 본체딜 스킬 불일치 {len(mism)}건 ===")
for j, nm, si, msg in mism: print(f"  [{j}] {nm}({si}): {msg}")

# ==== ②축 발동 검증: 딜 스킬 발동 sanity(폭주·무발동 회귀 안전망) ====
# 마크가 다시 proc+무내부쿨로 돌아가는 것(8.47배 폭주) 같은 발동 회귀를 잡는다.
PROC = ('OnAttack', 'OnHit', 'OnDamage', 'OnActiveSkill')
tmism = []
for f in glob.glob('data/skills_v2/*.json'):
    job = f.replace('\\', '/').split('/')[-1][:-5]
    for sk in json.load(open(f, encoding='utf-8'))['skills']:
        si = sk['index']
        has_deal = sk.get('damage') or sk.get('dealChild') or sk.get('summon')
        if not has_deal:
            continue
        tg = sk.get('trigger') or {}
        typ = tg.get('type')
        cd = sk.get('cd', 0) or 0
        period = tg.get('periodMs')
        proc = tg.get('procPct', 100)
        # ① proc 딜인데 내부쿨(cd)·주기(period) 없고 발동확률 100% → 매 공격 무한발동(폭주 위험)
        if typ in PROC and cd == 0 and not period and proc >= 100:
            tmism.append((job, sk.get('name'), si, f'proc 100%+무내부쿨 → 매공격 발동(폭주 위험) type={typ}'))
        # ② OnTimer 딜인데 주기 없음 → 발동 불가(무발동)
        if typ == 'OnTimer' and not period:
            tmism.append((job, sk.get('name'), si, f'OnTimer인데 주기(periodMs) 없음 → 무발동'))
        # ③ 연계딜(dealChild) 스킬은 발동 경로가 반드시 있어야(cd/period/proc<100 중 하나)
        if sk.get('dealChild') and typ in PROC and cd == 0 and not period and proc >= 100:
            tmism.append((job, sk.get('name'), si, '연계딜인데 발동 제한 없음(dealChild)'))

print(f"=== ②축 발동 검증: 딜 스킬 발동 이상 {len(tmism)}건 ===")
for j, nm, si, msg in tmism: print(f"  [{j}] {nm}({si}): {msg}")

# ==== ⑦축 마스터리 반영 검증: 신엔진 반영 type 대조 → 미반영·딜영향 자동 플래그 ====
# ★신엔진(SkillEngineImpl)이 masterySum/masteryStatAdd로 반영하는 type. 코드 변경 시 이 집합을 동기화한다.
MASTERY_REFLECTED = {'dmgPct', 'hitPlus', 'targetPlus', 'summonTargetPlus', 'cdrPct',
                     'tickPct', 'summonTickMs', 'finalDmgPct', 'stackPlus', 'statAdd',
                     'ratioBoost', 'ratioValue', 'summonFirePlus', 'cdFlatMs'}
# 딜/쿨에 직접 영향 → 반드시 반영돼야(미반영이면 계산 오차). value 있는 정적값만(동적 val=None은 조건부라 별도).
MASTERY_DEAL = {'dmgPct', 'hitPlus', 'targetPlus', 'summonTargetPlus', 'cdrPct', 'tickPct',
                'summonTickMs', 'finalDmgPct', 'stackPlus', 'statAdd', 'ratioBoost', 'ratioValue',
                'summonFirePlus', 'cdFlatMs'}
mmism = []
mtypes = Counter()
for f in glob.glob('data/skills_v2/*.json'):
    job = f.replace('\\', '/').split('/')[-1][:-5]
    for sk in json.load(open(f, encoding='utf-8'))['skills']:
        for m in (sk.get('masteries') or []):
            t = m.get('type')
            mtypes[t] += 1
            # 딜영향 type인데 반영 안 됨 + 정적값 있음 → 계산에서 누락(플래그)
            if t in MASTERY_DEAL and t not in MASTERY_REFLECTED and m.get('value') is not None:
                mmism.append((job, sk.get('name'), sk['index'], t, (m.get('desc') or '')[:40]))

print(f"=== ⑦축 마스터리 반영 검증: 미반영·딜영향 {len(mmism)}건 ===")
for j, nm, si, t, ds in mmism: print(f"  [{j}] {nm}({si}) {t}: {ds}")
# 참고: 전체 마스터리 type 분포(반영O/미반영X)
print("  --- 마스터리 type 분포(O=반영 X=미반영) ---")
for t, c in mtypes.most_common():
    if t in ('none', 'raw', None): continue
    mark = 'O' if t in MASTERY_REFLECTED else ('~' if t not in MASTERY_DEAL else 'X')
    print(f"    {mark} {t:<18} {c}")
