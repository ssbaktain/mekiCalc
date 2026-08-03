"use strict";

const UNIT_SUFFIX = { PCT: "%", SEC: "초", LEVEL: "Lv", FLAT: "" };
const CUBE_KEY = "mekicalc.cube.v1";
const CONV_KEY = "mekicalc.conv.v1";
const RANGE_KEY = "mekicalc.ranges.v1";
const STATP_KEY = "mekicalc.statPresets.v1";
const CUBEP_KEY = "mekicalc.cubePresets.v1";
const BUFFP_KEY = "mekicalc.buffPresets.v1";
const ALLYP_KEY = "mekicalc.allyPresets.v1";
const RELIC_KEY = "mekicalc.relic.v1";          // 유물 작업 상태([{id,awaken,on}])
const RELICP_KEY = "mekicalc.relicPresets.v1";  // 유물 프리셋 {active, presets:{name:[rows]}}
const CACHE_ARTIFACTS = "mekicalc.cache.artifacts.v1";   // 유물 카탈로그(목록+슬롯임계, 정적)
// 즉시 페인트용 캐시(서버 대기 없이 화면 먼저 그림). 정적 데이터는 계정 무관, 데미지는 직전 값.
const CACHE_STATS = "mekicalc.cache.statDefs.v1";
const CACHE_META  = "mekicalc.cache.meta.v1";
const CACHE_ME    = "mekicalc.cache.me.v1";
const CACHE_DMG   = "mekicalc.cache.damage.v1";
const CACHE_TGTFACTOR = "mekicalc.cache.tgtfactor.v1";   // 직전 방어배수({tgt,factor}) — 즉시 페인트 시 신선값과 일치시켜 애니메이션 1회 유지
const CACHE_SKILLS = "mekicalc.cache.skills.v1";   // 스킬 탭 목록(즉시 페인트용, 직업별)
const CACHE_COMPANIONS = "mekicalc.cache.companions.v1";   // 동료 목록(정적, 즉시 표시용)

let statDefs = [];
let meta = { parts: [], grades: [], gradeKo: {} };
let myCube = loadCube();               // { 부위: { 윗잠|아랫잠: {grade, slots:[{stat,value}|null x3], locked:[bool x3], stack} } }
let myConv = loadJSON(CONV_KEY, {});   // 커스텀 딜 환산 statId -> 1단위당 %
let myRanges = loadJSON(RANGE_KEY, {});// 추천 옵션 범위 statId -> {enabled,lo,hi,noLow,noHigh}
let statPresets = loadJSON(STATP_KEY, null); // {active, presets:{name:{stats,job}}}
let cubePresets = loadJSON(CUBEP_KEY, null); // {active, presets:{name:cubeState}}
let buffPresets = loadJSON(BUFFP_KEY, null); // {active, presets:{name:{job, buffs:[id]|null}}}  buffs=null=전부 체크
// 버프 작업 상태(활성 프리셋의 현재 편집본). myBuffs=null이면 "전부 체크"(=현행/하위호환), []=전부 끔.
let myBuffJob = 1;
let myBuffs = null;
let myHitInterval = 0;   // 피격 주기(초). 페이크 반격·버프손실 계산용. 0=안 맞음(미적용). 스킬 프리셋에 저장.
if (buffPresets && buffPresets.presets && buffPresets.presets[buffPresets.active]) {
  const _bp = buffPresets.presets[buffPresets.active];
  myBuffJob = _bp.job || 0;
  myBuffs = Array.isArray(_bp.buffs) ? _bp.buffs : null;
  myHitInterval = _bp.hitInterval || 0;
}
let buffDirty = false;
// 동료(메인): 프리셋 {active, presets:{name:{companionId,grade,level,ratio}}}. companions=메인 동료 목록(bootstrap).
let allyPresets = loadJSON(ALLYP_KEY, null);
let allyDirty = false;
let companions = loadJSON(CACHE_COMPANIONS, []);
// 유물: 작업 상태 myRelic=[{id,awaken,on}], 프리셋 relicPresets. 카탈로그=목록+슬롯임계(bootstrap).
let artifactCatalog = loadJSON(CACHE_ARTIFACTS, { list: [], slotByAwakenSum: [] });
let myRelic = normalizeRelic(loadJSON(RELIC_KEY, null));   // { awaken:{id:0~5}, equipped:[id...] }
let relicPresets = loadJSON(RELICP_KEY, null);
let relicDirty = false;
const CACHE_MONDEF = "mekicalc.cache.targetCatalog.v1";   // 대상 카탈로그(챕터/스테이지 구조, 방어력 미포함, 정적)
let targetCatalog = loadJSON(CACHE_MONDEF, null);
let qualSet = new Set();               // 현재 직업에서 '딜 미환산'인 스탯 id 집합
const slotCache = {};                  // "부위|등급" -> slots[3][]

function loadJSON(key, def) { try { const v = JSON.parse(localStorage.getItem(key)); return v == null ? def : v; } catch { return def; } }
function saveJSON(key, val) { localStorage.setItem(key, JSON.stringify(val)); if (PRESET_KEYS.has(key)) syncUp(); }
function clone(o) { return JSON.parse(JSON.stringify(o)); }

// ── 계정/서버 동기화 상태 ──
const PRESET_KEYS = new Set([STATP_KEY, CUBEP_KEY, BUFFP_KEY, ALLYP_KEY, RELICP_KEY, CONV_KEY, RANGE_KEY]);
let me = { oauthEnabled: false, loggedIn: false, name: null, email: null };
let suppressSync = false;     // 서버에서 받아 적용하는 동안 push 막기(에코 방지)
let booting = true;           // 초기화 중에는 재계산(refreshQual·calcDamage)을 미뤄 맨 끝에서 1번만
let damageShown = false;      // 데미지 값이 한 번 표시됐는지(이후 갱신은 조용히 — '계산 중'·'오류' 텍스트 안 띄움)
// 홈 대시보드 상태(buildMenu가 최상위에서 renderHome을 호출하므로, 그 선언보다 먼저 초기화돼야 함 — TDZ 방지)
let lastDamage = null;        // 마지막 데미지 결과(홈 대시보드·대상별 절대 데미지용)
const HOME_TARGET_KEY = "mekicalc.home.target.v1";   // 'normal' | 'boss' (사용자가 누른 적 있으면 고정)
let homeTarget = null;        // 현재 선택된 히어로(일반/보스). null이면 데이터로 자동 선택.
let syncTimer = null;
function syncUp() {
  if (!me.loggedIn || suppressSync) return;
  clearTimeout(syncTimer);
  syncTimer = setTimeout(pushPresets, 1200);   // 디바운스
}
async function pushPresets() {
  if (!me.loggedIn) return;
  try { await fetch("/api/presets", { method: "PUT", headers: { "Content-Type": "application/json" }, body: bundleString() }); } catch {}
}
async function pullPresets() {
  if (!me.loggedIn) return;
  try {
    const r = await fetch("/api/presets");
    if (!r.ok) return;
    const data = await r.json();
    if (data && (data.statPresets || data.cubePresets)) {
      suppressSync = true;
      await applyBundle(JSON.stringify(data), false);   // 서버 동기화: 덮어쓰기(계정이 정본)
      suppressSync = false;
    } else {
      pushPresets();   // 서버가 비었으면 현재 로컬을 올려 시드
    }
  } catch {}
}
function cleanName(name) { return String(name || "").replace(/%$/, "").replace(/\(\+초\)$/, "").replace(/\(\+\)$/, "").trim(); }

// ──────────────────── 공통 ────────────────────

const $ = (id) => document.getElementById(id);
function fmt(n) { return Math.round(n).toLocaleString("ko-KR"); }

// 잠깐 떴다가 페이드아웃되는 알림(저장 등).
let toastTimer = null;
function toast(msg) {
  const t = $("toast");
  if (!t) return;
  t.textContent = msg;
  t.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove("show"), 1900);
}
// 적응형 소수 자리: 작은 값도 유효숫자 sig개가 보이도록(최소 2자리, 최대 8자리).
function pctDecimals(v, sig) {
  if (v === 0) return 2;
  const digits = Math.floor(Math.log10(Math.abs(v)));
  return Math.min(8, Math.max(2, sig - 1 - digits));
}
function pct(x, sig = 3) { if (x == null) return "—"; const v = x * 100; return v.toFixed(pctDecimals(v, sig)) + "%"; }
function signPct(x, sig = 3) { if (x == null) return "—"; const v = x * 100; return (v >= 0 ? "+" : "") + v.toFixed(pctDecimals(v, sig)) + "%"; }
function gradeKo(code) { return meta.gradeKo[code] ?? code; }

// 등급별 색(데스크톱 Theme.gradeColor, 다크 배경용으로 약간 밝게).
const GRADE_COLOR = {
  NORMAL: "#a7adb8", RARE: "#4d8bff", EPIC: "#a06bff",
  UNIQUE: "#e0a82e", LEGENDARY: "#2fbf6b", MYTHIC: "#ff5a5a",
};
function gradeColor(code) { return GRADE_COLOR[code] || "var(--text)"; }

// 부위 표시 순서(데스크톱 PART_ORDER와 동일). 모든 부위 드롭다운·카드에 통일 적용.
const PART_ORDER = ["모자", "망토", "상의", "어깨장식", "하의", "벨트", "장갑", "신발", "반지", "반지2", "목걸이", "눈장식", "얼굴장식", "귀고리"];
function partIdx(p) { const i = PART_ORDER.indexOf(p); return i < 0 ? 999 : i; }
function sortParts(arr) { return arr.slice().sort((a, b) => partIdx(a) - partIdx(b)); }
function gradeText(code) { return `<span class="grade-text" style="color:${gradeColor(code)}">${gradeKo(code)}</span>`; }

// 등급 select: 옵션마다 등급색, 접힌 표시도 선택 등급색.
function fillGradeSelect(sel) {
  sel.innerHTML = "";
  for (const g of meta.grades) {
    const o = new Option(gradeKo(g), g);
    o.style.color = gradeColor(g);
    o.style.background = "#262a33";
    sel.add(o);
  }
  applyGradeColor(sel);
  sel.addEventListener("change", () => applyGradeColor(sel));
}
function applyGradeColor(sel) { sel.style.color = gradeColor(sel.value); sel.style.fontWeight = "700"; }

// 큰 수의 한글 단위 환산(경/조/억/만, 유효숫자 3자리). 1만 미만은 빈 문자열. (데스크톱과 동일)
const KOR_UNITS = [[1e16, "경"], [1e12, "조"], [1e8, "억"], [1e4, "만"]];
function koreanUnit(v) {
  const a = Math.abs(v);
  if (v == null || !isFinite(a) || a < 1e4) return "";
  for (const [val, name] of KOR_UNITS) if (a >= val) return threeSig(v / val) + name;
  return "";
}
function threeSig(v) {
  if (v === 0) return "0";
  const digits = Math.ceil(Math.log10(Math.abs(v)));
  const p = Math.max(0, 3 - digits);
  return parseFloat(v.toFixed(p)).toString();
}

// 옵션 칩. opt={name,value,yellow,special,statId} 또는 null(빈). cls=before|after|same|locked.
function chip(opt, cls) {
  if (!opt) return `<span class="opt-chip empty">(비움)</span>`;
  const y = opt.yellow ? " yellow" : "";
  const star = opt.special ? "★ " : "";
  return `<span class="opt-chip ${cls}${y}">${star}${optLabel(opt)}</span>`;
}
function keyOf(o) { return (o.statId != null ? o.statId : o.name) + "|" + o.value; }

// 기존 옵션 묶음 → 새 옵션 묶음(하나의 세트가 다른 세트로 바뀌는 표현). 유지=중립, 빠짐=빨강, 추가=초록.
function bundleChange(curOpts, recOpts) {
  const curKeys = new Set(curOpts.map(keyOf));
  const recKeys = new Set(recOpts.map(keyOf));
  const before = curOpts.length
    ? curOpts.map((o) => chip(o, recKeys.has(keyOf(o)) ? "same" : "before")).join("")
    : `<span class="opt-chip empty">(없음)</span>`;
  const after = recOpts.length
    ? recOpts.map((o) => chip(o, curKeys.has(keyOf(o)) ? "same" : "after")).join("")
    : `<span class="opt-chip empty">(없음)</span>`;
  return `<div class="bundle-change"><div class="bundle">${before}</div>` +
    `<span class="arrow big">→</span><div class="bundle">${after}</div></div>`;
}

// {stat,value} → {name,value}(myCube 슬롯 → 칩용, 노랑 정보 없음).
function slotOpt(s) { return s ? { name: statLabel(s.stat), value: s.value, statId: s.stat } : null; }

// 슬롯 테이블에서 stat+value에 맞는 후보를 찾아 노랑/특옵 정보까지 채운 칩 옵션을 만든다.
function findDraw(table, stat, value) {
  const d = table.find((x) => x.statId === stat && x.value === value);
  return d ? { name: d.name, value: d.value, unit: d.unit, yellow: d.yellow, special: d.special, statId: d.statId }
           : { name: statLabel(stat), value, unit: statUnit(stat), yellow: false, special: false, statId: stat };
}
// part·grade의 슬롯들에 대해 {stat,value}[] → 노랑 정보 채운 옵션[](null 제외).
async function resolveOpts(part, grade, slotsArr) {
  const tables = await getSlots(part, grade);
  const out = [];
  for (let i = 0; i < slotsArr.length; i++) {
    if (slotsArr[i]) out.push(findDraw(tables[i] || [], slotsArr[i].stat, slotsArr[i].value));
  }
  return out;
}

// 효율 점수: 데스크톱과 동일하게 ×1,000,000 후 유효숫자 포맷.
function fmtScore(v) {
  if (v == null || !isFinite(v) || v === 0) return "0";
  const twoDown = Math.trunc(v * 100) / 100;          // 0.01 이상 → 소수 2자리 버림
  if (twoDown !== 0) return String(twoDown);
  const digits = Math.ceil(Math.log10(Math.abs(v)));  // 더 작으면 2 유효숫자
  const p = Math.max(0, 2 - digits);
  return String(Math.trunc(v * Math.pow(10, p)) / Math.pow(10, p));
}

function collectStats() {
  const stats = {};
  for (const input of document.querySelectorAll("#sub-build input[data-stat-id]")) {   // 헤더 레벨 + 폼 필드
    stats[input.dataset.statId] = statValueOf(input);   // 표시(버림)값이 아닌 계산용 풀값 우선
  }
  stats.JOB = currentJob();   // 직업 단일 소스(job-sel = myBuffJob)
  return stats;
}

// ── 직업 단일 소스 ─────────────────────────────────────────────────────────
// 계산 JOB(job-sel)·버프 작업상태(myBuffJob)를 항상 같은 값으로 동기화한다.
// 어느 화면에서 직업을 바꿔도 계산 JOB·스킬 목록·버프 선택이 함께 따라간다(직업 desync 버그 근본 해결).
function currentJob() { return parseInt($("job-sel").value, 10) || 0; }
// job으로 두 곳을 맞춘다. 직업이 실제로 바뀌면 myBuffs를 초기화(전부 체크)해 "버프는 항상 현재 직업용" 불변식 유지.
// 반환: 직업이 바뀌었는가(호출부가 스킬 목록 재렌더 여부 판단).
function setJob(job) {
  job = parseInt(job, 10) || 0;
  const changed = job !== myBuffJob;
  $("job-sel").value = String(job);
  myBuffJob = job;
  if (changed) myBuffs = null;   // 직업 변경 → 스킬 선택 초기화(불변식). 일관 상태(동일 직업)면 no-op이라 선택 보존.
  return changed;
}

// 계산에 보낼 버프 선택. null이면 서버가 전부 적용(현행/하위호환), []는 전부 끔, [...]는 그것만.
// myBuffs는 setJob 불변식으로 항상 현재 직업용 → 옛 job≠myBuffJob 폴백 가드 불필요.
function activeBuffs() { return myBuffs; }

async function postJson(url, payload) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const data = await res.json();
  if (data.error) throw new Error(data.error);
  return data;
}

function loadCube() {
  try { return JSON.parse(localStorage.getItem(CUBE_KEY)) || {}; } catch { return {}; }
}
// 큐브 편집은 saveCube()로 작업본(myCube)만 갱신 → '저장(프리셋)' 전까진 미저장 상태(dirty).
let cubeDirty = false;
function saveCube() { localStorage.setItem(CUBE_KEY, JSON.stringify(myCube)); cubeDirty = true; }

// 활성 큐브 프리셋으로 작업본을 되돌림(미저장 변경 폐기).
function revertCube() {
  myCube = clone((cubePresets && cubePresets.presets[cubePresets.active]) || {});
  localStorage.setItem(CUBE_KEY, JSON.stringify(myCube));
  cubeDirty = false;
  renderCube();
}
// 큐브 미저장 변경이 있으면 이동 전 확인. 진행(폐기) 시 true, 취소 시 false.
function canLeaveCube() {
  if (!cubeDirty) return true;
  if (confirm("저장하지 않은 큐브 변경이 있습니다.\n저장하지 않고 이동하면 변경이 되돌아갑니다. 계속할까요?")) {
    revertCube();
    return true;
  }
  return false;
}

// 스탯도 동일: '저장(프리셋)' 전 변경은 미저장(dirty).
let statDirty = false;
function revertStat() { spApply(statPresets.active); }   // 활성 스탯 프리셋으로 폼 복원(spApply가 dirty 해제)
function canLeaveStat() {
  if (!statDirty) return true;
  if (confirm("저장하지 않은 스탯 변경이 있습니다.\n저장하지 않고 이동하면 변경이 되돌아갑니다. 계속할까요?")) {
    revertStat();
    return true;
  }
  return false;
}

// 현재 보고 있는 편집기(스탯/큐브)의 미저장 이탈 가드.
let currentSub = "build";
let cubeNeedsRender = false;   // 큐브 데이터는 바뀌었지만 아직 화면 렌더 안 함(지연 로딩)
function canLeaveEditors() {
  if ($("panel-presets").hidden) return true;
  if (currentSub === "build") return canLeaveStat();   // 빌드=스탯+스킬(statDirty가 둘 다 커버)
  if (currentSub === "cube") return canLeaveCube();
  return true;
}

// 부위·등급의 슬롯 후보(캐시). slots[slotIdx] = [{statId,name,value,special,yellow}, …]
async function getSlots(part, grade) {
  const key = part + "|" + grade;
  if (!slotCache[key]) {
    const data = await postJson("/api/slotdraws", { part, grade });
    slotCache[key] = data.slots;
  }
  return slotCache[key];
}

// ──────────────────── 상단 메뉴(드롭다운) ────────────────────

// 기능 추가 시 여기 항목만 늘리면 됨. items 없으면 단독 메뉴, 있으면 드롭다운.
// 하위 항목 panel="soon"이면 준비 중 패널(soon 라벨 표시).
const MENU = [
  { label: "데미지 분석", panel: "home", path: "/" },   // 최좌측 · 드롭다운 없음 · 로고 클릭으로도 진입. 평타/DPS + 평타/스킬/동료 딜 비중 + 동료/유물 기여
  { label: "프리셋", flat: true, divider: true, items: [   // flat: 드롭다운X·클릭 시 캐릭터 탭 진입(서브바가 세부전환). divider: 뒤에 세로 구분선
    { label: "캐릭터", panel: "presets", sub: "build", path: "/presets/build" },
    { label: "동료", panel: "presets", sub: "ally", path: "/presets/ally" },
    { label: "유물", panel: "presets", sub: "relic", path: "/presets/relic" },
    { label: "큐브", panel: "presets", sub: "cube", path: "/presets/cube" },
    { label: "설정", panel: "presets", sub: "settings", path: "/presets/settings" },
  ]},
  { label: "큐브", flat: true, items: [   // flat: 드롭다운X·클릭 시 큐브 기댓값 진입(세부전환=각 큐브 패널 상단 서브바)
    { label: "큐브 기댓값", panel: "cube", path: "/cube" },
    { label: "옵션 비교", panel: "optcompare", path: "/cube/option-compare" },
    { label: "내 옵션 비교/변경", panel: "compare", path: "/cube/my-options" },
    { label: "우선순위 추천", panel: "recommend", path: "/cube/recommend" },
  ]},
  { label: "스탯 비교", panel: "statcompare", path: "/stat-compare" },   // 최상위 단독 · 스탯 등가 환산(한 스탯↔다른 스탯 필요량)
  { label: "장비 비교", panel: "gear", path: "/gear/compare" },   // 최상위 단독 · 프리셋 없이 단발 비교/변경
  { label: "공지사항", panel: "notices", path: "/notices" },      // 계산 아닌 첫 기능 · 넥슨 포럼 공지 크롤링·요약
];
const MENU_RIGHT = [];   // 설정은 프리셋 패널의 서브탭으로 이동

// 캐릭터 스탯이 있어야 계산되는 탭 — 미입력이면 블러+CTA 게이트(정상 렌더 스킵). 홈은 박스별로 따로 처리.
const CHAR_GATED_PANELS = new Set(["cube", "optcompare", "compare", "recommend", "statcompare", "gear"]);

function showPanel(name) {
  for (const p of document.querySelectorAll(".panel")) p.hidden = (p.id !== "panel-" + name);
  document.body.classList.toggle("notices-active", name === "notices");   // 공지 탭은 비영리 → 광고 비노출(CSS)
  if (CHAR_GATED_PANELS.has(name)) {   // 캐릭터 미입력 → 게이트. 내 옵션 비교·우선순위 추천은 큐브 프리셋도 필요.
    const reqs = [{ empty: characterEmpty(), msg: "계산하려면 캐릭터 스탯이 필요해요.<br>스탯을 입력하면 이 기능을 쓸 수 있어요.", cta: "캐릭터 입력하러 가기", path: "/presets/build" }];
    if (name === "compare" || name === "recommend") reqs.push({ empty: cubeEmpty(), msg: "큐브 프리셋이 아직 없어요.<br>큐브를 입력하면 이 기능을 쓸 수 있어요.", cta: "큐브 입력하러 가기", path: "/presets/cube" });
    if (applyGate($("panel-" + name), reqs)) return;
  }
  if (name === "home") renderHome();               // 홈 대시보드(로고로 진입)
  if (name === "compare") renderCompareEditor(true);
  if (name === "optcompare") renderOptCompare();
  if (name === "recommend") runRecommend(true);   // 진입 시 자동 계산 → 슬라이더 활성화
  if (name === "cube") calcEvaluate();             // 진입 시 자동 계산(계산 버튼 제거)
  if (name === "statcompare") renderStatCompare();  // 스탯 비교(등가 환산) UI 구성
  if (name === "gear") renderGear();               // 장비 비교 UI 구성
  if (name === "notices") renderNotices();         // 공지 탭(넥슨 포럼 크롤링·요약)
}

// ──────────────────── 라우팅(SPA) ────────────────────
// 각 탭에 진짜 URL 부여: 크롤러가 발견·색인 가능하고, 뒤로가기·북마크·공유도 된다.
// 메뉴 UX는 그대로 두되(버튼), 클릭을 navigate로 태워 주소를 갱신하고, 직접 URL 진입은 서버 SPA 폴백 + 초기 라우트가 처리.
let routeItemEls = new Map();   // path → 데스크톱 최상위 .menu-item(활성 표시용)

function allLeaves() {
  const out = [];
  for (const m of MENU) { if (m.items) for (const it of m.items) out.push(it); else out.push(m); }
  return out;
}
function leafByPath(path) { return allLeaves().find((l) => l.path === path) || null; }

// 구 경로 호환: 스탯/스킬 프리셋이 '캐릭터'(빌드)로 통합됨 → 옛 북마크·링크를 새 경로로 정규화.
const PATH_REDIRECTS = { "/presets/stat": "/presets/build", "/presets/skill": "/presets/build" };
function normPath(path) { return PATH_REDIRECTS[path] || path; }

// 라우트별 문서 제목·설명(크롤러가 렌더 시 반영, 유저 탭 제목도 갱신).
const ROUTE_META = {
  "/":                    ["MekiCalc - 메이플 키우기 스탯 효율 계산기", "메이플 키우기 스탯·딜 효율 계산기. 예상 데미지·DPS, 큐브 잠재능력 기댓값, 동료 기여를 현재 스탯 기준으로 계산합니다."],
  "/cube":                ["큐브 잠재능력 기댓값 - MekiCalc", "메이플 키우기 큐브 잠재능력 옵션별 딜 증가량과 큐브 한 개당 기댓값을 계산합니다."],
  "/cube/option-compare": ["큐브 옵션 비교 - MekiCalc", "두 큐브 잠재 옵션 조합의 딜 기여를 나란히 비교합니다."],
  "/cube/my-options":     ["내 큐브 옵션 비교·변경 - MekiCalc", "현재 장착 큐브 옵션과 바꿀 옵션의 딜 차이를 비교하고 반영합니다."],
  "/cube/recommend":      ["큐브 우선순위 추천 - MekiCalc", "현재 스탯 기준으로 어떤 큐브 옵션부터 챙기는 게 효율적인지 순위로 추천합니다."],
  "/stat-compare":        ["스탯 비교 - MekiCalc", "한 스탯에 값을 입력하면 그만큼의 딜을 내려면 다른 스탯이 얼마나 필요한지 등가 증가량을 현재 스탯 기준으로 계산합니다."],
  "/gear/compare":        ["장비 비교 - MekiCalc", "현재 장착 장비와 새 장비의 옵션을 입력해 어느 쪽이 딜에 더 유리한지 바로 비교합니다. 무기 공격력·부옵션(크리 데미지·데미지 등) 차이를 현재 스탯 기준으로 계산합니다."],
  "/notices":             ["공지사항 - 메이플 키우기 공지·패치노트·이벤트 | MekiCalc", "메이플 키우기 공식 공지사항·패치노트·이벤트·확인 중인 현상을 요약과 함께 한 곳에서 보고, 현재 사용 가능한 쿠폰을 확인합니다."],
};
function applyRouteMeta(path) {
  const m = ROUTE_META[path];
  document.title = m ? m[0] : "MekiCalc - 메이플 키우기 스탯 효율 계산기";
  const desc = document.querySelector('meta[name="description"]');
  if (desc && m) desc.setAttribute("content", m[1]);
  setRobots((path || "").startsWith("/presets"));   // 프리셋(개인 데이터) 화면은 색인 제외
  const canon = document.querySelector('link[rel="canonical"]');
  if (canon) canon.setAttribute("href", "https://mekicalc.com" + (path && path !== "/" ? path : "/"));
}
function setRobots(noindex) {
  let tag = document.querySelector('meta[name="robots"]');
  if (noindex) {
    if (!tag) { tag = document.createElement("meta"); tag.name = "robots"; document.head.appendChild(tag); }
    tag.setAttribute("content", "noindex");
  } else if (tag) { tag.remove(); }
}
function syncMobileActive(leaf) {
  if (typeof mobileMenuEls !== "undefined") mobileMenuEls.forEach((x) => x.btn.classList.toggle("active", x.leaf === leaf));
}

// 실제 패널 적용(공용). push=true면 주소창 URL도 갱신(pushState).
function applyLeaf(leaf, item, push) {
  const staysInPresets = leaf.panel === "presets" && !leaf.sub;
  if (!staysInPresets && !canLeaveEditors()) return false;
  closeAllModals();   // 탭 이동 시 열린 팝업(내보내기·불러오기·선택)·잔존 선택 정리
  closeMenus();
  item = item || routeItemEls.get(leaf.path);
  if (item) setActiveMenu(item);
  syncMobileActive(leaf);
  showPanel(leaf.panel);
  if (leaf.sub) selectSub(leaf.sub);
  applyRouteMeta(leaf.path);
  if (push && leaf.path) history.pushState({ p: leaf.path }, "", leaf.path);
  return true;
}
// 주소(path)로 이동. push=false는 초기 진입·뒤로가기(주소 안 바꿈).
function navigate(path, push) {
  const leaf = leafByPath(normPath(path)) || MENU[0];   // 구 경로면 정규화(캐릭터 탭으로)
  applyLeaf(leaf, null, push);
}

// 홈(=예상 데미지 탭)으로 이동. 로고 클릭·기본 진입 공용.
function goHome() {
  if (!applyLeaf(MENU[0], null, true)) return;
  window.scrollTo({ top: 0 });
}

// 현재 보이는 패널의 계산을 다시 돌린다(프리셋 전환 시 즉시 반영용). 예상 데미지 카드는 항상 갱신.
function recalcActivePanel() {
  calcDamage();
  if (currentSub === "ally") calcDamage();   // 동료 탭: 스탯/프리셋 바뀌면 홈 예상 데미지 갱신
  if (!$("panel-cube").hidden) calcEvaluate();
  else if (!$("panel-recommend").hidden) runRecommend(true);
  else if (!$("panel-compare").hidden) refreshCompareOnPresetChange();
  else if (!$("panel-optcompare").hidden) runOptCompare();
  else if (!$("panel-gear").hidden && $("gc-delta").innerHTML.trim()) runGearCompare();   // 결과가 떠 있을 때만 재계산
  else if (!$("panel-statcompare").hidden && scSource) runStatCompare();   // 기준 스탯이 있으면 새 스탯으로 재환산
}

