# 유물 32종 이름 복호화 → 코드↔이름 매핑표 (유물 IR name 필드용)
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
from _clt_decrypt import decrypt, kv   # emu_hash 기반 AES 복호화 재사용

D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
ART = json.load(io.open(os.path.join(D, 'ArtifactTable.json'), encoding='utf-8'))

out = {}
print('=== 유물 32종 이름 복호화 ===')
for a in ART:
    code = a['ArtifactCode']; nk = a.get('NameLocalKey')
    name = decrypt(nk, kv.get(nk)) if nk in kv else None
    out[code] = {'index': a['Index'], 'grade': a['GradeType'], 'nameKey': nk, 'name': name}
    print(f"  {code} [{a['GradeType']}] {name}")

# 매핑표 저장(IR 구현 시 조인용)
outp = r'C:\Users\MhHs\Documents\project\mekiCalc\dev\_clt2_artifact_names.json'
json.dump(out, io.open(outp, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print(f'\n저장: {outp}')
