"""Find all direct CALL (E8) and JMP (E9) sites that target a given RVA.
Usage: python xref.py 0x6aa9ed0
Prints caller RVA and, if resolvable, the enclosing method name from script.json.
"""
import sys, json, struct, pefile

DLL = r"C:\Nexon\MapleIdleRPG\GameAssembly.dll"
BASE = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\dump_2694_il2cpp"

pe = pefile.PE(DLL, fast_load=True)
text = None
for s in pe.sections:
    nm = s.Name.rstrip(b"\x00")
    if nm == b"il2cpp":   # IL2CPP code lives here, not .text
        text = s
        break
sec_va = text.VirtualAddress
sec_off = text.PointerToRawData
sec_size = text.SizeOfRawData
with open(DLL, "rb") as f:
    f.seek(sec_off)
    code = f.read(sec_size)

# method boundaries for naming
methods = json.load(open(BASE + r"\script.json", encoding="utf-8"))["ScriptMethod"]
addrs = sorted(m["Address"] for m in methods)
addr2name = {m["Address"]: m["Name"] for m in methods}
import bisect
def enclosing(rva):
    i = bisect.bisect_right(addrs, rva) - 1
    if i >= 0:
        a = addrs[i]
        return a, addr2name[a]
    return None, None

def find_xrefs(target):
    hits = []
    for op, oplen in ((0xE8, 5), (0xE9, 5)):
        i = 0
        n = len(code)
        while True:
            j = code.find(bytes([op]), i)
            if j < 0 or j + 5 > n:
                break
            rel = struct.unpack_from("<i", code, j + 1)[0]
            src_rva = sec_va + j
            dst = src_rva + 5 + rel
            if dst == target:
                hits.append((src_rva, "call" if op == 0xE8 else "jmp"))
            i = j + 1
    return hits

if __name__ == "__main__":
    target = int(sys.argv[1], 16)
    hits = find_xrefs(target)
    print("xrefs to %x: %d" % (target, len(hits)))
    seen = set()
    for src, kind in hits:
        a, nm = enclosing(src)
        key = a
        print("  %-6s from 0x%x  in [+0x%x] %s" % (kind, src, src - (a or 0), (nm or "?")[:110]))
