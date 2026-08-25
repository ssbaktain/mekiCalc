import json, os, re
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
mt = json.load(open(os.path.join(base,'MonsterTierTable.json'), encoding='utf-8'))
rows=[]
for r in mt:
    ti = str(r.get('TierIndex',''))
    if re.fullmatch(r'5002\d+', ti):
        sr = r.get('StatRatio',{})
        rows.append((ti, int(ti[4:]), sr.get('AvoidChance'), sr.get('HitChance'), sr.get('Attack'), sr.get('MaxHp')))
rows.sort(key=lambda x:x[1])
print(f"5002 티어 개수: {len(rows)}")
print(f"{'티어':>10} {'ord':>4} {'회피(명중컷)':>10} {'몹명중':>8} {'공격비':>10} {'HP비':>12}")
for ti,ordn,av,hit,atk,hp in rows:
    print(f"{ti:>10} {ordn:>4} {str(av):>10} {str(hit):>8} {str(atk):>10} {str(hp):>12}")
