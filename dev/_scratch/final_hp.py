import math
# 챕터별 실측 2점으로 등비수열 HP(c,st)=A*r^(st-1) fit 후 전 앵커 재현
anchors_by_ch = {
 1:[(1,973)],
 5:[(1,621389)],
 15:[(1,1601181392)],
 25:[(1,1052120055147),(7,1719249650227),(9,2025296609724)],
 32:[(14,409865309706945)],
 33:[(1,438790632553173),(9,681232556211098),(10,719770626507348),(14,897077785522617)],
 35:[(1,2102783400411374)],
}
def fit(pts):
    if len(pts)==1:
        return None
    (s1,h1),(s2,h2)=pts[0],pts[-1]
    r=(h2/h1)**(1.0/(s2-s1))
    A=h1/r**(s1-1)
    return A,r

print("%-6s %-16s %-16s %-9s"%("stage","predicted","actual","err%"))
for ch,pts in anchors_by_ch.items():
    f=fit(pts)
    if f is None:
        print("ch%d: only 1 anchor (need 2 for closed form) -> %s"%(ch,pts))
        continue
    A,r=f
    for (st,hp) in pts:
        pred=A*r**(st-1)
        print("%2d-%-2d %-16.10g %-16d %+.4f%%"%(ch,st,pred,hp,100*(pred/hp-1)))
    print("   -> A=%.6g r=%.6f"%(A,r))
