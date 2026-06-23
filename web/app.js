"use strict";

const UNIT_SUFFIX = { PCT: "%", SEC: "초", LEVEL: "Lv", FLAT: "" };
const CUBE_KEY = "mekicalc.cube.v1";
const CONV_KEY = "mekicalc.conv.v1";
const RANGE_KEY = "mekicalc.ranges.v1";
const STATP_KEY = "mekicalc.statPresets.v1";
const CUBEP_KEY = "mekicalc.cubePresets.v1";
// 즉시 페인트용 캐시(서버 대기 없이 화면 먼저 그림). 정적 데이터는 계정 무관, 데미지는 직전 값.
const CACHE_STATS = "mekicalc.cache.statDefs.v1";
const CACHE_META  = "mekicalc.cache.meta.v1";
const CACHE_ME    = "mekicalc.cache.me.v1";
const CACHE_DMG   = "mekicalc.cache.damage.v1";

let statDefs = [];
let meta = { parts: [], grades: [], gradeKo: {} };
let myCube = loadCube();               // { 부위: { 윗잠|아랫잠: {grade, slots:[{stat,value}|null x3], locked:[bool x3], stack} } }
let myConv = loadJSON(CONV_KEY, {});   // 커스텀 딜 환산 statId -> 1단위당 %
let myRanges = loadJSON(RANGE_KEY, {});// 추천 옵션 범위 statId -> {enabled,lo,hi,noLow,noHigh}
let statPresets = loadJSON(STATP_KEY, null); // {active, presets:{name:{stats,job}}}
let cubePresets = loadJSON(CUBEP_KEY, null); // {active, presets:{name:cubeState}}
let qualSet = new Set();               // 현재 직업에서 '딜 미환산'인 스탯 id 집합
const slotCache = {};                  // "부위|등급" -> slots[3][]

function loadJSON(key, def) { try { const v = JSON.parse(localStorage.getItem(key)); return v == null ? def : v; } catch { return def; } }
function saveJSON(key, val) { localStorage.setItem(key, JSON.stringify(val)); if (PRESET_KEYS.has(key)) syncUp(); }
function clone(o) { return JSON.parse(JSON.stringify(o)); }

