# 기존 12동료 전수 감사: 각 등급 각 스킬의 딜 성분(GetDamage) + CreateProjectile 재귀(숨은 딜) + CoolTimeMs
# 게임 OFF 상태 전용. 출력을 companions.json과 수동 대조.
# -*- coding: utf-8 -*-
import io, json, base64, struct, os, sys
sys.stdout.reconfigure(encoding='utf-8')
import pefile
from unicorn import *
from unicorn.x86_const import *
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.backends import default_backend
be = default_backend()
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))

# ---- 복호화기 (이름 매칭용) ----
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

# ---- 테이블 ----
SK = {}
for fn in os.listdir(NEW):
    if fn.startswith("SkillTable") and fn.endswith(".json"):
        for r in load(fn): SK[str(r.get("SkillIndex"))] = r
PROJ = {str(r.get("ProjectileIndex")): r for r in load("SkillProjectileTable.json")}
SUP = load("SupporterTable.json")
SUPI = {str(s.get("SupporterIndex")): s for s in SUP}

GRADE = {"Grade2": "RARE", "Grade3": "EPIC", "Grade4": "UNIQUE", "Grade5": "LEGENDARY"}

def coeff(v0):
    # Values[0]가 순수 숫자면 /10 = 계수%, 함수형이면 raw
    try:
        return float(v0) / 10.0
    except Exception:
        return v0

def as_list(x):
    if x is None: return []
    return x if isinstance(x, list) else [x]

def proj_tree(pidx, depth, parent_si):
    pr = PROJ.get(str(pidx))
    node = {"projIdx": pidx,
            "maxTarget": pr.get("MaxTargetCount") if pr else None,
            "tickMs": pr.get("TickTimeMs") if pr else None,
            "durMs": pr.get("DurationTimeMs") if pr else None,
            "maxFire": pr.get("MaxFireCount") if pr else None,
            "children": [], "childProjs": []}
    if not pr or depth >= 5: return node
    for csi in as_list(pr.get("SkillIndex")):
        if str(csi) != str(parent_si):
            node["children"].append(skill_tree(csi, depth + 1))
    for cp in as_list(pr.get("ChildProjectileIndices")):
        if str(cp) != str(pidx):
            node["childProjs"].append(proj_tree(cp, depth + 1, parent_si))
    return node

def skill_tree(si, depth=0):
    r = SK.get(str(si))
    if not r: return {"index": si, "missing": True}
    cd = r.get("CoolTimeMs")
    dmg = []
    proj = []
    for op in r.get("Operations") or []:
        if not isinstance(op, dict): continue
        t = op.get("Type", "")
        if "Damage" in t:
            vals = op.get("Values") or []
            dmg.append({"type": t, "coeff": coeff(vals[0]) if vals else None,
                        "vals": vals, "tgt": op.get("TargetType"),
                        "cond": op.get("EnableCondition"), "delay": op.get("QueueDelayMs")})
        elif t == "CreateProjectile":
            vals = op.get("Values") or []
            pidx = vals[0] if vals else None
            if pidx is not None and depth < 5:
                proj.append(proj_tree(pidx, depth, si))
    return {"index": si, "cd_ms": cd, "targetCount": r.get("TargetCount"),
            "dmg": dmg, "proj": proj}

def fmt_tree(t, indent="      "):
    lines = []
    if t.get("missing"):
        return [indent + "skill %s = MISSING" % t.get("index")]
    head = indent + "skill %s" % t["index"]
    if t.get("cd_ms") not in (None, "0", 0): head += " [cd %sms=%.3gs]" % (t["cd_ms"], int(t["cd_ms"]) / 1000)
    if t.get("targetCount"): head += " tgtCnt=%s" % t["targetCount"]
    lines.append(head)
    for d in t.get("dmg", []):
        c = d["coeff"]
        cs = ("%.1f%%" % c) if isinstance(c, (int, float)) else str(c)
        extra = ""
        if d.get("cond"): extra += " COND=%s" % d["cond"]
        if d.get("delay"): extra += " delay=%s" % d["delay"]
        if len(d["vals"]) > 1: extra += " vals=%s" % d["vals"]
        lines.append(indent + "  DMG %s coeff=%s tgt=%s%s" % (d["type"], cs, d["tgt"], extra))
    for p in t.get("proj", []):
        lines += fmt_proj(p, indent + "  ")
    return lines

def fmt_proj(p, indent):
    lines = []
    pl = indent + "PROJECTILE %s maxTgt=%s tick=%s dur=%s maxFire=%s" % (
        p["projIdx"], p["maxTarget"], p["tickMs"], p["durMs"], p["maxFire"])
    lines.append(pl)
    for c in p.get("children", []):
        lines += fmt_tree(c, indent + "    ")
    for cp in p.get("childProjs", []):
        lines += fmt_proj(cp, indent + "    ")
    return lines

# ---- 12동료 순회 ----
result = {}
print("### 기존 12동료 클라 스킬 트리 덤프 (계수/타수/쿨/CreateProjectile 재귀) ###\n")
for cls in range(1, 13):
    # SupporterIndex = {gradeStep}{class:02d}. gradeStep: RARE=1,EPIC=2,UNIQUE=3,LEG=4
    legidx = "4%02d" % cls
    s_leg = SUPI.get(legidx)
    namekey = s_leg.get("NameLocalKey") if s_leg else None
    name = decrypt(namekey) if namekey else None
    classtype = s_leg.get("ClassType") if s_leg else None
    print("=" * 70)
    print("■ class %d  이름=%s  ClassType=%s  NameKey=%s" % (cls, name, classtype, namekey))
    print("=" * 70)
    grades = {}
    for gnum, gname in [(1, "RARE"), (2, "EPIC"), (3, "UNIQUE"), (4, "LEGENDARY")]:
        sidx = "%d%02d" % (gnum, cls)
        s = SUPI.get(sidx)
        if not s: continue
        print("\n  【%s】(supporter %s)" % (gname, sidx))
        enter = s.get("EnterSkillIndex")
        acts = s.get("MainActiveSkillIndices") or []
        actlv = s.get("OpenActiveSkillLevel") or []
        pasv = s.get("MainPassiveSkillIndices") or []
        pasvlv = s.get("OpenPassiveSkillLevel") or []
        gd = {"enter": None, "active": [], "passive": []}
        if enter:
            t = skill_tree(enter); gd["enter"] = t
            print("    [소환/enter]")
            print("\n".join(fmt_tree(t)))
        for i, a in enumerate(acts):
            t = skill_tree(a); gd["active"].append(t)
            lv = actlv[i] if i < len(actlv) else "?"
            print("    [active openLv=%s]" % lv)
            print("\n".join(fmt_tree(t)))
        for i, p in enumerate(pasv):
            t = skill_tree(p); gd["passive"].append(t)
            lv = pasvlv[i] if i < len(pasvlv) else "?"
            print("    [passive openLv=%s]" % lv)
            print("\n".join(fmt_tree(t)))
        grades[gname] = gd
    result[cls] = {"name": name, "classtype": classtype, "grades": grades}
    print()

outp = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\companion_audit_raw.json"
io.open(outp, "w", encoding="utf-8").write(json.dumps(result, ensure_ascii=False, indent=1))
print("\n저장:", outp)
