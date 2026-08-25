# -*- coding: utf-8 -*-
# StateActionTable(State 스택별 StatValue) 나이트로드 관련 8-4 vs 8-13
import json, io, os, sys
sys.stdout.reconfigure(encoding="utf-8")
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
OLD = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\bak_TextAsset_pre2694"
def load(b, n): return json.load(io.open(os.path.join(b, n), encoding="utf-8"))

# 나이트로드 State 이름들(SkillTable1 op에서 참조): 숫자 State + 명명 State
PATS = ["93060","92040","92041","93080","93040","94040","94080","94070","94050",
        "MarkOfAssassin","DarkFlare","PurgeArea","Venom","Fatal","Fake","Adrenal","WindTalisman","93080_"]
def rel(name):
    return any(p in str(name) for p in PATS)

def key(r): return (str(r.get("StateName")), str(r.get("StackCount")))
on = {key(r): r for r in load(OLD, "StateActionTable.json")}
nn = {key(r): r for r in load(NEW, "StateActionTable.json")}
print("### StateActionTable 나이트로드 관련 변경 ###")
allk = sorted(set(on)|set(nn))
cnt = 0
seen_names = set()
for k in allk:
    if not rel(k[0]): continue
    seen_names.add(k[0])
    o = json.dumps(on.get(k), ensure_ascii=False, sort_keys=True)
    n = json.dumps(nn.get(k), ensure_ascii=False, sort_keys=True)
    if o != n:
        cnt += 1
        print(f"  {k}: {on.get(k)} -> {nn.get(k)}")
print(f"  관련 State: {sorted(seen_names)}")
print(f"  변경 {cnt}개")
