import json, sys
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\dump_2694_il2cpp"
d = json.load(open(base + r"\script.json", encoding="utf-8"))
addr2name = {}
for m in d["ScriptMethod"]:
    addr2name[m["Address"]] = m["Name"]
# Addresses may also hold metadata/string refs
for a in sys.argv[1:]:
    rva = int(a, 16)
    n = addr2name.get(rva)
    if n:
        print(hex(rva), "->", n)
    else:
        # find nearest below
        best = None
        for ad, nm in addr2name.items():
            if ad <= rva and (best is None or ad > best[0]):
                best = (ad, nm)
        if best:
            print(hex(rva), "-> (nearest -%x) " % (rva-best[0]), best[1][:120])
        else:
            print(hex(rva), "-> ???")
