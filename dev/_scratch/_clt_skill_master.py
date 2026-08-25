# Phase B: 스킬 이름·설명(컬러유지 마커)·마스터리 로컬라이징 → 마스터 데이터 통합
# 게임 원문의 [#Red]=계수 → «계수»(초록bold 마커), [#White]등=값만. {N}은 SkillDescParam으로 치환.
# 사용: python _clt_skill_master.py <CI>   (게임 OFF 필요 — dll 에뮬)
# -*- coding: utf-8 -*-
import io, json, os, re, sys
sys.stdout.reconfigure(encoding='utf-8')
from _clt_decrypt import decrypt, kv   # SKILL_NAME/DESC 복호화(main가드됨)
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))

SK = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in load(fn): SK[str(r.get('SkillIndex'))] = r
PROJ = {str(r.get("ProjectileIndex")): r for r in load("SkillProjectileTable.json")}
STATE = {str(r.get("StateName")): r for r in load("StateTable.json")}
PARAM = {}
for r in load("SkillDescParamTable.json"):
    PARAM.setdefault(str(r.get("SkillIndex")), {})[str(r.get("ParamNo"))] = r

def resolve(si, pno):
    p = PARAM.get(str(si), {}).get(str(pno))
    if not p: return None
    calc = p.get("Calculate")
    if calc and calc[0] == "Const":
        v = float(calc[1])
        if "Div" in calc: v = v / float(calc[calc.index("Div") + 1])
        return int(v) if v == int(v) else round(v, 2)
    steps = [p["Step%d" % k] for k in range(1, 20) if p.get("Step%d" % k)]
    if not steps: return None
    cur = None
    try:
        for i, st in enumerate(steps):
            f = st[0]; idx = st[1] if len(st) > 1 else None
            if f == "SkillTable":
                key = idx if (i == 0 and idx is not None) else (cur[0] if isinstance(cur, list) else cur)
                cur = SK.get(str(key))
            elif f == "SkillProjectileTable":
                key = idx if (i == 0 and idx is not None) else (cur[0] if isinstance(cur, list) else cur)
                cur = PROJ.get(str(key))
            elif f == "StateTable":
                key = idx if (i == 0 and idx is not None) else (cur[0] if isinstance(cur, list) else cur)
                cur = STATE.get(str(key))
            elif f == "Operations":
                cur = (cur.get("Operations") or [])[int(idx)] if cur else None
            elif f == "Values":
                v = cur.get("Values") if isinstance(cur, dict) else None
                cur = v[int(idx)] if v else None
            else:
                val = cur.get(f) if isinstance(cur, dict) else None
                if isinstance(val, list):
                    val = val[int(idx)] if idx is not None else (val[0] if val else None)
                cur = val
        val = cur
        if calc and calc[0] == "Div":
            v = float(val) / float(calc[1]); return int(v) if v == int(v) else round(v, 2)
        return val
    except Exception: return None

def fill_colored(desckey, si):
    """SKILL_DESC 복호화 → {N} 치환 → [#Red]X[-]=«X»(계수마커), 다른 컬러=값만, 잔여태그 제거."""
    raw = kv.get(desckey)
    if not raw: return None
    desc = decrypt(desckey, raw)
    if not desc: return None
    def rep(m):
        v = resolve(si, m.group(1))
        return str(v) if v is not None else m.group(0)
    out = re.sub(r"\{(\d+)\}", rep, desc)
    out = re.sub(r"\[#Red\](.+?)\[-\]", r"«\1»", out)      # 계수 = «값»(초록bold 마커)
    out = re.sub(r"\[#\w+\](.+?)\[-\]", r"\1", out)          # 다른 컬러 = 값만
    out = re.sub(r"\[/?[#\w-]*\]", "", out)                   # 잔여 태그 제거
    return out.replace("\\n", " ").strip()

def name_of(si):
    k = "SKILL_NAME_" + str(si)
    return decrypt(k, kv[k]) if k in kv else None

def process(ci):
    master = json.load(io.open(f'skill_master/ci{ci}.json', encoding='utf-8'))
    for s in master['skills']:
        si = s['index']
        s['name'] = name_of(si)
        s['desc'] = fill_colored("SKILL_DESC_" + si, si)
        for m in s.get('masteries', []):
            msi = m['msi']
            m['name'] = name_of(msi)
            m['desc'] = fill_colored("SKILL_DESC_" + msi, msi)
    io.open(f'skill_master/ci{ci}_full.json', 'w', encoding='utf-8').write(
        json.dumps(master, ensure_ascii=False, indent=1))
    return len(master['skills'])

def main():
    cis = [sys.argv[1]] if len(sys.argv) > 1 else [str(i) for i in range(1, 15)]
    for ci in cis:
        try:
            n = process(ci)
            print(f"CI{ci} 완료: {n}스킬", flush=True)
        except Exception as e:
            print(f"CI{ci} 실패: {e}", flush=True)

main()
