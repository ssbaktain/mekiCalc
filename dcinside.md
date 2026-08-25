# 디시인사이드 HTML 작성 가이드라인

**버전:** v1.0  
**용도:** 디시인사이드 글쓰기 HTML 모드에 붙여넣기 위한 실전 가이드  
**핵심 원칙:** 예쁜 HTML보다, 디시 에디터가 일부 스타일을 지워도 살아남는 HTML을 만든다.

---

## 1. 기본 전제

디시인사이드의 HTML 글쓰기는 일반 웹페이지 제작과 다르게 접근해야 한다.

디시 본문에 넣는 HTML은 전체 웹페이지가 아니라 **본문 안에 들어가는 HTML 조각**이다.  
따라서 아래와 같은 전체 문서 구조는 피하는 것이 좋다.

```html
<!DOCTYPE html>
<html>
<head>
<style>
...
</style>
</head>
<body>
...
</body>
</html>
```

대신 아래처럼 본문 조각만 작성한다.

```html
<div style="font-size:15px;line-height:1.75;color:#222;">
  <p>본문</p>
</div>
```

더 보수적으로는 `table` 기반 구조를 사용한다.

```html
<table width="100%" cellpadding="0" cellspacing="0" border="0">
  <tr>
    <td style="padding:18px;color:#222;font-size:15px;line-height:1.75;">
      본문
    </td>
  </tr>
</table>
```

---

## 2. 디시 HTML의 핵심 원칙

디시용 HTML의 핵심은 다음 한 문장으로 요약된다.

> **화려한 CSS가 아니라, 망가져도 읽히는 편집 디자인을 만든다.**

디시 에디터에서는 일부 CSS가 사라지거나, 모바일에서 레이아웃이 깨지거나, 수정 과정에서 스타일이 변형될 수 있다.  
따라서 HTML을 만들 때는 다음을 항상 전제로 둔다.

- 배경색이 사라져도 글자가 보여야 한다.
- 모바일에서 오른쪽으로 튀어나가지 않아야 한다.
- 긴 글이라도 읽기 쉽게 카드 단위로 쪼개야 한다.
- 과한 CSS 효과보다 안정적인 구조를 우선해야 한다.
- `linear-gradient`, `box-shadow`, `flex`, `grid` 같은 최신 CSS에 의존하지 않는다.

---

## 3. 비교적 안전한 태그

디시 글쓰기용 HTML에서는 아래 태그 중심으로 구성하는 것이 안전하다.

```html
<div>
<p>
<span>
<br>
<b>
<strong>
<i>
<u>
<a>
<img>
<table>
<tbody>
<tr>
<td>
<hr>
```

실전에서는 다음 조합이 가장 무난하다.

```text
div + table + p + span + br + b + a
```

이미지를 넣을 때는 `img`를 사용할 수 있지만, 반드시 모바일 대응 스타일을 넣는다.

```html
<img src="이미지주소" style="max-width:100%;height:auto;">
```

---

## 4. 피해야 할 태그

아래 태그는 디시 본문용 HTML에서 피하는 것이 좋다.

```html
<script>
<iframe>
<form>
<input>
<button>
<video>
<audio>
<canvas>
<svg>
<style>
<link>
<meta>
<html>
<head>
<body>
```

특히 다음은 사용하지 않는 것이 좋다.

- JavaScript
- 외부 CSS
- CDN
- Tailwind
- iframe
- form 요소
- 인터랙티브 UI
- 전체 HTML 문서 구조

디시 본문은 웹앱을 만드는 공간이 아니라, 읽기 좋은 게시글을 만드는 공간이라고 생각하는 편이 안전하다.

---

## 5. 가장 중요한 규칙: 배경이 날아가도 글자는 보여야 한다

디시 HTML에서 가장 흔한 문제 중 하나는 배경이 사라졌을 때 글자가 안 보이는 것이다.

위험한 예시는 아래와 같다.

```html
<div style="background:linear-gradient(135deg,#111827,#1d4ed8);color:#ffffff;">
  흰 글씨
</div>
```

