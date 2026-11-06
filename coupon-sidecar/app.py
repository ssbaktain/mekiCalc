# -*- coding: utf-8 -*-
"""
mekiCalc 쿠폰 자동 입력 사이드카 (로컬·헤드리스 Chromium).

넥슨 coupon-bff.nexon.com은 Cloudflare Bot Management 뒤에 있어 서버에서 직접
HTTP 요청하면 403(cfcode AC001)이다. 그래서 진짜 Chromium(Playwright)을 띄워
쿠폰 사이트에 들어가 Cloudflare JS 챌린지를 정상 통과한 뒤, 그 브라우저 세션
안에서 fetch로 조회/사용 API를 호출한다(=브라우저가 하는 일을 자동화).

Playwright 객체는 스레드 어피니티가 있어 브라우저는 단일 워커 스레드가 소유하고,
FastAPI 핸들러는 작업을 큐에 넣고 future로 결과를 기다린다(요청은 순차 처리).

엔드포인트:
  GET  /health                         → {ok, up, ageSec}
  POST /validate {npaCode, probePin}   → 회원코드 유효성(조회) {valid, code, message}
  POST /redeem   {npaCode, pin}        → 실제 쿠폰 사용 {ok, code, message}

실행: python app.py   (환경변수: COUPON_PORT 기본 8091 · COUPON_HEADLESS 기본 1)
의존: playwright, fastapi, uvicorn, pydantic  (+ playwright install chromium)
"""
import os, json, time, queue, threading, logging
from concurrent.futures import Future

# ★미니PC는 사용자명이 한글(비ASCII)이라 Playwright 기본 브라우저 경로(~\AppData\Local\ms-playwright)를
# C++ 레이어가 못 여는 경우가 있다(OCR paddle 캐시 경로와 동일 이슈). 비ASCII 홈이면 ASCII 경로로 돌린다.
# 설치(playwright install chromium)도 같은 PLAYWRIGHT_BROWSERS_PATH로 해야 런타임과 경로가 맞는다.
if not os.path.expanduser("~").isascii():
    os.environ.setdefault("PLAYWRIGHT_BROWSERS_PATH", r"C:\Users\Public\ms-playwright")

from contextlib import asynccontextmanager
from fastapi import FastAPI
from pydantic import BaseModel
import uvicorn
from playwright.sync_api import sync_playwright

PORT = int(os.environ.get("COUPON_PORT", "8091"))
HEADLESS = os.environ.get("COUPON_HEADLESS", "1") != "0"
GID = os.environ.get("COUPON_GID", "2694")          # 메이플 키우기 게임ID
LANG = os.environ.get("COUPON_LANG", "ko-kr")
PAGE = "https://coupon.nexon.com/ko-kr/maplestoryidle"
BFF = "https://coupon-bff.nexon.com/couponplus/v1/pins"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36")
REFRESH_SEC = 20 * 60   # __cf_bm TTL 30분 → 20분마다 재진입으로 세션 갱신
NAV_SETTLE_MS = 5000    # Cloudflare JS 챌린지 통과 대기

log = logging.getLogger("coupon")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


