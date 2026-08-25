import json
from collections import Counter
import os

base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
dt = json.load(open(os.path.join(base, 'DungeonTable.json'), encoding='utf-8'))
fam = json.load(open(os.path.join(base, 'CampFamilyTable1.json'), encoding='utf-8'))
F = {r['FamilyIndex']: r for r in fam}


def analyze(row):
    ov = row.get('OverrideFamilyIndex')
    try:
        md = json.load(open(os.path.join(base, row['MapDefineFileName']), encoding='utf-8'))
    except Exception:
        return None
    auto = 0
    elite = 0
    rate = 0.0
    cts = Counter()
    for c in md['Camps']:
        ci = c['CampIndex']
        fi = ov[ci] if (ov and ci < len(ov)) else None
        r = F.get(fi)
        if not r:
            continue
        sc = sum(int(m['SpawnCount']) for m in r['Monster'])
        if r.get('NoAutoSpawn') == 'True':
            elite += sc
            continue
        ct = int(r.get('OverrideCoolTimeMs') or c.get('CoolTimeMs') or 90000)
        if ct >= 999999:
            continue
        auto += sc
        rate += sc * 60000.0 / ct
        cts[ct] += 1
    doms = '/'.join(str(k // 1000) for k, _ in cts.most_common()) if cts else '-'
    return auto, elite, round(rate, 1), doms


rows = []
for ch in range(1, 45):
    srows = [r for r in dt if r.get('ChapterIndex') == str(ch) and 'IsTrial' not in r]
    srows.sort(key=lambda r: int(r.get('DungeonIndex', '0')))
    for r in srows:
        di = r.get('DungeonIndex')
        res = analyze(r)
        if not res:
            continue
        a, e, rate, doms = res
        kind = '사냥' if a > 1 else '보스/이동'
        rows.append((ch, di, kind, a, e, doms, rate))

html = ['<meta charset="utf-8">', '<table class="table table-bordered"><tbody>']
html.append('<tr><td>챕터</td><td>스테이지</td><td>구분</td><td>자동스폰 몹수</td>'
            '<td>쿨타임(초)</td><td>분당 스폰</td></tr>')
for ch, di, kind, a, e, doms, rate in rows:
    hunt = kind == '사냥'
    html.append(
        '<tr><td>%d</td><td>%s-%s</td><td>%s</td>'
        '<td>%s</td><td>%s</td><td><b>%s</b></td></tr>'
        % (ch, ch, di, kind,
           a if hunt else '-',
           doms if hunt else '-',
           ('%.1f' % rate) if hunt else '-'))
html.append('</tbody></table>')

out = r'C:\Users\MhHs\챕터별_몹스폰표.html'
open(out, 'w', encoding='utf-8').write('\n'.join(html))
print('rows total', len(rows), '| 사냥', sum(1 for x in rows if x[2] == '사냥'))
print('saved', out)
print('--- 챕터별 최대 분당(사냥 스테이지) ---')
for ch in range(1, 45):
    hs = [x for x in rows if x[0] == ch and x[2] == '사냥']
    if hs:
        mx = max(hs, key=lambda x: x[6])
        print('Ch%2d: 최대 분당 %3d (스테이지 %s, 자동 %d마리, 쿨 %s초)'
              % (ch, mx[6], mx[1], mx[3], mx[5]))