`linear-gradient`나 `background` 속성이 제대로 적용되지 않으면 흰 배경 위에 흰 글씨가 남을 수 있다.  
그러면 제목이나 강조 문장이 아예 보이지 않는다.

따라서 디시용 HTML에서는 아래 조합을 기본으로 한다.

```html
<div style="background-color:#f3f6fb;color:#111827;">
  진한 글씨
</div>
```

### 권장

```text
밝은 배경 + 진한 글씨
```

### 비권장

```text
어두운 배경 + 흰 글씨
gradient 배경 + 흰 글씨
이미지 배경 + 흰 글씨
```

---

## 6. `table + bgcolor + inline style` 조합을 사용한다

디시용 HTML에서는 `div`보다 `table`이 더 안정적으로 보일 때가 많다.  
특히 카드형 레이아웃은 아래처럼 작성하는 것이 좋다.

```html
<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#f3f6fb" style="border-collapse:collapse;background-color:#f3f6fb;border:1px solid #d8dee9;">
  <tr>
    <td style="padding:18px;color:#111827;font-size:15px;line-height:1.75;">
      내용
    </td>
  </tr>
</table>
```

핵심은 `bgcolor`와 `background-color`를 같이 쓰는 것이다.

```html
bgcolor="#f3f6fb"
style="background-color:#f3f6fb;"
```

둘 중 하나가 무시되어도 다른 하나가 남을 가능성이 있다.

---

## 7. 모바일 기준으로 작성한다

디시 글은 PC보다 모바일에서 보는 경우가 많다.  
따라서 고정폭 레이아웃은 피한다.

### 비권장

```html
<table width="900">
```

```html
<div style="width:900px;">
```

### 권장

```html
<table width="100%">
```

```html
<div style="width:100%;">
```

이미지도 반드시 아래처럼 처리한다.

```html
<img src="이미지주소" style="max-width:100%;height:auto;">
```

---

## 8. 글자 크기와 줄간격

긴 글은 글자 크기와 줄간격이 중요하다.  
디시에서 너무 작은 글자는 읽기 어렵다.

### 추천값

```text
상단 라벨: 11~12px
제목: 22~26px
부제: 13~15px
본문: 15~16px
각주/출처: 12~13px
줄간격: 1.65~1.85
```

가장 무난한 본문 기본값은 아래와 같다.

```html
font-size:15px;
line-height:1.75;
```

긴 리포트형 글이라면 `line-height:1.75` 정도가 좋다.

---

## 9. 색상 조합

디시에서는 강한 색보다 밝은 배경, 진한 글자, 얇은 포인트 색이 안정적이다.

### 9.1 블루그레이 리포트

기술, AI, 미래학 글에 가장 무난하다.

```text
배경: #f3f6fb
테두리: #d8dee9
본문: #111827
보조 글자: #4b5563
포인트: #2563eb
```

### 9.2 아이보리 리포트

강조 문장, 결론 박스, 선언문에 적합하다.

```text
배경: #fff8ed
테두리: #ead7b7
본문: #1f2937
보조 글자: #6b5b45
포인트: #b45309
```

### 9.3 모노톤 리포트

논문 요약, 공지, 정보글에 적합하다.

```text
배경: #f7f7f7
테두리: #d4d4d4
본문: #171717
보조 글자: #525252
포인트: #404040
```

---

## 10. 추천 디자인 방향

디시에서 가장 세련된 방식은 웹페이지처럼 화려하게 꾸미는 것이 아니다.  
오히려 다음 조건을 만족하는 편이 더 세련되어 보인다.

- 모바일에서 안 깨짐
- 배경이 사라져도 글자가 보임
- 카드 단위로 내용이 정리됨
- 색상 수가 적음
- 출처가 정돈되어 있음
- 긴 글이지만 읽을 엄두가 남
- 과하게 블로그 같지 않음
- 디시 특유의 정보글 분위기를 유지함

추천 스타일은 다음과 같다.

```text
Magazine Report + Survival HTML
```

구성은 아래처럼 잡는다.

