# -*- coding: utf-8 -*-
import json, os
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def L(n): return json.load(open(os.path.join(base,n), encoding='utf-8'))
dt = L('DungeonTable.json')
rt = {str(r['RewardIndex']): r for r in L('RewardTable.json') if isinstance(r,dict) and 'RewardIndex' in r}
rg = {r['RewardGroup']: r for r in L('RewardGroupTable.json') if isinstance(r,dict) and 'RewardGroup' in r}

def resolve_group(name, depth=0):
    g=rg.get(name)
    if not g or depth>5: return [name]
    out=[]
    for x in g.get('Rewards',[]):
        if x.get('Type')=='RewardGroup':
            out+=resolve_group(x.get('Value'), depth+1)
        else:
            out.append(f"{x.get('Value')}")
    return out

# 챕터 1003 던전 전부
rows=[]
for d in dt:
    if d.get('ChapterIndex')!='1003': continue
    di=int(d['DungeonIndex'])
    ci=d.get('ClearReward',{}).get('RewardIndex')
    rw=rt.get(str(ci),{})
    mscroll=None; bonus=None
    for x in rw.get('Rewards',[]):
        if x.get('Type')=='Item' and x.get('Value')=='MAGIC_SCROLL':
            mscroll=int(x.get('Count'))
        elif x.get('Type')=='RewardGroup':
            grp=x.get('Value'); prob=int(x.get('Prob',0))/10000.0
            resolved=resolve_group(grp)
            bonus=(grp, prob, tuple(resolved))
    rows.append((di, ci, mscroll, bonus))

rows.sort()
print(f"강화 던전(챕터1003) 레벨수: {len(rows)}")
# 패턴 요약: 주문의흔적 증가폭, 보너스 그룹 변화
prev=None
changes=[]
for di,ci,ms,bonus in rows:
    key=(bonus[0] if bonus else None, bonus[2] if bonus else None, bonus[1] if bonus else None)
    if key!=prev:
        changes.append((di,ms,bonus))
        prev=key
print("\n[보너스 그룹/구성 변경 지점]")
for di,ms,bonus in changes:
    print(f"  Lv{di}: 주문의흔적={ms}, 보너스={bonus}")
print("\n[샘플 레벨]")
for di,ci,ms,bonus in rows:
    if di in (1,2,10,50,100,139,140) or di==rows[-1][0]:
        print(f"  Lv{di} (reward{ci}): 주문의흔적 {ms}개(100%) + {bonus[0]} {bonus[1]:.0f}% -> {bonus[2]}")
print(f"\n주문의흔적 증가폭: Lv1={rows[0][2]}, Lv2={rows[1][2]}, 마지막 Lv{rows[-1][0]}={rows[-1][2]}")
