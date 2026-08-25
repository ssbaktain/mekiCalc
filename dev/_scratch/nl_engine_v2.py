# -*- coding: utf-8 -*-
"""나이트로드 IR 엔진 v2 — 일반몹(자동사냥) 기준. 다중대상 반영."""
import json, os
from collections import defaultdict
BASE = r"C:\Users\MhHs\Documents\project\mekiCalc"
IR = json.load(open(os.path.join(BASE,"data/skills_calc/nightlord_manual.json"), encoding="utf-8"))

# ===== 프리셋 "자동 사냥" (ch36 일반몹) =====
S = {
 "LEVEL":116,"ATK_FLAT":997382,"ATK_PCT":27522.9,"DEFENSE":60972,"CRIT_RATE":111.5,"CRIT_DMG":304,
 "ATK_SPEED":75.4,"MAIN_STAT_FLAT":48147,"MAIN_STAT_PCT":229,"SUB_STAT_FLAT":1300,"STAT_PROP_DMG":1587.2863,
 "DMG":922.1,"DMG_AMP":61.7,"BASIC_ATK_DMG":101.2,"SKILL_DMG":87.3,"DEF_PEN":42.1,"BOSS_DMG":127.2,
 "NORMAL_DMG":247.7,"MIN_DMG_MULT":241.5,"MAX_DMG_MULT":411.3,"FINAL_DMG":104.2,"BASIC_ATK_TARGETS":3,
 "SKILL_LEVEL_2":14,"SKILL_LEVEL_3":35,"SKILL_LEVEL_4":124,"SKILL_LEVELS":38,"JOB":1,"DMG_ADD":0,
}
def g(k): return S.get(k,0.0)
TARGET_MODE="normal"   # 일반몹

# ===== 몬스터 방어배수 (ch36 stage2) =====
MDEF=json.load(open(os.path.join(BASE,"data/monster_defense.json"),encoding="utf-8"))["chapters"]
mdef=MDEF["36"]["stages"]["2"]                       # 일반몹 방어력
fDef=5000.0/(6000.0+mdef*max(0,1-g("DEF_PEN")/100.0))

lv=g("LEVEL")
levelDerived=3*(lv-100); tier="SKILL_LEVEL_4"     # lv116 → 4차
EFF = levelDerived + g(tier) + g("SKILL_LEVELS")

baseAtk=g("ATK_FLAT")*(1+g("ATK_PCT")/100.0)
fDmg=(1+(g("DMG")+g("DMG_ADD"))/100.0)*(1+g("DMG_AMP")/100.0)
fStatProp=1+g("STAT_PROP_DMG")/100.0
fCrit=1+min(g("CRIT_RATE")/100.0,1.0)*(g("CRIT_DMG")/100.0)
mn,mx=g("MIN_DMG_MULT"),g("MAX_DMG_MULT")
fRange=(mx/100.0) if mn>mx else (mn+mx)/200.0
fFinal=1+g("FINAL_DMG")/100.0
G_notarget=baseAtk*fDmg*fStatProp*fCrit*fRange*fFinal
target = 1 + (g("NORMAL_DMG") if TARGET_MODE=="normal" else g("BOSS_DMG"))/100.0
atkspeed=1+g("ATK_SPEED")/100.0

RATE={12:0.005,21:0.004,22:0.003}
def growth(slot,L=EFF):
    if slot is None: return 1.0
    if int(slot)==23: return 1+0.05*min(L,120)+0.01*max(0,L-120)
    r=RATE.get(int(slot)); return 1+r*L if r else 1.0

skills=IR["skills"]; masteries=IR.get("masteries",[])
sd=defaultdict(float); hitb=defaultdict(int); tcb=defaultdict(int); cdb=defaultdict(float); perb=defaultdict(float)
def me(sk,e):
    t=e.get("type");v=e.get("value",0)
    if t=="skillDamage": sd[sk]+=v/1000.0
    elif t=="hit": hitb[sk]+=int(v)
    elif t=="targetCount": tcb[sk]+=int(v)
    elif t=="cooldown": cdb[sk]+=v/1000.0
    elif t=="period": perb[sk]+=v
    # skillStat AttackPowerToBoss → 일반몹 무관, 생략
for m in masteries:
    if m.get("lv",0)>lv: continue
    sk=m.get("skill")
    if not sk: continue
    for e in (m["effects"] if "effects" in m else [m]): me(sk,e)
fd=defaultdict(list); tc_boost=defaultdict(int)
for s in skills:
    for e in s.get("effects",[]):
        if e.get("role")=="skillBoost":
            if e.get("type")=="finalDmg": fd[e["target"]].append((e.get("value",0)/1000.0)*growth(e.get("growthSlot")))
            elif e.get("type")=="targetCount": tc_boost[e["target"]]+=int(e.get("value",0))