```text
[상단 제목 카드]
[요약 카드]
[섹션 1]
[섹션 2]
[섹션 3]
[강조 문장 박스]
[출처 박스]
```

---

## 11. 리포트형 글 구조

AI, 미래학, 기술 전망, 특이점 갤러리용 글에는 아래 구조가 적합하다.

```text
FUTURE REPORT
큰 제목
짧은 부제

요약:
이 글에서 말하려는 핵심 주장 3~5줄

01 · 문제 제기
02 · 핵심 주장
03 · 반론 또는 리스크
04 · 미래 시나리오
05 · 결론

마지막 강조 문장

참고 자료
```

긴 글일수록 문단을 길게 이어 쓰기보다, 카드 단위로 끊는 것이 좋다.

---

## 12. 기본 카드 패턴

### 12.1 상단 제목 카드

```html
<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#f3f6fb" style="border-collapse:collapse;background-color:#f3f6fb;border:1px solid #d8dee9;">
  <tr>
    <td style="padding:20px;color:#111827;font-family:Arial,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
      <div style="font-size:12px;letter-spacing:1px;color:#2563eb;font-weight:bold;">
        FUTURE REPORT · AGENTS · ROBOTICS · AI FATIGUE
      </div>
      <div style="font-size:25px;line-height:1.35;font-weight:bold;margin-top:8px;">
        AI가 현실까지 조작하는 시대,<br>
        인간은 어디로 도망갈 수 있을까?
      </div>
      <div style="font-size:14px;line-height:1.6;color:#4b5563;margin-top:10px;">
        에이전트, 로봇, AR, AI 피로, 그리고 아날로그 프리미엄에 대한 미래학적 메모
      </div>
    </td>
  </tr>
</table>
```

---

### 12.2 섹션 카드

```html
<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#ffffff" style="border-collapse:collapse;background-color:#ffffff;border:1px solid #e5e7eb;">
  <tr>
    <td style="padding:18px;color:#222;font-family:Arial,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;font-size:15px;line-height:1.75;">
      <div style="font-size:12px;color:#2563eb;font-weight:bold;letter-spacing:1px;">
        01 · 핵심 주장
      </div>
      <div style="font-size:19px;font-weight:bold;color:#111827;margin-top:6px;">
        인간의 마지막 일은 로봇 관리가 아니라 경계 설정이다
      </div>
      <p style="margin-top:12px;margin-bottom:0;">
        AI가 정보를 처리하고, 에이전트가 디지털 업무를 실행하고, 로봇이 물리적 작업을 수행할수록 인간은 직접 일하는 사람에서 예외, 책임, 신뢰, 의미를 관리하는 사람으로 이동한다.
      </p>
    </td>
  </tr>
</table>
```

---

### 12.3 강조 문장 박스

강조 박스는 반드시 밝은 배경과 진한 글씨를 사용한다.

```html
<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#fff8ed" style="border-collapse:collapse;background-color:#fff8ed;border:1px solid #ead7b7;">
  <tr>
    <td style="padding:20px;color:#1f2937;font-family:Arial,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;text-align:center;">
      <div style="font-size:20px;line-height:1.6;font-weight:bold;">
        AI를 잘 쓰는 사람일수록,<br>
        AI를 끄는 시간도 의식적으로 설계한다.
      </div>
    </td>
  </tr>
</table>
```

---

### 12.4 요약 박스

```html
<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#f7f7f7" style="border-collapse:collapse;background-color:#f7f7f7;border:1px solid #d4d4d4;">
  <tr>
    <td style="padding:16px;color:#171717;font-family:Arial,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;font-size:15px;line-height:1.75;">
      <b>요약:</b><br>
      AI의 진짜 wow 모먼트는 모델이 더 똑똑해지는 순간이 아니라, 인간의 의도와 현실 세계 사이의 마찰이 줄어드는 순간이다.
    </td>
  </tr>
</table>
```

---

### 12.5 출처 박스

