# companion_audit_raw.json 분석: 숨은딜(CreateProjectile 하위 DMG) 후보 + cd 있는 스킬 자동 플래그
# -*- coding: utf-8 -*-
import io, json, sys
sys.stdout.reconfigure(encoding='utf-8')
raw = json.load(io.open(r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\companion_audit_raw.json", encoding="utf-8"))
NAME = {1:"히어로",2:"팔라딘",3:"다크나이트",4:"썬콜",5:"불독",6:"비숍",7:"보우마스터",8:"신궁",9:"나이트로드",10:"섀도어",11:"바이퍼",12:"캡틴"}

def collect_proj_dmg(node, path, out):
    # node = skill_tree. 재귀하며 proj 하위 dmg를 out에 수집
    for p in node.get("proj", []):
        walk_proj(p, path + ["proj%s" % p["projIdx"]], out, p)

def walk_proj(p, path, out, projmeta):
    for c in p.get("children", []):
        for d in c.get("dmg", []):
            out.append({"path": " > ".join(path + ["skill%s" % c["index"]]),
                        "coeff": d["coeff"], "type": d["type"], "vals": d["vals"],
                        "tgt": d["tgt"], "cond": d.get("cond"),
                        "maxTgt": projmeta.get("maxTarget"), "tick": projmeta.get("tickMs"),
                        "dur": projmeta.get("durMs"), "maxFire": projmeta.get("maxFire")})
        # child skill이 또 proj를 가질 수 있음
        collect_proj_dmg(c, path + ["skill%s" % c["index"]], out)
    for cp in p.get("childProjs", []):
        walk_proj(cp, path + ["proj%s" % cp["projIdx"]], out, cp)

for cls in range(1, 13):
    e = raw[str(cls)]
    print("\n" + "=" * 66)
    print("■ %s (class %d)" % (NAME[cls], cls))
    print("=" * 66)
    for g in ["RARE", "EPIC", "UNIQUE", "LEGENDARY"]:
        gd = e["grades"].get(g)
        if not gd: continue
        rows = []
        for sec in ["enter", "active", "passive"]:
            items = gd.get(sec)
            if not items: continue
            if sec == "enter": items = [items]
            for sk in items:
                if not sk or sk.get("missing"): continue
                si = sk["index"]
                cd = sk.get("cd_ms")
                direct = [(d["coeff"], d["type"], d["vals"], d["tgt"]) for d in sk.get("dmg", [])]
                hidden = []
                collect_proj_dmg(sk, [], hidden)
                tag = []
                if cd not in (None, "0", 0): tag.append("cd=%gs" % (int(cd)/1000))
                if direct: tag.append("직접딜=%s" % [f"{c}%" if isinstance(c,(int,float)) else c for c,_,_,_ in direct])
                if hidden: tag.append("★숨은딜=%d건" % len(hidden))
                if tag:
                    print("  [%s] %s %s :: %s" % (g, sec, si, " | ".join(tag)))
                    for h in hidden:
                        cc = ("%s%%" % h["coeff"]) if isinstance(h["coeff"],(int,float)) else h["coeff"]
                        print("        ★ %s coeff=%s maxTgt=%s tick=%s dur=%s maxFire=%s cond=%s vals=%s"
                              % (h["type"], cc, h["maxTgt"], h["tick"], h["dur"], h["maxFire"], h["cond"], h["vals"]))
