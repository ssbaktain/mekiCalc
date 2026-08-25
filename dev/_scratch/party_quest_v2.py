# -*- coding: utf-8 -*-
import json, os
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def L(n): return json.load(open(os.path.join(base,n), encoding='utf-8'))
dt = L('DungeonTable.json')
rt = {str(r['RewardIndex']): r for r in L('RewardTable.json') if isinstance(r,dict) and 'RewardIndex' in r}
lrt = L('CustomGearLevelRangeTable.json')  # rows: Level + Rate[]
cg = {r['CustomCode']: r for r in L('CustomGearTable.json') if isinstance(r,dict) and 'CustomCode' in r}
og = {str(r['OptionGroupIndex']): r for r in L('GearOptionGroupTable.json')}
ot = {str(r['OptionIndex']): r for r in L('GearOptionTable.json')}
gt = {str(r['GearIndex']): r for r in L('GearTable.json') if isinstance(r,dict) and 'GearIndex' in r}
GRADE={'Grade1':'노말','Grade2':'레어','Grade3':'에픽','Grade4':'유니크','Grade5':'레전드리'}
STATKO={'Attack':'공격력','Defence':'방어력','MaxHp':'최대HP','MaxMpR':'MP회복%','AvoidChance':'회피',
 'HitChance':'명중','CriticalChance':'크리확률','CriticalPower':'크리데미지','AttackPowerToBoss':'보스뎀',
 'AttackPowerExcludeBoss':'보스제외뎀','AttackPower':'데미지','MinDamageRatio':'최소뎀','MaxDamageRatio':'최대뎀',
 'JobStep1SkillLevel':'1차스킬','JobStep2SkillLevel':'2차스킬','JobStep3SkillLevel':'3차스킬',
 'AttackR':'공격력%','DefenceR':'방어력%','MaxHpR':'최대HP%'}

def level_dist(lri):
    # 세로: 모든 행 Level=X 에서 Rate[lri]>0 인 것 수집
    out=[]
    for row in lrt:
        rate=row.get('Rate',[])
        if lri < len(rate) and rate[lri] not in (None,) and int(rate[lri])>0:
            out.append((int(row['Level']), int(rate[lri])))
    return out

# 챕터10000 던전(난이도)별 반지
for d in dt:
    if d.get('ChapterIndex')!='10000': continue
    di=int(d['DungeonIndex'])
    li=d.get('LimitableClearReward',{}).get('RewardIndex')
    rw=rt.get(str(li),{})
    ring=None; ringprob=0
    for x in rw.get('Rewards',[]):
        if x.get('Type')=='CustomGear': ring=x.get('Value'); ringprob=int(x.get('Prob',0))/10000.0
    if not ring: continue
    c=cg.get(ring,{}); gi=str(c.get('GearIndex')); lri=int(c.get('LevelRangeIndex'))
    g=gt.get(gi,{})
    grade=GRADE.get(g.get('ItemGradeType'),g.get('ItemGradeType'))
    ld=level_dist(lri); ldt=sum(w for _,w in ld)
    lvls=sorted(x[0] for x in ld)
    cnts=g.get('SubOptionCountProbs',[])
    grp=g.get('SubOptionGroupIndices',['?'])[0]
    opts=og.get(str(grp),{}).get('OptionIndices',[])
    types=[STATKO.get(ot.get(str(o),{}).get('StatData',{}).get('Type'), ot.get(str(o),{}).get('StatData',{}).get('Type')) for o in opts]
    print(f"\n=== {di}던전: {ring} (기어{gi}) ===")
    print(f"  등급: {grade} | 반지 드롭확률: {ringprob:.0f}% | 레벨범위인덱스: {lri}")
    print(f"  레벨 분포({len(ld)}종, Lv{min(lvls)}~{max(lvls)}): " + ', '.join(f'Lv{l}({w/ldt*100:.1f}%)' for l,w in sorted(ld)))
    print(f"  부옵션 줄수: " + ', '.join(f"{c['OptionCount']}줄 {int(c['Ratio'])/10:.0f}%" for c in cnts))
    print(f"  부옵션 종류({len(opts)}종, 균등 {100/len(opts):.2f}%): {', '.join(types)}")
