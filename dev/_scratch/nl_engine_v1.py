# -*- coding: utf-8 -*-
"""
나이트로드 IR 엔진 v1 — 실제 프리셋 스탯 적용 (절대 DPS)
- globalMultiplier: 기존 검증 엔진 DamageModel.java 공식 그대로 이식.
- 성장 입력 = 유효스킬레벨(effLevel) = 3×(레벨-차수시작) + 차수스킬 + 전체스킬  (DamageModel과 동일 규약)
- 스코프: 딜 스킬, 보스 단일대상. 동료·유물 배제. 발동빈도·가동률 정적 근사.
"""
import json, os
from collections import defaultdict
BASE = r"C:\Users\MhHs\Documents\project\mekiCalc"
IR = json.load(open(os.path.join(BASE,"data/skills_calc/nightlord_manual.json"), encoding="utf-8"))

# ===== 프리셋: "챕터 보스" (active) =====
S = {
 "DMG":876.6,"JOB":1,"LEVEL":114,"ATK_PCT":27440.9,"DEFENSE":64834,"DEF_PEN":41.3,"DMG_AMP":57.6,
 "ATK_FLAT":993249.2,"BOSS_DMG":401.2,"CRIT_DMG":316.7,"ATK_SPEED":63.8,"CRIT_RATE":108.6,
 "FINAL_DMG":69.8,"SKILL_DMG":87.3,"NORMAL_DMG":51,"MAX_DMG_MULT":332.4,"MIN_DMG_MULT":275.1,
 "SKILL_LEVELS":38,"BASIC_ATK_DMG":101.2,"MAIN_STAT_PCT":229,"SKILL_LEVEL_1":0,"SKILL_LEVEL_2":16,
 "SKILL_LEVEL_3":34,"SKILL_LEVEL_4":102,"STAT_PROP_DMG":1578.0085,"SUB_STAT_FLAT":1300,
 "MAIN_STAT_FLAT":47865,"BASIC_ATK_TARGETS":3,"DMG_ADD":0,
}
def g(k): return S.get(k,0.0)

# ===== 유효 스킬레벨 (DamageModel.effectiveSkillLevel) =====
lv=g("LEVEL")
if   lv>=100: levelDerived=3*(lv-100); tier="SKILL_LEVEL_4"
elif lv>=60:  levelDerived=3*(lv-60);  tier="SKILL_LEVEL_3"
elif lv>=30:  levelDerived=3*(lv-30);  tier="SKILL_LEVEL_2"
elif lv>=10:  levelDerived=3*(lv-10);  tier="SKILL_LEVEL_1"
else:         levelDerived=0; tier=None
EFF = levelDerived + (g(tier) if tier else 0) + g("SKILL_LEVELS")

# ===== globalMultiplier (DamageModel.java, boss) — 단, 대상항(BOSS)은 스킬별로 뺌 =====
baseAtk = g("ATK_FLAT")*(1+g("ATK_PCT")/100.0)     # 나로 JOB=1 → defenseMainStat 0
fDmg = (1+(g("DMG")+g("DMG_ADD"))/100.0)*(1+g("DMG_AMP")/100.0)
fStatProp = 1+g("STAT_PROP_DMG")/100.0
fCrit = 1+min(g("CRIT_RATE")/100.0,1.0)*(g("CRIT_DMG")/100.0)
mn,mx=g("MIN_DMG_MULT"),g("MAX_DMG_MULT")
fRange = (mx/100.0) if mn>mx else (mn+mx)/200.0
fFinal = 1+g("FINAL_DMG")/100.0
G_notarget = baseAtk*fDmg*fStatProp*fCrit*fRange*fFinal
boss_target = 1+g("BOSS_DMG")/100.0
atkspeed = 1+g("ATK_SPEED")/100.0

# ===== 성장 =====
RATE={12:0.005,21:0.004,22:0.003}
def growth(slot,L=EFF):
    if slot is None: return 1.0
    if int(slot)==23: return 1+0.05*min(L,120)+0.01*max(0,L-120)
    r=RATE.get(int(slot)); return 1+r*L if r else 1.0

skills=IR["skills"]; masteries=IR.get("masteries",[])
# ===== 마스터리 집계 (lv<=캐릭레벨) =====
sd=defaultdict(float); hitb=defaultdict(int); cdb=defaultdict(float)
perb=defaultdict(float); bossb=defaultdict(float)
def me(sk,e):
    t=e.get("type");v=e.get("value",0);u=e.get("unit")
    if t=="skillDamage": sd[sk]+=v/1000.0
    elif t=="hit": hitb[sk]+=int(v)
    elif t=="cooldown": cdb[sk]+=v/1000.0
    elif t=="period": perb[sk]+=v
    elif t=="skillStat" and e.get("stat")=="AttackPowerToBoss": bossb[sk]+=v/1000.0
for m in masteries:
    if m.get("lv",0)>lv: continue
    sk=m.get("skill")
    if not sk: continue
    for e in (m["effects"] if "effects" in m else [m]): me(sk,e)
