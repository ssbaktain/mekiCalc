# -*- coding: utf-8 -*-
# 나이트로드 seed의 name/masteryName/desc를 최신 복호화(SKILL_NAME/SKILL_DESC)와 전수 대조
import json, io, sys, importlib.util
sys.stdout.reconfigure(encoding="utf-8")

spec = importlib.util.spec_from_file_location("dec", r"dev\_clt_decrypt.py")
dec = importlib.util.module_from_spec(spec); spec.loader.exec_module(dec)
def name_of(idx):
    ct = dec.kv.get("SKILL_NAME_"+str(idx))
    return dec.decrypt("SKILL_NAME_"+str(idx), ct) if ct else None
def desc_of(idx):
    ct = dec.kv.get("SKILL_DESC_"+str(idx))
    return dec.decrypt("SKILL_DESC_"+str(idx), ct) if ct else None

seed = json.load(io.open(r"data\skills_calc\nightlord_seed.json", encoding="utf-8"))

print("========== 스킬 이름 대조 ==========")
for s in seed["skills"]:
    idx = s["idx"]; cur = s["name"]; latest = name_of(idx)
    mark = "OK" if cur == latest else "≠≠≠"
    print(f"[{mark}] {idx}  seed='{cur}'  clt='{latest}'")

print("\n========== 마스터리 이름 대조 ==========")
for m in seed["masteries"]:
    mi = m["masteryIdx"]; cur = m["masteryName"]; latest = name_of(mi)
    if latest is None:
        print(f"[STAT] {mi}  seed='{cur}'  clt=(SKILL_NAME 없음, StatData 전역스탯)")
        continue
    mark = "OK" if cur == latest else "≠≠≠"
    print(f"[{mark}] {mi}  seed='{cur}'  clt='{latest}'")

print("\n========== 스킬 desc 원문(템플릿) ==========")
for s in seed["skills"]:
    idx = s["idx"]
    print(f"--- {idx} {s['name']} ---")
    print(f"  seed: {s['desc']}")
    print(f"  clt : {desc_of(idx)}")
