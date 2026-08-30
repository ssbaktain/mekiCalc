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
const BUILDP_KEY = "mekicalc.builds.v1";        // 빌드 프리셋(타임라인 전용) {active, presets:{name:{rotation,autoFill}}}
const CACHE_ARTIFACTS = "mekicalc.cache.artifacts.v1";   // 유물 카탈로그(목록+슬롯임계, 정적)
// 즉시 페인트용 캐시(서버 대기 없이 화면 먼저 그림). 정적 데이터는 계정 무관, 데미지는 직전 값.
const CACHE_STATS = "mekicalc.cache.statDefs.v2";   // v2: combine/max 추가(변경 실험용)
const CACHE_META  = "mekicalc.cache.meta.v1";
const CACHE_ME    = "mekicalc.cache.me.v1";
const CACHE_DMG   = "mekicalc.cache.damage.v1";
const CACHE_TGTFACTOR = "mekicalc.cache.tgtfactor.v1";   // 직전 방어배수({tgt,factor}) — 즉시 페인트 시 신선값과 일치시켜 애니메이션 1회 유지
const CACHE_SKILLS = "mekicalc.cache.skills.v1";   // 스킬 탭 목록(즉시 페인트용, 직업별)
const CACHE_COMPANIONS = "mekicalc.cache.companions.v1";   // 동료 목록(정적, 즉시 표시용)
const CACHE_COMPANION_META = "mekicalc.cache.companionMeta.v1";   // 동료 계승 상수(등급별 레벨별 계승비율 등, 정적)

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
function setAllyDirty(v) { allyDirty = v; const b = $("ap-save"); if (b) b.disabled = !v; }
let companions = loadJSON(CACHE_COMPANIONS, []);
let companionMeta = loadJSON(CACHE_COMPANION_META, {});   // {inheritByLevel, gradeLevelCap, compareLevel}
// 유물: 작업 상태 myRelic=[{id,awaken,on}], 프리셋 relicPresets. 카탈로그=목록+슬롯임계(bootstrap).
let artifactCatalog = loadJSON(CACHE_ARTIFACTS, { list: [], slotByAwakenSum: [] });
let myRelic = normalizeRelic(loadJSON(RELIC_KEY, null));   // { awaken:{id:0~5}, equipped:[id...] }
let relicPresets = loadJSON(RELICP_KEY, null);
let relicDirty = false;
function setRelicDirty(v) { relicDirty = v; const b = $("rp-save"); if (b) b.disabled = !v; }
let buildPresets = loadJSON(BUILDP_KEY, null);   // 빌드 프리셋(타임라인 로테이션) — 첫 타임라인 진입 시 초기화
const CACHE_MONDEF = "mekicalc.cache.targetCatalog.v1";   // 대상 카탈로그(챕터/스테이지 구조, 방어력 미포함, 정적)
let targetCatalog = loadJSON(CACHE_MONDEF, null);
let qualSet = new Set();               // 현재 직업에서 '딜 미환산'인 스탯 id 집합
const slotCache = {};                  // "부위|등급" -> slots[3][]

function loadJSON(key, def) { try { const v = JSON.parse(localStorage.getItem(key)); return v == null ? def : v; } catch { return def; } }
function saveJSON(key, val) { localStorage.setItem(key, JSON.stringify(val)); if (PRESET_KEYS.has(key)) syncUp(); }
function clone(o) { return JSON.parse(JSON.stringify(o)); }

// ── 계정/서버 동기화 상태 ──
const PRESET_KEYS = new Set([STATP_KEY, CUBEP_KEY, BUFFP_KEY, ALLYP_KEY, RELICP_KEY, BUILDP_KEY, CONV_KEY, RANGE_KEY]);
// 계정 격리: 현재 localStorage 프리셋의 '소유 계정'(이메일 or "anon"). 계정이 바뀌면 이전 계정 데이터를 지운다(교차 오염 방지).
const PRESET_OWNER_KEY = "mekicalc.presets.owner.v1";
// 계정 전환/로그아웃 시 지울 유저 데이터 키(정적 캐시·패치본기록은 제외).
const USER_DATA_KEYS = [STATP_KEY, CUBEP_KEY, BUFFP_KEY, ALLYP_KEY, RELICP_KEY, RELIC_KEY, BUILDP_KEY, CONV_KEY, RANGE_KEY, CUBE_KEY, CACHE_DMG, CACHE_SKILLS];
function clearUserData() { USER_DATA_KEYS.forEach((k) => localStorage.removeItem(k)); }
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

// 큰 수의 한글 단위 환산(경/조/억/만). 유효숫자 4자리 + 소수점 최소 1자리(예 418.9조·7.754조). 1만 미만은 빈 문자열.
const KOR_UNITS = [[1e16, "경"], [1e12, "조"], [1e8, "억"], [1e4, "만"]];
function koreanUnit(v) {
  const a = Math.abs(v);
  if (v == null || !isFinite(a) || a < 1e4) return "";
  for (const [val, name] of KOR_UNITS) if (a >= val) return korMantissa(v / val) + name;
  return "";
}
// 만티사(단위당 1~9999.x)를 유효숫자 4자리로, 소수점은 무조건 1자리 이상 표시(불필요한 끝 0만 제거).
function korMantissa(v) {
  const a = Math.abs(v);
  if (a === 0) return "0.0";
  const intDigits = Math.floor(Math.log10(a)) + 1;   // 정수부 자릿수(1~4)
  let s = v.toFixed(Math.max(1, 4 - intDigits));      // 4유효숫자, 최소 소수 1자리
  if (s.indexOf(".") >= 0) { s = s.replace(/0+$/, ""); if (s.endsWith(".")) s += "0"; }   // 끝 0 제거하되 .0은 유지
  return s;
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
  document.body.classList.toggle("job-nightlord", job === 1);   // 나이트로드=신엔진 → 구엔진 '데미지 분석(구)' 탭 숨김 + 스킬 프리셋 숨김(빌드 타임라인에서 스킬 배치)
  if (job === 1) {   // 나이트로드는 스킬 프리셋 미사용 → 스탯 뷰로 고정(스탯/스킬 토글은 CSS로 숨김)
    document.querySelectorAll(".bv-tab").forEach((b) => b.classList.toggle("active", b.dataset.bv === "stat"));
    const bk = $("bv-skill"); if (bk) bk.hidden = true;
    const bs = $("bv-stat"); if (bs) bs.hidden = false;
  }
  try { localStorage.setItem("mekicalc.job", String(job)); } catch (e) {}   // 다음 로드 첫 페인트 진입탭 결정용(head 스크립트가 읽음)
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
function setCubeDirty(v) { cubeDirty = v; const b = $("cp-save"); if (b) b.disabled = !v; }
function saveCube() { localStorage.setItem(CUBE_KEY, JSON.stringify(myCube)); setCubeDirty(true); }

// 활성 큐브 프리셋으로 작업본을 되돌림(미저장 변경 폐기).
function revertCube() {
  myCube = clone((cubePresets && cubePresets.presets[cubePresets.active]) || {});
  localStorage.setItem(CUBE_KEY, JSON.stringify(myCube));
  setCubeDirty(false);
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
// sp-save(캐릭터 프리셋 저장) 버튼 활성/비활성 동기화 — 변경 없으면 비활성.
function setStatDirty(v) { statDirty = v; const b = $("sp-save"); if (b) b.disabled = !v; }
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
function revertAlly() { apApply(allyPresets.active); }   // 저장된 동료 프리셋으로 복원(apApply가 dirty 해제)
function canLeaveAlly() {
  if (!allyDirty) return true;
  if (confirm("저장하지 않은 동료 변경이 있습니다.\n저장하지 않고 이동하면 변경이 되돌아갑니다. 계속할까요?")) { revertAlly(); return true; }
  return false;
}
function revertRelic() { rpApply(relicPresets.active); }   // 저장된 유물 프리셋으로 복원(rpApply가 dirty 해제)
function canLeaveRelic() {
  if (!relicDirty) return true;
  if (confirm("저장하지 않은 유물 변경이 있습니다.\n저장하지 않고 이동하면 변경이 되돌아갑니다. 계속할까요?")) { revertRelic(); return true; }
  return false;
}
function canLeaveEditors() {
  if (!$("panel-timeline").hidden && !canLeaveBuild()) return false;   // 타임라인(빌드) 미저장 가드
  if ($("panel-presets").hidden) return true;
  if (currentSub === "build") return canLeaveStat();   // 빌드=스탯+스킬(statDirty가 둘 다 커버)
  if (currentSub === "cube") return canLeaveCube();
  if (currentSub === "ally") return canLeaveAlly();
  if (currentSub === "relic") return canLeaveRelic();
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
  { label: "데미지 분석(구)", panel: "home", path: "/" },   // 최좌측 · 구엔진 대시보드. 나이트로드(신엔진)면 숨김(body.job-nightlord). 추후 관리자 전용화 예정
  { label: "빌드", panel: "timeline", path: "/build" },   // 나이트로드 정밀 타임라인 시뮬(빌드=배치+프리셋 로드아웃). panel은 timeline 유지, 경로만 /build
  { label: "프리셋", flat: true, divider: true, items: [   // flat: 드롭다운X·클릭 시 캐릭터 탭 진입(서브바가 세부전환). divider: 뒤에 세로 구분선
    { label: "캐릭터", panel: "presets", sub: "build", path: "/presets/build" },
    { label: "동료", panel: "presets", sub: "ally", path: "/presets/ally" },
    { label: "유물", panel: "presets", sub: "relic", path: "/presets/relic" },
    { label: "큐브", panel: "presets", sub: "cube", path: "/presets/cube" },
  ]},
  { label: "큐브", flat: true, items: [   // flat: 드롭다운X·클릭 시 큐브 기댓값 진입(세부전환=각 큐브 패널 상단 서브바)
    { label: "큐브 기댓값", panel: "cube", path: "/cube" },
    { label: "옵션 비교", panel: "optcompare", path: "/cube/option-compare" },
    { label: "내 옵션 변경", panel: "compare", path: "/cube/my-options" },
    { label: "우선순위 추천", panel: "recommend", path: "/cube/recommend" },
  ]},
  { label: "스탯 비교", panel: "statcompare", path: "/stat-compare" },   // 최상위 단독 · 스탯 등가 환산(한 스탯↔다른 스탯 필요량)
  { label: "장비 비교", panel: "gear", path: "/gear/compare" },   // 최상위 단독 · 프리셋 없이 단발 비교/변경
  { label: "공지사항", panel: "notices", path: "/notices" },      // 계산 아닌 첫 기능 · 넥슨 포럼 공지 크롤링·요약
  { label: "업데이트 내역", panel: "updates", path: "/updates" },  // MekiCalc 자체 패치노트(public/patchnotes.json)
];
const MENU_RIGHT = [];   // (비움)
// 설정: 메뉴바 항목이 아니라 상단 톱니(데스크톱=로그인 우측, 모바일=드로어 첫 줄)로 진입하는 독립 패널.
const SETTINGS_LEAF = { label: "설정", panel: "settings", path: "/settings" };

// 캐릭터 스탯이 있어야 계산되는 탭 — 미입력이면 블러+CTA 게이트(정상 렌더 스킵).
// home(데미지 분석 구)·timeline(빌드)도 포함 — 스탯 미입력 신규/비로그인 유저에게 동일한 캐릭터 입력 CTA 노출.
const CHAR_GATED_PANELS = new Set(["home", "timeline", "cube", "optcompare", "compare", "recommend", "statcompare", "gear"]);

let tlExpApply = false;   // 실험값 전체 적용(실험실 토글). buildMenu가 상단 실행에서 참조 → showPanel 위에 선언(TDZ 방지).

function showPanel(name) {
  for (const p of document.querySelectorAll(".panel")) p.hidden = (p.id !== "panel-" + name);
  updateExpTags();   // 패널 전환 시 '실험실' 태그 반영(실험값 적용 중일 때)
  document.body.classList.toggle("notices-active", name === "notices");   // 공지 탭은 비영리 → 광고 비노출(CSS)
  if (CHAR_GATED_PANELS.has(name)) {   // 캐릭터 미입력 → 게이트. 내 옵션 비교·우선순위 추천은 큐브 프리셋도 필요.
    const reqs = [{ empty: characterEmpty(), msg: "계산하려면 캐릭터 스탯이 필요해요.<br>스탯을 입력하면 이 기능을 쓸 수 있어요.", cta: "캐릭터 입력하러 가기", path: "/presets/build" }];
    if (name === "compare" || name === "recommend") reqs.push({ empty: cubeEmpty(), msg: "큐브 프리셋이 아직 없어요.<br>큐브를 입력하면 이 기능을 쓸 수 있어요.", cta: "큐브 입력하러 가기", path: "/presets/cube" });
    if (applyGate($("panel-" + name), reqs)) return;
  }
  if (name === "home") renderHome();               // 홈 대시보드(로고로 진입)
  if (name === "timeline") renderTimeline();       // 나이트로드 정밀 타임라인(베타)
  if (name === "compare") renderCompareEditor(true);
  if (name === "optcompare") renderOptCompare();
  if (name === "recommend") runRecommend(true);   // 진입 시 자동 계산 → 슬라이더 활성화
  if (name === "cube") calcEvaluate();             // 진입 시 자동 계산(계산 버튼 제거)
  if (name === "statcompare") renderStatCompare();  // 스탯 비교(등가 환산) UI 구성
  if (name === "gear") renderGear();               // 장비 비교 UI 구성
  if (name === "notices") renderNotices();         // 공지 탭(넥슨 포럼 크롤링·요약)
  if (name === "updates") renderUpdates();         // 업데이트 내역(패치노트)
  if (name === "settings") renderLoginUI();        // 설정(계정 카드·내보내기/불러오기·환산·범위) — 계정 카드 갱신
}

// ──────────────────── 라우팅(SPA) ────────────────────
// 각 탭에 진짜 URL 부여: 크롤러가 발견·색인 가능하고, 뒤로가기·북마크·공유도 된다.
// 메뉴 UX는 그대로 두되(버튼), 클릭을 navigate로 태워 주소를 갱신하고, 직접 URL 진입은 서버 SPA 폴백 + 초기 라우트가 처리.
let routeItemEls = new Map();   // path → 데스크톱 최상위 .menu-item(활성 표시용)

function allLeaves() {
  const out = [];
  for (const m of MENU) { if (m.items) for (const it of m.items) out.push(it); else out.push(m); }
  out.push(SETTINGS_LEAF);   // 메뉴엔 없지만 /settings 라우팅은 인식돼야 함
  return out;
}
function leafByPath(path) { return allLeaves().find((l) => l.path === path) || null; }

// 구 경로 호환: 스탯/스킬 프리셋이 '캐릭터'(빌드)로 통합됨 → 옛 북마크·링크를 새 경로로 정규화.
const PATH_REDIRECTS = { "/presets/stat": "/presets/build", "/presets/skill": "/presets/build", "/presets/settings": "/settings", "/timeline": "/build" };
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
  "/settings":            ["설정 - MekiCalc", "프리셋·빌드 내보내기/불러오기, 딜 환산율, 추천 옵션 범위 설정."],
};
function applyRouteMeta(path) {
  const m = ROUTE_META[path];
  document.title = m ? m[0] : "MekiCalc - 메이플 키우기 스탯 효율 계산기";
  const desc = document.querySelector('meta[name="description"]');
  if (desc && m) desc.setAttribute("content", m[1]);
  setRobots((path || "").startsWith("/presets") || path === "/settings");   // 프리셋·설정(개인 데이터) 화면은 색인 제외
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
  else document.querySelectorAll(".menu-item.active").forEach((i) => i.classList.remove("active"));   // 설정 등 메뉴 밖 진입
  syncMobileActive(leaf);
  showPanel(leaf.panel);
  if (leaf.sub) selectSub(leaf.sub);
  applyRouteMeta(leaf.path);
  if (push && leaf.path) history.pushState({ p: leaf.path }, "", leaf.path);
  return true;
}
// 주소(path)로 이동. push=false는 초기 진입·뒤로가기(주소 안 바꿈).
// 기본 진입/로고 목적지 — 나이트로드(신엔진)는 타임라인, 그 외는 데미지 분석(구).
// buildMenu는 스탯 로드 전 실행이라 currentJob()=0일 수 있음 → 그땐 캐시된 직업으로 판단(첫 페인트 깜빡임 방지).
function homeLeaf() {
  let job = currentJob();
  if (!job) { try { job = parseInt(localStorage.getItem("mekicalc.job"), 10) || 0; } catch (e) {} }
  return job === 1 ? (allLeaves().find((l) => l.panel === "timeline") || MENU[0]) : MENU[0];
}

function navigate(path, push) {
  let leaf = leafByPath(normPath(path)) || homeLeaf();   // 구 경로면 정규화(캐릭터 탭으로)
  if (leaf === MENU[0]) leaf = homeLeaf();   // 홈(데미지 분석) 목적지는 나이트로드면 빌드로
  applyLeaf(leaf, null, push);
  if (leaf.path && location.pathname !== leaf.path) history.replaceState({ p: leaf.path }, "", leaf.path);   // 리다이렉트(구경로 /timeline·홈→빌드) 시 주소 정정
}

// 홈(=진입 탭)으로 이동. 로고 클릭·기본 진입 공용. 나이트로드면 타임라인.
function goHome() {
  if (!applyLeaf(homeLeaf(), null, true)) return;
  window.scrollTo({ top: 0 });
}

// 실험값 적용 토글(타임라인 헤더 + 실험실 박스가 공유). 켜면 모든 엔진 계산이 실험값 기준으로 재계산.
function setExpApply(on) {
  tlExpApply = !!on;
  syncExpToggles();
  setBuildDirty(true);
  updateExpTags();       // 각 계산 박스 '실험실' 태그 표시/숨김
  recalcActivePanel();   // 현재 보이는 탭 즉시 재계산(타임라인 포함)
}
function syncExpToggles() {
  for (const id of ["tl-exp-apply", "lab-exp-apply"]) { const el = $(id); if (el) el.checked = tlExpApply; }
}

// 실험값 적용 중이면 각 계산 패널 상단에 '실험실' 태그(토글 ON 색)를 표시. 결과 innerHTML과 독립(패널 직속).
// 타임라인·실험실은 이미 토글 스위치가 있어 제외. 나머지 계산 탭의 메인 카드(핵심 큰 박스)에만 태그.
const EXP_TAG_PANELS = ["cube", "optcompare", "compare", "recommend", "statcompare", "gear"];
function updateExpTags() {
  const on = expActive();
  for (const name of EXP_TAG_PANELS) {
    const panel = $("panel-" + name);
    if (!panel) continue;
    const host = panel.querySelector(":scope > .card") || panel;   // 탭 메인 카드(핵심 큰 박스)
    let tag = host.querySelector(":scope > .exp-tag");
    if (on && !tag) {
      host.classList.add("exp-host");   // position:relative — 배지를 우측 상단에 얹기 위함
      tag = document.createElement("div");
      tag.className = "exp-tag";
      tag.textContent = "실험실";
      host.appendChild(tag);
    } else if (!on && tag) { tag.remove(); host.classList.remove("exp-host"); }
  }
}

// 현재 보이는 패널의 계산을 다시 돌린다(프리셋 전환·실험값 토글 시 즉시 반영용). 예상 데미지 카드는 항상 갱신.
function recalcActivePanel() {
  calcDamage();
  if (currentSub === "ally") calcDamage();   // 동료 탭: 스탯/프리셋 바뀌면 홈 예상 데미지 갱신
  if (!$("panel-cube").hidden) calcEvaluate();
  else if (!$("panel-recommend").hidden) runRecommend(true);
  else if (!$("panel-compare").hidden) refreshCompareOnPresetChange();
  else if (!$("panel-optcompare").hidden) runOptCompare();
  else if (!$("panel-gear").hidden && $("gc-delta").innerHTML.trim()) runGearCompare();   // 결과가 떠 있을 때만 재계산
  else if (!$("panel-statcompare").hidden && scSource) runStatCompare();   // 기준 스탯이 있으면 새 스탯으로 재환산
  else if (!$("panel-timeline").hidden) runTimeline();   // 타임라인 탭
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
  if (name === "build") refreshStatGuideHint();       // 첫 입력이면 스탯 입력 가이드 배너 노출
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
    if (m.panel === "home") el.classList.add("menu-home");   // 나이트로드(신엔진)면 CSS로 숨김
    if (m.items) for (const it of m.items) routeItemEls.set(it.path, el);
    else if (m.path) routeItemEls.set(m.path, el);
    if (m.divider) { const d = document.createElement("span"); d.className = "menu-divider"; d.setAttribute("aria-hidden", "true"); left.appendChild(d); }
  }
  for (const m of MENU_RIGHT) right.appendChild(makeMenuItem(m));
  bar.appendChild(left);
  bar.appendChild(right);
  bar.addEventListener("mouseleave", closeMenus);   // 메뉴바 벗어나면 드롭다운 닫기
  buildMobileMenu(itemEls);   // 모바일 햄버거 메뉴(좁은 화면용)
  const gear = $("header-settings");   // 데스크톱 상단 톱니(로그인 우측) → 설정 패널
  if (gear && !gear._wired) { gear._wired = true; gear.addEventListener("click", () => selectLeaf(SETTINGS_LEAF, null)); }
  const labExp = $("lab-exp-apply");   // 실험실 박스 '실험값 적용' 토글(타임라인 헤더 토글과 공유)
  if (labExp && !labExp._wired) { labExp._wired = true; labExp.checked = tlExpApply; labExp.addEventListener("change", () => setExpApply(labExp.checked)); }
  // 프리셋 서브탭은 미리 빌드로 세팅.
  selectSub("build");
  // 활성 메뉴는 '현재 경로' 기준으로 처음부터 설정. 무조건 첫 항목(예상 데미지)을 활성화하면
  // 이후 라우팅이 공지사항 등으로 바꿔 '예상 데미지 → 공지사항' 메뉴 깜빡임이 생김(딥링크 진입).
  const menuLeaf = leafByPath(location.pathname) || (location.pathname.startsWith("/notices") ? leafByPath("/notices") : null);
  const activeItem = (menuLeaf && routeItemEls.get(menuLeaf.path)) || left.querySelector(".menu-item");
  if (activeItem) setActiveMenu(activeItem);
  syncMobileActive(menuLeaf || MENU[0]);   // 모바일 메뉴 활성도 현재 경로 기준(깜빡임 방지)
  // 홈 경로면 첫 페인트에 진입 패널만 노출(렌더·라우팅·메뉴활성은 데이터 로드 후 init의 navigate가 수행).
  // 나이트로드=타임라인. 여기서 showPanel/applyLeaf로 렌더까지 하면 데이터 미로드 상태라 에러 → 가시성만 잡는다.
  if (location.pathname === "/" || location.pathname === "") {
    const hp = homeLeaf().panel;
    for (const p of document.querySelectorAll(".panel")) p.hidden = (p.id !== "panel-" + hp);
  }
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

  // 드로어 맨 위 첫 줄: 계정(로그인/로그아웃, 좌) + 설정(우). renderHeaderAccount가 m-account 내용을 채움.
  const topRow = document.createElement("div");
  topRow.className = "m-top-row";
  const acct = document.createElement("div");
  acct.id = "m-account"; acct.className = "m-account";
  const mset = document.createElement("button");
  mset.className = "m-settings"; mset.type = "button"; mset.textContent = "⚙️ 설정";
  topRow.appendChild(acct); topRow.appendChild(mset);
  panel.appendChild(topRow);

  const closeDrawer = () => { panel.classList.remove("open"); burger.classList.remove("active"); };
  mset.addEventListener("click", (e) => { e.stopPropagation(); closeDrawer(); selectLeaf(SETTINGS_LEAF, null); });

  const addLeaf = (label, leaf, desktopItem, isTop) => {
    const btn = document.createElement("button");
    btn.className = "m-leaf" + (isTop ? " m-leaf-top" : "") + (leaf && leaf.panel === "home" ? " menu-home" : "");   // isTop=최상위 단독(프리셋)=대분류 크기. home=나이트로드면 숨김

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

// i 버튼(ⓘ) + 툴팁 요소 생성. 호버로 뜨고(팝업 위로 옮겨도 유지), 클릭하면 고정(.pin) — 마우스를 떼도 유지.
// 고정 상태에서 버튼 재클릭 또는 바깥 클릭 시 해제. 팝업 내부 클릭(링크 등)은 유지.
function mkInfo(label, bodyEl, small) {
  const wrap = document.createElement("span");
  wrap.className = "info-help " + (small ? "info-inline" : "info-block");
  const btn = document.createElement("button");
  btn.type = "button"; btn.className = "info-btn" + (small ? " info-btn-sm" : "");
  btn.textContent = label;
  if (small) btn.setAttribute("aria-label", "설명");
  const pop = document.createElement("span");
  pop.className = "info-pop";
  pop.appendChild(bodyEl);
  // 위치 보정은 공용 positionPop에 위임(호버·고정 표시 중 스크롤/리사이즈에도 버튼에 재정렬 — 아래 전역 리스너).
  const fitPop = () => positionPop(wrap);
  wrap.addEventListener("mouseenter", () => requestAnimationFrame(fitPop));   // 호버 표시(데스크톱)
  btn.addEventListener("click", (e) => {
    e.preventDefault(); e.stopPropagation();
    const on = wrap.classList.contains("pin");
    document.querySelectorAll(".info-help.pin").forEach((w) => { if (w !== wrap) w.classList.remove("pin"); });   // 한 번에 하나만 고정
    wrap.classList.toggle("pin", !on);
    if (!on) requestAnimationFrame(fitPop);   // 고정(모바일 탭)으로 열 때 위치 보정
  });
  pop.addEventListener("click", (e) => e.stopPropagation());   // 팝업 내부 클릭은 고정 유지(바깥 클릭 닫힘에서 제외)
  wrap.appendChild(btn); wrap.appendChild(pop);
  return wrap;
}
// 바깥 클릭 시 고정된 툴팁 해제.
document.addEventListener("click", () => { document.querySelectorAll(".info-help.pin").forEach((w) => w.classList.remove("pin")); });
// 팝업(position:fixed)을 버튼 기준으로 배치 + 좌우 뷰포트 clamp(가로 스크롤 방지).
function positionPop(wrap) {
  const btn = wrap.querySelector(".info-btn"), pop = wrap.querySelector(".info-pop");
  if (!btn || !pop) return;
  const bt = btn.getBoundingClientRect(), m = 8, vw = document.documentElement.clientWidth;
  pop.style.top = (bt.bottom + 6) + "px";
  // 모바일(≤640)에선 최상단 도움말(.info-block) 팝업을 좌우 여백 m만 두고 꽉 채움(원래 모바일 풀폭 복원). max-width 캡 해제 필요.
  if (wrap.classList.contains("info-block") && vw <= 640) {
    pop.style.maxWidth = "none";
    pop.style.width = (vw - 2 * m) + "px";
    pop.style.left = m + "px";
    return;
  }
  pop.style.maxWidth = ""; pop.style.width = "";   // 데스크톱/인라인: CSS 기본 복귀(max-content, max-width min(92vw,440px))
  pop.style.left = Math.max(m, Math.min(bt.left, vw - m - pop.offsetWidth)) + "px";   // 버튼 왼쪽 기준, 뷰포트 안으로 clamp
}
// fixed 팝업은 스크롤을 안 따라가므로, 표시 중(고정 .pin 또는 호버)엔 스크롤/리사이즈마다 버튼에 재정렬(버튼이 화면 밖이면 자연히 사라짐).
function repositionInfoPops() {
  document.querySelectorAll(".info-help").forEach((w) => {
    if (w.classList.contains("pin") || w.matches(":hover")) positionPop(w);
  });
}
window.addEventListener("scroll", repositionInfoPops, true);   // capture: 중첩 스크롤 컨테이너 스크롤도 포착
window.addEventListener("resize", repositionInfoPops);
// 설명글(접이식 panel-about + 인라인 힌트·안내 status/hint)을 i 버튼 호버 툴팁으로 접어 화면을 깔끔하게.
// 경고성(.deal-warning·.relic-notice)·동적 상태(#id 있는 status)는 제외.
function toInfoHelp() {
  document.querySelectorAll("details.panel-about").forEach((d) => {
    const sum = d.querySelector("summary");
    const body = d.querySelector(".about-body");
    if (!body) return;
    d.replaceWith(mkInfo("ⓘ " + (sum ? sum.textContent.trim() : "도움말"), body, false));   // 링크 등 본문 유지
  });
  document.querySelectorAll(".tl-ro-hint, .status:not([id]), .hint").forEach((h) => {
    if (!h.textContent.trim()) return;
    const body = document.createElement("span");
    body.className = "info-pop-body";
    body.innerHTML = h.innerHTML;
    h.replaceWith(mkInfo("ⓘ", body, true));
  });
}
toInfoHelp();

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
  setStatDirty(true); deriveStatProp(); refreshQual();
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
      setStatDirty(true); calcDamage(); refreshStatGuideHint();
    });
  }
  applyQualMarkers();
  refreshStatGuideHint();
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

