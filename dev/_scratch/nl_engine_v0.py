# -*- coding: utf-8 -*-
"""
나이트로드 IR 엔진 v0 (첫걸음)
- 입력: data/skills_calc/nightlord_manual.json
- 스코프: 딜 스킬만. 동료·유물 배제. 보스 단일대상(targetCount=1) 기준.
- 목적: IR대로 성장·마스터리·skillBoost·발동빈도를 조립해 스킬별 DPS 기여도가 그럴듯한지 검증.
- 프리셋 전역스탯(공격력·크확 등)은 아직 미적용 → 순수 "계수 처리량(throughput)" 상대 비교.
  선반영(preReflected) 버프는 프리셋에 포함되므로 엔진에서 제외.
- 발동빈도/가동률은 정적 근사(ENGINE_SPEC: 정밀은 타임라인 시뮬 필요) — 라벨로 명시.
"""
import json, os

BASE = r"C:\Users\MhHs\Documents\project\mekiCalc"
IR = json.load(open(os.path.join(BASE,"data/skills_calc/nightlord_manual.json"), encoding="utf-8"))

# ---- 파라미터 ----
CHAR_LV = 187          # 캐릭터 레벨 (성장 앵커: 마크오브나이트로드 lv187=524.4%)
BASIC_ATK_SEC = 1.0    # 평타 주기(초). 공속 미적용(atkspeed=0) 가정. memory: 평타=1초
DOT_UPTIME = 1.0       # onAttack→dot 가동률 근사(잦은 프록으로 상시)

# ---- 성장 팩터 ----
RATE = {12:0.005, 21:0.004, 22:0.003}
def growth(slot, L=CHAR_LV):
    if slot is None: return 1.0
    if slot == 23: return 1 + 0.05*min(L,120) + 0.01*max(0,L-120)
    r = RATE.get(int(slot))
    return 1 + r*L if r is not None else 1.0

skills = IR["skills"]
by_idx = {s["idx"]: s for s in skills}
masteries = IR.get("masteries", [])

# ---- 마스터리 집계 (캐릭레벨 해금분만) ----
def m_applies(m): return m.get("lv",0) <= CHAR_LV
# 스킬별 마스터리 버킷
from collections import defaultdict
sd_bonus   = defaultdict(float)  # skillDamage 합(ratio)
hit_bonus  = defaultdict(int)    # hit +N
tc_bonus   = defaultdict(int)    # targetCount +N
cd_bonus   = defaultdict(float)  # cooldown ratio (음수=감소)
period_bonus_ms = defaultdict(float)
tick_bonus_ms   = defaultdict(float)
bossdmg_bonus   = defaultdict(float)  # skillStat AttackPowerToBoss ratio (그 스킬 귀속)
prob_bonus = defaultdict(float)

def handle_m_entry(skill_idx, e):
    t = e.get("type"); v = e.get("value",0); unit=e.get("unit")
    if t=="skillDamage":       sd_bonus[skill_idx]   += v/1000.0
    elif t=="hit":             hit_bonus[skill_idx]  += int(v)
    elif t=="targetCount":     tc_bonus[skill_idx]   += int(v)
    elif t=="cooldown":        cd_bonus[skill_idx]   += v/1000.0     # -300 = -30%
    elif t=="period":          period_bonus_ms[skill_idx] += v       # ms
    elif t=="tick":
        if unit=="ms":         tick_bonus_ms[skill_idx]  += v
        else:                  tick_bonus_ms[skill_idx]  += 0        # pct 틱은 v0 생략
    elif t=="probability":     prob_bonus[skill_idx] += v/1000.0
    elif t=="skillStat" and e.get("stat")=="AttackPowerToBoss":
        bossdmg_bonus[skill_idx] += v/1000.0

for m in masteries:
    if not m_applies(m): continue
    sk = m.get("skill")
    if "effects" in m:
        for e in m["effects"]:
            if sk: handle_m_entry(sk, e)
    else:
        if sk: handle_m_entry(sk, m)

# ---- skillBoost 집계 (다른 스킬이 이 스킬을 강화) ----
fd_boosts = defaultdict(list)   # target_idx -> [finalDmg ratio,...] (각각 곱)
tc_boosts = defaultdict(int)    # target_idx -> targetCount +N
for s in skills:
    for e in s.get("effects",[]):
        if e.get("role")=="skillBoost":
            tgt=e.get("target"); typ=e.get("type"); v=e.get("value",0); gs=e.get("growthSlot")
            if typ=="finalDmg":    fd_boosts[tgt].append((v/1000.0)*growth(gs))
            elif typ=="targetCount": tc_boosts[tgt]+=int(v)

# ---- 발동빈도 계산 ----
# 평타: basicAttack 중 최고 requireLevel 1개만 실제 평타
basic_deal = [s for s in skills if s.get("trigger")=="basicAttack" and any(e.get("role")=="deal" for e in s.get("effects",[]))]
the_basic = max(basic_deal, key=lambda s:s.get("requireLevel",0)) if basic_deal else None

def active_freq(s):
    cd = s.get("cooldown")
    if not cd: return None
    cd_eff = cd * (1 + cd_bonus.get(s["idx"],0.0))   # 쿨감 마스터리
    return 1000.0/cd_eff  # /s

def periodic_freq(s):
    p = s.get("periodMs",1)+period_bonus_ms.get(s["idx"],0)
    return 1000.0/p

