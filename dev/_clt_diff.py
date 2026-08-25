# -*- coding: utf-8 -*-
# 기존 3겹 덤프본(8-04) vs 새 추출본(8-13) 비교
import os, io, sys, hashlib
sys.stdout.reconfigure(encoding="utf-8")

OLD = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
NEW = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\dump_2694"

def norm(name):
    # chunk 분할 파일은 base 이름으로 병합 비교 어려우니 그대로 두되, 표시만
    return name

def digest(path):
    return hashlib.md5(open(path, "rb").read()).hexdigest()

old_files = {f for f in os.listdir(OLD) if os.path.isfile(os.path.join(OLD, f))}
new_files = {f for f in os.listdir(NEW) if os.path.isfile(os.path.join(NEW, f))}

# chunk 파일은 old쪽에만 있는 분할본 → base 이름 집합으로도 관리
def base_of(f):
    # A.chunk3.txt -> A
    if ".chunk" in f:
        return f.split(".chunk")[0]
    return f.rsplit(".", 1)[0]

old_bases = {base_of(f) for f in old_files}
new_bases = {base_of(f) for f in new_files}

added_bases = sorted(new_bases - old_bases)
removed_bases = sorted(old_bases - new_bases)

print("=== 신규 테이블(base 기준) ===")
for b in added_bases:
    print("  +", b)
print(f"신규 {len(added_bases)}개")

print("\n=== 삭제 테이블(base 기준) ===")
for b in removed_bases:
    print("  -", b)
print(f"삭제 {len(removed_bases)}개")

# 공통 파일 내용 변경 (chunk 없는 것만 정확 비교)
print("\n=== 내용 변경된 파일(비-chunk) ===")
changed = []
for f in sorted(old_files & new_files):
    if ".chunk" in f:
        continue
    if digest(os.path.join(OLD, f)) != digest(os.path.join(NEW, f)):
        so = os.path.getsize(os.path.join(OLD, f))
        sn = os.path.getsize(os.path.join(NEW, f))
        changed.append((f, so, sn))
for f, so, sn in changed:
    print(f"  * {f}  {so} -> {sn} ({sn-so:+d})")
print(f"변경 {len(changed)}개")
