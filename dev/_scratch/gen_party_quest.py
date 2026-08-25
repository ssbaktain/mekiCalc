# -*- coding: utf-8 -*-
import json, os
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def L(n): return json.load(open(os.path.join(base,n), encoding='utf-8'))
dt = L('DungeonTable.json')
rt = {str(r['RewardIndex']): r for r in L('RewardTable.json') if isinstance(r,dict) and 'RewardIndex' in r}
lrt = L('CustomGearLevelRangeTable.json')
cg = {r['CustomCode']: r for r in L('CustomGearTable.json') if isinstance(r,dict) and 'CustomCode' in r}
og = {str(r['OptionGroupIndex']): r for r in L('GearOptionGroupTable.json')}
ot = {str(r['OptionIndex']): r for r in L('GearOptionTable.json')}
gt = {str(r['GearIndex']): r for r in L('GearTable.json') if isinstance(r,dict) and 'GearIndex' in r}
GRADE={'Grade1':'노말','Grade2':'레어','Grade3':'에픽','Grade4':'유니크','Grade5':'레전드리'}
STATKO={'Attack':'공격력','Defence':'방어력','MaxHp':'최대HP','MaxMpR':'MP회복%','AvoidChance':'회피',
 'HitChance':'명중','CriticalChance':'크리확률','CriticalPower':'크리데미지','AttackPowerToBoss':'보스뎀',
 'AttackPowerExcludeBoss':'보스제외뎀','AttackPower':'데미지','MinDamageRatio':'최소뎀','MaxDamageRatio':'최대뎀',
 'JobStep1SkillLevel':'1차스킬','JobStep2SkillLevel':'2차스킬','JobStep3SkillLevel':'3차스킬','JobStep4SkillLevel':'4차스킬',
 'AttackR':'공격력%','DefenceR':'방어력%','MaxHpR':'최대HP%'}
DIFF={1:'1던전',2:'2던전',3:'3던전',4:'4던전'}

def level_dist(lri):
    out=[]
    for row in lrt:
        rate=row.get('Rate',[])
        if lri<len(rate) and rate[lri] not in (None,) and int(rate[lri])>0:
            out.append((int(row['Level']), int(rate[lri])))
    return sorted(out)

rows=[]
for d in dt:
    if d.get('ChapterIndex')!='10000': continue
    di=int(d['DungeonIndex'])
    rw=rt.get(str(d.get('LimitableClearReward',{}).get('RewardIndex')),{})
    ring=None; rp=0
    for x in rw.get('Rewards',[]):
        if x.get('Type')=='CustomGear': ring=x.get('Value'); rp=int(x.get('Prob',0))/10000.0
    if not ring: continue
    c=cg.get(ring,{}); gi=str(c.get('GearIndex')); lri=int(c.get('LevelRangeIndex')); g=gt.get(gi,{})
    grade=GRADE.get(g.get('ItemGradeType'),'?')
    ld=level_dist(lri); ldt=sum(w for _,w in ld); lvls=[l for l,_ in ld]
    lvstr=f"Lv{min(lvls)}~{max(lvls)}"
    grp=g.get('SubOptionGroupIndices',['?'])[0]; opts=og.get(str(grp),{}).get('OptionIndices',[])
    types=[STATKO.get(ot.get(str(o),{}).get('StatData',{}).get('Type'),'?') for o in opts]
    cnts=g.get('SubOptionCountProbs',[])
    cntstr=' · '.join(f"{c['OptionCount']}줄 {int(c['Ratio'])/10:.0f}%" for c in cnts if int(c['Ratio'])>0)
    rows.append((DIFF[di],grade,rp,lvstr,types,cntstr,len(opts)))

H=['<meta charset="utf-8">','<h3>파티 퀘스트 "첫 번째 동행" — 난이도별 반지 드롭·레벨·부옵션</h3>']
# 요약 표
H.append('<table class="table table-bordered"><tbody>')
H.append('<tr><td>난이도</td><td>등급</td><td>반지 드롭</td><td>레벨 범위<br>(균등)</td><td>부옵션 줄수</td><td>부옵션 종류</td></tr>')
for diff,grade,rp,lvstr,types,cntstr,n in rows:
    H.append(f'<tr><td>{diff}</td><td><b>{grade}</b></td><td>{rp:.0f}%</td><td><b>{lvstr}</b></td>'
             f'<td>{cntstr}</td><td>{n}종 균등 각 {100/n:.2f}%<br><span style="color:#555">{" · ".join(types)}</span></td></tr>')
H.append('</tbody></table>')
H.append('<p>※ 레벨은 범위 내 각 레벨 균등. 부옵션 종류는 그룹 내 가중치 없어 균등 추정. '
         '<b>최소뎀·최대뎀은 레전드리(3·4던전)에만</b>, 4차스킬은 4던전에만 등장. '
         '에픽·유니크(1·2던전)엔 "데미지" 부옵이 그 자리를 대신함.</p>')
out=r'C:\Users\MhHs\파티퀘스트_첫번째동행_반지.html'
open(out,'w',encoding='utf-8').write('\n'.join(H))
print('saved',out)
for r in rows: print(r[0],r[1],f'{r[2]:.0f}%',r[3],f'{r[6]}종')