// 캐릭터 프리셋 첫 입력(스탯 미입력) 시 상단 '스탯 입력 가이드' 배너 노출, 값이 채워지면 숨김.
function refreshStatGuideHint() {
  const el = $("stat-guide-hint");
  if (el) el.hidden = !characterEmpty();
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
    // 사용법·FAQ 버튼을 캐릭터 입력 CTA 위에 함께 노출(정적 guide 페이지 → route-link 아님, 기본 이동).
    gate.innerHTML = `<p class="locked-msg">${fail.msg}</p>` +
      `<a class="gate-faq" href="/guide/how-to.html">📖 사용법 · FAQ</a>` +
      `<a class="route-link locked-btn" href="${fail.path}">${fail.cta} →</a>`;
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
      stats: effStats(), conversions: myConv, buffs: activeBuffs(), hitInterval: myHitInterval,
      ...effRelicCtx(), simMs: tlSimMs(),   // 나이트로드=신엔진(유물 조립) 딜 기준
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
  setStatDirty(true);
  calcDamage();
  refreshStatGuideHint();   // 0으로 초기화 → 가이드 배너 다시 노출
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

// ★compute-once: 빌드가 바뀔 때만 /api/recommend 1회 호출(무거운 딜 계산). 응답의 recsBySlider(모든 슬라이더 위치×전체 추천)를
// 캐시해두고, 슬라이더·정렬·윗/아랫 필터는 renderCurrentRecs로 재fetch 없이 즉시 처리한다.
let rcData = null;   // { maxDivisions, recsBySlider: [[recJson,...] (슬라이더0), ...] }

async function runRecommend(resetSlider) {
  if (!Object.keys(myCube).length) { $("rc-status").textContent = "먼저 '내 큐브' 탭에서 큐브 옵션을 입력하세요."; $("rc-list").innerHTML = ""; rcData = null; return; }
  const done = deferLoading("rc-status");
  try {
    const data = await postJson("/api/recommend", {
      stats: effStats(), conversions: myConv, ...targetForApi(), ...companionParams(),
      cubeState: myCube, ranges: myRanges, buffs: activeBuffs(), hitInterval: myHitInterval,
      ...effRelicCtx(), simMs: tlSimMs(),   // 나이트로드=신엔진(유물 조립) 추천. 실험실과 동일 sim 길이(순환/파생 유물 sim 의존)
    });
    rcData = { maxDivisions: data.maxDivisions || 1, recsBySlider: data.recsBySlider || [] };
    const slider = $("rc-slider");
    slider.max = Math.max(0, rcData.maxDivisions - 1);
    $("rc-slider-max").textContent = slider.max;
    if (resetSlider || (parseInt(slider.value, 10) || 0) > slider.max) { slider.value = 0; $("rc-slider-val").textContent = "0"; }
    renderCurrentRecs();
  } catch (e) { $("rc-status").textContent = "오류: " + e.message; }
  finally { done(); }
}

// 슬라이더·정렬·윗/아랫 변경 시 재fetch 없이 캐시(recsBySlider)에서 필터·정렬·렌더. sortKey 정의는 서버 sortValue와 동일.
function renderCurrentRecs() {
  if (!rcData) { $("rc-list").innerHTML = ""; return; }
  const s = Math.min(rcData.recsBySlider.length - 1, Math.max(0, parseInt($("rc-slider").value, 10) || 0));
  let recs = (rcData.recsBySlider[s] || []).slice();
  const up = $("rc-upper").checked, lo = $("rc-lower").checked;
  recs = recs.filter((r) => (r.upper && up) || (!r.upper && lo));
  const sk = parseInt($("rc-sort").value, 10) || 0;
  const sv = (r) => sk === 1 ? r.recProbSum / r.cost : sk === 2 ? r.totalProb / r.cost : sk === 3 ? r.gainEff : r.dealDiff / r.cost;
  recs.sort((a, b) => sv(b) - sv(a));
  renderRecs(recs);
  $("rc-status").textContent = recs.length ? "" : "추천할 옵션이 없습니다.";
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
      `<span title="딜 증가량 × 등장 확률 ÷ 큐브 가격 — 큐브 소모 대비 딜 이득(가성비)">가성비 <b>${fmtScore(r.gainEff * 1e6)}</b></span>` +
      `</div>${slotCompare(rows)}`;
    list.appendChild(card);
  });
}

// ★슬라이더·정렬·윗/아랫 = 이미 계산된 recsBySlider에서 즉시 재렌더(재계산·재fetch 없음).
$("rc-sort").addEventListener("change", renderCurrentRecs);
$("rc-upper").addEventListener("change", renderCurrentRecs);
$("rc-lower").addEventListener("change", renderCurrentRecs);
$("rc-slider").addEventListener("input", () => { $("rc-slider-val").textContent = $("rc-slider").value; renderCurrentRecs(); });

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
      stats: effStats(), conversions: myConv, ...targetForApi(), ...companionParams(), buffs: activeBuffs(), hitInterval: myHitInterval,
      ...effRelicCtx(), simMs: tlSimMs(),   // 나이트로드=신엔진(유물 조립) 딜 기준
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
  const base = (statDefs || []).filter((d) => !d.hidden && d.id !== "LEVEL" && d.id !== "BASIC_ATK_TARGETS" && d.id !== "JOB");
  return [...base, { id: "ATK_PCT_SKILL", name: EXP_SYNTH.ATK_PCT_SKILL, unit: "PCT" }];   // 합성: 스킬/유물 유래 공격력%(프리셋 공%와 별도 곱)
}

// 스탯 비교 전용 소분류(종류별로 묶어 한눈에 보이게). stats.json 순서는 캐릭터 폼이 '스탯창 순서'로 쓰므로 건드리지 않고 여기서만 그룹핑.
const SC_GROUPS = [
  { title: "+ 스탯", ids: ["ATK_FLAT", "MAIN_STAT_FLAT", "SUB_STAT_FLAT", "DEFENSE"] },
  { title: "% 스탯", ids: ["ATK_PCT", "ATK_PCT_SKILL", "MAIN_STAT_PCT", "STAT_PROP_DMG", "CRIT_RATE", "CRIT_DMG", "DMG", "DMG_AMP", "BASIC_ATK_DMG", "SKILL_DMG", "BOSS_DMG", "NORMAL_DMG", "MIN_DMG_MULT", "MAX_DMG_MULT"] },
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
      stats: effStats(), conversions: myConv, ...targetForApi(), ...companionParams(),
      buffs: activeBuffs(), hitInterval: myHitInterval,
      refStatId: src.statId, refValue: src.value,
      ...effRelicCtx(), simMs: tlSimMs(),   // 나이트로드=신엔진(유물 조립) 딜 기준
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
        stats: effStats(), conversions: myConv, buffs: activeBuffs(), hitInterval: myHitInterval,
        simMs: tlSimMs() },   // 나이트로드=신엔진(유물 조립) 딜 기준, 실험실과 동일 sim
      effRelicCtx(), targetForApi(), companionParams()));
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
    setStatDirty(false);
    calcDamage();
    $("gc-status").textContent = "";
    toast("변경 적용 — 스탯 프리셋에 저장됨");
  } catch (e) { $("gc-status").textContent = "오류: " + e.message; }
}

// ──────────────────── 내 옵션 비교 ────────────────────