// 내 옵션 비교: 프리셋 전환 시 좌측(현재 큐브)·결과를 갱신하되 우측(가정) 선택은 보존.
async function refreshCompareOnPresetChange() {
  const hadResult = $("cmp-diff").innerHTML.trim() !== "";
  const saved = [0, 1, 2].map((i) => { const s = $("cmp-right-" + i); return s ? s.value : ""; });
  await renderCompareEditor(false);   // 좌측=새 큐브 반영, 등급/스택 보존(우측 슬롯은 재생성됨)
  saved.forEach((v, i) => { const s = $("cmp-right-" + i); if (s && v && [...s.options].some((o) => o.value === v)) s.value = v; });
  if (hadResult) runCompare();   // 결과가 떠 있었으면 새 스탯·큐브로 다시 계산
}

function closeMenus() { document.querySelectorAll(".menu-item.open").forEach((i) => i.classList.remove("open")); }
function setActiveMenu(item) {
  document.querySelectorAll(".menu-item").forEach((i) => i.classList.remove("active"));
  item.classList.add("active");
}

// 메뉴 클릭 진입점 → 공용 라우터로 위임(주소 갱신 포함).
function selectLeaf(leaf, item) { applyLeaf(leaf, item, true); }

// 프리셋 패널 안의 서브탭 선택. 쿼리는 #panel-presets로 한정 — 큐브 페이지의 route-link 서브탭(.cube-subtabs)까지
// 건드리면 안 됨(그건 정적 active + 라우터가 담당). 전역 .subtab로 잡으면 큐브 앵커에 active가 잘못 퍼짐.
function selectSub(name) {
  currentSub = name;
  document.querySelectorAll("#panel-presets .subtab").forEach((b) => b.classList.toggle("active", b.dataset.sub === name));
  document.querySelectorAll(".subpanel").forEach((p) => { p.hidden = (p.id !== "sub-" + name); });
  if (name === "cube" && cubeNeedsRender) { cubeNeedsRender = false; renderCube(); }   // 지연 렌더
  if (name === "build" && !booting) renderBuffs();   // 빌드 화면 스킬 목록 최신화(초기 pre-set 땐 미룸 — init 끝 refreshSkillData가 처리)
  if (name === "ally") renderAlly();
  if (name === "relic") renderRelic();
}
for (const b of document.querySelectorAll("#panel-presets .subtab")) {   // 프리셋 서브탭만(큐브 route-link 서브탭 제외)
  b.addEventListener("click", () => {
    if (b.dataset.sub !== currentSub && !canLeaveEditors()) return;   // 다른 서브탭으로 갈 때 미저장 가드
    // 서브탭도 진짜 경로가 있으므로 navigate로 태워 URL·상단 메뉴 활성까지 동기화(뒤로가기/북마크 정합).
    const leaf = allLeaves().find((l) => l.panel === "presets" && l.sub === b.dataset.sub);
    if (leaf) navigate(leaf.path, true); else selectSub(b.dataset.sub);
  });
}

function makeMenuItem(m) {
  const item = document.createElement("div");
  item.className = "menu-item" + (m.boxed ? " boxed" : "");
  const label = document.createElement("button");
  label.className = "menu-label";
  label.type = "button";
  label.textContent = m.label;
  item.appendChild(label);

  if (m.items && !m.flat) {
    label.insertAdjacentHTML("beforeend", ' <span class="caret">▾</span>');
    const dd = document.createElement("div");
    dd.className = "menu-dropdown";
    for (const it of m.items) {
      const sub = document.createElement("button");
      sub.className = "menu-sub";
      sub.type = "button";
      sub.textContent = it.label;
      sub.addEventListener("click", (e) => { e.stopPropagation(); selectLeaf(it, item); });
      dd.appendChild(sub);
    }
    item.appendChild(dd);
    // 드롭다운은 .open으로만 제어(호버/클릭 둘 다 JS로 → 동시에 두 개 뜨는 버그 방지).
    item.addEventListener("mouseenter", () => { closeMenus(); item.classList.add("open"); });
    label.addEventListener("click", (e) => {
      e.stopPropagation();
      const wasOpen = item.classList.contains("open");
      closeMenus();
      if (!wasOpen) item.classList.add("open");
    });
  } else {
    // flat(프리셋): 드롭다운 없이 클릭 시 바로 진입(첫 서브=캐릭터). 세부 전환은 패널 내부 서브바가 담당.
    const target = m.items ? m.items[0] : m;
    label.addEventListener("click", (e) => { e.stopPropagation(); selectLeaf(target, item); });
  }
  return item;
}

function buildMenu() {
  const bar = $("menubar");
  bar.innerHTML = "";
  const left = document.createElement("div"); left.className = "menu-group";
  const right = document.createElement("div"); right.className = "menu-group";
  const itemEls = [];
  routeItemEls = new Map();
  for (const m of MENU) {
    const el = makeMenuItem(m); left.appendChild(el); itemEls.push([m, el]);
    if (m.items) for (const it of m.items) routeItemEls.set(it.path, el);
    else if (m.path) routeItemEls.set(m.path, el);
    if (m.divider) { const d = document.createElement("span"); d.className = "menu-divider"; d.setAttribute("aria-hidden", "true"); left.appendChild(d); }
  }
  for (const m of MENU_RIGHT) right.appendChild(makeMenuItem(m));
  bar.appendChild(left);
  bar.appendChild(right);
  bar.addEventListener("mouseleave", closeMenus);   // 메뉴바 벗어나면 드롭다운 닫기
  buildMobileMenu(itemEls);   // 모바일 햄버거 메뉴(좁은 화면용)
  // 프리셋 서브탭은 미리 빌드로 세팅.
  selectSub("build");
  // 활성 메뉴는 '현재 경로' 기준으로 처음부터 설정. 무조건 첫 항목(예상 데미지)을 활성화하면
  // 이후 라우팅이 공지사항 등으로 바꿔 '예상 데미지 → 공지사항' 메뉴 깜빡임이 생김(딥링크 진입).
  const menuLeaf = leafByPath(location.pathname) || (location.pathname.startsWith("/notices") ? leafByPath("/notices") : null);
  const activeItem = (menuLeaf && routeItemEls.get(menuLeaf.path)) || left.querySelector(".menu-item");
  if (activeItem) setActiveMenu(activeItem);
  syncMobileActive(menuLeaf || MENU[0]);   // 모바일 메뉴 활성도 현재 경로 기준(깜빡임 방지)
  // 홈 패널은 현재 경로가 홈일 때만 노출(딥링크면 여기서 홈으로 덮지 않음 → 패널 깜빡임 방지).
  if (location.pathname === "/" || location.pathname === "") showPanel("home");
}

// 모바일(좁은 화면) 햄버거 메뉴. 모든 항목을 펼친 세로 목록으로 → 한 번 터치로 이동(호버 불필요).
let mobileMenuEls = [];   // {leaf, btn, desktopItem} — 활성 표시 동기화용
function buildMobileMenu(itemEls) {
  const bar = $("menubar");
  mobileMenuEls = [];

  const burger = document.createElement("button");
  burger.className = "hamburger"; burger.type = "button";
  burger.setAttribute("aria-label", "메뉴 열기");
  burger.innerHTML = "<span></span><span></span><span></span>";

  const panel = document.createElement("div");
  panel.className = "mobile-menu";

  // 계정(로그인/로그아웃·상태) — 드로어 맨 위. renderHeaderAccount가 내용을 채움.
  const acct = document.createElement("div");
  acct.id = "m-account"; acct.className = "m-account";
  panel.appendChild(acct);

  const closeDrawer = () => { panel.classList.remove("open"); burger.classList.remove("active"); };

  const addLeaf = (label, leaf, desktopItem, isTop) => {
    const btn = document.createElement("button");
    btn.className = "m-leaf" + (isTop ? " m-leaf-top" : "");   // isTop=최상위 단독(프리셋)=대분류 크기
    btn.type = "button"; btn.textContent = label;
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      closeDrawer();
      selectLeaf(leaf, desktopItem);   // 미저장 가드·패널 전환은 기존 로직 재사용
      mobileMenuEls.forEach((x) => x.btn.classList.toggle("active", x.btn === btn));
    });
    panel.appendChild(btn);
    mobileMenuEls.push({ leaf, btn, desktopItem });
  };

  for (const [m, el] of itemEls) {
    if (m.items) {
      const head = document.createElement("div");
      head.className = "m-group-title"; head.textContent = m.label;
      panel.appendChild(head);
      for (const it of m.items) addLeaf(it.label, it, el, false);   // 소분류
    } else {
      addLeaf(m.label, m, el, true);   // 최상위 단독(프리셋) = 대분류
    }
  }

  burger.addEventListener("click", (e) => {
    e.stopPropagation();
    const open = panel.classList.toggle("open");
    burger.classList.toggle("active", open);
  });
  panel.addEventListener("click", (e) => e.stopPropagation());
  document.addEventListener("click", closeDrawer);   // 바깥 터치 시 닫기

  // 햄버거·드로어는 헤더 우상단에 배치(모바일에서 로그인 자리를 대체). 데스크톱에선 CSS로 숨김.
  const header = document.querySelector("header") || bar;
  header.appendChild(burger);
  header.appendChild(panel);
}
document.addEventListener("click", closeMenus);
buildMenu();

// 뒤로/앞으로 가기 → 해당 라우트 패널로(주소는 브라우저가 이미 바꿈).
window.addEventListener("popstate", () => navigate(location.pathname, false));
// 크롤러 발견용 내부 링크(푸터 사이트맵 등) — 앱 라우트는 새로고침 없이 이동, 외부/정적 페이지는 기본 동작.
document.addEventListener("click", (e) => {
  const a = e.target.closest("a.route-link");
  if (!a) return;
  if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;   // 새 탭 등은 기본 동작 유지
  const path = a.getAttribute("href");
  if (!path || !leafByPath(path)) return;   // 라우트가 아니면(정적 guide 등) 기본 이동
  e.preventDefault();
  navigate(path, true);
});

// 브랜드(로고+타이틀) 클릭 → 홈 대시보드로.
const brandEl = document.querySelector("header .brand");
if (brandEl) brandEl.addEventListener("click", goHome);

// 인앱(임베디드) 브라우저 감지 → 구글 로그인이 차단되므로 외부 브라우저로 열도록 안내.
(function inAppBrowserNotice() {
  const ua = navigator.userAgent || "";
  const isKakao = /KAKAOTALK/i.test(ua);
  const isInApp = isKakao || /(FBAN|FBAV|Instagram|Line\/|NAVER|DaumApps|; wv\)|Snapchat)/i.test(ua);
  const banner = $("inapp-banner");
  if (!isInApp || !banner) return;
  banner.hidden = false;
  $("inapp-close").addEventListener("click", () => { banner.hidden = true; });
  $("inapp-open").addEventListener("click", () => {
    const url = location.href;
    if (isKakao) {
      location.href = "kakaotalk://web/openExternal?url=" + encodeURIComponent(url);   // 카카오톡: 시스템 브라우저로
    } else if (/Android/i.test(ua)) {
      location.href = "intent://" + location.host + location.pathname + location.search +
        "#Intent;scheme=https;package=com.android.chrome;end";                          // 안드로이드: 크롬 인텐트
    } else {
      if (navigator.clipboard) navigator.clipboard.writeText(url).catch(() => {});
      toast("주소를 복사했어요. Safari·크롬에 붙여넣어 열어주세요.");
    }
  });
})();

// 직업 변경: '딜 미환산' 표시 갱신 + 예상 데미지 재계산 + 미저장 표시.
$("job-sel").addEventListener("change", () => {   // 직업 바뀌면 방어력→주스탯 계수 재파생 + 스킬 직업/목록도 동기
  setJob($("job-sel").value); buffDirty = true;
  statDirty = true; deriveStatProp(); refreshQual();
  renderBuffs();   // 스킬 목록도 새 직업으로(숨겨진 스킬 화면도 미리 갱신)
  calcDamage();
});


// ──────────────────── 스탯 입력 폼 ────────────────────

async function loadStats(pre) {
  try {
    statDefs = pre || await (await fetch("/api/stats")).json(); saveJSON(CACHE_STATS, statDefs); renderForm();
    if (!$("panel-statcompare").hidden) renderStatCompare();   // 딥링크로 먼저 들어온 경우 스탯 로드 후 채워줌
  }
  catch (e) { $("stat-form").textContent = "스탯 목록을 불러오지 못했습니다: " + e; }
}

function renderForm() {
  const groups = new Map();
  let levelDef = null;
  for (const d of statDefs) {
    if (d.id === "JOB") continue;   // 직업은 상단 선택기에서
    if (d.id === "LEVEL") { levelDef = d; continue; }   // 레벨은 상단 공용 헤더로(직업·대상 옆)
    if (!groups.has(d.group)) groups.set(d.group, []);
    groups.get(d.group).push(d);
  }
  const slot = $("build-level-slot");   // 레벨: 헤더 슬롯. data-stat-id 동일 → 아래 핸들러/collectStats가 함께 처리
  if (slot) slot.innerHTML = levelDef
    ? `<label class="ctl inline">레벨<input type="number" id="f_${levelDef.id}" data-stat-id="${levelDef.id}" value="0" step="any" /></label>` : "";
  const form = $("stat-form");
  form.innerHTML = "";
  for (const [group, defs] of groups) {
    const box = document.createElement("div");
    box.className = "group";
    box.innerHTML = `<div class="group-title">${group}</div>`;
    const grid = document.createElement("div");
    grid.className = "field-grid";
    for (const d of defs) {
      const field = document.createElement("div");
      field.className = "field";
      field.dataset.statId = d.id;
      field.innerHTML =
        `<div class="field-head"><label for="f_${d.id}">${d.name}</label>` +
        `<button type="button" class="qual-info" hidden aria-label="데미지 미환산 스탯" data-tip="데미지 미환산 스탯">ⓘ</button></div>` +
        `<div class="input-wrap"><input type="number" id="f_${d.id}" data-stat-id="${d.id}" value="0" step="any" />` +
        `<span class="unit">${UNIT_SUFFIX[d.unit] ?? ""}</span></div>`;
      grid.appendChild(field);
    }
    box.appendChild(grid);
    form.appendChild(box);
  }
  // 스탯 값 변경 시: (파생 트리거면)스탯 비례 데미지 즉시 반영 + 표기 버림 + 미저장 표시 + 자동 재계산
  // #sub-build 범위 = 헤더 레벨 + 폼 필드 모두 커버(레벨을 헤더로 옮겼어도 동일 핸들러 적용)
  for (const input of document.querySelectorAll("#sub-build input[data-stat-id]")) {
    const id = input.dataset.statId;
    input.addEventListener("input", () => { delete input.dataset.full; });   // 유저가 직접 편집 → 보존한 풀값 폐기, 표기값 신뢰
    if (STAT_PROP_TRIGGERS.has(id)) input.addEventListener("input", deriveStatProp);   // 입력 즉시 갱신
    input.addEventListener("change", () => {
      truncStatInput(input);                              // 게임 표기 규칙(소수 2자리부터 버림)
      if (STAT_PROP_TRIGGERS.has(id)) deriveStatProp();   // 트리거값 확정 시 재파생
      statDirty = true; calcDamage();
    });
  }
  applyQualMarkers();
}

// 게임 스탯 표기 규칙(전역): +옵션(FLAT·LEVEL)은 자연수, %·초는 소수 1자리까지, 소수 2자리부터는 버림(내림).
function truncStat(v, unit) {
  if (v == null || !isFinite(v)) return 0;
  if (unit === "PCT" || unit === "SEC") return Math.trunc(v * 10) / 10;
  return Math.trunc(v);
}
function statDefById(id) { return (statDefs || []).find((d) => d.id === id); }
function truncStatInput(input) {
  const v = parseFloat(input.value);
  if (isNaN(v)) return;
  const def = statDefById(input.dataset.statId);
  input.value = truncStat(v, def ? def.unit : "FLAT");
}

// 최종뎀% 등은 게임 내부 곱연산 라운딩을 역산할 수 없어(넥슨 공식: 스탯창 표기대로 적용) 표기값은 소수 1자리로 두되,
// 옵션 비교/변경으로 엔진이 계산한 결과는 풀 precision을 dataset.full에 숨겨 보존한다(반복 변경 시 반올림 누적 방지).
// 화면(input.value)=게임 표기(버림), 계산 원본(collectStats)=full 우선. 유저가 직접 편집하면 full을 폐기해 표기값을 신뢰.
function setStatValue(input, v, unit) {
  if (v == null || !isFinite(v)) { input.value = "0"; delete input.dataset.full; return; }
  input.value = truncStat(v, unit);   // 화면: 게임 표기(소수 1자리 버림)
  input.dataset.full = String(v);     // 내부: 풀 precision 보존
}
function statValueOf(input) {
  const f = input.dataset.full;
  if (f != null && f !== "") { const n = parseFloat(f); if (isFinite(n)) return n; }
  const v = parseFloat(input.value);
  return isNaN(v) ? 0 : v;
}

// 스탯 비례 데미지%를 주스탯·부스탯에서 즉시 파생: 유효주스탯/100 + 부스탯×0.25% + 방어력환산주스탯/100.
// 유저 입력을 막지 않되(직접 수정 가능), 아래 값을 건드리면 이 값으로 갱신한다.
// LEVEL·SKILL_LEVELS·SKILL_LEVEL_2는 방어력→주스탯 변환율(defenseToMainRatio)을 바꾸므로 함께 트리거해야 stale 방지.
const STAT_PROP_TRIGGERS = new Set(["MAIN_STAT_FLAT", "MAIN_STAT_PCT", "SUB_STAT_FLAT", "DEFENSE", "LEVEL", "SKILL_LEVELS", "SKILL_LEVEL_2"]);

// 방어력→주스탯 변환 계수(방어력 1당 주스탯). DamageModel.defenseToMainRatio와 동일 공식 — 백엔드와 함께 수정할 것.
// 다크나이트 아이언 월(2차 패시브): base 10% × (1 + 0.3%/레벨 × 총스킬레벨). 총스킬레벨 = 2차 레벨 파생분 + 모스렙 + 2스렙.
function defenseToMainRatio(num) {
  const job = parseInt(($("job-sel") && $("job-sel").value) || "0", 10) || 0;
  if (job !== 2 && job !== 3) return 0;   // 다크나이트(아이언 월)·비숍(인빈서블) — 동일 계수
  const lv = num("LEVEL");
  const baseSkillLevel = Math.max(0, (Math.min(lv, 60) - 30) * 3);   // 2차 스킬: 30~59렙 레벨당 3, 60렙~ 고정 90
  const totalSkillLevel = baseSkillLevel + num("SKILL_LEVELS") + num("SKILL_LEVEL_2");
  return 0.10 * (1 + 0.003 * totalSkillLevel);
}

function deriveStatProp() {
  const scope = $("sub-build"); if (!scope) return;   // 레벨 입력은 stat-form 밖(build-level-slot)에 있어 sub-build 전체를 조회 — 안 그러면 num("LEVEL")=0으로 baseSkillLevel이 늘 0
  const num = (id) => { const el = scope.querySelector(`input[data-stat-id="${id}"]`); return el ? statValueOf(el) : 0; };   // 표시(버림)값이 아닌 풀값 기준 파생
  const out = scope.querySelector('input[data-stat-id="STAT_PROP_DMG"]'); if (!out) return;
  const mainStat = num("MAIN_STAT_FLAT") * (1 + num("MAIN_STAT_PCT") / 100);
  const defEffMain = num("DEFENSE") * defenseToMainRatio(num);   // 아이언 월 주스탯 증가분: 선반영이라 주스탯% 곱셈 바깥의 최종 flat 값(주스탯% 미적용)
  setStatValue(out, mainStat / 100 + num("SUB_STAT_FLAT") * 0.0025 + defEffMain / 100, "PCT");   // 표시는 버림, 계산은 풀값
}

// 현재 qualSet에 맞춰 각 필드의 '데미지 미환산' i 정보 버튼 표시/숨김.
function applyQualMarkers() {
  for (const field of $("stat-form").querySelectorAll(".field")) {
    const info = field.querySelector(".qual-info");
    if (info) info.hidden = !qualSet.has(field.dataset.statId);
  }
}

// 직업·환산에 따라 '딜 미환산' 스탯 목록을 갱신.
async function refreshQual() {
  try {
    const data = await postJson("/api/qualitative", { job: parseInt($("job-sel").value, 10) || 0, conversions: myConv });
    qualSet = new Set(data.ids || []);
  } catch (e) { qualSet = new Set(); }
  applyQualMarkers();
}

// ──────────────────── 예상 데미지 ────────────────────

// 데미지 표시(즉시 페인트·실계산 공용). data = {normal, boss, detail:{normal:{min,max,avg,dps}, boss:{...}}}.
function showDamage(data) {
  lastDamage = data;
  renderHome();   // 방어배수(targetFactor)는 호출 전에 갱신돼 있다고 가정(calcDamage가 병렬로 미리 받음)
}

// 현재 대상(일반/보스) — 선택된 스테이지에서 결정(보스 스테이지=보스몹, 일반 스테이지=일반몹).
// 딜 구성 탭은 이 값을 dealMob 기본으로만 쓰고, 토글로 일몹↔보스를 자유 전환한다.
function currentTarget() {
  const t = currentTargetSel() || targetOfActivePreset();
  return (t && t.stage === "boss") ? "boss" : "normal";
}

// 선택 몹의 방어배수(서버 계산, 방어력 수치 미노출). 표시 딜에 곱해 '해당 몹 기준' 데미지로.
// 방어배수: 일반몹·보스 각각(딜 구성 탭의 일몹/보스 토글이 각 대상 방어력까지 반영하도록 둘 다 미리 구함).
let targetFactorNormal = 1, targetFactorBoss = 1;
function factorFor(mob) { return mob === "boss" ? targetFactorBoss : targetFactorNormal; }
// 보스만 선택된 프리셋이면 일반몹 기준은 그 챕터 마지막 일반 스테이지로.
function lastNormalStage(ch) {
  const c = targetCatalog && targetCatalog.chapters[ch];
  if (!c || !c.stages || !c.stages.length) return "boss";
  const mx = c.stages.slice().sort((a, b) => +b - +a)[0];
  return "stage:" + mx;
}
async function refreshTargetFactor() {
  const t = currentTargetSel();
  const ch = t && t.ch, sv = t && t.stage;
  if (!ch || !sv) { targetFactorNormal = 1; targetFactorBoss = 1; return; }
  const base = { chapter: ch, pen: parseFloat(collectStats().DEF_PEN) || 0, stats: collectStats(), conversions: myConv };
  const normalStage = sv === "boss" ? lastNormalStage(ch) : sv;
  try {
    const [rn, rb] = await Promise.all([   // 동료 적 방어력 감소·방관도 각각 반영(companionParams)
      postJson("/api/target-damage", Object.assign({ target: normalStage }, base, companionParams())),
      postJson("/api/target-damage", Object.assign({ target: "boss" }, base, companionParams())),
    ]);
    targetFactorNormal = (rn && rn.factor > 0) ? rn.factor : 1;
    targetFactorBoss = (rb && rb.factor > 0) ? rb.factor : 1;
    saveJSON(CACHE_TGTFACTOR, { n: targetFactorNormal, b: targetFactorBoss });   // 다음 즉시 페인트용
  } catch (e) { targetFactorNormal = 1; targetFactorBoss = 1; }
}
// 방어배수를 데미지 상세에 곱한 사본(비율/시간 값은 보존).
function scaleDetail(det, f) {
  if (!det || !(f > 0) || f === 1) return det;
  const sc = (v) => (v == null ? v : v * f);
  const scShares = (arr) => Array.isArray(arr) ? arr.map((x) => Object.assign({}, x, { dps: sc(x.dps) })) : arr;
  const scState = (s) => (s ? Object.assign({}, s, { avg: sc(s.avg), min: sc(s.min), max: sc(s.max), dps: sc(s.dps), shares: scShares(s.shares) }) : s);
  const d = Object.assign({}, det, { solo: scState(det.solo), summon: scState(det.summon) });
  const scMap = (m) => { if (!m) return m; const o = {}; for (const k in m) o[k] = sc(m[k]); return o; };
  if (det.dps) d.dps = Object.assign({}, det.dps, {
    total: sc(det.dps.total), basic: sc(det.dps.basic), skill: sc(det.dps.skill),
    companion: sc(det.dps.companion), companionWindow: sc(det.dps.companionWindow),
    companionBasic: sc(det.dps.companionBasic), companionSkill: sc(det.dps.companionSkill),
    companionDetail: scMap(det.dps.companionDetail),
  });
  if (Array.isArray(det.shares)) d.shares = det.shares.map((s) => Object.assign({}, s, { dps: sc(s.dps) }));
  if (Array.isArray(det.relicShares)) d.relicShares = det.relicShares.map((s) => Object.assign({}, s, { dps: sc(s.dps) }));
  return d;
}

// ──────────────────── 대상 선택(챕터/스테이지 → 일반몹/보스몹) ────────────────────
// 대상은 직업처럼 '스탯 프리셋'에 포함된다(preset.target = {ch, stage}). 프리셋 전환 시 함께 바뀜.
const TARGET_KEY = "mekicalc.target.v1";   // 마이그레이션·폴백용(프리셋에 target 없을 때)
function activeStatPreset() {
  return statPresets && statPresets.presets && statPresets.presets[statPresets.active];
}
function targetOfActivePreset() {
  const p = activeStatPreset();
  return (p && p.target) || loadJSON(TARGET_KEY, null) || null;
}
// 대상 선택기 인스턴스(같은 값을 공유): 프리셋 빠른 전환(플로팅) + 스탯 프리셋 패널(직업 옆).
const TARGET_PAIRS = [["qs-chapter", "qs-stage"], ["spt-chapter", "spt-stage"]];
function currentTargetSel() {
  for (const [chId, stId] of TARGET_PAIRS) {
    const st = $(stId);
    if (st && st.value) return { ch: $(chId).value, stage: st.value };
  }
  return targetOfActivePreset() || undefined;
}
// 스탯 프리셋 저장 페이로드(스탯 + 대상). 모든 저장 지점에서 사용.
// 빌드 프리셋 = 스탯 + 대상 + 직업(stats.JOB) + 스킬(buffs/hitInterval). 스탯·스킬을 한 단위로 저장(직업 단일).
function statPresetPayload() { return { stats: collectStats(), target: currentTargetSel(), buffs: myBuffs, hitInterval: myHitInterval }; }
// 큐브/계산 API에 보낼 대상(챕터/스테이지). 보스/일반·방어력은 서버가 이걸로 결정.
function targetForApi() {
  const t = currentTargetSel();
  return t ? { chapter: t.ch, target: t.stage } : { chapter: "", target: "" };
}
// 큐브 탭 '대상' 힌트(보스 토글 대체) — 현재 프리셋 대상 표시.
function updateCubeTargetHint() {
  const t = currentTargetSel();
  let label = "프리셋 기준";
  if (t) {
    const boss = t.stage === "boss";
    label = boss ? `${t.ch}챕터 보스 · 보스몹` : `${t.ch}-${String(t.stage).replace("stage:", "")} · 일반몹`;
  }
  document.querySelectorAll(".ct-hint-val").forEach((e) => { e.textContent = label; });
}
function fillStages(ssel, ch, selected) {
  if (!ssel) return;
  const c = targetCatalog && targetCatalog.chapters[ch];
  if (!c) { ssel.innerHTML = ""; return; }
  const stages = (c.stages || []).slice().sort((a, b) => +a - +b);
  const opts = stages.map((s) => `<option value="stage:${s}">${s}</option>`);   // 스테이지 숫자만
  if (c.boss) {                                                                  // 보스 = 마지막 스테이지 다음 번호 + (보스)
    const bossNo = stages.length ? Math.max(...stages.map((s) => +s)) + 1 : "";
    opts.push(`<option value="boss">${bossNo} (보스)</option>`);
  }
  ssel.innerHTML = opts.join("");
  if (selected && [...ssel.options].some((o) => o.value === selected)) ssel.value = selected;
}
// 모든 대상 선택기를 활성 프리셋의 대상으로 동기화(프리셋 전환·부팅 시).
function syncTargetSelector() {
  if (!targetCatalog || !targetCatalog.chapters) return;
  const t = targetOfActivePreset();
  for (const [chId, stId] of TARGET_PAIRS) {
    const csel = $(chId), ssel = $(stId);
    if (!csel || !ssel) continue;
    if (t && targetCatalog.chapters[t.ch]) csel.value = t.ch;
    fillStages(ssel, csel.value, t && t.stage);
  }
  const pn = $("ts-preset-name"); if (pn) pn.textContent = "‘" + ((statPresets && statPresets.active) || "") + "’";
  updateCubeTargetHint();
}
function renderTargetSelector() {
  if (!targetCatalog || !targetCatalog.chapters) return;
  const chs = Object.keys(targetCatalog.chapters).filter((c) => +c <= 41 && hasTargets(c)).sort((a, b) => +a - +b);  // 캠페인 챕터
  const opts = chs.map((c) => `<option value="${c}">${c}</option>`).join("");   // 챕터 숫자만
  for (const [chId, stId] of TARGET_PAIRS) {
    const csel = $(chId), ssel = $(stId);
    if (!csel || !ssel) continue;
    csel.innerHTML = opts;
    csel.addEventListener("change", () => { fillStages(ssel, csel.value, null); onTargetChange(chId, stId); });
    ssel.addEventListener("change", () => onTargetChange(chId, stId));
  }
  syncTargetSelector();
  refreshTargetFactor().then(renderHome);   // 부팅 시엔 프리셋에 쓰지 않고 표시만
}
// 사용자가 (어느 선택기든) 대상을 바꾸면 활성 스탯 프리셋의 target에 저장하고 다른 선택기도 동기화.
function onTargetChange(chId, stId) {
  const csel = $(chId || "qs-chapter"), ssel = $(stId || "qs-stage");
  if (!ssel || !ssel.value) return;
  const t = { ch: csel.value, stage: ssel.value };
  const p = activeStatPreset();
  if (p) { p.target = t; saveJSON(STATP_KEY, statPresets); }   // 프리셋에 귀속(자동 저장)
  saveJSON(TARGET_KEY, t);                                     // 폴백/호환 유지
  syncTargetSelector();                                        // 홈·스탯패널 선택기 동기화
  refreshTargetFactor().then(renderHome);
}
function hasTargets(ch) {
  const c = targetCatalog.chapters[ch];
  return c && ((c.stages && c.stages.length) || c.boss);
}

