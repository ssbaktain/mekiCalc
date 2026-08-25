# 신규 동료(나이트워커/윈드브레이커) 스킬 설명 평문화 — desc의 {N}을 실제 계수로 치환, 색상태그 제거
# -*- coding: utf-8 -*-
import json, io, os, re, sys
sys.stdout.reconfigure(encoding='utf-8')
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))

SK = {}
for fn in os.listdir(NEW):
    if fn.startswith("SkillTable") and fn.endswith(".json"):
        for r in load(fn): SK[str(r.get("SkillIndex"))] = r
DP = load("SkillDescParamTable.json")
PARAM = {}
for r in DP:
    PARAM.setdefault(str(r.get("SkillIndex")), {})[str(r.get("ParamNo"))] = r

def dig(node, steps):
    # steps: list of ["Field", idx?] 를 SkillTable 레코드에서 따라감
    cur = node
    i = 0
    while i < len(steps):
        st = steps[i]
        field = st[0]
        if field == "SkillTable":
            cur = SK.get(str(cur if not (len(st) > 1) else st[1]))
        elif field == "Operations":
            cur = (cur.get("Operations") or [])[int(st[1])] if cur else None
        elif field == "Values":
            cur = cur.get("Values")[int(st[1])] if cur else None
        elif field == "DurationMs":
            cur = cur.get("DurationMs") if cur else None
        else:
            cur = cur.get(field) if isinstance(cur, dict) else None
        i += 1
    return cur

def resolve(si, pno):
    p = PARAM.get(str(si), {}).get(str(pno))
    if not p: return None
    calc = p.get("Calculate")
    if calc and calc[0] == "Const":
        return calc[1]
    # Step1.. 체인. Step1은 [SkillTable, idx]
    steps = []
    for k in range(1, 9):
        s = p.get("Step%d" % k)
        if s: steps.append(s)
    if not steps: return None
    # 첫 Step1이 [SkillTable, idx]면 그 레코드부터
    cur = None
    try:
        first = steps[0]
        if first[0] == "SkillTable":
            cur = SK.get(str(first[1]))
            rest = steps[1:]
        else:
            cur = None; rest = steps
        for st in rest:
            f = st[0]
            if f == "SkillTable":
                cur = SK.get(str(cur))
            elif f == "Operations":
                cur = (cur.get("Operations") or [])[int(st[1])] if cur else None
            elif f == "Values":
                cur = cur.get("Values")[int(st[1])] if cur else None
            else:
                cur = cur.get(f) if isinstance(cur, dict) else None
        val = cur
        if calc and calc[0] == "Div":
            v = float(val) / float(calc[1])
            return int(v) if v == int(v) else round(v, 2)
        return val
    except Exception:
        return None

TAG = re.compile(r"\[#?[A-Za-z]+\]|\[-\]|\[/?[a-zA-Z]+\]")
def fill(si, desc):
    if not desc: return desc
    def rep(m):
        n = m.group(1)
        v = resolve(si, n)
        return str(v) if v is not None else m.group(0)
    out = re.sub(r"\{(\d+)\}", rep, desc)
    out = TAG.sub("", out)
    return out.replace("\\n", " ").strip()

raw = json.load(io.open(r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\companion_new_raw.json", encoding="utf-8"))
GRADES = ["RARE", "EPIC", "UNIQUE", "LEGENDARY"]
KOR = {"RARE": "레어", "EPIC": "에픽", "UNIQUE": "유니크", "LEGENDARY": "레전드리"}

def cd_str(sk):
    cd = sk.get("cd_s")
    return " (쿨 %ss)" % (int(cd) if cd and cd == int(cd) else cd) if cd else ""

for cid in ["nightWalker", "windBreaker"]:
    e = raw[cid]
    print("\n" + "=" * 60)
    print("■ %s  (%s)" % (e.get("name"), cid))
    print("=" * 60)
    for g in GRADES:
        gd = e["grades"].get(g)
        if not gd: continue
        print("\n【%s】" % KOR[g])
        ent = gd.get("enter")
        if ent:
            t = fill(ent["index"], ent.get("desc"))
            if t: print("  · [소환] %s%s" % (t, cd_str(ent)))
        for sec, tag in [("active", "액티브"), ("passive", "패시브")]:
            for sk in gd.get(sec, []) or []:
                si = sk.get("index")
                t = fill(si, sk.get("desc"))
                if not t: continue
                # 800000 만료 스킬 제외
                if str(si) == "800000": continue
                print("  · [%s] %s%s" % (tag, t, cd_str(sk)))
