# -*- coding: utf-8 -*-
"""
clt2 나이트로드 DPS 엔진 (이벤트 기반 타임라인 시뮬)
스펙: dev/_clt2_ENGINE_SPEC.md + data/skills_calc/md/{_clt2_IR_TAGS,_clt2_SIM_MODEL}.md
- 유물·동료 배제(소스 분리 구조라 후조립). 스킬 딜만.
- 기본모드 로테이션: 매 행동슬롯 최소 idx active 재선택, 없으면 평타.
- 버프 on/off 상태 → 각 딜 이벤트는 그 순간 켜진 버프로 공식 계산.
"""
import json, os, heapq
from collections import defaultdict
BASE = r"C:\Users\MhHs\Documents\project\mekiCalc"
IR = json.load(open(os.path.join(BASE,"data/skills_calc/nightlord_manual.json"), encoding="utf-8"))
MDEF = json.load(open(os.path.join(BASE,"data/monster_defense.json"), encoding="utf-8"))["chapters"]

# ============ 프리셋 ============
PRESETS = {
 "자동사냥": dict(stats={
   "LEVEL":116,"ATK_FLAT":997382,"ATK_PCT":27522.9,"CRIT_RATE":111.5,"CRIT_DMG":304,"ATK_SPEED":75.4,
   "STAT_PROP_DMG":1587.2863,"DMG":922.1,"DMG_AMP":61.7,"BASIC_ATK_DMG":101.2,"SKILL_DMG":87.3,"DEF_PEN":42.1,
   "BOSS_DMG":127.2,"NORMAL_DMG":247.7,"MIN_DMG_MULT":241.5,"MAX_DMG_MULT":411.3,"FINAL_DMG":104.2,
   "BASIC_ATK_TARGETS":3,"SKILL_LEVEL_4":124,"SKILL_LEVELS":38,"JOB":1,"COOLDOWN_REDUCTION_PCT":0},
   ch="36", stage="2", boss=False, simT=90.0),
 "자동사냥최소뎀": dict(stats={
   "LEVEL":116,"ATK_FLAT":986105,"ATK_PCT":27522.9,"CRIT_RATE":111.5,"CRIT_DMG":305,"ATK_SPEED":38.9,
   "STAT_PROP_DMG":1347.7231,"DMG":950.1,"DMG_AMP":61.7,"BASIC_ATK_DMG":89.2,"SKILL_DMG":87.3,"DEF_PEN":42.1,
   "BOSS_DMG":162.2,"NORMAL_DMG":120.2,"MIN_DMG_MULT":376.6,"MAX_DMG_MULT":346.2,"FINAL_DMG":104.2,
   "BASIC_ATK_TARGETS":3,"SKILL_LEVEL_4":106,"SKILL_LEVELS":38,"JOB":1,"COOLDOWN_REDUCTION_PCT":0},
   ch="36", stage="2", boss=False, simT=90.0),
 "챕터보스": dict(stats={
   "LEVEL":114,"ATK_FLAT":993249.2,"ATK_PCT":27440.9,"CRIT_RATE":108.6,"CRIT_DMG":316.7,"ATK_SPEED":63.8,
   "STAT_PROP_DMG":1578.0085,"DMG":876.6,"DMG_AMP":57.6,"BASIC_ATK_DMG":101.2,"SKILL_DMG":87.3,"DEF_PEN":41.3,
   "BOSS_DMG":401.2,"NORMAL_DMG":51,"MIN_DMG_MULT":275.1,"MAX_DMG_MULT":332.4,"FINAL_DMG":69.8,
   "BASIC_ATK_TARGETS":3,"SKILL_LEVEL_4":102,"SKILL_LEVELS":38,"JOB":1,"COOLDOWN_REDUCTION_PCT":0},
   ch="34", stage="boss", boss=True, simT=70.0),
}

import sys
PICK = sys.argv[1] if len(sys.argv)>1 else "자동사냥"
ALLBUFF = len(sys.argv)>2 and sys.argv[2]=="allbuff"   # 액티브 버프 전부 상시 on
NOBUFF  = len(sys.argv)>2 and sys.argv[2]=="nobuff"    # 완전 노버프(엔진버프 전부 off, 선반영만)
P = PRESETS[PICK]; S = P["stats"]; BOSS = P["boss"]; T = P["simT"]
MAXT = 1     # 1대상(1마리/보스) 기준. 보드총합 원하면 필드 몹수로.
def g(k): return S.get(k,0.0)

