import json
D = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(open(D+"\\"+n+".json",encoding="utf-8"))

dungeon = load("DungeonTable")
camp1   = load("CampFamilyTable1")
cstat   = load("CreatureStatTable")
tier    = load("MonsterTierTable")
factor  = load("CreatureLevelStatFactorTable")

DUN = {}
for r in dungeon:
    DUN[(r.get("ChapterIndex"), r.get("DungeonIndex"))] = r
FAM = {str(r["FamilyIndex"]): r for r in camp1}
CS  = {str(r["CreatureIndex"]): r["Stat"] for r in cstat}
TIER= {str(r["TierIndex"]): r["StatRatio"] for r in tier}
F   = {}
for r in factor: F[(int(r["Level"]),int(r["Step"]))] = [int(x) for x in r["Factor"]]

def normal_monster(ch, st):
    """Return (creatureIndex, tierIndex, baseMaxHp, tierHpRatio) for the normal mob
    at chapter ch stage st (1-based)."""
    r = DUN.get((str(ch), str(st)))
    if not r: return None
    ofi = r.get("OverrideFamilyIndex")
    if not ofi: return None
    # pick a representative non-null family (skip index0 which is often null)
    fam = None
    for f in ofi:
        if f: fam = str(f); break
    # more robust: take the most common family among slots
    from collections import Counter
    c = Counter(str(f) for f in ofi if f)
    fam = c.most_common(1)[0][0]
    famrow = FAM.get(fam)
    if not famrow: return None
    # first non-boss monster
    for m in famrow["Monster"]:
        if str(m.get("IsBoss")) != "True":
            ci = str(m["CreatureIndex"]); ti = str(m["TierIndex"])
            base = int(CS[ci]["MaxHp"])
            thr  = int(TIER[ti]["MaxHp"])
            return dict(fam=fam, ci=ci, ti=ti, base=base, tierHp=thr)
    return None

if __name__ == "__main__":
    anchors = {
        (1,1):973,(5,1):621389,(15,1):1601181392,
        (25,1):1052120055147,(25,7):1719249650227,(25,9):2025296609724,
        (32,14):409865309706945,
        (33,1):438790632553173,(33,9):681232556211098,(33,10):719770626507348,(33,14):897077785522617,
        (35,1):2102783400411374,
    }
    for (ch,st),hp in anchors.items():
        info = normal_monster(ch,st)
        print("%2d-%-2d hp=%d  %s" % (ch,st,hp,info))
