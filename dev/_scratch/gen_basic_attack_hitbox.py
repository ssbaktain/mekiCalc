import json
import os

base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
hs = json.load(open(os.path.join(base, 'HeroSkillTable.json'), encoding='utf-8'))
st = json.load(open(os.path.join(base, 'SkillTable1.json'), encoding='utf-8'))
pj = json.load(open(os.path.join(base, 'SkillProjectileTable.json'), encoding='utf-8'))
byidx = {r['SkillIndex']: r for r in st if isinstance(r, dict)}
pjidx = {r['ProjectileIndex']: r for r in pj}

KOR = {
    '1': '히어로(전사)', '2': '팔라딘', '3': '다크나이트', '4': '아크메이지(썬콜)',
    '5': '아크메이지(불독)', '6': '비숍', '7': '보우마스터', '8': '신궁',
    '9': '나이트로드', '10': '섀도어', '11': '바이퍼', '12': '캡틴',
    '13': '나이트워커', '14': '윈드브레이커',
}


def dmgrect(s):
    for op in (s.get('Operations') or []):
        if isinstance(op, dict) and str(op.get('Type', '')).startswith('GetDamage') and 'RectAreaMm' in op:
            return op['RectAreaMm']
    return None


def proj_rect(s):
    for op in (s.get('Operations') or []):
        if isinstance(op, dict) and op.get('Type') == 'CreateProjectile':
            pi = (op.get('Values') or [None])[0]
            pr = pjidx.get(pi)
            if pr and pr.get('RectAreaMm'):
                return pr.get('RectAreaMm'), pi
    return None, None


def fmt(arr):
    if not arr:
        return None
    return '/'.join(str(x) for x in arr)


rows = []
for ci in [str(i) for i in range(1, 15)]:
    for step in ['1', '2', '3', '4']:
        acts = [r['SkillIndex'] for r in hs
                if r.get('CreatureIndex') == ci and r.get('JobStep') == step
                and r.get('HeroSkillType') == 'ActiveSkill']
        basics = [si for si in acts
                  if not byidx.get(si, {}).get('CoolTimeMs') and byidx.get(si, {}).get('MaxRangeMm')]
        for si in basics:
            s = byidx.get(si, {})
            nm = s.get('ActionName') or ('#' + si)
            rect = dmgrect(s)
            note = '직접타격'
            if not rect:
                prect, pi = proj_rect(s)
                if prect:
                    rect = prect
                    note = '투사체 %s' % pi
                else:
                    rect = s.get('MaxRangeMm')   # 판정=탐지범위 → 히트박스에 탐지범위 값 넣음
                    note = '타겟직격(판정=탐지범위)'
            rows.append((KOR[ci], step, nm, si, fmt(rect), fmt(s.get('MaxRangeMm')), note))

html = ['<meta charset="utf-8">', '<table class="table table-bordered"><tbody>']
html.append('<tr><td>직업</td><td>차수</td><td>기본공격</td><td>스킬번호</td>'
            '<td>히트박스<br>(앞/뒤/위/아래 mm)</td><td>탐지범위<br>(앞/뒤/위/아래 mm)</td><td>판정</td></tr>')
for job, step, nm, si, rect, mr, note in rows:
    html.append('<tr><td>%s</td><td>%s차</td><td>%s</td><td>%s</td><td><b>%s</b></td><td>%s</td><td>%s</td></tr>'
                % (job, step, nm, si, rect if rect else '-', mr, note))
html.append('</tbody></table>')

out = r'C:\Users\MhHs\차수별_기본공격_히트박스.html'
open(out, 'w', encoding='utf-8').write('\n'.join(html))
print('rows', len(rows))
print('saved', out)
