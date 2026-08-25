# 유물 잠재(HiddenForce) 참조 데이터 생성.
# 입력: artifacts_ir.json(hiddenForcePool = ArtifactHiddenForceOptionTable 파싱본) + ArtifactHiddenForceTable(등급별 슬롯).
# 출력: data/artifact_potential.json — 앱/엔진이 소비하는 잠재 규칙+옵션 풀.
# 값 단위 = 천분율(앱 표시%로는 ÷10). CC중데미지(AttackPowerInCc)는 유저 지시로 제외.
import json, os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ir = json.load(open(os.path.join(ROOT, 'data/artifacts_ir.json'), encoding='utf-8'))
hf = ir['hiddenForcePool']

# StatType(클뜯) → 앱 statId(stats.json). 생존 3종은 statId=None(표시만, 딜 미반영). InCc 제외.
STAT_MAP = {
    'AttackPower':            ('DMG',           '데미지%',            True),
    'CriticalChance':         ('CRIT_RATE',     '크리티컬 확률%',      True),
    'MaxDamageRatio':         ('MAX_DMG_MULT',  '최대 데미지 배율%',   True),
    'MinDamageRatio':         ('MIN_DMG_MULT',  '최소 데미지 배율%',   True),
    'AttackPowerToBoss':      ('BOSS_DMG',      '보스 몬스터 데미지%', True),
    'AttackPowerExcludeBoss': ('NORMAL_DMG',    '일반 몬스터 데미지%', True),
    'PiercePower':            ('DEF_PEN',       '방어 관통력%',        True),
    'MainStatR':              ('MAIN_STAT_PCT', '주스탯%',            True),
    'AttackPowerInCc':        (None,            '상태이상 데미지%',   False),  # 조건부(적 CC), 지금은 표시만·딜0(나중에 살림)
    'Toughness':              (None,            '강인함',             False),
    'DefenceR':               (None,            '방어력%',            False),
    'HitChance':              (None,            '명중',               False),
}

# 옵션 등급(hiddenForcePool Grade2~6 = 레어~미스틱). 5단계.
# ★미스틱(Grade6)은 별도 등급이 아니라 같은 등급 안에서 값이 롤(높은 값=낮은 확률) → 값 배열.
GRADES = [
    ('rare',   '레어',    'Grade2'),
    ('epic',   '에픽',    'Grade3'),
    ('unique', '유니크',  'Grade4'),
    ('legend', '레전더리', 'Grade5'),
    ('mythic', '미스틱',   'Grade6'),
]

def values_of(stype, gradeType):
    """옵션종류·등급의 값(천분율) 오름차순. slotIndex 무관(동일). 미스틱은 여러 값(롤)."""
    return sorted({int(r['value']) for r in hf[gradeType] if r['type'] == stype})

options = []
for stype, (sid, name, deal) in STAT_MAP.items():
    values = {}
    for gid, _, gt in GRADES:
        vs = values_of(stype, gt)
        if not vs: continue
        values[gid] = vs if len(vs) > 1 else vs[0]   # 롤 여러개(미스틱)=배열, 단일=스칼라
    options.append({'type': stype, 'statId': sid, 'name': name, 'deal': deal, 'values': values})

# 유물 등급별 잠재 슬롯 규칙(ArtifactHiddenForceTable, 2694). 키=앱 카탈로그 등급 코드(대문자).
slot_rule = {
    'EPIC':      {'maxSlot': 2, 'reqAwake': [2, 4]},   # Grade3
    'UNIQUE':    {'maxSlot': 2, 'reqAwake': [1, 3]},   # Grade4
    'LEGENDARY': {'maxSlot': 3, 'reqAwake': [1, 3, 5]},  # Grade5
}

out = {
    '_comment': '유물 잠재(HiddenForce) 참조. 클뜯 2694 ArtifactHiddenForceTable/OptionTable. '
                '값=천분율(표시%는 ÷10). 줄수=유물등급 slotRule + 각성도(reqAwake 이상 슬롯만 열림). '
                '미스틱(mythic)은 값이 배열(같은 등급 내 롤, 높은 값=낮은 확률). 생존 3종(statId=null)은 표시만·딜 미반영. CC중뎀 제외.',
    'slotRule': slot_rule,
    'grades': [{'id': g, 'name': n} for g, n, _ in GRADES],
    'options': options,
}
dst = os.path.join(ROOT, 'data/artifact_potential.json')
json.dump(out, open(dst, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('생성:', dst)
print('옵션', len(options), '종 (딜', sum(1 for o in options if o['deal']), '· 생존', sum(1 for o in options if not o['deal']), ')')
print('예시 AttackPower values:', next(o['values'] for o in options if o['type'] == 'AttackPower'))
print('slotRule:', slot_rule)
