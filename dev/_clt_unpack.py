# -*- coding: utf-8 -*-
# 새 패치 bundle에서 모든 TextAsset 추출 → out 폴더. UnityPy 사용.
import os, io, sys
import UnityPy
sys.stdout.reconfigure(encoding="utf-8")

BUNDLE = r"C:\Nexon\MapleIdleRPG\AssetPackages\table\tables_assets_all.bundle"
OUT = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\dump_2694"
os.makedirs(OUT, exist_ok=True)

env = UnityPy.load(BUNDLE)
n = 0
for obj in env.objects:
    if obj.type.name != "TextAsset":
        continue
    data = obj.read()
    name = getattr(data, "m_Name", None) or getattr(data, "name", None)
    # m_Script(bytes/str) 추출
    script = getattr(data, "m_Script", None)
    if script is None:
        continue
    if isinstance(script, str):
        raw = script.encode("utf-8", "surrogateescape")
    else:
        raw = bytes(script)
    # 확장자: json이면 .json, 아니면 .txt
    ext = ".json"
    s = raw.lstrip()[:1]
    if s not in (b"[", b"{"):
        ext = ".txt"
    fn = f"{name}{ext}"
    with open(os.path.join(OUT, fn), "wb") as f:
        f.write(raw)
    n += 1

print(f"추출 완료: {n}개 TextAsset → {OUT}")