# 방어배수
mdef = MDEF[P["ch"]]["boss"] if BOSS else MDEF[P["ch"]]["stages"][P["stage"]]
DEFPEN = min(g("DEF_PEN"),100)/100.0
FDEF = 5000.0/(6000.0 + mdef*max(0,1-DEFPEN))

# 유효 스킬레벨(4차 가정, lv>=100)
LV = g("LEVEL")
EFF = 3*(LV-100) + g("SKILL_LEVEL_4") + g("SKILL_LEVELS")

# 성장
RATE={12:0.005,21:0.004,22:0.003}
def growth(slot,L=EFF):
    if slot is None: return 1.0
    s=int(slot)
    if s==23: return 1+0.05*min(L,120)+0.01*max(0,L-120)
    return 1+RATE[s]*L if s in RATE else 1.0

skills=[s for s in IR["skills"] if s.get("requireLevel",1)<=LV]   # 안 배운 스킬 제외
by_idx={s["idx"]:s for s in skills}
masteries=[m for m in IR.get("masteries",[]) if m.get("lv",0)<=LV]

# ============ 마스터리 집계 ============
m_sd=defaultdict(float); m_hit=defaultdict(int); m_tc=defaultdict(int); m_cd=defaultdict(float)
m_period=defaultdict(float); m_dur=defaultdict(float); m_bossStat=defaultdict(float)
def m_apply(sk,e):
    t=e.get("type"); v=e.get("value",0); u=e.get("unit")
    if t=="skillDamage": m_sd[sk]+=v/1000.0
    elif t=="hit": m_hit[sk]+=int(v)
    elif t=="targetCount": m_tc[sk]+=int(v)
    elif t=="cooldown": m_cd[sk]+=v/1000.0           # 쿨감%(체감 근사: 단순합)
    elif t=="period": m_period[sk]+=v                # ms
    elif t=="duration" and u=="pct": m_dur[sk]+=v/1000.0
    elif t=="skillStat" and e.get("stat")=="AttackPowerToBoss": m_bossStat[sk]+=v/1000.0
for m in masteries:
    sk=m.get("skill")
    if not sk: continue
    for e in (m["effects"] if "effects" in m else [m]): m_apply(sk,e)
# 마스터리 약화 디버프(베놈약화·마크약화, 적 Weakness) — 캐릭스탯 아님(프리셋 미포함) → 엔진 적용. 성장X(고정)
m_weak=0.0
for m in masteries:
    for e in (m["effects"] if "effects" in m else [m]):
        if e.get("stat")=="Weakness" and (e.get("role")=="debuff" or m.get("role")=="debuff"):
            m_weak += e.get("value",0)/1000.0

# ============ skillBoost ============
fd_boost=defaultdict(list); tc_boost=defaultdict(int)
for s in skills:
    for e in s.get("effects",[]):
        if e.get("role")=="skillBoost":
            if e.get("type")=="finalDmg": fd_boost[e["target"]].append((e.get("value",0)/1000.0)*growth(e.get("growthSlot")))
            elif e.get("type")=="targetCount": tc_boost[e["target"]]+=int(e.get("value",0))

# ============ 쿨타임(쿨감) ============
def eff_cd(s):
    cd=s.get("cooldown")
    if not cd: return None
    pct=min(1.0, m_cd.get(s["idx"],0.0)*-1 + g("COOLDOWN_REDUCTION_PCT")/100.0)  # m_cd 음수
    cd1=cd*(1-pct)
    return max(4000.0, cd1)/1000.0   # 하한 4초, 초 단위

# ============ 딜 스펙 (스킬별, 컴포넌트 분리) ============
def deal_spec(s):
    idx=s["idx"]; deals=[e for e in s.get("effects",[]) if e.get("role")=="deal"]
    isBasic=s.get("trigger")=="basicAttack"
    comps=[]
    for e in deals:
        gr=growth(e.get("growthSlot")); r=(e.get("coeff",0)/1000.0)*gr
        hits=e.get("hits",1)+m_hit.get(idx,0)
        tc = e.get("targetCount",1) + m_tc.get(idx,0) + tc_boost.get(idx,0) + (int(g("BASIC_ATK_TARGETS")) if isBasic else 0)
        isdot = e.get("form")=="dot" or (e.get("tickMs") and e.get("durationMs"))
        if isdot:
            tick=e["tickMs"]/1000.0; ticks=max(1,int(round(e["durationMs"]/e["tickMs"])))
            comps.append(dict(coeff=r*hits, tc=tc, dot=True, tick=tick, ticks=ticks, hits=hits))
        else:
            comps.append(dict(coeff=r*hits, tc=tc, dot=False, hits=hits))
    return dict(comps=comps, dmgMastery=m_sd.get(idx,0.0), bossStat=m_bossStat.get(idx,0.0),
                fdBoost=fd_boost.get(idx,[]), isBasic=isBasic)

