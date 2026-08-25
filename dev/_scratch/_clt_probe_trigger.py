# ②축 발동 진단: 전 직업 딜 스킬의 실제 발동 필드(클뜯) 전수 조사.
# proc(OnAttack/OnHit/OnDamage) 스킬의 내부쿨(CoolTimeMs) 유무·표식형 구조를 확인. -*- coding: utf-8 -*-
import io, json, os, glob
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r
PROJ = {str(r.get('ProjectileIndex')): r for r in load('SkillProjectileTable.json')}

def fld(si):
    s = ST.get(str(si))
    if not s: return None
    return (s.get('TriggerType'), s.get('TriggerRatio'), s.get('TriggerTimeMs'),
            s.get('CoolTimeMs'), s.get('SkillTargetType'))

# skills_v2에서 proc/표식형/override 스킬 추출
print("=== proc·override·표식형 발동 필드 (클뜯 원본) ===")
print(f"{'job':<12}{'skill':<22}{'idx':<7}{'v2_trig':<12}{'clt_TT':<10}{'clt_TR':<7}{'clt_TrigTMs':<14}{'clt_Cool':<10}{'dealChild'}")
for f in sorted(glob.glob(r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_v2\*.json')):
    job = os.path.basename(f)[:-5]
    d = json.load(io.open(f, encoding='utf-8'))
    for sk in d['skills']:
        tg = (sk.get('trigger') or {}).get('type')
        # 딜이 있는 스킬만
        has_deal = sk.get('damage') or sk.get('dealChild') or sk.get('summon')
        if not has_deal: continue
        if tg not in ('OnAttack', 'OnHit', 'OnDamage', 'OnTimer', 'OnActiveSkill'): continue
        si = sk['index']
        f0 = fld(si) or ('?',)*5
        dc = sk.get('dealChild')
        line = f"{job:<12}{(sk.get('name') or '?')[:20]:<22}{si:<7}{str(tg):<12}{str(f0[0]):<10}{str(f0[1]):<7}{str(f0[2]):<14}{str(f0[3]):<10}{dc or ''}"
        print(line)
        # dealChild가 있으면 자식의 발동/쿨도
        if dc:
            for cidx in (dc if isinstance(dc, list) else [dc]):
                cf = fld(cidx) or ('?',)*5
                print(f"    └child {cidx:<15}{'':<12}{str(cf[0]):<10}{str(cf[1]):<7}{str(cf[2]):<14}{str(cf[3]):<10}(targetType={cf[4]})")
