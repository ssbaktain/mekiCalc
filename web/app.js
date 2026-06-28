"use strict";

const UNIT_SUFFIX = { PCT: "%", SEC: "초", LEVEL: "Lv", FLAT: "" };
const CUBE_KEY = "mekicalc.cube.v1";
const CONV_KEY = "mekicalc.conv.v1";
const RANGE_KEY = "mekicalc.ranges.v1";
const STATP_KEY = "mekicalc.statPresets.v1";
const CUBEP_KEY = "mekicalc.cubePresets.v1";
const BUFFP_KEY = "mekicalc.buffPresets.v1";
const ALLYP_KEY = "mekicalc.allyPresets.v1";
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
const CACHE_MONDEF = "mekicalc.cache.targetCatalog.v1";   // 대상 카탈로그(챕터/스테이지 구조, 방어력 미포함, 정적)
let targetCatalog = loadJSON(CACHE_MONDEF, null);
let qualSet = new Set();               // 현재 직업에서 '딜 미환산'인 스탯 id 집합
const slotCache = {};                  // "부위|등급" -> slots[3][]

function loadJSON(key, def) { try { const v = JSON.parse(localStorage.getItem(key)); return v == null ? def : v; } catch { return def; } }
function saveJSON(key, val) { localStorage.setItem(key, JSON.stringify(val)); if (PRESET_KEYS.has(key)) syncUp(); }
function clone(o) { return JSON.parse(JSON.stringify(o)); }

// ── 계정/서버 동기화 상태 ──
const PRESET_KEYS = new Set([STATP_KEY, CUBEP_KEY, BUFFP_KEY, ALLYP_KEY, CONV_KEY, RANGE_KEY]);
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
const PART_ORDER = ["모자", "망토", "상의", "어깨장식", "하의", "벨트", "장갑", "신발", "반지", "목걸이", "눈장식", "얼굴장식", "귀고리"];
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
  for (const input of $("stat-form").querySelectorAll("input[data-stat-id]")) {
    const v = parseFloat(input.value);
    stats[input.dataset.statId] = isNaN(v) ? 0 : v;
  }
  stats.JOB = parseInt($("job-sel").value, 10) || 0;   // 직업은 별도 선택기에서
  return stats;
}

// 계산에 보낼 버프 선택. null이면 서버가 전부 적용(현행/하위호환), []는 전부 끔, [...]는 그것만.
// 버프 프리셋의 직업이 현재 캐릭터(스탯) 직업과 다르면 폴백(null=전부 적용) — 엉뚱한 직업 버프로 0 적용되는 사고 방지.
function activeBuffs() {
  const job = parseInt($("job-sel").value, 10) || 0;
  if (job !== myBuffJob) return null;
  return myBuffs;   // null 또는 배열
}

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
let currentSub = "stat";
let cubeNeedsRender = false;   // 큐브 데이터는 바뀌었지만 아직 화면 렌더 안 함(지연 로딩)
function canLeaveEditors() {
  if ($("panel-presets").hidden) return true;
  if (currentSub === "stat") return canLeaveStat();
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
  { label: "예상 데미지", panel: "home" },   // 최좌측 · 드롭다운 없음 · 로고 클릭으로도 진입
  { label: "프리셋", items: [
    { label: "스탯", panel: "presets", sub: "stat" },
    { label: "스킬", panel: "presets", sub: "buff" },
    { label: "동료", panel: "presets", sub: "ally" },
    { label: "유물", panel: "presets", sub: "relic" },
    { label: "큐브", panel: "presets", sub: "cube" },
    { label: "설정", panel: "presets", sub: "settings" },
  ]},
  { label: "큐브", items: [
    { label: "큐브 기댓값", panel: "cube" },
    { label: "옵션 비교", panel: "optcompare" },
    { label: "내 옵션 비교/변경", panel: "compare" },
    { label: "우선순위 추천", panel: "recommend" },
  ]},
  { label: "유물", items: [{ label: "유물 잠재", panel: "soon", soon: "유물 잠재" }] },
  { label: "어빌리티", items: [{ label: "어빌리티 효율", panel: "soon", soon: "어빌리티" }] },
  { label: "용사의 여정", items: [{ label: "용사의 여정", panel: "soon", soon: "용사의 여정" }] },
  { label: "장비 비교", items: [{ label: "장비 비교", panel: "soon", soon: "장비 비교" }] },
];
const MENU_RIGHT = [];   // 설정은 프리셋 패널의 서브탭으로 이동

function showPanel(name) {
  for (const p of document.querySelectorAll(".panel")) p.hidden = (p.id !== "panel-" + name);
  if (name === "home") renderHome();               // 홈 대시보드(로고로 진입)
  if (name === "compare") renderCompareEditor(true);
  if (name === "optcompare") renderOptCompare();
  if (name === "recommend") runRecommend(true);   // 진입 시 자동 계산 → 슬라이더 활성화
  if (name === "cube") calcEvaluate();             // 진입 시 자동 계산(계산 버튼 제거)
}

// 홈(=예상 데미지 탭)으로 이동. 로고 클릭·기본 진입 공용. 첫 메뉴(예상 데미지)를 활성표시.
function goHome() {
  if (!canLeaveEditors()) return;
  closeMenus();
  const first = document.querySelector("#menubar .menu-item");
  if (first) setActiveMenu(first);
  if (typeof mobileMenuEls !== "undefined") mobileMenuEls.forEach((x) => x.btn.classList.toggle("active", x.leaf === MENU[0]));
  showPanel("home");
  window.scrollTo({ top: 0 });
}

// 현재 보이는 패널의 계산을 다시 돌린다(프리셋 전환 시 즉시 반영용). 예상 데미지 카드는 항상 갱신.
function recalcActivePanel() {
  calcDamage();
  if (currentSub === "ally") allyRecalc();   // 동료 탭: 스탯/프리셋 바뀌면 동료 딜 재계산
  if (!$("panel-cube").hidden) calcEvaluate();
  else if (!$("panel-recommend").hidden) runRecommend(true);
  else if (!$("panel-compare").hidden) refreshCompareOnPresetChange();
  else if (!$("panel-optcompare").hidden) runOptCompare();
}

