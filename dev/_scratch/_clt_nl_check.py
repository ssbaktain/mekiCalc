# -*- coding: utf-8 -*-
# 나이트로드 스킬(SkillTable1) 이번 패치 변경 여부 확인
import json, io, sys
sys.stdout.reconfigure(encoding="utf-8")

OLD = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset\SkillTable1.json"
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\dump_2694\SkillTable1.json"

def load(p):
    d = json.load(io.open(p, encoding="utf-8"))
    return {str(r.get("SkillIndex")): r for r in d}

old = load(OLD)
new = load(NEW)

# 나이트로드 스킬 인덱스 대역: 9xxxx (90000~94999). manual IR의 인덱스들
nl_idx = ["90015","90000","91000","92020","92040","92041","93030","93031",
          "93060","93080","94040","94041","94050","94070","94080"]
# 대역 전체 스캔: 90000~95000
band = [k for k in set(old)|set(new) if k.isdigit() and 90000 <= int(k) < 95000]

print("=== 나이트로드 대역(90000~95000) 변경 ===")
changed = []
for k in sorted(band, key=int):
    o = json.dumps(old.get(k), ensure_ascii=False, sort_keys=True)
    n = json.dumps(new.get(k), ensure_ascii=False, sort_keys=True)
    if o != n:
        name = (new.get(k) or old.get(k) or {}).get("NameKey","")
        st = "삭제" if k not in new else ("신규" if k not in old else "변경")
        changed.append((k, st, name))
for k, st, name in changed:
    print(f"  {st} {k} {name}")
print(f"나이트로드 대역 변경 {len(changed)}개")

# 전체 Hero 스킬 대역 변경 요약 (직업별 파악용): 8만대(동료), 9만대(히어로)
print("\n=== 전체 SkillTable1 변경 통계 ===")
allk = set(old)|set(new)
add = [k for k in allk if k not in old]
rem = [k for k in allk if k not in new]
chg = [k for k in allk if k in old and k in new and
       json.dumps(old[k],ensure_ascii=False,sort_keys=True)!=json.dumps(new[k],ensure_ascii=False,sort_keys=True)]
print(f"  신규 {len(add)} 삭제 {len(rem)} 변경 {len(chg)}")
