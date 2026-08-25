# 로컬라이징 복호화기 — 신규 동료(나이트워커/윈드브레이커) 이름·스킬 설명 추출
import io, json, base64, struct, os
import pefile
from unicorn import *
from unicorn.x86_const import *
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.backends import default_backend
be = default_backend()
dllpath = r"C:\Nexon\MapleIdleRPG\GameAssembly.dll"
dll = open(dllpath, "rb").read(); pe = pefile.PE(dllpath, fast_load=True); ib = 0x180000000
sc = [x for x in pe.sections if x.VirtualAddress <= 0x6bc1e30 < x.VirtualAddress + max(x.Misc_VirtualSize, x.SizeOfRawData)][0]
sva = ib + sc.VirtualAddress; scode = dll[sc.PointerToRawData:sc.PointerToRawData + sc.SizeOfRawData]
_hc = {}
def emu_hash(s):
    if s in _hc: return _hc[s]
    u = Uc(UC_ARCH_X86, UC_MODE_64)
    cb = sva & ~0xFFF; ce = (sva + len(scode) + 0xFFF) & ~0xFFF
    u.mem_map(cb, ce - cb); u.mem_write(sva, scode)
    u.mem_map(0x200000000, 0x100000); u.reg_write(UC_X86_REG_RSP, 0x200080000)
    STR = 0x300000000; u.mem_map(STR, 0x40000)
    u.mem_write(STR + 0x10, struct.pack("<i", len(s))); u.mem_write(STR + 0x14, s.encode("utf-16-le"))
    u.reg_write(UC_X86_REG_RCX, STR)
    R2 = 0x4141410000; u.mem_write(0x200080000, struct.pack("<Q", R2))
    GC = 0x184a6b940; INI = {0x1807cdbb0, 0x1807139e0}
    def hcb(u, a, sz, _):
        if a == GC:
            rcx = u.reg_read(UC_X86_REG_RCX); rdx = u.reg_read(UC_X86_REG_RDX)
            ch = struct.unpack("<H", u.mem_read(rcx + 0x14 + rdx * 2, 2))[0]; u.reg_write(UC_X86_REG_RAX, ch)
            rsp = u.reg_read(UC_X86_REG_RSP); r = struct.unpack("<Q", u.mem_read(rsp, 8))[0]
            u.reg_write(UC_X86_REG_RSP, rsp + 8); u.reg_write(UC_X86_REG_RIP, r)
        elif a in INI:
            rsp = u.reg_read(UC_X86_REG_RSP); r = struct.unpack("<Q", u.mem_read(rsp, 8))[0]
            u.reg_write(UC_X86_REG_RSP, rsp + 8); u.reg_write(UC_X86_REG_RIP, r)
    def hmb(u, ac, a, sz, v, _):
        try: u.mem_map(a & ~0xFFF, 0x1000)
        except Exception: pass
        return True
    u.hook_add(UC_HOOK_CODE, hcb); u.hook_add(UC_HOOK_MEM_READ_UNMAPPED | UC_HOOK_MEM_WRITE_UNMAPPED | UC_HOOK_MEM_FETCH_UNMAPPED, hmb)
    try: u.emu_start(0x186bc1e30, R2, count=300000)
    except Exception: pass
    h = u.reg_read(UC_X86_REG_RAX) & 0xffffffff; _hc[s] = h; return h

def unpad(b):
    p = b[-1] if b else 0
    return b[:-p] if 1 <= p <= 16 and b[-p:] == bytes([p]) * p else b
def decrypt(key_name, ct_b64):
    h = emu_hash(key_name); signed = h if h < 0x80000000 else h - 0x100000000
    key = ("local_enc_key_" + str(signed)).encode("ascii")[:16]
    ct = base64.b64decode(ct_b64)
    d = Cipher(algorithms.AES(key), modes.CBC(key), be).decryptor()
    try: return unpad(d.update(ct) + d.finalize()).decode("utf-8")
    except Exception: return None

locp = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset\Localization_ko.json"
kv = {r.get("Key"): r.get("Text") for r in json.load(io.open(locp, encoding="utf-8")) if r.get("Key") and r.get("Text")}

# import 시엔 kv/decrypt만 제공. 직접 실행 시 argv 키 복호화(모듈 재사용 가능).
if __name__ == "__main__":
    import sys
    for key in sys.argv[1:]:
        print(key, "=", repr(decrypt(key, kv.get(key))))
