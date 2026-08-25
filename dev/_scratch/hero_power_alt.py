import json, os, math

base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
fac = json.load(open(os.path.join(base, 'HeroPowerSlotLevelStatFactorTable.json'), encoding='utf-8'))
F = {int(r['Level']): [int(x) for x in r['Factor']] for r in fac}

USER_STAT = [490,504,519,534,549,564,580,596,612,627,643,660,676,692,709,726,743,760,778,795,
             813,831,849,867,886,905,923,942,961,981,1001,1020,1040,1060,1081,1102,1122,1143,1164,1186,
             1207,1229,1251,1274,1297,1319,1342,1365,1389,1412,1436,1460,1485,1509,1534,1559,1585,1610,1636,1662,
             1689,1715,1742,1769,1797]
USER_ACC = [1050,1081,1113,1145,1178,1211,1244,1277,1311,1345,1379,1414,1449,1484,1520,1556,1592,1629,1667,1705,
            1743,1781,1820,1859,1899]

print("사용자 STAT 개수:", len(USER_STAT), " ACC 개수:", len(USER_ACC))
print("사용자 STAT 합:", sum(USER_STAT), " (post 표기 104,188)")
print("사용자 ACC 합:", sum(USER_ACC), " (post 표기 36,666)")
print()

def series(basec, ml, mode):
    out=[]
    for L in range(1, ml+1):
        v = basec*F[L][1]/1000
        if mode=='floor': v=math.floor(v)
        elif mode=='round': v=math.floor(v+0.5)
        elif mode=='ceil': v=math.ceil(v)
        out.append(int(v) if mode!='exact' else v)
    return out

for ml,label,basec,userlist,usertot in [(80,'STAT',490,USER_STAT,104188),(25,'ACC',1050,USER_ACC,36666)]:
    print(f"--- {label} base={basec} maxLv={ml}  (post 총 {usertot:,}) ---")
    for mode in ['floor','round','ceil','exact']:
        s=series(basec,ml,mode)
        tot=sum(s)
        print(f"  {mode:6s} 총={tot:,.1f}  끝값={s[-1]:.2f}")
    # match user's listed per-level with floor
    fl=series(basec,ml,'floor')
    mism=[(i+1,fl[i],userlist[i]) for i in range(min(len(fl),len(userlist))) if fl[i]!=userlist[i]]
    print(f"  floor vs 사용자표기 불일치(공통구간 {min(len(fl),len(userlist))}개 중): {mism}")
    print(f"  floor 1~{len(userlist)}레벨 합: {sum(fl[:len(userlist)]):,}  (사용자 같은구간 합 {sum(userlist):,})")
    print()
