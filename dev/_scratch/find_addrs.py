import json, sys
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\dump_2694_il2cpp"
d = json.load(open(base + r"\script.json", encoding="utf-8"))
sm = d["ScriptMethod"]
needles = sys.argv[1:] if len(sys.argv) > 1 else [
    "ApplyDungeonEnemyInfo", "CreateEnemyUserInfo",
    "CalculateCreatureBaseStatInternal"]
for m in sm:
    n = m["Name"]
    if any(x in n for x in needles):
        print(hex(m["Address"]), "|", n[:140])
