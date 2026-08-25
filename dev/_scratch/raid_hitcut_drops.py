# -*- coding: utf-8 -*-
import json, os
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def L(n): return json.load(open(os.path.join(base,n), encoding='utf-8'))
dt = L('DungeonTable.json')
cf = {r['FamilyIndex']: r for r in L('CampFamilyTable.json') if isinstance(r,dict) and 'FamilyIndex' in r}
mt = {str(r['TierIndex']): r for r in L('MonsterTierTable.json') if isinstance(r,dict) and 'TierIndex' in r}
rt = {str(r['RewardIndex']): r for r in L('RewardTable.json') if isinstance(r,dict) and 'RewardIndex' in r}

DIFF = {1:'Easy(이지)',2:'Normal(노말)',3:'Hard(하드)',4:'Chaos(카오스)'}
RAID = {20001:'자쿰', 20002:'혼테일'}

def avoid_of(fam):
    c = cf.get(str(fam))
    if not c: return []
    out=[]
    for m in c.get('Monster',[]):
        ti = str(m.get('TierIndex'))
        av = mt.get(ti,{}).get('StatRatio',{}).get('AvoidChance')
        out.append((m.get('CreatureIndex'), ti, av, m.get('IsBoss')=='True'))
    return out

for chap in (20001,20002):
    print('\n' + '='*60)
    print(f"### {RAID[chap]} 레이드 (챕터 {chap})")
    for d in dt:
        if d.get('ChapterIndex')!=str(chap): continue
        di = int(d['DungeonIndex'])
        fams = [f for f in d.get('OverrideFamilyIndex',[]) if f]
        # avoid: 보스 우선, 전체 회피 집합
        allav=[]; bossav=[]
        for f in fams:
            for ci,ti,av,isb in avoid_of(f):
                if av is not None:
                    allav.append(int(av))
                    if isb: bossav.append(int(av))
        print(f"\n--- {DIFF[di]} (dungeon {di}) ---")
        print(f"  회피(명중컷): 보스={sorted(set(bossav))}  전체몹범위={min(allav) if allav else '?'}~{max(allav) if allav else '?'}")
        # drops
        li = d.get('LimitableClearReward',{}).get('RewardIndex')
        ci_ = d.get('ClearReward',{}).get('RewardIndex')
        rw = rt.get(str(li))
        if rw:
            tot = sum(int(x.get('Prob',0)) for x in rw.get('Rewards',[]))
            print(f"  드랍(티켓소모 LimitableClearReward {li}, RollType={rw.get('RollType')}, Prob합={tot}):")
            for x in rw.get('Rewards',[]):
                p = int(x.get('Prob',0)); pct = p/10000.0
                dc = x.get('DropCount','1')
                print(f"    {x.get('Type'):13s} {str(x.get('Value'))[:42]:42s} x{x.get('Count')} drop{dc}  {pct:.2f}%")
        # clear reward (보통 EXP)
        rc = rt.get(str(ci_))
        if rc:
            exps=[f"{x.get('Value')}x{x.get('Count')}" for x in rc.get('Rewards',[])]
            print(f"  클리어보상(무제한 {ci_}): {exps}")