// 상세 표 셀: 전체 숫자 + (조/억 읽기). 값 없으면 —.
function dmgCell(v) {
  return (v == null) ? "—" : fmt(v) + "<br><span class='ku'>(" + koreanUnit(v) + ")</span>";
}
// 비중 포함 셀(평타/스킬 DPS): 숫자 + (조/억) · 전체 대비 %.
function dmgCellShare(v, total) {
  if (v == null) return "—";
  const share = (total > 0) ? Math.round((v / total) * 100) : 0;
  return fmt(v) + "<br><span class='ku'>(" + koreanUnit(v) + ") · " + share + "%</span>";
}
// ──────────────────── 홈 대시보드 ────────────────────

const PIE_COLORS = ["#7c9cff", "#5bd1c2", "#c98bff", "#6fcf6f", "#ff8a9b", "#5b9bd5", "#d98cc0", "#86c5a8", "#b7a3ff", "#7fd1e8"];
const COMPANION_COLOR = "#f5a623";   // 동료 = 골드(로고색 계열)
const OTHER_COLOR = "#6b7280";       // 기타 = 회색

// 부팅 땐 renderHome이 동일 데이터로 여러 번(초기 계산 + 로그인 동기화 재계산 등) 호출돼 DOM을 계속 새로 만들고,
// 그때마다 차트 진입 애니메이션이 재생→즉시 다음 렌더에 덮여 부자연스러웠다. → 결과가 같으면 재렌더를 건너뛴다(애니메이션 1회).
// 딜 구성 탭 토글 상태. dealMob=null이면 프리셋 스테이지(일반/보스)를 기본으로 따른다.
let dealMob = null;        // "normal" | "boss"
let dealSummon = "solo";   // "solo" | "summon"
let lastHomeSig = null;
function currentDealMob() { return dealMob || currentTarget(); }

function renderHome() {
  if (!lastDamage || !lastDamage.detail) return;
  const mob = currentDealMob();
  const rawDet = mob === "boss" ? lastDamage.detail.boss : lastDamage.detail.normal;
  if (!rawDet) return;
  const f = factorFor(mob);
  const canSummon = !!rawDet.summon;
  const useSummon = dealSummon === "summon" && canSummon;
  const sig = JSON.stringify([mob, useSummon, f, rawDet]);
  if (sig === lastHomeSig) return;   // 동일 결과 → 재렌더 스킵(애니메이션 1회)
  lastHomeSig = sig;
  syncDealToggles(mob, canSummon, useSummon);
  const det = scaleDetail(rawDet, f);   // 선택 몹 방어배수 반영(서버 계산)
  const state = useSummon ? det.summon : det.solo;
  if (!state) return;
  // 캐릭터(스탯) 프리셋이 전부 0/디폴트면 = 스탯 미입력 → 헤드라인·딜 비중 박스 블러 + 캐릭터 입력 CTA.
  const empty = characterEmpty();
  renderDealHeadline(state, mob, det, empty);
  renderDealShares(state, mob, det, empty);
}

// 토글 버튼 활성/비활성(동료 없으면 소환 버튼 잠금).
function syncDealToggles(mob, canSummon, useSummon) {
  const mobBox = $("ds-mob"), sumBox = $("ds-summon");
  if (mobBox) mobBox.querySelectorAll(".ds-seg-btn").forEach((b) => b.classList.toggle("active", b.dataset.v === mob));
  if (sumBox) sumBox.querySelectorAll(".ds-seg-btn").forEach((b) => {
    const solo = b.dataset.v === "solo";
    b.disabled = !solo && !canSummon;
    b.classList.toggle("active", b.dataset.v === (useSummon ? "summon" : "solo"));
  });
}

// 헤드라인 3박스: 평타 한줄 평균딜 · DPS(토글 상태 순간) · DPS(n초 측정, 소환 가동률까지 반영한 전투 평균 실측).
// 세 번째는 det.dps.total(전투 70/90초 평균)이라 미소환/소환 토글과 무관한 실전값. n=windowSeconds(보스70·사냥90).
function renderDealHeadline(state, mob, det, empty) {
  const box = $("ds-headline"); if (!box) return;
  if (empty) {
    box.innerHTML = lockedBox(skelHeadline(), "캐릭터 스탯이 아직 없어요.<br>스탯을 입력하면 예상 데미지를 볼 수 있어요.", "캐릭터 입력하러 가기", "/presets/build");
    return;
  }
  const tile = (label, v) =>
    `<div class="ds-hl-tile"><div class="ds-hl-label">${label}</div>` +
    `<div class="ds-hl-val">${v == null ? "—" : koreanUnit(v)}</div>` +
    `<div class="ds-hl-sub">${v == null ? "" : fmt(Math.round(v))}</div></div>`;
  const dps = det && det.dps ? det.dps : null;
  const secs = dps && dps.windowSeconds ? Math.round(dps.windowSeconds) : (mob === "boss" ? 70 : 90);
  box.innerHTML = tile("평타 한줄 평균딜", state.avg) + tile("DPS", state.dps)
    + tile(`DPS(${secs}초 측정)`, dps ? dps.total : null);
}

// 딜 비중: 평타 + 각 스킬 + 동료가 도넛에 개별 조각으로. 범례에선 스킬을 '스킬 N개' 그룹으로 묶어 펼친다.
function renderDealShares(state, mob, det, empty) {
  if (empty) {   // 캐릭터 스탯 미입력 → 도넛 블러 + 캐릭터 CTA. 동료·유물은 각자 요구 프리셋 기준으로 블러됨.
    lockDonut("캐릭터 스탯이 아직 없어요.<br>스탯을 입력하면 딜 비중을 볼 수 있어요.", "캐릭터 입력하러 가기", "/presets/build");
    renderCompanionSummary(det);
    renderArtifactSummary(det);
    if ($("ds-share-sub")) $("ds-share-sub").textContent = "";
    return;
  }
  const shares = state.shares || [];
  const total = shares.reduce((s, x) => s + (x.dps || 0), 0);
  const basic = shares.filter((x) => x.name === "평타").reduce((s, x) => s + (x.dps || 0), 0);
  const comp = shares.filter((x) => x.companion).reduce((s, x) => s + (x.dps || 0), 0);
  const skillItems = shares.filter((x) => !x.companion && x.name !== "평타").sort((a, b) => (b.dps || 0) - (a.dps || 0));
  const slices = [];
  if (basic > 0) slices.push({ name: "평타", dps: basic, color: PIE_COLORS[0], kind: "basic" });
  skillItems.forEach((s, i) => { if ((s.dps || 0) > 0) slices.push({ name: s.name, dps: s.dps, color: PIE_COLORS[(i + 1) % PIE_COLORS.length], kind: "skill" }); });
  if (comp > 0) slices.push({ name: activeCompanionLabel(), dps: comp, color: COMPANION_COLOR, kind: "companion" });
  // 평타 외 조각(스킬/동료)이 하나도 없으면 = 스킬 프리셋이 덜 입력됨 → 도넛 블러 + 스킬 입력 CTA.
  if (total > 0 && !slices.some((x) => x.kind !== "basic")) {
    lockDonut("체크된 딜 스킬이 없어요.<br>스킬을 입력하면 스킬별 딜 비중을 볼 수 있어요.", "스킬 입력하러 가기", "/presets/build");
  } else {
    renderDealDonut(slices, total);
  }
  renderCompanionSummary(det);
  renderArtifactSummary(det);
  if ($("ds-share-sub")) $("ds-share-sub").textContent = "";
}

// ── 미입력/덜 입력된 프리셋 안내(블러 스켈레톤 + 입력 화면 CTA) ──
// route-link라 기존 클릭 핸들러가 해당 프리셋 입력 탭으로 이동시킨다. msg는 정적 문자열(HTML 허용, <br> 등).
function lockedBox(skeleton, msg, cta, path) {
  return `<div class="locked-wrap"><div class="locked-blur" aria-hidden="true">${skeleton}</div>` +
    `<div class="locked-cta"><p class="locked-msg">${msg}</p>` +
    `<a class="route-link locked-btn" href="${path}">${cta} →</a></div></div>`;
}
function skelBars(n) {
  const w = [80, 58, 42, 30];
  let s = "";
  for (let i = 0; i < n; i++) s += `<div class="cmp-bar-row"><span class="cmp-bar-name">···</span>` +
    `<div class="bar-track"><div class="bar-fill" style="width:${w[i % w.length]}%"></div></div><span class="bar-val">··</span></div>`;
  return s;
}
function skelDonut() {
  return `<svg viewBox="0 0 180 180" class="donut-svg" aria-hidden="true"><circle cx="90" cy="90" r="67" fill="none" stroke="var(--muted)" stroke-width="34"/></svg>`;
}
function skelHeadline() {
  const t = `<div class="ds-hl-tile"><div class="ds-hl-label">···</div><div class="ds-hl-val">—</div><div class="ds-hl-sub">···</div></div>`;
  return `<div class="ds-headline ds-hl-skel">${t}${t}${t}</div>`;
}

// 캐릭터(스탯) 프리셋이 사실상 미입력(딜의 밑바탕이 전부 0/디폴트)인지 — 공격력·주스탯·방어력 모두 0이면 딜이 0.
function characterEmpty() {
  const s = collectStats();
  return !((s.ATK_FLAT > 0) || (s.MAIN_STAT_FLAT > 0) || (s.DEFENSE > 0));
}

// 큐브 프리셋에 입력된 잠재 옵션(스탯 있는 슬롯)이 하나도 없으면 미작성.
function cubeEmpty() {
  if (!myCube || typeof myCube !== "object") return true;
  for (const part of Object.values(myCube)) {
    if (!part) continue;
    for (const line of Object.values(part)) {
      if (line && Array.isArray(line.slots) && line.slots.some((s) => s && s.stat)) return false;
    }
  }
  return true;
}

// 패널 게이트: reqs=[{empty,msg,cta,path}]를 순서대로 검사, 첫 미충족을 블러+CTA로. 모두 충족이면 게이트 제거. true 반환 시 렌더 스킵.
function applyGate(panel, reqs) {
  if (!panel) return false;
  const fail = reqs.find((r) => r.empty);
  panel.classList.toggle("char-gated", !!fail);
  let gate = panel.querySelector(":scope > .char-gate");
  if (fail) {
    if (!gate) { gate = document.createElement("div"); gate.className = "char-gate"; panel.appendChild(gate); }
    gate.innerHTML = `<p class="locked-msg">${fail.msg}</p><a class="route-link locked-btn" href="${fail.path}">${fail.cta} →</a>`;
  } else if (gate) {
    gate.remove();
  }
  return !!fail;
}
function lockDonut(msg, cta, path) {
  const chart = $("donut-chart"), legend = $("donut-legend");
  if (chart) chart.innerHTML = lockedBox(skelDonut(), msg, cta, path);
  if (legend) legend.innerHTML = "";
  if ($("ds-share-sub")) $("ds-share-sub").textContent = "";
}

// 오른쪽 하단 카드: 각 유물의 딜 기여. det.relicShares(백엔드 유물별 기여)를 막대로. 데이터 없으면 안내.
function renderArtifactSummary(det) {
  const box = $("ds-artifacts"); if (!box) return;
  const sub = $("ds-relic-sub");
  const relics = activeRelic();
  const rs = det && det.relicShares;   // [{name, dps}] — 백엔드에서 채워지면 표시
  if (!relics || !relics.length) {
    if (sub) sub.textContent = "";
    box.innerHTML = lockedBox(skelBars(4), "장착한 유물이 없어요.<br>유물을 입력하면 각 유물의 딜 기여를 볼 수 있어요.", "유물 입력하러 가기", "/presets/relic");
    return;
  }
  if (!rs || !rs.length) {   // 유물은 있으나 기여 데이터 미구현 상태(백엔드 준비 예정)
    if (sub) sub.textContent = `${relics.length}개 장착`;
    box.innerHTML = `<p class="ds-comp-empty">유물별 딜 기여 계산을 준비 중이에요.</p>`;
    return;
  }
  // 각 유물 기여를 %로: 기본은 미소환(solo) 기준 딜 증가율(= dps / (전체 - dps)).
  // 동료 유물(유리구두=소환지속)은 미소환 기준 0이라 백엔드가 소환 종합 딜 기준 pct를 직접 준다(s.pct 우선).
  const full = (det.solo && det.solo.dps) || 0;
  const rows = rs.map((s) => {
    const d = s.dps || 0, without = full - d;
    const pct = (s.pct != null) ? s.pct : (without > 0 ? d / without * 100 : 0);
    return { name: s.name, pct };
  }).sort((a, b) => b.pct - a.pct);
  const mx = Math.max(...rows.map((x) => x.pct), 0.01);
  if (sub) sub.textContent = `${rows.length}개 · 딜 기여`;
  box.innerHTML = rows.map((s, i) => {
    const c = PIE_COLORS[(i + 1) % PIE_COLORS.length];
    return `<div class="cmp-bar-row"><span class="cmp-bar-name" title="${escapeHtml(s.name)}">${escapeHtml(s.name)}</span>` +
      `<div class="bar-track"><div class="bar-fill" style="width:${s.pct / mx * 100}%;background:${c}"></div></div>` +
      `<span class="bar-val">+${num1s(s.pct)}%</span></div>`;
  }).join("");
}

// 개별 조각 도넛 + 범례(평타·동료는 단독 행, 스킬은 그룹으로 묶어 펼침) + 조각↔범례 호버 하이라이트/툴팁.
function renderDealDonut(slices, total) {
  const chart = $("donut-chart"), legend = $("donut-legend");
  if (!chart) return;
  const g = slices.filter((x) => x.dps > 0);
  if (!g.length || total <= 0) { chart.innerHTML = "<div class='donut-empty'>데이터 없음</div>"; if (legend) legend.innerHTML = ""; return; }
  const S = 180, cx = S / 2, cy = S / 2, R = 84, r = 50;
  const top = g.reduce((m, s) => (s.dps > m.dps ? s : m), g[0]);   // 가운데 기본 표시 = 주력(최대) 조각
  const shortName = (nm) => (nm && nm.length > 7) ? nm.slice(0, 6) + "…" : (nm || "");
  let a = 0, paths = "";
  g.forEach((s, i) => {
    const deg = s.dps / total * 360;
    const a1 = (i === g.length - 1) ? 360 : a + deg;
    const d = (g.length === 1) ? ringPath(cx, cy, R, r, 0.01, 359.99) : ringPath(cx, cy, R, r, a, a1);
    paths += `<path d="${d}" fill="${s.color}" class="pie-seg" data-i="${i}"><title>${escapeHtml(s.name + " · " + num1s(s.dps / total * 100) + "% (" + koreanUnit(s.dps) + ")")}</title></path>`;
    a = a1;
  });
  // 가운데: 호버한 조각의 이름+비중을 보여줌(안 하면 주력 조각). 정적 '종합 딜 100%'보다 정보량↑.
  chart.innerHTML =
    `<svg viewBox="0 0 ${S} ${S}" class="donut-svg" role="img" aria-label="딜 구성 도넛"><g class="donut-ring">${paths}</g>` +
    `<text x="${cx}" y="${cy - 6}" class="donut-center-top" id="donut-ctr-name">${escapeHtml(shortName(top.name))}</text>` +
    `<text x="${cx}" y="${cy + 14}" class="donut-center-val" id="donut-ctr-val">${num1s(top.dps / total * 100)}%</text></svg>`;
  if (legend) {
    const lgRow = (i, dot, name, pct, val, cls) =>
      `<div class="${cls || "lg-row"}" data-i="${i}">${dot}<span class="lg-name">${name}</span>` +
      `<span class="lg-pct">${num1s(pct)}%</span><span class="lg-val">${koreanUnit(val)}</span></div>`;
    const skills = g.map((s, i) => ({ s, i })).filter((o) => o.s.kind === "skill");
    const skillSum = skills.reduce((t, o) => t + o.s.dps, 0);
    let html = "";
    let skillEmitted = false;
    g.forEach((s, i) => {
      if (s.kind === "skill") {
        if (skillEmitted) return;
        skillEmitted = true;
        const head = `<div class="lg-row lg-other-head"><span class="lg-dot" style="background:${PIE_COLORS[1]}"></span>` +
          `<span class="lg-name">스킬 <span class="lg-cnt">${skills.length}개</span></span>` +
          `<span class="lg-pct">${num1s(skillSum / total * 100)}%</span><span class="lg-val">${koreanUnit(skillSum)}</span></div>`;
        const subs = skills.map((o) =>
          `<div class="lg-row lg-subrow" data-i="${o.i}"><span class="lg-subdot" style="background:${o.s.color};opacity:1"></span>` +
          `<span class="lg-name">${escapeHtml(o.s.name)}</span><span class="lg-pct">${num1s(o.s.dps / total * 100)}%</span>` +
          `<span class="lg-val">${koreanUnit(o.s.dps)}</span></div>`).join("");
        html += `<div class="lg-group">${head}<div class="lg-sublist">${subs}</div></div>`;
      } else {
        html += lgRow(i, `<span class="lg-dot" style="background:${s.color}"></span>`, escapeHtml(s.name), s.dps / total * 100, s.dps);
      }
    });
    legend.innerHTML = html;
  }
  // 조각 ↔ 범례 하이라이트 연동 + 가운데 텍스트 갱신(호버=그 조각 / 벗어나면 주력).
  const segs = chart.querySelectorAll(".pie-seg"), rows = legend ? legend.querySelectorAll("[data-i]") : [];
  const ctrName = chart.querySelector("#donut-ctr-name"), ctrVal = chart.querySelector("#donut-ctr-val");
  const setCenter = (s) => { if (ctrName) ctrName.textContent = shortName(s.name); if (ctrVal) ctrVal.textContent = num1s(s.dps / total * 100) + "%"; };
  const hi = (i, on) => {
    segs.forEach((p) => p.classList.toggle("dim", on && +p.dataset.i !== i));
    rows.forEach((rw) => rw.classList.toggle("active", on && +rw.dataset.i === i));
    setCenter(on && g[i] ? g[i] : top);
  };
  segs.forEach((p) => { p.addEventListener("mouseenter", () => hi(+p.dataset.i, true)); p.addEventListener("mouseleave", () => hi(0, false)); });
  rows.forEach((rw) => { rw.addEventListener("mouseenter", () => hi(+rw.dataset.i, true)); rw.addEventListener("mouseleave", () => hi(0, false)); });
}

// 오른쪽 카드: 동료 기여 — 미소환 vs 소환 종합 딜 + 소환 시 증가율 + 동료 순수 DPS. 동료 없으면 안내.
function renderCompanionSummary(det) {
  const box = $("ds-companion"); if (!box) return;
  const sub = $("ds-comp-sub");
  const solo = det.solo, summon = det.summon;
  // 동료는 항상 하나가 선택돼 있어 '미선택'이 안 됨 → 디폴트/미작성 신호 = 공격력 계승 비율(ratio)이 0.
  // ratio가 0이면(또는 동료·소환 데이터 없으면) 블러+CTA. 다른 게이트처럼 프리셋을 직접 검사.
  const ally = activeCompanionSel();
  if (!ally.companionId || !(ally.ratio > 0) || !summon || !solo) {
    if (sub) sub.textContent = "";
    box.innerHTML = lockedBox(skelBars(2), "동료가 아직 없어요.<br>동료를 입력하면 소환 시 딜 기여를 볼 수 있어요.", "동료 입력하러 가기", "/presets/ally");
    return;
  }
  const secs = (det.dps && det.dps.windowSeconds) ? Math.round(det.dps.windowSeconds) : 90;
  const soloDps = solo.dps || 0, sumDps = summon.dps || 0;
  const contrib = soloDps > 0 ? (sumDps / soloDps - 1) * 100 : 0;
  const compShare = (summon.shares || []).find((x) => x.companion);
  const compDps = compShare ? (compShare.dps || 0) : 0;
  const nm = activeCompanionName();
  if (sub) sub.textContent = nm || "";
  const pMax = Math.max(soloDps, sumDps) || 1;
  const bar = (name, v, c) =>
    `<div class="cmp-bar-row"><span class="cmp-bar-name">${name}</span>` +
    `<div class="bar-track"><div class="bar-fill" style="width:${v / pMax * 100}%;background:${c}"></div></div>` +
    `<span class="bar-val">${koreanUnit(v)}</span></div>`;
  box.innerHTML =
    `<div class="ds-contrib"><span class="ds-contrib-label">동료 소환 시 종합 딜</span><b class="ds-contrib-val">+${num1s(contrib)}%</b></div>` +
    `<div class="ds-cmp-bars">${bar("미소환", soloDps, PIE_COLORS[0])}${bar("소환", sumDps, COMPANION_COLOR)}</div>` +
    `<div class="ds-comp-note"><i class="cdot" style="background:${COMPANION_COLOR}"></i>${activeCompanionLabel()} 순수 DPS <b>${koreanUnit(compDps)}</b> <span class="sub">${secs}초 소환 기준</span></div>`;
}

// 토글 이벤트(정적 요소 → 1회 등록). 값 바꾸면 sig 무효화 후 즉시 재렌더.
if ($("ds-mob")) $("ds-mob").addEventListener("click", (e) => {
  const b = e.target.closest(".ds-seg-btn"); if (!b) return;
  dealMob = b.dataset.v; lastHomeSig = null; renderHome();
});
if ($("ds-summon")) $("ds-summon").addEventListener("click", (e) => {
  const b = e.target.closest(".ds-seg-btn"); if (!b || b.disabled) return;
  dealSummon = b.dataset.v; lastHomeSig = null; renderHome();
});

// 동료 기여 카드: 동료 평타·딜 스킬·버프의 데미지와 기여도(모두 소환 유지창 기준). 상단 딜 상세와 중복되는 합계 타일은 없앰. 동료 선택 시에만.
function renderCompanionCard(det) {
  const box = $("home-companion"); if (!box) return;
  const dps = det && det.dps;
  if (!dps || dps.companion == null) { box.hidden = true; box.innerHTML = ""; return; }
  box.hidden = false;
  const nm = activeCompanionName();
  const sw = dps.summonSeconds ? Math.round(dps.summonSeconds) : null;   // 소환 유지창(예: 48초)
  const sumSec = dps.summonSeconds || 0;                                 // 소환 지속(초) — 합산 환산용
  // 기준선(동료 없을 때 플레이어 딜) = det.solo.dps(방어배수 스케일 적용본). dps.playerSolo는 미스케일이라 동료 값과 단위가 안 맞음.
  const solo = (det.solo && det.solo.dps) || 0;
  const cBasic = dps.companionBasic || 0, cSkill = dps.companionSkill || 0, cWin = dps.companionWindow || 0;
  const playerSummon = det.summon && det.summon.dps != null ? det.summon.dps - cWin : solo;   // 소환 중 플레이어 딜(동료 버프 받은)
  const buffBoost = Math.max(0, playerSummon - solo);                     // 동료 버프로 늘어난 플레이어 딜
  const pctOf = (v) => (solo > 0 ? v / solo * 100 : 0);                   // 기여도 %는 DPS 비율(시간 무관) — 그대로
  // dmg는 소환 유지창 동안의 총 딜(합산). 동료 평타+딜스킬 = 딜 비중의 동료 조각과 동일한 숫자가 됨.
  const rows = [
    { name: "동료 평타", dmg: koreanUnit(cBasic * sumSec), pct: pctOf(cBasic), c: COMPANION_COLOR },
    { name: "동료 딜 스킬", dmg: koreanUnit(cSkill * sumSec), pct: pctOf(cSkill), c: "#c78bff" },
    { name: "동료 버프", dmg: `플레이어 딜 +${koreanUnit(buffBoost * sumSec)}`, pct: pctOf(buffBoost), c: "#f5a623" },
  ];
  const totalPct = rows.reduce((s, r) => s + r.pct, 0);
  const items = rows.map((r) =>
    `<div class="cc-item">` +
      `<div class="cc-item-top"><span class="cc-name">${r.name}</span>` +
      `<span class="cc-vals"><span class="cc-dmg">${r.dmg}</span><span class="cc-pct" style="color:${r.c}">+${num1s(r.pct)}%</span></span></div>` +
      `<div class="cc-track"><div class="cc-fill" style="width:${totalPct > 0 ? Math.min(100, r.pct / totalPct * 100) : 0}%;background:${r.c}"></div></div>` +
    `</div>`).join("");
  box.innerHTML =
    `<div class="dash-head cc-dhead">` +
      `<div class="cc-head-l"><h2>동료 기여${nm ? " — " + nm : ""}</h2><span class="dash-sub">소환 유지${sw ? " " + sw + "초" : ""} · 동료 없을 때 대비</span></div>` +
      `<div class="cc-total"><span class="cc-total-label">소환 중 총 기여도</span><b class="cc-total-val">+${num1s(totalPct)}%</b></div>` +
    `</div>` +
    `<div class="cc-items">${items}</div>`;
}

// 동료 총 딜(합산): 소환 창 DPS × 소환 지속(초). 동료 기여 카드와 '같은 값'을 쓰기 위한 단일 출처.
// (딜 비중은 이 합산을, 딜 상세는 DPS를 보여줘 관점을 분리 — 헷갈리던 "동료 딜 두 개"를 하나로.)
function companionTotal(dps) {
  if (!dps || dps.companionWindow == null) return 0;
  return (dps.companionWindow || 0) * (dps.summonSeconds || 0);
}

function polar(cx, cy, r, deg) { const a = (deg - 90) * Math.PI / 180; return [cx + r * Math.cos(a), cy + r * Math.sin(a)]; }
function ringPath(cx, cy, R, r, a0, a1) {
  const large = (a1 - a0) > 180 ? 1 : 0;
  const [ox0, oy0] = polar(cx, cy, R, a0), [ox1, oy1] = polar(cx, cy, R, a1);
  const [ix1, iy1] = polar(cx, cy, r, a1), [ix0, iy0] = polar(cx, cy, r, a0);
  return `M${ox0.toFixed(2)} ${oy0.toFixed(2)} A${R} ${R} 0 ${large} 1 ${ox1.toFixed(2)} ${oy1.toFixed(2)} L${ix1.toFixed(2)} ${iy1.toFixed(2)} A${r} ${r} 0 ${large} 0 ${ix0.toFixed(2)} ${iy0.toFixed(2)} Z`;
}

// 플레이어 vs 동료 비율 스택 바(데스크톱=가로, 모바일=세로 — 크기는 CSS의 --seg로 제어).
function renderPcSplit(shares, dps) {
  const box = $("pc-split"); if (!box) return;
  const comp = shares.find((x) => x.companion);
  if (!comp) { box.innerHTML = ""; box.hidden = true; return; }
  box.hidden = false;
  const winSec = (dps && dps.windowSeconds) || 0;
  const cDmg = companionTotal(dps);                                                  // 동료 총 딜(합산) — 카드와 동일
  const pDmg = shares.filter((x) => !x.companion).reduce((s, x) => s + (x.dps || 0) * winSec, 0);
  const total = pDmg + cDmg;
  const pPct = total > 0 ? pDmg / total * 100 : 0, cPct = 100 - pPct;
  const cl = activeCompanionLabel();
  box.innerHTML =
    `<div class="pc-head"><span>플레이어 vs ${cl}</span></div>` +
    `<div class="pc-bar">` +
      `<div class="pc-seg pc-player" style="--seg:${pPct}%"><span>${num1s(pPct)}%</span></div>` +
      `<div class="pc-seg pc-comp" style="--seg:${cPct}%"><span>${num1s(cPct)}%</span></div>` +
    `</div>` +
    `<div class="pc-legend"><span><i class="pc-i pc-player"></i>플레이어 ${koreanUnit(pDmg)}</span>` +
    `<span><i class="pc-i pc-comp"></i>${cl} ${koreanUnit(cDmg)}</span></div>`;
}

