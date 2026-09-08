# -*- coding: utf-8 -*-
"""
mekiCalc 장비 비교 OCR 사이드카 (로컬·무료·무제한).
PaddleOCR(한국어) + 규칙 파서 v2로 장비 상세 스크린샷 → {leftPart,rightPart,left,right,overall} JSON.
좌우 산술 교차검증(오른쪽=왼쪽+델타, 색 무관)으로 병합값을 자동 분리·보정하고, 줄별 신뢰도/경고(warn)를 낸다.
Java(web)의 LocalOcr가 localhost로 호출하고, statId 매핑·주/부 분리·부위 정규화는 Java가 수행.

실행: python app.py   (환경변수: OCR_PORT(기본 8090)·OCR_WORKERS(기본 2)·OCR_CPU_THREADS(기본 6))
의존: paddlepaddle==3.0.0, paddleocr, opencv-python-headless(<5, numpy<2), fastapi, uvicorn
"""
import os, io, base64, queue, threading, re, time, logging

# ★홈 경로가 비ASCII(예: 한글 사용자명 C:\Users\박민혁)면 paddle C++가 모델 파일을 못 연다.
# 그 경우 paddlex/HF 모델 캐시를 ASCII 경로(C:\Users\Public)로 돌린다. ASCII 홈이면 기본 유지.
if not os.path.expanduser("~").isascii():
    _cache = r"C:\Users\Public\paddlex"
    os.makedirs(_cache, exist_ok=True)
    os.environ.setdefault("PADDLE_PDX_CACHE_HOME", _cache)
    os.environ.setdefault("HF_HOME", os.path.join(_cache, "hf"))

import numpy as np
import cv2
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import uvicorn

PORT = int(os.environ.get("OCR_PORT", "8090"))
N_WORKERS = int(os.environ.get("OCR_WORKERS", "2"))
CPU_THREADS = int(os.environ.get("OCR_CPU_THREADS", "6"))
log = logging.getLogger("ocr")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

# ─────────────────────────── 파서 v2 (단일 패스 + 산술 교차검증, 색 무관) ───────────────────────────
HANGUL = re.compile(r"[가-힣]")
VALUE_RE = re.compile(r"^[+\-]?\d[\d,.\s%+\-]*$")
ANCHOR = "장착효과"
GRADE_WORDS = ("레전드리", "유니크", "에픽", "신화", "노멀")
PART_SYN = {"어깨장식": "견장"}
PART_KEYS = ["투구","상의","망토","견장","어깨장식","하의","장갑","벨트","신발","반지2","반지",
             "목걸이","얼굴장식","눈장식","귀고리","포켓"]
WARN_BELOW = 0.90   # 이 신뢰도 미만이면 warn=True(프론트 ⚠️). 산술 검증(verified)은 항상 통과.
TOL = 0.06

def _norm(s): return re.sub(r"\s", "", s or "")
def _is_value(t): return bool(VALUE_RE.match(t)) and not HANGUL.search(t)
def _is_name(t): return bool(HANGUL.search(t))
def _is_anchor(t):   # 블러 내성: '과'로 끝 + 길이3~5 + 앵커 글자 포함('장착 효과'가 '교과'·'장작' 등으로 뭉개져도)
    n = _norm(t); return 3 <= len(n) <= 5 and n.endswith("과") and any(c in n for c in "장착효교")
def _has_pct(s): return "%" in (s or "")
def _fix_thousands(tok):   # 흐림으로 천단위 콤마가 점으로 오독: '44.758'→'44758' (점 뒤 그룹이 전부 3자리면 천단위)
    if "%" in tok or "," in tok: return tok
    sign = tok[0] if tok[:1] in "+-" else ""; body = tok[len(sign):]
    parts = body.split(".")
    if len(parts) >= 2 and all(p.isdigit() for p in parts) and all(len(p) == 3 for p in parts[1:]):
        return sign + "".join(parts)
    return tok
def _to_num(s):
    m = re.search(r"[+\-]?\d[\d,.]*", s or "")
    if not m: return None
    try: return float(_fix_thousands(m.group()).replace(",", ""))
    except ValueError: return None
def _first_num_str(s):
    m = re.match(r"[+\-]?(?:\d{1,3}(?:[,.]\d{3})+|\d+)(?:\.\d+)?", s or "")
    return (_fix_thousands(m.group()) + ("%" if _has_pct(s) else "")) if m else s

