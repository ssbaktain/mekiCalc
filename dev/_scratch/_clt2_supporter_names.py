# 동료 61종 이름 복호화 → 코드↔이름 매핑표 (동료 IR name 필드용)
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
from _clt_decrypt import decrypt, kv
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
SUP = json.load(io.open(os.path.join(D, 'SupporterTable.json'), encoding='utf-8'))
out = {}
print('=== 동료 61종 이름 복호화 ===')
for s in SUP:
    code = s['SupporterCode']; nk = s.get('NameLocalKey')
    name = decrypt(nk, kv.get(nk)) if nk in kv else None
    out[code] = {'index': s['SupporterIndex'], 'class': s['ClassType'], 'grade': s['GradeType'], 'name': name}
    print(f"  {code} [{s['ClassType']} {s['GradeType']}] {name}")
outp = r'C:\Users\MhHs\Documents\project\mekiCalc\dev\_clt2_supporter_names.json'
json.dump(out, io.open(outp, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print(f'\n저장: {outp}')
