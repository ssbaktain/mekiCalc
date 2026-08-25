# 직업 스킬 → skill.txt 양식 출력: SKILL_DESC 복호화 + {N} 계수 치환 + 쿨/오픈렙 + 마스터리
# 사용: python _clt_job_skilltext.py <CI>  (검증: 9=나이트로드로 기존 skill.txt와 대조)
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

# ---- 테이블 ----
SK = {}
for fn in os.listdir(NEW):
    if fn.startswith("SkillTable") and fn.endswith(".json"):
        for r in load(fn): SK[str(r.get("SkillIndex"))] = r
HS = load("HeroSkillTable.json")
HM = load("HeroSkillMasteryTable.json")
DP = load("SkillDescParamTable.json")
PROJ = {str(r.get("ProjectileIndex")): r for r in load("SkillProjectileTable.json")}
STATE = {str(r.get("StateName")): r for r in load("StateTable.json")}
PARAM = {}
for r in DP:
    PARAM.setdefault(str(r.get("SkillIndex")), {})[str(r.get("ParamNo"))] = r

def resolve(si, pno):
    p = PARAM.get(str(si), {}).get(str(pno))
    if not p: return None
    calc = p.get("Calculate")
    if calc and calc[0] == "Const":
        v = float(calc[1])
        if "Div" in calc: v = v / float(calc[calc.index("Div") + 1])   # 함수형(#FlashSlash 등) Const+Div: 실계수 = Const÷Div
        return int(v) if v == int(v) else round(v, 2)
    steps = [p["Step%d" % k] for k in range(1, 20) if p.get("Step%d" % k)]
    if not steps: return None
    cur = None
    try:
        for i, st in enumerate(steps):
            f = st[0]; idx = st[1] if len(st) > 1 else None
            if f == "SkillTable":
                key = idx if (i == 0 and idx is not None) else (cur[0] if isinstance(cur, list) else cur)
                cur = SK.get(str(key))
            elif f == "SkillProjectileTable":
                key = idx if (i == 0 and idx is not None) else (cur[0] if isinstance(cur, list) else cur)
                cur = PROJ.get(str(key))
            elif f == "StateTable":
                key = idx if (i == 0 and idx is not None) else (cur[0] if isinstance(cur, list) else cur)
                cur = STATE.get(str(key))
            elif f == "Operations":
                cur = (cur.get("Operations") or [])[int(idx)] if cur else None
            elif f == "Values":
                v = cur.get("Values") if isinstance(cur, dict) else None
                cur = v[int(idx)] if v else None
            else:
                val = cur.get(f) if isinstance(cur, dict) else None
                if isinstance(val, list):
                    val = val[int(idx)] if idx is not None else (val[0] if val else None)
                cur = val
        val = cur
        if calc and calc[0] == "Div":
            v = float(val) / float(calc[1]); return int(v) if v == int(v) else round(v, 2)
        return val
    except Exception: return None

TAG = re.compile(r"\[#?[A-Za-z]+\]|\[-\]|\[/?[a-zA-Z]+\]")
def fill(si):
    desc = decrypt("SKILL_DESC_" + str(si))
    if not desc: return None
    def rep(m):
        v = resolve(si, m.group(1))
        return str(v) if v is not None else m.group(0)
    out = re.sub(r"\{(\d+)\}", rep, desc)
    return TAG.sub("", out).replace("\\n", " ").strip()

CI = sys.argv[1] if len(sys.argv) > 1 else "1"
def cd_s(si):
    r = SK.get(str(si)); cd = r.get("CoolTimeMs") if r else None
    try:
        v = int(cd)/1000
        return v if v > 0 else None
    except Exception: return None

skills = [r for r in HS if str(r.get("CreatureIndex")) == CI]
acts = [r for r in skills if r.get("HeroSkillType") == "ActiveSkill"]
pas  = [r for r in skills if r.get("HeroSkillType") == "PassiveSkill"]

# 오픈레벨(RequireLevel) 순 정렬
def openlv(r):
    try: return int(r.get("RequireLevel", 0))
    except: return 0

