# 전 유물 stat op에 실제 쓰인 clt stat명 전수(재귀) + optype + LocalizeHint(StatType enum) 대조
# -*- coding: utf-8 -*-
import io, json, os, sys, re
from collections import defaultdict
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\MhHs\Documents\project\mekiCalc\dev')
import _clt2_stage_a as SA
D = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset'
ART = json.load(io.open(os.path.join(D, 'ArtifactTable.json'), encoding='utf-8'))
STAT_OPS = {'ModStat', 'ModStatR', 'ModDamagePipelineStat', 'ModAttrDamagePipelineStat'}

# StatType enum LocalizeHint 파싱(dump.cs)
DUMP = r'C:\Users\MhHs\Documents\project\mekiCalc\textAsset\dump_2694_il2cpp\dump.cs'
HINT = {}
lines = io.open(DUMP, encoding='utf-8', errors='ignore').read().splitlines()
for i, ln in enumerate(lines):
    m = re.search(r'public const StatType (\w+) = \d+;', ln)
    if m:
        name = m.group(1); hint = ''
        for j in range(max(0, i-3), i):
            h = re.search(r'LocalizeHint\("([^"]*)"', lines[j])
            if h: hint = h.group(1)
        HINT[name] = hint

def walk(si, seen, acc):
    s = SA.ST.get(si)
    if not s or si in seen: return
    seen.add(si)
    for op in (s.get('Operations') or []):
        if not isinstance(op, dict): continue
        t = op.get('Type'); v = op.get('Values') or []
        if t in STAT_OPS:
            stat = v[0] if t in ('ModStat','ModStatR') else (v[2] if len(v)>2 else None)
            acc[(stat, t)] += 1
        elif t == 'UsePassiveSkill':
            for cv in v:
                if str(cv).isdigit(): walk(str(cv), seen, acc)

acc = defaultdict(int)
for a in ART:
    for si in (a.get('EquipSkillIndices') or [])[:1]:  # aw0만(구조 동일)
        walk(str(si), set(), acc)

print('=== 유물에 쓰인 clt stat × optype ===')
for (stat, t), n in sorted(acc.items(), key=lambda x: -x[1]):
    print(f'  {str(stat):28} {t:26} x{n:2}  → StatType hint: {HINT.get(str(stat), "(enum에 없음/특수)")}')
