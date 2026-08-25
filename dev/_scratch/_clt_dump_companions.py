# 신규 동료(나이트워커/윈드브레이커) 전 등급 계수+스킬설명 덤프 → textAsset/companion_new_raw.json
import io, json, base64, struct, os
import pefile
from unicorn import *
from unicorn.x86_const import *
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.backends import default_backend
be = default_backend()
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))

# ---- 복호화기 ----
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

# ---- SkillTable / SupporterTable ----
SK = {}
for fn in os.listdir(NEW):
    if fn.startswith("SkillTable") and fn.endswith(".json"):
        for r in load(fn): SK[str(r.get("SkillIndex"))] = r
SUP = {str(r["SupporterIndex"]): r for r in load("SupporterTable.json")}

def parse_skill(si):
    r = SK.get(str(si))
    out = {"index": si, "action": (r or {}).get("ActionName"), "desc": decrypt("SKILL_DESC_" + str(si))}
    if not r: return out
    cd = r.get("CoolTimeMs")
    try: out["cd_s"] = (int(cd) / 1000) if cd and str(cd) != "0" else None
    except Exception: out["cd_s"] = None
    out["targetCount"] = r.get("TargetCount")
    hits = []
    for op in (r.get("Operations") or []):
        if isinstance(op, dict):
            hits.append(op)
    out["ops"] = hits
    return out

GRADE = {"Grade2": "RARE", "Grade3": "EPIC", "Grade4": "UNIQUE", "Grade5": "LEGENDARY"}
result = {}
for cls, idxs in (("nightWalker", ["113","213","313","413"]), ("windBreaker", ["114","214","314","414"])):
    entry = {"name": decrypt("NAME_SUPPORTER_%s_1" % ("13" if cls == "nightWalker" else "14")), "grades": {}}
    for six in idxs:
        s = SUP[six]; g = GRADE.get(s["GradeType"], s["GradeType"])
        gd = {"supporterIndex": int(six), "name": decrypt("NAME_SUPPORTER_" + s["SupporterCode"].split("_")[1] + "_" + s["SupporterCode"].split("_")[2]) if False else None,
              "enter": parse_skill(s.get("EnterSkillIndex")) if s.get("EnterSkillIndex") else None,
              "active": [parse_skill(x) for x in (s.get("MainActiveSkillIndices") or [])],
              "activeOpenLv": s.get("OpenActiveSkillLevel"),
              "passive": [parse_skill(x) for x in (s.get("MainPassiveSkillIndices") or [])],
              "passiveOpenLv": s.get("OpenPassiveSkillLevel"),
              "linkedAttackLevelFactor": s.get("LinkedAttackStatLevelFactor"),
              "compareLevel": s.get("LinkedAttackStatCompareLevel")}
        entry["grades"][g] = gd
    result[cls] = entry

outp = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\companion_new_raw.json"
io.open(outp, "w", encoding="utf-8").write(json.dumps(result, ensure_ascii=False, indent=2))
print("저장:", outp)
# 요약 출력
for cls, e in result.items():
    print("\n==", cls, e["name"], "==")
    leg = e["grades"].get("LEGENDARY", {})
    for sk in leg.get("active", []):
        print("  [active %s] %s :: %s" % (sk["index"], sk.get("action"), (sk.get("desc") or "")[:70]))