// 딜 상세: 2열(동료 미소환/소환) × {평균딜·최소딜·최대딜(사이클 중 평타 범위)·DPS}. 동료 없으면 1열.
function renderHomeStats(det) {
  const box = $("home-stats"); if (!box) return;
  if (!det || !det.solo) { box.innerHTML = ""; return; }
  const tile = (label, v, accent) =>
    `<div class="tile${accent ? " tile-accent" : ""}"><div class="tile-label">${label}</div>` +
    `<div class="tile-val">${v == null ? "—" : koreanUnit(v)}</div>` +
    `<div class="tile-ku">${v == null ? "" : fmt(Math.round(v))}</div></div>`;
  const col = (title, s) =>
    `<div class="stat-col"><div class="stat-col-h">${title}</div>` +
    tile("1타 평균딜", s.avg, true) + tile("1타 최소딜", s.min) + tile("1타 최대딜", s.max) + tile("DPS", s.dps, true) + `</div>`;
  const summon = det.summon;
  box.innerHTML = `<div class="stat-cols${summon ? "" : " single"}">` +
    col("동료 미소환", det.solo) + (summon ? col("동료 소환", summon) : "") + `</div>`;
}

// 평타 / 스킬 / 동료(90초 사이클) DPS 가로 막대(전체 대비) + 동료 순수 DPS 별도 표기
function renderDpsBars(dps) {
  const box = $("home-dpsbars"); if (!box) return;
  if (!dps) { box.innerHTML = "<p class='status'>이 직업은 DPS 모델이 없어요.</p>"; return; }
  const total = dps.total || 0;
  const secs = dps.windowSeconds ? Math.round(dps.windowSeconds) : null;
  const rows = [
    { name: "평타 DPS", v: dps.basic, c: PIE_COLORS[0] },
    { name: "스킬 DPS", v: dps.skill, c: PIE_COLORS[1] },
  ];
  if (dps.companion != null) rows.push({ name: activeCompanionLabel() + " DPS", v: dps.companion, c: COMPANION_COLOR, sub: secs ? `${secs}초 기준` : "" });
  let html = `<div class="bars-head">DPS 구성${secs ? ` <span class="bars-sub">(${secs}초)</span>` : ""}</div>` + rows.map((rw) => {
    const pct = total > 0 ? (rw.v || 0) / total * 100 : 0;
    return `<div class="bar-row"><div class="bar-name">${rw.name}${rw.sub ? `<span class="bar-sub">${rw.sub}</span>` : ""}</div>` +
      `<div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:${rw.c}"></div></div>` +
      `<div class="bar-val">${koreanUnit(rw.v || 0)}<span class="bar-pct">${num1s(pct)}%</span></div></div>`;
  }).join("");
  // 동료 DPS 한 줄: 소환 지속(초, SUMMON_DURATION 반영)과 소환 기간 기준 DPS
  if (dps.companionWindow != null) {
    const sd = dps.summonSeconds ? num1s(dps.summonSeconds) : null;
    html += `<div class="comp-dps-note">` +
      `<span><i class="cdot" style="background:${COMPANION_COLOR}"></i>${activeCompanionLabel()} DPS <b>${koreanUnit(dps.companionWindow)}</b>` +
      `${sd ? ` <span class="sub">${sd}초 소환 기준</span>` : ""}</span></div>`;
  }
  box.innerHTML = html;
}

function escapeHtml(s) { return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])); }
async function calcDamage() {
  // 이미 값이 떠 있으면(즉시 페인트·이전 계산) 백그라운드 갱신은 조용히 — '계산 중'/'오류' 텍스트로 방해하지 않음.
  const refreshing = damageShown;
  if (!refreshing) $("status").textContent = "계산 중…";
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const factorP = refreshTargetFactor();   // 방어배수와 데미지를 동시에 요청(서로 의존 없음 → 직렬 왕복 ~0.8s 제거)
      const data = await postJson("/api/damage", Object.assign({ stats: collectStats(), conversions: myConv, buffs: activeBuffs(), hitInterval: myHitInterval, artifacts: activeRelic() }, activeCompanionSel()));
      await factorP;   // 방어배수까지 확정된 뒤 1회 렌더
      showDamage(data);
      saveJSON(CACHE_DMG, data);   // 직전 데미지(상세 포함, 다음 로드 즉시 표시용)
      damageShown = true;
      $("status").textContent = "";
      return;
    } catch (e) {
      if (attempt === 0) { await new Promise((r) => setTimeout(r, 400)); continue; }   // 짧게 쉬고 1회 재시도(터널 블립 흡수)
      if (!refreshing) $("status").textContent = "오류: " + e.message;   // 값이 아예 없을 때만 오류 표시(있으면 직전 값 유지)
    }
  }
}

// ──────────────────── 큐브 기댓값 ────────────────────

let evResults = [];   // 마지막 계산 결과(정렬만 바꿀 때 재요청 없이 재정렬)

// 0.5초 넘는 계산일 때만 "계산 중…" 표시(빠른 계산은 표시 안 함 → 깜빡임 방지). 끝나면 반환값 호출해 해제.
function deferLoading(statusId) {
  const t = setTimeout(() => { const el = $(statusId); if (el) el.textContent = "계산 중…"; }, 500);
  return () => clearTimeout(t);
}

async function calcEvaluate() {
  const done = deferLoading("ev-status");   // 표는 숨기지 않음(이전 결과 유지) → renderEval이 끝에 갱신
  try {
    const slotRaw = $("ev-slot").value;
    const data = await postJson("/api/evaluate", {
      part: $("ev-part").value, grade: $("ev-grade").value,
      slot: slotRaw === "" ? null : parseInt(slotRaw, 10),
      ...targetForApi(), ...companionParams(),
      stats: collectStats(), conversions: myConv, buffs: activeBuffs(), hitInterval: myHitInterval,
    });
    evResults = data.results;
    renderEval();
    $("ev-status").textContent = evResults.length ? "" : "표시할 옵션이 없습니다.";
  } catch (e) { $("ev-status").textContent = "오류: " + e.message; }
  finally { done(); }
}

// 정렬키: 딜 증가량(미환산=뒤로), 효율=등장확률×딜증가량.
function evDmgKey(r) { return r.qualitative || r.damageIncrease == null ? -Infinity : r.damageIncrease; }
function evScoreKey(r) { return r.qualitative || r.damageIncrease == null ? -Infinity : r.pPerCube * r.damageIncrease; }

function renderEval() {
  const rows = evResults.slice();
  switch (parseInt($("ev-sort").value, 10)) {
    case 2: rows.sort((a, b) => b.pPerCube - a.pPerCube); break;            // 등장 확률 순
    case 0: rows.sort((a, b) => evScoreKey(b) - evScoreKey(a)); break;      // 효율 순
    default: rows.sort((a, b) => evDmgKey(b) - evDmgKey(a));                // 딜 증가량 순
  }
  const tb = $("ev-table").querySelector("tbody");
  tb.innerHTML = "";
  for (const r of rows) {
    const inc = r.qualitative ? '<span class="qual-tag">딜 미환산</span>'
      : (r.damageIncrease == null ? "—" : "+" + (r.damageIncrease * 100).toFixed(2) + "%");
    const cubes = (r.expectedCubes == null) ? "∞" : fmt(r.expectedCubes) + "개";
    const tr = document.createElement("tr");
    tr.innerHTML =
      `<td class="star">${r.special ? "★" : ""}</td><td${r.yellow ? ' style="color:#e7c14a"' : ""}>${cleanName(r.name)}</td><td class="num"${r.yellow ? ' style="color:#e7c14a"' : ""}>${r.value}${OPT_SUFFIX[r.unit] || ""}</td>` +
      `<td class="num ${r.qualitative ? "" : "good"}">${inc}</td><td class="num">${pct(r.pPerCube)}</td><td class="num">${cubes}</td>`;
    tb.appendChild(tr);
  }
  $("ev-table").hidden = rows.length === 0;
}
// 입력(부위·등급·슬롯·기준) 변경 시 자동 계산. 정렬만 바꾸면 재요청 없이 재정렬.
for (const id of ["ev-part", "ev-grade", "ev-slot"]) $(id).addEventListener("change", calcEvaluate);
$("ev-sort").addEventListener("change", () => { if (evResults.length) renderEval(); });

// ──────────────────── 내 큐브 편집기 ────────────────────

function emptyLine() {
  return { grade: meta.grades[meta.grades.length - 1], slots: [null, null, null], locked: [false, false, false], stack: 0 };
}

$("mc-add").addEventListener("click", () => {
  const part = $("mc-part-add").value;
  if (!part || myCube[part]) return;
  myCube[part] = { 윗잠: emptyLine(), 아랫잠: emptyLine() };
  saveCube(); renderCube();
});
$("mc-clear").addEventListener("click", () => {
  if (!Object.keys(myCube).length) return;
  if (!confirm("입력한 큐브 옵션을 모두 초기화할까요?")) return;
  myCube = {}; saveCube(); renderCube();
});

// 스탯 초기화: 입력값을 모두 0으로(프리셋은 그대로, 저장 전까진 디스크 미반영).
$("stat-reset").addEventListener("click", () => {
  if (!confirm("스탯 입력값을 모두 0으로 초기화할까요?")) return;
  for (const input of document.querySelectorAll("#sub-build input[data-stat-id]")) { input.value = "0"; delete input.dataset.full; }   // 헤더 레벨 포함
  statDirty = true;
  calcDamage();
});

// 슬롯 select 채우기(중복 옵션 제거, "비움" 포함). 노란 옵션은 금색, 흰 옵션은 기본색으로 구분.
function fillSlotSelect(sel, slotList, selectedSlot) {
  sel.innerHTML = "";
  sel.add(new Option("(비움)", ""));
  const seen = new Set();
  for (const d of slotList) {
    const key = d.statId + "|" + d.value;
    if (seen.has(key)) continue;
    seen.add(key);
    const o = new Option((d.special ? "★ " : "") + optLabel(d), key);
    o.style.color = d.yellow ? "#e7c14a" : "#cfd6e6";
    o.style.background = "#262a33";
    o.dataset.yellow = d.yellow ? "1" : "";
    sel.add(o);
  }
  sel.value = selectedSlot ? selectedSlot.stat + "|" + selectedSlot.value : "";
  applySelectColor(sel);
  sel.addEventListener("change", () => applySelectColor(sel));
}

// 접힌 select의 글자색을 선택된 옵션(노랑/흰)에 맞춤.
function applySelectColor(sel) {
  const o = sel.options[sel.selectedIndex];
  sel.style.color = (o && o.dataset && o.dataset.yellow) ? "#e7c14a" : "";
}

function parseSlotKey(key) {
  if (!key) return null;
  const i = key.lastIndexOf("|");
  return { stat: key.slice(0, i), value: parseFloat(key.slice(i + 1)) };
}

// 부스탯은 큐브에서만 나온다(깡부스탯·부스탯%는 큐브 옵션으로만 존재, 스탯 프리셋엔 최종값만 노출).
// myCube 전체 슬롯에서 깡부스탯 합·부스탯% 합을 모아 최종 부스탯 = 깡합 × (1+%합/100)로 파생한다.
// (곱셈이라 줄 단위가 아니라 전체 합으로 계산해야 정확)
function cubeSubStatFinal(cube) {
  let raw = 0, pct = 0;
  for (const part in cube) {
    for (const ln of ["윗잠", "아랫잠"]) {
      const line = cube[part] && cube[part][ln];
      if (!line || !line.slots) continue;
      for (const s of line.slots) {
        if (!s) continue;
        if (s.stat === "SUB_STAT_FLAT") raw += (+s.value || 0);
        else if (s.stat === "SUB_STAT_PCT") pct += (+s.value || 0);
      }
    }
  }
  return raw * (1 + pct / 100);
}

async function renderCube() {
  const list = $("mc-list");
  const parts = sortParts(Object.keys(myCube));
  if (!parts.length) { list.innerHTML = '<p class="status">아직 추가한 부위가 없습니다. 위에서 부위를 추가하세요.</p>'; return; }
  // 모든 부위×줄의 슬롯 테이블을 병렬로 미리 로드(순차 대기 제거 → 빠름)
  await Promise.all(parts.flatMap((part) => ["윗잠", "아랫잠"]
    .filter((ln) => myCube[part][ln] && myCube[part][ln].grade)
    .map((ln) => getSlots(part, myCube[part][ln].grade).catch(() => null))));
  // 새 내용을 프래그먼트에 먼저 다 만든 뒤 한 번에 교체 → 빈 상태로 높이가 줄어 스크롤이 위로 튀는 현상 방지.
  const frag = document.createDocumentFragment();
  for (const part of parts) {
    const fs = document.createElement("fieldset");
    fs.className = "cube-part";
    fs.innerHTML = `<legend>${part} <button class="part-del" type="button" title="이 부위 삭제">✕</button></legend>`;
    fs.querySelector(".part-del").addEventListener("click", () => { delete myCube[part]; saveCube(); renderCube(); });
    for (const lineName of ["윗잠", "아랫잠"]) {
      fs.appendChild(await renderLine(part, lineName));
    }
    frag.appendChild(fs);
  }
  list.replaceChildren(frag);
}

async function renderLine(part, lineName) {
  const line = myCube[part][lineName];
  const slots = await getSlots(part, line.grade);
  const wrap = document.createElement("div");
  wrap.className = "cube-line";

  // 제목 (데스크톱과 동일: 윗잠 (미라클 큐브) / 아랫잠 (에디셔널 큐브))
  const title = document.createElement("div");
  title.className = "line-title";
  title.textContent = lineName === "윗잠" ? "윗잠 (미라클 큐브)" : "아랫잠 (에디셔널 큐브)";
  wrap.appendChild(title);

  // 등급
  const gradeSel = document.createElement("select");
  gradeSel.className = "line-grade";
  fillGradeSelect(gradeSel);
  gradeSel.value = line.grade;
  applyGradeColor(gradeSel);
  gradeSel.addEventListener("change", async () => {
    line.grade = gradeSel.value; line.slots = [null, null, null];   // 등급 바뀌면 옵션 표 달라져 초기화
    saveCube();
    wrap.replaceWith(await renderLine(part, lineName));   // 이 줄만 교체 → 화면이 위로 튀지 않음
  });
  wrap.appendChild(gradeSel);

  // 슬롯 1·2·3 (각 잠금)
  for (let i = 0; i < 3; i++) {
    const row = document.createElement("div");
    row.className = "slot-row";
    const sel = document.createElement("select");
    fillSlotSelect(sel, slots[i], line.slots[i]);
    sel.addEventListener("change", () => { line.slots[i] = parseSlotKey(sel.value); saveCube(); });
    const lock = document.createElement("input");
    lock.type = "checkbox"; lock.checked = !!line.locked[i];
    lock.addEventListener("change", () => { line.locked[i] = lock.checked; saveCube(); });
    const lockLabel = document.createElement("label");
    lockLabel.className = "lock";
    lockLabel.appendChild(lock);
    lockLabel.appendChild(document.createTextNode("잠금"));
    row.appendChild(sel);
    row.appendChild(lockLabel);
    wrap.appendChild(row);
  }

  // 현재 스택
  const stackRow = document.createElement("div");
  stackRow.className = "stack-row";
  const sl = document.createElement("span");
  sl.className = "stack-label";
  sl.textContent = "현재 스택";
  const stackInput = document.createElement("input");
  stackInput.type = "number"; stackInput.min = "0"; stackInput.value = line.stack ?? 0; stackInput.className = "stack-input";
  stackInput.addEventListener("change", () => { line.stack = parseInt(stackInput.value, 10) || 0; saveCube(); });
  stackRow.appendChild(sl);
  stackRow.appendChild(stackInput);
  wrap.appendChild(stackRow);

  return wrap;
}

// ──────────────────── 우선순위 추천 ────────────────────

async function runRecommend(useSliderMax) {
  if (!Object.keys(myCube).length) { $("rc-status").textContent = "먼저 '내 큐브' 탭에서 큐브 옵션을 입력하세요."; $("rc-list").innerHTML = ""; return; }
  const done = deferLoading("rc-status");
  try {
    const data = await postJson("/api/recommend", {
      stats: collectStats(), conversions: myConv, ...targetForApi(), ...companionParams(),
      cubeState: myCube, ranges: myRanges, buffs: activeBuffs(), hitInterval: myHitInterval,
      sliderValue: parseInt($("rc-slider").value, 10) || 0,
      sortKey: parseInt($("rc-sort").value, 10) || 0,
      upper: $("rc-upper").checked, lower: $("rc-lower").checked,
    });
    const slider = $("rc-slider");
    slider.max = Math.max(0, data.maxDivisions - 1);
    $("rc-slider-max").textContent = slider.max;
    if (useSliderMax) { slider.value = 0; $("rc-slider-val").textContent = "0"; }
    renderRecs(data.recs);
    $("rc-status").textContent = data.recs.length ? "" : "추천할 옵션이 없습니다.";
  } catch (e) { $("rc-status").textContent = "오류: " + e.message; }
  finally { done(); }
}

// 슬롯 박스 한 칸. cls=neutral|before|after|locked. locked=true면 자물쇠 표시.
function scBox(opt, cls, no, locked) {
  const tag = no ? `<span class="sc-no">${no}</span>` : "";
  const lk = locked ? `<span class="sc-lock" title="잠금">🔒</span>` : "";
  return `<div class="sc-box ${cls}">${tag}${optText(opt)}${lk}</div>`;
}
// 좌(현재)/우(추천 or 가정) 정렬 + 중앙 구분선. rowsHtml은 .sc-row들.
function slotCompare(rowsHtml) { return `<div class="slot-compare">${rowsHtml}</div>`; }

function renderRecs(recs) {
  const list = $("rc-list");
  list.innerHTML = "";
  recs.forEach((r, idx) => {
    const card = document.createElement("div");
    card.className = "rec-card";
    const cross = r.crossGrade ? `<span class="cross">승급</span>` : "";
    // 슬롯 박스 비교(현재 → 추천). 열 제목에 등급도 표기(승급 시 좌우 등급이 다름).
    let rows = `<div class="sc-row sc-titles"><div class="sc-col-title">현재 · ${gradeText(r.curGrade)}</div><div class="sc-col-title">추천 · ${gradeText(r.grade)}</div></div>`;
    r.picks.forEach((p, i) => {
      let lc = "neutral", rc = "neutral";
      if (p.locked) { lc = rc = "locked"; }
      else if (p.changed) { lc = "before"; rc = "after"; }
      rows += `<div class="sc-row">${scBox(p.cur, lc, i + 1, p.locked)}${scBox(p.rec, rc, i + 1, p.locked)}</div>`;
    });
    const avgTxt = r.recCubes == null ? "∞" : fmt(r.recCubes);
    card.innerHTML =
      `<div class="rec-head"><span class="rank">${idx + 1}</span><b>${r.part} ${r.upper ? "윗잠" : "아랫잠"}</b>${cross}` +
      `<span class="rec-avg" title="이 추천 옵션을 얻기까지 평균 큐브 수(승급 추천은 승급 비용까지 포함)">평균 <b>${avgTxt}</b>개</span></div>` +
      `<div class="rec-metrics">` +
      `<span>딜 증가량 <b class="good">${signPct(r.dealDiff)}</b></span>` +
      `<span title="한 큐브 리롤로 지금 끼고 있는 옵션보다 딜이 높은 구성이 나올 확률">1회 리롤·현재보다↑ <b>${pct(r.totalProb)}</b></span>` +
      `<span title="한 큐브 리롤로 이 추천 옵션보다도 딜이 높은 구성이 나올 확률">1회 리롤·추천보다↑ <b>${pct(r.remainProb)}</b></span>` +
      `<span>효율 <b>${fmtScore(r.gainEff * 1e6)}</b></span>` +
      `</div>${slotCompare(rows)}`;
    list.appendChild(card);
  });
}

$("rc-sort").addEventListener("change", () => runRecommend(false));
$("rc-upper").addEventListener("change", () => runRecommend(false));
$("rc-lower").addEventListener("change", () => runRecommend(false));
$("rc-slider").addEventListener("input", () => { $("rc-slider-val").textContent = $("rc-slider").value; });
$("rc-slider").addEventListener("change", () => runRecommend(false));

// ──────────────────── 옵션 비교(좌/우 자유 선택) ────────────────────

let ocBuilt = false;

function ocBuildSide(prefix) {
  const partSel = $(prefix + "-part"), gradeSel = $(prefix + "-grade");
  partSel.innerHTML = "";
  for (const p of meta.parts) partSel.add(new Option(p, p));
  fillGradeSelect(gradeSel);
  gradeSel.value = meta.grades[meta.grades.length - 1];
  applyGradeColor(gradeSel);
  partSel.addEventListener("change", () => ocFillSlots(prefix));
  gradeSel.addEventListener("change", () => ocFillSlots(prefix));
}

async function ocFillSlots(prefix) {
  const tables = await getSlots($(prefix + "-part").value, $(prefix + "-grade").value);
  const box = $(prefix + "-slots");
  box.innerHTML = "";
  for (let i = 0; i < 3; i++) {
    const row = document.createElement("div");
    row.className = "slot-row";
    const sel = document.createElement("select");
    sel.id = prefix + "-" + i;
    fillSlotSelect(sel, tables[i] || [], null);
    row.appendChild(sel);
    box.appendChild(row);
  }
}

async function renderOptCompare() {
  if (!ocBuilt) { ocBuildSide("oc-l"); ocBuildSide("oc-r"); ocBuilt = true; }
  await ocFillSlots("oc-l");
  await ocFillSlots("oc-r");
}

function ocCollect(prefix) {
  const out = [];
  for (let i = 0; i < 3; i++) {
    const p = parseSlotKey($(prefix + "-" + i).value);
    if (p) out.push({ statId: p.stat, value: p.value });
  }
  return out;
}

function ocResultHtml(s) {
  if (s.empty) return '<p class="status">옵션을 선택하세요.</p>';
  const inc = s.qual ? '<span class="bad">계산 불가</span>' : `<span class="good">${signPct(s.inc)}</span>`;
  const cubes = s.prob > 0 ? fmt(1 / s.prob) + "개" : "-";
  const eff = s.qual ? "계산 불가" : (s.prob > 0 ? fmtScore(s.prob * s.inc * 1e4) : "-");
  return `<div class="cmp-grid">` +
    cell("딜 증가량", `<b>${inc}</b>`) +
    cell("동일 옵션 등장 확률 총합", pct(s.prob)) +
    cell("큐브 기댓값", cubes) +
    cell("효율", eff) +
    `</div>`;
}

async function runOptCompare() {
  const done = deferLoading("oc-status");
  try {
    const data = await postJson("/api/optcompare", {
      left: { part: $("oc-l-part").value, grade: $("oc-l-grade").value, opts: ocCollect("oc-l") },
      right: { part: $("oc-r-part").value, grade: $("oc-r-grade").value, opts: ocCollect("oc-r") },
      stats: collectStats(), conversions: myConv, ...targetForApi(), ...companionParams(), buffs: activeBuffs(), hitInterval: myHitInterval,
    });
    $("oc-l-result").innerHTML = ocResultHtml(data.left);
    $("oc-r-result").innerHTML = ocResultHtml(data.right);
    $("oc-status").textContent = "";
  } catch (e) { $("oc-status").textContent = "오류: " + e.message; }
  finally { done(); }
}
$("oc-btn").addEventListener("click", runOptCompare);

// ──────────────────── 장비 비교(프리셋 없음, 단발 비교/변경) ────────────────────
// 장비 = 주옵션(장비별 고정 3종, 일부 비딜) + 부옵션(부스탯, 공통 딜 + 부위별 특옵).
// 값은 사용자가 직접 입력. 비교 방식 = 현재 총스탯에서 왼쪽(현재) 옵션을 빼고 오른쪽(새 장비)을 얹은 딜과 비교.

let gearCfg = null;      // /api/gear-options 캐시 {nonDealLabels, commonSub, parts[]}
let gearBuilt = false;

async function loadGearCfg() {
  if (!gearCfg) gearCfg = await (await fetch("/api/gear-options")).json();
  return gearCfg;
}

// 비딜 스탯(최대HP·방어력 등)은 stats.json에 없어 config 라벨을, 딜 스탯은 statDefs 라벨을 쓴다.
function gearStatLabel(id) {
  return (gearCfg && gearCfg.nonDealLabels && gearCfg.nonDealLabels[id]) || statLabel(id);
}
function gcPartDef() {
  const p = $("gc-part").value;
  return gearCfg.parts.find((x) => x.part === p) || gearCfg.parts[0];
}
// 이 side의 특옵 목록(투구는 종류 선택에 따라 달라짐).
function gcSpecials(prefix) {
  const def = gcPartDef();
  if (def.variants) {
    const sel = $(prefix + "-variant-sel");
    const v = sel ? def.variants.find((x) => x.name === sel.value) : null;
    return (v || def.variants[0]).special || [];
  }
  return def.special || [];
}
// 부옵션 후보 스탯 = 공통 + 부위별 특옵.
function gcSubPool(prefix) { return gearCfg.commonSub.concat(gcSpecials(prefix)); }

// ──────────────────── 스탯 비교(등가 환산) ────────────────────
// 한 스탯에 값을 넣으면 그 스탯이 올려주는 딜과 같은 딜을 내는 다른 스탯의 필요량을 서버가 계산해 채운다.
// 어느 입력이든 값을 넣으면 그게 '기준'이 되고, 다른 입력에 값을 넣으면 기준이 바뀐다(계산은 프리셋·대상 기준).
let scSource = null;   // {statId, value} 현재 기준
let scTimer = null;

function scStatDefs() {
  return (statDefs || []).filter((d) => !d.hidden && d.id !== "LEVEL" && d.id !== "BASIC_ATK_TARGETS" && d.id !== "JOB");
}

// 스탯 비교 전용 소분류(종류별로 묶어 한눈에 보이게). stats.json 순서는 캐릭터 폼이 '스탯창 순서'로 쓰므로 건드리지 않고 여기서만 그룹핑.
const SC_GROUPS = [
  { title: "+ 스탯", ids: ["ATK_FLAT", "MAIN_STAT_FLAT", "SUB_STAT_FLAT", "DEFENSE"] },
  { title: "% 스탯", ids: ["ATK_PCT", "MAIN_STAT_PCT", "STAT_PROP_DMG", "CRIT_RATE", "CRIT_DMG", "DMG", "DMG_AMP", "BASIC_ATK_DMG", "SKILL_DMG", "BOSS_DMG", "NORMAL_DMG", "MIN_DMG_MULT", "MAX_DMG_MULT"] },
  { title: "곱연산 스탯", ids: ["DEF_PEN", "ATK_SPEED", "FINAL_DMG"] },
  { title: "기타 스탯", ids: ["COOLDOWN_REDUCTION_PCT", "COOLDOWN_REDUCTION", "SUMMON_DURATION", "BUFF_DURATION"] },
  { title: "스킬 레벨", ids: ["SKILL_LEVEL_1", "SKILL_LEVEL_2", "SKILL_LEVEL_3", "SKILL_LEVEL_4", "SKILL_LEVELS"] },
];

// scStatDefs를 SC_GROUPS 순서/분류로 묶어 [제목, defs[]] 배열로. 어느 그룹에도 없는 스탯은 '기타'로.
function scGroupedDefs() {
  const defById = new Map(scStatDefs().map((d) => [d.id, d]));
  const used = new Set();
  const grouped = [];
  for (const g of SC_GROUPS) {
    const defs = g.ids.map((id) => defById.get(id)).filter(Boolean);
    defs.forEach((d) => used.add(d.id));
    if (defs.length) grouped.push([g.title, defs]);
  }
  const rest = scStatDefs().filter((d) => !used.has(d.id));
  if (rest.length) grouped.push(["기타", rest]);
  return grouped;
}

function renderStatCompare() {
  const form = $("sc-form");
  if (!form) return;
  // statDefs가 준비됐고 아직 안 그렸으면(또는 직전 로드 전 빈 상태면) 구성. statDefs 갱신 시 loadStats가 다시 부른다.
  if (form.querySelectorAll(".field").length === 0 && statDefs && statDefs.length) {
    // 소분류(SC_GROUPS)로 묶어 그룹 헤더와 함께 구성. 필드 컴포넌트는 프리셋 스탯 탭과 동일.
    form.innerHTML = "";
    for (const [group, defs] of scGroupedDefs()) {
      const box = document.createElement("div");
      box.className = "group";
      box.innerHTML = `<div class="group-title">${group}</div>`;
      const grid = document.createElement("div");
      grid.className = "field-grid";
      for (const d of defs) {
        const field = document.createElement("div");
        field.className = "field";
        field.dataset.statId = d.id;
        field.innerHTML =
          `<label for="sc_${d.id}">${d.name}</label>` +
          `<div class="input-wrap"><input type="number" id="sc_${d.id}" data-stat-id="${d.id}" step="any" placeholder="0" />` +
          `<span class="unit">${UNIT_SUFFIX[d.unit] ?? ""}</span></div>` +
          `<span class="dmgx-badge sc-badge" hidden></span>`;
        grid.appendChild(field);
      }
      box.appendChild(grid);
      form.appendChild(box);
    }
    form.querySelectorAll("input[data-stat-id]").forEach((inp) => {
      inp.addEventListener("input", () => onScInput(inp));   // input 하나로 타이핑·스피너·붙여넣기 모두 커버(change는 blur 중복이라 제거)
    });
  }
  updateCubeTargetHint();   // 대상 힌트를 실제 프리셋 스테이지로(직업·스테이지 반영을 명시)
  if (scSource) runStatCompare(); else scClear();
}