NAME = {1:"히어로",2:"팔라딘",3:"다크나이트",4:"썬콜",5:"불독",6:"비숍",7:"보우마스터",8:"신궁",9:"나이트로드",10:"섀도어",11:"바이퍼",12:"캐논",13:"나이트워커",14:"윈드브레이커"}
STAT_KO = {
    "MainStat": ("주스탯", 1, ""),
    "CriticalChance": ("크리티컬 확률", 10, "%"),
    "BaseAttackPower": ("기본 공격 데미지", 10, "%"),
    "HitChance": ("명중", 1, ""),
    "MaxDamageRatio": ("최대 데미지 배율", 10, "%"),
    "MinDamageRatio": ("최소 데미지 배율", 10, "%"),
    "SkillPower": ("스킬 데미지", 10, "%"),
    "BaseAttackMaxHitCount": ("기본 공격 대상 수 증가", 1, ""),
    "CriticalPower": ("크리티컬 데미지", 10, "%"),
    "Attack": ("공격력", 10, "%"),
    "AttackPowerToBoss": ("보스 몬스터 데미지", 10, "%"),
    "FinalDamage": ("최종 데미지", 10, "%"),
    "Defense": ("방어력", 10, "%"),
    "MaxHp": ("최대 HP", 10, "%"),
    "AttackSpeed": ("공격 속도", 10, "%"),
}
def statdata_text(sd):
    if not sd: return None
    t = sd.get("Type")
    try: v = float(sd.get("Value"))
    except Exception: return "{STAT:%s=%s}" % (t, sd.get("Value"))
    if t in STAT_KO:
        label, div, unit = STAT_KO[t]
        vv = v / div; vv = int(vv) if vv == int(vv) else round(vv, 2)
        return "%s +%s%s" % (label, vv, unit)
    return "{STAT:%s=%s}" % (t, v)
def act_tail(cd, lv):
    parts = []
    if cd: parts.append("%g초" % cd)
    if lv: parts.append("%s렙" % lv)
    return (" - " + " ".join(parts)) if parts else ""
def pas_tail(cd, lv):
    parts = []
    if lv: parts.append("%s렙" % lv)
    if cd: parts.append("%g초" % cd)
    return (" - " + " ".join(parts)) if parts else ""

print(NAME.get(int(CI), CI))
print()
print("액티브 스킬")
for r in sorted(acts, key=openlv):
    si = r.get("SkillIndex")
    if str(si) == "9000": continue   # 민첩한 몸놀림 = 전 직업 공용(별도 섹션)
    nm = decrypt("SKILL_NAME_"+str(si)); desc = fill(si)
    print("%s: %s%s" % (nm, desc, act_tail(cd_s(si), r.get("RequireLevel"))))
print()
print("패시브 스킬")
for r in sorted(pas, key=openlv):
    si = r.get("SkillIndex"); nm = decrypt("SKILL_NAME_"+str(si)); desc = fill(si)
    print("%s: %s%s" % (nm, desc, pas_tail(cd_s(si), r.get("RequireLevel"))))
print()
print("스킬 마스터리")
mas = [m for m in HM if str(m.get("CreatureIndex")) == CI]
def mlv(m):
    try: return int(m.get("RequireCreatureLevel",0))
    except: return 0
for m in sorted(mas, key=mlv):
    mi = m.get("MasteryIndex")
    nl = m.get("NameLocal")
    mm = re.search(r"(\d+)$", nl) if nl else None
    didx = mm.group(1) if mm else str(mi)   # 이름·설명 모두 NameLocal 인덱스 기준(게임 표시대로). 대부분 mi와 동일, 히어로 인사이징/인핸스 4개만 교차.
    nm = decrypt(nl) if nl else decrypt("SKILL_NAME_"+str(mi))
    desc = fill(didx) or statdata_text(m.get("StatData"))
    print("%s: %s - %s렙" % (nm, desc, m.get("RequireCreatureLevel")))
