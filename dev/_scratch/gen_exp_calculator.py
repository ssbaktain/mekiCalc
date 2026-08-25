# -*- coding: utf-8 -*-
import json
data=json.load(open(r'C:\Users\MhHs\Documents\project\mekiCalc\dev\_scratch\killexp_data.json',encoding='utf-8'))
# compact: {chapter: [killexp per stage]}
KE=json.dumps({int(k):v for k,v in data.items()}, separators=(',',':'))

html = '''<!DOCTYPE html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>여정의 보물 경험치 계산기</title>
<style>
*{box-sizing:border-box}
body{font-family:"Malgun Gothic","Apple SD Gothic Neo",sans-serif;background:#eef1f6;margin:0;padding:16px;color:#1f2937}
.wrap{max-width:460px;margin:0 auto}
.card{background:#fff;border:1px solid #d8dee9;border-radius:12px;padding:18px;margin-bottom:14px}
h1{font-size:19px;margin:0 0 4px}
.sub{font-size:12px;color:#6b7280;margin-bottom:14px}
label{display:block;font-size:13px;font-weight:bold;color:#374151;margin:12px 0 5px}
select,input{width:100%;padding:11px;font-size:15px;border:1px solid #cbd5e1;border-radius:8px;background:#fff;color:#111}
.row{display:flex;gap:10px}
.row>div{flex:1}
.res{background:#f3f6fb;border:1px solid #d8dee9}
.big{font-size:14px;color:#4b5563;margin:2px 0}
.big b{font-size:20px;color:#1d4ed8}
.hl{font-size:13px;color:#4b5563;margin-top:2px}
.hl b{font-size:16px;color:#111827}
.warn{background:#fff4e5;border-color:#f0c893;color:#92400e;font-size:13px;padding:11px;border-radius:8px;margin-top:10px}
.foot{font-size:11px;color:#9097a3;line-height:1.6;margin-top:6px}
table{width:100%;border-collapse:collapse;margin-top:8px}
td{padding:6px 4px;font-size:13px;border-bottom:1px solid #eef1f6}
td:last-child{text-align:right;font-weight:bold;color:#111827}
</style></head>
<body><div class="wrap">
<div class="card">
<h1>여정의 보물 경험치 계산기</h1>
<div class="sub">챕터·스테이지·분당 처치수를 고르면 보물경험치 획득량이 나와요.</div>

<label>챕터</label>
<select id="ch"></select>

<label>스테이지</label>
<select id="st"></select>

<label>분당 처치 수 (마리/분)</label>
<input id="kpm" type="number" value="110" min="1" step="1" inputmode="numeric">
</div>

<div class="card res">
<div class="big">보물경험치 / 분 &nbsp; <b id="o_min">-</b></div>
<table>
<tr><td>시간당 (60분)</td><td id="o_hr">-</td></tr>
<tr><td>하루 (24시간)</td><td id="o_day">-</td></tr>
<tr><td>일주일 (7일)</td><td id="o_week">-</td></tr>
</table>
<div id="warn"></div>
<div class="hl" style="margin-top:12px">참고 · 캐릭터 기본경험치 / 분 &nbsp; <b id="o_base">-</b></div>
</div>

<div class="foot">
· 보물경험치 = 처치수 × 스테이지 KillExp × 1e-7 (경험치 획득률·버프 무관, 순수 기본경험치 기준)<br>
· 19챕터 이하는 여정의 보물 경험치를 획득할 수 없습니다.<br>
· KillExp는 클라이언트 데이터(빌드 2694) 기준.
</div>
</div>

<script>
var KE=__KE__;
var EXP_RATE=1e-7;
var chSel=document.getElementById('ch'), stSel=document.getElementById('st'), kpm=document.getElementById('kpm');

function kor(n){
  if(!isFinite(n)||n<=0) return '0';
  var units=[[1e16,'경'],[1e12,'조'],[1e8,'억'],[1e4,'만']];
  for(var i=0;i<units.length;i++){
    if(n>=units[i][0]){
      var v=n/units[i][0];
      return (v>=100?v.toFixed(0):v.toFixed(2))+units[i][1];
    }
  }
  return Math.round(n).toLocaleString();
}

var chapters=Object.keys(KE).map(Number).sort(function(a,b){return a-b});
chapters.forEach(function(c){
  var o=document.createElement('option'); o.value=c; o.textContent=c+'챕터'; chSel.appendChild(o);
});
chSel.value=44;

function fillStages(){
  var c=+chSel.value, arr=KE[c]||[], keep=stSel.value;
  stSel.innerHTML='';
  arr.forEach(function(_,i){
    var o=document.createElement('option'); o.value=i; o.textContent=c+'-'+(i+1); stSel.appendChild(o);
  });
  stSel.value=(keep!=='' && keep<arr.length)?keep:0;
}

function calc(){
  var c=+chSel.value, si=+stSel.value;
  var ke=(KE[c]||[])[si]||0;
  var m=Math.max(0, +kpm.value||0);
  var baseMin=m*ke;                 // 캐릭터 기본경험치/분
  var treMin=(c>=20)?baseMin*EXP_RATE:0;  // 보물경험치/분
  document.getElementById('o_min').textContent=kor(treMin);
  document.getElementById('o_hr').textContent=kor(treMin*60);
  document.getElementById('o_day').textContent=kor(treMin*60*24);
  document.getElementById('o_week').textContent=kor(treMin*60*24*7);
  document.getElementById('o_base').textContent=kor(baseMin);
  document.getElementById('warn').innerHTML=(c<20)
    ? '<div class="warn">19챕터 이하에서는 여정의 보물 경험치를 획득할 수 없습니다.</div>' : '';
}

chSel.onchange=function(){fillStages();calc()};
stSel.onchange=calc; kpm.oninput=calc;
fillStages(); calc();
</script>
</body></html>'''

html=html.replace('__KE__', KE)
out=r'C:\Users\MhHs\여정의보물_경험치계산기.html'
open(out,'w',encoding='utf-8').write(html)
print('saved',out,len(html),'bytes')