def _boxes(ocr, img):
    out = []
    for r in ocr.predict(input=img):
        t = r.get("rec_texts", []); sc = r.get("rec_scores", []); pl = r.get("rec_polys", r.get("dt_polys", []))
        for i, x in enumerate(t):
            if i >= len(pl): continue
            p = pl[i]; xs = [q[0] for q in p]; ys = [q[1] for q in p]
            out.append(dict(text=(x or "").strip(), conf=(sc[i] if i < len(sc) else 0),
                            xl=min(xs), xr=max(xs), xc=sum(xs)/len(xs), yc=sum(ys)/len(ys)))
    return [b for b in out if b["conf"] >= 0.5 and b["text"]]

def _panel(bs, lo, hi, yt):
    band = [b for b in bs if lo <= b["xc"] < hi and b["yc"] > yt - 6]; band.sort(key=lambda b: b["yc"])
    rows = []
    for b in band:
        if rows and abs(b["yc"] - rows[-1][0]) <= 14: rows[-1][1].append(b)
        else: rows.append([b["yc"], [b]])
    out = []
    for _, rb in rows:
        rb.sort(key=lambda b: b["xl"])
        nb = next((b for b in rb if _is_name(b["text"])), None)
        if nb is None: continue
        nums = [b for b in rb if _is_value(b["text"]) and b["xc"] > nb["xc"]]
        if not nums: continue
        if ANCHOR in _norm(nb["text"]) or "전투력" in nb["text"]: continue
        out.append({"label": nb["text"].strip(), "nameConf": nb["conf"],
                    "nums": [b["text"] for b in nums], "numConf": [b["conf"] for b in nums]})
    return out

def _part(bs, lo, hi, yb):
    cand = [b for b in bs if lo <= b["xc"] < hi and b["yc"] < yb and any(g in b["text"] for g in GRADE_WORDS)]
    if not cand: cand = [b for b in bs if lo <= b["xc"] < hi and b["yc"] < yb and HANGUL.search(b["text"])]
    for b in sorted(cand, key=lambda b: b["yc"]):
        for k in PART_KEYS:
            if k in _norm(b["text"]): return PART_SYN.get(k, k)
    return ""

def _enhance(img, lo=120, hi=230):
    return (np.clip((img.astype(np.float32)-lo)/(hi-lo), 0, 1)*255).astype(np.uint8)

def _parse_one(ocr, img):
    bs = _boxes(ocr, img)
    an = sorted([b for b in bs if _is_anchor(b["text"])], key=lambda b: b["xc"])
    if not an: return {"leftPart": "", "rightPart": "", "left": [], "right": []}
    if len(an) < 2:
        a = an[0]; p = 360
        return {"leftPart": _part(bs, a["xc"]-30, a["xc"]+p-30, a["yc"]), "rightPart": "",
                "left": _panel(bs, a["xc"]-30, a["xc"]+p-30, a["yc"]), "right": []}
    aL, aR = an[0], an[1]; p = aR["xc"] - aL["xc"]
    return {"leftPart": _part(bs, aL["xc"]-30, aL["xc"]+p-30, aL["yc"]),
            "rightPart": _part(bs, aR["xc"]-30, aR["xc"]+p-30, aR["yc"]),
            "left": _panel(bs, aL["xc"]-30, aL["xc"]+p-30, aL["yc"]),
            "right": _panel(bs, aR["xc"]-30, aR["xc"]+p-30, aR["yc"])}

def _n_opts(r): return len(r["left"]) + len(r["right"])

# ── 산술 교차검증 ──
def _rule_default(base, lv, delta): return abs((base - (lv if lv is not None else 0)) - delta) <= TOL
STAT_RULES = {}   # _norm(label) -> fn(base,lv,delta)->bool. 예외 스탯만 등록.

def _split_structural(t):
    m = re.search(r"(?<=\d)[+\-]", t)
    if m: return (t[:m.start()], t[m.start():])
    ps = [mm.end() for mm in re.finditer("%", t)]
    if len(ps) >= 2: return (t[:ps[0]], t[ps[0]:])
    m = re.match(r"[+\-]?\d{1,3}(?:[,.]\d{3})+", t)   # 콤마(또는 점 오독) 천단위 첫 수: '44.758 8,050'
    if m and m.end() < len(t): return (t[:m.end()], t[m.end():])
    return None

