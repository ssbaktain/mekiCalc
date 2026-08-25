import json, os, math

base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
slots = json.load(open(os.path.join(base, 'HeroPowerSlotLevelTable.json'), encoding='utf-8'))
fac = json.load(open(os.path.join(base, 'HeroPowerSlotLevelStatFactorTable.json'), encoding='utf-8'))

# factor[level][levelFactorIndex] -> per-mille
F = {}
for r in fac:
    F[int(r['Level'])] = [int(x) for x in r['Factor']]

STATNAME = {'Attack':'공격력','MaxHp':'최대HP','Defence':'방어력','MainStat':'주스탯',
            'AttackPower':'데미지','HitChance':'명중'}

def slot_cost(base_count, lf_idx, maxlevel):
    # sum floor(base * factor[L][lf]/1000) for L=1..maxlevel  (level within stage)
    tot = 0
    per = []
    for L in range(1, maxlevel+1):
        c = (base_count * F[L][lf_idx]) // 1000
        tot += c
        per.append(c)
    return tot, per

# organize by stage
by_stage = {}
for s in slots:
    st = int(s['HeroPowerLevel'])
    by_stage.setdefault(st, []).append(s)

grand = 0
stage_totals = {}
for st in sorted(by_stage):
    print(f"=== {st}단계 ===")
    stotal = 0
    for s in sorted(by_stage[st], key=lambda x:int(x['SlotIndex'])):
        cnt = int(s['ReqLevelUpCost']['Count'])
        lf = int(s['LevelFactor'])
        ml = int(s['MaxLevel'])
        typ = s['StatData']['Type']
        tot, per = slot_cost(cnt, lf, ml)
        stotal += tot
        print(f"  슬롯{s['SlotIndex']} {STATNAME.get(typ,typ):5s} base={cnt} maxLv={ml} lf={lf} -> 총 {tot:,}  (첫값 {per[0]}, 끝값 {per[-1]})")
    stage_totals[st] = stotal
    grand += stotal
    print(f"  >>> {st}단계 합계: {stotal:,}")
    print()

print("단계별 합계:", {k:f"{v:,}" for k,v in stage_totals.items()})
cum = 0
for st in sorted(stage_totals):
    cum += stage_totals[st]
    print(f"  1~{st}단계 누적: {cum:,}")
print(f"\n★ 전체(1~8단계 풀강) 누적 용사의증표: {grand:,}")
