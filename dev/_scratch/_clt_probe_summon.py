# 소환 가동률 조사: 전 직업 소환수(오래 지속 proj) + 소환 스킬 cd + 종속 딜 + 쿨감/지속 마스터리 전수 스캔.
# 소환수 vs 즉발발사체 구분: proj DurationTimeMs가 큼(수초~). -*- coding: utf-8 -*-
import io, json, os
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r
SP = {str(r.get('ProjectileIndex')): r for r in load('SkillProjectileTable.json')}
HS = load('HeroSkillTable.json')

def has_deal(si):
    for o in (ST.get(str(si), {}).get('Operations') or []):
        if isinstance(o, dict) and ('GetDamage' in str(o.get('Type', '')) or 'GetDot' in str(o.get('Type', ''))):
            return True
    return False

print("=== 소환수(지속≥3초 proj) 생성 스킬 + 종속 딜 ===")
seen = set()
for si, s in sorted(ST.items(), key=lambda x: x[0]):
    for o in (s.get('Operations') or []):
        if not isinstance(o, dict) or o.get('Type') not in ('CreateProjectile', 'FireProjectile'):
            continue
        pv = (o.get('Values') or [])
        pidx = str(pv[0]) if pv else None
        pr = SP.get(pidx)
        if not pr: continue
        dur = pr.get('DurationTimeMs')
        try: durn = int(dur)
        except (ValueError, TypeError): continue
        if durn < 3000 or durn >= 99999999: continue   # 소환수(오래 지속). 즉발/무한 제외
        cd = s.get('CoolTimeMs')
        # 이 소환 스킬 op가 함께 만드는 딜 자식들(CreateProjectile Values 중 딜스킬)
        children = []
        for o2 in (s.get('Operations') or []):
            if isinstance(o2, dict) and o2.get('Type') in ('CreateProjectile', 'FireProjectile'):
                cv = (o2.get('Values') or [])
                for c in cv[:1]:
                    if has_deal(c): children.append(str(c))
        # proj의 SkillIndex(소환수 자체 딜)
        proj_deal = [str(x) for x in (pr.get('SkillIndex') or []) if has_deal(x)]
        key = (si, pidx)
        if key in seen: continue
        seen.add(key)
        nm = s.get('SkillName') or ''
        print(f"  {si} (cd={cd} {o.get('Type')} proj{pidx} 지속{durn/1000:.0f}s) 자식딜={children} proj딜={proj_deal}")

print("\n=== 소환/재사용/지속 관련 마스터리(cdrPct·durMs·durPct) ===")
HM = load('HeroSkillMasteryTable.json')
for m in HM:
    desc = str(m.get('Description') or '')
    key = str(m.get('MasteryKey') or '')
    if any(w in desc for w in ['재사용', '지속시간', '소환']):
        print(f"  {m.get('SkillIndex')} [{m.get('RequireLevel')}] {desc[:55]}")
