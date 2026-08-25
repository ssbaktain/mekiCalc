# -*- coding: utf-8 -*-
# 나이트로드 관련 State/Effect를 이름 패턴으로 찾아 8-4 vs 8-13 변경 확인
import json, io, os, sys
sys.stdout.reconfigure(encoding="utf-8")

NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
OLD = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\bak_TextAsset_pre2694"
def load(b, n): return json.load(io.open(os.path.join(b, n), encoding="utf-8"))

PATS = ["93060","92040","92041","93080","93040","94040","94080","94070",
        "MarkOfAssassin","DarkFlare","PurgeArea","Venom","Fatal","Fake","Adrenal","WindTalisman"]

def rel(r):
    s = json.dumps(r, ensure_ascii=False)
    return any(p in s for p in PATS)

def check(fname, keyfield):
    on = {str(r.get(keyfield)): r for r in load(OLD, fname)}
    nn = {str(r.get(keyfield)): r for r in load(NEW, fname)}
    print(f"\n### {fname} (key={keyfield}) StateIndex 샘플: {[str(r.get(keyfield)) for r in list(nn.values())[:3]]}")
    keys = [k for k in set(on)|set(nn) if rel(on.get(k) or nn.get(k) or {})]
    chg = 0
    for k in sorted(keys):
        o = json.dumps(on.get(k), ensure_ascii=False, sort_keys=True)
        n = json.dumps(nn.get(k), ensure_ascii=False, sort_keys=True)
        if o != n:
            chg += 1
            st = "삭제" if k not in nn else ("신규" if k not in on else "변경")
            print(f"  [{st}] {k}")
    print(f"  관련 {len(keys)}개 중 변경 {chg}개")

check("StateTable.json", "StateName")
# SkillEffectTable 실제 키 확인
se = load(NEW, "SkillEffectTable.json")
print("\nSkillEffectTable 키:", list(se[0].keys())[:6])