def _resolve_right(nums, lv, label):
    """반환 (value_str, delta_or_None, verified, trusted). verified=왼쪽으로 독립 교차검증됨."""
    if not nums: return (None, None, False, False)
    rule = STAT_RULES.get(_norm(label), _rule_default)
    has_left = lv is not None
    def ver(bv, dv): return has_left and dv is not None and rule(bv, lv, dv)
    if len(nums) >= 2:
        base_s = _first_num_str(nums[0]); dv = _to_num(_first_num_str(nums[1]))
        return (base_s, dv, ver(_to_num(base_s), dv), True)
    T = nums[0]
    st = _split_structural(T)
    if st:
        base_s = _first_num_str(st[0]); dv = _to_num(st[1])
        return (base_s, dv, ver(_to_num(base_s), dv), True)
    core = T.replace("%", ""); pct = "%" if _has_pct(T) else ""
    if core.isdigit() and len(core) >= 2:
        if has_left:
            for i in range(1, len(core)):
                bv, dv = float(core[:i]), float(core[i:])
                if rule(bv, lv, dv): return (core[:i]+pct, dv, True, True)
        elif len(core) % 2 == 0 and core[:len(core)//2] == core[len(core)//2:]:
            h = core[:len(core)//2]; return (h+pct, float(h), False, True)
    if has_left and abs((_to_num(T) if _to_num(T) is not None else -1e9) - lv) <= TOL:
        return (_first_num_str(T), 0.0, True, False)
    return (_first_num_str(T), None, False, False)

def _line_conf(row):
    cs = [row.get("nameConf", 1.0)] + list(row.get("numConf", []) or [1.0])
    return round(min(cs), 3)

def _resolve(res):
    left, right = res["left"], res["right"]
    lval = {}
    for r in left:
        r["value"] = _first_num_str(r["nums"][0]); r["_v"] = _to_num(r["value"]); r["_verified"] = False
        lval.setdefault(_norm(r["label"]), []).append(r)
    occ = {}
    for r in right:
        k = _norm(r["label"]); i = occ.get(k, 0); occ[k] = i + 1
        lrow = lval[k][i] if k in lval and i < len(lval[k]) else None
        lv = lrow["_v"] if lrow else None
        val, dv, verified, trusted = _resolve_right(r["nums"], lv, r["label"])
        r["value"] = val; r["_v"] = _to_num(val); r["_verified"] = verified
        if trusted and dv is not None and lrow is not None and r["_v"] is not None:
            expect_l = r["_v"] - dv
            if lrow["_v"] is None or abs(lrow["_v"] - expect_l) > TOL:
                fixed = str(int(expect_l)) if float(expect_l).is_integer() else str(expect_l)
                if _has_pct(r["value"]): fixed += "%"
                lrow["value"] = fixed; lrow["_v"] = expect_l
            lrow["_verified"] = True; r["_verified"] = True

    def emit(r):
        ocrc = _line_conf(r); verified = r.get("_verified", False)
        conf = max(ocrc, 0.97) if verified else ocrc
        lconf = round(r.get("nameConf", 1.0), 3)
        vconf = round(min(r.get("numConf") or [1.0]), 3)
        if verified: vconf = max(vconf, 0.97)   # 산술 교차검증되면 값 신뢰
        return {"label": r["label"], "value": r["value"], "confidence": round(conf, 3),
                "verified": bool(verified), "warn": (not verified) and conf < WARN_BELOW,
                "labelConf": lconf, "valueConf": vconf}   # 라벨·값 신뢰도 분리

    L = [emit(r) for r in left]; R = [emit(r) for r in right]
    allc = [o["confidence"] for o in L + R] or [1.0]
    overall = {"confidence": round(sum(allc)/len(allc), 3), "min": round(min(allc), 3),
               "verified": sum(1 for o in L + R if o["verified"]), "lines": len(L + R)}
    return {"leftPart": res["leftPart"], "rightPart": res["rightPart"], "left": L, "right": R, "overall": overall}

def parse(ocr, img):
    r1 = _parse_one(ocr, img)
    if _n_opts(r1) < 6:
        r2 = _parse_one(ocr, _enhance(img))
        if _n_opts(r2) > _n_opts(r1): r1 = r2
    return _resolve(r1)

# ─────────────────────────── 스탯 정보창 파서 ───────────────────────────
# 인게임 '스탯 정보' 창(본문 라벨:값) + ⓘ 상세(+ :/% :/합산 :). ★statId 매핑·주부스탯 판별·
# 공격력 선반영 역산은 Java가 수행 — 여기선 원문 라벨/값 + 신뢰도만 낸다(+ 한글 만·억·조·경 숫자 파싱).
_MYRIAD = [("경", 10**16), ("조", 10**12), ("억", 10**8), ("만", 10**4)]
_MYRIAD_V = {u: v for u, v in _MYRIAD}
_STAT_NOISE = ("스탯정보", "물약", "능력치", "자동사용설정", "상세", "현재보유중인능력치")  # 값으로 안 잡을 헤더/노이즈
_MAIN_STAT_TOK = ("STR", "DEX", "INT", "LUK")

def parse_kor_num(s):
    """'4억 6422만'·'4543만 3561'·'2경 6644조'·'64,292'·'132.5%'·'1,103,455' → (value, is_pct). 실패=None."""
    if s is None: return None
    t = s.strip().replace(" ", "")
    pct = "%" in t
    t = t.replace("%", "").replace(",", "")
    if not t: return None
    if any(u in t for u, _ in _MYRIAD):
        total = 0.0
        for m in re.finditer(r"(\d+)(경|조|억|만)", t):
            total += int(m.group(1)) * _MYRIAD_V[m.group(2)]
        tail = re.sub(r"\D", "", re.sub(r"\d+(경|조|억|만)", "", t))   # 단위그룹 제거 후 남은 1의 자리
        if tail: total += int(tail)
        return (total, pct)
    m = re.search(r"\d+(?:\.\d+)?", t)   # 접두 부호/콜론('+ :', '% :') 허용 위해 search
    return (float(m.group()), pct) if m else None

def _has_num(b): return parse_kor_num(b["text"]) is not None
def _row_conf(bs): return round(min(b["conf"] for b in bs), 3)
def _is_main_stat(text):   # STR·DEX·INT·LUK (ⓘ가 'LUKi' 등으로 붙어도 인식)
    u = re.sub(r"[^A-Za-z]", "", text or "").upper()
    return any(u.startswith(t) for t in _MAIN_STAT_TOK)

def _stat_rows(bs):
    """단일 컬럼 행 그룹(y 근접). 각 행 = x 오름차순 박스들."""
    rows = []
    for b in sorted(bs, key=lambda b: b["yc"]):
        if rows and abs(b["yc"] - rows[-1][0]) <= 14: rows[-1][1].append(b)
        else: rows.append([b["yc"], [b]])
    return [sorted(r[1], key=lambda b: b["xl"]) for r in rows]

def _stat_title(rb):
    return next((b for b in rb if _is_name(b["text"]) or _is_main_stat(b["text"])), None)

def _parse_stat_main(bs):
    """본문 라벨:값 목록. 라벨=한글/주스탯토큰, 값=라벨 우측 수치(다박스 결합)."""
    out = []
    for rb in _stat_rows(bs):
        name = _stat_title(rb)
        if name is None: continue
        nn = _norm(name["text"])
        if not nn or any(h in nn for h in _STAT_NOISE): continue
        nums = [b for b in rb if b is not name and b["xc"] > name["xc"] and _has_num(b)]
        if not nums: continue
        pv = parse_kor_num("".join(b["text"] for b in nums))
        if pv is None: continue
        out.append({"label": name["text"].strip(), "raw": "".join(b["text"] for b in nums).strip(),
                    "value": pv[0], "pct": pv[1], "confidence": _row_conf([name] + nums),
                    "labelConf": round(name["conf"], 3), "valueConf": _row_conf(nums)})   # 라벨·값 신뢰도 분리
    return {"type": "main", "rows": out}

def _parse_stat_detail(bs):
    """ⓘ 상세: 제목(스탯명) + '+ :'(flat)·'% :'(pct)·'합산 :'(sum). ★배경 격리 후 호출 전제."""
    rows = _stat_rows(bs)
    start = 0                                          # '현재 보유 중인 능력치' 이후만 값(상세 설명문의 숫자 배제)
    for i, rb in enumerate(rows):
        n = _norm("".join(b["text"] for b in rb))
        if "보유" in n and "능력치" in n: start = i + 1; break
    flat = pct = summ = title = None; confs = []
    for rb in rows[start:]:
        n = _norm("".join(b["text"] for b in rb))
        num = parse_kor_num("".join(b["text"] for b in rb if _has_num(b)))
        if num is None: continue
        if "합산" in n:
            if summ is None: summ = num[0]
        elif num[1]:                                   # is_pct → % 줄
            if pct is None: pct = num[0]; confs.append(_row_conf(rb))
        elif flat is None:                             # 합산·% 아닌 순수 숫자 = '+  :' 줄
            flat = num[0]; confs.append(_row_conf(rb))
    for rb in rows:                                    # 제목 = 공격력 / 주스탯(STR·DEX·INT·LUK)
        for b in rb:
            if _norm(b["text"]) == "공격력" or _is_main_stat(b["text"]): title = b["text"].strip(); break
        if title: break
    return {"type": "detail", "title": title, "flat": flat, "pct": pct, "sum": summ,
            "confidence": round(min(confs) if confs else 0.5, 3)}

def _modal_boxes(img, bs):
    """스탯 모달(밝은 패널)만 남긴다. 좌측 메뉴·뒤 배경(반투명 어두움) 제거.
    가장 크고 중앙에 가까운 '밝은' 사각 영역을 모달로 보고 그 안의 박스만 취한다."""
    H, W = img.shape[:2]
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    _, th = cv2.threshold(g, 175, 255, cv2.THRESH_BINARY)
    th = cv2.morphologyEx(th, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))   # 텍스트 구멍 메움→모달 한 덩어리
    cnts, _ = cv2.findContours(th, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    best = None; best_score = 0.0; cx0 = W / 2.0
    for c in cnts:
        x, y, w, h = cv2.boundingRect(c)
        if w < W * 0.18 or w > W * 0.72 or h < H * 0.25: continue   # 모달 크기대(메뉴칩·배경 제외)
        score = w * h - abs((x + w / 2.0) - cx0) * h                # 큰 면적 + 중앙 근접
        if score > best_score: best_score = score; best = (x, y, w, h)
    if best is None: return bs
    x, y, w, h = best; m = 10
    return [b for b in bs if x - m <= b["xc"] <= x + w + m and y - m <= b["yc"] <= y + h + m]

def parse_stats(ocr, img):
    bs = _modal_boxes(img, _boxes(ocr, img))
    if not bs: return {"type": "main", "rows": []}
    if any("합산" in _norm(b["text"]) for b in bs): return _parse_stat_detail(bs)
    return _parse_stat_main(bs)

# ─────────────────────────── 워커(스레드별 자체 예측기) ───────────────────────────
_jobs = queue.Queue()
_ready = threading.Event()
_ready_count = [0]
_ready_lock = threading.Lock()

def _build_ocr():
    from paddleocr import PaddleOCR
    return PaddleOCR(lang="korean", use_doc_orientation_classify=False, use_doc_unwarping=False,
                     use_textline_orientation=False, text_detection_model_name="PP-OCRv5_mobile_det",
                     text_recognition_model_name="korean_PP-OCRv5_mobile_rec",
                     enable_mkldnn=True, cpu_threads=CPU_THREADS)

def _worker(wid):
    # ★예측기는 이 스레드에서 생성·사용(PaddleOCR 예측기는 생성 스레드에 묶임)
    ocr = _build_ocr()
    list(ocr.predict(input=np.zeros((64, 64, 3), dtype="uint8")))   # 워밍업
    with _ready_lock:
        _ready_count[0] += 1
        if _ready_count[0] >= N_WORKERS: _ready.set()
    log.info("worker %d ready", wid)
    while True:
        img, fut, mode = _jobs.get()
        try:
            fut["result"] = parse_stats(ocr, img) if mode == "stats" else parse(ocr, img)
        except Exception as e:
            fut["error"] = e
            log.exception("ocr failed")
        finally:
            fut["event"].set()

# ─────────────────────────── FastAPI ───────────────────────────
app = FastAPI()

class OcrReq(BaseModel):
    image: str            # base64 (data URL 접두 있어도 됨)
    mimeType: str | None = None

@app.get("/health")
def health():
    return {"ok": _ready.is_set(), "workers": N_WORKERS, "cpuThreads": CPU_THREADS}

def _decode(req: OcrReq):
    b64 = req.image or ""
    if "," in b64[:64] and b64[:5].lower() in ("data:", "data"):
        b64 = b64.split(",", 1)[1]
    try:
        raw = base64.b64decode(b64)
        img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
    except Exception:
        raise HTTPException(status_code=400, detail="이미지 디코딩 실패")
    if img is None:
        raise HTTPException(status_code=400, detail="이미지 형식 오류")
    return img

def _run(img, mode):
    fut = {"event": threading.Event()}
    _jobs.put((img, fut, mode))
    if not fut["event"].wait(timeout=60):
        raise HTTPException(status_code=504, detail="OCR 처리 시간 초과")
    if "error" in fut:
        raise HTTPException(status_code=500, detail="OCR 처리 오류: " + str(fut["error"])[:200])
    return fut["result"]

@app.post("/ocr")
def ocr(req: OcrReq):
    return _run(_decode(req), "gear")

@app.post("/ocr-stats")
def ocr_stats(req: OcrReq):
    return _run(_decode(req), "stats")

if __name__ == "__main__":
    for w in range(N_WORKERS):
        threading.Thread(target=_worker, args=(w,), daemon=True).start()
    log.info("loading %d PaddleOCR workers (cpu_threads=%d each) ...", N_WORKERS, CPU_THREADS)
    _ready.wait()
    log.info("OCR sidecar ready on :%d", PORT)
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning")
