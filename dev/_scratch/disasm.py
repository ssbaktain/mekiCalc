"""Disassemble a function at a given RVA in GameAssembly.dll (x86-64).
Usage: python disasm.py 0x6aaa830 [num_bytes]
RVA is the Il2CppDumper 'RVA' == offset from image base (matches script.json Address).
"""
import sys, pefile
from capstone import Cs, CS_ARCH_X86, CS_MODE_64

DLL = r"C:\Nexon\MapleIdleRPG\GameAssembly.dll"

pe = pefile.PE(DLL, fast_load=True)
image_base = pe.OPTIONAL_HEADER.ImageBase

def rva_to_off(rva):
    for s in pe.sections:
        if s.VirtualAddress <= rva < s.VirtualAddress + max(s.Misc_VirtualSize, s.SizeOfRawData):
            return s.PointerToRawData + (rva - s.VirtualAddress)
    raise ValueError("RVA not in any section: %x" % rva)

with open(DLL, "rb") as f:
    data = f.read()

def disasm(rva, n=0x600, show=True):
    off = rva_to_off(rva)
    code = data[off:off+n]
    md = Cs(CS_ARCH_X86, CS_MODE_64)
    md.detail = True
    out = []
    for ins in md.disasm(code, rva):
        line = "0x%x:\t%s\t%s" % (ins.address, ins.mnemonic, ins.op_str)
        out.append((ins, line))
        if show:
            print(line)
        # stop at ret if we've gone past a bit? no, keep going to n
    return out

if __name__ == "__main__":
    rva = int(sys.argv[1], 16)
    n = int(sys.argv[2], 0) if len(sys.argv) > 2 else 0x600
    disasm(rva, n)