# ============ 버프 스펙 (비선반영 active/count 버프) ============
# 선반영(preReflected:true) 버프는 프리셋에 포함 → 제외. onStart ATK_PCT(post)는 상시 엔진적용.
# 엔진 반영 버프: statUp effect 중 preReflected!=true (또는 ATK_PCT post 상시)
def buff_effects(s):
    out=[]
    for e in s.get("effects",[]):
        if e.get("role") not in ("statUp","debuff"): continue   # debuff=적 약화(Weakness) 포함
        if e.get("role")=="debuff" and e.get("stat")!="Weakness": continue
        if e.get("preReflected"): continue          # 프리셋 포함
        stat=e.get("stat"); val=e.get("value",0)*growth(e.get("growthSlot"))/1000.0  # 배율/‰→ratio
        durs=e.get("durationMs")
        # duration 마스터리
        durf=1+m_dur.get(s["idx"],0.0)
        out.append(dict(stat=stat, val=val, dur=(durs*durf/1000.0 if durs else None), mode=e.get("mode")))
    return out

# ============ 데미지 공식 (DamageModel.globalMultiplier + tf-add + 방어 + skillBoost) ============
def base_G(active_buffs, fd_mult=1.0):
    """active_buffs: {stat: total_ratio} (엔진버프 델타, 합산스탯). fd_mult=최종뎀 효과별 곱."""
    crit_extra = active_buffs.get("CriticalChance",0.0)*100  # %p
    critdmg_extra = active_buffs.get("CriticalPower",0.0)*100
    weak = active_buffs.get("Weakness",0.0)          # 적 받피증 ratio
    skp = active_buffs.get("SkillPower",0.0)*100
    minr= active_buffs.get("MinDamageRatio",0.0)*100
    maxr= active_buffs.get("MaxDamageRatio",0.0)*100
    atkpct_post = active_buffs.get("ATK_PCT",0.0)    # 스킬 공퍼(post 곱)
    baseAtk=g("ATK_FLAT")*(1+g("ATK_PCT")/100.0)*(1+atkpct_post)
    fDmg=(1+g("DMG")/100.0)*(1+g("DMG_AMP")/100.0)
    fStatProp=1+g("STAT_PROP_DMG")/100.0
    cr=min((g("CRIT_RATE")+crit_extra)/100.0,1.0)
    fCrit=1+cr*((g("CRIT_DMG")+critdmg_extra)/100.0)
    mn=g("MIN_DMG_MULT")+minr; mx=g("MAX_DMG_MULT")+maxr
    fRange=(mn/100.0) if mn>mx else (mn+mx)/200.0   # 최소뎀 오버세팅: MIN 천장(유저 확정)
    fFinal=(1+g("FINAL_DMG")/100.0)*fd_mult      # 프리셋 최종뎀 × 엔진버프 최종뎀(각각 곱)
    fWeak=1+weak
    G=baseAtk*fDmg*fStatProp*fCrit*fRange*fFinal*fWeak
    return G, skp

def hit_damage(spec, comp, active_buffs, fd_mult=1.0):
    G, skp = base_G(active_buffs, fd_mult)
    tgt = 1 + (g("BOSS_DMG") if BOSS else g("NORMAL_DMG"))/100.0 + (spec["bossStat"] if BOSS else 0.0)
    dtype = (1+g("BASIC_ATK_DMG")/100.0) if spec["isBasic"] else (1+(g("SKILL_DMG")+skp)/100.0)
    tf = dtype + spec["dmgMastery"]                 # ★타입뎀+피해마스터리 (합)
    fdm=1.0
    for b in spec["fdBoost"]: fdm*=(1+b)            # skillBoost 최종뎀(각각 곱)
    return G*FDEF*tgt*tf*fdm*comp["coeff"]*min(comp["tc"],MAXT)

# ============ 타임라인 시뮬 (기본모드) ============
# 로테이션 참여 active: type active + (deal 또는 buff). utility만이면 제외.
def is_rotational(s):
    if s.get("type")!="active": return False
    roles=set(s.get("role",[]))
    return bool(roles & {"deal","buff","debuff"})