function scClear() {
  $("sc-head").hidden = true;
  document.querySelectorAll("#sc-form .field").forEach((f) => f.classList.remove("sc-src"));
  scApplyQualMarks();   // 빈 상태에서도 현재 직업의 '딜 미환산' 스탯을 미리 표시(공용의 기공뎀·스킬뎀·쿨감·지속 등)
}

// qualSet(현재 직업 기준 딜 미환산 스탯)을 sc-form 뱃지에 반영. 입력 전 빈 상태 프리마크용.
function scApplyQualMarks() {
  document.querySelectorAll("#sc-form .field").forEach((f) => {
    const b = f.querySelector(".sc-badge"); if (!b) return;
    if (qualSet.has(f.dataset.statId)) { b.hidden = false; b.textContent = "딜 미환산"; b.className = "dmgx-badge sc-badge"; }
    else { b.hidden = true; b.textContent = ""; b.className = "dmgx-badge sc-badge"; }
  });
}

// 기준 외 계산된 입력·뱃지 비우기(exceptId는 유지).
function scClearComputed(exceptId) {
  document.querySelectorAll("#sc-form .field").forEach((f) => {
    if (f.dataset.statId === exceptId) return;
    const inp = f.querySelector("input"); if (inp) inp.value = "";
    const b = f.querySelector(".sc-badge"); if (b) { b.hidden = true; b.textContent = ""; }
  });
}

function onScInput(inp) {
  const id = inp.dataset.statId;
  const v = parseFloat(inp.value);
  if (isNaN(v) || v === 0) {
    if (scSource && scSource.statId === id) { scSource = null; scClearComputed(id); scClear(); }
    return;
  }
  if (scSource && scSource.statId === id && scSource.value === v) return;   // 값 변화 없으면 재계산 안 함(blur 등 중복 트리거 방지)
  scSource = { statId: id, value: v };
  document.querySelectorAll("#sc-form .field").forEach((f) => f.classList.toggle("sc-src", f.dataset.statId === id));
  clearTimeout(scTimer);
  scTimer = setTimeout(runStatCompare, 250);   // 타이핑 디바운스
}

async function runStatCompare() {
  if (!scSource) return;
  const src = scSource;
  const h = $("sc-head"); h.hidden = false; h.textContent = "계산 중…";   // 동료 포함 시 몇 초 걸릴 수 있어 진행 표시
  let data;
  try {
    data = await postJson("/api/stat-compare", {
      stats: collectStats(), conversions: myConv, ...targetForApi(), ...companionParams(),
      buffs: activeBuffs(), hitInterval: myHitInterval,
      refStatId: src.statId, refValue: src.value,
    });
  } catch (e) {
    const h = $("sc-head"); h.hidden = false;
    h.innerHTML = `<span style="color:#e07a7a">계산 실패: ${String(e)}</span>`;   // 조용한 실패 대신 화면에 표시
    return;
  }
  if (!scSource || scSource.statId !== src.statId || scSource.value !== src.value) return;   // 그새 기준이 바뀜

  const head = $("sc-head");
  head.hidden = false;
  const refLabel = `${statLabel(src.statId)} ${scFmtVal(src.value, src.statId)}`;
  if (data.refQual || !(data.inc > 0)) {
    head.innerHTML = `<span class="sc-conv"><b class="sc-conv-stat">${refLabel}</b>` +
      `<span class="sc-conv-note">현재 설정에서는 딜로 환산되지 않아요</span></span>`;
  } else {
    head.innerHTML = `<span class="sc-conv"><b class="sc-conv-stat">${refLabel}</b>` +
      `<span class="sc-conv-arrow">→</span>` +
      `<span class="sc-conv-dmg">딜 +${(data.inc * 100).toFixed(2)}%</span></span>`;
  }

  for (const row of data.rows || []) {
    const field = document.querySelector(`#sc-form .field[data-stat-id="${row.statId}"]`);
    if (!field) continue;
    const inp = field.querySelector("input");
    const badge = field.querySelector(".sc-badge");
    field.classList.toggle("sc-src", !!row.self);
    // 교차 기준(보뎀=보스 / 일뎀=직전 스테이지) 안내 라벨을 라벨 옆에 표시
    const label = field.querySelector("label");
    const existing = label.querySelector(".sc-note");
    if (existing) existing.remove();
    if (row.note) label.insertAdjacentHTML("beforeend", ` <span class="sc-note">(${row.note})</span>`);
    if (row.self) { badge.hidden = true; badge.textContent = ""; continue; }   // 기준 스탯은 유저 입력 유지
    if (row.qual) { inp.value = ""; badge.hidden = false; badge.textContent = "딜 미환산"; badge.className = "dmgx-badge sc-badge"; }
    else if (!row.reachable || row.value == null) { inp.value = ""; badge.hidden = false; badge.textContent = "달성 불가"; badge.className = "dmgx-badge sc-badge sc-unreach"; }
    else { inp.value = scFmtNum(row.value, statUnit(row.statId)); badge.hidden = true; badge.textContent = ""; badge.className = "dmgx-badge sc-badge"; }
  }
}

function scFmtVal(v, statId) { return scFmtNum(v, statUnit(statId)) + (UNIT_SUFFIX[statUnit(statId)] || ""); }
function scFmtNum(v, unit) {
  if (v == null || !isFinite(v)) return "";
  if (unit === "PCT" || unit === "SEC") return String(Math.round(v * 100) / 100);   // 소수 2자리
  if (Math.abs(v) >= 100) return String(Math.round(v));                              // 큰 +옵션: 정수(콤마 X — number input 호환)
  return String(Math.round(v * 10) / 10);                                             // 작은 +옵션: 소수 1자리
}

async function renderGear() {
  await loadGearCfg();
  if (!gearBuilt) {
    const partSel = $("gc-part");
    partSel.innerHTML = "";
    for (const p of gearCfg.parts) partSel.add(new Option(p.part, p.part));
    partSel.addEventListener("change", gcBuildBoxes);
    document.querySelectorAll("#panel-gear .gc-add").forEach((b) =>
      b.addEventListener("click", () => gcAddSubRow(b.dataset.side)));
    $("gc-btn").addEventListener("click", runGearCompare);
    $("gc-apply").addEventListener("click", runGearApply);
    // 이미지 인식(OCR): 버튼·파일선택·붙여넣기·드래그드롭
    $("gc-ocr-btn").addEventListener("click", () => $("gc-file").click());
    $("gc-file").addEventListener("change", (e) => { const f = e.target.files[0]; if (f) runGearOcr(f); e.target.value = ""; });
    document.addEventListener("paste", (e) => {
      if ($("panel-gear").hidden || !meta.ocrEnabled) return;
      const it = [...(e.clipboardData?.items || [])].find((x) => x.type.startsWith("image/"));
      if (it) { e.preventDefault(); runGearOcr(it.getAsFile()); }
    });
    const panel = $("panel-gear");
    panel.addEventListener("dragover", (e) => { if (meta.ocrEnabled) e.preventDefault(); });
    panel.addEventListener("drop", (e) => {
      const f = [...(e.dataTransfer?.files || [])].find((x) => x.type.startsWith("image/"));
      if (f) { e.preventDefault(); runGearOcr(f); }
    });
    gearBuilt = true;
  }
  $("gc-ocr").hidden = !meta.ocrEnabled;   // OCR 미설정이면 버튼 숨김
  gcBuildBoxes();
}

// 이미지 → 다운스케일(최대 1600px, JPEG) → base64. 페이로드·비용·속도 절감.
function gcReadImage(file) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      const max = 1600, scale = Math.min(1, max / Math.max(img.width, img.height));
      const w = Math.round(img.width * scale), h = Math.round(img.height * scale);
      const cv = document.createElement("canvas");
      cv.width = w; cv.height = h;
      cv.getContext("2d").drawImage(img, 0, 0, w, h);
      resolve({ base64: cv.toDataURL("image/jpeg", 0.85).split(",")[1], mimeType: "image/jpeg" });
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error("이미지를 열 수 없습니다.")); };
    img.src = url;
  });
}

// 업로드 이미지를 박스 좌우폭에 꽉 차게 미리보기로 표시(풀사이즈). 인식 중 어둡게 + 중앙 스피너.
function gcShowPreview(file) {
  const img = $("gc-preview-img");
  if (img.dataset.url) URL.revokeObjectURL(img.dataset.url);
  const url = URL.createObjectURL(file);
  img.dataset.url = url;
  img.src = url;
  $("gc-preview").hidden = false;
}

async function runGearOcr(file) {
  if (!file || !file.type.startsWith("image/")) { toast("이미지 파일이 아닙니다."); return; }
  gcShowPreview(file);
  const box = $("gc-preview");
  box.classList.add("loading");   // 어둡게 + 스피너(클래스로만 제어 → 완료 시 확실히 사라짐)
  $("gc-status").textContent = "이미지 인식 중…";
  try {
    const { base64, mimeType } = await gcReadImage(file);
    const data = await postJson("/api/gear-ocr", { image: base64, mimeType, part: $("gc-part").value });
    // 감지된 부위로 드롭다운 전환 후 박스 재구성(부위별 주옵션·부옵션 풀이 달라 안 하면 특옵 입력 불가).
    if (data.part && $("gc-part").value !== data.part) {
      $("gc-part").value = data.part;
      gcBuildBoxes();
    }
    gcApplyOcrSide("gc-l", data.left);
    gcApplyOcrSide("gc-r", data.right);
    $("gc-status").textContent = "인식 완료 — 값을 확인·수정한 뒤 비교하세요.";
    runGearCompare();
  } catch (e) { $("gc-status").textContent = "인식 실패: " + e.message; }
  finally { box.classList.remove("loading"); }
}

// OCR 결과 한 박스를 폼에 반영: 주 옵션은 입력값, 부 옵션은 줄로 재생성.
function gcApplyOcrSide(prefix, side) {
  if (!side) return;
  for (const inp of $(prefix + "-main").querySelectorAll("input.gc-val")) inp.value = "0";
  for (const o of (side.main || [])) {
    const inp = $(prefix + "-main").querySelector('input[data-stat="' + o.statId + '"]');
    if (inp) inp.value = o.value;
  }
  $(prefix + "-sub").innerHTML = "";
  for (const o of (side.sub || [])) gcAddSubRow(prefix, o.statId, o.value);
}

function gcBuildBoxes() {
  $("gc-delta").innerHTML = "";
  $("gc-status").textContent = "";
  gcBuildSide("gc-l");
  gcBuildSide("gc-r");
}

function gcBuildSide(prefix) {
  const def = gcPartDef();
  // 종류(투구=일반/자쿰의 투구) 선택 — variants 있는 부위만
  const vbox = $(prefix + "-variant");
  vbox.innerHTML = "";
  if (def.variants) {
    const lab = document.createElement("label");
    lab.className = "ctl";
    lab.textContent = "종류";
    const sel = document.createElement("select");
    sel.id = prefix + "-variant-sel";
    for (const v of def.variants) sel.add(new Option(v.name, v.name));
    sel.addEventListener("change", () => gcRefreshSub(prefix));   // 특옵 풀 변경 → 부옵션 드롭다운 갱신
    lab.appendChild(sel);
    vbox.appendChild(lab);
  }
  // 주옵션(고정): 비딜은 "딜 미반영" 표시하되 입력창은 띄움
  const mbox = $(prefix + "-main");
  mbox.innerHTML = "";
  for (const m of def.main) {
    const row = document.createElement("div");
    row.className = "gc-row";
    row.innerHTML = `<span class="gc-name">${gearStatLabel(m.stat)}${m.deal ? "" : ' <span class="gc-nodeal">딜 미반영</span>'}</span>`;
    const inp = document.createElement("input");
    inp.type = "number"; inp.className = "gc-val"; inp.value = "0";
    inp.dataset.stat = m.stat; inp.dataset.deal = m.deal ? "1" : "0";
    row.appendChild(inp);
    mbox.appendChild(row);
  }
  $(prefix + "-sub").innerHTML = "";   // 부옵션은 빈 상태로 시작(유저가 추가)
}

// 부옵션 한 줄(스탯 드롭다운 + 값 + 삭제).
function gcAddSubRow(prefix, stat, value) {
  const box = $(prefix + "-sub");
  const row = document.createElement("div");
  row.className = "gc-row gc-subrow";
  const sel = document.createElement("select");
  sel.className = "gc-substat";
  for (const s of gcSubPool(prefix)) sel.add(new Option(statLabel(s), s));
  if (stat) sel.value = stat;
  const inp = document.createElement("input");
  inp.type = "number"; inp.className = "gc-val"; inp.value = value != null ? value : "0";
  const del = document.createElement("button");
  del.type = "button"; del.className = "gc-del"; del.textContent = "×"; del.title = "삭제";
  del.addEventListener("click", () => row.remove());
  row.appendChild(sel); row.appendChild(inp); row.appendChild(del);
  box.appendChild(row);
}

// 종류 변경 시 기존 부옵션 줄의 드롭다운 옵션만 갱신(선택 보존).
function gcRefreshSub(prefix) {
  const pool = gcSubPool(prefix);
  for (const sel of $(prefix + "-sub").querySelectorAll(".gc-substat")) {
    const cur = sel.value;
    sel.innerHTML = "";
    for (const s of pool) sel.add(new Option(statLabel(s), s));
    if (pool.includes(cur)) sel.value = cur;
  }
}

// 딜 반영 옵션만 수집({statId,value}[]). 주옵션은 deal=1만, 부옵션은 전부.
function gcCollect(prefix) {
  const out = [];
  for (const inp of $(prefix + "-main").querySelectorAll("input.gc-val")) {
    if (inp.dataset.deal !== "1") continue;
    const v = parseFloat(inp.value);
    if (v) out.push({ statId: inp.dataset.stat, value: v });
  }
  for (const row of $(prefix + "-sub").querySelectorAll(".gc-subrow")) {
    const stat = row.querySelector(".gc-substat").value;
    const v = parseFloat(row.querySelector(".gc-val").value);
    if (stat && v) out.push({ statId: stat, value: v });
  }
  return out;
}

async function runGearCompare() {
  const done = deferLoading("gc-status");
  try {
    const data = await postJson("/api/gear-compare", Object.assign(
      { leftOpts: gcCollect("gc-l"), rightOpts: gcCollect("gc-r"),
        stats: collectStats(), conversions: myConv, buffs: activeBuffs(), hitInterval: myHitInterval },
      targetForApi(), companionParams()));
    $("gc-delta").innerHTML = gcDeltaHtml(data);
    $("gc-status").textContent = "";
  } catch (e) { $("gc-status").textContent = "오류: " + e.message; }
  finally { done(); }
}

function gcDeltaHtml(d) {
  if (d.qual) return '<div class="gc-delta-box"><span class="bad">일부 옵션은 딜 환산이 안 돼 비교가 어렵습니다.</span></div>';
  const p = d.deltaPct || 0;
  const cls = p > 0 ? "good" : (p < 0 ? "bad" : "");
  return `<div class="gc-delta-box"><span class="gc-delta-lead">딜 증가량</span> <b class="${cls} gc-delta-val">${signPct(p)}</b></div>`;
}

// 오른쪽(새 장비)으로 교체 시 스탯 재계산 → 스탯 프리셋에 반영(큐브와 무관, 스탯만).
async function runGearApply() {
  const oldOpts = gcCollect("gc-l"), newOpts = gcCollect("gc-r");
  if (!oldOpts.length && !newOpts.length) { toast("입력한 옵션이 없습니다."); return; }
  if (!confirm("현재 장비(왼쪽) 옵션을 새 장비(오른쪽) 옵션으로 바꾸고 스탯 프리셋에 반영할까요?")) return;
  $("gc-status").textContent = "적용 중…";
  try {
    const data = await postJson("/api/applystats", { stats: collectStats(), conversions: myConv, oldOpts, newOpts });
    fillStats(data.stats);
    deriveStatProp();   // 스탯 비례 데미지는 주스탯+부스탯에서 새로 정확히 파생(엔진 증분값은 폼의 버림된 기존값 위에 더해져 소수 유실). 프리셋 저장 전에 갱신.
    statPresets.presets[statPresets.active] = statPresetPayload();
    saveJSON(STATP_KEY, statPresets);
    statDirty = false;
    calcDamage();
    $("gc-status").textContent = "";
    toast("변경 적용 — 스탯 프리셋에 저장됨");
  } catch (e) { $("gc-status").textContent = "오류: " + e.message; }
}

// ──────────────────── 내 옵션 비교 ────────────────────

function statLabel(id) { const d = statDefs.find((x) => x.id === id); return d ? d.name : id; }
function statUnit(id) { const d = statDefs.find((x) => x.id === id); return d ? d.unit : ""; }

// 옵션 박스 안 내용(읽기전용). 노랑은 금색 글씨 + 배지.
// 옵션 표기: 이름에서 단위(%·(+)·(+초)) 떼고 값 뒤에 단위 붙임 (예: "공격 속도 10%").
const OPT_SUFFIX = { PCT: "%", SEC: "초" };
function optLabel(opt) {
  return `${cleanName(opt.name)} ${opt.value}${OPT_SUFFIX[opt.unit] || ""}`;
}

function optText(opt) {
  if (!opt) return '<span class="muted">(비움)</span>';
  const star = opt.special ? "★ " : "";
  const col = opt.yellow ? ' style="color:#e7c14a"' : "";
  return `<span${col}>${star}${optLabel(opt)}</span>`;
}

let cmpRightGrade = null;

// 비교 편집기: 등급 + 3슬롯을 좌(현재·읽기전용)/우(가정·선택)로 같은 행에 정렬해 비교하기 쉽게.
async function renderCompareEditor(resetGrade) {
  const part = $("cmp-part").value;
  const lineName = $("cmp-line").value === "upper" ? "윗잠" : "아랫잠";
  const line = myCube[part] && myCube[part][lineName];
  const highest = meta.grades[meta.grades.length - 1];
  if (resetGrade || !cmpRightGrade) cmpRightGrade = (line && line.grade) ? line.grade : highest;
  if (resetGrade) $("cmp-stack").value = (line && line.stack) ? line.stack : 0;   // 변경 후 스택 기본값=현재 스택

  const leftTables = (line && line.grade) ? await getSlots(part, line.grade) : null;
  const rightTables = await getSlots(part, cmpRightGrade);

  // 옵션 비교 탭과 동일한 좌우 카드(.opt-sides). 왼쪽=내 장비(읽기전용, 내 큐브에서 자동) / 오른쪽=가정(편집).
  const leftGrade = (line && line.grade) ? gradeText(line.grade) : '<span class="muted">(없음)</span>';
  let leftSlots = "";
  for (let i = 0; i < 3; i++) {
    const lo = (leftTables && line.slots[i]) ? findDraw(leftTables[i] || [], line.slots[i].stat, line.slots[i].value) : null;
    leftSlots += `<div class="slot-row">${scBox(lo, "neutral")}</div>`;   // 번호 없이 — 오른쪽 드롭다운과 1:1 대칭
  }
  $("cmp-editor").innerHTML =
    `<div class="opt-sides">` +
      `<div class="opt-side">` +
        `<h3>현재 옵션</h3>` +
        `<div class="controls ce-rg-row"><label class="ctl">등급<span class="ce-grade-box">${leftGrade}</span></label></div>` +
        `${leftSlots}` +
        `<div id="ce-l-result" class="opt-result"></div>` +
      `</div>` +
      `<div class="opt-side">` +
        `<h3>비교 옵션</h3>` +
        `<div class="controls ce-rg-row"><label class="ctl">등급<span id="ce-rg"></span></label></div>` +
        `<div id="ce-rslots"></div>` +
        `<div id="ce-r-result" class="opt-result"></div>` +
      `</div>` +
    `</div>`;
  $("cmp-diff").innerHTML = "";   // 편집 시 이전 결과(딜 차이) 제거 — 다시 '비교' 눌러야 갱신

  // 우측 등급 select(동적 생성, id 유지)
  const gsel = document.createElement("select");
  gsel.id = "cmp-right-grade";
  fillGradeSelect(gsel); gsel.value = cmpRightGrade; applyGradeColor(gsel);
  gsel.addEventListener("change", () => { cmpRightGrade = gsel.value; renderCompareEditor(false); });
  $("ce-rg").appendChild(gsel);

  // 우측 슬롯 select
  const rsBox = $("ce-rslots");
  for (let i = 0; i < 3; i++) {
    const row = document.createElement("div"); row.className = "slot-row";
    const sel = document.createElement("select");
    sel.id = "cmp-right-" + i;
    fillSlotSelect(sel, rightTables[i] || [], null);
    row.appendChild(sel); rsBox.appendChild(row);
  }
}

async function runCompare() {
  const done = deferLoading("cmp-status");   // 결과는 안 지움(이전 결과 유지) → 끝에서 교체
  try {
    const rightOpts = [];
    for (let i = 0; i < 3; i++) {
      const p = parseSlotKey($("cmp-right-" + i).value);
      if (p) rightOpts.push({ statId: p.stat, value: p.value });
    }
    const data = await postJson("/api/compare", {
      stats: collectStats(), conversions: myConv, ...targetForApi(), ...companionParams(),
      cubeState: myCube, ranges: myRanges, buffs: activeBuffs(), hitInterval: myHitInterval,
      part: $("cmp-part").value, upper: $("cmp-line").value === "upper",
      rightGrade: $("cmp-right-grade").value, rightOpts,
    });
    if (!data.valid) { $("cmp-status").textContent = "현재(내 큐브 옵션)가 비어 있어 비교할 수 없습니다. '내 큐브' 탭에서 입력하세요."; return; }
    $("cmp-status").textContent = "";
    // 좌/우 카드 = 옵션 비교 탭과 동일 형식(딜 증가량·등장 확률·큐브 기댓값·효율)
    if ($("ce-l-result")) $("ce-l-result").innerHTML = ocResultHtml({ inc: data.leftDeal, prob: data.leftProbSum });
    if ($("ce-r-result")) $("ce-r-result").innerHTML = ocResultHtml({ inc: data.rightDeal, prob: data.rightProbSum });
    // 하단(버튼 위) = 내 장비 → 가정 딜 증가량 차이만 크게
    const lose = data.dealDiff <= 0;
    $("cmp-diff").innerHTML =
      `<div class="cmp-diff-box">` +
        `<div class="cmp-diff-head">딜 증가량</div>` +
        `<div class="cmp-diff-val ${lose ? "bad" : "good"}">${signPct(data.dealDiff)}</div>` +
      `</div>`;
  } catch (e) { $("cmp-status").textContent = "오류: " + e.message; }
  finally { done(); }
}

function cell(label, val) { return `<div class="cmp-cell"><div class="cmp-label">${label}</div><div class="cmp-val">${val}</div></div>`; }
function fmtEff(x) { return x == null ? "—" : fmtScore(x * 1e6); }

// 가정(오른쪽) 옵션을 실제로 적용 → 스탯·큐브 프리셋에 즉시 반영(저장).
async function runApply() {
  const part = $("cmp-part").value;
  const lineName = $("cmp-line").value === "upper" ? "윗잠" : "아랫잠";
  const line = myCube[part] && myCube[part][lineName];
  const rightSlots = [0, 1, 2].map((i) => parseSlotKey($("cmp-right-" + i).value));   // {stat,value}|null
  const newGrade = $("cmp-right-grade").value;
  const newStack = parseInt($("cmp-stack").value, 10) || 0;
  const oldOpts = (line && line.slots) ? line.slots.filter(Boolean).map((s) => ({ statId: s.stat, value: s.value })) : [];
  const newOpts = rightSlots.filter(Boolean).map((s) => ({ statId: s.stat, value: s.value }));
  if (!newOpts.length && !oldOpts.length) { toast("적용할 옵션이 없습니다."); return; }
  if (!confirm(`${part} ${lineName}을(를) 오른쪽(가정) 옵션으로 변경하고 스탯·큐브 프리셋에 저장할까요?`)) return;

  $("cmp-status").textContent = "적용 중…";
  try {
    // 부스탯(깡부스탯·부스탯%)은 프리셋 부스탯(+)이 이미 '최종값'이라 서버 SUM에 raw로 더하면 오염된다.
    // → 서버엔 부스탯 외 옵션만 보내고, 부스탯은 아래 myCube 파생으로 클라가 전담한다.
    const isSub = (id) => id === "SUB_STAT_FLAT" || id === "SUB_STAT_PCT";
    const oldOptsSrv = oldOpts.filter((o) => !isSub(o.statId));
    const newOptsSrv = newOpts.filter((o) => !isSub(o.statId));
    // 1) 옵션 교체에 따른 새 스탯 총합 계산(엔진: 기존 빼고 새 옵션 더함 — 부스탯 제외)
    const data = await postJson("/api/applystats", { stats: collectStats(), conversions: myConv, oldOpts: oldOptsSrv, newOpts: newOptsSrv });
    // 2) 스탯 폼에 반영(부스탯은 서버가 안 건드림 → 기존값 유지, 아래 3-b에서 재파생)
    fillStats(data.stats);
    // 3) 큐브 줄 + 활성 큐브 프리셋에 반영
    if (!myCube[part]) myCube[part] = { 윗잠: emptyLine(), 아랫잠: emptyLine() };
    const locked = (line && line.locked) ? line.locked.slice() : [false, false, false];
    myCube[part][lineName] = {
      grade: newGrade,
      slots: rightSlots.map((s) => (s ? { stat: s.stat, value: s.value } : null)),
      locked, stack: newStack,
    };
    // 3-b) 부스탯 = 갱신된 myCube 전체에서 깡부스탯합 × (1+부스탯%합/100)로 파생해 프리셋 부스탯(+)에 반영
    const subInput = $("stat-form").querySelector('input[data-stat-id="SUB_STAT_FLAT"]');
    if (subInput) setStatValue(subInput, cubeSubStatFinal(myCube), "FLAT");   // 화면=표기, 계산=풀값
    deriveStatProp();   // 스탯 비례 데미지는 주스탯+부스탯에서 새로 정확히 파생. 프리셋 저장 전에 갱신.
    // 4) 활성 스탯 프리셋에 반영(로그인 시 서버 동기화도 트리거)
    statPresets.presets[statPresets.active] = statPresetPayload();
    saveJSON(STATP_KEY, statPresets);
    statDirty = false;
    saveCube();
    cubePresets.presets[cubePresets.active] = clone(myCube);
    saveJSON(CUBEP_KEY, cubePresets);
    cubeDirty = false; cubeNeedsRender = true;
    // 4) 화면 갱신(왼쪽=현재가 방금 변경값으로)
    calcDamage();
    await renderCompareEditor(true);
    $("cmp-status").textContent = "";
    toast("변경 적용 — 스탯·큐브 프리셋에 저장됨");
  } catch (e) { $("cmp-status").textContent = "오류: " + e.message; }
}

$("cmp-part").addEventListener("change", () => renderCompareEditor(true));
$("cmp-line").addEventListener("change", () => renderCompareEditor(true));
$("cmp-btn").addEventListener("click", runCompare);
$("cmp-apply").addEventListener("click", runApply);

// ──────────────────── 내 값 불러오기(개발 편의) ────────────────────

// 스탯 입력 폼을 주어진 값으로 채운다(없는 스탯은 0).
// double 덧뺄셈에서 생기는 부동소수점 노이즈(예: 321.90000000000003) 제거.
function fillStats(stats) {
  for (const input of document.querySelectorAll("#sub-build input[data-stat-id]")) {   // 헤더 레벨 + 폼 필드
    const def = statDefById(input.dataset.statId);
    const v = stats[input.dataset.statId];
    setStatValue(input, v == null ? null : v, def ? def.unit : "FLAT");   // 화면=표기(버림), 계산 원본=풀값 보존
  }
}

// 서버에 저장된 내 데이터(스탯·환산·큐브)를 불러온다.
// overwriteCube=true면 큐브도 덮어쓴다(버튼). 자동 로드 시엔 내 큐브가 비어 있을 때만 채운다.
async function loadMyData(overwriteCube, pre) {
  try {
    const data = pre || await (await fetch("/api/mydata")).json();
    if (data.stats && Object.keys(data.stats).length) {
      fillStats(data.stats);
      if (data.stats.JOB != null) setJob(Math.round(data.stats.JOB));   // 직업 단일 동기(myBuffJob 포함)
    }
    if (overwriteCube || !localStorage.getItem(CONV_KEY)) {
      myConv = data.conversions || {};
      saveJSON(CONV_KEY, myConv);
      renderConvSettings();
    }
    if (!booting) await refreshQual();   // 초기화 중엔 맨 끝에서 1번만(중복 왕복 제거)
    if (data.cubeState && Object.keys(data.cubeState).length && (overwriteCube || !Object.keys(myCube).length)) {
      myCube = data.cubeState;
      saveCube();
      await renderCube();
    }
    return true;
  } catch (e) { console.error("내 값 불러오기 실패", e); return false; }
}

// ──────────────────── 설정: 딜 환산 · 추천 범위 ────────────────────

