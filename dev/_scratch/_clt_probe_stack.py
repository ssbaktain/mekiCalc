# State/스택 시스템 조사: 스택 State의 쌓기(증가)·소모·지속·MaxStack + 조건부 딜 참조.
# 정상상태 스택수/가동률 모델 설계 근거. -*- coding: utf-8 -*-
import io, json, os, re
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): ST[str(r.get('SkillIndex'))] = r
STATE = {str(r.get('StateName')): r for r in load('StateTable.json')}
HS = load('HeroSkillTable.json')
# 직업 스킬만(CreatureIndex 1~14)
JOB_SKILLS = {}
for r in HS:
    ci = str(r.get('CreatureIndex'))
    if ci.isdigit() and 1 <= int(ci) <= 14:
        JOB_SKILLS.setdefault(ci, []).append(str(r.get('SkillIndex')))

# 관심 스택 State(직업 딜 관련, MaxStack>1 or 콤보/스택 메커니즘)
TARGETS = ['ComboAttack', 'Assassination', 'SerpentStone', 'AssaultMode', 'Meso', 'BloodyPocket']

def find_ops(state):
    """이 State를 조작하는 op(증가/부여/소모)를 전 스킬에서 찾음."""
    give, clear = [], []
    for si, s in ST.items():
        for o in (s.get('Operations') or []):
            if not isinstance(o, dict): continue
            t = o.get('Type', '')
            if o.get('State') == state or (o.get('Values') and state in [str(x) for x in (o.get('Values') or [])]):
                if 'Clear' in t: clear.append((si, t, o.get('Values')))
                elif t in ('State', 'AddState', 'ModStateStack', 'ModState'):
                    give.append((si, t, o.get('DurationMs'), s.get('TriggerType'), s.get('CoolTimeMs')))
    return give, clear

for stnm in TARGETS:
    stt = STATE.get(stnm, {})
    print(f"\n=== {stnm} (MaxStack={stt.get('MaxStack')} StackRule={stt.get('StackRule')} Dur={stt.get('DurationMs')}) ===")
    give, clear = find_ops(stnm)
    print(f"  부여/증가: {give[:6]}")
    print(f"  소모(Clear): {clear[:4]}")
