# -*- coding: utf-8 -*-
# 8-13 재덤프에서 해시/get_Chars 새 RVA 획득 + 해시함수 디스어셈블로 call 타겟(INI 후보) 추출
import json, io, sys
import pefile
from capstone import Cs, CS_ARCH_X86, CS_MODE_64
sys.stdout.reconfigure(encoding="utf-8")

SC = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\dump_2694_il2cpp\script.json"
d = json.load(io.open(SC, encoding="utf-8"))
byname = {}
for x in d["ScriptMethod"]:
    byname.setdefault(x["Name"], x["Address"])

HASH = "C1Shared.StringExtension$$GetPersistentHashCodeExt"
CHARS = "System.String$$get_Chars"
hash_rva = byname.get(HASH)
chars_rva = byname.get(CHARS)
print("GetPersistentHashCodeExt RVA:", hex(hash_rva) if hash_rva else None)
print("get_Chars RVA:", hex(chars_rva) if chars_rva else None)

# 해시 함수 디스어셈블 → call 타겟
dllpath = r"C:\Nexon\MapleIdleRPG\GameAssembly.dll"
dll = open(dllpath, "rb").read()
pe = pefile.PE(dllpath, fast_load=True)
ib = 0x180000000

def rva_to_off(rva):
    for s in pe.sections:
        if s.VirtualAddress <= rva < s.VirtualAddress + max(s.Misc_VirtualSize, s.SizeOfRawData):
            return s.PointerToRawData + (rva - s.VirtualAddress)
    return None

off = rva_to_off(hash_rva)
code = dll[off:off+0x400]
md = Cs(CS_ARCH_X86, CS_MODE_64)
va = ib + hash_rva
calls = []
end = None
for ins in md.disasm(code, va):
    if ins.mnemonic == "call":
        op = ins.op_str
        if op.startswith("0x"):
            calls.append(int(op, 16))
    if ins.mnemonic == "ret":
        end = ins.address
        break
print("함수 끝(ret) VA:", hex(end) if end else "?")
print("call 타겟(VA):", [hex(c) for c in calls])
# get_Chars VA
chars_va = ib + chars_rva
print("get_Chars VA:", hex(chars_va))
# INI 후보 = get_Chars 아닌 call
ini = [c for c in calls if c != chars_va]
print("INI 후보(VA):", [hex(c) for c in ini])
print("INI 후보(RVA):", [hex(c-ib) for c in ini])
