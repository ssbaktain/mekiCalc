# -*- coding: utf-8 -*-
import json
data=json.load(open(r'C:\Users\MhHs\Documents\project\mekiCalc\dev\_scratch\killexp_data.json',encoding='utf-8'))
KE={int(k):v for k,v in data.items()}

def kor(n):
    if n<=0: return '0'
    for u,s in [(1e16,'경'),(1e12,'조'),(1e8,'억'),(1e4,'만')]:
        if n>=u:
            v=n/u
            return (f'{v:.0f}' if v>=100 else f'{v:.2f}')+s
    return f'{round(n):,}'

EXP_RATE=1e-7
BASE=100  # 100마리/분 기준

# 카드 헬퍼 (디시 규격: table + bgcolor + inline style)
def card(inner,bg='#ffffff',bd='#e5e7eb',pad='16px'):
    return (f'<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="{bg}" '
            f'style="border-collapse:collapse;background-color:{bg};border:1px solid {bd};margin-bottom:12px;">'
            f'<tr><td style="padding:{pad};color:#1f2937;font-family:Arial,\'Malgun Gothic\',sans-serif;'
            f'font-size:15px;line-height:1.7;">{inner}</td></tr></table>')

H=[]
# 제목 카드
H.append(card(
  '<div style="font-size:12px;letter-spacing:1px;color:#1d4ed8;font-weight:bold;">MAPLE IDLE · JOURNEY TREASURE</div>'
  '<div style="font-size:23px;line-height:1.35;font-weight:bold;color:#111827;margin-top:6px;">여정의 보물 경험치 조견표</div>'
  '<div style="font-size:13px;color:#4b5563;margin-top:8px;">아래 값은 <b>분당 100마리</b> 기준. '
  '본인 분당처치수가 다르면 <b>(분당처치수 ÷ 100)</b>을 곱하면 됨. (예: 150마리 → ×1.5, 60마리 → ×0.6)</div>',
  bg='#f3f6fb',bd='#d8dee9',pad='20px'))

# 메인 표 (챕터 20~44, X-1 스테이지 기준)
rows=['<tr bgcolor="#eef1f6">'
      '<td style="padding:8px;border:1px solid #d8dee9;font-weight:bold;">챕터</td>'
      '<td style="padding:8px;border:1px solid #d8dee9;font-weight:bold;text-align:right;">분당</td>'
      '<td style="padding:8px;border:1px solid #d8dee9;font-weight:bold;text-align:right;">시간당</td>'
      '<td style="padding:8px;border:1px solid #d8dee9;font-weight:bold;text-align:right;">하루</td>'
      '<td style="padding:8px;border:1px solid #d8dee9;font-weight:bold;text-align:right;">일주일</td></tr>']
for ch in range(20,45):
    ke=min(KE[ch])  # X-1 = 최저 스테이지(기본 파밍)
    per_min=BASE*ke*EXP_RATE
    bg='#ffffff' if ch%2 else '#fafbfc'
    rows.append(f'<tr bgcolor="{bg}">'
      f'<td style="padding:8px;border:1px solid #e5e7eb;font-weight:bold;color:#111827;">{ch}-1</td>'
      f'<td style="padding:8px;border:1px solid #e5e7eb;text-align:right;">{kor(per_min)}</td>'
      f'<td style="padding:8px;border:1px solid #e5e7eb;text-align:right;">{kor(per_min*60)}</td>'
      f'<td style="padding:8px;border:1px solid #e5e7eb;text-align:right;color:#1d4ed8;font-weight:bold;">{kor(per_min*60*24)}</td>'
      f'<td style="padding:8px;border:1px solid #e5e7eb;text-align:right;">{kor(per_min*60*24*7)}</td></tr>')
table=(f'<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#ffffff" '
       f'style="border-collapse:collapse;background-color:#ffffff;font-family:Arial,\'Malgun Gothic\',sans-serif;'
       f'font-size:13px;color:#1f2937;">'+''.join(rows)+'</table>')
H.append(card(
  '<div style="font-size:12px;color:#1d4ed8;font-weight:bold;letter-spacing:1px;">보물경험치 획득량 (분당 100마리 · X-1 스테이지)</div>'
  '<div style="height:8px;"></div>'+table))

# 스테이지 보정 안내
H.append(card(
  '<b>스테이지 보정</b><br>'
  '표는 <b>X-1(최저 스테이지)</b> 기준. 같은 챕터라도 상위 스테이지일수록 경험치가 조금 더 많음(최고 스테이지 기준 <b>약 +25~30%</b>).<br>'
  '상위 챕터로 갈수록 챕터당 경험치는 약 <b>1.32배씩 급증</b>.',
  bg='#fff8ed',bd='#ead7b7'))

# 공식/출처
H.append(card(
  '<b>계산식</b><br>'
  '보물경험치 = 분당처치수 × 스테이지 KillExp × 0.0000001<br>'
  '· 경험치 획득률·버프·멤버십과 <b>무관</b>(순수 기본경험치만 반영)<br>'
  '· <b>19챕터 이하는 여정의 보물 경험치 획득 불가</b><br>'
  '· KillExp는 클라이언트 데이터(빌드 2694) 기준',
  bg='#f7f7f7',bd='#d4d4d4',pad='14px'))

out=r'C:\Users\MhHs\여정의보물_경험치_디시표.html'
frag='<div style="font-family:Arial,\'Malgun Gothic\',sans-serif;">\n'+'\n'.join(H)+'\n</div>'
open(out,'w',encoding='utf-8').write(frag)
print('saved',out,len(frag),'bytes')
