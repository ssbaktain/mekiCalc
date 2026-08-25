# -*- coding: utf-8 -*-
import json, os
base = r"C:\Users\MhHs\Documents\project\mekiCalc\textAsset\TextAsset\TextAsset"
def L(n): return json.load(open(os.path.join(base,n), encoding='utf-8'))

req=[float(r['ReqExp']) for r in L('JourneyTreasureLevelTable.json')]  # per-level cost
def cum(n): return sum(req[:n])
dt=L('DungeonTable.json')
ke={}
for d in dt:
    if isinstance(d,dict) and 'KillExp' in d:
        try: ch=int(d['ChapterIndex'])
        except: continue
        v=float(d['KillExp'])
        ke[ch]=min(ke.get(ch,1e99),v)   # X-1 스테이지 = 챕터 내 최소 KillExp (실제 파밍 스테이지)

EXP_RATE=1e-7
# 보정: 110마리/분 (기존 여정보물 분석 기준, 경험치/분·Ch44 3.2일 앵커와 정확히 일치)
kills_day=110*1440
def Eweek(ch): return kills_day*7*ke[ch]*EXP_RATE
def dLevels(ch,L0):
    E=Eweek(ch); lv=0; rem=E; cur=req[L0-1]
    while rem>=cur and lv<5000:
        rem-=cur; lv+=1; cur*=1.0302
    return lv+rem/cur

def rec(g):
    if g<3:  return ('아무 요일 (월≈일)','#8a8a8a')
    if g<15: return ('주 후반 (금·토 권장)','#3b7dd8')
    if g<50: return ('토·일','#e08a00')
    return ('무조건 일요일','#d63b3b')

H=['<meta charset="utf-8"><style>body{font-family:"Malgun Gothic",sans-serif;max-width:1000px;margin:20px auto;color:#222}'
   'table{border-collapse:collapse;width:100%;margin:10px 0}td,th{border:1px solid #ccc;padding:6px 9px;text-align:center;font-size:13px}'
   'th{background:#f0f2f5}h2{border-left:5px solid #4a7;padding-left:8px}h3{margin-top:26px}.k{color:#555;font-size:12px}b.hi{color:#d63b3b}</style>']
H.append('<h2>여정의 보물 · 주간 가속 — 언제(요일) 쓰는 게 최적인가</h2>')

# 메커니즘
H.append('<h3>1. 메커니즘 (클라이언트 데이터 확정)</h3><table><tbody>')
mech=[
 ('보물경험치 단위','"현재 보물레벨 필요경험치"의 %. 100%마다 보상카드 1장 선택→레벨업, <b>최대 200%까지 뱅킹</b>(초과분 소멸). 레벨·게이지는 <b>영구 누적</b>(주간 리셋 아님).'),
 ('충전원','몬스터 처치 + 오프라인 전투 기본경험치 × 1e-7. <b>ExpRate는 전 챕터 동일(1e-7)</b> → 챕터 차이는 순전히 몹 KillExp 차이. <b>19챕터 이하는 획득 불가(20챕터부터)</b>.'),
 ('주간 가속 효과','현재 레벨 필요경험치의 <b>20%(60%)/25%(30%)/30%(10%)</b> 추가 = 평균 <b>+0.225레벨</b>.'),
 ('횟수','광고 가속 주 1회 + <b>멤버십 가속 주 1회 = 최대 2회</b>. <b>매주 월요일 0시 초기화, 이월 없음</b>. 게이지 200% 도달 시 사용 불가.'),
]
for a,b in mech: H.append(f'<tr><td style="width:150px;background:#f7f9fb"><b>{a}</b></td><td style="text-align:left">{b}</td></tr>')
H.append('</tbody></table>')

# 핵심 원리
H.append('<h3>2. 왜 "후반(일요일)"이 이득인가</h3>')
H.append('<p>가속은 <b>사용 시점 레벨의 필요경험치</b>에 비례해 절대경험치를 준다. 필요경험치는 레벨이 오를수록 커지므로 '
 '(<b>보물레벨 50↑에서 레벨당 +3.02% 복리</b>, 저레벨은 6~23%로 더 가파름), '
 '한 주 동안 레벨이 많이 오르는 사람일수록 <b>가장 높은 레벨이 되는 일요일 밤(리셋 직전)에 쓰는 게 절대이득</b>이다. '
 '이득에 상한/스윗스팟은 없다 — 항상 늦게 쓸수록 이득(단, 게이지 200% 초과분 소멸만 주의).</p>')
