# -*- coding: utf-8 -*-
import json, os
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def L(n): return json.load(open(os.path.join(base,n), encoding='utf-8'))
dt = L('DungeonTable.json')
cf = {r['FamilyIndex']: r for r in L('CampFamilyTable.json') if isinstance(r,dict) and 'FamilyIndex' in r}
mt = {str(r['TierIndex']): r for r in L('MonsterTierTable.json') if isinstance(r,dict) and 'TierIndex' in r}
rt = {str(r['RewardIndex']): r for r in L('RewardTable.json') if isinstance(r,dict) and 'RewardIndex' in r}
DIFF = {1:'이지',2:'노말',3:'하드',4:'카오스'}
RAID = {20001:'자쿰', 20002:'혼테일'}

# 인게임 이름 매칭
ITEM_KO = {
 'Gold':'메소','ITEM_EXP':'경험치','MAGIC_SCROLL':'주문의 흔적','STONE_WEAPON':'무기 강화석',
 'GEAR_GACHA_POINT':'엘리트 몬스터 소환 포인트','TICKET_TIMECUT':'시간 단축 티켓(1분)',
 'SCROLL_RAID_ZAKUM_1':'자쿰의 주문서(하급)','SCROLL_RAID_ZAKUM_2':'자쿰의 주문서(중급)',
 'SCROLL_RAID_HORNTAIL_1':'혼테일의 주문서(하급)','SCROLL_RAID_HORNTAIL_2':'혼테일의 주문서(중급)',
}
def item_ko(v):
    return ITEM_KO.get(str(v), str(v))
def gear_ko(v):
    v=str(v); g=v.split('_')[-1]
    if 'HELMET' in v: return f'자쿰 모자 {g}단계'
    if 'EYEACCESSORY' in v: return f'자쿰 눈장식 {g}단계'
    if 'EARACCESSORY' in v: return f'혼테일 귀고리 {g}단계'
    if 'PENDANT' in v: return f'혼테일 펜던트 {g}단계'
    return item_ko(v)

def avoids(fams):
    allav=[]; bossav=[]
    for f in fams:
        c=cf.get(str(f))
        if not c: continue
        for m in c.get('Monster',[]):
            av=mt.get(str(m.get('TierIndex')),{}).get('StatRatio',{}).get('AvoidChance')
            if av is not None:
                allav.append(int(av))
                if m.get('IsBoss')=='True': bossav.append(int(av))
    return bossav, allav

def boss_hp(fams):
    # 보스 부위들의 티어 MaxHp 합 = 총 보스 체력, + 부위별 값
    parts=[]
    for f in fams:
        c=cf.get(str(f))
        if not c: continue
        for m in c.get('Monster',[]):
            if m.get('IsBoss')=='True':
                hp=int(mt.get(str(m.get('TierIndex')),{}).get('StatRatio',{}).get('MaxHp',0))
                parts.append(hp)
    return sum(parts), parts

def kor_unit(n):
    # 억/조/경 단위 축약
    units=[(10**16,'경'),(10**12,'조'),(10**8,'억'),(10**4,'만')]
    for u,name in units:
        if n>=u:
            return f'{n/u:.2f}{name}'
    return f'{n:,}'

rows=[]
for chap in (20001,20002):
    for d in dt:
        if d.get('ChapterIndex')!=str(chap): continue
        di=int(d['DungeonIndex'])
        fams=[f for f in d.get('OverrideFamilyIndex',[]) if f]
        bossav, allav = avoids(fams)
        boss=min(bossav) if bossav else '?'
        mx=max(allav) if allav else '?'
        cut = f'{boss}' if boss==mx else f'{boss} <span style="color:#888">(부위 최대 {mx})</span>'
        hp_sum, hp_parts = boss_hp(fams)
        nparts=len(hp_parts)
        hp_str = f'<b>{kor_unit(hp_sum)}</b><br><span style="color:#888">({nparts}부위 · 총 {hp_sum:,})</span>'
        li=d.get('LimitableClearReward',{}).get('RewardIndex')
        rw=rt.get(str(li),{})
        gears=[]; mats=[]
        for x in rw.get('Rewards',[]):
            p=int(x.get('Prob',0))/10000.0
            if x.get('Type') in ('CustomGear','GearScrollItem'):
                gears.append(f'{gear_ko(x.get("Value"))} <b>{p:.0f}%</b>')
            else:
                cnt=int(x.get('Count',0)); dc=int(x.get('DropCount','1'))
                amt = f'{cnt:,}' + (f'×{dc}' if dc>1 else '')
                mats.append(f'{item_ko(x.get("Value"))} {amt}개 <b>{p:.0f}%</b>')
        rows.append((RAID[chap], DIFF[di], cut, hp_str, '<br>'.join(gears), '<br>'.join(mats)))

html=['<meta charset="utf-8">','<table class="table table-bordered"><tbody>']
html.append('<tr><td>레이드</td><td>난이도</td><td>명중컷<br>(보스 회피)</td><td>보스 체력<br>(총 부위합)</td>'
            '<td>장비 드랍(가중추첨·티켓 1개당 1개)</td><td>재화 드랍</td></tr>')
for raid,diff,cut,hp,gears,mats in rows:
    html.append('<tr><td>%s</td><td>%s</td><td><b>%s</b></td><td>%s</td><td>%s</td><td>%s</td></tr>'
                %(raid,diff,cut,hp,gears,mats))
html.append('</tbody></table>')
out=r'C:\Users\MhHs\자쿰혼테일_레이드_명중컷_드랍.html'
open(out,'w',encoding='utf-8').write('\n'.join(html))
print('saved',out)
for r in rows: print(r[0],r[1],'| 명중컷',r[2].split('<')[0].strip(),'| 체력',r[3].split('<br>')[0].replace('<b>','').replace('</b>',''))
