# -*- coding: utf-8 -*-
"""
mekiCalc 장비 비교 OCR 사이드카 (로컬·무료·무제한).
PaddleOCR(한국어) + 규칙 파서로 장비 상세 스크린샷 → {leftPart,rightPart,left,right} 원문 JSON.
Java(web)의 LocalOcr 제공자가 localhost로 호출하고, statId 매핑·주/부 분리·부위 정규화는 Java가 수행.

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

# ─────────────────────────── 파서(검증본) ───────────────────────────
VALUE_RE = re.compile(r"^[+\-]?\d[\d,.\s%+\-]*$")      # 숫자로 시작·숫자/기호만 = 값
HANGUL = re.compile(r"[가-힣]")
FIRST_NUM = re.compile(r"[+\-]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?%?")   # 흰 값(첫 숫자)만
ANCHOR = "장착효과"
PART_SYN = {"어깨장식": "견장"}
PART_KEYS = ["투구","상의","망토","견장","어깨장식","하의","장갑","벨트","신발","반지2","반지",
             "목걸이","얼굴장식","눈장식","귀고리","포켓"]

def _norm(s): return re.sub(r"\s", "", s or "")
def _is_value(t): return bool(VALUE_RE.match(t)) and not HANGUL.search(t)
def _is_name(t): return bool(HANGUL.search(t))
def _is_anchor(t):
    n = _norm(t); return n.endswith("효과") and 2 <= len(n) <= 6
def _clean_value(t):
    parts = t.split(); m = FIRST_NUM.match(parts[0] if parts else t)
    return m.group() if m else t

def _boxes(ocr, img):
    out = []
    for r in ocr.predict(input=img):
        txts = r.get("rec_texts", []); scs = r.get("rec_scores", []); pls = r.get("rec_polys", r.get("dt_polys", []))
        for i, t in enumerate(txts):
            if i >= len(pls): continue
            p = pls[i]; xs = [q[0] for q in p]; ys = [q[1] for q in p]
            out.append(dict(text=(t or "").strip(), conf=(scs[i] if i < len(scs) else 0),
                            xl=min(xs), xr=max(xs), xc=sum(xs) / len(xs), yc=sum(ys) / len(ys)))
    return [b for b in out if b["conf"] >= 0.5 and b["text"]]

def _panel(bs, lo, hi, yt):
    band = [b for b in bs if lo <= b["xc"] < hi and b["yc"] > yt - 6]; band.sort(key=lambda b: b["yc"])
    rows = []
    for b in band:
        if rows and abs(b["yc"] - rows[-1][0]) <= 14: rows[-1][1].append(b)
        else: rows.append([b["yc"], [b]])
    opts = []
    for _, rb in rows:
        rb.sort(key=lambda b: b["xl"])
        nb = next((b for b in rb if _is_name(b["text"])), None)
        if nb is None: continue
        vc = [b for b in rb if _is_value(b["text"]) and b["xc"] > nb["xc"]]
        if not vc: continue
        vb = min(vc, key=lambda b: b["xc"])
        if ANCHOR in _norm(nb["text"]) or "전투력" in nb["text"]: continue
        opts.append({"label": nb["text"].strip(), "value": _clean_value(vb["text"])})
    return opts

def _part(bs, lo, hi, yb):
    for b in sorted([b for b in bs if lo <= b["xc"] < hi and b["yc"] < yb and HANGUL.search(b["text"])], key=lambda b: b["yc"]):
        for k in PART_KEYS:
            if k in _norm(b["text"]): return PART_SYN.get(k, k)
    return ""

def _enhance(img, lo=120, hi=230):
    f = np.clip((img.astype(np.float32) - lo) / (hi - lo), 0, 1)
    return (f * 255).astype(np.uint8)

def _parse_one(ocr, img):
    bs = _boxes(ocr, img)
    an = sorted([b for b in bs if _is_anchor(b["text"])], key=lambda b: b["xc"])
    if not an:
        return {"leftPart": "", "rightPart": "", "left": [], "right": []}
    if len(an) < 2:
        a = an[0]; pitch = 360
        return {"leftPart": _part(bs, a["xc"] - 30, a["xc"] + pitch - 30, a["yc"]), "rightPart": "",
                "left": _panel(bs, a["xc"] - 30, a["xc"] + pitch - 30, a["yc"]), "right": []}
    aL, aR = an[0], an[1]; p = aR["xc"] - aL["xc"]
    return {"leftPart": _part(bs, aL["xc"] - 30, aL["xc"] + p - 30, aL["yc"]),
            "rightPart": _part(bs, aR["xc"] - 30, aR["xc"] + p - 30, aR["yc"]),
            "left": _panel(bs, aL["xc"] - 30, aL["xc"] + p - 30, aL["yc"]),
            "right": _panel(bs, aR["xc"] - 30, aR["xc"] + p - 30, aR["yc"])}

def _n_opts(r): return len(r.get("left", [])) + len(r.get("right", []))

def parse(ocr, img):
    r1 = _parse_one(ocr, img)                     # raw 먼저(어두운 크롭에 강함)
    if _n_opts(r1) >= 6: return r1
    r2 = _parse_one(ocr, _enhance(img))           # 부실하면 대비강화(배경 많은 스샷에 강함)
    return r2 if _n_opts(r2) > _n_opts(r1) else r1

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
        img, fut = _jobs.get()
        try:
            fut["result"] = parse(ocr, img)
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

@app.post("/ocr")
def ocr(req: OcrReq):
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
    fut = {"event": threading.Event()}
    _jobs.put((img, fut))
    if not fut["event"].wait(timeout=60):
        raise HTTPException(status_code=504, detail="OCR 처리 시간 초과")
    if "error" in fut:
        raise HTTPException(status_code=500, detail="OCR 처리 오류: " + str(fut["error"])[:200])
    return fut["result"]

if __name__ == "__main__":
    for w in range(N_WORKERS):
        threading.Thread(target=_worker, args=(w,), daemon=True).start()
    log.info("loading %d PaddleOCR workers (cpu_threads=%d each) ...", N_WORKERS, CPU_THREADS)
    _ready.wait()
    log.info("OCR sidecar ready on :%d", PORT)
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning")
