# -*- coding: utf-8 -*-
import json, os
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def L(n): return json.load(open(os.path.join(base,n), encoding='utf-8'))
dt = L('DungeonTable.json')
cf = {r['FamilyIndex']: r for r in L('CampFamilyTable.json') if isinstance(r,dict) and 'FamilyIndex' in r}
mt = {str(r['TierIndex']): r for r in L('MonsterTierTable.json') if isinstance(r,dict) and 'TierIndex' in r}
DIFF = {1:'이지',2:'노말',3:'하드',4:'카오스'}
RAID = {20001:'자쿰', 20002:'혼테일'}

for chap in (20001,20002):
    print('\n### %s (챕터 %d)'%(RAID[chap],chap))
    prev=None
    for d in dt:
        if d.get('ChapterIndex')!=str(chap): continue
        di=int(d['DungeonIndex'])
        fams=[f for f in d.get('OverrideFamilyIndex',[]) if f]
        parts=[]  # (family, creatureIdx, tier, maxhp, def, isboss)
        for f in fams:
            c=cf.get(str(f))
            if not c: continue
            for m in c.get('Monster',[]):
                ti=str(m.get('TierIndex'))
                sr=mt.get(ti,{}).get('StatRatio',{})
                parts.append((f, m.get('CreatureIndex'), ti, int(sr.get('MaxHp',0)), int(sr.get('Defence',0)), m.get('IsBoss')=='True'))
        boss_parts=[p for p in parts if p[5]]
        # 보스 체력합(티어 MaxHp 비율 기준)
        boss_hp_sum=sum(p[3] for p in boss_parts)
        all_hp_sum=sum(p[3] for p in parts)
        mult = '' if prev is None else f'  (직전 대비 x{boss_hp_sum/prev:.1f})' if prev else ''
        print(f'\n[{DIFF[di]}] 보스부위 {len(boss_parts)}개 / 전체 {len(parts)}개')
        for f,ci,ti,hp,df,isb in parts:
            tag='보스' if isb else '  '
            print(f'    {tag} fam{f} cr{ci} tier{ti}: MaxHp비율={hp:,} Def={df}')
        print(f'  => 보스 티어MaxHp합={boss_hp_sum:,}{mult}')
        prev=boss_hp_sum
