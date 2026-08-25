# 유물 장착스킬 SKILL_DESC 존재/내용 확인 — 죽은자부적/육각목걸이/헬레나장갑 0각 스킬
# -*- coding: utf-8 -*-
import io, json, os, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
from _clt_decrypt import decrypt, kv
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
ART = json.load(io.open(os.path.join(D, 'ArtifactTable.json'), encoding='utf-8'))

# 처음 3종 + 마지막 3종(신규 유물 후보)의 각 awaken 스킬 SKILL_DESC 확인
sample = ART[:3] + ART[-3:]
for a in sample:
    code = a['ArtifactCode']; nk = a.get('NameLocalKey')
    name = decrypt(nk, kv.get(nk)) if nk in kv else '?'
    print(f'\n===== {code} ({name}) =====')
    sis = a.get('EquipSkillIndices') or []
    for aw, si in enumerate(sis):
        k = 'SKILL_DESC_' + str(si)
        has = k in kv
        txt = decrypt(k, kv[k]) if has else None
        print(f'  aw{aw} si={si}  SKILL_DESC? {has}  ::  {txt!r}')
        if aw == 0 and not has:
            # 대체 키 후보
            for alt in ['SKILL_NAME_'+str(si), 'SKILL_SIMPLE_DESC_'+str(si)]:
                if alt in kv: print(f'       alt {alt} = {decrypt(alt, kv[alt])!r}')