actives=sorted([s for s in skills if is_rotational(s) and s.get("trigger")=="active"], key=lambda s:int(s["idx"]))
basic_cands=[s for s in skills if s.get("trigger")=="basicAttack" and any(e.get("role")=="deal" for e in s.get("effects",[]))]
the_basic=max(basic_cands,key=lambda s:s.get("requireLevel",0)) if basic_cands else None

specs={s["idx"]:deal_spec(s) for s in skills if any(e.get("role")=="deal" for e in s.get("effects",[]))}
buffspecs={s["idx"]:buff_effects(s) for s in skills}

# 전체 버프 집합(ALLBUFF용) + 공속 캐이던스
full_buff=defaultdict(float); full_fd_mult=1.0
for idx,bes in buffspecs.items():
    for be in bes:
        if be["stat"]=="FinalDamage": full_fd_mult*=(1+be["val"])   # ★최종뎀=효과별 곱
        else: full_buff[be["stat"]]+=be["val"]
full_buff["Weakness"]+=m_weak      # 마스터리 약화(베놈·마크)
as_cadence = g("ATK_SPEED") + (full_buff.get("AttackSpeed",0.0)*100 if ALLBUFF else 0.0)
slotLen = 1.0/(1+as_cadence/100.0)

cd_ready={s["idx"]:0.0 for s in actives}
buff_windows=[]         # (stat, val, mode, start, end)
# ★onStart 상시 패시브(비선반영, 숙련된표창술 공퍼 등) — 시뮬 내내 ON
perm_buffs=defaultdict(float)
perm_passive=defaultdict(float); perm_passive_fd=1.0    # 끌 수 없는 상시 패시브(숙련된표창술)만 — 베놈약화 제외
for s in skills:
    if s.get("trigger")=="onStart" and s.get("type")=="passive":
        for be in buffspecs.get(s["idx"],[]):
            buff_windows.append((be["stat"], be["val"], be.get("mode"), 0.0, T))
            perm_buffs[be["stat"]]+=be["val"]
            if be["stat"]=="FinalDamage": perm_passive_fd*=(1+be["val"])
            else: perm_passive[be["stat"]]+=be["val"]
# 마스터리 약화(베놈·마크) — 자동사냥 상시 근사(베놈~상시·마크 주기2s 상시)
if m_weak>0:
    buff_windows.append(("Weakness", m_weak, None, 0.0, T))
    perm_buffs["Weakness"]+=m_weak
deal_events=[]          # (time, idx, comp, weight)
attack_actions=[]       # 공격 액션 시각(평타+액티브딜) → onAttack
active_casts=[]         # 액티브 발동 시각(평타 포함) → onActiveSkill

def start_buff(s, t):
    for be in buffspecs.get(s["idx"],[]):
        end = t + be["dur"] if be["dur"] else T
        buff_windows.append((be["stat"], be["val"], be.get("mode"), t, end))

def emit(idx, t, weight=1.0):
    """스킬 딜을 컴포넌트별로 발동. dot은 틱 이벤트로 전개."""
    sp=specs[idx]
    for comp in sp["comps"]:
        if comp["dot"]:
            for k in range(comp["ticks"]):
                tt=t+k*comp["tick"]
                if tt<T: deal_events.append((tt, idx, comp, weight))
        else:
            deal_events.append((t, idx, comp, weight))

# 슬롯 진행 (기본모드)
t=0.0; EPS=1e-9
while t < T:
    fired=None
    for s in actives:
        if cd_ready[s["idx"]] <= t+EPS: fired=s; break
    if fired is not None:
        cd=eff_cd(fired) or slotLen
        cd_ready[fired["idx"]]=t+cd
        active_casts.append(t)
        if "deal" in fired.get("role",[]) and fired["idx"] in specs:
            emit(fired["idx"], t); attack_actions.append(t)
        if buffspecs.get(fired["idx"]): start_buff(fired, t)
    else:
        if the_basic:
            emit(the_basic["idx"], t); attack_actions.append(t); active_casts.append(t)
    t+=slotLen

# ---- 배경 트리거 ----
# periodic
for s in skills:
    if s.get("trigger")=="periodic" and s["idx"] in specs:
        pm=(s.get("periodMs",1)+m_period.get(s["idx"],0))/1000.0
        tt=pm
        while tt<T: emit(s["idx"], tt); tt+=pm
