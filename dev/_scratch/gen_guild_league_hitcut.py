import json, os, re
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
mt = json.load(open(os.path.join(base,'MonsterTierTable.json'), encoding='utf-8'))

rows=[]
for r in mt:
    ti=str(r.get('TierIndex',''))
    if re.fullmatch(r'5002\d+',ti):
        av=r.get('StatRatio',{}).get('AvoidChance')
        rows.append((int(ti[4:]), int(av)))
rows.sort()

SPAWN_BUFF = 20  # 일반몹 스폰직후 회피 +20 (9600010), 0.8초당 -2 감소, 8초 후 0

# HTML forum table
html=['<meta charset="utf-8">','<table class="table table-bordered"><tbody>']
html.append('<tr><td>웨이브</td><td>보스/일반몹 명중컷<br>(base 회피)</td>'
            '<td>일반몹 스폰직후 명중컷<br>(base+20, 0.8초당 -2)</td><td>직전값 대비</td></tr>')
prev=None
for w,av in rows:
    d = '' if prev is None else ('+%d'%(av-prev))
    html.append('<tr><td>%d</td><td><b>%d</b></td><td><b>%d</b></td><td>%s</td></tr>'
                %(w,av,av+SPAWN_BUFF,d))
    prev=av
html.append('</tbody></table>')
out=r'C:\Users\MhHs\길드대항전_웨이브별_명중컷.html'
open(out,'w',encoding='utf-8').write('\n'.join(html))
print('총 웨이브:',len(rows),' base 회피:',rows[0][1],'~',rows[-1][1],
      ' / 일반몹 스폰직후:',rows[0][1]+SPAWN_BUFF,'~',rows[-1][1]+SPAWN_BUFF)
print('saved',out)
print('\n웨이브: base / 스폰직후')
for w,av in rows:
    print(f'{w}:{av}/{av+SPAWN_BUFF}', end='  ')
    if w%10==0: print()