function renderConvSettings() {
  const box = $("conv-list");
  if (!box) return;
  box.innerHTML = "";
  for (const d of (meta.convertibleStats || [])) {
    const on = myConv[d.id] != null;
    const unitNote = OPT_SUFFIX[d.unit] || (d.unit === "LEVEL" ? "Lv" : "");
    const row = document.createElement("div");
    row.className = "set-row";
    row.innerHTML =
      `<label class="set-chk"><input type="checkbox" ${on ? "checked" : ""}/> ${cleanName(d.name)}</label>` +
      `<div class="set-input"><input type="number" step="any" value="${on ? myConv[d.id] : 0}" ${on ? "" : "disabled"}/>` +
      `<span class="unit">% / 1${unitNote}</span></div>`;
    const chk = row.querySelector("input[type=checkbox]");
    const num = row.querySelector("input[type=number]");
    chk.addEventListener("change", () => {
      if (chk.checked) { myConv[d.id] = parseFloat(num.value) || 0; num.disabled = false; }
      else { delete myConv[d.id]; num.disabled = true; }
      saveJSON(CONV_KEY, myConv); refreshQual();
    });
    num.addEventListener("change", () => { if (chk.checked) { myConv[d.id] = parseFloat(num.value) || 0; saveJSON(CONV_KEY, myConv); } });
    box.appendChild(row);
  }
}

function renderRangeSettings() {
  const box = $("range-list");
  if (!box) return;
  box.innerHTML = "";
  for (const d of (meta.rangeStats || [])) {
    const r = myRanges[d.id] || { enabled: false, lo: 0, hi: 0, noLow: false, noHigh: false };
    const sfx = OPT_SUFFIX[d.unit] || "";
    const row = document.createElement("div");
    row.className = "set-row range-row";
    row.innerHTML =
      `<label class="set-chk"><input type="checkbox" class="en" ${r.enabled ? "checked" : ""}/> ${cleanName(d.name)}</label>` +
      `<div class="set-input range-bounds">` +
        `<div class="bound-row">` +
          `<label class="mini-chk"><input type="checkbox" class="nl" ${r.noLow ? "checked" : ""}/>하한없음</label>` +
          `<input type="number" step="any" class="lo" value="${r.lo}"/>` +
          `<span class="unit">${sfx}</span>` +
        `</div>` +
        `<span class="tilde">~</span>` +
        `<div class="bound-row">` +
          `<label class="mini-chk"><input type="checkbox" class="nh" ${r.noHigh ? "checked" : ""}/>상한없음</label>` +
          `<input type="number" step="any" class="hi" value="${r.hi}"/>` +
          `<span class="unit">${sfx}</span>` +
        `</div>` +
      `</div>`;
    const en = row.querySelector(".en"), nl = row.querySelector(".nl"), nh = row.querySelector(".nh");
    const lo = row.querySelector(".lo"), hi = row.querySelector(".hi");
    const sync = () => {
      nl.disabled = nh.disabled = !en.checked;
      lo.disabled = !en.checked || nl.checked;
      hi.disabled = !en.checked || nh.checked;
    };
    const upd = () => {
      myRanges[d.id] = {
        enabled: en.checked, lo: parseFloat(lo.value) || 0, hi: parseFloat(hi.value) || 0,
        noLow: nl.checked, noHigh: nh.checked,
      };
      sync();
      saveJSON(RANGE_KEY, myRanges);
    };
    [en, nl, nh, lo, hi].forEach((el) => el.addEventListener("change", upd));
    sync();
    box.appendChild(row);
  }
}

// ──────────────────── 프리셋: 스탯 · 큐브 ────────────────────

function fillPresetSel(sel, store) {
  sel.innerHTML = "";
  for (const name of Object.keys(store.presets)) sel.add(new Option(name, name));
  sel.value = store.active;
}

// 빌드 프리셋 적용(스탯+직업+대상+스킬). skipRecompute=true면 폼만 채우고 재계산 건너뜀(초기화 중 중복 방지).
function spApply(name, skipRecompute) {
  const p = statPresets.presets[name];
  if (!p) return;
  fillStats(p.stats);
  setJob(Math.round((p.stats && p.stats.JOB) || 0));   // 직업 단일 동기(스킬 직업/myBuffJob 포함)
  deriveStatProp();   // 저장값은 옛 공식 기준일 수 있어 스탯 비례 데미지%를 현재 주스탯·부스탯·방어력·레벨·스킬레벨로 재파생(stale 교정)
  if ("buffs" in p) myBuffs = Array.isArray(p.buffs) ? p.buffs.slice() : null;   // 빌드에 저장된 스킬 선택
  myHitInterval = p.hitInterval || 0;
  syncTargetSelector();   // 대상(챕터/스테이지)도 프리셋 값으로 — 직업처럼 함께 전환
  statDirty = false; buffDirty = false;   // 프리셋 적용 = 저장된 상태
  if (skipRecompute) return;
  renderBuffs();   // 스킬 목록/선택 반영(직업·버프 선택 바뀔 수 있음)
  refreshQual();
  recalcActivePanel();   // 프리셋 전환 시 보이는 패널까지 즉시 재계산(대상 반영 딜 포함)
}
// 구 buffPresets(스킬 프리셋)를 빌드(statPresets)로 1회 병합: buffs 필드 없는 프리셋에 동일이름 우선, 없으면 활성 스킬 프리셋 값.
function migrateBuildBuffs() {
  if (!statPresets || !statPresets.presets) return;
  const bp = buffPresets && buffPresets.presets;
  const active = bp && buffPresets.active ? bp[buffPresets.active] : null;
  for (const [name, p] of Object.entries(statPresets.presets)) {
    if ("buffs" in p) continue;
    const src = (bp && bp[name]) || active || {};
    p.buffs = Array.isArray(src.buffs) ? src.buffs.slice() : null;
    p.hitInterval = src.hitInterval || 0;
  }
}
function initStatPresets() {
  if (!statPresets) statPresets = { active: "기본", presets: { "기본": statPresetPayload() } };
  else { migrateBuildBuffs(); spApply(statPresets.active, booting); }   // 스킬 병합 후 적용(초기화 중엔 재계산 미룸)
  saveJSON(STATP_KEY, statPresets);
  fillPresetSel($("sp-sel"), statPresets);
  statDirty = false;
}
$("sp-sel").addEventListener("change", () => {
  if (!canLeaveStat()) { $("sp-sel").value = statPresets.active; return; }   // 미저장이면 취소 시 되돌림
  statPresets.active = $("sp-sel").value; saveJSON(STATP_KEY, statPresets); spApply(statPresets.active);
});
$("sp-save").addEventListener("click", () => { statPresets.presets[statPresets.active] = statPresetPayload(); saveJSON(STATP_KEY, statPresets); statDirty = false; buffDirty = false; calcDamage(); toast(`'${statPresets.active}' 캐릭터 프리셋 저장됨`); });
$("sp-new").addEventListener("click", () => {
  const name = (prompt("새 스탯 프리셋 이름") || "").trim();
  if (!name || statPresets.presets[name]) return;
  statPresets.presets[name] = statPresetPayload();
  statPresets.active = name; saveJSON(STATP_KEY, statPresets); fillPresetSel($("sp-sel"), statPresets);
  statDirty = false;   // 현재 입력이 새 프리셋으로 저장됨
});
$("sp-del").addEventListener("click", () => {
  const names = Object.keys(statPresets.presets);
  if (names.length <= 1) return;
  delete statPresets.presets[statPresets.active];
  statPresets.active = Object.keys(statPresets.presets)[0];
  saveJSON(STATP_KEY, statPresets); fillPresetSel($("sp-sel"), statPresets); spApply(statPresets.active);
});
$("sp-rename").addEventListener("click", () => {
  if (renamePreset(statPresets) == null) return;
  saveJSON(STATP_KEY, statPresets); fillPresetSel($("sp-sel"), statPresets);
  toast("스탯 프리셋 이름을 변경했습니다.");
});

// 활성 프리셋 이름 변경(목록 순서 유지). store={active, presets:{name:val}}.
function renamePreset(store) {
  const old = store.active;
  const name = (prompt("프리셋 이름 변경", old) || "").trim();
  if (!name || name === old) return null;
  if (store.presets[name]) { toast("이미 같은 이름의 프리셋이 있습니다."); return null; }
  const renamed = {};
  for (const k of Object.keys(store.presets)) renamed[k === old ? name : k] = store.presets[k];
  store.presets = renamed;
  store.active = name;
  return name;
}

// 큐브 프리셋
async function cpApply(name) {
  const c = cubePresets.presets[name];
  if (!c) return;
  myCube = clone(c); saveCube(); cubeDirty = false;
  // 큐브 탭이 보일 때만 즉시 렌더, 아니면 지연(슬롯 조회가 초기 로드를 막지 않게)
  if (currentSub === "cube") await renderCube(); else cubeNeedsRender = true;
  recalcActivePanel();   // 큐브 프리셋 전환 시 보이는 계산(추천·비교 등) 즉시 갱신
}
async function initCubePresets() {
  if (!cubePresets) cubePresets = { active: "기본", presets: { "기본": clone(myCube) } };
  else await cpApply(cubePresets.active);
  saveJSON(CUBEP_KEY, cubePresets);
  fillPresetSel($("cp-sel"), cubePresets);
  cubeDirty = false;
}
// 페이지를 실제로 떠날 때(새로고침·닫기) 미저장 큐브 변경 경고.
window.addEventListener("beforeunload", (e) => { if (cubeDirty || statDirty || buffDirty) { e.preventDefault(); e.returnValue = ""; } });
$("cp-sel").addEventListener("change", async () => {
  if (!canLeaveCube()) { $("cp-sel").value = cubePresets.active; return; }   // 미저장이면 취소 시 되돌림
  cubePresets.active = $("cp-sel").value; saveJSON(CUBEP_KEY, cubePresets); await cpApply(cubePresets.active);
});
$("cp-save").addEventListener("click", () => { cubePresets.presets[cubePresets.active] = clone(myCube); saveJSON(CUBEP_KEY, cubePresets); cubeDirty = false; toast(`'${cubePresets.active}' 큐브 프리셋 저장됨`); });
$("cp-new").addEventListener("click", () => {
  const name = (prompt("새 큐브 프리셋 이름") || "").trim();
  if (!name || cubePresets.presets[name]) return;
  cubePresets.presets[name] = clone(myCube);
  cubePresets.active = name; saveJSON(CUBEP_KEY, cubePresets); fillPresetSel($("cp-sel"), cubePresets);
  cubeDirty = false;   // 현재 작업본이 새 프리셋으로 저장됨
});
$("cp-del").addEventListener("click", async () => {
  const names = Object.keys(cubePresets.presets);
  if (names.length <= 1) return;
  delete cubePresets.presets[cubePresets.active];
  cubePresets.active = Object.keys(cubePresets.presets)[0];
  saveJSON(CUBEP_KEY, cubePresets); fillPresetSel($("cp-sel"), cubePresets); await cpApply(cubePresets.active);
});
$("cp-rename").addEventListener("click", () => {
  if (renamePreset(cubePresets) == null) return;
  saveJSON(CUBEP_KEY, cubePresets); fillPresetSel($("cp-sel"), cubePresets);
  toast("큐브 프리셋 이름을 변경했습니다.");
});

// ──────────────────── 버프 선택(편집기) ────────────────────
// (구 스킬 프리셋 바 bpApply/initBuffPresets는 캐릭터 프리셋 통합으로 제거됨. 직업은 setJob 단일 소스, 스킬 선택은 myBuffs.)

// 스킬 탭 데이터 캐시(딜 스킬 + 버프). 직전 응답을 localStorage에도 보관 → 도메인(터널 지연)에서도 즉시 표시.
let skillData = loadJSON(CACHE_SKILLS, null);
let skillView = "deal";   // "deal"(딜 스킬) | "buff"(버프 스킬)

// DPS 모델(스킬 데이터)이 있는 직업 = 나이트로드(1)·다크나이트(2)·비숍(3). 직업 추가 시 여기만 늘리면 됨.
function jobHasSkills(job) { return job === 1 || job === 2 || job === 3; }

// 현재 스탯 기준 스킬/버프 목록을 그린다. 캐시/메모리에 있으면 즉시 렌더(불러오는 중 안 띄움), 항상 백그라운드로 최신화.
async function renderBuffs() {
  if ($("hit-interval")) $("hit-interval").value = myHitInterval;
  syncSkillViewButtons();
  if (!jobHasSkills(myBuffJob)) {   // DPS 모델 있는 직업만(나이트로드·다크나이트). 공용 등은 준비 중.
    const soon = '<div class="buff-soon"><div class="soon-icon">🚧</div>'
      + '<p>타 직업 스킬 선택 기능은 아직 준비 중이에요. 곧 추가될 예정입니다.</p></div>';
    $("deal-list").innerHTML = soon; $("buff-list").innerHTML = soon;
    return;
  }
  if (skillData && skillData.job === myBuffJob) renderActiveSkillView();   // 즉시(캐시/메모리)
  else { const a = skillView === "deal" ? $("deal-list") : $("buff-list"); a.innerHTML = '<p class="status">불러오는 중…</p>'; }
  await refreshSkillData();
}

// /api/buffs 백그라운드 호출 → 최신 목록으로 갱신·캐시. 캐시가 이미 있으면 실패해도 조용히.
async function refreshSkillData() {
  if (!jobHasSkills(myBuffJob)) return;
  try {
    const stats = collectStats(); stats.JOB = myBuffJob;
    const fresh = await postJson("/api/buffs", { stats });
    skillData = fresh; saveJSON(CACHE_SKILLS, fresh);
    if (currentSub === "build") renderActiveSkillView();
  } catch (e) {
    if (!skillData && currentSub === "build") {
      const a = skillView === "deal" ? $("deal-list") : $("buff-list");
      a.innerHTML = '<p class="status">오류: ' + e.message + '</p>';
    }
  }
}

function syncSkillViewButtons() {
  document.querySelectorAll(".skill-view").forEach((b) => b.classList.toggle("active", b.dataset.view === skillView));
  $("deal-list").hidden = skillView !== "deal";
  $("buff-list").hidden = skillView !== "buff";
}

function renderActiveSkillView() {
  if (!skillData) return;
  if (skillView === "deal")
    renderSkillList(skillData.dealSkills || [], "deal-list",
      [["active", "액티브"], ["passive", "패시브"], ["unlearned", "미습득 (배우지 않음)"]], "피해량");
  else
    renderSkillList(skillData.buffs || [], "buff-list",
      [["active", "액티브"], ["passive", "패시브"], ["always", "상시 패시브"], ["unlearned", "미습득 (배우지 않음)"]], "효과");
}

// items=[{id,name,open,group,locked,effects,cooldown,skillLevel,also,targets}] — 그룹 분리(가로선), 미습득은 별도.
function renderSkillList(items, containerId, order, effectLabel) {
  const list = $(containerId);
  list.innerHTML = "";
  if (!items.length) { list.innerHTML = '<p class="status">표시할 항목이 없습니다.</p>'; return; }
  const groups = {};
  for (const b of items) {
    const g = b.locked ? "unlearned" : b.group;
    (groups[g] || (groups[g] = [])).push(b);
  }
  const wrap = document.createElement("div"); wrap.className = "buff-groups";
  let first = true;
  for (const [key, title] of order) {
    if (!groups[key] || !groups[key].length) continue;
    if (!first) { const hr = document.createElement("hr"); hr.className = "buff-divider"; wrap.appendChild(hr); }
    first = false;
    const t = document.createElement("div"); t.className = "buff-group-title"; t.textContent = title;
    wrap.appendChild(t);
    for (const b of groups[key]) wrap.appendChild(buffItem(b, effectLabel));
  }
  list.appendChild(wrap);
}

function buffItem(b, effectLabel) {
  const row = document.createElement("div");
  row.className = "buff-item" + (b.locked ? " locked" : "");

  const lbl = document.createElement("label");
  lbl.className = "buff-check";
  const cb = document.createElement("input");
  cb.type = "checkbox"; cb.dataset.buffId = b.id;
  cb.checked = (myBuffs == null) ? !b.locked : myBuffs.includes(b.id);   // 기본: 습득한 것만 체크(미습득은 해제)
  cb.addEventListener("change", () => toggleSkill(b, cb.checked));
  const nm = document.createElement("span"); nm.className = "buff-name"; nm.textContent = b.name;
  lbl.appendChild(cb); lbl.appendChild(nm);
  if (b.also) { const tag = document.createElement("span"); tag.className = "buff-tag"; tag.textContent = "딜+" + b.also; lbl.appendChild(tag); }

  const cd = document.createElement("span"); cd.className = "buff-cd"; cd.textContent = b.cooldown || "";

  const info = document.createElement("button");
  info.type = "button"; info.className = "buff-info"; info.textContent = "ⓘ"; info.setAttribute("aria-label", "상세");

  // 순서: 습득 레벨 → 스킬 레벨 → 쿨타임 → (대상수) → 피해량/효과(2개 이상이면 줄마다 하나씩).
  const label = effectLabel || "효과";
  const tip = document.createElement("div"); tip.className = "buff-tip";
  tip.appendChild(tipRow("습득 레벨", "Lv " + b.open));
  tip.appendChild(tipRow("스킬 레벨", b.skillLevel || "—"));
  tip.appendChild(tipRow("쿨타임", b.cooldown || "—"));
  if (b.targets) tip.appendChild(tipRow("대상 수", b.targets));
  const effects = b.effects || [];
  if (!effects.length) tip.appendChild(tipRow(label, "—"));
  else effects.forEach((e, i) => tip.appendChild(tipRow(i === 0 ? label : "", e)));

  // ⓘ: 데스크톱은 호버(CSS)로만 표시. 모바일(hover 없음)은 탭으로 토글, 바깥을 탭하면 닫힘.
  info.addEventListener("click", (e) => {
    e.preventDefault(); e.stopPropagation();
    if (window.matchMedia("(hover: hover)").matches) return;
    const showing = tip.classList.contains("show");
    closeAllTips();
    if (!showing) tip.classList.add("show");
  });

  row.appendChild(lbl); row.appendChild(cd); row.appendChild(info); row.appendChild(tip);
  return row;
}
function closeAllTips() { document.querySelectorAll(".buff-tip.show").forEach((t) => t.classList.remove("show")); }
document.addEventListener("click", closeAllTips);   // 바깥 탭 시 모바일 툴팁 닫기(info는 stopPropagation)

function tipRow(label, value) {
  const r = document.createElement("div"); r.className = "buff-tip-row";
  const s = document.createElement("span"); s.textContent = label;
  const v = document.createElement("b"); v.textContent = value;
  r.appendChild(s); r.appendChild(v);
  return r;
}

// 현재 체크 대상 전체 스킬 id(딜+버프, 학습한 것). myBuffs 최초 구체화용 — 딜 id까지 포함해야
// 버프만 토글해도 딜 스킬이 집합에서 빠지지 않음(빠지면 추천/평가에서 모든 딜 스킬이 꺼짐).
function defaultCheckedIds() {
  const ids = new Set();
  const all = skillData ? [].concat(skillData.buffs || [], skillData.dealSkills || []) : [];
  for (const b of all) if (!b.locked) ids.add(b.id);
  return [...ids];
}

// 체크 변경 → 공유 집합(myBuffs)에 증분 반영(뷰 전환에도 유지·듀얼 스킬 자동 동기화). 즉시 재계산.
function toggleSkill(b, checked) {
  if (myBuffs == null) myBuffs = defaultCheckedIds();
  if (checked) { if (!myBuffs.includes(b.id)) myBuffs.push(b.id); }
  else myBuffs = myBuffs.filter((x) => x !== b.id);
  buffDirty = true; statDirty = true;   // 스킬도 빌드 프리셋의 일부 → 빌드 dirty
  calcDamage();
  if (b.locked && checked) toast("‘" + b.name + "’은(는) 아직 배우지 않은 스킬이라 과대평가될 수 있어요.");
  else if (!b.locked && !checked && (b.group === "passive" || b.group === "always"))
    toast("‘" + b.name + "’은(는) 원래 항상 켜져 있는 패시브라, 해제하면 과소평가될 수 있어요.");
}

// 딜/버프 뷰 토글
for (const btn of document.querySelectorAll(".skill-view")) {
  btn.addEventListener("click", () => {
    if (skillView === btn.dataset.view) return;
    skillView = btn.dataset.view;
    syncSkillViewButtons();
    if (skillData) renderActiveSkillView(); else renderBuffs();
  });
}

// 스킬 프리셋 바(bp-*)는 빌드 프리셋(sp-*)으로 통합돼 제거됨. 직업은 상단 공용 select(job-sel) 하나.
// 피격 주기 입력도 제거(항상 '안 맞기'=최고 효율 가정 → myHitInterval=0). 반격 계산코드는 백엔드에 휴면.

// 빌드 화면: 스탯/스킬 뷰 토글(한 번에 하나만 표시 → 항상 짧게)
for (const btn of document.querySelectorAll(".bv-tab")) {
  btn.addEventListener("click", () => {
    const v = btn.dataset.bv;
    document.querySelectorAll(".bv-tab").forEach((b) => b.classList.toggle("active", b === btn));
    if ($("bv-stat")) $("bv-stat").hidden = (v !== "stat");
    if ($("bv-skill")) $("bv-skill").hidden = (v !== "skill");
    if (v === "skill") renderBuffs();   // 스킬 뷰 열 때 현재 직업 스킬 목록 최신화
  });
}

// ──────────────────── 동료(메인) ────────────────────
// 서브 동료·장착효과는 이미 스탯에 포함 → 메인 동료(동료/등급/레벨/공격력 계승비율)만 입력. 계산 연동은 동료 데이터 오면.
function companionById(id) { return companions.find((c) => c.id === id) || null; }

function initAllyPresets() {
  if (!allyPresets) allyPresets = { active: "기본", presets: { "기본": {} } };
  saveJSON(ALLYP_KEY, allyPresets);
  fillPresetSel($("ap-sel"), allyPresets);
  allyDirty = false;
}

function renderAlly() {
  if (!allyPresets) initAllyPresets();
  const body = $("ally-body");
  if (!companions.length) {   // 동료 데이터 아직 없음(companions.json 빈 상태)
    body.innerHTML = '<div class="buff-soon"><div class="soon-icon">🚧</div><p>동료 데이터 준비 중이에요. 곧 추가될 예정입니다.</p></div>';
    return;
  }
  const p = allyPresets.presets[allyPresets.active] || {};
  body.innerHTML =
    '<div class="controls">' +
    '<label class="ctl">동료<select id="al-companion"></select></label>' +
    '<label class="ctl">등급<select id="al-grade"></select></label>' +
    '<label class="ctl">레벨<input id="al-level" type="number" min="1" step="1"></label>' +
    '<label class="ctl">공격력 계승 비율(%)<input id="al-ratio" type="number" min="0" step="any"></label>' +
    '</div>';
  const csel = $("al-companion");
  for (const c of companions) csel.add(new Option(c.name || c.id, c.id));
  csel.value = p.companionId || companions[0].id;
  fillAllyGrades(csel.value, p.grade);
  $("al-level").value = p.level != null ? p.level : 1;
  $("al-ratio").value = p.ratio != null ? p.ratio : 0;
  // 동료 딜은 홈 예상 데미지에서 확인. 여기선 입력만 받고, 바뀌면 홈을 갱신.
  csel.addEventListener("change", () => { fillAllyGrades(csel.value, null); allyDirty = true; calcDamage(); });
  for (const id of ["al-grade", "al-level", "al-ratio"]) $(id).addEventListener("change", () => { allyDirty = true; calcDamage(); });
}

// 큰 수 → 조/억 표기.
function fmtBig(v) {
  v = Number(v) || 0;
  if (v >= 1e12) return (v / 1e12).toFixed(2) + "조";
  if (v >= 1e8) return (v / 1e8).toFixed(2) + "억";
  if (v >= 1e4) return (v / 1e4).toFixed(1) + "만";
  return fmt(v);
}
function num1s(v) { return (Math.round((Number(v) || 0) * 10) / 10); }

function fillAllyGrades(companionId, selected) {
  const gsel = $("al-grade");
  gsel.innerHTML = "";
  const g = (companionById(companionId) || {}).grades || [];
  const grades = Array.isArray(g) ? g : Object.keys(g);   // grades는 객체({RARE:{...}}) 또는 배열 둘 다 허용
  for (const x of grades) gsel.add(new Option(gradeKo(x), x));
  if (selected && grades.includes(selected)) gsel.value = selected;
}

function collectAlly() {
  if (!$("al-companion")) return {};
  return {
    companionId: $("al-companion").value,
    grade: $("al-grade").value,
    level: parseInt($("al-level").value, 10) || 1,
    ratio: parseFloat($("al-ratio").value) || 0,
  };
}

// 예상 데미지에 합산할 활성 동료 = 동료 탭에 떠 있으면 라이브 입력, 아니면 저장된 활성 프리셋. 미선택이면 {}.
function activeCompanionSel() {
  const live = (currentSub === "ally") ? collectAlly() : null;
  const p = (live && live.companionId) ? live : (allyPresets && allyPresets.presets[allyPresets.active]) || {};
  if (!p.companionId) return {};
  return { companionId: p.companionId, grade: p.grade, level: p.level != null ? p.level : 1, ratio: p.ratio != null ? p.ratio : 0 };
}

// 활성 동료 이름(없으면 ""). 예: "캡틴".
function activeCompanionName() {
  const c = activeCompanionSel();
  if (!c || !c.companionId) return "";
  const m = companionById(c.companionId);
  return (m && (m.name || m.id)) || "";
}
// 활성 동료 표시 라벨. 동료 선택 시 "동료(이름)", 없으면 "동료". 예상 데미지 라벨에 동료 종류 표기용.
function activeCompanionLabel() {
  const nm = activeCompanionName();
  return nm ? `동료(${nm})` : "동료";
}

// 큐브 페이로드용 동료 파라미터(장비 grade/level과 키 충돌 방지: companion* 접두). 미선택이면 {}.
function companionParams() {
  const c = activeCompanionSel();
  if (!c.companionId) return {};
  return { companionId: c.companionId, companionGrade: c.grade, companionLevel: c.level, companionRatio: c.ratio };
}

function apApply(name) {
  if (!allyPresets.presets[name]) return;
  allyDirty = false;
  if (currentSub === "ally") renderAlly();
  recalcActivePanel();   // 동료 프리셋 바뀌면 홈 예상 데미지·보이는 계산 즉시 갱신(예전엔 새로고침해야 반영됐음)
}

// 저장 시 (캐릭터 레벨·동료·등급·동료 레벨·계승 비율)을 서버에 수집 → 추후 비율 공식화. 비율 0이면 생략.
function collectAllyRatio(data) {
  if (!data.companionId || !data.ratio) return;
  const charLevel = parseInt(collectStats().LEVEL, 10) || 0;
  fetch("/api/ally-ratio", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ts: new Date().toISOString(), charLevel, companionId: data.companionId, grade: data.grade, companionLevel: data.level, ratio: data.ratio }),
  }).catch(() => {});
}

$("ap-sel").addEventListener("change", () => {
  if (allyDirty && !confirm("저장하지 않은 동료 변경이 있습니다. 버리고 전환할까요?")) { $("ap-sel").value = allyPresets.active; return; }
  allyPresets.active = $("ap-sel").value; saveJSON(ALLYP_KEY, allyPresets); apApply(allyPresets.active);
});
$("ap-save").addEventListener("click", () => {
  const data = collectAlly();
  allyPresets.presets[allyPresets.active] = data;
  saveJSON(ALLYP_KEY, allyPresets); allyDirty = false;
  toast(`'${allyPresets.active}' 동료 프리셋 저장됨`);
  collectAllyRatio(data);
});
$("ap-new").addEventListener("click", () => {
  const name = (prompt("새 동료 프리셋 이름") || "").trim();
  if (!name || allyPresets.presets[name]) return;
  allyPresets.presets[name] = collectAlly();
  allyPresets.active = name; saveJSON(ALLYP_KEY, allyPresets); fillPresetSel($("ap-sel"), allyPresets);
  allyDirty = false;
});
$("ap-del").addEventListener("click", () => {
  const names = Object.keys(allyPresets.presets);
  if (names.length <= 1) return;
  delete allyPresets.presets[allyPresets.active];
  allyPresets.active = Object.keys(allyPresets.presets)[0];
  saveJSON(ALLYP_KEY, allyPresets); fillPresetSel($("ap-sel"), allyPresets); apApply(allyPresets.active);
});
$("ap-rename").addEventListener("click", () => {
  if (renamePreset(allyPresets) == null) return;
  saveJSON(ALLYP_KEY, allyPresets); fillPresetSel($("ap-sel"), allyPresets);
  toast("동료 프리셋 이름을 변경했습니다.");
});

// ──────────────────── 유물 ────────────────────
// 저장 포맷 정규화: { awaken:{id:0~5}, equipped:[id...] }. 구버전(행 배열)도 변환.
function normalizeRelic(r) {
  if (r && !Array.isArray(r) && typeof r === "object") return { awaken: r.awaken || {}, equipped: Array.isArray(r.equipped) ? r.equipped.slice() : [] };
  const out = { awaken: {}, equipped: [] };
  if (Array.isArray(r)) for (const row of r) { if (row && row.id) { out.awaken[row.id] = parseInt(row.awaken, 10) || 0; if (row.on) out.equipped.push(row.id); } }
  return out;
}
function relicHas(id) { return Object.prototype.hasOwnProperty.call(myRelic.awaken || {}, id); }   // 보유 여부(미보유=키 없음, 0각=키 있고 값 0)
function relicAwaken(id) { return parseInt((myRelic.awaken || {})[id], 10) || 0; }
function relicAwakenSum() { return Object.values(myRelic.awaken || {}).reduce((s, n) => s + (parseInt(n, 10) || 0), 0); }
function relicSlots() {
  const sum = relicAwakenSum();
  for (const t of (artifactCatalog.slotByAwakenSum || [])) {
    if (t.else) return t.slots || 4;
    if (typeof t.under === "number" && sum < t.under) return t.slots;
  }
  return 4;
}
function relicOwned() { return (artifactCatalog.list || []).filter((a) => relicHas(a.id)); }

