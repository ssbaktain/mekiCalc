# skill.txt 교차검증: (1)병합무결성 skill.txt섹션==스크립트재출력, (2)개수완전성, (3)계수 독립검증(클뜯 raw)
# -*- coding: utf-8 -*-
import io, json, os, re, sys, subprocess
sys.stdout.reconfigure(encoding='utf-8')
BASE = r"C:\Users\MhHs\Documents\project\mekiCalc"
NEW = os.path.join(BASE, r"textAsset\TextAsset\TextAsset")
def load(n): return json.load(io.open(os.path.join(NEW, n), encoding="utf-8"))
SK = {}
for fn in os.listdir(NEW):
    if fn.startswith("SkillTable") and fn.endswith(".json"):
        for r in load(fn): SK[str(r.get("SkillIndex"))] = r
HS = load("HeroSkillTable.json")
HM = load("HeroSkillMasteryTable.json")
NAME = {1:"히어로",2:"팔라딘",3:"다크나이트",4:"썬콜",5:"불독",6:"비숍",7:"보우마스터",8:"신궁",9:"나이트로드",10:"섀도어",11:"바이퍼",12:"캐논"}

# ---- skill.txt 파싱: 직업별 블록 ----
full = io.open(os.path.join(BASE, "skill.txt"), encoding="utf-8").read()
lines = full.split("\n")
# 직업 헤더 위치
hdr_idx = {}
for i, l in enumerate(lines):
    s = l.strip()
    for ci, nm in NAME.items():
        if s == nm and (i+2 < len(lines) and lines[i+2].strip() in ("액티브 스킬","패시브 스킬")):
            hdr_idx[ci] = i
order = sorted(hdr_idx, key=lambda c: hdr_idx[c])
blocks = {}
for j, ci in enumerate(order):
    start = hdr_idx[ci]
    end = hdr_idx[order[j+1]] if j+1 < len(order) else len(lines)
    blocks[ci] = "\n".join(lines[start:end]).strip()

print("=== (1) 병합 무결성: skill.txt 섹션 vs 스크립트 재출력 ===")
allok = True
for ci in range(1, 13):
    frag = subprocess.run([sys.executable, os.path.join(BASE, r"dev\_clt_job_skilltext.py"), str(ci)],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()
    if ci == 10: frag = frag.replace("{3}%", "6000%")   # 병합 때와 동일 보정
    a = blocks.get(ci, "").split("\n")
    b = frag.split("\n")
    if a == b:
        print("  CI%2d %s: 일치 (%d줄)" % (ci, NAME[ci], len(a)))
    else:
        allok = False
        print("  CI%2d %s: ★불일치! skill.txt %d줄 vs 재출력 %d줄" % (ci, NAME[ci], len(a), len(b)))
        for k in range(max(len(a), len(b))):
            la = a[k] if k < len(a) else "(없음)"
            lb = b[k] if k < len(b) else "(없음)"
            if la != lb:
                print("     L%d skill.txt: %s" % (k, la))
                print("     L%d 재출력  : %s" % (k, lb))
                break
print("  => 병합 무결성:", "OK 전부 일치" if allok else "★불일치 있음")

print("\n=== (2) 완전성: 스킬/마스터리 개수 (클뜯 테이블 vs skill.txt) ===")
for ci in range(1, 13):
    hs = [r for r in HS if str(r.get("CreatureIndex")) == str(ci)]
    act = [r for r in hs if r.get("HeroSkillType") == "ActiveSkill" and str(r.get("SkillIndex")) != "9000"]
    pas = [r for r in hs if r.get("HeroSkillType") == "PassiveSkill"]
    mas = [m for m in HM if str(m.get("CreatureIndex")) == str(ci)]
    blk = blocks.get(ci, "")
    # skill.txt 섹션별 줄 수
    def sect(name, nxt):
        m = re.search(re.escape(name)+r"\n(.*?)(?=\n\n"+ (re.escape(nxt) if nxt else "$") +r")", blk, re.S)
        if not m:
            m = re.search(re.escape(name)+r"\n(.*)$", blk, re.S)
        return [x for x in (m.group(1).split("\n") if m else []) if x.strip()]
    a_lines = sect("액티브 스킬", "패시브 스킬")
    p_lines = sect("패시브 스킬", "스킬 마스터리")
    m_lines = sect("스킬 마스터리", None)
    tag = "OK" if (len(a_lines)==len(act) and len(p_lines)==len(pas) and len(m_lines)==len(mas)) else "★불일치"
    print("  CI%2d %s: 액티브 클뜯%d/txt%d  패시브 클뜯%d/txt%d  마스터리 클뜯%d/txt%d  %s"
          % (ci, NAME[ci], len(act), len(a_lines), len(pas), len(p_lines), len(mas), len(m_lines), tag))

print("\n=== (3) 계수 독립검증: skill.txt 딜스킬 계수 vs 클뜯 SkillTable GetDamageR ===")
# 각 직업 딜 액티브의 첫 계수%를 skill.txt에서 파싱 → 해당 스킬 raw와 대조 (샘플: 4차 기본공격류·주력기)
# HeroSkillTable로 스킬index 확보, SkillTable GetDamageR[0]/10 = 계수. skill.txt 설명서 같은 % 존재?
mismatch = 0
checked = 0
for ci in range(1, 13):
    hs = [r for r in HS if str(r.get("CreatureIndex")) == str(ci) and r.get("HeroSkillType")=="ActiveSkill"]
    blk = blocks.get(ci, "")
    for r in hs:
        si = str(r.get("SkillIndex"))
        if si == "9000": continue
        sk = SK.get(si)
        if not sk: continue
        # 첫 GetDamageR 계수
        coeff = None
        for op in sk.get("Operations") or []:
            if isinstance(op, dict) and op.get("Type","").startswith("GetDamageR"):
                v = op.get("Values")
                if v:
                    try: coeff = float(v[0])/10
                    except: pass
                break
        if coeff is None: continue
        cs = ("%g%%" % coeff)
        # skill.txt 해당 스킬줄에 이 계수 존재?
        # 스킬명으로 줄 찾기 (SKILL_NAME 복호화 없이 raw idx→ 스크립트가 이미 넣음). 계수 문자열 포함 여부만 전체 블록에서.
        checked += 1
        if cs not in blk:
            mismatch += 1
            print("  ★CI%d skill %s: 클뜯 계수 %s 가 skill.txt에 없음" % (ci, si, cs))
print("  => 딜 액티브 %d개 중 계수 불일치 %d개" % (checked, mismatch))
