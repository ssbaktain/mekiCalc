# 직업 스킬 raw 덤프: CI별 액티브/패시브 계수·타수·쿨·성장Factor·CreateProjectile 재귀 + 마스터리 효과
# 사용: python _clt_job_dump.py <CI>   (없으면 9=나이트로드)
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))

SK = {}
for fn in os.listdir(NEW):
    if fn.startswith("SkillTable") and fn.endswith(".json"):
        for r in load(fn): SK[str(r.get("SkillIndex"))] = r
PROJ = {str(r.get("ProjectileIndex")): r for r in load("SkillProjectileTable.json")}
HS = load("HeroSkillTable.json")
HM = load("HeroSkillMasteryTable.json")
LF = {str(r["Level"]): r["Factor"] for r in load("SkillLevelFactorTable.json")}

CI = sys.argv[1] if len(sys.argv) > 1 else "9"
NAME = {"1":"히어로","2":"팔라딘","3":"다크나이트","4":"썬콜","5":"불독","6":"비숍","7":"보우마스터","8":"신궁","9":"나이트로드","10":"섀도어","11":"바이퍼","12":"캐논","13":"나이트워커","14":"윈드브레이커"}

def as_list(x):
    if x is None: return []
    return x if isinstance(x, list) else [x]

def coeff(v0):
    try: return float(v0)/10.0
    except Exception: return v0

def factor_growth(col):
    # col = ValueLevelFactors 마지막 컬럼번호. Lv50 factor로 레벨당 성장률 추정
    try:
        c = int(col)
        f1 = float(LF["1"][c]); f50 = float(LF["50"][c])
        # 1000=1.0. 레벨당 = (f50/1000 - 1)/49
        per = (f50/1000.0 - 1.0)/49.0*100
        return round(per, 3)
    except Exception: return None

def dump_ops(r, indent):
    lines = []
    for op in r.get("Operations") or []:
        if not isinstance(op, dict): continue
        t = op.get("Type","")
        vals = op.get("Values") or []
        vlf = op.get("ValueLevelFactors")
        extra = ""
        if "Damage" in t:
            c = coeff(vals[0]) if vals else None
            cs = ("%s%%"%c) if isinstance(c,(int,float)) else str(c)
            g = ""
            if vlf: g = " growthCol=%s(~%s%%/lv)" % (vlf[-1], factor_growth(vlf[-1]))
            extra = "coeff=%s tgt=%s vals=%s%s" % (cs, op.get("TargetType"), vals, g)
            if op.get("EnableCondition"): extra += " COND=%s" % op.get("EnableCondition")
            if op.get("Flag"): extra += " Flag=%s" % op.get("Flag")
            lines.append(indent + "DMG %s %s" % (t, extra))
        elif t == "CreateProjectile":
            pidx = vals[0] if vals else None
            pr = PROJ.get(str(pidx))
            lines.append(indent + "CreateProjectile proj=%s maxTgt=%s tick=%s dur=%s -> SkillIndex=%s" % (
                pidx, pr.get("MaxTargetCount") if pr else None, pr.get("TickTimeMs") if pr else None,
                pr.get("DurationTimeMs") if pr else None, pr.get("SkillIndex") if pr else None))
            if pr:
                for csi in as_list(pr.get("SkillIndex")):
                    cr = SK.get(str(csi))
                    if cr and str(csi) != str(r.get("SkillIndex")):
                        lines.append(indent + "  └ child skill %s (%s):" % (csi, cr.get("ActionName")))
                        lines += dump_ops(cr, indent + "     ")
        elif t in ("UseSkillToTarget","UseSkill"):
            lines.append(indent + "%s -> %s" % (t, vals))
            for csi in vals:
                cr = SK.get(str(csi))
                if cr and str(csi) != str(r.get("SkillIndex")):
                    lines.append(indent + "  └ skill %s (%s):" % (csi, cr.get("ActionName")))
                    lines += dump_ops(cr, indent + "     ")
        elif t.startswith("ModStat") or t.startswith("ModSkill") or "Buff" in t or "Stat" in t:
            lines.append(indent + "%s tgt=%s vals=%s" % (t, op.get("TargetType"), vals))
    return lines

print("=" * 74)
print("■ %s (CI %s) — 액티브/패시브 스킬" % (NAME.get(CI), CI))
print("=" * 74)
skills = [r for r in HS if str(r.get("CreatureIndex")) == CI]
for r in skills:
    si = str(r.get("SkillIndex"))
    sk = SK.get(si)
    if not sk:
        print("\n[%s] %s SkillIndex=%s (SkillTable 없음)" % (r.get("HeroSkillType"), r.get("JobStep"), si)); continue
    cd = sk.get("CoolTimeMs")
    cdstr = " cd=%gs"%(int(cd)/1000) if cd not in (None,"0",0) else ""
    print("\n[%s J%s] %s :: %s%s tgtCnt=%s" % (
        r.get("HeroSkillType"), r.get("JobStep"), si, sk.get("ActionName"), cdstr, sk.get("TargetCount")))
    for l in dump_ops(sk, "    "): print(l)

print("\n" + "=" * 74)
print("■ %s (CI %s) — 마스터리 58개" % (NAME.get(CI), CI))
print("=" * 74)
masteries = [r for r in HM if str(r.get("CreatureIndex")) == CI]
for m in masteries:
    mi = str(m.get("MasteryIndex"))
    parent = m.get("ParentSkillIndex")
    lv = m.get("RequireCreatureLevel")
    msk = SK.get(mi)
    ops = []
    if msk:
        for op in msk.get("Operations") or []:
            if isinstance(op, dict):
                ops.append("%s%s" % (op.get("Type"), op.get("Values") or ""))
    print("  M%s lv%s parent=%s main=%s :: %s" % (mi, lv, parent, m.get("IsMainNode"), " | ".join(ops) if ops else "(효과 op없음)"))
