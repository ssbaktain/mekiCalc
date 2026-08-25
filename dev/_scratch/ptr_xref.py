"""Find 8-byte absolute-pointer references to a function VA anywhere in the file,
and 32-bit rip-relative LEA refs in .text. Reports enclosing method for text refs."""
import sys, json, struct, pefile, bisect

DLL = r"C:\Nexon\MapleIdleRPG\GameAssembly.dll"
BASE = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\dump_2694_il2cpp"

pe = pefile.PE(DLL, fast_load=True)
image_base = pe.OPTIONAL_HEADER.ImageBase
with open(DLL, "rb") as f:
    data = f.read()

text = next(s for s in pe.sections if s.Name.rstrip(b"\x00") == b".text")
sec_va, sec_off, sec_size = text.VirtualAddress, text.PointerToRawData, text.SizeOfRawData
tcode = data[sec_off:sec_off+sec_size]

methods = json.load(open(BASE + r"\script.json", encoding="utf-8"))["ScriptMethod"]
addrs = sorted(m["Address"] for m in methods)
addr2name = {m["Address"]: m["Name"] for m in methods}
def enclosing(rva):
    i = bisect.bisect_right(addrs, rva) - 1
    if i >= 0:
        return addrs[i], addr2name[addrs[i]]
    return None, None

def off_to_rva(off):
    for s in pe.sections:
        if s.PointerToRawData <= off < s.PointerToRawData + s.SizeOfRawData:
            return s.VirtualAddress + (off - s.PointerToRawData)
    return None

target_rva = int(sys.argv[1], 16)
target_va = image_base + target_rva

# 1) absolute 8-byte pointer scan (whole file)
needle = struct.pack("<Q", target_va)
print("=== absolute VA 0x%x pointers ===" % target_va)
i = 0
cnt = 0
while True:
    j = data.find(needle, i)
    if j < 0: break
    rva = off_to_rva(j)
    print("  file_off 0x%x  rva 0x%s" % (j, ("%x" % rva) if rva else "?"))
    i = j + 1
    cnt += 1
    if cnt > 40: break
if cnt == 0:
    print("  (none)")

# 2) rip-relative LEA/CALL in .text (E8 already covered by xref.py; do LEA 48 8D / 4C 8D etc via scanning displacement)
print("=== rip-relative refs in .text ===")
cnt = 0
n = len(tcode)
i = 0
# scan for any 4-byte disp that resolves to target: check instructions is expensive; brute check each position where next-inst boundary unknown.
# Simple heuristic: find all positions p where sec_va+p+? ; we scan for 'lea rXx,[rip+disp]' encodings 48/4C 8D XX disp32
while i < n - 7:
    b0 = tcode[i]
    if b0 in (0x48, 0x4C, 0x49, 0x4D):
        if tcode[i+1] == 0x8D:
            modrm = tcode[i+2]
            if (modrm & 0xC7) == 0x05:  # [rip+disp32]
                disp = struct.unpack_from("<i", tcode, i+3)[0]
                nextrva = sec_va + i + 7 + disp
                if nextrva == target_rva:
                    src = sec_va + i
                    a, nm = enclosing(src)
                    print("  lea 0x%x in %s" % (src, (nm or "?")[:100]))
                    cnt += 1
    i += 1
if cnt == 0:
    print("  (none)")