# onActiveSkill count 버프 (아드레날린) — refresh:false: 지속 중 카운트 정지(중복 스택 금지)
for s in skills:
    if s.get("trigger")!="onActiveSkill": continue
    cnt=s.get("count",1)
    bdur=max([be["dur"] for be in buffspecs.get(s["idx"],[]) if be["dur"]] or [0])
    refresh=s.get("refresh",False)
    active_until=-1.0; c=0
    for tc in active_casts:
        if not refresh and tc < active_until: continue   # 지속 중 카운트 정지
        c+=1
        if c>=cnt:
            start_buff(s, tc); c=0; active_until=tc+bdur
# onAttack 딜
for s in skills:
    if s.get("trigger")!="onAttack" or s["idx"] not in specs: continue
    idx=s["idx"]; ch=s.get("chance",1000)/1000.0; sp=specs[idx]
    dotcomp=next((c for c in sp["comps"] if c["dot"]), None)
    if dotcomp:
        # onAttack dot(베놈): 잦은 프록으로 상시 근사 → 시뮬 내내 tick
        tt=dotcomp["tick"]
        while tt<T: deal_events.append((tt, idx, dotcomp, 1.0)); tt+=dotcomp["tick"]
    else:
        # 즉발 프록(쉐도우파트너·페이탈베놈): 공격 액션마다 확률 기댓값
        for ta in attack_actions: emit(idx, ta, ch)

# ---- 딜 이벤트 → 소스별 집계 ----
def buffs_at(tt):
    if NOBUFF: return perm_passive, perm_passive_fd   # 노버프: 상시패시브(숙련)만, 베놈약화·액티브 off
    if ALLBUFF: return full_buff, full_fd_mult   # 전 버프 상시 on
    out=defaultdict(float); fd=1.0
    for stat,val,mode,st,en in buff_windows:
        if st<=tt<en:
            if stat=="FinalDamage": fd*=(1+val)   # ★최종뎀=효과별 곱
            else: out[stat]+=val
    return out, fd
src_dmg=defaultdict(float); src_hits=defaultdict(float)
for tt,idx,comp,wgt in deal_events:
    ab,fdm=buffs_at(tt)
    src_dmg[idx]+=hit_damage(specs[idx], comp, ab, fdm)*wgt
    src_hits[idx]+=comp["hits"]*wgt          # 개별 타수(버프 포함 누적 기준)
def hit1(idx):  # 1타 = 누적딜 / 총타수 (버프분 평균 포함)
    return src_dmg[idx]/src_hits[idx] if src_hits[idx] else 0.0

# ---- 출력 ----
def kor(n):
    for u,s2 in [(1e16,'경'),(1e12,'조'),(1e8,'억'),(1e4,'만')]:
        if abs(n)>=u: return f"{n/u:.2f}{s2}"
    return f"{n:.0f}"
rows=sorted([(by_idx[i]["name"], d/T, i) for i,d in src_dmg.items()], key=lambda r:-r[1])
total=sum(r[1] for r in rows)
print("="*104)
print(f"clt2 엔진 — 프리셋 '{PICK}' {'보스' if BOSS else '일반몹'} 1대상  (시뮬 {T:.0f}초, 슬롯 {slotLen*1000:.0f}ms){'  [★액티브버프 전부 ON]' if ALLBUFF else ''}")
print("="*104)
print(f"LV{LV:.0f} 유효스킬레벨 {EFF:.0f}  공속 {g('ATK_SPEED')}%  방어력 {mdef} 방관 {g('DEF_PEN')}% → f={FDEF:.4f}")
print("-"*104)
print(f"{'스킬':<15}{'1타(누적/타수)':>18}{'초당타수':>10}{'DPS':>14}{'비중':>8}")
print("-"*104)
for name,dps,idx in rows:
    print(f"{name:<15}{kor(hit1(idx)):>18}{src_hits[idx]/T:>10.2f}{kor(dps):>14}{dps/total*100:>7.1f}%")
print("-"*104)
print(f"{'종합 DPS':<15}{'':>18}{'':>10}{kor(total):>14}{100.0:>7.1f}%")
print(f"[주] 1타 = 스킬별 누적딜 ÷ 총타수(버프분 평균 포함). DPS = 누적딜 ÷ {T:.0f}초. dot·다단타·프록 개별타수 반영. 시한부 버프(다크사이트·아드레날린·퍼지) 켜진 구간 자동 반영.")
