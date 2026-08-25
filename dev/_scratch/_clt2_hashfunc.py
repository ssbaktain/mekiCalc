# #함수 딜계수 4종 규명: 함수 쓰는 스킬 원본 op + skills_v2 desc 대조로 인자 의미 파악.
# -*- coding: utf-8 -*-
import json, io, os, sys, re
sys.stdout.reconfigure(encoding='utf-8')
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
SV = r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_v2'
ST = {}
for fn in ['SkillTable.json', 'SkillTable1.json']:
    for r in json.load(io.open(os.path.join(D, fn), encoding='utf-8')):
        ST[str(r.get('SkillIndex'))] = r
# skills_v2 전체: index -> (job, name, desc, damage)
JOBS = ['hero', 'paladin', 'darkknight', 'archmagetc', 'archmagefp', 'bishop', 'bowmaster',
        'marksman', 'nightlord', 'shadower', 'viper', 'captain', 'nightwalker', 'windbreaker']
V2 = {}
for j in JOBS:
    d = json.load(io.open(os.path.join(SV, j + '.json'), encoding='utf-8'))
    for s in d['skills']:
        V2[str(s['index'])] = (j, s.get('name'), s.get('desc'), s.get('damage'))

FUNCS = ['FlashSlash', 'TriflingWhim', 'ShadowBat', 'Element_Darkness_Dot', 'BowMaster_SoulArrow']
for fn in FUNCS:
    print('=' * 70)
    print('#' + fn)
    for si, s in ST.items():
        for i, op in enumerate(s.get('Operations') or []):
            if not isinstance(op, dict):
                continue
            for v in (op.get('Values') or []):
                if isinstance(v, str) and ('#' + fn + '(') in v:
                    # 부모 스킬(V2에 있는) 찾기: si 또는 그 부모
                    info = V2.get(si)
                    # si가 트리 자식(V2 미노출)이면 부모 추정: FollowLevelIndex
                    par = s.get('FollowLevelIndex')
                    pinfo = V2.get(str(par)) if par else None
                    print('  스킬 {} op{} {} = {}'.format(si, i, op.get('Type'), v))
                    if info:
                        print('     [V2 {}] {} | growth추정 | desc: {}'.format(info[0], info[1], (info[2] or '')[:75]))
                    elif pinfo:
                        print('     [부모 {} V2 {}] {} | desc: {}'.format(par, pinfo[0], pinfo[1], (pinfo[2] or '')[:75]))
                    else:
                        print('     [V2 미노출, 부모', par, ']')
