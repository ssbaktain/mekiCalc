# 유물 desc 키 패턴 탐색 — Localization_ko 평문 키 중 ARTIFACT 관련 나열 + 후보 desc 복호화
# -*- coding: utf-8 -*-
import io, json, os, sys, re
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
from _clt_decrypt import decrypt, kv

# 1) ARTIFACT 관련 평문 키 패턴 집계(끝 숫자 제거해 패턴화)
pats = {}
for k in kv:
    if 'ARTIFACT' in k.upper():
        p = re.sub(r'\d+', '#', k)
        pats.setdefault(p, []).append(k)
print('=== ARTIFACT 관련 Localization 키 패턴 ===')
for p, ks in sorted(pats.items()):
    print(f'  {p:40} x{len(ks)}   예: {ks[0]}')

# 2) 3_1(죽은자의 부적) 후보 키 복호화 시도
print('\n=== ARTIFACT_3_1 후보 복호화 ===')
for cand in ['ARTIFACT_NAME_3_1', 'ARTIFACT_DESC_3_1', 'ARTIFACT_DESCRIPTION_3_1',
             'ARTIFACT_EXPLAIN_3_1', 'ARTIFACT_INFO_3_1', 'ARTIFACT_TOOLTIP_3_1']:
    if cand in kv:
        print(f'  {cand} = {decrypt(cand, kv[cand])!r}')
    else:
        print(f'  {cand}  (키 없음)')