# 공격 액션 빈도(onAttack 편승용): 평타 + 액티브딜 + 주기딜
attack_action_rate = 0.0
for s in skills:
    if not any(e.get("role")=="deal" for e in s.get("effects",[])): continue
    tr=s.get("trigger")
    if tr=="basicAttack" and s is the_basic: attack_action_rate += 1.0/BASIC_ATK_SEC
    elif tr=="active":   attack_action_rate += active_freq(s) or 0
    elif tr=="periodic": attack_action_rate += periodic_freq(s)

# ---- 스킬별 딜 처리량(보스 단일대상) ----
rows=[]
for s in skills:
    deals=[e for e in s.get("effects",[]) if e.get("role")=="deal"]
    if not deals: continue
    tr=s.get("trigger"); idx=s["idx"]
    # 발동빈도(/s)
    if tr=="basicAttack":
        if s is not the_basic: continue   # 상위 평타에 밀림
        freq=1.0/BASIC_ATK_SEC; freq_lbl=f"평타 {freq:.2f}/s"
    elif tr=="active":
        f=active_freq(s); freq=f or 0; freq_lbl=f"쿨 {freq:.3f}/s"
    elif tr=="periodic":
        freq=periodic_freq(s); freq_lbl=f"주기 {freq:.2f}/s"
    elif tr=="onAttack":
        ch=s.get("chance",1000)/1000.0
        freq=attack_action_rate*ch
        if s.get("gatedBy"): freq*=DOT_UPTIME
        freq_lbl=f"onAtk {freq:.2f}/s(×{ch:.0%})"
    else:
        continue
    # 스킬 강화 배수
    sd = 1 + sd_bonus.get(idx,0.0)                    # skillDamage 마스터리
    fdm = 1.0
    for b in fd_boosts.get(idx,[]): fdm *= (1+b)      # skillBoost finalDmg(각각 곱)
    # 각 deal effect별 계수×성장×타수×(dot틱)
    per_act = 0.0
    detail=[]
    for e in deals:
        g=growth(e.get("growthSlot"))
        ratio=(e.get("coeff",0)/1000.0)*g
        if e.get("form")=="dot" or (e.get("tickMs") and e.get("durationMs")):
            ticks=max(1,int(round(e["durationMs"]/e["tickMs"])))
        else:
            ticks=1
        hits=(e.get("hits",1))+hit_bonus.get(idx,0)
        contrib=ratio*hits*ticks
        per_act+=contrib
        detail.append(f"{e.get('coeff')}‰×{g:.3f}×{hits}타"+(f"×{ticks}틱" if ticks>1 else ""))
    # dot(onAttack proc) 가동률: 지속 dot는 활성당이 아니라 상시 → freq 대신 uptime로
    dot_only = all((e.get("form")=="dot") for e in deals)
    if tr=="onAttack" and dot_only:
        # dot DPS = per_act(전체틱) / (지속시간s) × uptime  ≈ 틱당×틱수/지속 = 틱당/틱주기
        dur=deals[0]["durationMs"]/1000.0
        dps = sd*fdm*per_act/dur*DOT_UPTIME
        freq_lbl=f"dot 상시({DOT_UPTIME:.0%})"
    else:
        dps = sd*fdm*per_act*freq
    rows.append({
        "name":s["name"],"idx":idx,"trigger":tr,"freq_lbl":freq_lbl,
        "per_act":per_act,"sd":sd,"fdm":fdm,"dps":dps,
        "boss":bossdmg_bonus.get(idx,0.0),
        "detail":" + ".join(detail),
    })

rows.sort(key=lambda r:-r["dps"])
total=sum(r["dps"] for r in rows)

print("="*96)
print(f"나이트로드 IR 엔진 v0 — 보스 단일대상 딜 처리량 (캐릭레벨 {CHAR_LV}, 평타 {BASIC_ATK_SEC}s, 스탯 미적용)")
print("="*96)
print(f"성장 검증: 마크오브나이트로드 finalDmg 300%×growth(21,{CHAR_LV})={growth(21):.3f} = {300*growth(21):.1f}%  (실측 524.4%)")
print(f"공격액션빈도(onAttack 편승용) = {attack_action_rate:.3f}/s")
print("-"*96)
print(f"{'스킬':<16}{'발동':<8}{'빈도':<16}{'활성당계수':>10}{'스킬뎀×':>7}{'최종뎀×':>8}{'DPS기여':>10}{'비중':>7}")
print("-"*96)
for r in rows:
    print(f"{r['name']:<16}{r['trigger']:<8}{r['freq_lbl']:<16}{r['per_act']*100:>9.0f}%{r['sd']:>6.2f}x{r['fdm']:>7.2f}x{r['dps']:>10.2f}{r['dps']/total*100:>6.1f}%")
print("-"*96)
print(f"{'합계':<48}{'':>27}{total:>10.2f}{100.0:>6.1f}%")
print()
print("[주] DPS는 '계수×타수×빈도' 상대 처리량(공격력·크리·최종뎀 등 전역스탯 미적용). 프리셋 스탯을 곱하면 절대 DPS.")
print("[주] 발동빈도·dot가동률은 정적 근사 — 정밀은 타임라인 시뮬(다음 단계).")
print("[주] 선반영 버프(다크세레니티·자벨린엑스퍼트 등)는 프리셋 스탯창에 포함 → 엔진 제외.")
print()
# 각 스킬 계수 조립 상세
print("계수 조립 상세:")
for r in rows:
    boss=f" boss+{r['boss']*100:.0f}%" if r['boss'] else ""
    print(f"  {r['name']:<16} = {r['detail']}  |스킬뎀×{r['sd']:.2f} 최종뎀×{r['fdm']:.2f}{boss}")
