# 유물 IR 설계 확정용: 미확정 5개 + 시스템 메타를 실제 클뜯 데이터로 규명.
# -*- coding: utf-8 -*-
import json, io, os, sys
from collections import Counter, defaultdict
sys.stdout.reconfigure(encoding='utf-8')
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'

def L(fn):
    return json.load(io.open(os.path.join(D, fn), encoding='utf-8'))

ART = L('ArtifactTable.json')
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in L(fn):
        ST[str(r.get('SkillIndex'))] = r

print('=== ① ArtifactTable 구조 (첫 항목 필드) ===')
print(list(ART[0].keys()))
print(f'유물 수: {len(ART)}')

print('\n=== ② 등급 분포 + 코드 + 각성스킬수 ===')
grades = Counter()
for a in ART:
    grades[a.get('GradeType')] += 1
print(dict(grades))
print('\n--- 전 유물 코드/등급/EquipSkill개수/CollectionOption개수 ---')
for a in ART:
    eq = a.get('EquipSkillIndices') or []
    co = a.get('CollectionOptionIndices') or []
    print(f"  {a.get('ArtifactIndex') or a.get('Index')}: {a.get('ArtifactCode')} [{a.get('GradeType')}] eq={len(eq)} col={len(co)}")

print('\n=== ③ 각성 단계별 효과(EquipSkillIndices 예시 2개) — 각성=강화 확인 ===')
for a in ART[:2]:
    print(f"\n유물 {a.get('ArtifactCode')} EquipSkillIndices={a.get('EquipSkillIndices')}")
    for si in (a.get('EquipSkillIndices') or []):
        s = ST.get(str(si))
        if not s:
            print(f'    {si}: (SkillTable 없음)')
            continue
        ops = s.get('Operations') or []
        summ = []
        for op in ops:
            if isinstance(op, dict):
                summ.append(f"{op.get('Type')}{op.get('Values')}")
        print(f'    {si}: {summ}')