// 딜에 반영할 장착 유물 = equipped 중 보유(0각 포함)한 것, 슬롯 수 상한. 중복 제거.
function activeRelic() {
  const slots = relicSlots(); const seen = new Set(); const out = [];
  for (const id of (myRelic.equipped || [])) {
    if (!id || seen.has(id) || !relicHas(id)) continue;
    seen.add(id); out.push({ id, awaken: relicAwaken(id) });
    if (out.length >= slots) break;
  }
  return out;
}
function saveRelic() { myRelic.equipped = (myRelic.equipped || []).filter((id) => relicHas(id)); saveJSON(RELIC_KEY, myRelic); relicDirty = true; }

const RELIC_CAT = { compute: ["딜 반영", "ok"], preReflected: ["선반영", "muted"], content: ["컨텐츠 전용", "muted"], survival: ["생존", "muted"] };
function relicCatById(id) { return ((artifactCatalog.list || []).find((a) => a.id === id) || {}).category; }
function relicBadge(id) { const m = RELIC_CAT[relicCatById(id)]; return m ? `<span class="relic-badge ${m[1]}">${m[0]}</span>` : ""; }

function initRelicPresets() {
  if (!relicPresets) relicPresets = { active: "기본", presets: { "기본": clone(myRelic) } };
  saveJSON(RELICP_KEY, relicPresets);
  fillPresetSel($("rp-sel"), relicPresets);
  relicDirty = false;
}
function rpApply(name) {
  if (!relicPresets || !relicPresets.presets[name]) return;
  myRelic = normalizeRelic(clone(relicPresets.presets[name]));
  saveJSON(RELIC_KEY, myRelic); relicDirty = false;
  if (currentSub === "relic") renderRelic();
  calcDamage();
}
function renderRelic() {
  if (!relicPresets) initRelicPresets();
  const grid = $("relic-grid"); if (!grid) return;
  const cat = artifactCatalog.list || [];
  if (!cat.length) { grid.innerHTML = '<p class="status">유물 데이터 준비 중이에요.</p>'; return; }
  // 보유현황 4열 그리드 — 각 유물의 각성도 입력
  grid.innerHTML = cat.map((a) => relicCellHtml(a)).join("");
  grid.querySelectorAll("select[data-id]").forEach((sel) => {
    sel.addEventListener("change", () => {
      const id = sel.dataset.id;
      if (sel.value === "") delete myRelic.awaken[id];   // 미보유
      else myRelic.awaken[id] = Math.max(0, Math.min(5, parseInt(sel.value, 10) || 0));   // 0~5각
      saveRelic(); renderRelic(); calcDamage();
    });
  });
  renderRelicEquip();
  updateRelicSlotInfo();
}
function relicCellHtml(a) {
  const has = relicHas(a.id), aw = relicAwaken(a.id);
  const opts = `<option value=""${!has ? " selected" : ""}>미보유</option>` +
    [0, 1, 2, 3, 4, 5].map((n) => `<option value="${n}"${has && n === aw ? " selected" : ""}>${n}각</option>`).join("");
  return `<div class="relic-cell${has ? " owned" : ""}">` +
    `<div class="relic-cell-top"><span class="relic-cell-name" title="${a.name}">${a.name}</span>${relicBadge(a.id)}</div>` +
    `<select class="relic-cell-aw" data-id="${a.id}">${opts}</select>` +
    `</div>`;
}
// 장착 패널(PC 왼쪽 / 모바일 위): 슬롯 수만큼 보유 유물 중 선택.
function renderRelicEquip() {
  const box = $("relic-slots"); if (!box) return;
  const slots = relicSlots(), owned = relicOwned();
  const eq = (myRelic.equipped || []).filter((id) => relicHas(id));   // 보유면 장착(0각 포함 — 0각도 기본효과값 있음)
  let html = "";
  for (let i = 0; i < slots; i++) {
    const cur = eq[i] || "";
    const opts = '<option value="">— 비움 —</option>' + owned.map((a) => {
      if (eq.some((x, j) => x === a.id && j !== i)) return "";   // 다른 슬롯이 이미 사용
      return `<option value="${a.id}"${a.id === cur ? " selected" : ""}>${a.name} (${relicAwaken(a.id)}각)</option>`;
    }).join("");
    html += `<label class="relic-slot"><span class="relic-slot-n">${i + 1}</span><select data-slot="${i}">${opts}</select></label>`;
  }
  if (!owned.length) html = '<p class="status muted">오른쪽(모바일은 아래) 그리드에서 보유 유물의 각성을 먼저 입력하세요.</p>';
  box.innerHTML = html;
  box.querySelectorAll("select[data-slot]").forEach((sel) => {
    sel.addEventListener("change", () => {
      const i = parseInt(sel.dataset.slot, 10);
      const arr = (myRelic.equipped || []).filter((id) => relicHas(id)).slice(0, slots);
      while (arr.length < slots) arr.push("");
      arr[i] = sel.value;
      myRelic.equipped = arr.filter(Boolean);
      saveRelic(); renderRelic(); calcDamage();
    });
  });
}
function updateRelicSlotInfo() {
  const sum = relicAwakenSum(), slots = relicSlots(), eq = activeRelic().length;
  if ($("rl-slots-badge")) $("rl-slots-badge").textContent = `(${slots}칸)`;
  if ($("rl-suminfo")) $("rl-suminfo").innerHTML = `각성 총합 <b>${sum}</b> → 슬롯 <b>${slots}</b>칸 · 장착 ${eq}/${slots}`;
}
$("rp-sel").addEventListener("change", () => {
  if (relicDirty && !confirm("저장하지 않은 유물 변경이 있습니다. 버리고 전환할까요?")) { $("rp-sel").value = relicPresets.active; return; }
  relicPresets.active = $("rp-sel").value; saveJSON(RELICP_KEY, relicPresets); rpApply(relicPresets.active);
});
$("rp-save").addEventListener("click", () => {
  relicPresets.presets[relicPresets.active] = clone(myRelic);
  saveJSON(RELICP_KEY, relicPresets); relicDirty = false;
  toast(`'${relicPresets.active}' 유물 프리셋 저장됨`);
});
$("rp-new").addEventListener("click", () => {
  const name = (prompt("새 유물 프리셋 이름") || "").trim();
  if (!name || relicPresets.presets[name]) return;
  relicPresets.presets[name] = clone(myRelic);
  relicPresets.active = name; saveJSON(RELICP_KEY, relicPresets); fillPresetSel($("rp-sel"), relicPresets);
  relicDirty = false;
});
$("rp-del").addEventListener("click", () => {
  const names = Object.keys(relicPresets.presets);
  if (names.length <= 1) return;
  delete relicPresets.presets[relicPresets.active];
  relicPresets.active = Object.keys(relicPresets.presets)[0];
  saveJSON(RELICP_KEY, relicPresets); fillPresetSel($("rp-sel"), relicPresets); rpApply(relicPresets.active);
});
$("rp-rename").addEventListener("click", () => {
  if (renamePreset(relicPresets) == null) return;
  saveJSON(RELICP_KEY, relicPresets); fillPresetSel($("rp-sel"), relicPresets);
  toast("유물 프리셋 이름을 변경했습니다.");
});

// ──────────────────── 프리셋 빠른 전환(플로팅) ────────────────────
// 어디서든 우하단 ⚡ → 작은 패널에서 프리셋을 바로 전환. 메인 셀렉트와 동일 로직 + 동기화.
// 프리셋 종류를 추가할 땐 이 목록에만 한 줄 추가하면 플로팅 패널에 자동 반영됨(순서도 이 배열 순서).
const QP_TYPES = [
  { key: "sp", label: "캐릭터", main: "sp-sel", store: () => statPresets, saveKey: STATP_KEY,
    canLeave: () => canLeaveStat(), apply: (n) => spApply(n) },
  { key: "ap", label: "동료", main: "ap-sel", store: () => allyPresets, saveKey: ALLYP_KEY,
    canLeave: () => !allyDirty || confirm("저장하지 않은 동료 변경이 있습니다. 버리고 전환할까요?"), apply: (n) => apApply(n) },
  { key: "cp", label: "큐브", main: "cp-sel", store: () => cubePresets, saveKey: CUBEP_KEY,
    canLeave: () => canLeaveCube(), apply: (n) => cpApply(n) },
  { key: "rp", label: "유물", main: "rp-sel", store: () => relicPresets, saveKey: RELICP_KEY,
    canLeave: () => !relicDirty || confirm("저장하지 않은 유물 변경이 있습니다. 버리고 전환할까요?"), apply: (n) => rpApply(n) },
];
function buildQuickPanel() {
  const rows = $("qp-rows"); if (!rows) return;
  // 최상단: 대상 한 줄(챕터=좁게 고정, 단계=넓게). 스탯 프리셋의 target에 귀속
  // (qs-chapter/qs-stage는 TARGET_PAIRS로 자동 채움·동기화).
  const chapterRow = `<label class="qp-row qp-target-row"><span>챕터</span>` +
    `<select id="qs-chapter" title="챕터"></select><select id="qs-stage" title="단계(일반/보스)"></select></label>`;
  rows.innerHTML = chapterRow + QP_TYPES.map((t) => `<label class="qp-row"><span>${t.label}</span><select id="qs-${t.key}"></select></label>`).join("");
  for (const t of QP_TYPES) {
    $("qs-" + t.key).addEventListener("change", async () => {
      const store = t.store(); if (!store) return;
      const sel = $("qs-" + t.key);
      if (!t.canLeave()) { sel.value = store.active; return; }
      store.active = sel.value; saveJSON(t.saveKey, store);
      await t.apply(store.active);
      const main = $(t.main); if (main) main.value = store.active;
      toast(`${t.label} → '${store.active}'`);
    });
  }
}
function syncQuickPanel() {
  for (const t of QP_TYPES) { const store = t.store(); if (store) fillPresetSel($("qs-" + t.key), store); }
  syncTargetSelector();   // 대상(챕터/스테이지)을 현재 활성 스탯 프리셋 값으로 동기화
}
function toggleQuickPanel(open) {
  $("qp-panel").hidden = !open;
  $("qp-fab").setAttribute("aria-expanded", open ? "true" : "false");
  if (open) syncQuickPanel();   // 열 때마다 최신 프리셋 목록·활성값 반영
}
buildQuickPanel();
$("qp-fab").addEventListener("click", (e) => { e.stopPropagation(); toggleQuickPanel($("qp-panel").hidden); });
$("qp-panel").addEventListener("click", (e) => e.stopPropagation());   // 패널 내부 클릭은 닫지 않음
document.addEventListener("click", () => { if (!$("qp-panel").hidden) toggleQuickPanel(false); });   // 바깥 클릭 시 닫기

// ──────────────────── 프리셋 내보내기 / 불러오기 (.mkbak) ────────────────────

const BACKUP_FORMAT = "mekicalc-presets";

function bundleString() {
  return JSON.stringify({ format: BACKUP_FORMAT, version: 1, statPresets, cubePresets, buffPresets, allyPresets, relicPresets, conversions: myConv, ranges: myRanges }, null, 2);
}

// 데스크톱(구버전) .mkbak → 웹 번들로 변환.
// 차이: statPresets/cubePresets가 배열, cubePresets는 {name,cubes,ranges} 래퍼, conversions는 {enabled,perUnit}.
function fromDesktopBackup(b) {
  const sp = { active: b.statPresets.active, presets: {} };
  for (const p of (b.statPresets.presets || [])) sp.presets[p.name] = { stats: p.stats || {} };

  const cp = { active: b.cubePresets.active, presets: {} };
  let ranges = {};
  for (const p of (b.cubePresets.presets || [])) {
    cp.presets[p.name] = p.cubes || {};
    if (p.name === b.cubePresets.active) ranges = p.ranges || {};   // 웹은 전역 ranges 1개 → 활성 프리셋 것 사용
  }

  const conv = {};
  for (const [k, v] of Object.entries(b.conversions || {})) {
    if (k.startsWith("_")) continue;
    if (v && v.enabled) conv[k] = v.perUnit || 0;   // 웹은 enabled 항목만 {id: perUnit}
  }
  return { statPresets: sp, cubePresets: cp, conversions: conv, ranges };
}

// 이름 충돌 시 "이름 (2)" 식으로 고유 이름.
function uniqueName(name, existing) {
  if (!existing[name]) return name;
  let i = 2;
  while (existing[`${name} (${i})`]) i++;
  return `${name} (${i})`;
}

// 이름 있는 프리셋 컬렉션({active, presets:{name:val}})을 into에 병합(추가). from 없으면 그대로.
// 향후 다른 프리셋 타입(유물 등)이 생겨도 이 함수 한 줄로 병합 가능 + 구버전 백업 호환(없으면 건너뜀).
function mergePresetCollection(into, from) {
  if (!from || !from.presets) return into;
  if (!into) into = { active: null, presets: {} };
  let last = into.active;
  for (const [name, val] of Object.entries(from.presets)) {
    const key = uniqueName(name, into.presets);
    into.presets[key] = val;
    last = key;
  }
  into.active = last;   // 마지막 불러온 걸 활성으로
  return into;
}

// 백업 문자열 → 정규화 번들 객체(format/구버전/데스크톱 호환). 적용 전 미리보기·선택용으로도 사용.
function parseBundle(text) {
  const raw = JSON.parse(text);
  if (raw.format === BACKUP_FORMAT) return raw;
  if (raw.statPresets && raw.cubePresets) return fromDesktopBackup(raw);   // 데스크톱/구버전 백업 호환
  throw new Error("MekiCalc 프리셋 데이터가 아닙니다.");
}

// 백업 문자열을 받아 적용. merge=true면 기존 프리셋 위에 '추가'(덮어쓰지 않음).
async function applyBundle(text, merge) { await applyBundleObj(parseBundle(text), merge); }

// 정규화된 번들 객체를 적용. 번들에 있는 섹션만 적용하고 없는/모르는 섹션은 무시 → 구버전 백업·향후 새 프리셋 타입 모두 호환.
async function applyBundleObj(b, merge) {
  if (merge) {
    // 추가(병합): 이름 있는 프리셋 컬렉션만 합치고, 전역 설정(환산·범위)은 현재 것 유지.
    statPresets = mergePresetCollection(statPresets, b.statPresets); saveJSON(STATP_KEY, statPresets);
    cubePresets = mergePresetCollection(cubePresets, b.cubePresets); saveJSON(CUBEP_KEY, cubePresets);
    if (b.buffPresets) { buffPresets = mergePresetCollection(buffPresets, b.buffPresets); saveJSON(BUFFP_KEY, buffPresets); }
    if (b.allyPresets) { allyPresets = mergePresetCollection(allyPresets, b.allyPresets); saveJSON(ALLYP_KEY, allyPresets); }
    if (b.relicPresets) { relicPresets = mergePresetCollection(relicPresets, b.relicPresets); saveJSON(RELICP_KEY, relicPresets); }
  } else {
    // 덮어쓰기(서버 동기화 등). 섹션이 있을 때만 교체 → 없는 섹션은 현재 유지.
    if (b.statPresets) { statPresets = b.statPresets; saveJSON(STATP_KEY, statPresets); }
    if (b.cubePresets) { cubePresets = b.cubePresets; saveJSON(CUBEP_KEY, cubePresets); }
    if (b.buffPresets) { buffPresets = b.buffPresets; saveJSON(BUFFP_KEY, buffPresets); }
    if (b.allyPresets) { allyPresets = b.allyPresets; saveJSON(ALLYP_KEY, allyPresets); }
    if (b.relicPresets) { relicPresets = b.relicPresets; saveJSON(RELICP_KEY, relicPresets); }
    if (b.conversions) { myConv = b.conversions; saveJSON(CONV_KEY, myConv); }
    if (b.ranges) { myRanges = b.ranges; saveJSON(RANGE_KEY, myRanges); }
  }
  migrateBuildBuffs();   // 구 형식(스탯+스킬 분리) 번들이면 스킬을 빌드로 병합
  fillPresetSel($("sp-sel"), statPresets); spApply(statPresets.active);   // 스킬(buffs)도 함께 적용됨
  fillPresetSel($("cp-sel"), cubePresets); await cpApply(cubePresets.active);
  if (allyPresets) { fillPresetSel($("ap-sel"), allyPresets); allyDirty = false; if (currentSub === "ally") renderAlly(); }
  if (relicPresets) { fillPresetSel($("rp-sel"), relicPresets); rpApply(relicPresets.active); }
  renderConvSettings(); renderRangeSettings(); refreshQual(); calcDamage();
}

// 파일로 내보내기
$("export-btn").addEventListener("click", () => {
  const blob = new Blob([bundleString()], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = "mekicalc-presets.mkbak";
  document.body.appendChild(a); a.click(); a.remove();
  URL.revokeObjectURL(url);
  toast("파일로 내보냈습니다.");
});
// ── 선택 불러오기: 백업을 파싱해 프리셋 목록을 체크박스로 보여주고, 고른 것만 병합 ──
const IMPORT_SECTIONS = [
  { key: "statPresets", label: "캐릭터" },
  { key: "cubePresets", label: "큐브" },
  { key: "buffPresets", label: "스킬(구)" },
  { key: "allyPresets", label: "동료" },
  { key: "relicPresets", label: "유물" },
];
let pendingBundle = null;   // 선택 대기 중인 파싱된 번들

// 컬렉션({active,presets})에서 선택된 이름만 남긴다(활성=선택 중 마지막).
function filterCollection(coll, nameSet) {
  const presets = {};
  let active = coll.active;
  for (const [n, v] of Object.entries(coll.presets || {})) if (nameSet.has(n)) { presets[n] = v; active = n; }
  return { active, presets };
}

// ── 모달(팝업) 공용 ──
function openModal(id) { const m = $(id); if (m) m.hidden = false; }
function closeModal(id) { const m = $(id); if (m) m.hidden = true; }
function closeAllModals() {
  document.querySelectorAll(".modal-overlay").forEach((m) => { m.hidden = true; });
  pendingBundle = null;   // 선택 상태도 초기화 → 탭 이동 뒤 잔존 선택으로 인한 오작동 방지
}
// ✕·취소(data-close) · 배경 클릭 · Esc 로 닫기
document.querySelectorAll(".modal-overlay").forEach((ov) => {
  ov.addEventListener("click", (e) => { if (e.target === ov || (e.target.closest && e.target.closest("[data-close]"))) ov.hidden = true; });
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") document.querySelectorAll(".modal-overlay:not([hidden])").forEach((m) => { m.hidden = true; });
});
async function copyToClipboard(s) { try { await navigator.clipboard.writeText(s); return true; } catch { return false; } }

// 백업 텍스트를 파싱해 '불러올 프리셋 선택' 목록을 채운다. 프리셋이 있으면 true(호출자가 선택 팝업을 연다).
// 실패·빈 백업이면 statusEl(있으면 그 칸, 없으면 토스트)에 사유 표시 후 false.
function openImportSelection(text, statusEl) {
  const setErr = (msg) => { if (statusEl) statusEl.textContent = msg; else toast(msg); };
  let b;
  try { b = parseBundle(text); }
  catch (err) { setErr("불러오기 실패: " + err.message); return false; }
  const list = $("import-select-list");
  list.innerHTML = "";
  let any = false;
  for (const sec of IMPORT_SECTIONS) {
    const coll = b[sec.key];
    const names = coll && coll.presets ? Object.keys(coll.presets) : [];
    if (!names.length) continue;
    any = true;
    const group = document.createElement("div");
    group.className = "imp-group";
    const title = document.createElement("div");
    title.className = "imp-group-title";
    title.textContent = `${sec.label} (${names.length})`;
    group.appendChild(title);
    for (const n of names) {
      const row = document.createElement("label");
      row.className = "imp-row";
      const cb = document.createElement("input");
      cb.type = "checkbox"; cb.className = "imp-cb"; cb.checked = true;
      cb.dataset.sec = sec.key; cb.dataset.name = n;
      const span = document.createElement("span");
      span.textContent = n;
      row.appendChild(cb); row.appendChild(span);
      group.appendChild(row);
    }
    list.appendChild(group);
  }
  if (!any) { setErr("백업에 불러올 프리셋이 없습니다."); pendingBundle = null; return false; }
  pendingBundle = b;
  $("imp-all").checked = true;
  const mergeRadio = document.querySelector('input[name="imp-mode"][value="merge"]');
  if (mergeRadio) mergeRadio.checked = true;   // 열 때마다 안전한 기본값(추가)으로 초기화
  $("import-select-status").textContent = "";
  return true;
}

// 전체 선택 토글
$("imp-all").addEventListener("change", () => {
  const on = $("imp-all").checked;
  document.querySelectorAll(".imp-cb").forEach((cb) => { cb.checked = on; });
});
// 개별 체크가 바뀌면 '전체' 상태 반영
$("import-select-list").addEventListener("change", () => {
  const all = [...document.querySelectorAll(".imp-cb")];
  $("imp-all").checked = all.length > 0 && all.every((cb) => cb.checked);
});
// 선택한 프리셋 불러오기(추가/교체) → 성공 시 팝업 자동 닫힘
$("import-apply-btn").addEventListener("click", async () => {
  const status = $("import-select-status");
  if (!pendingBundle) { status.textContent = "불러올 백업을 다시 선택하세요."; return; }
  const checked = [...document.querySelectorAll(".imp-cb:checked")];
  if (!checked.length) { status.textContent = "불러올 프리셋을 하나 이상 선택하세요."; return; }
  const sel = {};
  for (const cb of checked) { (sel[cb.dataset.sec] = sel[cb.dataset.sec] || new Set()).add(cb.dataset.name); }
  const filtered = { format: BACKUP_FORMAT, version: 1 };
  for (const sec of IMPORT_SECTIONS) if (sel[sec.key] && pendingBundle[sec.key]) filtered[sec.key] = filterCollection(pendingBundle[sec.key], sel[sec.key]);
  // 방식: 추가(병합, 기본) vs 교체(불러오는 종류의 기존 프리셋을 지우고 선택분으로 대체 — 안 고른 종류는 유지).
  const replace = (document.querySelector('input[name="imp-mode"]:checked') || {}).value === "replace";
  if (replace) {
    const kinds = IMPORT_SECTIONS.filter((s) => filtered[s.key]).map((s) => s.label).join("·");
    if (!confirm(`${kinds} 프리셋을 기존 것 삭제 후 선택한 것으로 교체합니다. 계속할까요?`)) return;
  }
  try {
    await applyBundleObj(filtered, !replace);   // merge=!replace
    toast(`${checked.length}개 프리셋을 ${replace ? "교체" : "추가"}했습니다.`);
    closeModal("modal-select"); pendingBundle = null;
  } catch (err) { status.textContent = "불러오기 실패: " + err.message; }
});

// 파일에서 불러오기 → 선택 팝업 열기
$("import-btn").addEventListener("click", () => $("import-file").click());
$("import-file").addEventListener("change", (e) => {
  const file = e.target.files[0];
  if (file) {
    const reader = new FileReader();
    reader.onload = () => { if (openImportSelection(reader.result)) openModal("modal-select"); };
    reader.readAsText(file);
  }
  e.target.value = "";
});
// 텍스트로 내보내기 → 팝업 열고 자동 복사(우상단 복사 버튼으로 재복사 가능)
$("export-text-btn").addEventListener("click", async () => {
  const s = bundleString();
  $("export-text-area").value = s;
  openModal("modal-export");
  if (await copyToClipboard(s)) toast("텍스트를 복사했습니다.");
});
$("modal-export-copy").addEventListener("click", async () => {
  const ta = $("export-text-area"); ta.select();
  toast(await copyToClipboard(ta.value) ? "복사했습니다." : "복사 실패 — 텍스트를 직접 선택해 복사하세요.");
});
// 텍스트로 불러오기 → 빈 붙여넣기 팝업
$("import-text-btn").addEventListener("click", () => {
  $("import-text-area").value = "";
  $("import-modal-status").textContent = "";
  openModal("modal-import");
  $("import-text-area").focus();
});
// 붙여넣기 팝업의 '불러오기' → 파싱 성공 시 선택 팝업으로 전환(붙여넣기 팝업은 내려감)
$("import-text-load").addEventListener("click", () => {
  const s = $("import-text-area").value.trim();
  if (!s) { $("import-modal-status").textContent = "백업 텍스트를 붙여넣으세요."; return; }
  if (openImportSelection(s, $("import-modal-status"))) { closeModal("modal-import"); openModal("modal-select"); }
});

// ──────────────────── 계정(로그인) ────────────────────

async function loadMe(pre) {
  try { me = pre || await (await fetch("/api/me")).json(); } catch { me = { oauthEnabled: false, loggedIn: false }; }
  saveJSON(CACHE_ME, me);
  renderLoginUI();
}

async function doLogout() {
  try { await fetch("/auth/logout", { method: "POST" }); } catch {}
  // 계정 격리: 다음 로그인 계정에 이전 계정 데이터가 안 보이도록 로컬 유저데이터·캐시 제거(정적 캐시는 유지).
  [STATP_KEY, CUBEP_KEY, BUFFP_KEY, ALLYP_KEY, CONV_KEY, RANGE_KEY, CUBE_KEY, CACHE_DMG, CACHE_ME, CACHE_SKILLS].forEach((k) => localStorage.removeItem(k));
  location.reload();
}

function renderLoginUI() {
  renderHeaderAccount();
  const card = $("account-card"), body = $("account-body");
  if (!card || !body) return;
  if (!me.oauthEnabled) { card.hidden = true; return; }
  card.hidden = false;
  if (me.loggedIn) {
    body.innerHTML =
      `<p class="status set-desc"><b>${me.name || ""}</b> <span class="muted">${me.email || ""}</span> 로 로그인됨. ` +
      `프리셋이 이 계정에 자동 저장·동기화됩니다.</p>` +
      `<button id="logout-btn" class="mini-btn ghost" type="button">로그아웃</button>`;
    $("logout-btn").addEventListener("click", doLogout);
  } else {
    body.innerHTML =
      `<p class="status set-desc">로그인하면 프리셋(스탯·큐브·설정)을 계정에 저장해 여러 기기에서 동기화할 수 있어요.</p>` +
      `<a class="primary-btn google-btn" href="/auth/login">Google로 로그인</a>`;
  }
}

// 계정 영역: 데스크톱은 헤더 우상단(#header-account), 모바일은 햄버거 드로어 안(#m-account)에 동일 내용.
function renderHeaderAccount() {
  renderAccountInto($("header-account"));   // 데스크톱 우상단
  renderAccountInto($("m-account"));        // 모바일 드로어
}
function renderAccountInto(el) {
  if (!el) return;
  if (!me.oauthEnabled) { el.innerHTML = ""; return; }
  if (me.loggedIn) {
    el.innerHTML =
      `<span class="acct-chip" title="${me.email || ""}"><span class="acct-dot"></span>${me.name || "로그인됨"}</span>` +
      `<button class="mini-btn ghost acct-logout" type="button">로그아웃</button>`;
    el.querySelector(".acct-logout").addEventListener("click", doLogout);   // class 사용(중복 id 방지)
  } else {
    el.innerHTML = `<a class="login-btn" href="/auth/login"><span class="g-mark">G</span> 로그인</a>`;
  }
}

// ──────────────────── 초기화 ────────────────────

// meta 적용(부위·등급 드롭다운). 셀렉트를 비우고 다시 채워 두 번 호출돼도 중복 안 됨(즉시 페인트+갱신 공용).
function applyMeta(m) {
  meta = m;
  meta.parts = sortParts(meta.parts || []);   // 부위를 고정 순서로 통일
  for (const sel of [$("ev-part"), $("mc-part-add"), $("cmp-part")]) {
    sel.innerHTML = "";
    for (const p of meta.parts) sel.add(new Option(p, p));
  }
  fillGradeSelect($("ev-grade"));
  $("ev-grade").value = meta.grades[meta.grades.length - 1];
  applyGradeColor($("ev-grade"));
  saveJSON(CACHE_META, meta);
}

async function loadMeta(pre) {
  applyMeta(pre || await (await fetch("/api/meta")).json());
  await renderCube();
}

// ──────────────────── 공지사항(넥슨 포럼 크롤링·요약) ────────────────────
// 서버가 넥슨 포럼을 1분 폴링·요약해 저장한 걸 읽어 탭(전체 + 말머리별)으로 보여준다. 게시 시각 내림차순.
const noticeState = { cat: null, page: 0, pages: 1, q: "", loading: false, inited: false };
let ntQTimer = null;

async function renderNotices() {
  if (!noticeState.inited) {
    noticeState.inited = true;
    const q = $("nt-q"), clr = $("nt-q-clear");
    q.addEventListener("input", () => {
      clr.hidden = !q.value;
      clearTimeout(ntQTimer);
      ntQTimer = setTimeout(() => { noticeState.q = q.value.trim(); ntSyncUrl(); loadNotices(noticeState.cat, 0); }, 300);
    });
    clr.addEventListener("click", () => {
      q.value = ""; clr.hidden = true; noticeState.q = "";
      clearTimeout(ntQTimer); ntSyncUrl(); loadNotices(noticeState.cat, 0); q.focus();
    });
    // 카톡 검색 링크 등 ?q=로 진입 시: 검색어 미리 채우고 검색 실행(로드 시 loadNotices가 noticeState.q 사용)
    const urlQ = new URLSearchParams(location.search).get("q");
    if (urlQ && urlQ.trim()) { noticeState.q = urlQ.trim(); q.value = urlQ.trim(); clr.hidden = false; }
  }
  await buildNoticeTabs();                    // 재진입 시 개수 최신화(백필로 늘어남)
  ntCouponBanner();                           // 쿠폰 고정 배너(병렬)
  await loadNotices(noticeState.cat, 0);      // 현재 탭 최신 다시 로드
}

// 검색어를 주소창 ?q=에 반영(공유·복사 가능). replaceState라 히스토리는 안 쌓임.
function ntSyncUrl() {
  const u = new URL(location.href);
  if (noticeState.q) u.searchParams.set("q", noticeState.q); else u.searchParams.delete("q");
  history.replaceState(history.state, "", u.pathname + u.search + u.hash);
}

async function buildNoticeTabs() {
  const tabs = $("nt-tabs");
  let data = { categories: [], total: 0 };
  try { data = await (await fetch("/api/notice-categories")).json(); } catch (e) { /* 무시 */ }
  const defs = [{ key: null, label: "전체", count: data.total }].concat(
    (data.categories || []).map((c) => ({ key: c.key, label: c.key, count: c.count })));
  tabs.innerHTML = "";
  for (const d of defs) {
    const b = document.createElement("button");
    b.className = "nt-tab" + (d.key === noticeState.cat ? " active" : "");
    b.type = "button";
    b.innerHTML = escNt(d.label) + (d.count ? ' <span class="nt-tab-n">' + d.count + "</span>" : "");
    b.addEventListener("click", () => {
      if (noticeState.loading) return;
      document.querySelectorAll("#nt-tabs .nt-tab").forEach((x) => x.classList.remove("active"));
      b.classList.add("active");
      loadNotices(d.key, 0);
    });
    tabs.appendChild(b);
  }
}

// 목록만 비동기 교체(주변 UI·스크롤 건드리지 않음). 기존 목록은 새 데이터 도착 시 한 번에 스왑.
async function loadNotices(cat, page) {
  const list = $("nt-list"), status = $("nt-status");
  noticeState.loading = true;
  noticeState.cat = cat;
  list.classList.add("nt-loading");   // 살짝 흐리게(기존 내용 유지, 깜빡임 없음)
  status.textContent = "";
  try {
    const q = new URLSearchParams();
    if (cat) q.set("category", cat);
    q.set("page", page);
    if (noticeState.q) q.set("q", noticeState.q);
    const data = await (await fetch("/api/notices?" + q.toString())).json();
    const frag = document.createDocumentFragment();
    if (!data.items || data.items.length === 0) {
      const p = document.createElement("p");
      p.className = "nt-empty";
      p.textContent = noticeState.q
        ? "‘" + noticeState.q + "’에 대한 공지를 찾지 못했습니다."
        : "아직 수집된 공지가 없습니다. 잠시 후 다시 확인해 주세요.";
      frag.appendChild(p);
    } else {
      const closed = [];
      for (const it of data.items) {
        const card = noticeCard(it);
        if (it.status === "closed") closed.push(card); else frag.appendChild(card);
      }
      // 게시 종료(확인 중인 현상)는 아카이브 토글 뒤로 (스펙 §6-5)
      if (closed.length) {
        const tog = document.createElement("button");
        tog.type = "button"; tog.className = "nt-archtog";
        const arch = document.createElement("div"); arch.className = "nt-arch"; arch.hidden = true;
        closed.forEach((c) => arch.appendChild(c));
        const label = (hidden) => (hidden ? "종료된 현상 보기" : "종료된 현상 숨기기") + " (" + closed.length + ")";
        tog.textContent = label(true);
        tog.addEventListener("click", () => { arch.hidden = !arch.hidden; tog.textContent = label(arch.hidden); });
        frag.appendChild(tog); frag.appendChild(arch);
      }
    }
    list.replaceChildren(frag);   // 한 번에 교체
    noticeState.page = data.page || 0;
    noticeState.pages = data.pages || 1;
    renderPager(noticeState.page, noticeState.pages);
    ntOpenFromUrl();   // 개별 URL/해시로 들어왔으면 해당 카드 펼침+스크롤
  } catch (e) {
    status.textContent = "공지를 불러오지 못했습니다.";
  } finally {
    noticeState.loading = false;
    list.classList.remove("nt-loading");
  }
}

// 페이지네이션: 5단위 블록(1~5, 6~10…) + «(첫)/‹(이전)/번호/›(다음)/»(끝). 목록만 교체.
const NT_PAGE_BLOCK = 5;
function renderPager(page, pages) {
  const pager = $("nt-pager");
  pager.innerHTML = "";
  if (pages <= 1) { pager.hidden = true; return; }
  pager.hidden = false;
  const go = (p) => {
    if (p < 0 || p >= pages || p === noticeState.page || noticeState.loading) return;
    loadNotices(noticeState.cat, p);
    document.querySelector(".notices-card").scrollIntoView({ behavior: "smooth", block: "start" });
  };
  const btn = (label, p, opts) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "nt-page" + (opts && opts.cur ? " cur" : "") + (opts && opts.nav ? " nav" : "");
    b.textContent = label;
    if (opts && opts.disabled) b.disabled = true;
    else b.addEventListener("click", () => go(p));
    pager.appendChild(b);
  };
  const blockStart = Math.floor(page / NT_PAGE_BLOCK) * NT_PAGE_BLOCK;
  const blockEnd = Math.min(blockStart + NT_PAGE_BLOCK - 1, pages - 1);
  btn("«", 0, { nav: true, disabled: page <= 0 });               // 첫 페이지
  btn("‹", page - 1, { nav: true, disabled: page <= 0 });        // 이전 페이지(블록 경계 넘으면 번호창 자동 이동)
  for (let p = blockStart; p <= blockEnd; p++) btn(String(p + 1), p, { cur: p === page });
  btn("›", page + 1, { nav: true, disabled: page >= pages - 1 }); // 다음 페이지
  btn("»", pages - 1, { nav: true, disabled: page >= pages - 1 }); // 마지막 페이지
}

