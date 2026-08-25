# -*- coding: utf-8 -*-
# 나이트로드 SkillDescParamTable(desc 표시수치) + StateActionTable 8-4 vs 8-13 diff
# + 나이트로드 각 스킬 desc 완성문(파라미터 치환) 최신값 출력
import json, io, os, sys
sys.stdout.reconfigure(encoding="utf-8")

NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
OLD = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\bak_TextAsset_pre2694"
def load(b, n): return json.load(io.open(os.path.join(b, n), encoding="utf-8"))

def is_nl(idx):
    s = str(idx); return s.isdigit() and 90000 <= int(s) < 95000

# SkillDescParamTable 구조 확인 + 나이트로드 diff
dp_new = load(NEW, "SkillDescParamTable.json")
print("SkillDescParamTable 키:", list(dp_new[0].keys()))
def dpkey(r): return (str(r.get("SkillIndex")), str(r.get("ParamNo")))
on = {dpkey(r): r for r in load(OLD, "SkillDescParamTable.json")}
nn = {dpkey(r): r for r in dp_new}
print("\n### SkillDescParamTable 나이트로드 변경 ###")
cnt = 0
for k in sorted(set(on)|set(nn)):
    if not is_nl(k[0]): continue
    o = json.dumps(on.get(k), ensure_ascii=False, sort_keys=True)
    n = json.dumps(nn.get(k), ensure_ascii=False, sort_keys=True)
    if o != n:
        cnt += 1
        print(f"  {k}: {on.get(k)} -> {nn.get(k)}")
print(f"  변경 {cnt}개")

# StateActionTable 키 확인 + 나이트로드
sa = load(NEW, "StateActionTable.json")
print("\nStateActionTable 키:", list(sa[0].keys()))