function statLabel(id) { const d = statDefs.find((x) => x.id === id); return d ? d.name : (EXP_SYNTH[id] || id); }
function statUnit(id) { const d = statDefs.find((x) => x.id === id); return d ? d.unit : (EXP_SYNTH[id] ? "PCT" : ""); }

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
      stats: effStats(), conversions: myConv, ...targetForApi(), ...companionParams(),
      cubeState: myCube, ranges: myRanges, buffs: activeBuffs(), hitInterval: myHitInterval,
      part: $("cmp-part").value, upper: $("cmp-line").value === "upper",
      rightGrade: $("cmp-right-grade").value, rightOpts,
      ...effRelicCtx(), simMs: tlSimMs(),   // 나이트로드=신엔진(유물 조립) 딜 기준
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
  if (!confirm(`${part} ${lineName}의 현재(왼쪽) 옵션을 새(오른쪽) 옵션으로 바꾸고, 저장된 ‘${cubePresets.active}’ 큐브 프리셋과 ‘${statPresets.active}’ 캐릭터 프리셋에 반영·저장할까요?`)) return;

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
    setStatDirty(false);
    saveCube();
    cubePresets.presets[cubePresets.active] = clone(myCube);
    saveJSON(CUBEP_KEY, cubePresets);
    setCubeDirty(false); cubeNeedsRender = true;
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
  refreshStatGuideHint();   // 프리셋 적용 후 미입력/입력 여부 재판정
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
  setStatDirty(false); buffDirty = false;   // 프리셋 적용 = 저장된 상태
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
  setStatDirty(false);
}
$("sp-sel").addEventListener("change", () => {
  if (!canLeaveStat()) { $("sp-sel").value = statPresets.active; return; }   // 미저장이면 취소 시 되돌림
  statPresets.active = $("sp-sel").value; saveJSON(STATP_KEY, statPresets); spApply(statPresets.active);
});
$("sp-save").addEventListener("click", () => { statPresets.presets[statPresets.active] = statPresetPayload(); saveJSON(STATP_KEY, statPresets); setStatDirty(false); buffDirty = false; calcDamage(); toast(`'${statPresets.active}' 캐릭터 프리셋 저장됨`); });
$("sp-new").addEventListener("click", () => {
  const name = (prompt("새 스탯 프리셋 이름") || "").trim();
  if (!name || statPresets.presets[name]) return;
  statPresets.presets[name] = statPresetPayload();
  statPresets.active = name; saveJSON(STATP_KEY, statPresets); fillPresetSel($("sp-sel"), statPresets);
  setStatDirty(false);   // 현재 입력이 새 프리셋으로 저장됨
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
  myCube = clone(c); saveCube(); setCubeDirty(false);
  // 큐브 탭이 보일 때만 즉시 렌더, 아니면 지연(슬롯 조회가 초기 로드를 막지 않게)
  if (currentSub === "cube") await renderCube(); else cubeNeedsRender = true;
  recalcActivePanel();   // 큐브 프리셋 전환 시 보이는 계산(추천·비교 등) 즉시 갱신
}
async function initCubePresets() {
  if (!cubePresets) cubePresets = { active: "기본", presets: { "기본": clone(myCube) } };
  else await cpApply(cubePresets.active);
  saveJSON(CUBEP_KEY, cubePresets);
  fillPresetSel($("cp-sel"), cubePresets);
  setCubeDirty(false);
}
// 페이지를 실제로 떠날 때(새로고침·닫기) 미저장 큐브 변경 경고.
window.addEventListener("beforeunload", (e) => { if (cubeDirty || statDirty || buffDirty) { e.preventDefault(); e.returnValue = ""; } });
$("cp-sel").addEventListener("change", async () => {
  if (!canLeaveCube()) { $("cp-sel").value = cubePresets.active; return; }   // 미저장이면 취소 시 되돌림
  cubePresets.active = $("cp-sel").value; saveJSON(CUBEP_KEY, cubePresets); await cpApply(cubePresets.active);
});
$("cp-save").addEventListener("click", () => { cubePresets.presets[cubePresets.active] = clone(myCube); saveJSON(CUBEP_KEY, cubePresets); setCubeDirty(false); toast(`'${cubePresets.active}' 큐브 프리셋 저장됨`); });
$("cp-new").addEventListener("click", () => {
  const name = (prompt("새 큐브 프리셋 이름") || "").trim();
  if (!name || cubePresets.presets[name]) return;
  cubePresets.presets[name] = clone(myCube);
  cubePresets.active = name; saveJSON(CUBEP_KEY, cubePresets); fillPresetSel($("cp-sel"), cubePresets);
  setCubeDirty(false);   // 현재 작업본이 새 프리셋으로 저장됨
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

// DPS 모델(스킬 데이터)이 있는 직업 = 나이트로드(1)·다크나이트(2)·비숍(3)·히어로(4)·보우마스터(5). 직업 추가 시 여기만 늘리면 됨.
function jobHasSkills(job) { return job === 1 || job === 2 || job === 3 || job === 4 || job === 5; }

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
  buffDirty = true; setStatDirty(true);   // 스킬도 빌드 프리셋의 일부 → 빌드 dirty
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

// 공격력 계승비율(%) 자동 산출: 등급별 레벨별 표(inheritByLevel) + 플레이어 compareLevel 보정.
// 동료 레벨을 입력하면 계승비율이 자동으로 따라간다(수동입력 폐지). 클라 추출·검증: 섀도어 레전 L4=72%.
function inheritRatioAuto(grade, level, playerLevel) {
  const arr = ((companionMeta && companionMeta.inheritByLevel) || {})[grade];
  if (!arr || !arr.length) return 0;
  const cap = arr.length;
  const lv = Math.max(1, Math.min(parseInt(level, 10) || 1, cap));
  let r = arr[lv - 1];
  const cl = ((companionMeta && companionMeta.compareLevel) || {})[grade];   // 플레이어가 미달이면 감산(EPIC/UNIQUE/LEGENDARY)
  const pl = parseInt(playerLevel, 10) || 0;
  if (cl && pl > 0 && pl < cl) r = (r * pl) / cl;
  return Math.round(r * 100) / 100;
}
// 현재 캐릭터 레벨(계승 compareLevel 보정용).
function currentCharLevel() { return parseInt((collectStats() || {}).LEVEL, 10) || 0; }
// 동료 등급 만렙(gradeLevelCap) — 레벨 입력 상한.
function companionLevelCap(grade) { return ((companionMeta && companionMeta.gradeLevelCap) || {})[grade] || 0; }
// 동료 레벨 입력칸 상한·클램프 갱신.
function updateAllyLevelCap() {
  const el = $("al-level"); if (!el) return;
  const cap = companionLevelCap($("al-grade").value);
  if (cap > 0) { el.max = cap; if ((parseInt(el.value, 10) || 1) > cap) el.value = cap; }
}
// 계승비율 입력칸(읽기전용)에 자동값 표시.
function updateAllyRatio() {
  const el = $("al-ratio"); if (!el) return;
  el.value = inheritRatioAuto($("al-grade").value, $("al-level").value, currentCharLevel());
}

function initAllyPresets() {
  if (!allyPresets) allyPresets = { active: "기본", presets: { "기본": {} } };
  saveJSON(ALLYP_KEY, allyPresets);
  fillPresetSel($("ap-sel"), allyPresets);
  setAllyDirty(false);
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
    '<label class="ctl">공격력 계승 비율(%)<input id="al-ratio" type="number" readonly tabindex="-1" title="동료 등급·레벨로 자동 계산됩니다"></label>' +
    '</div>';
  const csel = $("al-companion");
  for (const c of companions) csel.add(new Option(c.name || c.id, c.id));
  csel.value = p.companionId || companions[0].id;
  fillAllyGrades(csel.value, p.grade);
  $("al-level").value = p.level != null ? p.level : 1;
  updateAllyLevelCap();
  updateAllyRatio();   // 계승비율은 등급·레벨로 자동(읽기전용)
  // 동료 딜은 홈 예상 데미지에서 확인. 여기선 입력만 받고, 바뀌면 홈을 갱신.
  csel.addEventListener("change", () => { fillAllyGrades(csel.value, null); updateAllyLevelCap(); updateAllyRatio(); setAllyDirty(true); calcDamage(); });
  $("al-grade").addEventListener("change", () => { updateAllyLevelCap(); updateAllyRatio(); setAllyDirty(true); calcDamage(); });
  $("al-level").addEventListener("input", () => { updateAllyRatio(); setAllyDirty(true); calcDamage(); });
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
  const grade = $("al-grade").value;
  const level = parseInt($("al-level").value, 10) || 1;
  return {
    companionId: $("al-companion").value,
    grade: grade,
    level: level,
    ratio: inheritRatioAuto(grade, level, currentCharLevel()),   // 등급·레벨로 자동
  };
}

// 예상 데미지에 합산할 활성 동료 = 동료 탭에 떠 있으면 라이브 입력, 아니면 저장된 활성 프리셋. 미선택이면 {}.
function activeCompanionSel() {
  const live = (currentSub === "ally") ? collectAlly() : null;
  const p = (live && live.companionId) ? live : (allyPresets && allyPresets.presets[allyPresets.active]) || {};
  if (!p.companionId) return {};
  const level = p.level != null ? p.level : 1;
  // 계승비율은 항상 등급·레벨로 자동 산출(저장된 옛 수동 ratio는 무시 → 레벨이 단일 소스).
  return { companionId: p.companionId, grade: p.grade, level: level, ratio: inheritRatioAuto(p.grade, level, currentCharLevel()) };
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
  setAllyDirty(false);
  if (currentSub === "ally") renderAlly();
  recalcActivePanel();   // 동료 프리셋 바뀌면 홈 예상 데미지·보이는 계산 즉시 갱신(예전엔 새로고침해야 반영됐음)
}

$("ap-sel").addEventListener("change", () => {
  if (!canLeaveAlly()) { $("ap-sel").value = allyPresets.active; return; }
  allyPresets.active = $("ap-sel").value; saveJSON(ALLYP_KEY, allyPresets); apApply(allyPresets.active);
});
$("ap-save").addEventListener("click", () => {
  const data = collectAlly();
  allyPresets.presets[allyPresets.active] = data;
  saveJSON(ALLYP_KEY, allyPresets); setAllyDirty(false);
  toast(`'${allyPresets.active}' 동료 프리셋 저장됨`);
});
$("ap-new").addEventListener("click", () => {
  const name = (prompt("새 동료 프리셋 이름") || "").trim();
  if (!name || allyPresets.presets[name]) return;
  allyPresets.presets[name] = collectAlly();
  allyPresets.active = name; saveJSON(ALLYP_KEY, allyPresets); fillPresetSel($("ap-sel"), allyPresets);
  setAllyDirty(false);
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
  if (r && !Array.isArray(r) && typeof r === "object")
    return { awaken: r.awaken || {}, equipped: Array.isArray(r.equipped) ? r.equipped.slice() : [], potentials: r.potentials || {} };
  const out = { awaken: {}, equipped: [], potentials: {} };
  if (Array.isArray(r)) for (const row of r) { if (row && row.id) { out.awaken[row.id] = parseInt(row.awaken, 10) || 0; if (row.on) out.equipped.push(row.id); } }
  return out;
}

// ── 유물 잠재(HiddenForce) ────────────────────────────────
// 참조데이터(artifactCatalog.potential): {slotRule:{등급:{maxSlot,reqAwake}}, grades:[{id,name}], options:[{type,statId,name,deal,values}]}
function potentialData() { return artifactCatalog.potential || null; }
function potentialOptionByType(type) { const p = potentialData(); return p && (p.options || []).find((o) => o.type === type); }
// 유물의 열린 잠재 줄 수 = 등급 slotRule에서 현재 각성도 이상으로 해금된 슬롯 수.
function openPotentialSlots(id) {
  const p = potentialData(); if (!p) return 0;
  const a = (artifactCatalog.list || []).find((x) => x.id === id); if (!a) return 0;
  const rule = (p.slotRule || {})[a.grade]; if (!rule) return 0;
  const aw = relicAwaken(id);
  return (rule.reqAwake || []).filter((req) => aw >= req).length;
}
// 저장된 유물 잠재 배열(열린 슬롯 길이에 맞춤). 각 줄 {type, grade, value?}.
function relicPotentials(id) {
  const n = openPotentialSlots(id);
  const arr = ((myRelic.potentials || {})[id] || []).slice(0, n);
  while (arr.length < n) arr.push({ type: "", grade: "" });
  return arr;
}
function setRelicPotential(id, line, patch) {
  myRelic.potentials = myRelic.potentials || {};
  const arr = relicPotentials(id);
  arr[line] = Object.assign({}, arr[line], patch);
  myRelic.potentials[id] = arr;
}
// 등급이 미스틱이고 값이 배열(롤)이면 그 배열, 아니면 null(단일값=자동).
function potentialRolls(type, grade) {
  const o = potentialOptionByType(type); if (!o) return null;
  const v = (o.values || {})[grade];
  return Array.isArray(v) ? v : null;
}
// 계산 payload용: 장착 유물들의 열린 잠재 줄 평탄화 → [{type, grade, value?}] (옵션종류 미선택 줄 제외).
function activePotentials() {
  const out = [];
  for (const { id } of activeRelic()) {
    for (const ln of relicPotentials(id)) {
      if (!ln.type || !ln.grade) continue;
      const row = { type: ln.type, grade: ln.grade };
      if (ln.value != null) row.value = ln.value;   // 미스틱 롤(천분율)
      out.push(row);
    }
  }
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
function saveRelic() { myRelic.equipped = (myRelic.equipped || []).filter((id) => relicHas(id)); saveJSON(RELIC_KEY, myRelic); setRelicDirty(true); }

// 정상 딜 반영 유물(compute·상시스탯 preReflected)은 뱃지 없음 — 주의가 필요한 예외 상태만 뱃지로 표시.
const RELIC_CAT = { content: ["컨텐츠 전용", "muted"], survival: ["딜 미반영", "muted"] };
// 타임라인 엔진에 아직 미구현인 유물(상태 유물·동료 유물) — category는 compute지만 '지원 예정'으로 표시.
const RELIC_PENDING = new Set(["burning_lava", "old_music_box", "horn"]);
function relicCatById(id) { return ((artifactCatalog.list || []).find((a) => a.id === id) || {}).category; }
function relicBadge(id) {
  if (RELIC_PENDING.has(id)) return `<span class="relic-badge pending">지원 예정</span>`;
  const m = RELIC_CAT[relicCatById(id)];
  return m ? `<span class="relic-badge ${m[1]}">${m[0]}</span>` : "";
}

function initRelicPresets() {
  if (!relicPresets) relicPresets = { active: "기본", presets: { "기본": clone(myRelic) } };
  saveJSON(RELICP_KEY, relicPresets);
  fillPresetSel($("rp-sel"), relicPresets);
  setRelicDirty(false);
}
function rpApply(name) {
  if (!relicPresets || !relicPresets.presets[name]) return;
  myRelic = normalizeRelic(clone(relicPresets.presets[name]));
  saveJSON(RELIC_KEY, myRelic); setRelicDirty(false);
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
  grid.querySelectorAll("select.relic-cell-aw").forEach((sel) => {
    sel.addEventListener("change", () => {
      const id = sel.dataset.id;
      if (sel.value === "") delete myRelic.awaken[id];   // 미보유
      else myRelic.awaken[id] = Math.max(0, Math.min(5, parseInt(sel.value, 10) || 0));   // 0~5각
      saveRelic(); renderRelic(); calcDamage();
    });
  });
  // 잠재 줄: 옵션/등급 변경 시 롤 초기화(재선택), 롤 변경 시 값 저장
  const potChange = (sel, patch) => { setRelicPotential(sel.dataset.id, +sel.dataset.line, patch); saveRelic(); renderRelic(); runTimelineIfActive(); };
  grid.querySelectorAll("select.relic-pot-type").forEach((sel) =>
    sel.addEventListener("change", () => potChange(sel, { type: sel.value, value: null })));
  grid.querySelectorAll("select.relic-pot-grade").forEach((sel) =>
    sel.addEventListener("change", () => {
      const cur = relicPotentials(sel.dataset.id)[+sel.dataset.line] || {};
      const rolls = potentialRolls(cur.type, sel.value);   // 미스틱(롤 배열)이면 최저 롤(흔한 쪽)을 기본값으로(백엔드와 일치)
      potChange(sel, { grade: sel.value, value: rolls ? rolls[0] : null });
    }));
  grid.querySelectorAll("select.relic-pot-roll").forEach((sel) =>
    sel.addEventListener("change", () => potChange(sel, { value: parseFloat(sel.value) })));
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
    (has ? potentialLinesHtml(a.id) : "") +
    `</div>`;
}
// 유물 셀 안 잠재 입력 줄들(열린 슬롯 수만큼). 각 줄=옵션 + 등급 (+미스틱이면 롤 값).
function potentialLinesHtml(id) {
  const p = potentialData(); if (!p) return "";
  const lines = relicPotentials(id);
  if (!lines.length) return "";
  const dealOpts = (p.options || []).filter((o) => o.deal);
  const etcOpts = (p.options || []).filter((o) => !o.deal);   // 생존·상태이상뎀 = 표시만(딜 미반영)
  const rows = lines.map((ln, i) => {
    const optHtml = (o) => `<option value="${o.type}"${o.type === ln.type ? " selected" : ""}>${o.name}</option>`;
    const typeSel = `<select class="relic-pot-type" data-id="${id}" data-line="${i}">` +
      `<option value="">옵션</option>` +
      `<optgroup label="딜">` + dealOpts.map(optHtml).join("") + `</optgroup>` +
      `<optgroup label="기타(딜 미반영)">` + etcOpts.map(optHtml).join("") + `</optgroup>` + `</select>`;
    const gradeSel = `<select class="relic-pot-grade" data-id="${id}" data-line="${i}">` +
      `<option value="">등급</option>` +
      (p.grades || []).map((g) => `<option value="${g.id}"${g.id === ln.grade ? " selected" : ""}>${g.name}</option>`).join("") + `</select>`;
    const rolls = potentialRolls(ln.type, ln.grade);
    const rollSel = rolls ? `<select class="relic-pot-roll" data-id="${id}" data-line="${i}">` +
      rolls.map((v) => `<option value="${v}"${v === ln.value ? " selected" : ""}>${v / 10}%</option>`).join("") + `</select>` : "";
    return `<div class="relic-pot-line">${typeSel}${gradeSel}${rollSel}</div>`;
  }).join("");
  return `<div class="relic-pot">${rows}</div>`;
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
  if (!canLeaveRelic()) { $("rp-sel").value = relicPresets.active; return; }
  relicPresets.active = $("rp-sel").value; saveJSON(RELICP_KEY, relicPresets); rpApply(relicPresets.active);
});
$("rp-save").addEventListener("click", () => {
  relicPresets.presets[relicPresets.active] = clone(myRelic);
  saveJSON(RELICP_KEY, relicPresets); setRelicDirty(false);
  toast(`'${relicPresets.active}' 유물 프리셋 저장됨`);
});
$("rp-new").addEventListener("click", () => {
  const name = (prompt("새 유물 프리셋 이름") || "").trim();
  if (!name || relicPresets.presets[name]) return;
  relicPresets.presets[name] = clone(myRelic);
  relicPresets.active = name; saveJSON(RELICP_KEY, relicPresets); fillPresetSel($("rp-sel"), relicPresets);
  setRelicDirty(false);
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
  { key: "tb", label: "빌드", main: "tb-sel", store: () => buildPresets, saveKey: BUILDP_KEY,
    canLeave: () => canLeaveBuild(), apply: (n) => bpApply(n) },   // 빌드 전환(미저장 가드). 재계산은 핸들러가 담당
  { key: "sp", label: "캐릭터", main: "sp-sel", store: () => statPresets, saveKey: STATP_KEY,
    canLeave: () => canLeaveStat(), apply: (n) => spApply(n) },
  { key: "ap", label: "동료", main: "ap-sel", store: () => allyPresets, saveKey: ALLYP_KEY,
    canLeave: () => canLeaveAlly(), apply: (n) => apApply(n) },
  { key: "cp", label: "큐브", main: "cp-sel", store: () => cubePresets, saveKey: CUBEP_KEY,
    canLeave: () => canLeaveCube(), apply: (n) => cpApply(n) },
  { key: "rp", label: "유물", main: "rp-sel", store: () => relicPresets, saveKey: RELICP_KEY,
    canLeave: () => canLeaveRelic(), apply: (n) => rpApply(n) },
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
      runTimelineIfActive();   // 타임라인 열려 있으면 재계산(빌드 변형 경고·직업 차단 갱신)
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
  return JSON.stringify({ format: BACKUP_FORMAT, version: 1, statPresets, cubePresets, buffPresets, allyPresets, relicPresets, buildPresets, conversions: myConv, ranges: myRanges }, null, 2);
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
  let last = into.active, importedActiveKey = null;
  for (const [name, val] of Object.entries(from.presets)) {
    const key = uniqueName(name, into.presets);
    into.presets[key] = val;
    last = key;
    if (from.active && name === from.active) importedActiveKey = key;   // 번들의 장착(active) 프리셋 → 불러온 뒤에도 그대로 장착
  }
  into.active = importedActiveKey || into.active || last;   // 장착 프리셋을 불러왔으면 그걸, 아니면 기존 장착 유지(없으면 마지막)
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
    if (b.buildPresets) { buildPresets = mergePresetCollection(buildPresets, b.buildPresets); saveJSON(BUILDP_KEY, buildPresets); }
  } else {
    // 덮어쓰기(서버 동기화 등). 섹션이 있을 때만 교체 → 없는 섹션은 현재 유지.
    if (b.statPresets) { statPresets = b.statPresets; saveJSON(STATP_KEY, statPresets); }
    if (b.cubePresets) { cubePresets = b.cubePresets; saveJSON(CUBEP_KEY, cubePresets); }
    if (b.buffPresets) { buffPresets = b.buffPresets; saveJSON(BUFFP_KEY, buffPresets); }
    if (b.allyPresets) { allyPresets = b.allyPresets; saveJSON(ALLYP_KEY, allyPresets); }
    if (b.relicPresets) { relicPresets = b.relicPresets; saveJSON(RELICP_KEY, relicPresets); }
    if (b.buildPresets) { buildPresets = b.buildPresets; saveJSON(BUILDP_KEY, buildPresets); }
    if (b.conversions) { myConv = b.conversions; saveJSON(CONV_KEY, myConv); }
    if (b.ranges) { myRanges = b.ranges; saveJSON(RANGE_KEY, myRanges); }
  }
  migrateBuildBuffs();   // 구 형식(스탯+스킬 분리) 번들이면 스킬을 빌드로 병합
  fillPresetSel($("sp-sel"), statPresets); spApply(statPresets.active);   // 스킬(buffs)도 함께 적용됨
  fillPresetSel($("cp-sel"), cubePresets); await cpApply(cubePresets.active);
  if (allyPresets) { fillPresetSel($("ap-sel"), allyPresets); setAllyDirty(false); if (currentSub === "ally") renderAlly(); }
  if (relicPresets) { fillPresetSel($("rp-sel"), relicPresets); rpApply(relicPresets.active); }
  if (buildPresets) { initBuildPresets(); fillBuildSel(); fillBuildPresetSelects(); if (tlInited) { bpApply(buildPresets.active); runTimelineIfActive(); } }
  renderConvSettings(); renderRangeSettings(); refreshQual(); calcDamage();
}

// 백업 문자열을 파일로 저장(공용).
function downloadBackup(text, filename) {
  const blob = new Blob([text], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click(); a.remove();
  URL.revokeObjectURL(url);
}

// ── 선택 내보내기: 체크박스로 보여주고 고른 것만 백업 ──
// mode='preset'(빌드 제외 모든 프리셋 + 전역) | 'build'(활성 빌드+스탯·동료·유물 묶음)
let exportMode = "preset";

// 컬렉션 하나를 체크박스 그룹으로 추가. onlyName이 주어지면 그 이름만(빌드 번들용). 추가됐으면 true.
function expAddGroup(list, key, label, onlyName) {
  const coll = liveCollections()[key];
  if (!coll || !coll.presets) return false;
  let names = Object.keys(coll.presets);
  if (onlyName != null) names = names.filter((n) => n === onlyName);
  if (!names.length) return false;
  const group = document.createElement("div"); group.className = "imp-group";
  const title = document.createElement("div"); title.className = "imp-group-title";
  title.textContent = `${label} (${names.length})`; group.appendChild(title);
  for (const n of names) {
    const row = document.createElement("label"); row.className = "imp-row";
    const cb = document.createElement("input");
    cb.type = "checkbox"; cb.className = "exp-cb"; cb.checked = true;
    cb.dataset.sec = key; cb.dataset.name = n;
    const span = document.createElement("span"); span.textContent = n;
    row.appendChild(cb); row.appendChild(span); group.appendChild(row);
  }
  list.appendChild(group);
  return true;
}

function openExportSelection(mode) {
  exportMode = mode;
  const list = $("export-select-list"); list.innerHTML = "";
  let any = false;
  if (mode === "build") {
    // 빌드 번들: 활성 빌드 1개 + 그 빌드에 속한 스탯·동료·유물(무조건 함께, 체크 해제 불가·읽기전용).
    const b = buildPresets && buildPresets.presets[buildPresets.active];
    if (!b) { toast("내보낼 빌드가 없습니다."); return false; }
    const rows = [["빌드", buildPresets.active]];
    if (b.stat && statPresets && statPresets.presets[b.stat]) rows.push(["캐릭터", b.stat]);
    if (b.ally && allyPresets && allyPresets.presets[b.ally]) rows.push(["동료", b.ally]);
    if (b.relic && relicPresets && relicPresets.presets[b.relic]) rows.push(["유물", b.relic]);
    const group = document.createElement("div"); group.className = "imp-group";
    const title = document.createElement("div"); title.className = "imp-group-title";
    title.textContent = `'${buildPresets.active}' 빌드 + 연결 프리셋 (함께 내보냄)`; group.appendChild(title);
    for (const [lbl, nm] of rows) {
      const row = document.createElement("div"); row.className = "imp-row exp-fixed";
      const k = document.createElement("span"); k.className = "exp-kind"; k.textContent = lbl;
      const v = document.createElement("span"); v.textContent = nm;
      row.appendChild(k); row.appendChild(v); group.appendChild(row);
    }
    list.appendChild(group); any = true;
  } else {
    // 프리셋: 빌드 제외한 모든 프리셋 + 전역(환산·범위)
    for (const sec of IMPORT_SECTIONS) if (sec.key !== "buildPresets") any = expAddGroup(list, sec.key, sec.label, null) || any;
    const gvals = EXPORT_GLOBALS.filter((g) => { const v = g.get(); return v && Object.keys(v).length; });
    if (gvals.length) {
      const group = document.createElement("div"); group.className = "imp-group";
      const title = document.createElement("div"); title.className = "imp-group-title"; title.textContent = "설정"; group.appendChild(title);
      for (const g of gvals) {
        const row = document.createElement("label"); row.className = "imp-row";
        const cb = document.createElement("input");
        cb.type = "checkbox"; cb.className = "exp-cb"; cb.checked = true; cb.dataset.glob = g.key;
        const span = document.createElement("span"); span.textContent = g.label;
        row.appendChild(cb); row.appendChild(span); group.appendChild(row);
      }
      list.appendChild(group); any = true;
    }
  }
  if (!any) { toast(mode === "build" ? "내보낼 빌드가 없습니다." : "내보낼 프리셋이 없습니다."); return false; }
  $("modal-export-select-title").textContent = mode === "build" ? "빌드 내보내기" : "프리셋 내보내기";
  $("exp-all").checked = true;
  const allWrap = $("exp-all") ? $("exp-all").closest("label") : null;
  if (allWrap) allWrap.style.display = mode === "build" ? "none" : "";   // 빌드 모드는 고를 게 없음(전체선택 숨김)
  $("export-select-status").textContent = "";
  return true;
}

// 빌드 번들: 활성 빌드 1개 + 그 빌드에 속한 스탯·동료·유물(무조건 포함). 큐브 제외.
function buildExportBundle() {
  const b = buildPresets && buildPresets.presets[buildPresets.active];
  if (!b) return null;
  const one = (coll, name) => (coll && coll.presets && coll.presets[name]) ? { active: name, presets: { [name]: coll.presets[name] } } : undefined;
  const out = { format: BACKUP_FORMAT, version: 1, buildPresets: { active: buildPresets.active, presets: { [buildPresets.active]: b } } };
  const sp = one(statPresets, b.stat), rp = one(relicPresets, b.relic);   // 동료는 빌드에 내장(별도 프리셋 아님)
  if (sp) out.statPresets = sp;
  if (rp) out.relicPresets = rp;
  return JSON.stringify(out, null, 2);
}
function exportBundleForMode() { return exportMode === "build" ? buildExportBundle() : selectedExportBundle(); }

// 체크된 항목만 담은 백업 문자열. 아무것도 안 고르면 null.
function selectedExportBundle() {
  const checked = [...document.querySelectorAll("#export-select-list .exp-cb:checked")];
  if (!checked.length) return null;
  const sel = {}; const globs = new Set();
  for (const cb of checked) {
    if (cb.dataset.glob) globs.add(cb.dataset.glob);
    else (sel[cb.dataset.sec] = sel[cb.dataset.sec] || new Set()).add(cb.dataset.name);
  }
  const live = liveCollections();
  const out = { format: BACKUP_FORMAT, version: 1 };
  for (const sec of IMPORT_SECTIONS) if (sel[sec.key] && live[sec.key]) out[sec.key] = filterCollection(live[sec.key], sel[sec.key]);
  if (globs.has("conversions")) out.conversions = myConv;
  if (globs.has("ranges")) out.ranges = myRanges;
  return JSON.stringify(out, null, 2);
}

$("export-btn").addEventListener("click", () => { if (openExportSelection("preset")) openModal("modal-export-select"); });
$("build-export-btn").addEventListener("click", () => { if (openExportSelection("build")) openModal("modal-export-select"); });
$("exp-all").addEventListener("change", () => {
  const on = $("exp-all").checked;
  document.querySelectorAll("#export-select-list .exp-cb").forEach((cb) => { cb.checked = on; });
});
$("export-select-list").addEventListener("change", () => {
  const all = [...document.querySelectorAll("#export-select-list .exp-cb")];
  $("exp-all").checked = all.length > 0 && all.every((cb) => cb.checked);
});
// 내보내기 파일명: {build|preset}_[빌드명_]YYMMDD_HHMM.mkbak (예: build_자동 사냥_260822_2319.mkbak)
function exportStamp() {
  const d = new Date(), p = (n) => String(n).padStart(2, "0");
  return `${p(d.getFullYear() % 100)}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}`;
}
// 파일명용 정리: 양끝 trim · 다중공백→단일 · 파일시스템 금지문자 제거(내부 띄어쓰기는 유지).
function fileSafe(s) { return String(s || "").replace(/[\\/:*?"<>|]/g, "").replace(/\s+/g, " ").trim(); }
function exportFilename() {
  const stamp = exportStamp();
  if (exportMode === "build") {
    const name = fileSafe((buildPresets && buildPresets.active) || "") || "build";
    return `build_${name}_${stamp}.mkbak`;
  }
  return `preset_${stamp}.mkbak`;
}
$("exp-file-btn").addEventListener("click", () => {
  const s = exportBundleForMode();
  if (!s) { $("export-select-status").textContent = "내보낼 항목을 하나 이상 선택하세요."; return; }
  downloadBackup(s, exportFilename());
  closeModal("modal-export-select"); toast("파일로 내보냈습니다.");
});
$("exp-text-btn").addEventListener("click", async () => {
  const s = exportBundleForMode();
  if (!s) { $("export-select-status").textContent = "내보낼 항목을 하나 이상 선택하세요."; return; }
  closeModal("modal-export-select");
  $("export-text-area").value = s;
  openModal("modal-export");
  if (await copyToClipboard(s)) toast("텍스트를 복사했습니다.");
});
// ── 선택 불러오기: 백업을 파싱해 프리셋 목록을 체크박스로 보여주고, 고른 것만 병합 ──
const IMPORT_SECTIONS = [
  { key: "buildPresets", label: "빌드" },
  { key: "statPresets", label: "캐릭터" },
  { key: "cubePresets", label: "큐브" },
  { key: "buffPresets", label: "스킬(구)" },
  { key: "allyPresets", label: "동료" },
  { key: "relicPresets", label: "유물" },
];
// 내보내기 선택 대상 = 이름 컬렉션(IMPORT_SECTIONS) + 전역 설정(환산·범위) 단일 토글.
const EXPORT_GLOBALS = [
  { key: "conversions", label: "딜 환산 설정", get: () => myConv },
  { key: "ranges", label: "추천 옵션 범위", get: () => myRanges },
];
// 라이브 컬렉션 접근(내보내기 선택 목록·필터용).
function liveCollections() {
  return { statPresets, cubePresets, buffPresets, allyPresets, relicPresets, buildPresets };
}
let pendingBundle = null;   // 선택 대기 중인 파싱된 번들

// 컬렉션({active,presets})에서 선택된 이름만 남긴다. 장착(active) 프리셋이 선택에 포함되면 그대로 유지,
// 포함 안 되면 선택된 것 중 첫 번째를 장착으로(불러올 때 원래 장착이 그대로 복원되도록).
function filterCollection(coll, nameSet) {
  const presets = {};
  for (const [n, v] of Object.entries(coll.presets || {})) if (nameSet.has(n)) presets[n] = v;
  const active = (coll.active && nameSet.has(coll.active)) ? coll.active : Object.keys(presets)[0];
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
  // ★계정 격리: 로그인 계정이 직전 소유자와 다르면 이전 계정의 로컬 프리셋을 지운다(새 계정에 이전 데이터가 새지 않도록).
  // 이 loadMe는 부팅에서 init*·pullPresets 앞에서 await되므로, 여기서 지우면 이후 init이 빈 상태로 시작해 서버 데이터로만 채워진다.
  const presetOwner = (me && me.loggedIn && me.email) ? String(me.email).toLowerCase() : "anon";
  const prevPresetOwner = localStorage.getItem(PRESET_OWNER_KEY);
  if (prevPresetOwner !== null && prevPresetOwner !== presetOwner) clearUserData();
  localStorage.setItem(PRESET_OWNER_KEY, presetOwner);
  // 정지 상태(banned/banMessage/banUntil)는 캐시에 남기지 않는다 — 오버레이는 항상 서버 진실로만 결정(스테일 오탐 방지).
  const meCache = Object.assign({}, me); delete meCache.banned; delete meCache.banMessage; delete meCache.banUntil;
  saveJSON(CACHE_ME, meCache);
  renderLoginUI();
  applyBanState();
  if (typeof refreshPatchForAccount === "function") refreshPatchForAccount();   // 계정 확정 후 새 소식 재평가(계정별)
  if (typeof refreshInquiryDot === "function") refreshInquiryDot();             // 계정 확정 후 운영자 답장 알림 재평가
}

async function doLogout() {
  try { await fetch("/auth/logout", { method: "POST" }); } catch {}
  // 계정 격리: 다음 로그인 계정에 이전 계정 데이터가 안 보이도록 로컬 유저데이터 전부 제거(유물·빌드 포함) + 캐시·소유자 초기화(정적 캐시는 유지).
  clearUserData();
  localStorage.removeItem(CACHE_ME);
  localStorage.setItem(PRESET_OWNER_KEY, "anon");
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

// ──────────────────── 제보/문의(인웹) + 계정 정지 ────────────────────
let inqFiles = [];                          // 선택한 이미지 File 목록
const INQ_MAX = 20 * 1024 * 1024;           // 1회 합계 20MB
const INQ_MAX_IMAGES = 5;                   // 이미지 최대 장수(서버·카톡 발송 캡과 동일)
const INQ_STATUS_USER = { new: "접수됨", seen: "확인 중", resolved: "처리 완료" };

// 정지 계정: 전체 기능 차단 오버레이 + 안내문. me.banned 기준(서버 /api/me).
function applyBanState() {
  const ov = $("ban-overlay"); if (!ov) return;
  const lo = $("ban-logout");   // 오버레이 로그아웃 = doLogout(캐시·로컬데이터 정리 후 리로드). 서버 리다이렉트만으론 캐시가 안 지워짐.
  if (lo && !lo.dataset.wired) { lo.dataset.wired = "1"; lo.addEventListener("click", (e) => { e.preventDefault(); doLogout(); }); }
  if (me && me.banned) {
    const m = $("ban-message"); if (m) m.textContent = me.banMessage || "계정 이용이 제한되었습니다.";
    ov.hidden = false; document.body.classList.add("banned");
  } else {
    ov.hidden = true; document.body.classList.remove("banned");
  }
}

function openInquiry() { const m = $("modal-inquiry"); if (!m) return; m.hidden = false; inqSwitch("new"); refreshInquiryDot(); }

// 로그인 여부에 따라 게이트/폼/내역 전환.
function inqSwitch(tab) {
  const gate = $("inq-login-gate"), form = $("inq-form"), mine = $("inq-mine");
  const tabNew = $("inq-tab-new"), tabMine = $("inq-tab-mine");
  if (!gate) return;
  if (!me.loggedIn) { gate.hidden = false; form.hidden = true; mine.hidden = true; tabNew.hidden = true; tabMine.hidden = true; return; }
  gate.hidden = true; tabNew.hidden = false; tabMine.hidden = false;
  tabNew.classList.toggle("ghost", tab !== "new");
  tabMine.classList.toggle("ghost", tab !== "mine");
  if (tab === "mine") { form.hidden = true; mine.hidden = false; loadMyInquiries(); }
  else { form.hidden = false; mine.hidden = true; inqLoadQuota(); }
}

async function inqLoadQuota() {
  const el = $("inq-quota"); if (el) el.textContent = "";
  try {
    const q = await (await fetch("/api/inquiries/quota")).json();
    if (q.banned) { if (el) el.textContent = q.banMessage || "계정 이용이 제한되었습니다."; return; }
    if (el) el.textContent = `오늘 ${q.usedToday}/${q.limit}건 접수 · 남은 ${q.remaining}건`;
  } catch { }
}

function inqSizeBytes() {
  let n = 0; for (const f of inqFiles) n += f.size;
  const body = $("inq-body"); if (body) n += new Blob([body.value || ""]).size;
  return n;
}
function inqRenderFiles() {
  const box = $("inq-previews"); if (!box) return;
  box.innerHTML = "";
  inqFiles.forEach((f, i) => {
    const url = URL.createObjectURL(f);
    const d = document.createElement("div");
    d.className = "inq-prev";
    d.innerHTML = `<img src="${url}" alt=""><button type="button" class="inq-prev-x" aria-label="삭제">✕</button>`;
    d.querySelector(".inq-prev-x").addEventListener("click", () => { inqFiles.splice(i, 1); inqRenderFiles(); });
    box.appendChild(d);
  });
  inqUpdateSize();
}
function inqUpdateSize() {
  const el = $("inq-size"); if (!el) return;
  const n = inqSizeBytes();
  el.textContent = `${(n / 1048576).toFixed(1)} / 20MB`;
  el.classList.toggle("over", n > INQ_MAX);
  const sub = $("inq-submit"); if (sub) sub.disabled = n > INQ_MAX;
}
function inqShowMsg(text, err) {
  const m = $("inq-msg"); if (!m) return;
  m.textContent = text; m.hidden = false; m.className = "inq-msg " + (err ? "err" : "ok");
}

async function submitInquiry(e) {
  e.preventDefault();
  const m = $("inq-msg"); if (m) { m.hidden = true; }
  const title = ($("inq-title").value || "").trim();
  const body = ($("inq-body").value || "").trim();
  if (!title) { inqShowMsg("제목을 입력해 주세요.", true); return; }
  if (!body) { inqShowMsg("내용을 입력해 주세요.", true); return; }
  if (inqSizeBytes() > INQ_MAX) { inqShowMsg("최대 용량(20MB)을 초과했어요.", true); return; }
  const fd = new FormData();
  fd.append("type", $("inq-type").value);
  fd.append("title", title);
  fd.append("body", body);
  fd.append("bundle", bundleString());   // 전체 프리셋(항상 첨부)
  try { const bx = buildExportBundle(); if (bx) fd.append("build", bx); } catch { }   // 현재 빌드(나이트로드 등, 있으면)
  for (const f of inqFiles) fd.append("files", f);
  const sub = $("inq-submit"); sub.disabled = true;
  try {
    const r = await fetch("/api/inquiries", { method: "POST", body: fd });
    const data = await r.json().catch(() => null);
    if (!r.ok) {
      inqShowMsg((data && data.error) || ("오류 " + r.status), true);
      if (data && data.banned) { me.banned = true; me.banMessage = data.error; applyBanState(); }
      return;
    }
    inqShowMsg(`접수됐어요. 소중한 제보 감사합니다! (오늘 남은 ${data.remainingToday}건)`, false);
    $("inq-form").reset(); inqFiles = []; inqRenderFiles(); inqLoadQuota();
  } catch (err) { inqShowMsg("전송 실패: " + err.message, true); }
  finally { sub.disabled = false; }
}

async function loadMyInquiries() {
  const box = $("inq-mine-list"); if (!box) return;
  box.innerHTML = `<p class="hint">불러오는 중…</p>`;
  try {
    const data = await (await fetch("/api/inquiries/mine")).json();
    const list = (data && data.inquiries) || [];
    box.innerHTML = list.length ? list.map(inqMineRow).join("") : `<p class="hint">아직 문의 내역이 없어요.</p>`;
    markInqRepliesSeen(inqLatestAdminAt(list));   // 내 문의(답장 포함) 열람 = 답장 확인 → 빨간 점 제거
  } catch { box.innerHTML = `<p class="hint">불러오지 못했어요.</p>`; }
}

// ── 문의 답장 알림(좌상단 FAB 빨간 점) — 업데이트 내역 새 소식과 동일 방식. 로그인 계정별로 '본 것' 기록. ──
const INQ_REPLY_SEEN_KEY = "mekicalc.inq.replySeen.v1";
function inqReplySeenKey() { return INQ_REPLY_SEEN_KEY + ":" + ((me && me.loggedIn && me.email) ? me.email.toLowerCase() : "anon"); }
// 내 문의들 중 운영자(admin) 답장의 최신 시각(ms). 없으면 0.
function inqLatestAdminAt(list) {
  let max = 0;
  for (const q of list || [])
    for (const m of (q.messages || []))
      if (m && m.author === "admin" && typeof m.at === "number" && m.at > max) max = m.at;
  return max;
}
function setInqDot(on) {
  const d = $("inq-fab-dot"); if (d) d.hidden = !on;             // 좌상단 FAB 점
  const t = $("inq-tab-mine-dot"); if (t) t.hidden = !on;         // 모달 '내 내역' 탭 점
}
function markInqRepliesSeen(latest) { saveJSON(inqReplySeenKey(), latest || 0); setInqDot(false); }
// 로그인 계정 확정 후 답장 유무 재평가(로그인 유저만 — 답장은 로그인 문의에만 옴).
async function refreshInquiryDot() {
  if (!(me && me.loggedIn)) { setInqDot(false); return; }
  try {
    const data = await (await fetch("/api/inquiries/mine")).json();
    const latest = inqLatestAdminAt((data && data.inquiries) || []);
    const seen = loadJSON(inqReplySeenKey(), 0) || 0;
    setInqDot(latest > seen);
  } catch { /* 조용히 무시 */ }
}
function inqTypeLabel(t) { return t === "bug" ? "버그 제보" : t === "suggest" ? "기능 건의" : "기타 문의"; }
function inqMineRow(q) {
  const st = INQ_STATUS_USER[q.status] || q.status;
  const when = new Date(q.createdAt).toLocaleString("ko-KR");
  const thumbs = (q.attachments || []).map((a, i) =>
    `<a href="/api/inquiries/mine/${q.id}/attachments/${i}" target="_blank" rel="noopener"><img class="inq-mine-thumb" src="/api/inquiries/mine/${q.id}/attachments/${i}" alt=""></a>`).join("");
  const replies = (q.messages || []).filter((m) => m.author === "admin").map((m) =>
    `<div class="inq-reply"><b>운영자</b> ${escapeHtml(m.body || "")}</div>`).join("");
  return `<div class="inq-mine-item">
    <div class="inq-mine-top"><span class="inq-badge s-${q.status}">${st}</span>
      <span class="inq-mine-type">${escapeHtml(inqTypeLabel(q.type))}</span>
      <span class="inq-mine-when">${when}</span></div>
    ${q.title ? `<div class="inq-mine-title">${escapeHtml(q.title)}</div>` : ""}
    <div class="inq-mine-body">${escapeHtml(q.body || "")}</div>
    ${thumbs ? `<div class="inq-mine-thumbs">${thumbs}</div>` : ""}
    ${replies ? `<div class="inq-replies">${replies}</div>` : ""}
  </div>`;
}

function initInquiry() {
  const link = $("footer-inquiry");
  if (link) link.addEventListener("click", (e) => { e.preventDefault(); openInquiry(); });
  const fab = $("inquiry-fab");   // 좌상단 고정 문의 버튼
  if (fab) fab.addEventListener("click", (e) => { e.preventDefault(); openInquiry(); });
  const fi = $("inq-file-input");
  if (fi) fi.addEventListener("change", () => {
    for (const f of fi.files) {
      if (!f || !f.type || !f.type.startsWith("image/")) continue;
      if (inqFiles.length >= INQ_MAX_IMAGES) { inqShowMsg(`이미지는 최대 ${INQ_MAX_IMAGES}장까지 첨부할 수 있어요.`, true); break; }
      inqFiles.push(f);
    }
    fi.value = ""; inqRenderFiles();
  });
  const form = $("inq-form"); if (form) form.addEventListener("submit", submitInquiry);
  const body = $("inq-body"); if (body) body.addEventListener("input", inqUpdateSize);
  const tn = $("inq-tab-new"); if (tn) tn.addEventListener("click", () => inqSwitch("new"));
  const tm = $("inq-tab-mine"); if (tm) tm.addEventListener("click", () => inqSwitch("mine"));
  const ec = $("inq-email-copy");   // 비로그인 이메일: mailto 대신 클릭하면 주소 복사
  if (ec) {
    const doCopy = async () => {
      const ok = await copyToClipboard("mekicalc@gmail.com");
      const hint = ec.querySelector(".inq-email-hint");
      if (hint) { const prev = hint.textContent; hint.textContent = ok ? "복사됨!" : "복사 실패"; ec.classList.add("copied"); setTimeout(() => { hint.textContent = prev; ec.classList.remove("copied"); }, 1500); }
    };
    ec.addEventListener("click", doCopy);
    ec.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); doCopy(); } });
  }
}
initInquiry();

// ──────────────────── 업데이트 내역(패치노트) + 새 소식 플로팅 ────────────────────
const PATCH_SEEN_KEY = "mekicalc.patch.lastSeen.v1";
let patchNotes = null;
const PATCH_TAG_KO = { feature: "신기능", improve: "개선", fix: "수정" };
const PATCH_TAG_ORDER = ["feature", "improve", "fix"];   // 태그 표시 순서 고정(신기능→개선→수정). 어떤 순서로 넣어도 이 순서로 정렬.

async function loadPatchNotes() {
  if (patchNotes) return patchNotes;
  try {
    const r = await fetch("/patchnotes.json", { cache: "no-store" });   // 배포 즉시 최신 반영(캐시 안 씀)
    const d = await r.json();
    patchNotes = Array.isArray(d) ? d : (d && d.notes) || [];
  } catch { patchNotes = []; }
  return patchNotes;
}
function patchLatestId() {
  if (!patchNotes || !patchNotes[0]) return null;
  const n = patchNotes[0]; return String(n.id != null ? n.id : n.date);
}
// 본 기록은 계정(이메일) 단위 — 로그아웃(익명)·다른 계정 로그인 시 각각 처음이면 다시 뜸.
function patchSeenKey() { return PATCH_SEEN_KEY + ":" + ((me && me.loggedIn && me.email) ? me.email.toLowerCase() : "anon"); }
function patchHasUnseen() { const l = patchLatestId(); return !!l && loadJSON(patchSeenKey(), null) !== l; }
function markPatchSeen() { const l = patchLatestId(); if (l) saveJSON(patchSeenKey(), l); updatePatchBadge(); hideWhatsNew(); }
// 로그인 상태가 확정된 뒤(loadMe) 계정 키 기준으로 새 소식 재평가.
async function refreshPatchForAccount() {
  await loadPatchNotes();
  updatePatchBadge();
  if (patchHasUnseen()) showWhatsNewIfNeeded(); else hideWhatsNew();
}

function patchTagsHtml(tags) {
  const rank = (t) => { const i = PATCH_TAG_ORDER.indexOf(t); return i < 0 ? PATCH_TAG_ORDER.length : i; };   // 미정의 태그는 뒤로
  return (tags || []).slice().sort((a, b) => rank(a) - rank(b))
    .map((t) => `<span class="pn-tag pn-tag-${escapeHtml(String(t))}">${escapeHtml(PATCH_TAG_KO[t] || t)}</span>`).join("");
}
function patchCardHtml(n) {
  return `<div class="pn-card">
    <div class="pn-head"><span class="pn-date">${escapeHtml(n.date || "")}</span>${patchTagsHtml(n.tags)}</div>
    <div class="pn-title">${escapeHtml(n.title || "")}</div>
    ${(n.items && n.items.length) ? `<ul class="pn-items">${n.items.map((it) => `<li>${escapeHtml(it)}</li>`).join("")}</ul>` : ""}
  </div>`;
}

// 전체 히스토리 탭 렌더 + 열면 '다 본 것'으로 표시.
async function renderUpdates() {
  const box = $("pn-list"); if (!box) return;
  box.innerHTML = `<p class="hint">불러오는 중…</p>`;
  await loadPatchNotes();
  box.innerHTML = patchNotes.length ? patchNotes.map(patchCardHtml).join("") : `<p class="hint">아직 업데이트 내역이 없습니다.</p>`;
  markPatchSeen();
}

// 메뉴 '업데이트 내역'에 새 소식 점(●) 토글.
function updatePatchBadge() {
  const on = patchHasUnseen();
  const item = (routeItemEls && routeItemEls.get) ? routeItemEls.get("/updates") : null;
  const lbl = item && item.querySelector(".menu-label");
  if (lbl) {
    let dot = lbl.querySelector(".menu-new-dot");
    if (on && !dot) { dot = document.createElement("span"); dot.className = "menu-new-dot"; dot.title = "새 소식"; lbl.appendChild(dot); }
    else if (!on && dot) dot.remove();
  }
}

function hideWhatsNew() { const w = $("whatsnew"); if (w) w.hidden = true; }
function showWhatsNewIfNeeded() {
  if (!patchHasUnseen()) return;
  if (location.pathname === "/updates") { markPatchSeen(); return; }   // 이미 업데이트 페이지면 플로팅 불필요
  const wrap = $("whatsnew"), body = $("whatsnew-body"); if (!wrap || !body) return;
  body.innerHTML = patchNotes.slice(0, 1).map(patchCardHtml).join("");   // 새 소식 = 최근 1건만(전체는 '업데이트 내역' 탭)
  wrap.hidden = false;
}

async function initPatchNotes() {
  await loadPatchNotes();
  const close = $("whatsnew-close"); if (close && !close._wired) { close._wired = true; close.addEventListener("click", markPatchSeen); }
  const open = $("whatsnew-open"); if (open && !open._wired) { open._wired = true; open.addEventListener("click", markPatchSeen); }   // 이동은 route-link가 처리, 여기선 본 것 표시만
  const wrap = $("whatsnew"); if (wrap && !wrap._wiredBd) { wrap._wiredBd = true; wrap.addEventListener("click", (e) => { if (e.target === wrap) markPatchSeen(); }); }   // 모바일: 배경 탭 시 닫기(데스크톱은 pointer-events:none이라 발동 안 함)
  updatePatchBadge();
  showWhatsNewIfNeeded();
}
initPatchNotes();

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

// ──────────────────── 정밀 타임라인 (나이트로드 · 베타) ────────────────────
let tlMode = "auto", tlSkills = null, tlInited = false, tlBooting = true;   // tlBooting: init 초기화 동안 runTimeline 중복 억제(첫 renderTimeline이 해제)

function tlBig(n) {
  const u = [[1e16, "경"], [1e12, "조"], [1e8, "억"], [1e4, "만"]];
  for (const [v, s] of u) if (Math.abs(n) >= v) return (n / v).toFixed(2) + s;
  return Math.round(n || 0).toLocaleString();
}

async function renderTimeline() {
  if (!tlSkills) { try { tlSkills = await (await fetch("/api/nl-skills")).json(); } catch { tlSkills = []; } }
  if (!tlInited) { tlInitHandlers(); initBuildPresets(); fillBuildSel(); fillBuildPresetSelects(); bpApply(buildPresets.active); tlInited = true; }
  tlBooting = false;   // 초기화(프리셋 적용) 중 발동한 runTimeline은 억제됐고, 이제부터 실계산 허용
  tlSyncMode();
  if (currentJob() !== 1) {
    tlClearResult('<div class="tl-empty">정밀 타임라인은 현재 <b>나이트로드</b>만 지원해요.<br>캐릭터 직업을 나이트로드로 두고 다시 열어주세요.</div>');
    return;
  }
  runTimeline();
}

// 활성 빌드의 직업이 현재 직업과 다르면 계산 차단(로테이션 스킬이 직업 전용이라 무의미). 배치표도 숨김.
function buildJobMismatch() {
  if (activeBuildJob() === currentJob()) return false;
  const jn = JOB_NAME[activeBuildJob()] || `직업 ${activeBuildJob()}`;
  tlClearResult(`<div class="tl-empty">이 빌드는 <b>${jn}</b>용이에요. 현재 직업과 달라 계산할 수 없어요.<br>직업을 맞추거나 <b>현재 직업의 빌드</b>를 선택하세요.</div>`);
  $("tl-manual").hidden = true;
  return true;
}

// 배치표 상태 = 시전 순서(오름차순: 첫 시전 → 마지막)의 배열. 각 원소 {idx, time(초)}.
// 화면은 유저 요청대로 '시각 내림차순'(최근 시전이 위)으로 그리지만, 재계산·드래그는 이 오름차순 순서로 처리한다.
// 이 상태는 활성 빌드 프리셋의 로테이션 편집본 — 저장 버튼(tb-save)으로만 빌드에 커밋된다(미저장 유실).
let tlSeq = [];
let tlAutoFill = true;
let tlFillSkills = true;    // 수동 하이브리드: 빈 칸을 사용 중인 스킬로 쿨 맞춰 자동 채움(기본 ON)
let tlOptimize = true;      // 스킬 사용 최적화(백엔드 플래그): 자동=항상, 수동=이 토글. tlSeq 불변(비파괴), 기본 ON.
let tlExpStats = [];        // 가상 스탯 실험: [{stat, amount}] — 프리셋에 옵션식대로 가산(이 빌드 계산에서만). 빌드에 저장.
let tlExpRelics = [];       // 유물 실험: [{id, awaken, pot:[{type,grade,value}]}] — 현재 장착 유물로 초기 채움, 각성·잠재 자유 편집. 이 빌드 계산에서만. 빌드에 저장.
let tlExpDiffActive = false; // 마지막 계산에서 실험 변경분(diff)이 있었는지 — 타임라인 헤더 토글 노출 조건.
// tlExpApply는 buildMenu(showPanel→updateExpTags)가 상단 실행에서 참조하므로 파일 앞쪽(showPanel 위)에 선언됨.
let tlSkillsOff = new Set();   // 사용 해제된 액티브 스킬 idx(빌드에 저장 · 자동/자동채움에서 제외)
let tlCooldowns = {};   // idx → 실효 쿨(ms), 마지막 계산 응답에서 캐시(배치 자동시각용)
let tlBaseSlotMs = 0;   // 실효 공속 슬롯(ms), 마지막 계산 응답 캐시
let tlDonutData = { slices: [] };   // 스킬 도넛 조각(호버 갱신용)

// 사용 스킬 체크리스트 렌더(액티브만, 평타 제외). 체크 해제 = tlSkillsOff에 추가.
function tlRenderSkillList() {
  const box = $("tl-skill-list"); if (!box) return;
  const actives = (tlSkills || []).filter((s) => !s.basic);
  if (!actives.length) { box.innerHTML = '<span class="tl-ro-hint">스킬 목록을 불러오는 중…</span>'; return; }
  box.innerHTML = actives.map((s) =>
    `<label class="tl-skill-cb"><input type="checkbox" data-idx="${s.idx}"${tlSkillsOff.has(s.idx) ? "" : " checked"}> ${s.name}</label>`).join("");
  for (const cb of box.querySelectorAll("input[data-idx]")) {
    cb.addEventListener("change", () => {
      const idx = parseInt(cb.dataset.idx, 10);
      if (cb.checked) tlSkillsOff.delete(idx); else tlSkillsOff.add(idx);
      setBuildDirty(true); runTimeline();
    });
  }
}

function tlInitHandlers() {
  for (const b of document.querySelectorAll("#tl-mode .ds-seg-btn")) {
    b.addEventListener("click", () => { tlMode = b.dataset.v; tlSyncMode(); setBuildDirty(true); runTimeline(); });
  }
  $("tl-run").addEventListener("click", runTimeline);
  $("tl-addrow").addEventListener("click", () => { tlAddRow(); setBuildDirty(true); runTimeline(); });
  const expAdd = $("tl-exp-addrow");
  if (expAdd) expAdd.addEventListener("click", () => { tlExpAddRow(); setBuildDirty(true); });
  const expRelicAdd = $("tl-exp-relic-addrow");
  if (expRelicAdd) expRelicAdd.addEventListener("click", () => { tlExpRelicAddRow(); setBuildDirty(true); });
  const expRelicReset = $("tl-exp-relic-reset");
  if (expRelicReset) expRelicReset.addEventListener("click", () => { tlExpRelicInit(); setBuildDirty(true); runTimeline(); });
  // '실험값 전체 적용' 토글은 타임라인 헤더에 매 렌더마다 동적 생성 → renderTimelineResult에서 바인딩.
  const af = $("tl-autofill");
  if (af) af.addEventListener("change", () => { tlAutoFill = af.checked; tlRender(); setBuildDirty(true); runTimeline(); });
  const fs = $("tl-fillskills");
  if (fs) fs.addEventListener("change", () => { tlFillSkills = fs.checked; setBuildDirty(true); runTimeline(); });
  const opt = $("tl-optimize");
  if (opt) opt.addEventListener("change", () => tlSetOptimize(opt.checked));
  // 시뮬 길이·유물 계산 옵션·동료 설정도 빌드에 저장되는 선택 → 변경 즉시 저장 버튼 활성(input), 확정 시 재계산(change).
  for (const id of ["tl-simsec", "tl-enemies", "tl-kills", "tl-content", "tl-artoffset", "tl-preact",
                    "tl-companion", "tl-companion-grade", "tl-companion-level", "tl-first-summon"]) {
    const e = $(id);
    if (e) {
      e.addEventListener("input", () => setBuildDirty(true));   // 타이핑 즉시 저장 버튼 활성(blur 전에도)
      e.addEventListener("change", () => {
        if (id === "tl-companion") tlFillCompanionGrades();       // 클래스 변경 → 등급 목록 재구성
        else if (id === "tl-companion-grade") tlSyncCompanionCap();
        setBuildDirty(true); runTimeline();
      });
    }
  }
  tlLoadSupporters();   // 동료 목록 채우기(비동기)
  const skAll = $("tl-skill-all"), skNone = $("tl-skill-none");
  if (skAll) skAll.addEventListener("click", () => { tlSkillsOff.clear(); tlRenderSkillList(); setBuildDirty(true); runTimeline(); });
  if (skNone) skNone.addEventListener("click", () => { tlSkillsOff = new Set((tlSkills || []).filter((s) => !s.basic).map((s) => s.idx)); tlRenderSkillList(); setBuildDirty(true); runTimeline(); });
  tlWireTooltip();
}

// 간트 커스텀 툴팁(네이티브 title 대신 — 마우스에 안 가리게 바 위에, 즉시 표시). #tl-box-gantt에 위임.
// x=마우스 위치 추적(확대 시 넓은 바에서 고정 좌표가 넘치는 문제 방지), y=바 위 고정.
// 간트 막대/마커의 툴팁을 배치. anchor=기준 막대. anchorX=시간선(커서/탭) x — 있으면 그 기준, 없으면 막대 중앙.
// y는 간트 최상단(시각 가이드 태그 높이) 위 — 어떤 막대와도 안 겹침. x는 좌우 뷰포트 경계에 닿으면 그걸 한계로 clamp.
function tlPlaceTip(res, tip, anchor, anchorX) {
  tip.hidden = false;
  tip.style.transform = "none";   // left/top을 박스 좌상단 기준으로 직접 배치(중앙 translate 안 씀)
  const rBar = anchor.getBoundingClientRect();
  const gantt = anchor.closest(".tl-gantt");
  const yTop = (gantt || anchor).getBoundingClientRect().top;   // 간트 최상단(막대들보다 위)
  const vw = document.documentElement.clientWidth;              // 스크롤바 제외 뷰포트 폭(가로 스크롤 방지 기준)
  const margin = 8, tw = tip.offsetWidth, th = tip.offsetHeight;
  const centerX = (anchorX != null) ? anchorX : (rBar.left + rBar.width / 2);   // 시간선 기준(폴백=막대 중앙)
  let left = centerX - tw / 2;
  const maxL = vw - margin - tw, minL = margin;
  if (left > maxL) left = maxL;                                  // 오른쪽 경계 한계(더 안 넘어감)
  if (left < minL) left = minL;                                  // 왼쪽 경계 한계(더 안 넘어감)
  tip.style.left = left + "px";
  tip.style.top = (yTop - 6 - th) + "px";                        // 간트 상단 위에 박스가 얹히도록(높이만큼 올림)
}
function tlWireTooltip() {
  const res = $("tl-box-gantt"), tip = $("tl-tip");   // 간트 막대 툴팁 → 타임라인 박스에 위임
  if (!res || !tip) return;
  // 데스크톱: 호버로 표시
  res.addEventListener("mousemove", (e) => {
    if (tip.dataset.pinned) return;   // 터치로 고정된 동안엔 호버가 덮지 않음
    const el = e.target.closest("[data-tip]");
    if (!el) { tip.hidden = true; return; }
    tip.innerHTML = el.getAttribute("data-tip");
    tlPlaceTip(res, tip, el, e.clientX);   // 시간선(커서) 위치 기준
  });
  res.addEventListener("mouseout", (e) => {
    if (tip.dataset.pinned) return;
    const el = e.target.closest("[data-tip]");
    if (el && !el.contains(e.relatedTarget)) tip.hidden = true;
  });
  // 모바일: 막대 탭으로 표시(고정). 다른 곳 탭하면 닫힘.
  res.addEventListener("touchend", (e) => {
    const t = e.changedTouches && e.changedTouches[0];
    const el = t && document.elementFromPoint(t.clientX, t.clientY);
    const bar = el && el.closest && el.closest("[data-tip]");
    if (!bar) { tip.hidden = true; delete tip.dataset.pinned; return; }
    tip.innerHTML = bar.getAttribute("data-tip");
    tip.dataset.pinned = "1";
    tlPlaceTip(res, tip, bar, t.clientX);   // 시간선(탭 지점) 기준
  }, { passive: true });
  document.addEventListener("touchend", (e) => {
    if (!tip.dataset.pinned) return;
    const t = e.changedTouches && e.changedTouches[0];
    const el = t && document.elementFromPoint(t.clientX, t.clientY);
    if (!el || !el.closest || (!el.closest("[data-tip]") && !el.closest(".tl-tip"))) { tip.hidden = true; delete tip.dataset.pinned; }
  }, { passive: true });
  // 라벨 열 접기 토글(재렌더돼도 위임으로 유지). 가변 폭(width:auto)이라 max-width 애니메이션이 앞부분에서
  // 죽는 걸 막기 위해, 실제 내용폭을 측정해 px 시작/끝점을 명시한다. 접힘 동안 매 프레임 스크롤도 보정.
  res.addEventListener("click", (e) => {
    if (!e.target.closest(".tl-labels-toggle")) return;
    const g = res.querySelector(".tl-gantt");
    const scroll = g && g.querySelector(".tl-gantt-scroll");
    const inner = scroll && scroll.querySelector(".tl-gantt-inner");
    const labels = g && g.querySelector(".tl-gantt-labels");
    if (!g || !labels) return;
    let frac = 0;
    if (inner && inner.clientWidth > 0) frac = scroll.scrollLeft / inner.clientWidth;   // 좌측 경계 콘텐츠 비율

    const collapsing = !tlLabelsCollapsed;
    // 제약 없는 내용폭 측정(transition 잠시 끔) → 펼침 목표폭은 CSS 상한(45%·300px)으로 클램프
    labels.style.transition = "none";
    labels.style.maxWidth = "none";
    const full = labels.scrollWidth;
    const target = Math.min(full, 300, res.clientWidth * 0.45);
    labels.style.maxWidth = (collapsing ? target : 0) + "px";   // 시작점
    void labels.offsetWidth;                                     // reflow
    labels.style.transition = "";                               // CSS transition 복귀

    tlLabelsCollapsed = collapsing;
    g.classList.toggle("collapsed", collapsing);
    labels.style.maxWidth = (collapsing ? 0 : target) + "px";   // 목표점
    const done = () => { labels.style.maxWidth = ""; labels.removeEventListener("transitionend", done); };   // 끝나면 CSS값으로 복귀
    labels.addEventListener("transitionend", done);

    if (!scroll || !inner) return;
    const t0 = performance.now(), DUR = 240;
    const follow = (now) => {
      scroll.scrollLeft = frac * inner.clientWidth;   // 폭 변하는 중에도 같은 비율 유지
      if (now - t0 < DUR) requestAnimationFrame(follow);
    };
    requestAnimationFrame(follow);
  });
}

function tlSyncMode() {
  for (const b of document.querySelectorAll("#tl-mode .ds-seg-btn")) b.classList.toggle("active", b.dataset.v === tlMode);
  $("tl-manual").hidden = tlMode !== "manual";
}

// ── 빌드 프리셋(타임라인 전용 로드아웃) ──────────────────────────────────────
// 빌드 = 스킬 배치(rotation)+자동채움+스탯·동료·유물 프리셋 선택+직업. 저장 버튼으로만 반영(미저장 유실).
// 빌드를 고르면 그 스탯/동료/유물이 전역 활성으로 적용됨(단일 소스 — 큐브 기댓값 등 전역 계산에 반영).
let buildDirty = false;
const JOB_NAME = { 0: "공용", 1: "나이트로드", 2: "다크나이트", 3: "비숍", 4: "히어로", 5: "보우마스터" };

// 현재 상태 스냅샷 1개 = 빌드. 로테이션+자동채움+모드+시뮬+유물계산옵션+로드아웃 전부. wipeRotation이면 배치 비움.
function buildPayload(wipeRotation) {
  const v = (id) => { const e = $(id); return e ? e.value : ""; };
  // 최적화는 백엔드 시뮬 플래그일 뿐 tlSeq를 안 건드리므로 저장은 항상 원본 시각 그대로.
  return {
    rotation: wipeRotation ? [] : tlSeq.map((r) => ({ idx: r.idx, time: r.time })),
    autoFill: tlAutoFill,
    fillSkills: tlFillSkills,                        // 수동 스킬 자동채움
    optimize: tlOptimize,                            // 스킬 사용 최적화 토글
    expStats: tlExpStats.map((r) => ({ stat: r.stat, amount: r.amount })),   // 가상 스탯 실험(스탯 델타)
    expRelics: tlExpRelics.map((r) => ({ id: r.id, awaken: r.awaken, pot: (r.pot || []).map((l) => ({ type: l.type, grade: l.grade, value: l.value })) })),   // 유물 실험(각성·잠재 포함)
    expApply: tlExpApply,                            // 실험값 전체 적용 토글
    skillsOff: [...tlSkillsOff],                     // 사용 해제 스킬
    mode: tlMode,                                   // 자동/수동
    sim: parseFloat(v("tl-simsec")) || 90,          // 시뮬 초
    enemies: v("tl-enemies"),                        // 마리수
    kills: v("tl-kills"),                            // 분당 처치수
    content: v("tl-content"),                        // 컨텐츠
    artOffset: parseFloat(v("tl-artoffset")) || 0,   // 유물 사전활성(초)
    preAct: !!($("tl-preact") && $("tl-preact").checked),   // 확률형 유물 사전활성
    job: currentJob(),
    stat: (statPresets && statPresets.active) || null,
    relic: (relicPresets && relicPresets.active) || null,
    companion: tlCompanionBuild(),                   // 타임라인 동료(클래스/등급/레벨/첫소환) — 빌드에 저장
    boxOrder: tlBoxOrderSnapshot(),                  // 빌드 박스 배치 순서(사용자 지정)
  };
}
// 활성 빌드의 저장된 직업(레거시 무태그=나이트로드).
function activeBuildJob() {
  const b = buildPresets && buildPresets.presets[buildPresets.active];
  return b && b.job != null ? b.job : 1;
}
// 미저장 변경 표시(변경 없으면 저장 버튼 비활성).
function setBuildDirty(on) {
  buildDirty = on;
  const btn = $("tb-save");
  if (btn) btn.disabled = !on;
}
function revertBuild() { bpApply(buildPresets.active); }   // 저장된 빌드로 복원(bpApply가 dirty 해제)
function canLeaveBuild() {
  if (!buildDirty) return true;
  if (confirm("저장하지 않은 빌드 변경이 있습니다.\n저장하지 않고 이동하면 변경이 되돌아갑니다. 계속할까요?")) { revertBuild(); return true; }
  return false;
}

// 현재 활성 스탯·동료·유물이 빌드 저장값과 다르면 변형 경고(저장 유도).
function updateBuildWarn() {
  const el = $("tl-build-warn"); if (!el) return;
  const b = buildPresets && buildPresets.presets[buildPresets.active];
  const diffs = [];
  if (b) {
    if (b.stat && statPresets && b.stat !== statPresets.active) diffs.push(`캐릭터 <b>${b.stat}</b>→${statPresets.active}`);
    if (b.ally && allyPresets && b.ally !== allyPresets.active) diffs.push(`동료 <b>${b.ally}</b>→${allyPresets.active}`);
    if (b.relic && relicPresets && b.relic !== relicPresets.active) diffs.push(`유물 <b>${b.relic}</b>→${relicPresets.active}`);
  }
  if (!diffs.length) { el.hidden = true; el.innerHTML = ""; return; }
  el.hidden = false;
  el.innerHTML = `⚠️ 현재 설정이 이 빌드 저장값과 달라요(${diffs.join(" · ")}). <b>저장</b>하면 이 설정으로 갱신되고, 안 하면 결과가 변형될 수 있어요.`;
}

// 레벨로 사용 불가한 스킬 라인 삭제(같은 직업 열화). 삭제 수 반환.
function tlCleanupLevel() {
  const lv = parseFloat((collectStats() || {}).LEVEL) || 0;
  const before = tlSeq.length;
  tlSeq = tlSeq.filter((r) => {
    const s = (tlSkills || []).find((x) => x.idx === r.idx);
    return !s || s.basic || (s.requireLevel || 0) <= lv;   // 미상(null)은 보존
  });
  return before - tlSeq.length;
}

function initBuildPresets() {
  if (!buildPresets || !buildPresets.presets || !Object.keys(buildPresets.presets).length) {
    buildPresets = { active: "기본", presets: { "기본": buildPayload(false) } };   // 빈 배치 + 현재 로드아웃
    saveJSON(BUILDP_KEY, buildPresets);
  }
  if (!buildPresets.presets[buildPresets.active]) buildPresets.active = Object.keys(buildPresets.presets)[0];
}

// 빌드가 저장한 스탯/동료/유물을 전역 활성으로 적용(있고, 현재와 다를 때만).
function applyBuildPresets(b) {
  if (b.stat && statPresets && statPresets.presets[b.stat] && statPresets.active !== b.stat) {
    statPresets.active = b.stat; saveJSON(STATP_KEY, statPresets); spApply(b.stat); if ($("sp-sel")) $("sp-sel").value = b.stat;
  }
  if (b.relic && relicPresets && relicPresets.presets[b.relic] && relicPresets.active !== b.relic) {
    relicPresets.active = b.relic; saveJSON(RELICP_KEY, relicPresets); rpApply(b.relic); if ($("rp-sel")) $("rp-sel").value = b.relic;
  }
}

// 활성 빌드를 로드: 로드아웃 프리셋 전역 적용 + 로테이션·자동채움·모드·시뮬·유물옵션 복원 + 레벨 열화 정리.
function bpApply(name) {
  const b = (buildPresets && buildPresets.presets[name]) || {};
  applyBuildPresets(b);
  tlSeq = (b.rotation || []).map((r) => ({ idx: r.idx, time: r.time }));
  tlAutoFill = b.autoFill !== false;
  tlFillSkills = b.fillSkills !== false;   // 미지정(옛 빌드)·신규 = 기본 ON
  const fs = $("tl-fillskills"); if (fs) fs.checked = tlFillSkills;
  // 최적화는 백엔드 플래그(비파괴) — tlSeq 변형 없이 토글만 복원. 미지정(옛 빌드)·신규 = 기본 ON.
  tlOptimize = b.optimize !== false;
  const opt = $("tl-optimize"); if (opt) opt.checked = tlOptimize;
  tlExpStats = (b.expStats || []).map((r) => ({ stat: r.stat, amount: r.amount }));   // 가상 스탯 실험 복원
  // 유물 실험: 저장분 있으면 복원(각성·잠재), 없으면 현재 장착 유물로 초기 채움.
  if (b.expRelics != null) tlExpRelics = b.expRelics.map((r) => ({ id: r.id, awaken: String(r.awaken), pot: (r.pot || []).map((l) => ({ type: l.type || "", grade: l.grade || "", value: l.value == null ? null : l.value })) }));
  else tlExpRelics = tlExpRelicSnapshot();
  tlExpApply = !!b.expApply;   // 토글 UI는 타임라인 헤더 + 실험실 박스에서 이 값으로 렌더됨
  syncExpToggles();   // 실험실 박스 토글 상태 동기화
  tlRenderExp();
  tlRenderExpRelic();
  tlSkillsOff = new Set(b.skillsOff || []);
  tlRenderSkillList();
  tlMode = b.mode === "manual" ? "manual" : "auto";
  // 항상 세팅(없으면 기본값) — 옛 빌드에서 이전 빌드 값이 남는 누수 방지.
  const setv = (id, val, dflt) => { const e = $(id); if (e) e.value = (val != null ? val : dflt); };
  setv("tl-simsec", b.sim, 90); setv("tl-enemies", b.enemies, ""); setv("tl-kills", b.kills, "");
  setv("tl-content", b.content, ""); setv("tl-artoffset", b.artOffset, 0);
  { const cs = $("tl-content"); if (cs && cs.options[cs.selectedIndex] && cs.options[cs.selectedIndex].disabled) cs.value = ""; }   // 미개발 컨텐츠가 저장돼 있으면 자동으로 보정
  tlApplyCompanionBuild(b.companion);   // 타임라인 동료 복원(목록 미로드면 보류→로드 후 적용)
  applyBoxOrder(b.boxOrder);            // 빌드 박스 순서 복원(없으면 기본)
  if ($("tl-preact")) $("tl-preact").checked = !!b.preAct;
  const af = $("tl-autofill"); if (af) af.checked = tlAutoFill;
  const removed = tlCleanupLevel();
  if (removed > 0) toast(`레벨로 사용 불가한 스킬 ${removed}개를 배치에서 제외했어요.`);
  fillBuildPresetSelects();
  setBuildDirty(false);
  tlSyncMode();   // 복원된 모드에 맞춰 배치표 표시
  tlRender();
  updateBuildWarn();
}

function fillBuildSel() { if (buildPresets) fillPresetSel($("tb-sel"), buildPresets); }
function fillBuildPresetSelects() {
  if (statPresets) fillPresetSel($("tb-stat"), statPresets);
  if (relicPresets) fillPresetSel($("tb-relic"), relicPresets);
}

// 저장: 직업 변동 시 경고 후 배치 삭제, 아니면 현재 상태를 활성 빌드에 커밋.
$("tb-save").addEventListener("click", () => {
  const b = buildPresets.presets[buildPresets.active];
  const newJob = currentJob();
  let wipe = false;
  if (b && b.job != null && b.job !== newJob && (b.rotation || []).length > 0) {
    const from = JOB_NAME[b.job] || b.job, to = JOB_NAME[newJob] || newJob;
    if (!confirm(`직업이 바뀌었어요(${from}→${to}). 저장하면 기존 스킬 배치가 삭제됩니다. 계속할까요?`)) return;
    wipe = true; tlSeq = [];
  }
  buildPresets.presets[buildPresets.active] = buildPayload(false);   // tlSeq 이미 반영(wipe=빈 배열)
  saveJSON(BUILDP_KEY, buildPresets);
  setBuildDirty(false);
  if (wipe) tlRender();
  updateBuildWarn(); runTimeline();
  toast(`'${buildPresets.active}' 빌드 저장됨`);
});
// 추가: 미저장이면 저장 먼저. 이름 입력 후 복사(배치 포함) vs 새로(빈 배치).
$("tb-new").addEventListener("click", () => {
  if (buildDirty) { alert("현재 빌드에 저장하지 않은 변경이 있어요. 먼저 저장하세요."); return; }
  const name = (prompt("새 빌드 이름") || "").trim();
  if (!name || (buildPresets.presets && buildPresets.presets[name])) return;
  const copy = confirm("현재 빌드의 스킬 배치까지 복사할까요?\n확인 = 복사 / 취소 = 빈 배치로 새로 생성");
  buildPresets.presets[name] = buildPayload(!copy);
  buildPresets.active = name; saveJSON(BUILDP_KEY, buildPresets);
  fillBuildSel(); bpApply(name); runTimeline();
});
$("tb-rename").addEventListener("click", () => {
  if (renamePreset(buildPresets) == null) return;
  saveJSON(BUILDP_KEY, buildPresets); fillBuildSel(); toast("빌드 이름을 변경했습니다.");
});
$("tb-del").addEventListener("click", () => {
  const names = Object.keys(buildPresets.presets);
  if (names.length <= 1) return;
  if (!canLeaveBuild()) return;
  delete buildPresets.presets[buildPresets.active];
  buildPresets.active = Object.keys(buildPresets.presets)[0];
  saveJSON(BUILDP_KEY, buildPresets); fillBuildSel(); bpApply(buildPresets.active); runTimeline();
});
$("tb-reset").addEventListener("click", () => {
  if (!confirm("현재 빌드의 배치를 모두 지울까요? (저장을 눌러야 확정됩니다)")) return;
  tlSeq = []; tlRender(); setBuildDirty(true); runTimeline();
});
$("tb-sel").addEventListener("change", () => {
  if (!canLeaveBuild()) { $("tb-sel").value = buildPresets.active; return; }
  buildPresets.active = $("tb-sel").value; saveJSON(BUILDP_KEY, buildPresets); bpApply(buildPresets.active); runTimeline();
});
// 빌드 내부 로드아웃 선택 — 전역 활성 전환 + 미저장 표시.
$("tb-stat").addEventListener("change", () => {
  const name = $("tb-stat").value;
  if (!canLeaveStat()) { $("tb-stat").value = statPresets.active; return; }
  statPresets.active = name; saveJSON(STATP_KEY, statPresets); spApply(name); if ($("sp-sel")) $("sp-sel").value = name;
  resetLab();   // 프리셋 변경 → 실험실 초기화
  setBuildDirty(true); updateBuildWarn(); runTimeline();
});
$("tb-relic").addEventListener("change", () => {
  const name = $("tb-relic").value;
  if (!canLeaveRelic()) { $("tb-relic").value = relicPresets.active; return; }
  relicPresets.active = name; saveJSON(RELICP_KEY, relicPresets); rpApply(name); if ($("rp-sel")) $("rp-sel").value = name;
  resetLab();   // 프리셋 변경(유물 새로 반영 후) → 실험실 초기화
  setBuildDirty(true); updateBuildWarn(); runTimeline();
});

// 1액션 슬롯(초). 계산 응답의 실효 슬롯(유물 공속 반영) 우선, 없으면 스탯 공속 근사.
function tlSlotSec() {
  if (tlBaseSlotMs > 0) return tlBaseSlotMs / 1000;
  const spd = parseFloat((collectStats() || {}).ATK_SPEED) || 0;
  return 1000 / (1 + spd / 100) / 1000;
}
function tlCdSec(idx) { const cd = tlCooldowns && tlCooldowns[idx]; return cd ? cd / 1000 : 0; }

// idx 스킬의 배치 자동시각(초): 직전 시각+슬롯, 그리고 같은 스킬의 앞선 캐스트+쿨 중 더 늦은 값.
// selfIdx = 재계산 대상 행(자기 자신 제외). 신규 추가면 tlSeq.length.
function tlNextTime(idx, afterTime, selfIdx) {
  const slot = tlSlotSec();
  let t = afterTime + slot;
  const cd = tlCdSec(idx);
  if (cd > 0) {
    let lastSame = -Infinity;
    for (let i = 0; i < tlSeq.length; i++) {
      if (i === selfIdx) continue;
      const r = tlSeq[i];
      if (r.idx === idx && r.time <= afterTime + 1e-6) lastSame = Math.max(lastSame, r.time);
    }
    if (lastSame > -Infinity) t = Math.max(t, lastSame + cd);   // 쿨 안 돌면 쿨 끝까지 밀기
  }
  return +t.toFixed(2);
}

// 기본 선택 스킬(평타 아닌 첫 딜/액티브). 없으면 목록 첫 항목.
function tlDefaultIdx() {
  const s = (tlSkills || []).find((x) => !x.basic) || (tlSkills || [])[0];
  return s ? s.idx : 0;
}

// 드롭다운 옵션: 평타는 자동채움이 꺼진 경우에만 직접 선택 가능.
function tlSkillOptions(sel) {
  // 스킬 미선택(빈 줄)이면 안내 플레이스홀더를 선택 상태로(목록엔 안 남는 hidden — '빈' 선택지가 아님).
  const ph = sel ? "" : `<option value="" disabled selected hidden>스킬 선택…</option>`;
  return ph + (tlSkills || [])
    .filter((s) => tlAutoFill ? !s.basic : true)
    .map((s) => `<option value="${s.idx}"${s.idx === sel ? " selected" : ""}>${s.name}</option>`).join("");
}

// 재계산: 시전 순서를 따라 각 시각이 직전+슬롯 이상이 되도록 앞으로만 민다.
// 각 원소의 time을 '희망 시각'(앵커)으로 보고, 여유가 있으면 그대로 두고 부족하면 뒤로 민다.
function tlReflow() {
  const slot = tlSlotSec();
  for (let i = 1; i < tlSeq.length; i++) {
    const min = tlSeq[i - 1].time + slot;
    if (tlSeq[i].time < min) tlSeq[i].time = +min.toFixed(3);
  }
}

function tlAddRow() {
  // 빈 줄로 추가(스킬 미선택). 스킬을 고르면 그때 쿨 반영해 시각이 확정된다 — 그 전까진 의미 없는 라인.
  tlSeq.push({ idx: 0, time: null });
  tlRender();
}

// 시각 오름차순으로 표시(위=처음 시전). 표시 순서 = 시전 순서 = tlSeq 인덱스.
// 좌측 햄버거로 드래그-드롭 순서 변경(들어갈 자리에 간격이 벌어짐).
function tlRender() {
  const tb = $("tl-table").querySelector("tbody");
  tb.innerHTML = "";
  for (let si = 0; si < tlSeq.length; si++) {
    const row = tlSeq[si];
    const tr = document.createElement("tr");
    tr.dataset.si = si;
    tr.innerHTML =
      `<td class="tl-drag" title="드래그로 순서 변경">≡</td>` +
      `<td><input type="number" class="tl-time" min="0" step="0.1" value="${row.time == null ? "" : row.time}"></td>` +
      `<td><select class="tl-skill">${tlSkillOptions(row.idx)}</select></td>` +
      `<td><button type="button" class="tl-del" title="삭제">✕</button></td>`;
    tr.querySelector(".tl-time").addEventListener("change", (e) => {
      tlSeq[si].time = parseFloat(e.target.value) || 0;
      // 직접 수정도 검증: 쿨 안 돈 시점으로 바꿔도 tlReflowCd가 정렬 후 슬롯·쿨 만족 시점으로 강제.
      tlReflowCd(); tlRender(); setBuildDirty(true); runTimeline();
    });
    tr.querySelector(".tl-skill").addEventListener("change", (e) => {
      const newIdx = parseInt(e.target.value, 10) || 0;
      tlSeq[si].idx = newIdx;
      // 스킬을 고르는 순간 시각 확정(빈 줄이면 여기서 처음 정해짐): 앞선 같은 스킬 쿨+슬롯 반영.
      const prevTime = si > 0 && tlSeq[si - 1].time != null ? tlSeq[si - 1].time : -tlSlotSec();
      const nt = tlNextTime(newIdx, prevTime, si);
      if (tlSeq[si].time == null || nt > tlSeq[si].time) tlSeq[si].time = nt;
      tlReflowCd(); tlRender(); setBuildDirty(true); runTimeline();
    });
    tr.querySelector(".tl-del").addEventListener("click", () => {
      tlSeq.splice(si, 1); tlRender(); setBuildDirty(true); runTimeline();
    });
    tlWireDragHandle(tr);
    tb.appendChild(tr);
  }
}

// ── 드래그 정렬(포인터 기반 · 집어서 올리고 다른 줄이 비켜주는 애니메이션) ──
// 잡은 줄은 커서를 따라 떠오르고(scale·그림자), 나머지 줄은 transform으로 스르륵 이동해 자리를 낸다.
// DOM 순서는 드롭 시에만 바꾸고(tlApplyMove), 드래그 중엔 transform만으로 표현(부드러운 애니메이션).
let tlDrag = null;

function tlWireDragHandle(tr) {
  const handle = tr.querySelector(".tl-drag");
  handle.style.touchAction = "none";
  handle.addEventListener("pointerdown", (e) => tlDragStart(e, tr));
}

function tlDragStart(e, tr) {
  if (e.button != null && e.button !== 0) return;
  e.preventDefault();
  const tb = tr.parentNode;
  const trs = [...tb.children];
  const rects = trs.map((r) => r.getBoundingClientRect());
  const fromIdx = trs.indexOf(tr);
  const rowH = rects[fromIdx].height;
  tlDrag = { tr, tb, trs, rects, fromIdx, rowH, startY: e.clientY, ins: fromIdx, moved: false };
  tr.classList.add("tl-lift");
  try { e.target.setPointerCapture(e.pointerId); } catch {}
  window.addEventListener("pointermove", tlDragMove);
  window.addEventListener("pointerup", tlDragDrop, { once: true });
}

function tlDragMove(e) {
  const g = tlDrag; if (!g) return;
  const dy = e.clientY - g.startY;
  if (Math.abs(dy) > 3) g.moved = true;
  // 잡은 줄은 커서를 따라(즉시), 살짝 확대.
  g.tr.style.transition = "none";
  g.tr.style.transform = `translateY(${dy}px) scale(1.015)`;
  // 삽입 위치 ins = 나머지 줄 중 원래 중점이 커서보다 위에 있는 개수.
  let ins = 0;
  for (let i = 0; i < g.trs.length; i++) {
    if (i === g.fromIdx) continue;
    if (e.clientY > g.rects[i].top + g.rects[i].height / 2) ins++;
  }
  g.ins = ins;
  // 나머지 줄을 최종 위치로 슬라이드(자리 내주기).
  let k = 0;
  for (let i = 0; i < g.trs.length; i++) {
    if (i === g.fromIdx) continue;
    const finalPos = (k < ins) ? k : k + 1;   // 잡은 줄이 ins에 들어갔을 때 이 줄의 최종 슬롯
    const shift = (finalPos - i) * g.rowH;
    g.trs[i].style.transition = "transform .16s cubic-bezier(.2,.8,.2,1)";
    g.trs[i].style.transform = shift ? `translateY(${shift}px)` : "";
    k++;
  }
}

function tlDragDrop() {
  const g = tlDrag; if (!g) return;
  window.removeEventListener("pointermove", tlDragMove);
  const commit = () => {
    if (!tlDrag) return; tlDrag = null;
    if (!g.moved || g.ins === g.fromIdx) { tlRender(); return; }   // 실제 이동 없으면 원복만
    tlApplyMove(g.fromIdx, g.ins);
  };
  if (!g.moved || g.ins === g.fromIdx) { commit(); return; }
  // 잡은 줄을 목표 슬롯으로 안착시키는 애니메이션 후 커밋.
  g.tr.style.transition = "transform .16s cubic-bezier(.2,.8,.2,1)";
  g.tr.style.transform = `translateY(${(g.ins - g.fromIdx) * g.rowH}px) scale(1)`;
  let done = false;
  const fin = () => { if (done) return; done = true; g.tr.removeEventListener("transitionend", fin); commit(); };
  g.tr.addEventListener("transitionend", fin);
  setTimeout(fin, 220);
}

// 드래그 = '순서 변경'. 인덱스 순서를 그대로 두고(정렬 금지) 시각만 앞으로 재계산(슬롯+쿨).
// 옮긴 스킬은 직전 시각을 앵커로 두면 tlReflowSeq가 슬롯/쿨만큼 밀어 확정하고, 뒤 스킬들도 필요한 만큼 밀린다.
function tlApplyMove(fromSi, target) {
  if (fromSi == null || target == null) return;
  const moved = tlSeq.splice(fromSi, 1)[0];
  tlSeq.splice(target, 0, moved);
  const prev = target > 0 ? tlSeq[target - 1] : null;
  moved.time = prev && prev.time != null ? prev.time : 0;   // 앵커 = 직전 시각(포워드 푸시가 슬롯/쿨로 확정)
  tlReflowSeq();   // ★정렬하지 않음 — 드래그한 순서를 존중해 앞으로만 민다
  tlRender();
  setBuildDirty(true);
  runTimeline();
}

// 슬롯·쿨 검증(앞으로만 밀기) — 현재 인덱스 순서를 유지한 채 각 행을 max(직전+슬롯, 같은스킬 직전+쿨)로 강제.
// 앞으로만 밀므로 결과 시각은 항상 증가(=인덱스 순서와 시각 순서 일치). 빈 줄(time=null)은 건너뜀.
function tlReflowSeq() {
  const slot = tlSlotSec();
  const lastCast = {};
  let prevT = -slot;
  for (let i = 0; i < tlSeq.length; i++) {
    const r = tlSeq[i];
    if (r.time == null) continue;
    let min = prevT + slot;
    if (r.idx) {
      const cd = tlCdSec(r.idx);
      if (cd > 0 && lastCast[r.idx] != null) min = Math.max(min, lastCast[r.idx] + cd);
    }
    if (r.time < min - 1e-6) r.time = +min.toFixed(3);
    prevT = r.time;
    if (r.idx) lastCast[r.idx] = r.time;
  }
}

// 시각 재정렬(sort) + 검증. 시각 직접수정·스킬선택처럼 '시각이 앵커'인 편집에만 사용(드래그는 순서가 앵커라 sort 금지).
function tlReflowCd() {
  tlSeq.sort((a, b) => (a.time == null ? Infinity : a.time) - (b.time == null ? Infinity : b.time));
  tlReflowSeq();
}

// 스킬 사용 최적화 토글: 백엔드 플래그로만 동작(자동=항상, 수동=이 토글). 배치표(tlSeq)는 건드리지 않아
// 저장 시각이 보존되고, 끄면 즉시 원래대로 — 비파괴가 구조적으로 보장됨.
function tlSetOptimize(on) {
  tlOptimize = on;
  setBuildDirty(true); runTimeline();
}

function tlCollectCasts() {
  const casts = [];
  for (const r of tlSeq) {
    if (r.idx) casts.push({ timeMs: Math.round((r.time || 0) * 1000), skillIdx: r.idx });
  }
  return casts;
}

// 타임라인 패널이 열려 있을 때만 재계산(유물/잠재 편집 즉시 반영용). 다른 화면이면 no-op.
function runTimelineIfActive() {
  const p = $("panel-timeline");
  if (p && !p.hidden && currentJob() === 1) runTimeline();
}
// 타임라인 계산 payload(runTimeline·변경실험 공용). stats는 collectStats() 원본.
// 유물·컨텐츠 설정 payload(타임라인 탭 UI 기준) — 타임라인/큐브 추천이 공유(같은 빌드 기준 딜).
function relicCtxPayload() {
  const p = { artifacts: activeRelic(), potentials: activePotentials() };
  const enemies = parseInt($("tl-enemies").value, 10); if (!isNaN(enemies)) p.enemies = enemies;   // 마리수 M
  const kills = parseFloat($("tl-kills").value); if (!isNaN(kills)) p.kills = kills;               // 분당 처치수 K
  const content = $("tl-content").value; if (content) p.content = content;                          // 컨텐츠 C
  p.artOffsetSec = parseFloat($("tl-artoffset").value) || 0;                                        // 유물 사전활성(초)
  if ($("tl-preact").checked) p.preActivateProc = true;                                             // 확률형 유물 사전활성
  if (tlSkillsOff.size) p.skillsOff = [...tlSkillsOff];                                              // 사용 해제 스킬
  return p;
}

// ── 실험값(실험실) 공용 적용 — 토글 ON이면 모든 엔진 계산이 실험값 기준 ──
// 실험 스탯 델타를 프리셋 스탯에 합성(주/부스탯=공격력+스탯비례 환산, 그 외=옵션 계산식). tlRunExperiment와 동일 규칙.
function applyExpStats(stats) {
  const active = tlExpStats.filter((r) => r.stat && r.amount);
  if (!active.length) return stats;
  const out = Object.assign({}, stats);
  for (const r of active) {
    const der = tlStatDerived(r.stat, r.amount, out);
    if (der != null) {
      out.ATK_FLAT = (parseFloat(out.ATK_FLAT) || 0) + der.atk;
      out.STAT_PROP_DMG = (parseFloat(out.STAT_PROP_DMG) || 0) + der.prop;
    } else {
      const cur = parseFloat(out[r.stat]) || 0;
      out[r.stat] = tlCombineStat(r.stat, cur, r.amount);
    }
  }
  return out;
}

// 실험값 적용 중이고 실험 변경분이 실제로 있으면 true(태그 표시·실험 계산 조건).
function expActive() {
  return tlExpApply && (tlExpStats.some((r) => r.stat && r.amount) || !!tlExpRelicPayload());
}

// 모든 계산 탭 공용 스탯 — 실험값 적용 중이면 실험 스탯 반영, 아니면 프리셋.
function effStats() {
  return tlExpApply ? applyExpStats(collectStats()) : collectStats();
}

// 모든 계산 탭 공용 유물/컨텐츠 — 실험값 적용 중이면 실험 유물 오버라이드 반영, 아니면 프리셋.
function effRelicCtx() {
  const p = relicCtxPayload();
  if (tlExpApply) {
    const rm = tlExpRelicPayload();
    if (rm) { p.artifacts = rm.artifacts; p.potentials = rm.potentials; }
  }
  const cb = tlCompanionBuild();   // 빌드 동료(신엔진) → 큐브·비교·추천 딜에도 반영(구 ally와 충돌 없게 tl* 필드)
  if (cb) { p.tlCompanion = cb.code; p.tlCompanionLevel = cb.level; p.tlFirstSummonMs = Math.round((cb.firstSummon || 0) * 1000); }
  return p;
}

// 시뮬 길이(ms) — 타임라인 탭 설정. 큐브 계산도 동일 값을 써 실험실과 딜 기준을 맞춘다(순환/파생 유물은 sim 길이 의존).
function tlSimMs() {
  let simSec = parseFloat($("tl-simsec").value) || 90;
  if (simSec > 600) { simSec = 600; $("tl-simsec").value = 600; }   // 시뮬 상한 600초
  if (simSec < 1) simSec = 1;
  return Math.round(simSec * 1000);
}

// 동료(v3.1): /api/supporters로 사용 가능 동료 목록을 받아 2단 드롭다운(동료/등급)에 채움. 레벨 상한=등급별.
let tlSupporters = null;
let tlSupByClass = {};   // 클래스 → [엔트리...] (등급 오름차순)
const TL_GRADE_ORDER = { cadet: 0, rare: 1, epic: 2, unique: 3, legend: 4 };
const TL_GRADE_KO = { cadet: "노멀", rare: "레어", epic: "에픽", unique: "유니크", legend: "레전더리" };
const TL_GRADE_UP = { cadet: "NORMAL", rare: "RARE", epic: "EPIC", unique: "UNIQUE", legend: "LEGENDARY" };

// 클래스 기본 한글명 = 그 클래스 엔트리 name에서 "(N차)" 제거(예 "나이트로드(4차)" → "나이트로드").
function tlClassKo(cls) {
  const s = (tlSupByClass[cls] || [])[0];
  return s ? (s.name || cls).replace(/\s*\(\d+차\)\s*$/, "") : cls;
}

async function tlLoadSupporters() {
  const sel = $("tl-companion");
  if (!sel) return;
  try { if (!tlSupporters) tlSupporters = await (await fetch("/api/supporters")).json(); }
  catch { tlSupporters = []; }
  // 클래스별 그룹핑(첫 등장 순서 유지, 각 클래스 내부는 등급 오름차순)
  tlSupByClass = {};
  const classOrder = [];
  for (const s of tlSupporters || []) {
    const cls = s.class || s.code;
    if (!tlSupByClass[cls]) { tlSupByClass[cls] = []; classOrder.push(cls); }
    tlSupByClass[cls].push(s);
  }
  for (const cls in tlSupByClass)
    tlSupByClass[cls].sort((a, b) => (TL_GRADE_ORDER[a.grade] ?? 9) - (TL_GRADE_ORDER[b.grade] ?? 9));
  // 동료(클래스) 드롭다운: 값=클래스, 라벨=기본 한글명
  const curClass = sel.dataset.cls || "";
  sel.innerHTML = `<option value="">없음</option>` +
    classOrder.map((cls) => `<option value="${cls}">${tlClassKo(cls)}</option>`).join("");
  if (curClass && tlSupByClass[curClass]) sel.value = curClass;
  tlFillCompanionGrades();
  const applied = tlRestoreCompanion();   // 빌드 로드가 목록 로드보다 앞서 보류된 동료 선택이 있으면 지금 적용
  // 목록이 첫 계산보다 늦게 도착해 동료가 뒤늦게 붙은 경우, 빌드 탭이 보이면 재계산.
  const tp = $("panel-timeline");
  if (applied && tp && !tp.hidden) runTimeline();
}

// 선택된 클래스의 등급(티어) 드롭다운 채움. 기본=최고 등급.
function tlFillCompanionGrades(preferCode) {
  const clsSel = $("tl-companion"), gSel = $("tl-companion-grade");
  if (!clsSel || !gSel) return;
  const cls = clsSel.value;
  clsSel.dataset.cls = cls;
  const list = tlSupByClass[cls] || [];
  const prev = preferCode || gSel.value;
  gSel.innerHTML = list.map((s) =>
    `<option value="${s.code}" style="color:${gradeColor(TL_GRADE_UP[s.grade])}">${TL_GRADE_KO[s.grade] || s.grade}</option>`).join("");
  gSel.disabled = list.length === 0;
  if (prev && list.some((s) => s.code === prev)) gSel.value = prev;
  else if (list.length) gSel.value = list[list.length - 1].code;   // 기본=최고 등급
  tlSyncCompanionCap();
}

function tlSyncCompanionCap() {
  const gSel = $("tl-companion-grade"), lvl = $("tl-companion-level");
  if (!gSel || !lvl) return;
  const s = (tlSupporters || []).find((x) => x.code === gSel.value);
  const cap = s ? (s.levelCap || 1) : 1;
  lvl.max = cap;
  if (!$("tl-companion") || !$("tl-companion").value) return;   // '없음'이면 레벨 손대지 않음
  const v = parseInt(lvl.value) || 0;
  if (v > cap || v < 1) lvl.value = cap;                        // 처음 선택/범위 밖 → 만렙 기본
}

function tlPayload() {
  const t = currentTargetSel(), ch = t && t.ch, sv = t && t.stage;
  const simMs = tlSimMs();
  const payload = { stats: collectStats(), mode: tlMode, chapter: ch || "", target: sv || "", simMs, ...relicCtxPayload() };
  if (tlMode === "manual") {
    payload.autoFillBasic = tlAutoFill;
    payload.fillSkills = tlFillSkills;
    payload.optimize = tlOptimize;
    payload.casts = tlCollectCasts();
  }
  const cSel = $("tl-companion"), gSel = $("tl-companion-grade");   // 동료(클래스)+등급(티어) → 코드
  if (cSel && cSel.value && gSel && gSel.value) {   // 동료(v3.1)
    payload.companion = gSel.value;
    payload.companionLevel = Math.max(1, parseInt(($("tl-companion-level") || {}).value) || 1);
    payload.firstSummonMs = Math.max(0, Math.round((parseFloat(($("tl-first-summon") || {}).value) || 0) * 1000));
  }
  return payload;
}

// ── 동료를 빌드에 저장/복원(타임라인 동료 카드 = 빌드 소유) ──
// 빌드 저장용 동료 상태(없으면 null). code=등급 셀렉트값(SUPPORTER_9_4 등), level·firstSummon(초).
function tlCompanionBuild() {
  const cSel = $("tl-companion"), gSel = $("tl-companion-grade");
  if (!cSel || !cSel.value || !gSel || !gSel.value) return null;
  return {
    code: gSel.value,
    level: Math.max(1, parseInt(($("tl-companion-level") || {}).value) || 1),
    firstSummon: Math.max(0, parseFloat(($("tl-first-summon") || {}).value) || 0),   // 초
  };
}
// 빌드 복원: 저장된 동료를 2단 셀렉트+레벨+첫소환에 반영. 목록 미로드면 보류(로드 후 자동 적용).
let tlPendingCompanion = undefined;   // undefined=미지정, null=없음, {code,...}=지정
function tlApplyCompanionBuild(c) { tlPendingCompanion = (c === undefined ? null : c); tlRestoreCompanion(); }
function tlRestoreCompanion() {
  const cSel = $("tl-companion"), gSel = $("tl-companion-grade");
  if (!cSel || !gSel || tlPendingCompanion === undefined) return false;
  if (!tlSupporters || !Object.keys(tlSupByClass).length) return false;   // 목록 로드 후 tlLoadSupporters가 재호출
  const c = tlPendingCompanion;
  let hasCompanion = false;
  if (!c || !c.code) { cSel.value = ""; cSel.dataset.cls = ""; tlFillCompanionGrades(); }
  else {
    const entry = (tlSupporters || []).find((x) => x.code === c.code);
    cSel.value = entry ? (entry.class || "") : "";
    tlFillCompanionGrades(c.code);
    const lvl = $("tl-companion-level"); if (lvl) lvl.value = Math.max(1, c.level || 1);
    const fs = $("tl-first-summon"); if (fs) fs.value = c.firstSummon != null ? c.firstSummon : 5;   // 기본 첫 소환 5초
    tlSyncCompanionCap();
    hasCompanion = !!(cSel.value && gSel.value);
  }
  tlPendingCompanion = undefined;
  return hasCompanion;
}

// ── 빌드 박스 순서(사용자 지정, 빌드마다 저장) ──
// 재배치 대상 = data-box 박스들(입력 6 + 결과 4). .tl-build-bar(빌드 헤더)만 항상 최상단 고정.
// 결과 4박스(타임라인·종합DPS·스킬별기여·동료결과)는 컨테이너 고정·내용만 갱신이라 순서가 유지됨.
const TL_BOX_KEYS = ["bar", "relic", "companion", "skillsel", "manual", "exp", "gantt", "head", "brk", "comp"];
const TL_BOX_LABEL = {
  bar: "모드 · 시뮬 · 계산", relic: "유물 계산 설정", companion: "동료 설정", skillsel: "사용 스킬",
  manual: "스킬 배치표", exp: "실험실", gantt: "타임라인", head: "종합 DPS", brk: "스킬별 기여", comp: "동료 결과",
};
function tlBuildContainer() { return $("panel-timeline"); }
// 현재 DOM의 박스 순서 스냅샷(누락 키는 뒤에 보충).
function tlBoxOrderSnapshot() {
  const c = tlBuildContainer();
  const order = c ? [...c.children].map((el) => el.dataset && el.dataset.box).filter((k) => TL_BOX_KEYS.includes(k)) : [];
  for (const k of TL_BOX_KEYS) if (!order.includes(k)) order.push(k);
  return order;
}
// 저장된 순서를 DOM에 적용 — 빌드 헤더(.tl-build-bar) 뒤부터 순서대로 재배치. 소개·경고는 헤더 앞이라 그대로 유지.
function applyBoxOrder(order) {
  const c = tlBuildContainer(); if (!c) return;
  const seq = (order || []).filter((k) => TL_BOX_KEYS.includes(k));
  for (const k of TL_BOX_KEYS) if (!seq.includes(k)) seq.push(k);   // 누락(신규 박스) 보충
  let prev = c.querySelector(":scope > .tl-build-bar");   // 고정 앵커
  if (!prev) return;
  for (const k of seq) {
    const el = c.querySelector(`:scope > [data-box="${k}"]`);
    if (!el) continue;
    if (prev.nextElementSibling !== el) c.insertBefore(el, prev.nextElementSibling);
    prev = el;
  }
}

// 박스 순서 변경 모달(드래그 앤 드롭, 배치표와 동일 인터랙션)
let boxOrderWorking = null;   // 편집 중 순서 배열
function openBoxOrder() {
  boxOrderWorking = tlBoxOrderSnapshot();
  renderBoxOrderList();
  openModal("modal-box-order");
}
function renderBoxOrderList() {
  const list = $("box-order-list"); if (!list || !boxOrderWorking) return;
  list.innerHTML = boxOrderWorking.map((k) =>
    `<div class="box-order-item" data-k="${k}"><span class="bo-grip" title="드래그로 순서 변경">⣿</span><span>${TL_BOX_LABEL[k] || k}</span></div>`).join("");
  [...list.children].forEach((row) => {
    row.addEventListener("pointerdown", (e) => boxDragStart(e, row));   // 행 아무 데나 잡아도 드래그(순서변경 전용 창)
  });
}
let boxDrag = null;
function boxDragStart(e, row) {
  if (e.button != null && e.button !== 0) return;
  e.preventDefault();
  const list = row.parentNode;
  const rows = [...list.children];
  const rects = rows.map((r) => r.getBoundingClientRect());
  const fromIdx = rows.indexOf(row);
  const rowH = rects[fromIdx].height + 8;   // 행높이 + .box-order-list gap
  boxDrag = { row, rows, rects, fromIdx, rowH, startY: e.clientY, ins: fromIdx, moved: false };
  row.classList.add("bo-lift");
  try { e.target.setPointerCapture(e.pointerId); } catch {}
  window.addEventListener("pointermove", boxDragMove);
  window.addEventListener("pointerup", boxDragDrop, { once: true });
}
function boxDragMove(e) {
  const g = boxDrag; if (!g) return;
  const dy = e.clientY - g.startY;
  if (Math.abs(dy) > 3) g.moved = true;
  g.row.style.transition = "none";
  g.row.style.transform = `translateY(${dy}px) scale(1.01)`;
  let ins = 0;
  for (let i = 0; i < g.rows.length; i++) {
    if (i === g.fromIdx) continue;
    if (e.clientY > g.rects[i].top + g.rects[i].height / 2) ins++;
  }
  g.ins = ins;
  let k = 0;
  for (let i = 0; i < g.rows.length; i++) {
    if (i === g.fromIdx) continue;
    const finalPos = (k < ins) ? k : k + 1;
    const shift = (finalPos - i) * g.rowH;
    g.rows[i].style.transition = "transform .16s cubic-bezier(.2,.8,.2,1)";
    g.rows[i].style.transform = shift ? `translateY(${shift}px)` : "";
    k++;
  }
}
function boxDragDrop() {
  const g = boxDrag; if (!g) return;
  window.removeEventListener("pointermove", boxDragMove);
  const commit = () => {
    boxDrag = null;
    if (g.moved && g.ins !== g.fromIdx) {
      const moved = boxOrderWorking.splice(g.fromIdx, 1)[0];
      boxOrderWorking.splice(g.ins, 0, moved);
    }
    renderBoxOrderList();   // 배열 기준 재렌더 → transform 초기화
  };
  if (!g.moved || g.ins === g.fromIdx) { commit(); return; }
  g.row.style.transition = "transform .16s cubic-bezier(.2,.8,.2,1)";
  g.row.style.transform = `translateY(${(g.ins - g.fromIdx) * g.rowH}px) scale(1)`;
  let done = false;
  const fin = () => { if (done) return; done = true; g.row.removeEventListener("transitionend", fin); commit(); };
  g.row.addEventListener("transitionend", fin);
  setTimeout(fin, 220);
}
if ($("tb-boxorder")) $("tb-boxorder").addEventListener("click", openBoxOrder);
if ($("box-order-apply")) $("box-order-apply").addEventListener("click", () => {
  if (boxOrderWorking) { applyBoxOrder(boxOrderWorking); setBuildDirty(true); }
  closeModal("modal-box-order");
});
if ($("box-order-reset")) $("box-order-reset").addEventListener("click", () => {
  boxOrderWorking = TL_BOX_KEYS.slice();   // 기본 순서로(적용은 '적용' 버튼으로 확정)
  renderBoxOrderList();
});

// ── 변경 실험(스탯 델타) ──
// 입력값 = '내가 얻는 양'. 옵션 계산식(체감누적/곱연산/합)으로 현재 프리셋값에 가산. 프리셋 원본 불변.
function tlCombineStat(key, cur, delta) {
  const d = statDefById(key);
  const c = d && d.combine;
  if (c === "DIMINISHING" && d.max > 0) return cur + (d.max - cur) * (delta / d.max);   // 공속150·방관100·쿨감%100
  if (c === "MULT_PCT") return ((1 + cur / 100) * (1 + delta / 100) - 1) * 100;          // 최종뎀 = 효과별 곱
  return cur + delta;                                                                     // SUM
}

// 주/부스탯(깡·%) → 파생 딜 스탯(구엔진 규칙). 해당 없으면 null.
//   주스탯: 공격력 1:1 + 스탯비례 0.01%p/개. 부스탯: 주스탯의 1/4(공격력 ×0.25 + 스탯비례 0.0025%p/개).
//   주스탯=깡×(1+주스탯%), 부스탯=깡×(1+부스탯%). 공격력분은 ATK_FLAT에 더해 타임라인이 공격력%까지 곱한다.
function tlStatDerived(stat, amt, s) {
  const mainFlat = parseFloat(s.MAIN_STAT_FLAT) || 0, mainPct = parseFloat(s.MAIN_STAT_PCT) || 0;
  const subFlat = parseFloat(s.SUB_STAT_FLAT) || 0, subPct = parseFloat(s.SUB_STAT_PCT) || 0;
  let dMain = 0, dSub = 0;
  switch (stat) {
    case "MAIN_STAT_FLAT": dMain = amt * (1 + mainPct / 100); break;
    case "MAIN_STAT_PCT":  dMain = mainFlat * (amt / 100); break;
    case "SUB_STAT_FLAT":  dSub = amt * (1 + subPct / 100); break;
    case "SUB_STAT_PCT":   dSub = subFlat * (amt / 100); break;
    default: return null;
  }
  return { atk: dMain + dSub / 4, prop: dMain / 100 + dSub * 0.0025 };
}

// 실험 드롭다운: 딜 영향 스탯. JOB/LEVEL 제외. 스탯비례데미지는 주/부스탯으로 조절하므로 직접선택 제외.
const EXP_SYNTH = { ATK_PCT_SKILL: "공격력%(스킬/유물)" };   // 합성 옵션: 프리셋 공%와 달리 별도 곱(스킬/유물 공격력처럼)
function tlExpStatOptions(sel) {
  const skip = new Set(["JOB", "LEVEL", "STAT_PROP_DMG"]);
  const ph = sel ? "" : `<option value="" disabled selected hidden>스탯 선택…</option>`;
  let opts = "";
  for (const d of (statDefs || [])) {
    if (skip.has(d.id)) continue;
    opts += `<option value="${d.id}"${d.id === sel ? " selected" : ""}>${d.name}</option>`;
    if (d.id === "ATK_PCT")   // 공격력% 바로 아래에 '공격력%(스킬/유물)' 배치
      opts += `<option value="ATK_PCT_SKILL"${sel === "ATK_PCT_SKILL" ? " selected" : ""}>${EXP_SYNTH.ATK_PCT_SKILL}</option>`;
  }
  return ph + opts;
}

function tlRenderExp() {
  const tb = $("tl-exp-table") && $("tl-exp-table").querySelector("tbody");
  if (!tb) return;
  tb.innerHTML = "";
  for (let i = 0; i < tlExpStats.length; i++) {
    const r = tlExpStats[i];
    const tr = document.createElement("tr");
    tr.innerHTML =
      `<td><select class="tl-exp-stat">${tlExpStatOptions(r.stat)}</select></td>` +
      `<td><input type="number" class="tl-exp-amt" step="any" value="${r.amount == null ? "" : r.amount}" placeholder="예: +15 / -10"></td>` +
      `<td><button type="button" class="tl-del" title="삭제">✕</button></td>`;
    tr.querySelector(".tl-exp-stat").addEventListener("change", (e) => { tlExpStats[i].stat = e.target.value; setBuildDirty(true); runTimeline(); });
    tr.querySelector(".tl-exp-amt").addEventListener("change", (e) => { tlExpStats[i].amount = parseFloat(e.target.value) || 0; setBuildDirty(true); runTimeline(); });
    tr.querySelector(".tl-del").addEventListener("click", () => { tlExpStats.splice(i, 1); tlRenderExp(); setBuildDirty(true); runTimeline(); });
    tb.appendChild(tr);
  }
}

function tlExpAddRow() { tlExpStats.push({ stat: "", amount: null }); tlRenderExp(); }

// ── 유물 실험 ──
// 현재 장착 유물로 초기 채움. 유물·각성·잠재 자유 편집. ✕=해제, 초기화=프리셋 복원.
const relicNameById = (id) => ((artifactCatalog.list || []).find((a) => a.id === id) || {}).name || id;
// 각성도(aw)에 해당하는 열린 잠재 슬롯 수 — openPotentialSlots의 myRelic 비의존 버전.
function openPotentialSlotsFor(id, aw) {
  const p = potentialData(); if (!p) return 0;
  const a = (artifactCatalog.list || []).find((x) => x.id === id); if (!a) return 0;
  const rule = (p.slotRule || {})[a.grade]; if (!rule) return 0;
  return (rule.reqAwake || []).filter((req) => aw >= req).length;
}
// 현재 프리셋 장착 유물 → 실험 행 초기 스냅샷.
function tlExpRelicSnapshot() {
  return activeRelic().map(({ id, awaken }) => ({
    id, awaken: String(awaken),
    pot: relicPotentials(id).map((l) => ({ type: l.type || "", grade: l.grade || "", value: l.value == null ? null : l.value })),
  }));
}
function tlExpRelicInit() { tlExpRelics = tlExpRelicSnapshot(); tlRenderExpRelic(); }
// 빌드 내 프리셋(스탯/동료/유물) 선택이 바뀌면 실험실 초기화 — 실험값은 이전 프리셋 기준이라 무의미.
function resetLab() {
  tlExpStats = [];
  tlExpApply = false;
  tlExpRelics = tlExpRelicSnapshot();   // 새 프리셋의 현재 장착 유물로 리셋
  syncExpToggles();
  tlRenderExp();
  tlRenderExpRelic();
  updateExpTags();
}
function tlExpRelicAddRow() { tlExpRelics.push({ id: "", awaken: "0", pot: [] }); tlRenderExpRelic(); }

// 유물 드롭다운: 카탈로그 전체(미보유도 '새로 뽑으면' 시뮬용). 딜 미반영(생존)·지원예정은 라벨.
function tlExpRelicOptions(sel) {
  const ph = sel ? "" : `<option value="" disabled selected hidden>유물 선택…</option>`;
  const opts = (artifactCatalog.list || []).map((a) => {
    const tag = RELIC_PENDING.has(a.id) ? " (지원예정)" : (relicCatById(a.id) === "survival" ? " (딜 미반영)" : "");
    return `<option value="${a.id}"${a.id === sel ? " selected" : ""}>${a.name}${tag}</option>`;
  }).join("");
  return ph + opts;
}
function tlExpRelicAwOptions(sel) {
  let opts = "";
  for (let n = 0; n <= 5; n++) opts += `<option value="${n}"${String(sel) === String(n) ? " selected" : ""}>${n}각</option>`;
  return opts;
}
// 행별 잠재 줄(각성으로 열린 슬롯 수만큼) — 유물 탭 잠재 UI와 동일 구성. row.pot을 열린 슬롯 길이에 맞춤.
function tlExpPotLinesHtml(i, row) {
  const p = potentialData(); if (!p || !row.id) return "";
  const n = openPotentialSlotsFor(row.id, parseInt(row.awaken, 10) || 0);
  if (!n) { row.pot = []; return `<div class="exp-relic-nopot tl-ro-hint">이 각성에선 열린 잠재 슬롯이 없어요.</div>`; }
  const lines = [];
  for (let k = 0; k < n; k++) lines.push(row.pot[k] || { type: "", grade: "", value: null });
  row.pot = lines;
  const dealOpts = (p.options || []).filter((o) => o.deal);
  const etcOpts = (p.options || []).filter((o) => !o.deal);   // 생존·상태이상뎀 = 표시만(딜 미반영)
  return lines.map((ln, li) => {
    const optHtml = (o) => `<option value="${o.type}"${o.type === ln.type ? " selected" : ""}>${o.name}</option>`;
    const typeSel = `<select class="tl-exp-pot-type" data-row="${i}" data-line="${li}"><option value="">옵션</option>` +
      `<optgroup label="딜">${dealOpts.map(optHtml).join("")}</optgroup>` +
      `<optgroup label="기타(딜 미반영)">${etcOpts.map(optHtml).join("")}</optgroup></select>`;
    const gradeSel = `<select class="tl-exp-pot-grade" data-row="${i}" data-line="${li}"><option value="">등급</option>` +
      (p.grades || []).map((g) => `<option value="${g.id}"${g.id === ln.grade ? " selected" : ""}>${g.name}</option>`).join("") + `</select>`;
    const rolls = potentialRolls(ln.type, ln.grade);
    const rollSel = rolls ? `<select class="tl-exp-pot-roll" data-row="${i}" data-line="${li}">` +
      rolls.map((v) => `<option value="${v}"${v === ln.value ? " selected" : ""}>${v / 10}%</option>`).join("") + `</select>` : "";
    return `<div class="exp-relic-pot-line">${typeSel}${gradeSel}${rollSel}</div>`;
  }).join("");
}
function tlRenderExpRelic() {
  const box = $("tl-exp-relic-list"); if (!box) return;
  if (!tlExpRelics.length) { box.innerHTML = `<div class="exp-relic-empty tl-ro-hint">장착 유물이 없어요. "+ 유물 추가"로 넣어보세요.</div>`; }
  else box.innerHTML = tlExpRelics.map((r, i) =>
    `<div class="exp-relic-row">` +
      `<div class="exp-relic-top">` +
        `<select class="tl-exp-relic-id">${tlExpRelicOptions(r.id)}</select>` +
        `<select class="tl-exp-relic-aw">${tlExpRelicAwOptions(r.awaken)}</select>` +
        `<button type="button" class="tl-del" data-row="${i}" title="이 유물 해제">✕</button>` +
      `</div>` +
      `<div class="exp-relic-pots">${tlExpPotLinesHtml(i, r)}</div>` +
    `</div>`).join("");
  // 유물 변경: pot 초기화(새 유물의 열린 슬롯에서 다시 채움)
  box.querySelectorAll(".exp-relic-row").forEach((row, i) => {
    // 유물 변경 시 잠재는 유지(같은 잠재로 다른 유물 테스트 가능) — 슬롯 수는 tlExpPotLinesHtml이 새 유물에 맞춰 자동 조정.
    row.querySelector(".tl-exp-relic-id").addEventListener("change", (e) => { tlExpRelics[i].id = e.target.value; tlRenderExpRelic(); setBuildDirty(true); runTimeline(); });
    row.querySelector(".tl-exp-relic-aw").addEventListener("change", (e) => { tlExpRelics[i].awaken = e.target.value; tlRenderExpRelic(); setBuildDirty(true); runTimeline(); });
    row.querySelector(".tl-del").addEventListener("click", () => { tlExpRelics.splice(i, 1); tlRenderExpRelic(); setBuildDirty(true); runTimeline(); });
  });
  const potChange = (sel, patch) => {
    const i = +sel.dataset.row, li = +sel.dataset.line;
    tlExpRelics[i].pot[li] = Object.assign({}, tlExpRelics[i].pot[li], patch);
    tlRenderExpRelic(); setBuildDirty(true); runTimeline();
  };
  box.querySelectorAll("select.tl-exp-pot-type").forEach((sel) =>
    sel.addEventListener("change", () => potChange(sel, { type: sel.value, value: null })));
  box.querySelectorAll("select.tl-exp-pot-grade").forEach((sel) =>
    sel.addEventListener("change", () => {
      const cur = tlExpRelics[+sel.dataset.row].pot[+sel.dataset.line] || {};
      const rolls = potentialRolls(cur.type, sel.value);   // 미스틱이면 최저 롤 기본값(백엔드와 일치)
      potChange(sel, { grade: sel.value, value: rolls ? rolls[0] : null });
    }));
  box.querySelectorAll("select.tl-exp-pot-roll").forEach((sel) =>
    sel.addEventListener("change", () => potChange(sel, { value: parseFloat(sel.value) })));
}

// 유물 실험 → {artifacts, potentials, applied[]}. 현재 프리셋과 동일하면 null(변경 없음).
// 각 행을 그대로 개별 항목으로 변환(id-키 병합 없음) — 같은 유물을 여러 행에 넣어도 각각 독립 반영.
// 신엔진은 artifacts 배열의 각 항목을 개별 계산하므로, 중복/자유 구성이 그대로 딜에 반영된다(실험용 자유도).
const _relicNorm = (list) => JSON.stringify((list || []).filter((r) => r.id).map((r) =>
  ({ id: r.id, aw: parseInt(r.awaken, 10) || 0, pot: (r.pot || []).map((l) => [l.type || "", l.grade || "", l.value == null ? "" : l.value]) })));
function tlExpRelicPayload() {
  const rows = tlExpRelics.filter((r) => r.id);
  const baseSnap = tlExpRelicSnapshot();
  if (_relicNorm(rows) === _relicNorm(baseSnap)) return null;   // 변경 없음 → 실험 안 함
  const artifacts = rows.map((r) => ({ id: r.id, awaken: parseInt(r.awaken, 10) || 0 }));
  const potentials = [];
  for (const r of rows) {                                       // 행별 개별 잠재(열린 슬롯 수만큼, 미선택 줄 제외)
    const n = openPotentialSlotsFor(r.id, parseInt(r.awaken, 10) || 0);
    for (const ln of (r.pot || []).slice(0, n)) {
      if (!ln.type || !ln.grade) continue;
      const row = { type: ln.type, grade: ln.grade };
      if (ln.value != null) row.value = ln.value;               // 미스틱 롤(천분율)
      potentials.push(row);
    }
  }
  return { artifacts, potentials, applied: tlExpRelicDiffNotes(baseSnap, rows) };
}
// 실험 세트 ↔ 기본 스냅샷 비교 → diff 설명 목록. 같은 유물 중복도 개별 표시(baseline을 소비 방식으로 매칭).
function tlExpRelicDiffNotes(base, rows) {
  const out = [];
  const remaining = base.map((b) => ({ b, used: false }));
  const potStr = (list) => JSON.stringify((list || []).map((l) => [l.type || "", l.grade || "", l.value == null ? "" : l.value]));
  for (const r of rows) {
    const aw = parseInt(r.awaken, 10) || 0;
    const bi = remaining.findIndex((x) => !x.used && x.b.id === r.id);
    if (bi < 0) { out.push({ relic: true, name: relicNameById(r.id), note: `장착(${aw}각)` }); continue; }
    remaining[bi].used = true;
    const b = remaining[bi].b, parts = [];
    if ((parseInt(b.awaken, 10) || 0) !== aw) parts.push(`${b.awaken}각 → ${aw}각`);
    if (potStr(b.pot) !== potStr(r.pot)) parts.push("잠재 변경");
    if (parts.length) out.push({ relic: true, name: relicNameById(r.id), note: parts.join(" · ") });
  }
  for (const x of remaining) if (!x.used) out.push({ relic: true, name: relicNameById(x.b.id), note: "해제" });
  return out;
}

// 변경 실험 실행: 유효 델타가 있으면 변경 stats로 재계산해 기본과 diff 렌더. baseD = 방금 기본 계산 결과.
async function tlRunExperiment(baseD) {
  const box = $("tl-exp-result");
  if (!box) return null;
  const active = tlExpStats.filter((r) => r.stat && r.amount);
  const relicMod = tlExpRelicPayload();   // 유물 변경(없으면 null)
  if (!active.length && !relicMod) { box.innerHTML = ""; return null; }
  const mod = tlPayload();
  const stats = Object.assign({}, mod.stats);
  const applied = [];
  for (const r of active) {
    const d = statDefById(r.stat), nm = d ? d.name : (EXP_SYNTH[r.stat] || r.stat);
    // ★주/부스탯은 타임라인이 직접 안 씀 → 구엔진 규칙대로 공격력(ATK_FLAT)+스탯비례(STAT_PROP)로 환산.
    const der = tlStatDerived(r.stat, r.amount, stats);
    if (der != null) {
      stats.ATK_FLAT = (parseFloat(stats.ATK_FLAT) || 0) + der.atk;
      stats.STAT_PROP_DMG = (parseFloat(stats.STAT_PROP_DMG) || 0) + der.prop;
      applied.push({ name: nm, amount: r.amount, note: `공격력 ${der.atk >= 0 ? "+" : ""}${num1s(der.atk)} · 스탯비례 ${der.prop >= 0 ? "+" : ""}${num1s(der.prop)}%p` });
    } else {
      const cur = parseFloat(stats[r.stat]) || 0;
      const nv = tlCombineStat(r.stat, cur, r.amount);
      stats[r.stat] = nv;
      applied.push({ name: nm, amount: r.amount, from: cur, to: nv });
    }
  }
  mod.stats = stats;
  if (relicMod) {   // 유물 변경본으로 교체 + diff 목록에 추가
    mod.artifacts = relicMod.artifacts;
    mod.potentials = relicMod.potentials;
    applied.push(...relicMod.applied);
  }
  try {
    const d2 = await postJson("/api/nl-timeline", mod);
    tlRenderExpDiff(box, baseD, d2, applied);
    return d2;
  } catch (e) { box.innerHTML = `<div class="tl-empty">실험 계산 실패: ${(e && e.message) || ""}</div>`; return null; }
}

function tlRenderExpDiff(box, baseD, modD, applied) {
  // 동료가 소환됐으면 동료 포함 DPS로 비교(dpsWithCompanion), 아니면 플레이어 DPS.
  const dpsOf = (d) => (d && d.dpsWithCompanion != null ? +d.dpsWithCompanion : (+(d && d.dps) || 0));
  const b = dpsOf(baseD), m = dpsOf(modD), diff = m - b;
  const pct = b > 0 ? (diff / b * 100) : 0;
  const sign = diff >= 0 ? "+" : "−";
  const cls = diff >= 0 ? "exp-up" : "exp-down";
  const rows = applied.map((a) => {
    if (a.relic) return `<li class="exp-relic"><b>${a.name}</b> <span class="exp-sub">${a.note}</span></li>`;
    const detail = a.note != null ? a.note : `${num1s(a.from)} → ${num1s(a.to)}`;
    return `<li><b>${a.name}</b> ${a.amount >= 0 ? "+" : ""}${a.amount} <span class="exp-sub">(${detail})</span></li>`;
  }).join("");
  // 딜에 아예 안 들어간(간트에도 없는) 유물 = 완전 미반영. 조건부 유물(보스 전용·처치수 필요 등) 안내.
  const shownNames = new Set((modD.buffs || []).map((x) => x.label));
  const missing = new Map();
  for (const s of (modD.artifactSkips || [])) if (!shownNames.has(s.name) && !missing.has(s.name)) missing.set(s.name, s.reason);
  const skipHtml = missing.size
    ? `<ul class="exp-skips">${[...missing].map(([n, r]) => `<li class="exp-skip">⚠ <b>${n}</b> <span class="exp-sub">${r} — 딜 미반영</span></li>`).join("")}</ul>`
    : "";
  box.innerHTML =
    `<div class="exp-box">` +
    `<div class="exp-head">실험실 결과</div>` +
    `<ul class="exp-list">${rows}</ul>` +
    skipHtml +
    `<div class="exp-diff ${cls}">` +
    `<div class="exp-line"><span>기본 DPS</span><b>${koreanUnit(b) || fmt(Math.round(b))}/s</b></div>` +
    `<div class="exp-line"><span>변경 DPS</span><b>${koreanUnit(m) || fmt(Math.round(m))}/s</b></div>` +
    `<div class="exp-line exp-delta"><span>차이</span><b>${sign}${koreanUnit(Math.abs(diff)) || fmt(Math.round(Math.abs(diff)))}/s (${sign}${Math.abs(pct).toFixed(2)}%)</b></div>` +
    `</div></div>`;
}

async function runTimeline() {
  if (tlBooting) return;   // init 초기화 중 중복 호출 억제(첫 renderTimeline이 tlBooting 해제)
  if (currentJob() !== 1) return;
  updateBuildWarn();
  if (buildJobMismatch()) return;   // 빌드 직업 ≠ 현재 직업 → 계산 차단(안내만)
  $("tl-manual").hidden = tlMode !== "manual";   // 정상 직업이면 배치표 복원(직전 차단으로 숨겨졌을 수 있음)
  const payload = tlPayload();
  const btn = $("tl-run"); btn.disabled = true; btn.textContent = "계산중…";
  try {
    const d = await postJson("/api/nl-timeline", payload);
    const d2 = await tlRunExperiment(d);                   // 딜 비교 diff 렌더 + 변경본 반환(없으면 null)
    tlExpDiffActive = !!d2;                                // 실험 변경분 유무 → 타임라인 헤더 토글 노출 조건
    const shown = (tlExpApply && d2) ? d2 : d;             // '실험값 전체 적용' ON이면 아래 표시를 변경본으로
    if (shown.cooldowns) tlCooldowns = shown.cooldowns;    // 배치 자동시각용 실효 쿨 캐시
    if (shown.baseSlotMs > 0) tlBaseSlotMs = shown.baseSlotMs;
    renderTimelineResult(shown);
  } catch (e) {
    tlClearResult(`<div class="tl-empty">계산에 실패했어요. ${(e && e.message) || ""}</div>`);
  } finally {
    btn.disabled = false; btn.textContent = "계산";
    updateExpTags();   // 실험값 편집/토글에 따라 '실험실' 태그 갱신
  }
}

// 결과 4박스 비우기 + 메시지(빈/에러/직업불일치)는 첫 박스(타임라인)에 표시.
function tlClearResult(msgHtml) {
  const g = $("tl-box-gantt"); if (g) g.innerHTML = msgHtml || "";
  for (const id of ["tl-box-head", "tl-box-brk", "tl-box-comp"]) { const e = $(id); if (e) e.innerHTML = ""; }
}

// 스킬별 기여 박스 렌더 — 동료 포함 토글(끄면 본체만, 켜면 동료 전체 딜을 한 조각으로 추가). 비중 = 전체 시뮬 기준(조각 합/전체).
function tlRenderBrk(d) {
  const box = $("tl-box-brk"); if (!box) return;
  const compTotal = (d && d.companion && +d.companion.total) || 0;
  const hasComp = compTotal > 0;
  const items = (d.perSkill || []).map((s) => ({ name: s.name, damage: s.damage, per1: s.per1, casts: s.casts }));
  if (hasComp && tlBrkShowComp) items.push({ name: "동료 (전체)", damage: compTotal, companion: true });   // 동료 상세는 동료 박스에서
  const toggle = hasComp
    ? `<label class="tl-brk-toggle" title="켜면 동료가 주는 전체 딜을 비중에 포함(동료 스킬 상세는 동료 박스)">` +
      `<span class="tl-switch"><input type="checkbox" id="tl-brk-comp"${tlBrkShowComp ? " checked" : ""}><span class="tl-switch-track"></span></span>` +
      `<span class="tl-brk-toggle-lbl">동료 포함</span></label>`
    : "";
  box.innerHTML = `<div class="card"><div class="dash-head"><h2>스킬별 기여</h2>${toggle}</div>${tlDonutHtml(items)}</div>`;
  const cb = $("tl-brk-comp");
  if (cb) cb.addEventListener("change", () => { tlBrkShowComp = cb.checked; tlRenderBrk(tlLastData); });
  tlWireDonut();
}

function renderTimelineResult(d) {
  tlLastData = d;   // DPS 곡선 토글/곡률 조정 시 재계산 없이 다시 그리기용
  const secs = Math.round(d.simMs / 1000);
  const t = currentTargetSel();
  const targetLabel = t ? (d.boss ? `${t.ch}챕터 보스` : `${t.ch}-${String(t.stage).replace("stage:", "")} 일반몹`) : "대상 미설정";
  const chips =
    `<span class="tl-hl-chip">🎯 ${targetLabel}</span>` +
    `<span class="tl-hl-chip">${d.mode === "manual" ? "✍️ 수동" : "⚙️ 자동"}</span>` +
    `<span class="tl-hl-chip">⏱ ${secs}초 측정</span>` +
    (d.monsterDef > 0 ? `<span class="tl-hl-chip">🛡 방어력 ${fmt(d.monsterDef)}</span>` : "");
  const tile = (label, val, cls, per) =>
    `<div class="tl-hl-tile${cls ? " " + cls : ""}"><div class="tl-hl-label">${label}</div>` +
    `<div class="tl-hl-num">${koreanUnit(val) || fmt(Math.round(val || 0))}${per ? `<span class="tl-hl-per">${per}</span>` : ""}</div>` +
    `<div class="tl-hl-full">${fmt(Math.round(val || 0))}</div></div>`;
  const hasComp = !!d.companion;
  const tiles = hasComp
    ? tile("플레이어 DPS", d.dps, "primary", "/초") + tile("동료 포함 DPS", d.dpsWithCompanion, "primary", "/초") + tile("전체 데미지", d.totalWithCompanion)
    : tile("종합 DPS", d.dps, "primary", "/초") + tile("전체 데미지", d.total);
  const head =
    `<div class="tl-headline">` +
    `<div class="tl-hl-ctx">${chips}</div>` +
    `<div class="tl-hl-tiles">${tiles}</div>` +
    `<div class="tl-hl-note">세팅·배치 간 상대 비교용 (이상적 상한 기준)${hasComp ? " · 플레이어 DPS엔 동료가 주는 버프가 반영됨" : ""}</div>` +
    `</div>`;
  // 타임라인 카드: 간트 + (미반영 유물 있을 때만 카드 내부 하단 서브섹션)
  const dpsCtrl = `<div class="tl-dps-ctrl">` +
    `<label class="tl-switch" title="간트 위에 순간 DPS 곡선 표시"><input type="checkbox" id="tl-dps-on"${tlShowDps ? " checked" : ""}><span class="tl-switch-track"></span></label>` +
    `<span class="tl-dps-lbl">DPS 곡선</span>` +
    `<input type="range" id="tl-dps-agg" min="0" max="100" value="${tlDpsAgg}" title="오른쪽으로 갈수록 구간을 넓게 뭉뚱그림"${tlShowDps ? "" : " disabled"}>` +
    `</div>`;
  // 실험 diff가 있을 때만: 실험값 전체 적용 토글을 타임라인 헤더에(DPS 컨트롤 옆).
  const expCtrl = tlExpDiffActive
    ? `<label class="tl-exp-apply-inline" title="켜면 이 타임라인·DPS·딜 라인을 실험 변경값 기준으로 표시(딜 비교는 항상 표시). 끄면 원본.">` +
      `<span class="tl-switch"><input type="checkbox" id="tl-exp-apply"${tlExpApply ? " checked" : ""}><span class="tl-switch-track"></span></span>` +
      `<span class="tl-exp-apply-lbl">실험값 적용</span></label>`
    : "";
  const gantt = `<div class="card"><div class="dash-head"><h2>타임라인</h2><div class="tl-head-ctrls">${expCtrl}${dpsCtrl}</div></div>` +
    `<div class="dash-sub tl-legend">액션(▮ 스킬 · 평타) · <b class="tl-lg-dot">지속딜/패시브딜</b> · <b class="tl-lg-buff">버프</b> · <b class="tl-lg-art">유물</b></div>${tlGantt(d)}${tlArtStatus(d)}</div>`;
  const compTotal = (d.companion && +d.companion.total) || 0;
  const wholeTotal = (+d.total || 0) + compTotal;   // 전체 시뮬 총딜(플레이어+동료) — 비중 분모
  // 결과 4박스에 분산 렌더(컨테이너는 고정 → 사용자 지정 순서 유지). 내용만 갱신.
  $("tl-box-gantt").innerHTML = gantt;
  $("tl-box-head").innerHTML = head;
  tlRenderBrk(d);   // 스킬별 기여(동료 포함 토글 · 도넛 · 호버 연동)
  $("tl-box-comp").innerHTML = d.companion ? tlCompanionCard(d.companion, wholeTotal) : "";
  const expApplyEl = $("tl-exp-apply");   // 매 렌더마다 새로 생성 → 재바인딩
  if (expApplyEl) expApplyEl.addEventListener("change", () => setExpApply(expApplyEl.checked));   // 실험실 박스 토글과 공유
  tlWireZoom();    // Ctrl+휠 확대/축소 리스너(1회)
  tlWireGuide();   // 호버 시간 가이드선(1회)
  tlWireDpsControls();   // DPS 토글·집계 슬라이더(1회, 위임)
  tlGuideMs = d.simMs || 0;
  tlMaxZoom = Math.min(200, Math.max(8, Math.round((d.simMs / 1000) / 6)));   // 시뮬 길수록 더 확대(600초→100배)
  if (tlZoom > tlMaxZoom) tlZoom = tlMaxZoom;
  applyTlZoom();   // 재렌더된 간트에 현재 배율 재적용
}

// 동료(v3.1) 결과 카드 — 스킬별 딜 막대. 비중=전체 시뮬 기준(동료딜/전체딜). 막대는 동료 스킬 간 상대(최대=꽉참).
function tlCompanionCard(c, wholeTotal) {
  const list = (c.perSkill || []);
  const maxDmg = list.reduce((m, s) => Math.max(m, s.damage || 0), 0) || 1;
  const W = wholeTotal || list.reduce((t, s) => t + (s.damage || 0), 0) || (c.total || 0);
  const rows = list.map((s) => {
    const pct = W > 0 ? (s.damage || 0) / W * 100 : 0;                       // 전체 시뮬에서 이 동료 스킬의 비중
    const barW = Math.max(2, Math.min(100, (s.damage || 0) / maxDmg * 100)); // 막대=동료 스킬 간 상대(시각용)
    const metaBits = [];   // 수치(한줄·회) 먼저, 비율은 맨 오른쪽
    if (s.per1 > 0) metaBits.push(`${tlBig(s.per1)}/줄`);
    if (s.casts > 0) metaBits.push(`${s.casts}회`);
    const meta = metaBits.length ? `<span class="tl-comp-meta">${metaBits.join(" · ")}</span>` : "";
    return `<div class="tl-comp-row"><span class="tl-comp-name">${escapeHtml(s.name)}</span>` +
      `<span class="tl-comp-track"><span class="tl-comp-fill" style="width:${barW}%"></span></span>` +
      `<span class="tl-comp-val">${meta}<b class="tl-comp-pct">${num1s(pct)}%</b></span></div>`;
  }).join("");
  const compShare = W > 0 ? (c.total || 0) / W * 100 : 0;
  return `<div class="card tl-comp-card"><div class="dash-head"><h2>동료 · ${escapeHtml(c.name)}</h2>` +
    `<span class="dash-sub">Lv${c.level} · 소환 ${c.summonCount}회 · 전체 딜 비중 ${num1s(compShare)}% · DPS ${koreanUnit(c.dps) || fmt(Math.round(c.dps || 0))}/s</span></div>` +
    `<div class="tl-comp-list">${rows}</div>` +
    `<div class="tl-hl-note">비중은 전체 시뮬 기준(플레이어+동료 총딜 대비). 동료는 소환창(30초) 동안 딜 + 플레이어 버프.</div></div>`;
}

// 간트 위 호버 시 커서 위치에 세로 가이드선 + 그 지점 시간(초) 표시. 평소엔 숨김.
function tlWireGuide() {
  const box = $("tl-box-gantt");
  if (!box || box.dataset.guideWired) return;
  box.dataset.guideWired = "1";
  const hide = () => { const g = box.querySelector(".tl-guide"); if (g) g.hidden = true; };
  box.addEventListener("mousemove", (e) => {
    const inner = e.target.closest && e.target.closest(".tl-gantt-inner");
    if (!inner) return hide();
    const guide = inner.querySelector(".tl-guide");
    if (!guide) return;
    const pad = 4, contentW = inner.clientWidth - pad * 2;
    if (contentW <= 0) return;
    const x = e.clientX - inner.getBoundingClientRect().left;   // inner(패딩박스) 기준 px
    const cx = Math.max(0, Math.min(contentW, x - pad));        // 트랙 콘텐츠 기준
    guide.dataset.frac = cx / contentW;   // 시각 비율로 저장 → 줌 시 폭 변해도 재계산(applyTlZoom)
    guide.style.left = (cx + pad) + "px";
    const tms = cx / contentW * tlGuideMs;
    let label = (tms / 1000).toFixed(1) + "s";
    if (tlShowDps && tlDpsShownPts && tlDpsShownPts.length) {   // 곡선 위 임의 시각의 DPS(표시 점 사이 선형 보간)
      const arr = tlDpsShownPts;
      let dv;
      if (tms <= arr[0].t) dv = arr[0].dps;
      else if (tms >= arr[arr.length - 1].t) dv = arr[arr.length - 1].dps;
      else {
        let lo = 0, hi = arr.length - 1;
        while (hi - lo > 1) { const mid = (lo + hi) >> 1; if (arr[mid].t <= tms) lo = mid; else hi = mid; }
        const a = arr[lo], b = arr[hi], f = (tms - a.t) / ((b.t - a.t) || 1);
        dv = a.dps + (b.dps - a.dps) * f;
      }
      if (dv != null) label += " · " + (koreanUnit(dv) || fmt(Math.round(dv))) + "/s";
    }
    const chip = guide.querySelector(".tl-guide-time");
    chip.textContent = label;
    // 보이는 뷰포트(스크롤 컨테이너) 오른쪽 끝 근처면 태그를 선 왼쪽으로 — 콘텐츠가 더 오른쪽으로 스크롤 가능해도 넘침 방지.
    const scroll = inner.parentElement;   // .tl-gantt-scroll
    const visRight = scroll ? scroll.scrollLeft + scroll.clientWidth : contentW + pad;
    chip.classList.toggle("flip", (cx + pad) > visRight - 90);
    guide.hidden = false;
  });
  box.addEventListener("mouseleave", hide);
}

// 스킬별 기여 도넛(좌: 차트, 우: 스킬명·비중). perSkill.damage 기준.
function tlDonutHtml(perSkill) {
  const items = (perSkill || []).filter((s) => (s.damage || 0) > 0).sort((a, b) => (b.damage || 0) - (a.damage || 0));
  const total = items.reduce((t, s) => t + (s.damage || 0), 0);
  if (!items.length || total <= 0) return `<div class="tl-empty">표시할 딜이 없어요.</div>`;
  const S = 180, cx = S / 2, cy = S / 2, R = 84, r = 50;
  const g = items.map((s, i) => ({ s, color: PIE_COLORS[i % PIE_COLORS.length], frac: s.damage / total }));
  const top = g[0];
  const shortName = (nm) => (nm && nm.length > 7) ? nm.slice(0, 6) + "…" : (nm || "");
  tlDonutData = { slices: g.map((o) => ({ name: o.s.name, frac: o.frac })) };   // 호버 갱신용
  let a = 0, paths = "";
  g.forEach((o, i) => {
    const a1 = (i === g.length - 1) ? 360 : a + o.frac * 360;
    const path = (g.length === 1) ? ringPath(cx, cy, R, r, 0.01, 359.99) : ringPath(cx, cy, R, r, a, a1);
    paths += `<path d="${path}" fill="${o.color}" class="pie-seg" data-i="${i}"><title>${escapeHtml(o.s.name + " · " + num1s(o.frac * 100) + "%")}</title></path>`;
    a = a1;
  });
  const chart = `<div class="tl-sk-chart"><svg viewBox="0 0 ${S} ${S}" class="donut-svg" role="img" aria-label="스킬 딜 비중">` +
    `<g class="donut-ring">${paths}</g>` +
    `<text x="${cx}" y="${cy - 6}" class="donut-center-top">${escapeHtml(shortName(top.s.name))}</text>` +
    `<text x="${cx}" y="${cy + 14}" class="donut-center-val">${num1s(top.frac * 100)}%</text></svg></div>`;
  const legend = `<div class="tl-sk-legend">${g.map((o, i) => {
    const bits = [];   // 동료 집계 슬라이스(companion)는 상세 없음 · 도트성(casts 0)은 회 생략
    if (!o.s.companion && o.s.per1 > 0) bits.push(`${tlBig(o.s.per1)}/줄`);
    if (!o.s.companion && o.s.casts > 0) bits.push(`${o.s.casts}회`);
    const per = bits.length ? `<span class="tl-sk-per">${bits.join(" · ")}</span>` : "";
    return `<div class="tl-sk-row" data-i="${i}"><span class="tl-sk-dot" style="background:${o.color}"></span>` +
      `<span class="tl-sk-name">${escapeHtml(o.s.name)}</span>` +
      `<span class="tl-sk-meta">${per}<span class="tl-sk-pct">${num1s(o.frac * 100)}%</span></span></div>`;   // 수치→비율 순, 우측 정렬
  }).join("")}</div>`;
  return `<div class="tl-sk-body">${chart}${legend}</div>`;
}

// 스킬 도넛 호버 연동: 조각/목록 호버 시 가운데 텍스트 갱신 + 나머지 조각 흐림 + 목록 강조. innerHTML 세팅 후 호출.
function tlWireDonut() {
  const root = $("tl-box-brk"); if (!root) return;   // 도넛 = 스킬별 기여 박스
  const chart = root.querySelector(".tl-sk-chart"); if (!chart) return;
  const segs = chart.querySelectorAll(".pie-seg");
  const rows = root.querySelectorAll(".tl-sk-legend .tl-sk-row");
  const ctrName = chart.querySelector(".donut-center-top"), ctrVal = chart.querySelector(".donut-center-val");
  const slices = tlDonutData.slices || [];
  if (!slices.length) return;
  const shortName = (nm) => (nm && nm.length > 7) ? nm.slice(0, 6) + "…" : (nm || "");
  const setCenter = (s) => { if (ctrName) ctrName.textContent = shortName(s.name); if (ctrVal) ctrVal.textContent = num1s(s.frac * 100) + "%"; };
  const top = slices[0];
  const hi = (i, on) => {
    segs.forEach((p) => p.classList.toggle("dim", on && +p.dataset.i !== i));
    rows.forEach((rw) => rw.classList.toggle("active", on && +rw.dataset.i === i));
    setCenter(on && slices[i] ? slices[i] : top);
  };
  segs.forEach((p) => { p.addEventListener("mouseenter", () => hi(+p.dataset.i, true)); p.addEventListener("mouseleave", () => hi(0, false)); });
  rows.forEach((rw) => { rw.addEventListener("mouseenter", () => hi(+rw.dataset.i, true)); rw.addEventListener("mouseleave", () => hi(0, false)); });
}

/** 미반영 유물 안내: 반영 안 된 유물이 있을 때만(평소엔 노이즈라 숨김). 타임라인 카드 내부 하단 서브섹션. 이름 + 사유 인라인. */
function tlArtStatus(d) {
  const skips = d.artifactSkips || [];
  if (!skips.length) return "";
  const seen = new Set(), rows = [];
  for (const s of skips) {   // 같은 유물의 여러 effect가 같은 사유(친화 문구 기준)면 1줄로 합침
    const k = s.name + "|" + tlFriendlyReason(s.reason);
    if (seen.has(k)) continue;
    seen.add(k); rows.push(s);
  }
  if (!rows.length) return "";
  return `<div class="tl-unreflected"><div class="tl-unreflected-head">미반영 유물 · ${rows.length}건</div>` +
    `<div class="tl-art-skips">${rows.map((s) =>
      `<div class="tl-art-skip"><span class="tl-art-sname">${s.name}</span><span class="tl-art-sreason">${tlFriendlyReason(s.reason)}</span></div>`).join("")}</div></div>`;
}

// 유물 미반영 내부 사유(raw) → 사용자 친화 문구.
function tlFriendlyReason(raw) {
  const r = String(raw || "");
  if (/companion|동료/.test(r)) return "동료가 쓰는 유물이라 타임라인엔 아직 반영되지 않아요(동료 엔진 개발 중)";
  if (/content/.test(r)) return "현재 컨텐츠에선 효과가 없는 유물이에요";
  if (/마리수 M/.test(r)) return "‘마리수(적)’를 입력하면 반영돼요";
  if (/처치수 K/.test(r)) return "‘분당 처치수’를 입력하면 반영돼요";
  if (/boss 전용/.test(r)) return "보스에게만 적용되는 효과예요";
  if (/비보스 전용/.test(r)) return "일반몹에게만 적용되는 효과예요";
  if (/생존|유틸/.test(r)) return "생존·유틸 유물이라 딜에는 반영되지 않아요";
  // defer(상태의존)·트리거 미모델·대상상태 require 등 아직 설계하지 않은 발동 케이스는 전부 여기로.
  return "아직 지원하지 않는 발동 방식이에요";
}

function tlGantt(d) {
  const T = d.simMs || 1, pct = (ms) => (ms / T * 100);
  // 그룹핑: 유물=인스턴스 인덱스(inst) 키로(중복 유물 오염방지), 없으면 이름. 스킬=이름. 상시 스킬버프는 숨김(클러터).
  const skill = {}, art = {};
  const pushSeg = (map, key, label, b) => { (map[key] = map[key] || { label, segs: [] }).segs.push(b); };
  for (const b of d.buffs) {
    if (b.artifact) pushSeg(art, (b.inst != null && b.inst >= 0) ? "i" + b.inst : b.label, b.label, b);
    else if (!b.always) pushSeg(skill, b.label, b.label, b);
  }
  let act = "";
  for (const a of d.actions) {
    const l = pct(a.t).toFixed(3);
    const dmg = a.dmg > 0 ? " · " + koreanUnit(a.dmg) : "";
    act += a.basic
      ? `<span class="tl-tick" style="left:${l}%" data-tip="${a.name} · ${(a.t / 1000).toFixed(2)}s${dmg}"></span>`
      : `<span class="tl-cast" style="left:${l}%" data-tip="${a.name} · ${(a.t / 1000).toFixed(2)}s${dmg}"></span>`;
  }
  const lanes = [{ label: "액션", labelCls: "", trackCls: "tl-track tl-track-sharp", tall: true, bars: act }];
  tlDealLines(d.dealLines, pct, lanes);   // 도트/주기/프록 딜: 옅은 막대(활성 구간)+진한 틱(실제 타격 시각)
  tlBuffLanes(skill, pct, false, lanes, T);
  tlBuffLanes(art, pct, true, lanes, T);
  tlCompanionLanes(d.companion, pct, lanes, T);   // 동료(v3.1): 소환창·딜·버프 lane(하단)

  const step = T <= 30000 ? 5000 : T <= 120000 ? 10000 : 20000;
  // 눈금(분기): 0부터 step 간격 + 끝(T)은 항상. step에 딱 안 떨어지거나 T<step여도 첫(0)·끝(T)은 반드시 찍는다.
  const ticks = [];
  for (let t = 0; t < T; t += step) ticks.push(t);
  if (T - ticks[ticks.length - 1] < step * 0.35) ticks.pop();   // 끝 눈금과 너무 붙는 마지막 중간눈금은 제거
  ticks.push(T);
  let axis = "";
  for (const t of ticks) {   // 양끝 라벨은 좌/우 정렬로(컨테이너 밖으로 안 삐져나가게)
    const p = pct(t), anchor = t === 0 ? "0" : p >= 99.9 ? "-100%" : "-50%";
    axis += `<span style="left:${p.toFixed(3)}%;transform:translateX(${anchor})">${+(t / 1000).toFixed(2)}s</span>`;
  }

  // 라벨 열(스크롤 밖 고정) + 트랙 열(가로 스크롤). 행 높이는 tall 여부로 라벨/트랙 짝맞춤.
  const labelCol = lanes.map((L) =>
    `<div class="tl-glabel${L.tall ? " tl-glabel-tall" : ""} ${L.labelCls}">${L.label}</div>`).join("") +
    `<div class="tl-glabel tl-glabel-axis"></div>`;
  const rowCol = lanes.map((L) =>
    `<div class="tl-trow${L.tall ? " tl-trow-tall" : ""}"><div class="${L.trackCls}">${L.bars}</div></div>`).join("") +
    `<div class="tl-axis">${axis}</div>`;
  // DPS 곡선 오버레이(토글 시) + 호버 가이드선(평소 숨김, 커서 올리면 해당 시각에 세로선+시간·DPS)
  // 라벨 접기 버튼(주로 모바일): 접으면 이름 열을 왼쪽으로 숨기고 트랙이 확장. tlLabelsCollapsed 상태 유지.
  return `<div class="tl-gantt${tlLabelsCollapsed ? " collapsed" : ""}">` +
    `<div class="tl-gantt-labels">${labelCol}</div>` +
    `<div class="tl-labels-toggle-col"><button type="button" class="tl-labels-toggle" title="이름 열 접기/펼치기" aria-label="이름 열 접기/펼치기"></button></div>` +
    `<div class="tl-gantt-scroll"><div class="tl-gantt-inner">${rowCol}${tlDpsOverlay(d)}` +
    `<div class="tl-guide" hidden><span class="tl-guide-time"></span></div></div></div></div>`;
}

// 순간 DPS 곡선 SVG(간트 위 오버레이). 토글 꺼짐/데이터 없으면 "". 간트와 x좌표·확대 공유(viewBox 0..1000).
function tlDpsOverlay(d) {
  tlDpsShownPts = null;
  if (!tlShowDps) return "";
  const pts0 = d.dpsPoints, T = d.simMs;
  if (!pts0 || pts0.length < 2 || !T) return "";
  // 액션별 순간 DPS 점(딜 없거나 첫 액션이 0초 아니면 0초에 0점 추가 → 항상 0초부터 시작).
  const raw = pts0.map((p) => ({ x: p.t, y: p.dps || 0 }));
  if (raw[0].x > 0) raw.unshift({ x: 0, y: 0 });
  if (raw[raw.length - 1].x < T) raw.push({ x: T, y: raw[raw.length - 1].y });   // 마지막 값 유지로 끝시간까지 연장(그래프 처음~끝 연결)
  // 슬라이더 → 분기 폭 W(기하 레벨 ×4/3, 저분기에 해상도 집중) → 목표 점 개수 threshold=액션수/W.
  const levels = tlBinLevels(raw.length);
  const s = tlDpsAgg / 100, fi = s * (levels.length - 1);
  const li = Math.floor(fi), hii = Math.min(levels.length - 1, li + 1), fr = fi - li;
  const W = levels[li] * (1 - fr) + levels[hii] * fr;
  // 가우시안 평활(겹침 → 슬라이더 밀어도 고점이 튀지 않고 매끄럽게 감쇠, 뾰족함 제거) 후 균등 다운샘플.
  const ys = tlGaussSmooth(raw.map((p) => p.y), W * 0.75);
  const smoothed = raw.map((p, i) => ({ x: p.x, y: ys[i] }));
  const threshold = Math.max(2, Math.round(raw.length / W));
  const sel = tlDecimate(smoothed, threshold);                 // 매끄러운 곡선 위에서 점만 줄임(양 끝 포함)
  tlDpsShownPts = sel.map((p) => ({ t: p.x, dps: p.y }));      // 호버 DPS 읽기용
  let max = 0; for (const p of sel) if (p.y > max) max = p.y;
  if (max <= 0) return "";
  const WVB = 1000, top = 6, bot = 94;
  const P = sel.map((p) => ({ x: Math.min(T, p.x) / T * WVB, y: bot - (p.y / max) * (bot - top) }));
  const line = tlMonoPath(P);                                   // 점들을 단조 큐빅으로 곡선 보간(직선 아님, 0 밑 오버슈트 없음)
  const x0 = P[0].x.toFixed(2), x1 = P[P.length - 1].x.toFixed(2);
  const area = P.length > 1 ? line + ` L ${x1} 100 L ${x0} 100 Z` : "";
  return `<svg class="tl-dps" viewBox="0 0 ${WVB} 100" preserveAspectRatio="none">` +
    `<path class="tl-dps-fill" d="${area}"/><path class="tl-dps-line" d="${line}"/></svg>`;
}

// 분기 폭 레벨(액션 수): 1부터 ×4/3(올림)씩, 전체의 1/10을 처음 넘는 값까지. 저분기 해상도 집중용.
function tlBinLevels(n) {
  const cap = n / 10, levels = [1];
  let w = 1, guard = 0;
  while (w <= cap && guard++ < 100) { w = Math.ceil(w * 4 / 3); levels.push(w); }
  return levels;
}

// 가우시안 평활: 각 점을 주변 값의 가우시안 가중 평균으로. sigma↑이면 더 매끄럽게(고점 완만히 감쇠).
function tlGaussSmooth(v, sigma) {
  const n = v.length;
  if (sigma <= 0.4) return v.slice();
  const r = Math.max(1, Math.ceil(sigma * 3)), ker = [];
  for (let k = -r; k <= r; k++) ker.push(Math.exp(-(k * k) / (2 * sigma * sigma)));
  const out = new Array(n);
  for (let i = 0; i < n; i++) {
    let s = 0, w = 0;
    for (let k = -r; k <= r; k++) {
      const j = i + k;
      if (j < 0 || j >= n) continue;
      s += v[j] * ker[k + r]; w += ker[k + r];
    }
    out[i] = w > 0 ? s / w : v[i];
  }
  return out;
}

// 균등 다운샘플: count개로 줄임(첫·끝 포함). 이미 매끄러운 곡선 위라 출렁임 없음.
function tlDecimate(pts, count) {
  const n = pts.length;
  if (count >= n || count < 2) return pts.slice();
  const out = [];
  for (let i = 0; i < count; i++) out.push(pts[Math.round(i * (n - 1) / (count - 1))]);
  return out;
}

// 점들 → 단조 큐빅(monotone cubic Hermite → bezier). 데이터 범위를 안 벗어나 급변 구간에서 0 밑으로 안 내려감.
function tlMonoPath(p) {
  const n = p.length;
  if (n < 2) return n ? `M ${p[0].x.toFixed(2)} ${p[0].y.toFixed(2)}` : "";
  const dx = new Array(n - 1), sl = new Array(n - 1);
  for (let i = 0; i < n - 1; i++) { dx[i] = p[i + 1].x - p[i].x; sl[i] = dx[i] !== 0 ? (p[i + 1].y - p[i].y) / dx[i] : 0; }
  const m = new Array(n);
  m[0] = sl[0]; m[n - 1] = sl[n - 2];
  for (let i = 1; i < n - 1; i++) m[i] = sl[i - 1] * sl[i] <= 0 ? 0 : (sl[i - 1] + sl[i]) / 2;   // 부호전환=극값→접선0
  for (let i = 0; i < n - 1; i++) {   // Fritsch-Carlson: 단조성 보존 접선 제한
    if (sl[i] === 0) { m[i] = 0; m[i + 1] = 0; continue; }
    const a = m[i] / sl[i], b = m[i + 1] / sl[i], s = a * a + b * b;
    if (s > 9) { const tau = 3 / Math.sqrt(s); m[i] = tau * a * sl[i]; m[i + 1] = tau * b * sl[i]; }
  }
  let d = `M ${p[0].x.toFixed(2)} ${p[0].y.toFixed(2)}`;
  for (let i = 0; i < n - 1; i++) {
    const c1x = p[i].x + dx[i] / 3, c1y = p[i].y + m[i] * dx[i] / 3;
    const c2x = p[i + 1].x - dx[i] / 3, c2y = p[i + 1].y - m[i + 1] * dx[i] / 3;
    d += ` C ${c1x.toFixed(2)} ${c1y.toFixed(2)} ${c2x.toFixed(2)} ${c2y.toFixed(2)} ${p[i + 1].x.toFixed(2)} ${p[i + 1].y.toFixed(2)}`;
  }
  return d;
}

// DPS 토글(켜기=재렌더로 오버레이 추가·슬라이더 활성) + 집계 슬라이더(경로만 갱신). #tl-box-gantt 위임(1회).
function tlWireDpsControls() {
  const box = $("tl-box-gantt");   // DPS 토글·슬라이더 = 타임라인 카드 헤더
  if (!box || box.dataset.dpsWired) return;
  box.dataset.dpsWired = "1";
  box.addEventListener("change", (e) => {
    if (e.target.id !== "tl-dps-on") return;
    tlShowDps = e.target.checked;
    if (tlLastData) renderTimelineResult(tlLastData);
  });
  box.addEventListener("input", (e) => {
    if (e.target.id !== "tl-dps-agg") return;
    tlDpsAgg = parseFloat(e.target.value) || 0;
    tlUpdateDpsCurve();
  });
}

// 집계 슬라이더 드래그 시 전체 재렌더 없이 곡선 경로만 교체(부드러운 반응).
function tlUpdateDpsCurve() {
  const svg = document.querySelector("#tl-box-gantt .tl-dps");
  if (!svg || !tlLastData) return;
  const tmp = document.createElement("div");
  tmp.innerHTML = tlDpsOverlay(tlLastData);
  if (tmp.firstChild) svg.innerHTML = tmp.firstChild.innerHTML;
}

// 간트 확대/축소: Ctrl+휠로 가로 배율 조정(최소 100%=1, 최대=시뮬 길이 비례). 확대 시 가로 스크롤.
let tlZoom = 1, tlMaxZoom = 8, tlGuideMs = 0, tlLastData = null;
let tlLabelsCollapsed = false;   // 간트 이름(라벨) 열 접힘 — 모바일 가로폭 확보용. 클릭 토글, 렌더 간 유지.
// DPS 곡선 상태(타임라인 카드 헤더의 토글·슬라이더로 제어). tlDpsAgg=구간 뭉뚱그리기 강도(0~100), 곡률은 항상 최대.
let tlShowDps = false, tlDpsAgg = 30, tlDpsShownPts = null;
let tlBrkShowComp = false;   // 스킬별 기여: 동료 전체 딜을 비중에 포함할지(토글)
function applyTlZoom() {
  const inner = document.querySelector("#tl-box-gantt .tl-gantt-inner");
  if (inner) inner.style.setProperty("--tl-zoom", tlZoom);
  repositionTlGuide(inner);   // 줌으로 콘텐츠 폭이 바뀌면 시간선도 같은 시각을 가리키도록 재배치
}
// 시간선(호버/터치로 만든 세로 가이드)을 저장된 시각 비율 기준으로 현재 콘텐츠 폭에 맞춰 다시 위치. 줌 시 어긋남 방지.
function repositionTlGuide(inner) {
  inner = inner || document.querySelector("#tl-box-gantt .tl-gantt-inner");
  if (!inner) return;
  const guide = inner.querySelector(".tl-guide");
  if (!guide || guide.hidden) return;
  const frac = parseFloat(guide.dataset.frac);
  if (!isFinite(frac)) return;
  const pad = 4, contentW = inner.clientWidth - pad * 2;
  if (contentW <= 0) return;
  const left = frac * contentW + pad;
  guide.style.left = left + "px";
  const scroll = inner.parentElement, chip = guide.querySelector(".tl-guide-time");
  if (scroll && chip) chip.classList.toggle("flip", left > scroll.scrollLeft + scroll.clientWidth - 90);
}
function tlWireZoom() {
  const box = $("tl-box-gantt");
  if (!box || box.dataset.zoomWired) return;
  box.dataset.zoomWired = "1";
  box.addEventListener("wheel", (e) => {
    if (!e.ctrlKey) return;                 // Ctrl 없으면 일반 스크롤/브라우저 줌
    const g = e.target.closest && e.target.closest(".tl-gantt");
    const scroll = g && g.querySelector(".tl-gantt-scroll");
    if (!scroll) return;
    e.preventDefault();
    const prev = tlZoom;
    tlZoom = Math.min(tlMaxZoom, Math.max(1, tlZoom * (e.deltaY < 0 ? 1.25 : 1 / 1.25)));
    if (tlZoom === prev) return;
    // 커서 위치를 기준으로 확대(그 지점이 유지되도록 스크롤 보정)
    const rect = scroll.getBoundingClientRect();
    const cursorX = e.clientX - rect.left + scroll.scrollLeft;
    applyTlZoom();
    scroll.scrollLeft = cursorX * (tlZoom / prev) - (e.clientX - rect.left);
  }, { passive: false });

  // 모바일 핀치 줌 — 간트 안에서 두 손가락을 벌리면 가로 배율 확대(브라우저 전체 줌 대신).
  let pinchDist = 0, pinchScroll = null, pinchZoom0 = 1;
  const dist2 = (t) => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
  const midX = (t) => (t[0].clientX + t[1].clientX) / 2;
  box.addEventListener("touchstart", (e) => {
    if (e.touches.length !== 2) return;
    const g = e.target.closest && e.target.closest(".tl-gantt");
    pinchScroll = g && g.querySelector(".tl-gantt-scroll");
    if (!pinchScroll) return;
    pinchDist = dist2(e.touches); pinchZoom0 = tlZoom;
    e.preventDefault();
  }, { passive: false });
  box.addEventListener("touchmove", (e) => {
    if (e.touches.length !== 2 || !pinchScroll || pinchDist <= 0) return;
    e.preventDefault();
    const prev = tlZoom;
    tlZoom = Math.min(tlMaxZoom, Math.max(1, pinchZoom0 * (dist2(e.touches) / pinchDist)));
    if (tlZoom === prev) return;
    const rect = pinchScroll.getBoundingClientRect();
    const cx = midX(e.touches) - rect.left + pinchScroll.scrollLeft;   // 두 손가락 중심점 기준 확대
    applyTlZoom();
    pinchScroll.scrollLeft = cx * (tlZoom / prev) - (midX(e.touches) - rect.left);
  }, { passive: false });
  const endPinch = (e) => { if (e.touches.length < 2) { pinchDist = 0; pinchScroll = null; } };
  box.addEventListener("touchend", endPinch);
  box.addEventListener("touchcancel", endPinch);
}

/** 버프 윈도우 레인들. isArt=유물(황금색 바 + '상시' 툴팁). 스택 세그먼트는 진하기·툴팁·숫자로 표시. */
// 효과 스탯키 → 정확한 게임 내 명칭. statDefs(프리셋 스탯 정의=게임 명칭 단일 소스)를 우선 사용,
// 거기 없는 유물/디버프 전용 키(약화·최대뎀배율 등)만 아래 폴백으로 보충.
const STAT_KR_FALLBACK = {
  WEAKNESS: "적 받는 피해 증가", MAX_DMG_MULT: "최대 데미지 배율", ATK_PCT_ADD: "공격력",
  ATK_SPEED: "공격 속도", COOLDOWN_REDUCTION_PCT: "쿨타임 감소", BUFF_DURATION: "버프 지속시간", SUMMON_DURATION: "동료 소환 지속시간",
  CRIT_RATE_ENEMY: "적 크리티컬 저항 감소", CRIT_DMG_ENEMY: "적 받는 크리티컬 데미지 증가",
};
function tlStatName(stat) {
  const d = statDefById(stat);           // stats.json 게임 명칭(예: BASIC_ATK_DMG=기본 공격 데미지)
  if (d && d.name) return d.name;
  return STAT_KR_FALLBACK[stat] || stat;
}
function tlEffLine(e) {
  const s = `${tlStatName(e.stat)} ${e.value >= 0 ? "+" : ""}${e.value}%`;
  return e.dormant ? `<s>${s}</s>` : s;   // 무실효(딜 미적용)=취소선. 막대는 그대로, 툴팁 옵션만 취소선.
}
// 툴팁 = 이름(스택형은 '이름 (n스택)') / 효과 각 줄 / 유지시간. 여러 줄(\n, CSS pre-line).
function tlBuffTitle(label, w) {
  const when = w.always ? "상시" : `${(w.start / 1000).toFixed(1)}~${(w.end / 1000).toFixed(1)}s`;
  const lines = [w.stacks != null ? `${label} (${w.stacks}스택)` : label];
  for (const e of (w.effects || [])) lines.push(tlEffLine(e));   // 효과 2개↑면 각각 줄넘김. 무실효 옵션은 취소선.
  lines.push(when);
  return lines.join("\n");
}
// 같은 그룹(유물 인스턴스/스킬)의 소스들을 시간축으로 병합 — 모든 경계(start/end)에서 잘라,
// 각 구간에 그때 활성인 소스의 효과(상시+파생+스택)를 전부 담는다. 효과가 바뀌는 지점마다 막대가 갈린다.
// → 상시 효과와 시변(파생·스택) 효과가 한 막대·한 툴팁에 함께 표기됨.
function tlMergeSegs(segs, T) {
  const bounds = new Set();
  for (const s of segs) { bounds.add(s.start); bounds.add(s.end); }
  const pts = [...bounds].sort((a, b) => a - b);
  const out = [];
  for (let i = 0; i < pts.length - 1; i++) {
    const t1 = pts[i], t2 = pts[i + 1];
    if (t1 >= t2) continue;
    const effects = [];
    let stacks = null;
    for (const s of segs) {
      if (s.start > t1 || s.end < t2) continue;   // 이 구간에 활성인 소스만
      for (const e of (s.effects || [])) effects.push(s.dormant ? Object.assign({}, e, { dormant: true }) : e);   // 무실효 소스의 옵션엔 dormant 표시(취소선)
      if (s.stacks != null) stacks = Math.max(stacks == null ? 0 : stacks, s.stacks);
    }
    if (!effects.length && stacks == null) continue;
    out.push({ start: t1, end: t2, always: t1 === 0 && t2 >= T, stacks, effects });
  }
  return out;
}
function tlBuffLanes(byKey, pct, isArt, lanes, T) {
  const built = [];   // 딜 기여 근사 점수로 정렬 후 push
  for (const key of Object.keys(byKey)) {
    const { label, segs } = byKey[key];
    const merged = tlMergeSegs(segs, T);   // 그리기 전에 동일 계수끼리 묶어 변화 구간마다 분리(순환의 고리 형식)
    // 값이 변하는 레인(파생·중첩): 시뮬 내 최저~최고로 정규화해 색 농도(옅게~진하게). 세그먼트 효과값 합 기준.
    const sumOf = (w) => (w.effects || []).reduce((s, e) => s + (e.value || 0), 0);
    const vals = merged.map(sumOf);
    const vmin = vals.length ? Math.min(...vals) : 0, vmax = vals.length ? Math.max(...vals) : 0, range = vmax - vmin;
    // 딜 기여 근사 점수 = Σ(효과값 × 구간 가동비율). 큰 효과·오래 가는 버프 우선(무실효 dormant 제외). 곱스탯은 값 그대로, 크뎀/크확 등 얽힌 건 근사.
    let score = 0;
    for (const w of merged) {
      const dur = (w.end - w.start) / T;
      for (const e of (w.effects || [])) if (!e.dormant) score += Math.abs(e.value || 0) * dur;
    }
    let bars = "";
    for (let i = 0; i < merged.length; i++) {
      const w = merged[i];
      const title = tlBuffTitle(label, w);
      const width = pct(w.end - w.start);   // 정확한 폭(최소폭 없음 — 은펜던트 등 짧은 유지도 실제 길이로, 확대하면 보임)
      let style = `left:${pct(w.start).toFixed(3)}%;width:${width.toFixed(3)}%`;
      if (range > 0.01) style += `;--a:${(0.45 + 0.5 * ((vals[i] - vmin) / range)).toFixed(2)}`;   // 낮으면 옅게, 높으면 진하게(배경 알파 — opacity 속성 대신, 확대 시 미표시 방지)
      const num = (w.stacks != null && width > 3) ? `<b>${w.stacks}</b>` : "";   // 중첩=숫자로만(높이 표현 폐지)
      bars += `<span class="tl-win${isArt ? " art" : ""}" style="${style}" data-tip="${title}">${num}</span>`;
    }
    built.push({ score, lane: { label, labelCls: isArt ? "tl-glabel-art" : "tl-glabel-buff", trackCls: "tl-track tl-buff", bars } });
  }
  built.sort((a, b) => b.score - a.score);   // 딜 기여 높은 순
  for (const b of built) lanes.push(b.lane);
}

/** 지속딜(도트·장판) 레인 — 시전 시각부터 지속시간만큼 바로 표시. 스킬별 한 레인. */
// 딜 라인: 스킬별 옅은 막대(활성/지속 구간)+진한 세로줄(실제 딜 틱). 순간 프록(마크·쉐도우)은 막대 없이 틱만.
function tlDealLines(dealLines, pct, lanes) {
  for (const dl of (dealLines || [])) {
    let bars = "";
    for (const w of (dl.windows || [])) {   // 옅은 막대(뒤)
      const width = pct(w.end - w.start);   // 정확한 폭(최소폭 없음)
      const when = `${(w.start / 1000).toFixed(1)}~${(w.end / 1000).toFixed(1)}s`;
      bars += `<span class="tl-dwin" style="left:${pct(w.start).toFixed(3)}%;width:${width.toFixed(3)}%" data-tip="${dl.name} 지속 · ${when}"></span>`;
    }
    for (const tk of (dl.ticks || [])) {     // 진한 틱(위). strong=즉발 첫타격 → 굵게
      const dmg = tk.dmg > 0 ? " · " + koreanUnit(tk.dmg) : "";
      bars += `<span class="tl-dtick${tk.strong ? " strong" : ""}" style="left:${pct(tk.t).toFixed(3)}%" data-tip="${dl.name} · ${(tk.t / 1000).toFixed(2)}s${dmg}"></span>`;
    }
    lanes.push({ label: dl.name, labelCls: "tl-glabel-dot", trackCls: "tl-track tl-buff", bars });
  }
}

// 동료(v3.1) 간트 lane: 소환창 막대 + 스킬별 딜 틱 + 동료→플레이어 버프 막대. 시간축 공유.
const COMPANION_STAT_LABEL = {
  ATK_PCT: "공격력", FinalDamage: "최종뎀", MaxDamageRatio: "최대뎀배율", CriticalPower: "크뎀",
  CriticalChance: "크확", AttackSpeed: "공속", SkillPower: "스킬뎀", DMG: "데미지",
  AttackPowerToBoss: "보스뎀", AttackPowerExcludeBoss: "일반뎀", BaseAttackPower: "평타뎀", Weakness: "약화",
};
function tlCompanionLanes(c, pct, lanes, T) {
  if (!c) return;
  // 1) 소환창 막대(소환 타이밍=막대 시작, 소환 기간=막대 길이)
  let sw = "";
  for (const w of (c.summons || [])) {
    const width = pct(w.end - w.start);
    const when = `${(w.start / 1000).toFixed(1)}~${(w.end / 1000).toFixed(1)}s`;
    sw += `<span class="tl-cwin" style="left:${pct(w.start).toFixed(3)}%;width:${width.toFixed(3)}%" data-tip="${c.name} 소환 · ${when}"></span>`;
  }
  lanes.push({ label: `▼ ${c.name} 소환`, labelCls: "tl-glabel-comp", trackCls: "tl-track tl-comp-lane", bars: sw });
  // 2) 동료 딜 lanes(스킬별 틱)
  for (const dl of (c.dealLines || [])) {
    let bars = "";
    for (const tk of (dl.ticks || [])) {
      const dmg = tk.dmg > 0 ? " · " + koreanUnit(tk.dmg) : "";
      bars += `<span class="tl-ctick" style="left:${pct(tk.t).toFixed(3)}%" data-tip="${dl.name} · ${(tk.t / 1000).toFixed(2)}s${dmg}"></span>`;
    }
    lanes.push({ label: dl.name, labelCls: "tl-glabel-comp tl-glabel-sub", trackCls: "tl-track tl-buff", bars });
  }
  // 3) 동료 버프 lanes — 스킬별(라벨=타입 이름만). 대상(플레이어/동료/…)은 호버 툴팁에만 표기.
  const bySkill = {};
  for (const b of (c.buffs || [])) {
    const k = b.skillIdx;
    (bySkill[k] = bySkill[k] || { name: b.skillName || String(k), items: [] }).items.push(b);
  }
  for (const k of Object.keys(bySkill)) {
    const g = bySkill[k];
    const byWin = {};   // 같은 창(start-end)끼리 묶어 그 창의 스탯들 나열
    for (const b of g.items) { const wk = b.start + "-" + b.end; (byWin[wk] = byWin[wk] || { start: b.start, end: b.end, stats: [] }).stats.push(b); }
    let bars = "";
    for (const wk of Object.keys(byWin)) {
      const w = byWin[wk];
      const width = pct(w.end - w.start);
      const when = `${(w.start / 1000).toFixed(1)}~${(w.end / 1000).toFixed(1)}s`;
      const statLines = w.stats.map((b) => {   // 효과별 줄바꿈 + 대상 태그(툴팁에만)
        const tgt = b.toEnemy ? "enemy" : (b.target || "player");
        return `${COMPANION_STAT_LABEL[b.stat] || b.stat} +${(b.value / 10).toFixed(1)}% · ${compBuffTargetLabel(tgt)}`;
      });
      const tip = [g.name, ...statLines, when].join("\n");
      bars += `<span class="tl-win tl-cbuff" style="left:${pct(w.start).toFixed(3)}%;width:${width.toFixed(3)}%" data-tip="${tip}"></span>`;
    }
    lanes.push({ label: g.name, labelCls: "tl-glabel-comp tl-glabel-sub", trackCls: "tl-track tl-buff", bars });
  }
}
// 동료 버프 대상 라벨(툴팁 태그용) — 플레이어/동료/플레이어·동료/적.
function compBuffTargetLabel(t) {
  return t === "both" ? "플레이어·동료 적용" : t === "companion" ? "동료 적용" : t === "enemy" ? "적 디버프" : "플레이어 적용";
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
        const cMe = loadJSON(CACHE_ME, null); if (cMe) { me = cMe; renderLoginUI(); }   // 정지 오버레이는 캐시로 띄우지 않음(서버 /api/me 진실만) — 스테일 캐시 오탐 방지
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
    if (boot && boot.companionMeta) { companionMeta = boot.companionMeta; saveJSON(CACHE_COMPANION_META, companionMeta); }   // 계승 상수(정적)
    if (boot && boot.artifacts) { artifactCatalog = boot.artifacts; saveJSON(CACHE_ARTIFACTS, artifactCatalog); }   // 유물 카탈로그(정적)
    if (boot && boot.targetCatalog) { targetCatalog = boot.targetCatalog; saveJSON(CACHE_MONDEF, targetCatalog); }   // 대상 카탈로그(정적, 방어력 미포함)
    renderTargetSelector();      // 예상 데미지: 대상(스테이지/보스) 선택
    await loadMyData(false, boot && boot.mydata);   // 시작 시 내 값 자동 채움(배포 땐 빈 값)
    renderConvSettings();        // 딜 환산 설정 목록
    renderRangeSettings();       // 추천 범위 설정 목록
    initStatPresets();           // 캐릭터 프리셋(스탯+직업+대상+스킬; 없으면 '기본' 생성, 구 스킬 프리셋 병합)
    await initCubePresets();     // 큐브 프리셋
    initAllyPresets();           // 동료 프리셋(메인 동료)
    initRelicPresets();          // 유물 프리셋
    initBuildPresets(); fillBuildSel(); fillBuildPresetSelects();   // 빌드 프리셋 — 전역 활성 로드아웃(퀵패널 노출)
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
    // 초기 bootPath가 아니라 '현재' 경로로 — 데이터 로드 중 사용자가 다른 탭을 눌렀으면 그 탭을 유지(강제 복귀 방지).
    navigate(location.pathname, false);
  }
})();