```html
<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#f9fafb" style="border-collapse:collapse;background-color:#f9fafb;border:1px solid #e5e7eb;">
  <tr>
    <td style="padding:14px;color:#4b5563;font-family:Arial,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;font-size:12px;line-height:1.7;">
      <b>참고:</b><br>
      · Deloitte, State of AI in the Enterprise 2026<br>
      · IEA, Energy and AI<br>
      · Challenger, Gray & Christmas Job Cuts Report<br>
      · Omdia, AI Glasses Market Analysis
    </td>
  </tr>
</table>
```

출처는 너무 길게 넣지 않는 것이 좋다.  
본문 아래에 3~6개 정도만 정리하면 깔끔하다.

---

## 13. 전체 스타터 템플릿

아래 코드는 디시 글쓰기 HTML 모드에 바로 붙여넣을 수 있는 기본 뼈대다.

```html
<div style="font-family:Arial,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;font-size:15px;line-height:1.75;color:#222;">

<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#f3f6fb" style="border-collapse:collapse;background-color:#f3f6fb;border:1px solid #d8dee9;">
  <tr>
    <td style="padding:20px;color:#111827;">
      <div style="font-size:12px;letter-spacing:1px;color:#2563eb;font-weight:bold;">
        FUTURE REPORT
      </div>
      <div style="font-size:25px;line-height:1.35;font-weight:bold;margin-top:8px;">
        제목을 여기에 입력
      </div>
      <div style="font-size:14px;line-height:1.6;color:#4b5563;margin-top:10px;">
        부제를 여기에 입력
      </div>
    </td>
  </tr>
</table>

<br>

<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#f7f7f7" style="border-collapse:collapse;background-color:#f7f7f7;border:1px solid #d4d4d4;">
  <tr>
    <td style="padding:16px;color:#171717;font-size:15px;line-height:1.75;">
      <b>요약:</b><br>
      핵심 요약을 여기에 입력.
    </td>
  </tr>
</table>

<br>

<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#ffffff" style="border-collapse:collapse;background-color:#ffffff;border:1px solid #e5e7eb;">
  <tr>
    <td style="padding:18px;color:#222;font-size:15px;line-height:1.75;">
      <div style="font-size:12px;color:#2563eb;font-weight:bold;letter-spacing:1px;">
        01 · 섹션 제목
      </div>
      <div style="font-size:19px;font-weight:bold;color:#111827;margin-top:6px;">
        소제목
      </div>
      <p style="margin-top:12px;margin-bottom:0;">
        본문을 여기에 입력.
      </p>
    </td>
  </tr>
</table>

<br>

<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#fff8ed" style="border-collapse:collapse;background-color:#fff8ed;border:1px solid #ead7b7;">
  <tr>
    <td style="padding:20px;color:#1f2937;text-align:center;">
      <div style="font-size:20px;line-height:1.6;font-weight:bold;">
        마지막 강조 문장
      </div>
    </td>
  </tr>
</table>

<br>

<table width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#f9fafb" style="border-collapse:collapse;background-color:#f9fafb;border:1px solid #e5e7eb;">
  <tr>
    <td style="padding:14px;color:#4b5563;font-size:12px;line-height:1.7;">
      <b>참고:</b><br>
      · 참고 자료 1<br>
      · 참고 자료 2<br>
      · 참고 자료 3
    </td>
  </tr>
</table>

</div>
```

---

## 14. 디시 HTML 체크리스트

### 14.1 내용 체크

```text
□ 제목 카드가 흰 배경에서도 읽히는가?
□ 강조 문장이 배경 없이도 읽히는가?
□ 본문 글자 크기가 15px 이상인가?
□ 줄간격이 1.65 이상인가?
□ 출처가 너무 길지 않은가?
□ 문단이 너무 길게 이어지지 않는가?
□ 카드 단위로 내용이 끊겨 있는가?
□ 이미지가 있다면 max-width:100%가 들어갔는가?
```

### 14.2 HTML 체크

```text
□ script, iframe, style 태그를 쓰지 않았는가?
□ html, head, body 구조를 넣지 않았는가?
□ width:900px 같은 고정폭을 쓰지 않았는가?
□ background:linear-gradient에 의존하지 않는가?
□ color:white에 의존하지 않는가?
□ table width="100%"를 썼는가?
□ bgcolor와 background-color를 같이 썼는가?
□ 인라인 style만 사용했는가?
□ flex, grid, position에 의존하지 않는가?
```

