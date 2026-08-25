# State 가동률 조사: TriggerCondition에 State 조건($CreatureStateCount) 있는 딜 스킬 + State 부여 스킬·지속 전수.
# -*- coding: utf-8 -*-
import io, json, os, re
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r
STATE = {str(r.get('StateName')): r for r in load('StateTable.json')}

def has_deal(s):
    for o in (s.get('Operations') or []):
        if isinstance(o, dict) and ('GetDamage' in str(o.get('Type', '')) or 'GetDot' in str(o.get('Type', ''))):
            return True
    return False

print("=== TriggerCondition에 State 조건 있는 스킬 ===")
state_names = set()
for si, s in sorted(ST.items()):
    tc = str(s.get('TriggerCondition') or '')
    m = re.search(r'StateCount\([^;]+;(\w+)\)', tc)
    if m:
        stnm = m.group(1)
        state_names.add(stnm)
        deal = '딜' if has_deal(s) else '  '
        print(f"  {si} {deal} state={stnm:<22} TT={s.get('TriggerType')} cool={s.get('CoolTimeMs')} | TC={tc[:55]}")

print("\n=== 관련 State 부여 스킬(op State=StateName) + 지속 ===")
for stnm in sorted(state_names):
    stt = STATE.get(stnm, {})
    givers = []
    for si, s in ST.items():
        for o in (s.get('Operations') or []):
            if isinstance(o, dict) and o.get('State') == stnm and o.get('Type') in ('State', 'ModStat', 'ModStatR'):
                dur = o.get('DurationMs')
                givers.append((si, s.get('CoolTimeMs'), dur))
    print(f"  [{stnm}] StateTable지속={stt.get('DurationMs')} MaxStack={stt.get('MaxStack')} | 부여스킬(si,cd,opDur)={givers[:5]}")

# 디바인 저지먼트(24070) 별도(IsBaseAttack + State 부여형)
print("\n=== 디바인 저지먼트 24070 State ===")
s = ST.get('24070', {})
print(f"  TC={s.get('TriggerContdition') or s.get('TriggerCondition')} cool={s.get('CoolTimeMs')}")
for o in (s.get('Operations') or []):
    if isinstance(o, dict) and o.get('State'): print(f"    op {o.get('Type')} State={o.get('State')} Dur={o.get('DurationMs')}")
print(f"  StateTable[DivineJudgement]={STATE.get('DivineJudgement')}")
