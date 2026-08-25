# 윈드브레이커 [정령의 기운] 등급별 정밀 비교 — 계수·생성개수·강화확률·강화배수
import io, json, base64, struct, os
import pefile
from unicorn import *
from unicorn.x86_const import *
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.backends import default_backend
be = default_backend()
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))

dllpath = r"C:\Nexon\MapleIdleRPG\GameAssembly.dll"
dll = open(dllpath, "rb").read(); pe = pefile.PE(dllpath, fast_load=True); ib = 0x180000000
sc = [x for x in pe.sections if x.VirtualAddress <= 0x6a2d980 < x.VirtualAddress + max(x.Misc_VirtualSize, x.SizeOfRawData)][0]
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
    GC = 0x1849853a0; INI = {0x1807b43c0, 0x1806fa1f0}
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
    try: u.emu_start(0x186a2d980, R2, count=300000)
    except Exception: pass
    h = u.reg_read(UC_X86_REG_RAX) & 0xffffffff; _hc[s] = h; return h
def unpad(b):
    p = b[-1] if b else 0
    return b[:-p] if 1 <= p <= 16 and b[-p:] == bytes([p]) * p else b
def decrypt(key_name):
    ct_b64 = KV.get(key_name)
    if not ct_b64: return None
    h = emu_hash(key_name); signed = h if h < 0x80000000 else h - 0x100000000
    key = ("local_enc_key_" + str(signed)).encode("ascii")[:16]
    try:
        d = Cipher(algorithms.AES(key), modes.CBC(key), be).decryptor()
        return unpad(d.update(base64.b64decode(ct_b64)) + d.finalize()).decode("utf-8")
    except Exception: return None

KV = {r.get("Key"): r.get("Text") for r in load("Localization_ko.json") if r.get("Key") and r.get("Text")}
SK = {}
for fn in os.listdir(NEW):
    if fn.startswith("SkillTable") and fn.endswith(".json"):
        for r in load(fn): SK[str(r.get("SkillIndex"))] = r

def dump(si, tag):
    r = SK.get(str(si))
    print("\n----- [%s] SkillIndex=%s -----" % (tag, si))
    if not r:
        print("  (없음)"); return
    print("  ActionName:", r.get("ActionName"), " CoolTimeMs:", r.get("CoolTimeMs"),
          " TargetCount:", r.get("TargetCount"), " MaxHitCount:", r.get("MaxHitCount"),
          " TriggerRatio:", r.get("TriggerRatio"))
    for i, op in enumerate(r.get("Operations") or []):
        if not isinstance(op, dict): continue
        print("  Op[%d] Type=%s Target=%s Values=%s ModStat=%s Delay=%s"
              % (i, op.get("Type"), op.get("TargetType"), op.get("Values"),
                 op.get("ModifyStatType"), op.get("QueueDelayMs")))
    d = decrypt("SKILL_DESC_" + str(si))
    if d: print("  desc:", d)

# enter 스킬 → 하위 정령 스킬 인덱스 추적
GRADE = [("RARE","814100"),("EPIC","814200"),("UNIQUE","814300"),("LEGENDARY","814400")]
print("======== 윈드브레이커 [정령의 기운] 등급별 ========")
for g, enter in GRADE:
    r = SK.get(enter)
    if not r:
        print("\n### %s enter=%s 없음" % (g, enter)); continue
    print("\n########## %s (enter=%s) ##########" % (g, enter))
    dump(enter, g+" enter")
    # UseSkill로 호출되는 하위 스킬
    for op in (r.get("Operations") or []):
        if isinstance(op, dict) and op.get("Type") == "UseSkill" and op.get("Values"):
            sub = op["Values"][0]
            dump(sub, g+" 정령본체")
            # 하위가 또 CreateProjectile/UseSkill 하는지
            sr = SK.get(str(sub))
            for op2 in (sr.get("Operations") or []) if sr else []:
                if isinstance(op2, dict) and op2.get("Type") in ("UseSkill","CreateProjectile") and op2.get("Values"):
                    dump(op2["Values"][0], g+" 정령하위("+op2.get("Type")+")")
