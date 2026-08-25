import json, math
D = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
factor = json.load(open(D+r"\CreatureLevelStatFactorTable.json",encoding="utf-8"))
# MaxHp factor = Factor[1], at Step 0 (assume step 0 for base level rows)
F1 = {}
for r in factor:
    lv=int(r["Level"]); stp=int(r["Step"])
    if stp==0:
        F1[lv]=int(r["Factor"][1])
maxlv=max(F1)
# extrapolate beyond 120 by last-step geometric ratio
last_ratio = F1[maxlv]/F1[maxlv-1]
def Fac(level):
    if level<1: level=1
    if level<=maxlv: return F1[level]
    # geometric continuation
    return F1[maxlv]*(last_ratio**(level-maxlv))

# actual HP anchors grouped by chapter
anchors = {
 25:[(1,1052120055147),(7,1719249650227),(9,2025296609724)],
 33:[(1,438790632553173),(9,681232556211098),(10,719770626507348),(14,897077785522617)],
}

# Fit: HP(st) = C * Fac(L0 + k*(st-1)).  For a chapter, find integer L0 and slope k
# such that ratio HP(st2)/HP(st1) = Fac(L0+k*(st2-1))/Fac(L0+k*(st1-1)).
# Grid search L0 in [1..600], k in [1..30]
for ch, pts in anchors.items():
    best=None
    s0,h0 = pts[0]
    for L0 in range(1,700):
        for k in range(1,40):
            # predict all points relative to first
            err=0
            for (st,hp) in pts:
                lvl = L0 + k*(st-s0)
                pred_ratio = Fac(lvl)/Fac(L0)
                act_ratio = hp/h0
                err += (pred_ratio/act_ratio - 1)**2
            if best is None or err<best[0]:
                best=(err,L0,k)
    err,L0,k=best
    print("ch%d best L0=%d k=%d rms=%.4f%%"%(ch,L0,k,100*math.sqrt(err/len(pts))))
    for (st,hp) in pts:
        lvl=L0+k*(st-s0)
        pr=Fac(lvl)/Fac(L0)*h0
        print("   st%2d lvl=%d pred=%.4g act=%d err=%.3f%%"%(st,lvl,pr,hp,100*(pr/hp-1)))