H.append('<p class="k">핵심 공식 &nbsp; <b>가속을 일요일까지 미뤄 얻는 추가이득(%) ≈ 1.0302<sup>(그 주에 오를 보물레벨 수 ΔL)</sup> − 1 ≈ 3% × ΔL</b></p>')

# ΔL -> gain 표
H.append('<h3>3. "주당 보물레벨 상승수(ΔL)" → 일요일 미루기 이득 (범용·정확)</h3><table><tr>'
 '<th>주당 레벨업 ΔL</th><th>일요일 미루기 이득</th><th>권장</th></tr>')
for d in [0.5,1,1.5,2,3,5,8,12,20,35]:
    g=(1.0302**d-1)*100; r,c=rec(g)
    H.append(f'<tr><td>{d:g} 레벨/주</td><td><b>+{g:.0f}%</b></td><td style="color:{c}"><b>{r}</b></td></tr>')
H.append('</table><p class="k">※ ΔL은 인게임에서 직접 관찰 가능(그 주에 보물레벨이 몇 오르는지). 이 표만으로 바로 판단 가능.</p>')

# 챕터 x 현재레벨 매트릭스
H.append('<h3>4. 챕터 × 현재 보물레벨 → 권장 요일 (모델 추정)</h3>')
H.append('<p class="k">여정의 보물은 신규 컨텐츠(오픈 수일 경과) → <b>지금은 거의 모두 보물레벨이 낮음(왼쪽 열이 현재 현실)</b>. '
 '주가 지나 보물레벨이 오르면 오른쪽 열로 이동하며 요일 중요도가 낮아진다.</p>')
H.append('<table><tr><th>챕터<br>(X-1 KillExp)</th><th>보물레벨 20<br>(현재)</th><th>보물레벨 50</th><th>보물레벨 100</th><th>보물레벨 200</th></tr>')
for ch in [20,22,24,26,28,30,32,34,36,38,40,42,44]:
    cells=[f'<td style="background:#fafbfc"><b>Ch{ch}</b><br><span class="k">{ke[ch]:.2e}</span></td>']
    for L0 in [20,50,100,200]:
        d=dLevels(ch,L0); g=(1.0302**d-1)*100; r,c=rec(g)
        cells.append(f'<td style="color:{c}">{d:.1f}L/주<br><b>+{g:.0f}%</b><br><span style="font-size:11px">{r}</span></td>')
    H.append('<tr>'+''.join(cells)+'</tr>')
H.append('</table>')
H.append('<p class="k">※ 110마리/분·X-1 스테이지 기준(경험치/분 Ch20=497·Ch35=33124·Ch44=368990, Ch44 레벨100 한칸 3.2일 — 기존 분석과 일치). '
 '절대 요일 판정은 <b>현재 보물레벨</b>에 크게 의존 — 이미 고레벨(느린 성장)이면 상위 챕터라도 월≈일. '
 '표 3의 ΔL 규칙이 더 정확하며, 표 4는 "챕터가 높고 아직 보물레벨이 낮아 빠르게 크는 사람"일수록 일요일이 중요함을 보여준다.</p>')

# 결론
H.append('<h3>5. 결론</h3><ul>'
 '<li><b>효율 최대는 언제나 일요일 밤(월요일 0시 리셋 직전).</b> 늦출수록 항상 이득(스윗스팟 없음).</li>'
 '<li><b>ΔL(주당 레벨업) ≤ 1.5</b> → 이득 5% 미만 → <b>아무 요일에나</b> 편하게(월요일에 해도 손해 미미). 저챕터·고보물레벨이 여기 해당.</li>'
 '<li><b>ΔL 3~5</b> → 이득 10~16% → 금·토까지는 미루기.</li>'
 '<li><b>ΔL 8↑</b> → 이득 25%+ → <b>무조건 일요일</b>. 상위 챕터에서 아직 보물레벨을 빠르게 올리는 중이면 반드시 여기.</li>'
 '<li>멤버십 가속(주 2회) <b>둘 다 일요일에</b> 쓰는 게 최적(둘 다 최고 레벨에서 소모). 단 게이지가 이미 높으면(≈175%↑) 200% 초과분 소멸 주의 → 카드 먼저 뽑아 게이지 낮추고 가속.</li>'
 '</ul>')

out=r'C:\Users\MhHs\여정의보물_가속요일.html'
open(out,'w',encoding='utf-8').write('\n'.join(H))
print('saved',out)
