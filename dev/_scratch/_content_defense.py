# -*- coding: utf-8 -*-
"""전 컨텐츠 몬스터 방어력 리졸버.
체인: DungeonTable[chapter].OverrideFamilyIndex -> CampFamilyTable(/1) -> MonsterTierTable.StatRatio.Defence
StatCalculator: actualDef = CreatureStatTable[creature].Defence(=1000) * StatRatio.Defence/1000 = StatRatio.Defence
tier 사다리(컨텐츠베이스+레벨) 자동 감지."""
import json, os
from collections import defaultdict
B = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def L(f): return json.load(open(os.path.join(B,f),encoding="utf-8"))
dt=L("DungeonTable.json")
cf={r["FamilyIndex"]:r for r in L("CampFamilyTable.json")}
cf1={r["FamilyIndex"]:r for r in L("CampFamilyTable1.json")}
mt={r["TierIndex"]:r for r in L("MonsterTierTable.json")}
cs={r["CreatureIndex"]:r["Stat"] for r in L("CreatureStatTable.json")}
def fam(f): return cf.get(f) or cf1.get(f)
def tdef(ti):
    sr=mt.get(ti,{}).get("StatRatio",{}); return sr.get("Defence"), sr.get("MaxHp")
def cdef(cid): return cs.get(cid,{}).get("Defence")
def actual(cid,ti):
    bd=cdef(cid); rd,_=tdef(ti)
    if bd is None or rd is None: return None
    return float(bd)*float(rd)/1000.0

byc=defaultdict(list)
for r in dt: byc[r.get("ChapterIndex")].append(r)

# 컨텐츠 라벨
LAB={"1000":"무기던전","1001":"경험치던전","1002":"장비던전","1003":"강화던전","1004":"직업던전(가디언타워)",
 "3000":"월드보스","5001":"길드토벌전","5002":"길드대항전","5003":"길드이벤트(훈련)","5004":"길드보스대전",
 "5101":"길드타워","6000":"스타포스필드","10000":"파티던전K","10001":"파티던전루디","10002":"파티던전오르비스",
 "10003":"파티던전마가티아","11000":"파티던전바람","12000":"플래그레이스","12001":"해변의지배자",
 "20001":"보스레이드:자쿰","20002":"보스레이드:혼테일","21001":"길드레이드:자쿰","21002":"길드레이드:혼테일",
 "40001":"콜로세움"}
for i in range(2000,2015): LAB[str(i)]="전직던전%d"%i

def ladder(ti):
    """tier 주변 사다리 감지: base+N 형태로 연속 Defence 존재?"""
    if not ti or not ti.isdigit(): return None
    n=int(ti); found=[]
    for k in range(0,60):
        t=str(n+k)
        d,h=tdef(t)
        if d is None: break
        found.append((k, int(d)))
    return found if len(found)>1 else None

def boss_family_of(row):
    ofi=row.get("OverrideFamilyIndex") or []
    for x in ofi:
        if x: return x
    # CampSpawn fallback
    return None

targets=["1000","1001","1002","1003","1004"]+[str(i) for i in range(2000,2015)]+\
 ["3000","5001","5002","5003","5004","5101","6000","10000","10001","10002","10003","11000","12000","12001",
  "20001","20002","21001","21002","40001"]

for ch in targets:
    rows=sorted(byc.get(ch,[]),key=lambda r:int(r["DungeonIndex"]))
    if not rows:
        print(f"[{ch}] {LAB.get(ch,'?')}: (없음)"); continue
    print("="*70)
    print(f"[{ch}] {LAB.get(ch,'?')}  던전수 {len(rows)}")
    # 대표: 첫 던전의 보스 + 모든 몹 defence 범위
    # 보스 defence
    seen=set()
    for r in rows[:6]:
        di=r["DungeonIndex"]; fidx=boss_family_of(r)
        if not fidx:
            # 마을/pvp 등 몹없음
            continue
        fr=fam(fidx)
        if not fr:
            print(f"  D{di} family {fidx} 미존재"); continue
        for m in fr["Monster"]:
            ti=m["TierIndex"]; ad=actual(m["CreatureIndex"],ti); isb=m.get("IsBoss")
            key=(di,m["CreatureIndex"],ti)
            print(f"  D{di} creature {m['CreatureIndex']} tier {ti} boss={isb} Defence={ad:.0f}" if ad is not None else f"  D{di} creature {m['CreatureIndex']} tier {ti} boss={isb} Defence=?")
            # 사다리
            lad=ladder(ti)
            if lad and len(lad)>2 and (di==rows[0]["DungeonIndex"]):
                defs=[d for k,d in lad]
                print(f"      ↳ tier사다리 {len(lad)}단: {defs[0]}→{defs[-1]} (step {defs[1]-defs[0] if len(defs)>1 else 0})")
