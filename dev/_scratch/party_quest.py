# -*- coding: utf-8 -*-
import json, os
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def L(n): return json.load(open(os.path.join(base,n), encoding='utf-8'))
lr = {str(r['Level']): r for r in L('CustomGearLevelRangeTable.json')}
og = {str(r['OptionGroupIndex']): r for r in L('GearOptionGroupTable.json')}
ot = {str(r['OptionIndex']): r for r in L('GearOptionTable.json')}

# 반지 110001 (파퀘 1던전): LevelRangeIndex 40, SubOptionGroup 10103003
# --- 레벨 확률 ---
row = lr.get('40')
rate = row.get('Rate', [])
print("=== 레벨 확률 (LevelRangeIndex 40) ===")
tot = sum(int(x) for x in rate if x not in (None,))
lvls = []
for i, x in enumerate(rate):
    if x not in (None,) and int(x) > 0:
        lvls.append((i, int(x)))
print(f"  총가중치 {tot}, 비어있지않은 레벨수 {len(lvls)}")
for i, w in lvls:
    print(f"    레벨 {i}: 가중치 {w} = {w/tot*100:.2f}%")

# --- 부옵션 종류 (그룹 10103003) ---
print("\n=== 부옵션 종류별 확률 (그룹 10103003) ===")
grp = og.get('10103003', {})
opts = grp.get('OptionIndices', [])
print(f"  옵션 {len(opts)}종 (가중치 표기 없음 → 균등 추정 {100/len(opts):.2f}%씩)")
STATKO = {'Attack':'공격력','Defence':'방어력','MaxHp':'최대HP','MaxMpR':'MP%','AttackR':'공격력%',
 'DefenceR':'방어력%','MaxHpR':'최대HP%','AvoidChance':'회피','HitChance':'명중','CriticalChance':'크리확률',
 'CriticalPower':'크리데미지','AttackSpeed':'공격속도','AttackPowerToBoss':'보스뎀','AttackPower':'데미지'}
for oi in opts:
    o = ot.get(str(oi), {}).get('StatData', {})
    t = o.get('Type'); mn=o.get('MinValue'); mx=o.get('MaxValue'); f=o.get('Factor')
    print(f"    {oi}: {STATKO.get(t,t)}({t}) {mn}~{mx} (Factor {f})")