# ===== skillBoost =====
fd=defaultdict(list)
for s in skills:
    for e in s.get("effects",[]):
        if e.get("role")=="skillBoost" and e.get("type")=="finalDmg":
            fd[e["target"]].append((e.get("value",0)/1000.0)*growth(e.get("growthSlot")))

# ===== 발동빈도 =====
basic=[s for s in skills if s.get("trigger")=="basicAttack" and any(x.get("role")=="deal" for x in s.get("effects",[]))]
the_basic=max(basic,key=lambda s:s.get("requireLevel",0)) if basic else None
def afreq(s):
    cd=s.get("cooldown")
    return 1000.0/(cd*(1+cdb.get(s["idx"],0))) if cd else 0
def pfreq(s):
    return 1000.0/(s.get("periodMs",1)+perb.get(s["idx"],0))
attack_rate=0.0
for s in skills:
    if not any(x.get("role")=="deal" for x in s.get("effects",[])): continue
    tr=s.get("trigger")
    if tr=="basicAttack" and s is the_basic: attack_rate+=1.0*atkspeed
    elif tr=="active": attack_rate+=afreq(s)
    elif tr=="periodic": attack_rate+=pfreq(s)

# ===== 스킬별 DPS =====
rows=[]
for s in skills:
    deals=[e for e in s.get("effects",[]) if e.get("role")=="deal"]
    if not deals: continue
    idx=s["idx"]; tr=s.get("trigger")
    if tr=="basicAttack":
        if s is not the_basic: continue
        freq=1.0*atkspeed; fl="평타"
    elif tr=="active": freq=afreq(s); fl="쿨"
    elif tr=="periodic": freq=pfreq(s); fl="주기"
    elif tr=="onAttack":
        ch=s.get("chance",1000)/1000.0; freq=attack_rate*ch; fl="onAtk"
    else: continue
    is_basic = (tr=="basicAttack")
    dtype = (1+g("BASIC_ATK_DMG")/100.0) if is_basic else (1+g("SKILL_DMG")/100.0)
    tgt = 1 + g("BOSS_DMG")/100.0 + bossb.get(idx,0.0)         # 스킬 귀속 보스뎀 추가
    sdm = 1+sd.get(idx,0.0)
    fdm = 1.0
    for b in fd.get(idx,[]): fdm*=(1+b)
    per_act=0.0
    for e in deals:
        gr=growth(e.get("growthSlot")); ratio=(e.get("coeff",0)/1000.0)*gr
        ticks=max(1,int(round(e["durationMs"]/e["tickMs"]))) if (e.get("tickMs") and e.get("durationMs")) else 1
        hits=e.get("hits",1)+hitb.get(idx,0)
        per_act+=ratio*hits*ticks
    perhit = G_notarget*tgt*dtype*sdm*fdm*per_act
    dot_only=all(e.get("form")=="dot" for e in deals)
    if tr=="onAttack" and dot_only:
        dur=deals[0]["durationMs"]/1000.0; dps=perhit/dur
    else:
        dps=perhit*freq
    rows.append({"name":s["name"],"tr":tr,"fl":fl,"freq":freq,"perhit":perhit,"dps":dps})

rows.sort(key=lambda r:-r["dps"]); total=sum(r["dps"] for r in rows)
def kor(n):
    for u,s2 in [(1e16,'경'),(1e12,'조'),(1e8,'억'),(1e4,'만')]:
        if abs(n)>=u: return f"{n/u:.2f}{s2}"
    return f"{n:.0f}"

print("="*92)
print(f"나이트로드 IR 엔진 v1 — 프리셋 '챕터 보스' 보스 단일대상 절대 DPS")
print("="*92)
print(f"캐릭레벨 {lv}  유효스킬레벨(성장입력) EFF={EFF:.0f}  공속×{atkspeed:.3f}")
print(f"성장검증: 마크나이트로드 300%×growth(21,{EFF:.0f})={growth(21):.3f} = {300*growth(21):.0f}%")
print(f"G(대상항 제외) = 공격력{kor(baseAtk)} ×데미지{fDmg:.2f} ×스탯비례{fStatProp:.2f} ×크리{fCrit:.2f} ×범위{fRange:.3f} ×최종{fFinal:.3f} = {kor(G_notarget)}")
print(f"보스대상항 ×{boss_target:.3f}")
print("-"*92)
print(f"{'스킬':<15}{'발동':<8}{'빈도/s':>8}{'1타데미지':>13}{'DPS':>13}{'비중':>7}")
print("-"*92)
for r in rows:
    print(f"{r['name']:<15}{r['fl']:<8}{r['freq']:>8.3f}{kor(r['perhit']):>13}{kor(r['dps']):>13}{r['dps']/total*100:>6.1f}%")
print("-"*92)
print(f"{'종합 DPS':<44}{kor(total):>13}{100.0:>6.1f}%")
print(f"{'1초당 종합':>57} = {kor(total)}")
print()
print("[주] 동료·유물 미포함. 발동빈도(평타 1s×공속·액티브=쿨만)·dot가동률 정적근사. 선반영 버프는 프리셋에 포함.")
