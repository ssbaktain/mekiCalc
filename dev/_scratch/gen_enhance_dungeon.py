# -*- coding: utf-8 -*-
import json, os
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def L(n): return json.load(open(os.path.join(base,n), encoding='utf-8'))
dt = L('DungeonTable.json')
rt = {str(r['RewardIndex']): r for r in L('RewardTable.json') if isinstance(r,dict) and 'RewardIndex' in r}
rg = {r['RewardGroup']: r for r in L('RewardGroupTable.json') if isinstance(r,dict) and 'RewardGroup' in r}

# 각 GROWTHSCROLL_N의 노말/레어/에픽 가중치(천분율→%)
def tier_dist(gname):
    g=rg.get(gname,{})
    d={'노말':0,'레어':0,'에픽':0}
    for x in g.get('Rewards',[]):
        v=x.get('Value',''); p=int(x.get('Prob',0))/10000.0
        if 'NORMAL' in v: d['노말']+=p
        elif 'RARE' in v: d['레어']+=p
        elif 'EPIC' in v: d['에픽']+=p
    return d

# 챕터1003 레벨→(주문의흔적, 보너스그룹)
lv=[]
for d in dt:
    if d.get('ChapterIndex')!='1003': continue
    di=int(d['DungeonIndex'])
    rw=rt.get(str(d.get('ClearReward',{}).get('RewardIndex')),{})
    ms=None; grp=None; bp=0
    for x in rw.get('Rewards',[]):
        if x.get('Value')=='MAGIC_SCROLL': ms=int(x.get('Count'))
        elif x.get('Type')=='RewardGroup': grp=x.get('Value'); bp=int(x.get('Prob',0))/10000.0
    lv.append((di,ms,grp,bp))
lv.sort()

# 밴드(같은 보너스그룹)로 묶기
bands=[]
for di,ms,grp,bp in lv:
    if bands and bands[-1][3]==grp:
        bands[-1][1]=di; bands[-1][5]=ms  # end lv, end ms
    else:
        bands.append([di,di,lv[0][1],grp,bp,ms])  # [startlv,endlv,_,grp,bp,ms]  (ms=end updated)
# fix: rebuild cleanly
bands=[]
for di,ms,grp,bp in lv:
    if bands and bands[-1]['grp']==grp:
        b=bands[-1]; b['e']=di; b['mse']=ms
    else:
        bands.append({'s':di,'e':di,'grp':grp,'bp':bp,'mss':ms,'mse':ms})

html=['<meta charset="utf-8">','<table class="table table-bordered"><tbody>']
html.append('<tr><td>레벨 구간</td><td>주문의 흔적<br>(100% 확정)</td>'
            '<td>의문의 주문서<br>드롭 확률</td><td>주문서 등급 분포<br>(드롭 시)</td></tr>')
print(f"강화 던전 140레벨 / 밴드 {len(bands)}개")
for b in bands:
    dist=tier_dist(b['grp'])
    parts=[f"{k} {v:.1f}%" for k,v in dist.items() if v>0]
    rng = f"Lv{b['s']}~{b['e']}" if b['s']!=b['e'] else f"Lv{b['s']}"
    msr = f"{b['mss']:,}~{b['mse']:,}개" if b['mss']!=b['mse'] else f"{b['mss']:,}개"
    html.append(f"<tr><td>{rng}</td><td>{msr}</td><td><b>{b['bp']:.0f}%</b></td><td>{' · '.join(parts)}</td></tr>")
    print(f"  {rng}: 주문의흔적 {msr}, 의문의주문서 {b['bp']:.0f}% -> {dist}")
html.append('</tbody></table>')
# 등급 내 세부(정정): 노말=40%짜리 1종 / 레어=40%,25% 각50% / 에픽=70%,30%,15% 각1/3. 유니크 없음.
html.append('<p>※ 의문의 주문서 = 랜덤 강화 주문서(강화 성공률별). '
            '<b>노말</b> 획득 시 = 40% 주문서. '
            '<b>레어</b> 획득 시 = 40%/25% 각 50%. '
            '<b>에픽</b> 획득 시 = 70%/30%/15% 각 1/3. '
            '<b>유니크 주문서는 없음</b>(게임 내 강화 주문서 최고 등급=에픽).</p>')
out=r'C:\Users\MhHs\강화던전_드롭률.html'
open(out,'w',encoding='utf-8').write('\n'.join(html))
print('saved',out)
