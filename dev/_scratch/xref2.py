"""Robust xref via linear capstone sweep of .text collecting call/jmp targets.
Usage: python xref2.py 0x6aa9ed0
"""
import sys, json, bisect, pefile
from capstone import Cs, CS_ARCH_X86, CS_MODE_64

DLL = r"C:\Nexon\MapleIdleRPG\GameAssembly.dll"
BASE = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\dump_2694_il2cpp"
target = int(sys.argv[1], 16)

pe = pefile.PE(DLL, fast_load=True)
text = next(s for s in pe.sections if s.Name.rstrip(b"\x00") == b".text")
sec_va, sec_off, sec_size = text.VirtualAddress, text.PointerToRawData, text.SizeOfRawData
with open(DLL, "rb") as f:
    f.seek(sec_off); code = f.read(sec_size)

methods = json.load(open(BASE + r"\script.json", encoding="utf-8"))["ScriptMethod"]
addrs = sorted(m["Address"] for m in methods)
a2n = {m["Address"]: m["Name"] for m in methods}
def enc(rva):
    i = bisect.bisect_right(addrs, rva) - 1
    return (addrs[i], a2n[addrs[i]]) if i >= 0 else (None, "?")

# Start disasm at each known method address to keep instruction alignment correct.
md = Cs(CS_ARCH_X86, CS_MODE_64)
hits = []
starts = [a for a in addrs if sec_va <= a < sec_va + sec_size]
for idx, start in enumerate(starts):
    end = starts[idx+1] if idx+1 < len(starts) else sec_va + sec_size
    off = start - sec_va
    n = end - start
    if n <= 0 or n > 0x20000:
        n = min(0x4000, sec_va + sec_size - start)
    for ins in md.disasm(code[off:off+n], start):
        if ins.mnemonic in ("call", "jmp") and ins.op_str.startswith("0x"):
            try:
                dst = int(ins.op_str, 16)
            except ValueError:
                continue
            if dst == target:
                hits.append((ins.address, ins.mnemonic, start))

print("xrefs to 0x%x: %d" % (target, len(hits)))
seen = set()
for src, kind, start in hits:
    nm = a2n.get(start, "?")
    print("  %-4s 0x%x  in %s" % (kind, src, nm[:110]))