basic=[s for s in skills if s.get("trigger")=="basicAttack" and any(x.get("role")=="deal" for x in s.get("effects",[]))]
the_basic=max(basic,key=lambda s:s.get("requireLevel",0)) if basic else None
def afreq(s):
    cd=s.get("cooldown"); return 1000.0/(cd*(1+cdb.get(s["idx"],0))) if cd else 0
def pfreq(s): return 1000.0/(s.get("periodMs",1)+perb.get(s["idx"],0))
attack_rate=0.0
for s in skills:
    if not any(x.get("role")=="deal" for x in s.get("effects",[])): continue
    tr=s.get("trigger")
    if tr=="basicAttack" and s is the_basic: attack_rate+=1.0*atkspeed
    elif tr=="active": attack_rate+=afreq(s)
    elif tr=="periodic": attack_rate+=pfreq(s)

rows=[]
for s in skills:
    if s.get("requireLevel",1) > lv: continue        # 안 배운 스킬 제외
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
    is_basic=(tr=="basicAttack")
    # ★기존 엔진: tf = 타입뎀배수 + 피해마스터리(합, 곱 아님)
    tf=((1+g("BASIC_ATK_DMG")/100.0) if is_basic else (1+g("SKILL_DMG")/100.0)) + sd.get(idx,0.0)
    fdm=1.0
    for b in fd.get(idx,[]): fdm*=(1+b)
    # 대상수: effect targetCount + 마스터리 tc + skillBoost tc (+평타는 BASIC_ATK_TARGETS)
    per_act=0.0; base_tc=1
    for e in deals:
        gr=growth(e.get("growthSlot")); ratio=(e.get("coeff",0)/1000.0)*gr
        ticks=max(1,int(round(e["durationMs"]/e["tickMs"]))) if (e.get("tickMs") and e.get("durationMs")) else 1
        hits=e.get("hits",1)+hitb.get(idx,0)
        per_act+=ratio*hits*ticks
        base_tc=max(base_tc, e.get("targetCount",1))
    tc = base_tc + tcb.get(idx,0) + tc_boost.get(idx,0) + (int(g("BASIC_ATK_TARGETS")) if is_basic else 0)
    perhit=G_notarget*fDef*target*tf*fdm*per_act
    dot_only=all(e.get("form")=="dot" for e in deals)
    if tr=="onAttack" and dot_only:
        dur=deals[0]["durationMs"]/1000.0; dps=perhit/dur      # 1마리 기준 — 대상수 곱 안함
    else:
        dps=perhit*freq                                        # 1마리 기준
    rows.append({"name":s["name"],"fl":fl,"freq":freq,"tc":tc,"perhit":perhit,"dps":dps})

rows.sort(key=lambda r:-r["dps"]); total=sum(r["dps"] for r in rows)
def kor(n):
    for u,s2 in [(1e16,'경'),(1e12,'조'),(1e8,'억'),(1e4,'만')]:
        if abs(n)>=u: return f"{n/u:.2f}{s2}"
    return f"{n:.0f}"

print("="*96)
print("나이트로드 IR 엔진 v2 — 프리셋 '자동 사냥' 일반몹 기준 (다중대상 보드 DPS)")
print("="*96)
print(f"캐릭레벨 {lv}  유효스킬레벨 EFF={EFF:.0f}  공속×{atkspeed:.3f}  일반몹대상항×{target:.3f}")
print(f"몬스터 방어력(ch36 s2)={mdef} 방관{g('DEF_PEN')}% → 방어배수 f={fDef:.4f}")
print(f"G(대상 제외)=공격력{kor(baseAtk)} ×데미지{fDmg:.2f} ×스탯비례{fStatProp:.2f} ×크리{fCrit:.2f} ×범위{fRange:.3f} ×최종{fFinal:.3f} = {kor(G_notarget)}")
print("-"*96)
print(f"{'스킬':<15}{'발동':<7}{'빈도/s':>8}{'대상':>5}{'1타데미지':>13}{'보드DPS':>13}{'비중':>7}")
print("-"*96)
for r in rows:
    print(f"{r['name']:<15}{r['fl']:<7}{r['freq']:>8.3f}{r['tc']:>5}{kor(r['perhit']):>13}{kor(r['dps']):>13}{r['dps']/total*100:>6.1f}%")
print("-"*96)
print(f"{'종합 보드 DPS':<48}{kor(total):>13}{100.0:>6.1f}%")
print()
print("[주] 일반몹=다중대상. 대상수=IR targetCount+마스터리+skillBoost(+평타 BASIC_ATK_TARGETS). 필드에 몹 충분 가정.")
print("[주] 보스전용 마스터리(skillStat 보스뎀) 제외. 발동빈도·dot 정적근사. 동료·유물 미포함.")