// ── 계정/서버 동기화 상태 ──
const PRESET_KEYS = new Set([STATP_KEY, CUBEP_KEY, CONV_KEY, RANGE_KEY]);
let me = { oauthEnabled: false, loggedIn: false, name: null, email: null };
let suppressSync = false;     // 서버에서 받아 적용하는 동안 push 막기(에코 방지)
let booting = true;           // 초기화 중에는 재계산(refreshQual·calcDamage)을 미뤄 맨 끝에서 1번만
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
  { label: "프리셋", panel: "presets" },
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
  if (name === "compare") renderCompareEditor(true);
  if (name === "optcompare") renderOptCompare();
  if (name === "recommend") runRecommend(true);   // 진입 시 자동 계산 → 슬라이더 활성화
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
  // 첫 메뉴(프리셋)를 기본 활성 + 스탯 서브탭
  const first = left.querySelector(".menu-item");
  if (first) { setActiveMenu(first); showPanel("presets"); selectSub("stat"); }
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

  const addLeaf = (label, leaf, desktopItem) => {
    const btn = document.createElement("button");
    btn.className = "m-leaf"; btn.type = "button"; btn.textContent = label;
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
      for (const it of m.items) addLeaf(it.label, it, el);
    } else {
      addLeaf(m.label, m, el);
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

// 브랜드(로고+타이틀) 클릭 → 홈(프리셋 패널)으로. 현재 서브탭은 유지(미저장 변경 보존).
const brandEl = document.querySelector("header .brand");
if (brandEl) {
  brandEl.addEventListener("click", () => {
    const first = document.querySelector("#menubar .menu-item");
    if (first) selectLeaf(MENU[0], first);
    window.scrollTo({ top: 0 });
  });
}

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

// 데미지 숫자 표시(즉시 페인트·실계산 공용).
function showDamage(normal, boss) {
  $("dmg-normal").textContent = normal == null ? "—" : fmt(normal);
  $("dmg-boss").textContent = boss == null ? "—" : fmt(boss);
  $("dmg-normal-unit").textContent = normal == null ? "" : "(" + koreanUnit(normal) + ")";
  $("dmg-boss-unit").textContent = boss == null ? "" : "(" + koreanUnit(boss) + ")";
}
async function calcDamage() {
  $("status").textContent = "계산 중…";
  try {
    const data = await postJson("/api/damage", { stats: collectStats(), conversions: myConv });
    showDamage(data.normal, data.boss);
    saveJSON(CACHE_DMG, { normal: data.normal, boss: data.boss });   // 직전 데미지(다음 로드 즉시 표시용)
    $("status").textContent = "";
  } catch (e) { $("status").textContent = "오류: " + e.message; }
}

// ──────────────────── 큐브 기댓값 ────────────────────

let evResults = [];   // 마지막 계산 결과(정렬만 바꿀 때 재요청 없이 재정렬)

async function calcEvaluate() {
  $("ev-status").textContent = "계산 중…";
  $("ev-table").hidden = true;
  try {
    const slotRaw = $("ev-slot").value;
    const data = await postJson("/api/evaluate", {
      part: $("ev-part").value, grade: $("ev-grade").value,
      slot: slotRaw === "" ? null : parseInt(slotRaw, 10),
      bossMode: $("ev-boss").value === "true",
      stats: collectStats(), conversions: myConv,
    });
    evResults = data.results;
    renderEval();
    $("ev-status").textContent = evResults.length ? "" : "표시할 옵션이 없습니다.";
  } catch (e) { $("ev-status").textContent = "오류: " + e.message; }
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
$("ev-btn").addEventListener("click", calcEvaluate);
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
  $("rc-status").textContent = "계산 중…";
  try {
    const data = await postJson("/api/recommend", {
      stats: collectStats(), conversions: myConv, bossMode: $("rc-boss").value === "true",
      cubeState: myCube, ranges: myRanges,
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
$("rc-boss").addEventListener("change", () => runRecommend(true));
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
  $("oc-status").textContent = "계산 중…";
  try {
    const data = await postJson("/api/optcompare", {
      left: { part: $("oc-l-part").value, grade: $("oc-l-grade").value, opts: ocCollect("oc-l") },
      right: { part: $("oc-r-part").value, grade: $("oc-r-grade").value, opts: ocCollect("oc-r") },
      stats: collectStats(), conversions: myConv, bossMode: $("oc-boss").value === "true",
    });
    $("oc-l-result").innerHTML = ocResultHtml(data.left);
    $("oc-r-result").innerHTML = ocResultHtml(data.right);
    $("oc-status").textContent = "";
  } catch (e) { $("oc-status").textContent = "오류: " + e.message; }
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
  $("cmp-status").textContent = "계산 중…";
  $("cmp-result").innerHTML = "";
  try {
    const rightOpts = [];
    for (let i = 0; i < 3; i++) {
      const p = parseSlotKey($("cmp-right-" + i).value);
      if (p) rightOpts.push({ statId: p.stat, value: p.value });
    }
    const data = await postJson("/api/compare", {
      stats: collectStats(), conversions: myConv, bossMode: $("cmp-boss").value === "true",
      cubeState: myCube, ranges: myRanges,
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
    statPresets.presets[statPresets.active] = { stats: collectStats() };
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
  statDirty = false;   // 프리셋 적용 = 저장된 상태
  if (skipRecompute) return;
  refreshQual();
  calcDamage();   // 프리셋 전환 시 즉시 재계산
}
function initStatPresets() {
  if (!statPresets) statPresets = { active: "기본", presets: { "기본": { stats: collectStats() } } };
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
  statPresets.presets[name] = { stats: collectStats() };
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
}
async function initCubePresets() {
  if (!cubePresets) cubePresets = { active: "기본", presets: { "기본": clone(myCube) } };
  else await cpApply(cubePresets.active);
  saveJSON(CUBEP_KEY, cubePresets);
  fillPresetSel($("cp-sel"), cubePresets);
  cubeDirty = false;
}
// 페이지를 실제로 떠날 때(새로고침·닫기) 미저장 큐브 변경 경고.
window.addEventListener("beforeunload", (e) => { if (cubeDirty || statDirty) { e.preventDefault(); e.returnValue = ""; } });
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

// ──────────────────── 프리셋 내보내기 / 불러오기 (.mkbak) ────────────────────

const BACKUP_FORMAT = "mekicalc-presets";

function bundleString() {
  return JSON.stringify({ format: BACKUP_FORMAT, version: 1, statPresets, cubePresets, conversions: myConv, ranges: myRanges }, null, 2);
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

// 백업 문자열을 받아 적용. merge=true면 기존 프리셋 위에 '추가'(덮어쓰지 않음).
// 번들에 있는 섹션만 적용하고 없는/모르는 섹션은 무시 → 구버전 백업·향후 새 프리셋 타입 모두 호환.
async function applyBundle(text, merge) {
  const raw = JSON.parse(text);
  let b;
  if (raw.format === BACKUP_FORMAT) b = raw;
  else if (raw.statPresets && raw.cubePresets) b = fromDesktopBackup(raw);   // 데스크톱/구버전 백업 호환
  else throw new Error("MekiCalc 프리셋 데이터가 아닙니다.");

  if (merge) {
    // 추가(병합): 이름 있는 프리셋 컬렉션만 합치고, 전역 설정(환산·범위)은 현재 것 유지.
    statPresets = mergePresetCollection(statPresets, b.statPresets); saveJSON(STATP_KEY, statPresets);
    cubePresets = mergePresetCollection(cubePresets, b.cubePresets); saveJSON(CUBEP_KEY, cubePresets);
    // 향후 새 프리셋 타입 추가 시 여기 한 줄:  xxxPresets = mergePresetCollection(xxxPresets, b.xxxPresets); saveJSON(...)
  } else {
    // 덮어쓰기(서버 동기화 등). 섹션이 있을 때만 교체 → 없는 섹션은 현재 유지.
    if (b.statPresets) { statPresets = b.statPresets; saveJSON(STATP_KEY, statPresets); }
    if (b.cubePresets) { cubePresets = b.cubePresets; saveJSON(CUBEP_KEY, cubePresets); }
    if (b.conversions) { myConv = b.conversions; saveJSON(CONV_KEY, myConv); }
    if (b.ranges) { myRanges = b.ranges; saveJSON(RANGE_KEY, myRanges); }
  }
  fillPresetSel($("sp-sel"), statPresets); spApply(statPresets.active);
  fillPresetSel($("cp-sel"), cubePresets); await cpApply(cubePresets.active);
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
// 파일에서 불러오기
$("import-btn").addEventListener("click", () => $("import-file").click());
$("import-file").addEventListener("change", (e) => {
  const file = e.target.files[0];
  if (file) {
    const reader = new FileReader();
    reader.onload = async () => {
      try { await applyBundle(reader.result, true); toast("불러온 프리셋을 추가했습니다."); $("backup-status").textContent = ""; }
      catch (err) { $("backup-status").textContent = "불러오기 실패: " + err.message; }
    };
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
// 텍스트로 불러오기 — 텍스트 칸 내용 적용
$("import-text-btn").addEventListener("click", async () => {
  const s = $("backup-text").value.trim();
  if (!s) { $("backup-status").textContent = "먼저 백업 텍스트를 붙여넣으세요."; return; }
  try { await applyBundle(s, true); toast("불러온 프리셋을 추가했습니다."); $("backup-status").textContent = ""; }
  catch (err) { $("backup-status").textContent = "불러오기 실패: " + err.message; }
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
  [STATP_KEY, CUBEP_KEY, CONV_KEY, RANGE_KEY, CUBE_KEY, CACHE_DMG, CACHE_ME].forEach((k) => localStorage.removeItem(k));
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
        const cDmg = loadJSON(CACHE_DMG, null); if (cDmg) showDamage(cDmg.normal, cDmg.boss);   // 직전 데미지 즉시
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
    await loadMyData(false, boot && boot.mydata);   // 시작 시 내 값 자동 채움(배포 땐 빈 값)
    renderConvSettings();        // 딜 환산 설정 목록
    renderRangeSettings();       // 추천 범위 설정 목록
    initStatPresets();           // 스탯 프리셋(없으면 '기본' 생성, 있으면 활성 적용 — 재계산은 미룸)
    await initCubePresets();     // 큐브 프리셋
    // 미환산 표시 + 예상 데미지는 서로 독립 → 병렬로 1회씩만(초기화 중 중복 호출 제거)
    booting = false;
    await Promise.all([refreshQual(), calcDamage()]);
    await pullPresets();         // 로그인돼 있으면 서버 프리셋으로 동기화(계정이 정본)
  } finally {
    suppressSync = false;        // 이제부터 사용자 편집은 계정에 동기화
  }
})();