// ── 공지 아코디언 카드: 배지 + 제목 + one_liner(접힘) / 구조화 전체(펼침). data 없으면 레거시 summary 폴백.
const NT_BADGE = {
  preview: ["미리보기", "b-preview"], patchnote: ["패치노트", "b-patch"], event: ["이벤트", "b-event"],
  issue: ["확인 중인 현상", "b-issue"], devnote: ["개발자 노트", "b-devnote"], coupon: ["쿠폰", "b-coupon"], etc: ["공지", "b-etc"],
};
// 태그 = 넥슨 원문 말머리(headline). 말머리별 시그니처 색상 클래스. 없으면 기존 타입 배지로 폴백.
const NT_HEAD = { "안내": "h-notice", "점검": "h-maint", "업데이트": "h-update", "진행중": "h-event", "당첨안내": "h-winner", "확인중": "h-issue" };
function ntHeadBadge(it) {
  const h = (it.headline || "").trim();
  if (h) return [h, NT_HEAD[h] || "h-etc"];
  return NT_BADGE[ntType(it)] || NT_BADGE.etc;
}
const NT_SEC_EMOJI = { new: "🆕", improve: "⚙️", bugfix: "🐛", event: "🎉", promise: "🤝", info: "📋" };

function ntType(it) {
  if (it.data && it.data.notice_type) return it.data.notice_type;
  const c = it.category || "";
  if (c.indexOf("패치") >= 0) return "patchnote";
  if (c.indexOf("이벤트") >= 0) return "event";
  if (c.indexOf("확인") >= 0 || c.indexOf("현상") >= 0) return "issue";
  return "preview";
}

function noticeCard(it) {
  const type = ntType(it), d = it.data, closed = it.status === "closed";
  const el = document.createElement("article");
  el.className = "nt-card" + (closed ? " nt-closed" : "");
  el.id = "notice-" + it.id;
  const badge = closed ? ["게시 종료", "b-closed"] : ntHeadBadge(it);
  const dd = ntDday(it, type, closed);
  const dateTxt = ntDate(it.postedAt);   // 넥슨 게시 일시(고정) — date_label(예정/적용/종료)은 펼침 본문에서 확인
  el.innerHTML =
    '<button class="nt-head2" type="button" aria-expanded="false">'
    + '<span class="nt-badge ' + badge[1] + '">' + escNt(badge[0]) + "</span>"
    + '<span class="nt-t">' + ntHl(it.title || "") + "</span>"
    + (dd ? '<span class="nt-badge ' + dd[1] + '">' + escNt(dd[0]) + "</span>" : "")
    + (it.sticky ? '<span class="nt-pin" title="상단 고정">📌</span>' : "")
    + '<span class="nt-dt">' + escNt(dateTxt) + "</span>"
    + '<span class="nt-chev">▾</span>'
    + "</button>"
    + '<div class="nt-line">' + ntCollapsedLine(it, type) + "</div>"
    + '<div class="nt-body2" hidden>' + ntExpandedBody(it, type) + "</div>";
  const btn = el.querySelector(".nt-head2");
  btn.addEventListener("click", () => ntToggle(el));
  el.querySelectorAll(".nt-copy").forEach((c) => c.addEventListener("click", (e) => { e.stopPropagation(); copyCoupon(c); }));
  return el;
}

function ntToggle(el, force) {
  const body = el.querySelector(".nt-body2"), btn = el.querySelector(".nt-head2");
  const open = force != null ? force : body.hidden;
  body.hidden = !open;
  btn.setAttribute("aria-expanded", open ? "true" : "false");
  el.classList.toggle("nt-open", open);
}

// 접힘 요약 줄(유형별). 검색 중 제목·기본줄에 매치가 없으면 매치된 요약 스니펫으로 대체(하이라이트 항상 노출).
function ntCollapsedLine(it, type) {
  const d = it.data;
  let plain = "", html = null;
  if (type === "issue" && d && d.issues) {
    const o = d.issues.open.length, f = d.issues.fixed.length;
    const rep = (d.issues.open[0] && d.issues.open[0].text) || (d.issues.fixed[0] && d.issues.fixed[0].text) || "";
    plain = rep;
    html = '<em>확인 중 ' + o + "건</em> · <em>수정 완료 " + f + "건</em>" + (rep ? " — " + ntHl(rep) : "");
  } else if (d && d.one_liner) {
    plain = d.one_liner;
    html = (type === "devnote" ? '<span class="nt-quote">' + ntHl(d.one_liner) + "</span>" : ntHl(d.one_liner));
  } else if (it.summary) {
    plain = String(it.summary).split("\n")[0].replace(/^[-•*]\s*/, "");
    html = ntHl(plain);
  } else {
    return '<span class="nt-pending">요약을 준비하고 있어요…</span>';
  }
  if (noticeState.q && !ntHasTok(it.title) && !ntHasTok(plain)) {
    const snip = ntMatchSnippet(it);
    if (snip) return ntHl(snip);
  }
  return html;
}

// 검색 토큰/매치 유틸 — 접힘줄 스니펫·하이라이트 판단용
function ntQTokens() { const q = noticeState.q; return q ? q.trim().toLowerCase().split(/\s+/).filter(Boolean) : []; }
function ntHasTok(s) { if (!s) return false; const l = String(s).toLowerCase(); return ntQTokens().some((x) => l.indexOf(x) >= 0); }
function ntDataTexts(d) {
  const out = [];
  if (!d) return out;
  if (d.one_liner) out.push(d.one_liner);
  (d.sections || []).forEach((s) => { if (s.heading) out.push(s.heading); (s.items || []).forEach((it) => { if (it.text) out.push(it.text); (it.sub || []).forEach((x) => out.push(x)); }); });
  if (d.event_meta) ["benefit", "how_to", "caution", "period"].forEach((k) => { if (d.event_meta[k]) out.push(d.event_meta[k]); });
  if (d.issues) { (d.issues.open || []).forEach((o) => { if (o.text) out.push(o.text); if (o.workaround) out.push(o.workaround); }); (d.issues.fixed || []).forEach((f) => { if (f.text) out.push(f.text); }); }
  (d.coupons || []).forEach((c) => { if (c.code) out.push(c.code); (c.rewards || []).forEach((r) => out.push(r)); });
  if (d.mail_rewards) { if (d.mail_rewards.target) out.push(d.mail_rewards.target); (d.mail_rewards.items || []).forEach((x) => out.push(x)); }
  return out;
}
function ntMatchSnippet(it) {
  const toks = ntQTokens(); if (!toks.length) return null;
  for (const s of ntDataTexts(it.data)) if (toks.some((x) => s.toLowerCase().indexOf(x) >= 0)) return s;
  if (it.summary) for (const ln of String(it.summary).split("\n")) { const t = ln.replace(/^[-•*]\s*/, ""); if (toks.some((x) => t.toLowerCase().indexOf(x) >= 0)) return t; }
  return null;
}

// 펼침 본문(생략 없이 전체)
function ntExpandedBody(it, type) {
  const d = it.data;
  let h = "";
  if (d) {
    if (type === "event" && d.event_meta) h += ntEventMeta(d.event_meta);
    else if (type === "issue" && d.issues) h += ntIssues(d.issues);
    else h += ntSections(d.sections);
    if (d.coupons && d.coupons.length) h += ntCouponsHtml(d.coupons);
    if (d.mail_rewards) h += ntMailHtml(d.mail_rewards);
    if (it.closeNote) h += '<p class="nt-closenote">' + escNt(it.closeNote) + "</p>";
  } else if (it.summary) {
    h += ntSummaryLegacy(it.summary);
  } else {
    h += '<p class="nt-pending">요약을 준비하고 있어요…</p>';
  }
  if (it.sourceUrl) h += '<div class="nt-foot"><a class="nt-src" href="' + escAttr(it.sourceUrl) + '" target="_blank" rel="noopener nofollow">원문 보기 ↗</a></div>';
  return h;
}

function ntSections(sections) {
  if (!sections || !sections.length) return "";
  let h = "";
  for (const s of sections) {
    if (!s.items || !s.items.length) continue;
    h += '<h4 class="nt-sec">' + (NT_SEC_EMOJI[s.key] || "📋") + " " + ntHl(s.heading || "") + "</h4><ul class=\"nt-items\">";
    for (const it of s.items) {
      h += "<li>" + ntHl(it.text || "");
      if (it.sub && it.sub.length) h += '<ul class="nt-sub">' + it.sub.map((x) => "<li>" + ntHl(x) + "</li>").join("") + "</ul>";
      h += "</li>";
    }
    h += "</ul>";
  }
  return h;
}

function ntEventMeta(e) {
  let r = "";
  if (e.benefit) r += ntRow("혜택", e.benefit);
  if (e.period) r += ntRow("기간", e.period);
  if (e.how_to) r += ntRow("방법", e.how_to);
  if (e.caution) r += ntRow("주의", e.caution);
  return '<dl class="nt-meta">' + r + "</dl>";
}
function ntRow(k, v) { return "<dt>" + escNt(k) + "</dt><dd>" + ntHl(v) + "</dd>"; }

function ntIssues(iss) {
  let h = "";
  if (iss.open && iss.open.length) {
    h += '<h4 class="nt-sec nt-o">🔴 확인 중 ' + iss.open.length + "건</h4><ul class=\"nt-items\">";
    for (const o of iss.open) {
      h += "<li>" + ntHl(o.text || "");
      if (o.workaround) h += '<div class="nt-work">ㄴ 임시 해결: ' + ntHl(o.workaround) + "</div>";
      h += "</li>";
    }
    h += "</ul>";
  }
  if (iss.fixed && iss.fixed.length) {
    h += '<h4 class="nt-sec nt-f">✅ 수정 완료 ' + iss.fixed.length + "건</h4><ul class=\"nt-items\">";
    for (const f of iss.fixed) h += "<li>" + ntHl(f.text || "") + (f.fixed_date ? " <small>(" + escNt(f.fixed_date) + ")</small>" : "") + "</li>";
    h += "</ul>";
  }
  return h;
}

function ntCouponsHtml(cs) {
  let h = '<div class="nt-cpblock"><h4 class="nt-sec">🎟️ 쿠폰</h4>';
  for (const c of cs) {
    h += '<div class="nt-cp"><button class="nt-copy" type="button" data-code="' + escAttr(c.code) + '" title="복사"><code>' + ntHl(c.code) + "</code> ⧉</button>"
      + (c.expires ? '<span class="nt-cp-exp">~' + escNt(c.expires) + "</span>" : "") + "</div>";
    if (c.rewards && c.rewards.length) h += '<ul class="nt-items nt-rw">' + c.rewards.map((r) => "<li>" + ntHl(r) + "</li>").join("") + "</ul>";
  }
  return h + "</div>";
}

function ntMailHtml(m) {
  let h = '<div class="nt-mail"><h4 class="nt-sec">📬 우편 보상</h4>';
  if (m.target) h += '<p class="nt-mail-t">대상: ' + ntHl(m.target) + (m.claim_deadline ? " · 수령 기한: " + escNt(m.claim_deadline) : "") + "</p>";
  if (m.items && m.items.length) h += '<ul class="nt-items">' + m.items.map((x) => "<li>" + ntHl(x) + "</li>").join("") + "</ul>";
  return h + "</div>";
}

function ntSummaryLegacy(s) {
  const lines = String(s).split("\n").map((l) => l.trim()).filter(Boolean);
  return '<ul class="nt-items">' + lines.map((l) => "<li>" + ntHl(l.replace(/^[-•*]\s*/, "")) + "</li>").join("") + "</ul>";
}

// event D-day 배지: [텍스트, class]. 이벤트·미종료·endAt 있을 때만.
function ntDday(it, type, closed) {
  if (type !== "event" || closed || !it.endAt) return null;
  const now = Date.now() / 1000;
  if (it.endAt < now) return ["종료", "b-closed"];
  const days = Math.max(0, Math.ceil((it.endAt - now) / 86400));
  return ["D-" + days, days <= 3 ? "b-dday-hot" : "b-dday"];
}

function copyCoupon(btn) {
  const code = btn.dataset.code || "";
  if (!navigator.clipboard) return;
  navigator.clipboard.writeText(code).then(() => {
    const prev = btn.innerHTML;
    btn.classList.add("copied");
    btn.innerHTML = "✓ 복사됨";
    setTimeout(() => { btn.innerHTML = prev; btn.classList.remove("copied"); }, 1200);
  }).catch(() => { /* 무시 */ });
}

function ntDate(sec) {
  if (!sec) return "";
  const d = new Date(sec * 1000);
  const p = (n) => String(n).padStart(2, "0");
  return d.getFullYear() + "." + p(d.getMonth() + 1) + "." + p(d.getDate()) + " " + p(d.getHours()) + ":" + p(d.getMinutes());
}

// 쿠폰 고정 배너(목록 최상단): 유효 쿠폰만. 코드칩+복사+만료+보상 전체. 각 코드에 출처 공지 링크.
async function ntCouponBanner() {
  const box = $("nt-coupon-banner");
  if (!box) return;
  let cs = [];
  try { cs = (await (await fetch("/api/coupons")).json()).coupons || []; } catch (e) { /* 무시 */ }
  if (!cs.length) { box.hidden = true; box.innerHTML = ""; return; }
  let h = '<h3>🎟️ 사용 가능한 쿠폰 <span class="nt-cpcount">' + cs.length + "</span></h3>";
  for (const c of cs) {
    h += '<div class="nt-cpchip"><button class="nt-copy" type="button" data-code="' + escAttr(c.code) + '" title="복사"><code>' + escNt(c.code) + "</code> ⧉</button>"
      + (c.end ? '<span class="nt-cp-exp">~' + escNt(ntMd(c.end)) + "</span>" : "")
      + (c.sourceUrl ? '<a class="nt-cp-src" href="' + escAttr(c.sourceUrl) + '" target="_blank" rel="noopener nofollow">출처 공지 ↗</a>' : "")
      + "</div>";
    if (c.rewards && c.rewards.length) h += '<ul class="nt-items nt-rw">' + c.rewards.map((r) => "<li>" + escNt(r) + "</li>").join("") + "</ul>";
  }
  box.innerHTML = h;
  box.hidden = false;
  box.querySelectorAll(".nt-copy").forEach((b) => b.addEventListener("click", () => copyCoupon(b)));
}

// "yyyy-MM-dd" → "M/D" (배너 만료 표기)
function ntMd(s) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(s || ""));
  return m ? Number(m[2]) + "/" + Number(m[3]) : s;
}

// 개별 URL(/notices/{id} 또는 #notice-{id}) → 해당 카드 펼침 + 스크롤. 로드 후 호출.
function ntOpenFromUrl() {
  let id = null;
  const m = /\/notices\/(\d+)/.exec(location.pathname);
  if (m) id = m[1];
  else if (location.hash.startsWith("#notice-")) id = location.hash.slice(8);
  if (!id) return;
  const el = document.getElementById("notice-" + id);
  if (el) { ntToggle(el, true); el.scrollIntoView({ behavior: "smooth", block: "start" }); }
}

function escNt(s) { const d = document.createElement("div"); d.textContent = (s == null ? "" : String(s)); return d.innerHTML; }
function escAttr(s) { return escNt(s).replace(/"/g, "&quot;"); }
function escapeRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }
// 검색어 하이라이트: 이스케이프 후 현재 검색 토큰을 <mark>로 감싼다. 검색 없으면 escNt와 동일.
function ntHl(s) {
  const esc = escNt(s);
  const q = noticeState.q;
  if (!q) return esc;
  const toks = q.trim().toLowerCase().split(/\s+/).filter(Boolean).map(escapeRe);
  if (!toks.length) return esc;
  return esc.replace(new RegExp("(" + toks.join("|") + ")", "gi"), '<mark class="nt-hl">$1</mark>');
}

(async function init() {
  // 초기화 중에는 자동 push 금지: 계정 정본을 pull하기 전에 로컬(스테일) 상태를 계정에 덮어쓰는 것 방지
  // (멀티 기기에서 나중에 연 기기가 옛 값으로 계정을 덮어써 예상 데미지가 흔들리던 문제). 사용자 편집은 init 후부터 동기화.
  suppressSync = true;

  // ── 딥링크 즉시 라우팅(홈 깜빡임 제거) ──
  // 기존엔 홈(예상 데미지)을 먼저 그린 뒤 init 끝에서야 이동 → /notices 링크로 들어오면 홈이 한참 보이다 점프했음.
  // 대상 패널을 첫 페인트부터 노출한다. notices는 자족적(독립 fetch)이라 즉시 렌더, 계산 패널은 컨테이너만 노출하고
  // 정식 렌더는 데이터 로드 후 하단 navigate가 맡는다(초기화 중 계산 렌더 호출 방지). '/notices/{id}'도 notices로.
  const bootPath = location.pathname;
  const bootLeaf = leafByPath(normPath(bootPath)) || (bootPath.startsWith("/notices") ? leafByPath("/notices") : null);
  const bootDeep = !!(bootLeaf && bootLeaf !== MENU[0]);
  if (bootDeep) {
    for (const p of document.querySelectorAll(".panel")) p.hidden = (p.id !== "panel-" + bootLeaf.panel);
    document.body.classList.toggle("notices-active", bootLeaf.panel === "notices");
    if (bootLeaf.panel === "notices") renderNotices();
  }
  // head 인라인 스크립트가 첫 페인트 전 홈을 CSS로 숨겨둠(deep-route). 이제 JS가 패널 가시성을 잡았으니 해제.
  document.documentElement.classList.remove("deep-route");

  try {
    // ── 즉시 페인트: 캐시(localStorage)로 화면을 서버 대기 없이 먼저 그림 → 재방문 시 "클릭하자마자" 느낌 ──
    // 정적 데이터(stats·meta)와 직전 데미지·계정 표시를 즉시 복원. 데이터는 곧 서버 응답으로 갱신됨.
    const cStats = loadJSON(CACHE_STATS, null), cMeta = loadJSON(CACHE_META, null);
    if (cStats && cMeta) {
      try {
        statDefs = cStats; renderForm();
        applyMeta(cMeta);
        const cMe = loadJSON(CACHE_ME, null); if (cMe) { me = cMe; renderLoginUI(); }
        initStatPresets();                  // localStorage 프리셋으로 폼 값 채움(booting=true → 재계산 안 함)
        renderConvSettings(); renderRangeSettings();
        // 홈 차트도 캐시로 즉시 그림(서버 대기 0). 방어배수까지 직전 값을 복원해 신선값과 서명이 일치 →
        // calcDamage 완료 시 renderHome이 재렌더를 건너뜀(진입 애니메이션 1회 유지). 값이 실제로 바뀐 경우만 1회 더.
        const cDmg = loadJSON(CACHE_DMG, null);
        if (cDmg) {
          const cf = loadJSON(CACHE_TGTFACTOR, null);
          if (cf && cf.n > 0) targetFactorNormal = cf.n;
          if (cf && cf.b > 0) targetFactorBoss = cf.b;
          lastDamage = cDmg;
          renderHome();
          damageShown = true;   // 이후 갱신은 조용히(‘계산 중’·‘오류’ 텍스트 안 띄움)
        }
      } catch (e) { console.error("즉시 페인트 실패(무시하고 정상 로드)", e); }
    }

    // ── 백그라운드: 서버에서 최신 받아 갱신 ──
    // stats+meta+mydata+me를 한 번의 왕복으로(순차 6회 → 1회). 실패 시 개별 엔드포인트로 폴백.
    const painted = !!(cStats && cMeta);
    let boot = null;
    try { boot = await (await fetch("/api/bootstrap")).json(); } catch (e) { console.error("bootstrap 실패, 개별 로드", e); }

    // 정적 데이터(stats·meta)는 배포 때나 바뀜 → 바뀌었을 때만 폼/드롭다운 재구성(평소엔 즉시페인트 화면 유지 = 깜빡임 0).
    const statsChanged = boot && boot.stats && JSON.stringify(boot.stats) !== JSON.stringify(cStats);
    const metaChanged  = boot && boot.meta  && JSON.stringify(boot.meta)  !== JSON.stringify(cMeta);

    if (!painted || statsChanged) await loadStats(boot && boot.stats);
    await loadMe(boot && boot.me);
    if (!painted || metaChanged) { try { await loadMeta(boot && boot.meta); } catch (e) { console.error("메타 로드 실패", e); } }
    else await renderCube();      // 메타 그대로(즉시페인트됨) → 큐브 에디터만 렌더
    if (boot && boot.companions) { companions = boot.companions; saveJSON(CACHE_COMPANIONS, companions); }   // 동료 목록(정적)
    if (boot && boot.artifacts) { artifactCatalog = boot.artifacts; saveJSON(CACHE_ARTIFACTS, artifactCatalog); }   // 유물 카탈로그(정적)
    if (boot && boot.targetCatalog) { targetCatalog = boot.targetCatalog; saveJSON(CACHE_MONDEF, targetCatalog); }   // 대상 카탈로그(정적, 방어력 미포함)
    renderTargetSelector();      // 예상 데미지: 대상(스테이지/보스) 선택
    await loadMyData(false, boot && boot.mydata);   // 시작 시 내 값 자동 채움(배포 땐 빈 값)
    renderConvSettings();        // 딜 환산 설정 목록
    renderRangeSettings();       // 추천 범위 설정 목록
    initStatPresets();           // 빌드 프리셋(스탯+직업+대상+스킬; 없으면 '기본' 생성, 구 스킬 프리셋 병합)
    await initCubePresets();     // 큐브 프리셋
    initAllyPresets();           // 동료 프리셋(메인 동료)
    initRelicPresets();          // 유물 프리셋
    // 미환산 표시 + 예상 데미지는 서로 독립 → 병렬로 1회씩만(초기화 중 중복 호출 제거)
    booting = false;
    await Promise.all([refreshQual(), calcDamage()]);
    await pullPresets();         // 로그인돼 있으면 서버 프리셋으로 동기화(계정이 정본)
  } finally {
    suppressSync = false;        // 이제부터 사용자 편집은 계정에 동기화
  }
  refreshSkillData();   // 스킬 탭 목록 미리 받아 캐시 → 첫 탭 클릭 시 '불러오는 중' 없이 즉시 표시
  // 딥링크 처리: notices는 위에서 이미 렌더됨 → 메뉴 활성/메타만 맞춤(재fetch 방지). 그 외(홈·계산 패널)는 정식 navigate.
  if (bootDeep && bootLeaf.panel === "notices") {
    const it = routeItemEls.get(bootLeaf.path);
    if (it) setActiveMenu(it);
    syncMobileActive(bootLeaf);
    applyRouteMeta(bootLeaf.path);
  } else {
    navigate(bootPath, false);   // 직접 URL 진입(예: /cube)이면 해당 패널로. '/'면 홈 유지(멱등).
  }
})();
