# 의심 스킬들의 실제 게임 설명(desc) 복호화 + {N} 치환으로 계수 확정
# -*- coding: utf-8 -*-
import io, json, base64, struct, os, re, sys
sys.stdout.reconfigure(encoding='utf-8')
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
DP = load("SkillDescParamTable.json")
PARAM = {}
for r in DP:
    PARAM.setdefault(str(r.get("SkillIndex")), {})[str(r.get("ParamNo"))] = r

def resolve(si, pno):
    p = PARAM.get(str(si), {}).get(str(pno))
    if not p: return None
    calc = p.get("Calculate")
    if calc and calc[0] == "Const": return calc[1]
    steps = []
    for k in range(1, 9):
        s = p.get("Step%d" % k)
        if s: steps.append(s)
    if not steps: return None
    cur = None
    try:
        first = steps[0]
        if first[0] == "SkillTable":
            cur = SK.get(str(first[1])); rest = steps[1:]
        else:
            cur = None; rest = steps
        for st in rest:
            f = st[0]
            if f == "SkillTable": cur = SK.get(str(cur))
            elif f == "Operations": cur = (cur.get("Operations") or [])[int(st[1])] if cur else None
            elif f == "Values": cur = cur.get("Values")[int(st[1])] if cur else None
            else: cur = cur.get(f) if isinstance(cur, dict) else None
        val = cur
        if calc and calc[0] == "Div":
            v = float(val) / float(calc[1]); return int(v) if v == int(v) else round(v, 2)
        return val
    except Exception: return None

TAG = re.compile(r"\[#?[A-Za-z]+\]|\[-\]|\[/?[a-zA-Z]+\]")
def fill(si):
    desc = decrypt("SKILL_DESC_" + str(si))
    if not desc: return "(desc없음)"
    def rep(m):
        v = resolve(si, m.group(1))
        return str(v) if v is not None else m.group(0)
    out = re.sub(r"\{(\d+)\}", rep, desc)
    return TAG.sub("", out).replace("\\n", " ").strip()

SUSPECT = {
  "다크나이트": ["803240","803241","803350","803351","803352","803460","803461","803470"],
  "불독": ["805420","805460"],
  "보우마스터": ["807330","807430"],
  "캡틴": ["812320","812330","812420","812430"],
}
for who, sids in SUSPECT.items():
    print("\n### %s ###" % who)
    for si in sids:
        r = SK.get(si)
        cd = r.get("CoolTimeMs") if r else "?"
        print("  [%s] cd=%s :: %s" % (si, cd, fill(si)))