### 14.3 디시 업로드 체크

```text
□ 원본 HTML을 따로 백업했다.
□ 디시 글쓰기에서 HTML 체크 후 붙여넣었다.
□ HTML 체크 해제 후 미리보기를 확인했다.
□ PC와 모바일에서 모두 확인했다.
□ 등록 전 제목 카드와 강조 박스가 보이는지 확인했다.
□ 수정 후 배경/글자색이 날아가지 않았는지 확인했다.
```

---

## 15. AI에게 디시용 HTML을 만들게 하는 프롬프트

아래 프롬프트를 사용하면 디시용으로 비교적 안정적인 HTML을 만들 수 있다.

```text
디시인사이드 글쓰기용 HTML 조각으로 작성해줘.

조건:
- html, head, body, style, script 태그 금지
- div, p, span, table, tr, td, br, b, a 정도만 사용
- 모든 CSS는 인라인 style만 사용
- 모바일 기준으로 width는 100% 사용
- 고정폭 px 레이아웃 금지
- linear-gradient, box-shadow, position, flex, grid 금지
- 어두운 배경 + 흰 글씨 금지
- 배경이 사라져도 글자가 보이게 밝은 배경 + 진한 글씨 사용
- table에는 bgcolor와 background-color를 둘 다 사용
- 제목 카드, 요약 카드, 섹션 카드, 강조 박스, 출처 박스로 구성
- 본문 font-size는 15px, line-height는 1.75
- 디시 HTML 체크박스에 붙여넣기 가능한 순수 HTML 조각만 출력
```

좀 더 세련된 리포트형 디자인이 필요하면 아래 조건을 추가한다.

```text
디자인 톤:
- 미래 리포트 / 웹진 / 노션 라이트 스타일
- 색상은 블루그레이, 화이트, 아이보리 중심
- 장식은 최소화
- 작은 라벨과 강한 소제목을 사용
- 긴 문단은 카드 단위로 끊어서 가독성을 높일 것
- 디시 게시글처럼 너무 과하게 블로그스럽지 않게 만들 것
```

---

## 16. 가장 세련된 실전 방향

현재 가능한 선에서 디시용 HTML을 가장 세련되게 만드는 방식은 아래 조합이다.

```text
table 기반
width 100%
밝은 배경
진한 글씨
짧은 인라인 CSS
카드형 구성
요약 박스
강조 문장 박스
출처 박스
모바일 우선
gradient 미사용
흰 글씨 미사용
```

디자인 방향은 다음과 같이 잡으면 좋다.

```text
상단: 밝은 블루그레이 제목 카드
본문: 흰색 카드 여러 개
강조: 아이보리 박스
출처: 회색 작은 박스
색상: 2~3개만 사용
장식: 거의 없음
텍스트: 강한 문장 중심
```

디시에서는 너무 화려한 디자인보다, 정보 밀도가 있고 읽기 쉬운 카드형 글이 더 잘 어울린다.

---

## 17. 최종 결론

디시인사이드 HTML은 자유도가 높아 보이지만, 실제로는 안정성이 더 중요하다.

가장 중요한 원칙은 다음과 같다.

> **디시용 HTML은 화려한 CSS가 아니라, 망가져도 읽히는 편집 디자인이다.**

좋은 디시 HTML은 다음 조건을 만족한다.

- 모바일에서 깨지지 않는다.
- 배경이 사라져도 글자가 보인다.
- 글자 수를 과하게 먹지 않는다.
- 긴 글도 카드 단위로 읽힌다.
- 출처와 결론이 정돈되어 있다.
- 디시 특유의 정보글 분위기를 해치지 않는다.

결론적으로, 디시 HTML의 베스트 프랙티스는 다음이다.

```text
밝은 배경 + 진한 글자 + table 기반 카드 + 짧은 인라인 CSS
```

이 방식이 현재 기준으로 가장 안전하고, 가장 세련되게 보인다.