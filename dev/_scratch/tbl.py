import json, sys
D = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(name):
    return json.load(open(D + "\\" + name + ".json", encoding="utf-8"))
def cols(rows):
    k=set()
    for r in rows: k.update(r.keys())
    return sorted(k)
for name in ["CreatureStatTable","CreatureLevelStatFactorTable","MonsterTierTable","MonsterTierRatioGroupTable","CreatureLevelStatTable","CreatureTable"]:
    try:
        r = load(name)
        print("===",name,"len",len(r),"===")
        print("cols:", cols(r))
        print("sample:", json.dumps(r[0], ensure_ascii=False)[:400])
        print()
    except Exception as e:
        print(name,"ERR",e)