// 내 옵션 비교: 프리셋 전환 시 좌측(현재 큐브)·결과를 갱신하되 우측(가정) 선택은 보존.
async function refreshCompareOnPresetChange() {
  const hadResult = $("cmp-result").innerHTML.trim() !== "";
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

function selectLeaf(leaf, item) {
  // 프리셋 패널(현재 서브탭 유지)로 머무는 이동이 아니면, 미저장 변경 가드.
  const staysInPresets = leaf.panel === "presets" && !leaf.sub;
  if (!staysInPresets && !canLeaveEditors()) return;
  closeMenus();
  setActiveMenu(item);
  if (leaf.panel === "soon") { $("soon-title").textContent = (leaf.soon || "기능") + " — 준비 중"; showPanel("soon"); }
  else showPanel(leaf.panel);
  if (leaf.sub) selectSub(leaf.sub);
}

// 프리셋 패널 안의 스탯/큐브 서브탭 선택.
function selectSub(name) {
  currentSub = name;
  document.querySelectorAll(".subtab").forEach((b) => b.classList.toggle("active", b.dataset.sub === name));
  document.querySelectorAll(".subpanel").forEach((p) => { p.hidden = (p.id !== "sub-" + name); });
  if (name === "cube" && cubeNeedsRender) { cubeNeedsRender = false; renderCube(); }   // 지연 렌더
  if (name === "buff") renderBuffs();   // 버프 목록은 현재 스탯 기준 계산이라 탭 열 때마다 최신화
  if (name === "ally") renderAlly();
}
for (const b of document.querySelectorAll(".subtab")) {
  b.addEventListener("click", () => {
    if (b.dataset.sub !== currentSub && !canLeaveEditors()) return;   // 다른 서브탭으로 갈 때 미저장 가드
    selectSub(b.dataset.sub);
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

  if (m.items) {
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
    label.addEventListener("click", (e) => { e.stopPropagation(); selectLeaf(m, item); });
  }
  return item;
}

function buildMenu() {
  const bar = $("menubar");
  bar.innerHTML = "";
  const left = document.createElement("div"); left.className = "menu-group";
  const right = document.createElement("div"); right.className = "menu-group";
  const itemEls = [];
  for (const m of MENU) { const el = makeMenuItem(m); left.appendChild(el); itemEls.push([m, el]); }
  for (const m of MENU_RIGHT) right.appendChild(makeMenuItem(m));
  bar.appendChild(left);
  bar.appendChild(right);
  bar.addEventListener("mouseleave", closeMenus);   // 메뉴바 벗어나면 드롭다운 닫기
  buildMobileMenu(itemEls);   // 모바일 햄버거 메뉴(좁은 화면용)
  // 기본 진입 = 예상 데미지(홈). 첫 메뉴 활성 + 프리셋 서브탭은 미리 스탯으로 세팅.
  selectSub("stat");
  const firstItem = left.querySelector(".menu-item");
  if (firstItem) setActiveMenu(firstItem);
  showPanel("home");
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
$("job-sel").addEventListener("change", () => { statDirty = true; refreshQual(); calcDamage(); });


// ──────────────────── 스탯 입력 폼 ────────────────────

async function loadStats(pre) {
  try { statDefs = pre || await (await fetch("/api/stats")).json(); saveJSON(CACHE_STATS, statDefs); renderForm(); }
  catch (e) { $("stat-form").textContent = "스탯 목록을 불러오지 못했습니다: " + e; }
}

function renderForm() {
  const groups = new Map();
  for (const d of statDefs) {
    if (d.id === "JOB") continue;   // 직업은 상단 선택기에서
    if (!groups.has(d.group)) groups.set(d.group, []);
    groups.get(d.group).push(d);
  }
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
        `<label for="f_${d.id}">${d.name}</label>` +
        `<div class="input-wrap"><input type="number" id="f_${d.id}" data-stat-id="${d.id}" value="0" step="any" />` +
        `<span class="unit">${UNIT_SUFFIX[d.unit] ?? ""}</span></div>` +
        `<span class="dmgx-badge" hidden>딜 미환산</span>`;
      grid.appendChild(field);
    }
    box.appendChild(grid);
    form.appendChild(box);
  }
  // 스탯 값 변경 시 미저장 표시 + 예상 데미지 자동 재계산
  for (const input of form.querySelectorAll("input[data-stat-id]")) {
    input.addEventListener("change", () => { statDirty = true; calcDamage(); });
  }
  applyQualMarkers();
}

// 현재 qualSet에 맞춰 각 필드의 '딜 미환산' 배지 표시/숨김.
function applyQualMarkers() {
  for (const field of $("stat-form").querySelectorAll(".field")) {
    const badge = field.querySelector(".dmgx-badge");
    if (badge) badge.hidden = !qualSet.has(field.dataset.statId);
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

// 현재 대상(일반/보스) — 스테이지 드롭다운에서 결정(보스 스테이지=보스몹, 일반 스테이지=일반몹).
function currentTarget() {
  const sv = $("tg-stage") && $("tg-stage").value;
  if (sv) return sv === "boss" ? "boss" : "normal";
  const t = targetOfActivePreset();   // 선택기 미구성(부팅 즉시 페인트) 시 저장된 프리셋 대상으로 판정
  return (t && t.stage === "boss") ? "boss" : "normal";
}

// 선택 몹의 방어배수(서버 계산, 방어력 수치 미노출). 표시 딜에 곱해 '해당 몹 기준' 데미지로.
let targetFactor = 1;
async function refreshTargetFactor() {
  const sv = $("tg-stage") && $("tg-stage").value;
  if (!sv) { targetFactor = 1; return; }
  try {
    const res = await postJson("/api/target-damage", Object.assign({
      chapter: $("tg-chapter").value, target: sv, pen: parseFloat(collectStats().DEF_PEN) || 0,
      stats: collectStats(), conversions: myConv,   // 동료 소환창·방어감소 계산용
    }, companionParams()));   // 동료 적 방어력 감소·방관을 방어배수에 반영
    targetFactor = (res && res.factor > 0) ? res.factor : 1;
    saveJSON(CACHE_TGTFACTOR, { tgt: currentTarget(), factor: targetFactor });   // 다음 즉시 페인트용
  } catch (e) { targetFactor = 1; }
}
// 방어배수를 데미지 상세에 곱한 사본(비율/시간 값은 보존).
function scaleDetail(det, f) {
  if (!det || !(f > 0) || f === 1) return det;
  const sc = (v) => (v == null ? v : v * f);
  const d = Object.assign({}, det, { avg: sc(det.avg), min: sc(det.min), max: sc(det.max) });
  if (det.dps) d.dps = Object.assign({}, det.dps, {
    total: sc(det.dps.total), basic: sc(det.dps.basic), skill: sc(det.dps.skill),
    companion: sc(det.dps.companion), companionWindow: sc(det.dps.companionWindow),
  });
  if (Array.isArray(det.shares)) d.shares = det.shares.map((s) => Object.assign({}, s, { dps: sc(s.dps) }));
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
// 대상 선택기 인스턴스(같은 값을 공유): 홈 + 스탯 프리셋 패널(직업 옆).
const TARGET_PAIRS = [["tg-chapter", "tg-stage"], ["spt-chapter", "spt-stage"]];
function currentTargetSel() {
  for (const [chId, stId] of TARGET_PAIRS) {
    const st = $(stId);
    if (st && st.value) return { ch: $(chId).value, stage: st.value };
  }
  return targetOfActivePreset() || undefined;
}
// 스탯 프리셋 저장 페이로드(스탯 + 대상). 모든 저장 지점에서 사용.
function statPresetPayload() { return { stats: collectStats(), target: currentTargetSel() }; }
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
  const box = $("target-box"); if (box) box.hidden = false;
  syncTargetSelector();
  refreshTargetFactor().then(renderHome);   // 부팅 시엔 프리셋에 쓰지 않고 표시만
}
// 사용자가 (어느 선택기든) 대상을 바꾸면 활성 스탯 프리셋의 target에 저장하고 다른 선택기도 동기화.
function onTargetChange(chId, stId) {
  const csel = $(chId || "tg-chapter"), ssel = $(stId || "tg-stage");
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
let lastHomeSig = null;
function renderHome() {
  if (!lastDamage) return;
  const tgt = currentTarget();   // 스테이지 드롭다운에서 일반/보스 결정
  const rawDet = lastDamage.detail && (tgt === "boss" ? lastDamage.detail.boss : lastDamage.detail.normal);
  const sig = JSON.stringify([tgt, targetFactor, rawDet]);
  if (sig === lastHomeSig) return;   // 동일 결과 → 재렌더 스킵(중복 애니메이션·덮어쓰기 방지)
  lastHomeSig = sig;
  const det = scaleDetail(rawDet, targetFactor);   // 선택 몹 방어배수 반영(서버 계산)
  const tname = tgt === "boss" ? "보스몹" : "일반몹";
  const dps = det && det.dps;
  const secs = dps && dps.windowSeconds ? Math.round(dps.windowSeconds) : (tgt === "boss" ? 70 : 90);
  if ($("ts-mode")) $("ts-mode").textContent = `${tgt === "boss" ? "👑 보스몹" : "🗡️ 일반몹"} 기준`;
  if ($("dash-share-sub")) $("dash-share-sub").textContent = `${tname} · ${secs}초 기준`;
  $("dash-detail-sub").textContent = `${tname} · ${secs}초 기준`;

  const shares = (det && det.shares) || [];
  renderDonut(shares);
  renderPcSplit(shares, dps);
  renderHomeStats(det, dps);
  renderDpsBars(dps);
  renderCompanionCard(dps);
}

// 동료 기여 카드: 동료 딜(전투 평균·소환 중) / 플레이어+동료 딜 / 동료 기여도(동료 없을 때 대비 증가율). 동료 선택 시에만.
function renderCompanionCard(dps) {
  const box = $("home-companion"); if (!box) return;
  if (!dps || dps.companion == null) { box.hidden = true; box.innerHTML = ""; return; }
  box.hidden = false;
  const total = dps.total || 0;
  const share = total > 0 ? dps.companion / total * 100 : 0;     // 동료 비중(전체 대비)
  const contrib = dps.companionContrib != null ? dps.companionContrib * 100 : null;   // 동료 없을 때 대비 증가율
  const sd = dps.summonSeconds ? num1s(dps.summonSeconds) : null;
  const secs = dps.windowSeconds ? Math.round(dps.windowSeconds) : null;
  const tile = (label, valHtml, sub, cls) =>
    `<div class="tile${cls ? " " + cls : ""}"><div class="tile-label">${label}</div>` +
    `<div class="tile-val">${valHtml}</div><div class="tile-ku">${sub || ""}</div></div>`;
  const nm = activeCompanionName();
  box.innerHTML =
    `<div class="dash-head"><h2>동료 기여${nm ? " — " + nm : ""}</h2><span class="dash-sub">${secs ? `전투 ${secs}초 평균` : "전투 평균"} 기준</span></div>` +
    `<div class="stat-tiles comp-tiles">` +
      tile("동료 딜", koreanUnit(dps.companion), dps.companionWindow != null ? `소환 중 ${koreanUnit(dps.companionWindow)}${sd ? ` · ${sd}초` : ""}` : "", "tile-comp") +
      tile("플레이어+동료 딜", koreanUnit(total), `동료 비중 ${num1s(share)}%`, "tile-accent") +
      tile("동료 기여도", contrib == null ? "—" : `+${num1s(contrib)}%`, "동료 없을 때 대비", "tile-good") +
    `</div>`;
}

// shares = [{name, dps, companion?}] (내림차순). 작은 조각은 '기타'로 묶고, 동료는 항상 따로.
function buildSlices(shares) {
  const total = shares.reduce((s, x) => s + (x.dps || 0), 0);
  if (total <= 0) return { slices: [], total: 0 };
  const comp = shares.find((x) => x.companion);
  const players = shares.filter((x) => !x.companion).slice().sort((a, b) => b.dps - a.dps);   // 딜 지분 내림차순
  const slices = [];
  let ci = 0, otherDps = 0; const otherItems = [];
  for (const p of players) {
    if ((p.dps / total) >= 0.04 && slices.length < 8) slices.push({ name: p.name, dps: p.dps, color: PIE_COLORS[ci++ % PIE_COLORS.length] });
    else { otherDps += p.dps; otherItems.push({ name: p.name, dps: p.dps }); }   // 기타에 묶인 항목들(호버용)
  }
  if (otherDps > 0) slices.push({ name: "기타", dps: otherDps, color: OTHER_COLOR, other: true, items: otherItems });
  if (comp && comp.dps > 0) slices.push({ name: activeCompanionLabel(), dps: comp.dps, color: COMPANION_COLOR, companion: true });
  return { slices, total };
}

function polar(cx, cy, r, deg) { const a = (deg - 90) * Math.PI / 180; return [cx + r * Math.cos(a), cy + r * Math.sin(a)]; }
function ringPath(cx, cy, R, r, a0, a1) {
  const large = (a1 - a0) > 180 ? 1 : 0;
  const [ox0, oy0] = polar(cx, cy, R, a0), [ox1, oy1] = polar(cx, cy, R, a1);
  const [ix1, iy1] = polar(cx, cy, r, a1), [ix0, iy0] = polar(cx, cy, r, a0);
  return `M${ox0.toFixed(2)} ${oy0.toFixed(2)} A${R} ${R} 0 ${large} 1 ${ox1.toFixed(2)} ${oy1.toFixed(2)} L${ix1.toFixed(2)} ${iy1.toFixed(2)} A${r} ${r} 0 ${large} 0 ${ix0.toFixed(2)} ${iy0.toFixed(2)} Z`;
}

function renderDonut(shares) {
  const chart = $("donut-chart"), legend = $("donut-legend");
  if (!chart) return;
  const { slices, total } = buildSlices(shares);
  if (!slices.length) { chart.innerHTML = "<div class='donut-empty'>데이터 없음</div>"; legend.innerHTML = ""; return; }
  // 툴팁: '기타'면 묶인 항목 내역까지 펼쳐 보여줌
  const tip = (s) => {
    let t = `${s.name} · ${num1s(s.dps / total * 100)}%`;
    if (s.other && s.items && s.items.length) {
      t += "\n" + s.items.map((it) => `· ${it.name} ${num1s(it.dps / total * 100)}% (${koreanUnit(it.dps)})`).join("\n");
    }
    return t;
  };
  const S = 180, cx = S / 2, cy = S / 2, R = 84, r = 50;
  let a = 0, paths = "";
  slices.forEach((s, i) => {
    const frac = s.dps / total, deg = frac * 360;
    const a1 = (i === slices.length - 1) ? 360 : a + deg;   // 마지막은 360까지(부동소수 틈 방지)
    let d;
    if (slices.length === 1) d = ringPath(cx, cy, R, r, 0.01, 359.99);
    else d = ringPath(cx, cy, R, r, a, a1);
    paths += `<path d="${d}" fill="${s.color}" class="pie-seg" data-i="${i}"><title>${escapeHtml(tip(s))}</title></path>`;
    a = a1;
  });
  const compPct = slices.filter((s) => s.companion).reduce((p, s) => p + s.dps, 0) / total * 100;
  chart.innerHTML =
    `<svg viewBox="0 0 ${S} ${S}" class="donut-svg" role="img" aria-label="딜 구성 도넛"><g class="donut-ring">${paths}</g>` +
    `<text x="${cx}" y="${cy - 6}" class="donut-center-top">종합 DPS</text>` +
    `<text x="${cx}" y="${cy + 14}" class="donut-center-val">${total > 0 ? koreanUnit(total) : "—"}</text></svg>`;
  const lgRow = (cls, di, dot, name, pct, val, title) =>
    `<div class="${cls}" data-i="${di}"${title ? ` title="${escapeHtml(title)}"` : ""}>` +
    dot + `<span class="lg-name">${name}</span>` +
    `<span class="lg-pct">${num1s(pct)}%</span><span class="lg-val">${koreanUnit(val)}</span></div>`;
  legend.innerHTML = slices.map((s, i) => {
    // 기타: 그래프엔 한 조각, 범례엔 세부 종류를 다 적되 '기타'로 묶어 펼침
    if (s.other && s.items && s.items.length) {
      const head = lgRow("lg-row lg-other-head", i, `<span class="lg-dot" style="background:${s.color}"></span>`,
        `기타 <span class="lg-cnt">${s.items.length}개</span>`, s.dps / total * 100, s.dps, tip(s));
      const subs = s.items.map((it) =>
        lgRow("lg-row lg-subrow", i, `<span class="lg-subdot"></span>`, escapeHtml(it.name), it.dps / total * 100, it.dps, "")
      ).join("");
      return `<div class="lg-group" data-i="${i}">${head}<div class="lg-sublist">${subs}</div></div>`;
    }
    return lgRow("lg-row", i, `<span class="lg-dot" style="background:${s.color}"></span>`,
      escapeHtml(s.name), s.dps / total * 100, s.dps, tip(s));
  }).join("");
  // 범례 ↔ 조각 하이라이트 연동
  const segs = chart.querySelectorAll(".pie-seg"), rows = legend.querySelectorAll(".lg-row");
  const hi = (i, on) => {
    segs.forEach((p) => p.classList.toggle("dim", on && +p.dataset.i !== i));
    rows.forEach((rw) => rw.classList.toggle("active", on && +rw.dataset.i === i));
  };
  segs.forEach((p) => { p.addEventListener("mouseenter", () => hi(+p.dataset.i, true)); p.addEventListener("mouseleave", () => hi(0, false)); });
  rows.forEach((rw) => { rw.addEventListener("mouseenter", () => hi(+rw.dataset.i, true)); rw.addEventListener("mouseleave", () => hi(0, false)); });
}

// 플레이어 vs 동료 비율 스택 바(데스크톱=가로, 모바일=세로 — 크기는 CSS의 --seg로 제어).
function renderPcSplit(shares, dps) {
  const box = $("pc-split"); if (!box) return;
  const comp = shares.find((x) => x.companion);
  if (!comp) { box.innerHTML = ""; box.hidden = true; return; }
  box.hidden = false;
  const total = shares.reduce((s, x) => s + (x.dps || 0), 0);
  const cDps = comp.dps, pDps = total - cDps;
  const pPct = total > 0 ? pDps / total * 100 : 0, cPct = 100 - pPct;
  const cl = activeCompanionLabel();
  box.innerHTML =
    `<div class="pc-head"><span>플레이어 vs ${cl}</span></div>` +
    `<div class="pc-bar">` +
      `<div class="pc-seg pc-player" style="--seg:${pPct}%"><span>${num1s(pPct)}%</span></div>` +
      `<div class="pc-seg pc-comp" style="--seg:${cPct}%"><span>${num1s(cPct)}%</span></div>` +
    `</div>` +
    `<div class="pc-legend"><span><i class="pc-i pc-player"></i>플레이어 ${koreanUnit(pDps)}</span>` +
    `<span><i class="pc-i pc-comp"></i>${cl} ${koreanUnit(cDps)}</span></div>`;
}

// 1타 최소/최대/평균 + 전체 DPS 타일
function renderHomeStats(det, dps) {
  const box = $("home-stats"); if (!box) return;
  if (!det) { box.innerHTML = ""; return; }
  const tile = (label, v, accent) =>
    `<div class="tile${accent ? " tile-accent" : ""}"><div class="tile-label">${label}</div>` +
    `<div class="tile-val">${v == null ? "—" : koreanUnit(v)}</div>` +
    `<div class="tile-ku">${v == null ? "" : fmt(Math.round(v))}</div></div>`;
  box.innerHTML =
    tile("1타 평균딜", det.avg, true) + tile("1타 최소딜", det.min) + tile("1타 최대딜", det.max) +
    tile("전체 DPS", dps ? dps.total : null, true);
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
      const data = await postJson("/api/damage", Object.assign({ stats: collectStats(), conversions: myConv, buffs: activeBuffs(), hitInterval: myHitInterval }, activeCompanionSel()));
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
  for (const input of $("stat-form").querySelectorAll("input[data-stat-id]")) input.value = "0";
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
    card.innerHTML =
      `<div class="rec-head"><span class="rank">${idx + 1}</span><b>${r.part} ${r.upper ? "윗잠" : "아랫잠"}</b>${cross}</div>` +
      `<div class="rec-metrics">` +
      `<span>딜 증가량 <b class="good">${signPct(r.dealDiff)}</b></span>` +
      `<span title="이 추천 옵션 구성이 한 큐브에 그대로 나올 확률(동일 옵션 등장 확률 총합)">등장 확률 <b>${pct(r.recProbSum)}</b></span>` +
      `<span>평균 <b>${r.recCubes == null ? "∞" : fmt(r.recCubes)}</b>개</span>` +
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

  let rows = `<div class="sc-row sc-titles"><div class="sc-col-title">현재 (내 큐브)</div><div class="sc-col-title">가정</div></div>`;
  const leftGrade = (line && line.grade) ? gradeText(line.grade) : '<span class="muted">(없음)</span>';
  rows += `<div class="sc-row"><div class="sc-cell sc-grade-cell">${leftGrade}</div><div class="sc-cell" id="ce-rg"></div></div>`;
  for (let i = 0; i < 3; i++) {
    const lo = (leftTables && line.slots[i]) ? findDraw(leftTables[i] || [], line.slots[i].stat, line.slots[i].value) : null;
    rows += `<div class="sc-row">${scBox(lo, "neutral", i + 1)}<div class="sc-cell" id="ce-rs-${i}"></div></div>`;
  }
  $("cmp-editor").innerHTML = `<div class="slot-compare">${rows}</div>`;

  // 우측 등급 select(동적 생성, id 유지)
  const gsel = document.createElement("select");
  gsel.id = "cmp-right-grade";
  fillGradeSelect(gsel); gsel.value = cmpRightGrade; applyGradeColor(gsel);
  gsel.addEventListener("change", () => { cmpRightGrade = gsel.value; renderCompareEditor(false); });
  $("ce-rg").appendChild(gsel);

  // 우측 슬롯 select
  for (let i = 0; i < 3; i++) {
    const sel = document.createElement("select");
    sel.id = "cmp-right-" + i;
    fillSlotSelect(sel, rightTables[i] || [], null);
    $("ce-rs-" + i).appendChild(sel);
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
    const lose = data.dealDiff <= 0;
    $("cmp-result").innerHTML =
      `<div class="cmp-grid">` +
      cell("딜 증가량 차이", `<b class="${lose ? "bad" : "good"}">${signPct(data.dealDiff)}</b>`) +
      cell(lose ? "딜 손해" : "최종 효율", lose ? `<span class="bad">${signPct(data.dealDiff)}</span>` : fmtEff(data.gainEff)) +
      cell("현재 딜 / 등장 확률", `${signPct(data.leftDeal)} / ${pct(data.leftProbSum, 3)}`) +
      cell("가정 딜 / 등장 확률", `${signPct(data.rightDeal)} / ${pct(data.rightProbSum, 3)}`) +
      cell("1회 리롤: 현재보다 좋을 확률", pct(data.totalProb, 3)) +
      cell("1회 리롤: 가정보다 좋을 확률", pct(data.remainProb, 3)) +
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
    // 1) 옵션 교체에 따른 새 스탯 총합 계산(엔진: 기존 빼고 새 옵션 더함)
    const data = await postJson("/api/applystats", { stats: collectStats(), conversions: myConv, oldOpts, newOpts });
    // 2) 스탯 폼 + 활성 스탯 프리셋에 반영(로그인 시 서버 동기화도 트리거)
    fillStats(data.stats);
    statPresets.presets[statPresets.active] = statPresetPayload();
    saveJSON(STATP_KEY, statPresets);
    statDirty = false;
    // 3) 큐브 줄 + 활성 큐브 프리셋에 반영
    if (!myCube[part]) myCube[part] = { 윗잠: emptyLine(), 아랫잠: emptyLine() };
    const locked = (line && line.locked) ? line.locked.slice() : [false, false, false];
    myCube[part][lineName] = {
      grade: newGrade,
      slots: rightSlots.map((s) => (s ? { stat: s.stat, value: s.value } : null)),
      locked, stack: newStack,
    };
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
function fillStats(stats) {
  for (const input of $("stat-form").querySelectorAll("input[data-stat-id]")) {
    const v = stats[input.dataset.statId];
    input.value = (v == null) ? "0" : v;
  }
}

// 서버에 저장된 내 데이터(스탯·환산·큐브)를 불러온다.
// overwriteCube=true면 큐브도 덮어쓴다(버튼). 자동 로드 시엔 내 큐브가 비어 있을 때만 채운다.
async function loadMyData(overwriteCube, pre) {
  try {
    const data = pre || await (await fetch("/api/mydata")).json();
    if (data.stats && Object.keys(data.stats).length) {
      fillStats(data.stats);
      if (data.stats.JOB != null) $("job-sel").value = String(Math.round(data.stats.JOB));
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

// 스탯 프리셋. skipRecompute=true면 폼만 채우고 재계산은 건너뜀(초기화 중 중복 호출 방지).
function spApply(name, skipRecompute) {
  const p = statPresets.presets[name];
  if (!p) return;
  fillStats(p.stats);
  $("job-sel").value = String(Math.round((p.stats && p.stats.JOB) || 0));
  syncTargetSelector();   // 대상(챕터/스테이지)도 프리셋 값으로 — 직업처럼 함께 전환
  statDirty = false;   // 프리셋 적용 = 저장된 상태
  if (skipRecompute) return;
  refreshQual();
  recalcActivePanel();   // 프리셋 전환 시 보이는 패널까지 즉시 재계산(대상 반영 딜 포함)
}
function initStatPresets() {
  if (!statPresets) statPresets = { active: "기본", presets: { "기본": statPresetPayload() } };
  else spApply(statPresets.active, booting);   // 초기화 중엔 재계산 미룸(init 끝에서 1번)
  saveJSON(STATP_KEY, statPresets);
  fillPresetSel($("sp-sel"), statPresets);
  statDirty = false;
}
$("sp-sel").addEventListener("change", () => {
  if (!canLeaveStat()) { $("sp-sel").value = statPresets.active; return; }   // 미저장이면 취소 시 되돌림
  statPresets.active = $("sp-sel").value; saveJSON(STATP_KEY, statPresets); spApply(statPresets.active);
});
$("sp-save").addEventListener("click", () => { statPresets.presets[statPresets.active] = { stats: collectStats() }; saveJSON(STATP_KEY, statPresets); statDirty = false; calcDamage(); toast(`'${statPresets.active}' 스탯 프리셋 저장됨`); });
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

// 활성 버프 프리셋 → 작업 상태(myBuffJob/myBuffs) 적재 후 화면·데미지 갱신.
function bpApply(name) {
  const p = buffPresets.presets[name];
  if (!p) return;
  myBuffJob = p.job || 0;
  myBuffs = Array.isArray(p.buffs) ? p.buffs.slice() : null;
  myHitInterval = p.hitInterval || 0;
  buffDirty = false;
  $("bp-job").value = String(myBuffJob);
  if ($("hit-interval")) $("hit-interval").value = myHitInterval;
  if (currentSub === "buff") renderBuffs();
  recalcActivePanel();   // 스킬 프리셋 바뀌면 보이는 계산까지 즉시 갱신
}

function initBuffPresets() {
  if (!buffPresets) {
    const job = parseInt($("job-sel").value, 10) || 0;   // 현재 캐릭터 직업을 기본 버프 직업으로
    buffPresets = { active: "기본", presets: { "기본": { job, buffs: null } } };
    myBuffJob = job; myBuffs = null;
  }
  saveJSON(BUFFP_KEY, buffPresets);
  fillPresetSel($("bp-sel"), buffPresets);
  buffDirty = false;
}

// 스킬 탭 데이터 캐시(딜 스킬 + 버프). 직전 응답을 localStorage에도 보관 → 도메인(터널 지연)에서도 즉시 표시.
let skillData = loadJSON(CACHE_SKILLS, null);
let skillView = "deal";   // "deal"(딜 스킬) | "buff"(버프 스킬)

// 현재 스탯 기준 스킬/버프 목록을 그린다. 캐시/메모리에 있으면 즉시 렌더(불러오는 중 안 띄움), 항상 백그라운드로 최신화.
async function renderBuffs() {
  if (!buffPresets) initBuffPresets();
  $("bp-job").value = String(myBuffJob);
  if ($("hit-interval")) $("hit-interval").value = myHitInterval;
  syncSkillViewButtons();
  if (myBuffJob !== 1) {   // DPS 환산 모델 있는 직업만(현재 나이트로드). 공용 등은 준비 중.
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
  if (myBuffJob !== 1) return;
  try {
    const stats = collectStats(); stats.JOB = myBuffJob;
    const fresh = await postJson("/api/buffs", { stats });
    skillData = fresh; saveJSON(CACHE_SKILLS, fresh);
    if (currentSub === "buff") renderActiveSkillView();
  } catch (e) {
    if (!skillData && currentSub === "buff") {
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
  buffDirty = true;
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

$("bp-sel").addEventListener("change", () => {
  if (buffDirty && !confirm("저장하지 않은 스킬 변경이 있습니다. 버리고 전환할까요?")) { $("bp-sel").value = buffPresets.active; return; }
  buffPresets.active = $("bp-sel").value; saveJSON(BUFFP_KEY, buffPresets); bpApply(buffPresets.active);
});
$("bp-save").addEventListener("click", () => {
  buffPresets.presets[buffPresets.active] = { job: myBuffJob, buffs: myBuffs, hitInterval: myHitInterval };
  saveJSON(BUFFP_KEY, buffPresets); buffDirty = false;
  toast(`'${buffPresets.active}' 스킬 프리셋 저장됨`);
});
$("bp-new").addEventListener("click", () => {
  const name = (prompt("새 스킬 프리셋 이름") || "").trim();
  if (!name || buffPresets.presets[name]) return;
  buffPresets.presets[name] = { job: myBuffJob, buffs: myBuffs, hitInterval: myHitInterval };
  buffPresets.active = name; saveJSON(BUFFP_KEY, buffPresets); fillPresetSel($("bp-sel"), buffPresets);
  buffDirty = false;
});
$("bp-del").addEventListener("click", () => {
  const names = Object.keys(buffPresets.presets);
  if (names.length <= 1) return;
  delete buffPresets.presets[buffPresets.active];
  buffPresets.active = Object.keys(buffPresets.presets)[0];
  saveJSON(BUFFP_KEY, buffPresets); fillPresetSel($("bp-sel"), buffPresets); bpApply(buffPresets.active);
});
// 피격 주기 입력은 제거됨(항상 '안 맞기'=최고 효율 가정 → myHitInterval=0). 반격 계산코드는 백엔드에 휴면 상태로 둠.
$("bp-rename").addEventListener("click", () => {
  if (renamePreset(buffPresets) == null) return;
  saveJSON(BUFFP_KEY, buffPresets); fillPresetSel($("bp-sel"), buffPresets);
  toast("스킬 프리셋 이름을 변경했습니다.");
});
$("bp-job").addEventListener("change", () => {
  myBuffJob = parseInt($("bp-job").value, 10) || 0;
  myBuffs = null;   // 직업 바뀌면 선택 초기화(전부 체크)
  buffDirty = true;
  renderBuffs(); calcDamage();
});

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
    '</div>' +
    '<div id="ally-result" class="ally-result"></div>';
  const csel = $("al-companion");
  for (const c of companions) csel.add(new Option(c.name || c.id, c.id));
  csel.value = p.companionId || companions[0].id;
  fillAllyGrades(csel.value, p.grade);
  $("al-level").value = p.level != null ? p.level : 1;
  $("al-ratio").value = p.ratio != null ? p.ratio : 0;
  csel.addEventListener("change", () => { fillAllyGrades(csel.value, null); allyDirty = true; allyRecalc(); });
  for (const id of ["al-grade", "al-level", "al-ratio"]) $(id).addEventListener("change", () => { allyDirty = true; allyRecalc(); });
  allyRecalc();
}

// 동료 종합 딜 계산 → 결과 표시(네 프리셋 스탯 + 동료 입력). 소환 유지창 기준 own-DPS + 항목별.
// 요청 순번(allyReqSeq) 가드: 동료를 빠르게 바꿔 여러 요청이 겹칠 때, 늦게 도착한 옛 응답이 최신 결과를 덮어쓰지 못하게 한다.
let allyReqSeq = 0;
async function allyRecalc() {
  const box = $("ally-result");
  if (!box || !$("al-companion")) return;
  const a = collectAlly();
  const boss = currentTarget() === "boss";   // 보스/일반 판정은 활성 스탯 프리셋의 대상을 따름
  const seq = ++allyReqSeq;
  try {
    const d = await postJson("/api/companion-dps", Object.assign({ stats: collectStats(), conversions: myConv, companionId: a.companionId, grade: a.grade, level: a.level, ratio: a.ratio, buffs: activeBuffs() }, targetForApi()));   // 대상(챕터/스테이지) → 방어배수 일관 적용
    if (seq !== allyReqSeq) return;   // 더 새 요청이 떴으면 이 응답은 폐기(옛 동료 값으로 덮어쓰기 방지)
    if (!d.valid) { box.innerHTML = '<p class="status">이 등급 데이터가 없어요.</p>'; return; }
    const items = Object.entries(d.detail || {}).sort((x, y) => y[1] - x[1])
      .map(([k, v]) => `<div class="buff-tip-row"><span>${k}</span><b>${fmtBig(v)}</b></div>`).join("");
    box.innerHTML =
      `<div class="ally-sum">동료 종합 딜 <b>${fmtBig(d.ownDps)}</b> <span class="muted">/ 초 (${boss ? "보스" : "일반몹"} · 스탯 프리셋 대상 기준, 소환 유지창 기준)</span></div>` +
      `<div class="ally-sub">평타 ${fmtBig(d.basicDps)} · 스킬 ${fmtBig(d.skillDps)} · 계승 ${num1s(d.ratioUsed)}%(참고 ${num1s(d.refRatio)}%) · 가동률 ${(d.uptime * 100).toFixed(0)}%</div>` +
      `<details class="ally-detail"><summary>항목별 보기</summary>${items}</details>`;
  } catch (e) { if (seq === allyReqSeq) box.innerHTML = '<p class="status">계산 실패: ' + e.message + '</p>'; }
  if (seq === allyReqSeq) calcDamage();   // 최신 요청일 때만 예상 데미지 카드의 동료 DPS 갱신
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

// ──────────────────── 프리셋 빠른 전환(플로팅) ────────────────────
// 어디서든 우하단 ⚡ → 작은 패널에서 프리셋을 바로 전환. 메인 셀렉트와 동일 로직 + 동기화.
// 프리셋 종류를 추가할 땐 이 목록에만 한 줄 추가하면 플로팅 패널에 자동 반영됨(순서도 이 배열 순서).
const QP_TYPES = [
  { key: "sp", label: "스탯", main: "sp-sel", store: () => statPresets, saveKey: STATP_KEY,
    canLeave: () => canLeaveStat(), apply: (n) => spApply(n) },
  { key: "bp", label: "스킬", main: "bp-sel", store: () => buffPresets, saveKey: BUFFP_KEY,
    canLeave: () => !buffDirty || confirm("저장하지 않은 스킬 변경이 있습니다. 버리고 전환할까요?"), apply: (n) => bpApply(n) },
  { key: "ap", label: "동료", main: "ap-sel", store: () => allyPresets, saveKey: ALLYP_KEY,
    canLeave: () => !allyDirty || confirm("저장하지 않은 동료 변경이 있습니다. 버리고 전환할까요?"), apply: (n) => apApply(n) },
  { key: "cp", label: "큐브", main: "cp-sel", store: () => cubePresets, saveKey: CUBEP_KEY,
    canLeave: () => canLeaveCube(), apply: (n) => cpApply(n) },
];
function buildQuickPanel() {
  const rows = $("qp-rows"); if (!rows) return;
  rows.innerHTML = QP_TYPES.map((t) => `<label class="qp-row"><span>${t.label}</span><select id="qs-${t.key}"></select></label>`).join("");
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
  return JSON.stringify({ format: BACKUP_FORMAT, version: 1, statPresets, cubePresets, buffPresets, allyPresets, conversions: myConv, ranges: myRanges }, null, 2);
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
  } else {
    // 덮어쓰기(서버 동기화 등). 섹션이 있을 때만 교체 → 없는 섹션은 현재 유지.
    if (b.statPresets) { statPresets = b.statPresets; saveJSON(STATP_KEY, statPresets); }
    if (b.cubePresets) { cubePresets = b.cubePresets; saveJSON(CUBEP_KEY, cubePresets); }
    if (b.buffPresets) { buffPresets = b.buffPresets; saveJSON(BUFFP_KEY, buffPresets); }
    if (b.allyPresets) { allyPresets = b.allyPresets; saveJSON(ALLYP_KEY, allyPresets); }
    if (b.conversions) { myConv = b.conversions; saveJSON(CONV_KEY, myConv); }
    if (b.ranges) { myRanges = b.ranges; saveJSON(RANGE_KEY, myRanges); }
  }
  fillPresetSel($("sp-sel"), statPresets); spApply(statPresets.active);
  fillPresetSel($("cp-sel"), cubePresets); await cpApply(cubePresets.active);
  // 버프 프리셋: 활성 프리셋을 작업 상태로 적재(직업·선택). 버프 탭이 보이면 다시 그림.
  if (buffPresets && buffPresets.presets[buffPresets.active]) {
    const bp = buffPresets.presets[buffPresets.active];
    myBuffJob = bp.job || 0; myBuffs = Array.isArray(bp.buffs) ? bp.buffs.slice() : null; buffDirty = false;
    myHitInterval = bp.hitInterval || 0;
    fillPresetSel($("bp-sel"), buffPresets); $("bp-job").value = String(myBuffJob);
    if ($("hit-interval")) $("hit-interval").value = myHitInterval;
    if (currentSub === "buff") renderBuffs();
  }
  if (allyPresets) { fillPresetSel($("ap-sel"), allyPresets); allyDirty = false; if (currentSub === "ally") renderAlly(); }
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
  { key: "statPresets", label: "스탯" },
  { key: "cubePresets", label: "큐브" },
  { key: "buffPresets", label: "스킬" },
  { key: "allyPresets", label: "동료" },
];
let pendingBundle = null;   // 선택 대기 중인 파싱된 번들

// 컬렉션({active,presets})에서 선택된 이름만 남긴다(활성=선택 중 마지막).
function filterCollection(coll, nameSet) {
  const presets = {};
  let active = coll.active;
  for (const [n, v] of Object.entries(coll.presets || {})) if (nameSet.has(n)) { presets[n] = v; active = n; }
  return { active, presets };
}

// 백업 텍스트를 파싱해 선택 패널을 연다(프리셋 없으면 안내).
function openImportSelection(text) {
  let b;
  try { b = parseBundle(text); }
  catch (err) { $("backup-status").textContent = "불러오기 실패: " + err.message; return; }
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
  if (!any) { $("backup-status").textContent = "백업에 불러올 프리셋이 없습니다."; $("import-select").hidden = true; pendingBundle = null; return; }
  pendingBundle = b;
  $("imp-all").checked = true;
  $("import-select").hidden = false;
  $("backup-status").textContent = "";
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
// 선택한 프리셋만 병합 적용
$("import-apply-btn").addEventListener("click", async () => {
  if (!pendingBundle) return;
  const checked = [...document.querySelectorAll(".imp-cb:checked")];
  if (!checked.length) { $("backup-status").textContent = "불러올 프리셋을 하나 이상 선택하세요."; return; }
  const sel = {};
  for (const cb of checked) { (sel[cb.dataset.sec] = sel[cb.dataset.sec] || new Set()).add(cb.dataset.name); }
  const filtered = { format: BACKUP_FORMAT, version: 1 };
  for (const sec of IMPORT_SECTIONS) if (sel[sec.key] && pendingBundle[sec.key]) filtered[sec.key] = filterCollection(pendingBundle[sec.key], sel[sec.key]);
  try {
    await applyBundleObj(filtered, true);
    toast(`${checked.length}개 프리셋을 불러왔습니다.`);
    $("import-select").hidden = true; pendingBundle = null; $("backup-status").textContent = "";
  } catch (err) { $("backup-status").textContent = "불러오기 실패: " + err.message; }
});
// 취소
$("import-cancel-btn").addEventListener("click", () => { $("import-select").hidden = true; pendingBundle = null; });

// 파일에서 불러오기 → 선택 패널 열기
$("import-btn").addEventListener("click", () => $("import-file").click());
$("import-file").addEventListener("change", (e) => {
  const file = e.target.files[0];
  if (file) {
    const reader = new FileReader();
    reader.onload = () => openImportSelection(reader.result);
    reader.readAsText(file);
  }
  e.target.value = "";
});
// 텍스트로 내보내기(복사) — 텍스트 칸에 표시 + 클립보드 복사 시도
$("export-text-btn").addEventListener("click", async () => {
  const s = bundleString();
  $("backup-text").value = s;
  try { await navigator.clipboard.writeText(s); toast("텍스트를 복사했습니다."); $("backup-status").textContent = ""; }
  catch { $("backup-status").textContent = "아래 칸의 텍스트를 복사하세요(자동 복사 실패)."; }
});
// 텍스트로 불러오기 — 텍스트 칸 내용 파싱 후 선택 패널 열기
$("import-text-btn").addEventListener("click", () => {
  const s = $("backup-text").value.trim();
  if (!s) { $("backup-status").textContent = "먼저 백업 텍스트를 붙여넣으세요."; return; }
  openImportSelection(s);
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

(async function init() {
  // 초기화 중에는 자동 push 금지: 계정 정본을 pull하기 전에 로컬(스테일) 상태를 계정에 덮어쓰는 것 방지
  // (멀티 기기에서 나중에 연 기기가 옛 값으로 계정을 덮어써 예상 데미지가 흔들리던 문제). 사용자 편집은 init 후부터 동기화.
  suppressSync = true;
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
          if (cf && cf.tgt === currentTarget() && cf.factor > 0) targetFactor = cf.factor;
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
    if (boot && boot.targetCatalog) { targetCatalog = boot.targetCatalog; saveJSON(CACHE_MONDEF, targetCatalog); }   // 대상 카탈로그(정적, 방어력 미포함)
    renderTargetSelector();      // 예상 데미지: 대상(스테이지/보스) 선택
    await loadMyData(false, boot && boot.mydata);   // 시작 시 내 값 자동 채움(배포 땐 빈 값)
    renderConvSettings();        // 딜 환산 설정 목록
    renderRangeSettings();       // 추천 범위 설정 목록
    initStatPresets();           // 스탯 프리셋(없으면 '기본' 생성, 있으면 활성 적용 — 재계산은 미룸)
    await initCubePresets();     // 큐브 프리셋
    initBuffPresets();           // 버프 프리셋(없으면 현재 직업 기준 '기본' 생성)
    initAllyPresets();           // 동료 프리셋(메인 동료)
    // 미환산 표시 + 예상 데미지는 서로 독립 → 병렬로 1회씩만(초기화 중 중복 호출 제거)
    booting = false;
    await Promise.all([refreshQual(), calcDamage()]);
    await pullPresets();         // 로그인돼 있으면 서버 프리셋으로 동기화(계정이 정본)
  } finally {
    suppressSync = false;        // 이제부터 사용자 편집은 계정에 동기화
  }
  refreshSkillData();   // 스킬 탭 목록 미리 받아 캐시 → 첫 탭 클릭 시 '불러오는 중' 없이 즉시 표시
})();
