# -*- coding: utf-8 -*-
import io, json, sys
sys.stdout.reconfigure(encoding='utf-8')
A = json.load(io.open(r'C:\Users\MhHs\Documents\project\mekiCalc\data\skills_calc\artifact_manual.json', encoding='utf-8'))['artifacts']
print('effect에 trigger(≠onStart) 달린 유물:')
for x in A:
    procs = [(e['stat'], e.get('trigger'), e.get('chance'), e.get('durationMs')) for e in x['effects'] if e.get('trigger') and e.get('trigger') != 'onStart']
    if procs:
        print('  ', x['idx'], x['name'], procs)
print('\ndesc에 {N} 남은 유물:', [x['idx'] + ':' + x['name'] for x in A if x.get('desc') and '{' in x['desc']])
print('effects 0개:', [x['idx'] + ':' + x['name'] for x in A if not x['effects']])