class BrowserWorker(threading.Thread):
    """Chromium을 소유하는 단일 워커. 큐의 작업을 순차 실행한다."""
    daemon = True

    def __init__(self):
        super().__init__()
        self.jobs: "queue.Queue[tuple]" = queue.Queue()
        self._pw = self._browser = self._ctx = self._page = None
        self._last_nav = 0.0

    # ── 세션 관리 (워커 스레드 내부에서만 호출) ──
    def _launch(self):
        self._pw = sync_playwright().start()
        self._browser = self._pw.chromium.launch(
            headless=HEADLESS, args=["--disable-blink-features=AutomationControlled"])
        self._ctx = self._browser.new_context(locale="ko-KR", user_agent=UA)
        self._page = self._ctx.new_page()
        self._navigate()

    def _navigate(self):
        self._page.goto(PAGE, wait_until="domcontentloaded", timeout=60000)
        self._page.wait_for_timeout(NAV_SETTLE_MS)
        self._last_nav = time.monotonic()
        log.info("navigated (title=%r)", self._page.title())

    def _ensure_fresh(self):
        if self._page is None:
            self._launch()
        elif time.monotonic() - self._last_nav > REFRESH_SEC:
            self._navigate()

    def _api(self, method, url, body):
        """Cloudflare 통과 세션 안에서 fetch. 403/-1이면 재진입 후 1회 재시도."""
        for attempt in (1, 2):
            self._ensure_fresh()
            res = self._page.evaluate(
                """async ({method, url, body, gid, lang}) => {
                    try {
                        const opt = { method, headers: {
                            'content-type':'application/json', 'gid':gid, 'lang':lang } };
                        if (body !== null) opt.body = JSON.stringify(body);
                        const r = await fetch(url, opt);
                        return { status: r.status, body: await r.text() };
                    } catch (e) { return { status: -1, body: String(e) }; }
                }""",
                {"method": method, "url": url, "body": body, "gid": GID, "lang": LANG})
            if res["status"] not in (-1, 403) or attempt == 2:
                return res
            log.warning("api %s -> %s; re-navigating and retrying", url, res["status"])
            self._navigate()
        return res

    @staticmethod
    def _parse(res):
        try:
            j = json.loads(res["body"])
            return str(j.get("code")), (j.get("message") or "")
        except Exception:
            return None, f'http{res["status"]}: {res["body"][:160]}'

    # ── 작업 ──
    def _validate(self, npa, probe_pin):
        url = f"{BFF}/coupon-info?pin={probe_pin}&npaCode={npa}"
        code, msg = self._parse(self._api("GET", url, None))
        # 0 = 유효. 1001-1003 = 잘못된 회원코드. 1001-3009 = probePin이 존재하지 않음(검증불가).
        return {"valid": code == "0", "code": code, "message": msg}

    def _redeem(self, npa, pin):
        url = f"{BFF}/use?npaCode={npa}"
        order_id = f"box_{int(time.time()*1000)}_{pin}"
        body = {"orderId": order_id, "pin": pin, "referrer": "box_game"}
        code, msg = self._parse(self._api("POST", url, body))
        return {"ok": code == "0", "code": code, "message": msg}

    def run(self):
        try:                       # 시작 시 예열(첫 실요청 지연 제거). 실패해도 이후 지연 기동으로 복구.
            self._ensure_fresh()
        except Exception:
            log.exception("initial warmup failed (will retry lazily)")
        while True:
            kind, args, fut = self.jobs.get()
            try:
                if kind == "validate":
                    fut.set_result(self._validate(*args))
                elif kind == "redeem":
                    fut.set_result(self._redeem(*args))
                elif kind == "health":
                    age = (time.monotonic() - self._last_nav) if self._page else -1
                    fut.set_result({"ok": True, "up": self._page is not None, "ageSec": round(age)})
            except Exception as e:
                log.exception("job %s failed", kind)
                fut.set_exception(e)

    def submit(self, kind, *args):
        fut: Future = Future()
        self.jobs.put((kind, args, fut))
        return fut


worker = BrowserWorker()


@asynccontextmanager
async def lifespan(app):
    worker.start()
    yield


app = FastAPI(lifespan=lifespan)


class ValidateReq(BaseModel):
    npaCode: str
    probePin: str


class RedeemReq(BaseModel):
    npaCode: str
    pin: str


@app.get("/health")
async def health():
    import asyncio
    return await asyncio.wrap_future(worker.submit("health"))


@app.post("/validate")
async def validate(req: ValidateReq):
    import asyncio
    return await asyncio.wrap_future(worker.submit("validate", req.npaCode.strip(), req.probePin.strip()))


@app.post("/redeem")
async def redeem(req: RedeemReq):
    import asyncio
    return await asyncio.wrap_future(worker.submit("redeem", req.npaCode.strip(), req.pin.strip()))


if __name__ == "__main__":
    log.info("coupon sidecar: port=%d headless=%s", PORT, HEADLESS)
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning")
