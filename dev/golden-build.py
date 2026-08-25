#!/usr/bin/env python3
# 빌드 프리셋 통합 리팩터 전용 골든 마스터.
# 프론트가 프리셋에서 조립해 /api/damage 로 보내는 "유효 입력"(직업+스탯+스킬+동료+유물) 매트릭스를 고정하고,
# 응답을 저장한다. 백엔드는 이 리팩터에서 안 바뀌므로, 리팩터 후 동일 유효입력에 동일 응답이어야 한다(회귀 안전망).
#   사용:  python dev/golden-build.py before      # 리팩터 전
#          python dev/golden-build.py after        # 리팩터 후
#          python dev/golden-build.py diff before after   # 비교
import json, sys, os, urllib.request

BASE = os.environ.get("MEKI_BASE", "http://localhost:8899")
ROOT = os.path.join(os.path.dirname(__file__), "golden", "build-refactor")

def get(path):
    return json.load(urllib.request.urlopen(BASE + path))
def post(path, payload):
    req = urllib.request.Request(BASE + path, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req))

def base_stats():
    defs = get("/api/stats")
    b = {d["id"]: 0 for d in defs}
    b.update({"LEVEL": 210, "ATK_FLAT": 52400, "ATK_PCT": 640, "CRIT_RATE": 100, "CRIT_DMG": 268,
              "ATK_SPEED": 74.3, "MAIN_STAT_FLAT": 8200, "MAIN_STAT_PCT": 120, "SUB_STAT_FLAT": 3000,
              "DMG": 215, "DEF_PEN": 61, "BOSS_DMG": 180, "NORMAL_DMG": 150, "MIN_DMG_MULT": 165,
              "MAX_DMG_MULT": 249, "FINAL_DMG": 52.4, "BUFF_DURATION": 12, "COOLDOWN_REDUCTION_PCT": 24})
    return b

def buff_ids(job):
    b = base_stats(); b["JOB"] = job
    r = post("/api/buffs", {"stats": b})
    return [x.get("id") for x in (r.get("dealSkills", []) + r.get("buffs", [])) if x.get("id")]

COMP = {"companionId": "hero", "grade": "LEGENDARY", "level": 210, "ratio": 100}
ARTS = [{"id": "emotion_bottle", "awaken": 5}, {"id": "arwen_glass_shoes", "awaken": 5},
        {"id": "helena_gloves", "awaken": 5}, {"id": "hexcrystal_necklace", "awaken": 5}]

def fixtures():
    b1, b2, b3 = (base_stats() for _ in range(3))
    b1["JOB"], b2["JOB"], b3["JOB"] = 1, 2, 3
    ids1, ids2, ids3 = buff_ids(1), buff_ids(2), buff_ids(3)
    b0 = base_stats(); b0["JOB"] = 0  # 공용(공통 경로)
    def req(stats, buffs, comp=False, arts=False):
        p = {"stats": stats, "conversions": {}, "buffs": buffs, "hitInterval": 0,
             "companionId": "", "grade": "", "level": 0, "ratio": 0, "artifacts": []}
        if comp: p.update(COMP)
        if arts: p["artifacts"] = ARTS
        return p
    return {
        "nl_solo":        req(b1, []),
        "nl_full":        req(b1, ids1, comp=True, arts=True),
        "dk_full":        req(b2, ids2, comp=True, arts=True),
        "bi_solo_nobuff": req(b3, []),
        "bi_buffs_only":  req(b3, ids3),
        "bi_comp":        req(b3, ids3, comp=True),
        "bi_arts":        req(b3, ids3, arts=True),
        "bi_full":        req(b3, ids3, comp=True, arts=True),
        "common_job":     req(b0, []),
    }

def canon(o):
    return json.dumps(o, ensure_ascii=False, sort_keys=True, separators=(",", ":"))

def capture(tag):
    out = os.path.join(ROOT, tag)
    os.makedirs(out, exist_ok=True)
    fx = fixtures()
    manifest = []
    for name, payload in fx.items():
        resp = post("/api/damage", payload)
        with open(os.path.join(out, name + ".request.json"), "w", encoding="utf-8") as f:
            f.write(canon(payload))
        with open(os.path.join(out, name + ".response.json"), "w", encoding="utf-8") as f:
            f.write(canon(resp))
        manifest.append(name)
        print(f"  {name}: OK")
    with open(os.path.join(out, "_manifest.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(manifest))
    print(f"완료 → {out} ({len(manifest)}개)")

def diff(a, b):
    da, db = os.path.join(ROOT, a), os.path.join(ROOT, b)
    names = [n.strip() for n in open(os.path.join(da, "_manifest.txt"), encoding="utf-8")]
    bad = 0
    for name in names:
        for kind in ("request", "response"):
            fa = os.path.join(da, f"{name}.{kind}.json"); fb = os.path.join(db, f"{name}.{kind}.json")
            ca = open(fa, encoding="utf-8").read() if os.path.exists(fa) else None
            cb = open(fb, encoding="utf-8").read() if os.path.exists(fb) else None
            if ca != cb:
                bad += 1; print(f"  DIFF {name}.{kind}")
    print("일치(0 diff)" if bad == 0 else f"{bad}건 불일치")
    return bad

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "before"
    if cmd == "diff":
        sys.exit(1 if diff(sys.argv[2], sys.argv[3]) else 0)
    capture(cmd)
