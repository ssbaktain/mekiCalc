package mekicalc.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import mekicalc.json.Json;

/**
 * 쿠폰 자동 입력 오케스트레이터. 등록된 회원코드 각각에 대해 쿠폰 사이드카(헤드리스 Chromium)로
 * 넥슨 쿠폰 사이트에 입력한다. 회원코드 간 2~3초(설정) 랜덤 딜레이로 입력 오류를 방지한다.
 *
 * <ul>
 *   <li>등록 시 조회로 회원코드 유효성 검증(잘못된 코드 거름).</li>
 *   <li>관리자 "지금 적용"(수동) + 새 쿠폰 감지 시 자동(스케줄러 {@code check()}).</li>
 *   <li>한 번에 하나의 배치만 실행(single-thread executor + running 가드).</li>
 *   <li>자동은 {@code processedPins}로 중복 입력 방지(사이드카 장애로 전건 실패면 미처리로 남겨 재시도).</li>
 *   <li>최초 1회 baseline: 배포 시점의 현재 유효 쿠폰을 processed로 기록 → 이후 '새로 뜬' 쿠폰만 자동 입력.</li>
 *   <li>신규 회원 등록 시(자동 on): 그 회원에게만 현재 유효 쿠폰 전부를 catch-up 입력(기존 회원은 이미 받음).</li>
 * </ul>
 */
public final class CouponService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 조회 응답 code: 존재하지 않는 회원 정보(잘못된 회원코드). */
    private static final String CODE_BAD_MEMBER = "1001-1003";

    private final Path file;                 // data/coupon.json (설정·상태 되쓰기)
    private final CouponSidecarClient sidecar;
    private final CouponMemberStore store;
    private final NoticeService notices;

    // 설정(런타임 가변, 파일 영속)
    private volatile String sidecarUrl;
    private volatile String probePin;
    private volatile boolean autoRedeem;
    private volatile int delayMinMs;
    private volatile int delayMaxMs;
    private volatile boolean baselined;   // 최초 1회 현재 유효 쿠폰을 processed로 기록했는지(기존 백로그 대량 입력 방지)
    private final Set<String> processedPins = Collections.synchronizedSet(new LinkedHashSet<>());

    // 실행 상태
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "coupon-redeem"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Object> runState = null;   // 현재/마지막 배치 상태(스냅샷)

    public CouponService(Path dataDir, CouponSidecarClient sidecar, CouponMemberStore store,
                         NoticeService notices, CouponConfig cfg) {
        this.file = dataDir.resolve("coupon.json");
        this.sidecar = sidecar;
        this.store = store;
        this.notices = notices;
        this.sidecarUrl = cfg.sidecarUrl;
        this.probePin = cfg.probePin == null ? "" : cfg.probePin;
        this.autoRedeem = cfg.autoRedeem;
        this.delayMinMs = Math.max(0, cfg.delayMinMs);
        this.delayMaxMs = Math.max(this.delayMinMs, cfg.delayMaxMs);
        this.baselined = cfg.baselined;
        this.processedPins.addAll(cfg.processedPins);
    }

    private synchronized void save() {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sidecarUrl", sidecarUrl);
            m.put("probePin", probePin);
            m.put("autoRedeem", autoRedeem);
            m.put("delayMinMs", delayMinMs);
            m.put("delayMaxMs", delayMaxMs);
            m.put("baselined", baselined);
            List<String> pins; synchronized (processedPins) { pins = new ArrayList<>(processedPins); }
            m.put("processedPins", pins);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(m), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { System.err.println("coupon.json 저장 실패: " + e); }
    }

    private String today() { return LocalDate.now(KST).toString(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> activeCoupons() {
        Object c = notices.coupons(today()).get("coupons");
        return (c instanceof List) ? (List<Map<String, Object>>) c : new ArrayList<>();
    }

    /**
     * 검증용 프로브 핀. 설정값이 있으면 그걸 쓰고, 없으면 현재 활성 쿠폰 중 하나를 <b>랜덤</b>으로 고른다
     * (항상 같은 코드로만 조회하면 반복 패턴이 남을 수 있어 분산). <b>상시(종료일 없는)</b> 쿠폰을 우선
     * 후보로 삼고, 상시가 없으면 전체 활성 쿠폰에서 고른다. 둘 다 없으면 null(검증 불가).
     */
    private String probePinFor() {
        if (probePin != null && !probePin.isBlank()) return probePin.trim();
        List<String> perm = new ArrayList<>();   // 상시(종료일 없음) — 우선 후보
        List<String> any = new ArrayList<>();
        for (Map<String, Object> c : activeCoupons()) {
            Object codeO = c.get("code");
            if (codeO == null || codeO.toString().isBlank()) continue;
            String code = codeO.toString().trim();
            any.add(code);
            Object end = c.get("end");
            if (end == null || end.toString().isBlank()) perm.add(code);   // 종료일 없음 = 상시
        }
        List<String> pool = perm.isEmpty() ? any : perm;
        if (pool.isEmpty()) return null;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    // ─────────────────────────── 회원코드 ───────────────────────────

    /** 회원코드 검증(조회). 반환 {valid(Boolean|null), code, message, probed(boolean)}. */
    public Map<String, Object> validate(String npaCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        String npa = CouponMemberStore.normalize(npaCode);
        if (npa.isEmpty()) { out.put("valid", false); out.put("message", "회원코드가 필요합니다."); out.put("probed", false); return out; }
        if (!sidecar.enabled()) { out.put("valid", null); out.put("message", "쿠폰 사이드카가 설정되지 않아 검증할 수 없습니다."); out.put("probed", false); return out; }
        String probe = probePinFor();
        if (probe == null) { out.put("valid", null); out.put("message", "검증에 쓸 쿠폰이 없어 지금은 검증할 수 없습니다(프로브 핀 미설정)."); out.put("probed", false); return out; }
        try {
            Map<String, Object> r = sidecar.validate(npa, probe);
            out.put("valid", r.get("valid"));
            out.put("code", r.get("code"));
            out.put("message", r.get("message"));
            out.put("probed", true);
        } catch (Exception e) {
            out.put("valid", null); out.put("message", "검증 실패(사이드카 오류): " + e.getMessage()); out.put("probed", false);
        }
        return out;
    }

    /**
     * 회원코드 등록. 가능하면 조회로 검증해 <b>명백히 잘못된 회원코드(1001-1003)는 거절</b>한다.
     * 검증 불가(사이드카 다운·프로브 없음)면 미검증 상태로 등록한다.
     * @return 등록된 Member의 클라 맵 + 검증 정보
     */
    public Map<String, Object> addMember(String nick, String npaCode) {
        String npa = CouponMemberStore.normalize(npaCode);
        if (npa.isEmpty()) throw new IllegalArgumentException("회원코드가 필요합니다.");
        if (store.byNpa(npa) != null) throw new IllegalArgumentException("이미 등록된 회원코드입니다.");
        Map<String, Object> v = validate(npa);
        Object validO = v.get("valid");
        if (Boolean.FALSE.equals(validO) && CODE_BAD_MEMBER.equals(String.valueOf(v.get("code")))) {
            throw new IllegalArgumentException("존재하지 않는 회원코드입니다. 다시 확인해 주세요.");
        }
        CouponMemberStore.Member m = store.add(nick, npa);
        Boolean valid = (validO instanceof Boolean) ? (Boolean) validO : null;
        store.markCheck(m.id, valid, str(v.get("code")), str(v.get("message")));
        Map<String, Object> out = store.get(m.id).toClientMap();
        out.put("verified", valid);
        out.put("verifyMessage", v.get("message"));
        // 최초 등록 catch-up: 자동 입력이 켜져 있으면 현재 유효 쿠폰 전부를 이 신규 회원에게만 입력(기존 회원은 이미 받음).
        if (autoRedeem && sidecar.enabled()) {
            List<String> pins = activeCouponCodes();
            if (!pins.isEmpty()) {
                try { startBatch(List.of(store.get(m.id)), pins, "register", false); out.put("catchup", pins.size()); }
                catch (Exception e) { System.err.println("[coupon] 등록 catch-up 실패: " + e.getMessage()); }
            }
        }
        return out;
    }

    public List<Map<String, Object>> members() { return store.list(); }

    public boolean removeMember(long id) { return store.remove(id); }

    // ─────────────────────────── 쿠폰 사용(배치) ───────────────────────────

    /**
     * 수동 "지금 적용": 전 회원 × 단일 쿠폰. 진행 중이면 거절한다.
     * @param source "manual" | "auto"
     */
    public Map<String, Object> redeemAll(String pin, String source) {
        String code = pin == null ? "" : pin.trim();
        if (code.isEmpty()) throw new IllegalArgumentException("쿠폰코드가 필요합니다.");
        return startBatch(store.all(), List.of(code), source, true);
    }

    /**
     * 배치 시작(비동기). targets × pins 모든 조합을 순서대로 입력하고 각 입력 사이에 딜레이를 둔다.
     * rejectIfBusy=true면 진행 중일 때 예외(수동), false면 single-thread 큐에 넣어 뒤에 실행(자동·등록).
     */
    private Map<String, Object> startBatch(List<CouponMemberStore.Member> targets, List<String> pins,
                                           String source, boolean rejectIfBusy) {
        if (!sidecar.enabled()) throw new IllegalStateException("쿠폰 사이드카가 설정되지 않았습니다.");
        if (pins == null || pins.isEmpty()) throw new IllegalArgumentException("쿠폰코드가 필요합니다.");
        if (targets == null || targets.isEmpty()) throw new IllegalArgumentException("대상 회원코드가 없습니다.");
        if (rejectIfBusy && running.get()) throw new IllegalStateException("이미 쿠폰 입력이 진행 중입니다.");
        final List<CouponMemberStore.Member> tg = new ArrayList<>(targets);
        final List<String> ps = new ArrayList<>(pins);
        runner.submit(() -> runBatch(tg, ps, source));
        return status();
    }

    /** 실제 배치 실행(단일 워커 스레드). pins × targets 각 조합을 입력, 각 사이 딜레이. */
    private void runBatch(List<CouponMemberStore.Member> targets, List<String> pins, String source) {
        running.set(true);
        List<Map<String, Object>> results = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        int totalSteps = pins.size() * targets.size();
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("running", true);
        st.put("pin", pins.size() == 1 ? pins.get(0) : (pins.size() + "개 쿠폰"));
        st.put("pins", new ArrayList<>(pins));
        st.put("source", source);
        st.put("total", totalSteps);
        st.put("done", 0);
        st.put("startedAt", (double) System.currentTimeMillis());
        st.put("finishedAt", 0.0);
        st.put("results", results);
        runState = st;
        int done = 0;
        try {
            for (String pin : pins) {
                boolean anyDefinitive = false;   // 넥슨이 실제 응답(code!=null)한 건이 하나라도 있는가
                for (CouponMemberStore.Member mem : targets) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", mem.id);
                    row.put("nick", mem.nick);
                    row.put("npaCode", mem.npaCode);
                    row.put("pin", pin);
                    String rcode = null, rmsg; boolean ok = false;
                    try {
                        Map<String, Object> r = sidecar.redeem(mem.npaCode, pin);
                        ok = Boolean.TRUE.equals(r.get("ok"));
                        rcode = str(r.get("code"));
                        rmsg = str(r.get("message"));
                        anyDefinitive = true;
                    } catch (Exception e) {
                        rmsg = "사이드카 오류: " + e.getMessage();
                    }
                    row.put("ok", ok);
                    row.put("code", rcode);
                    row.put("message", rmsg);
                    results.add(row);
                    store.markRedeem(mem.id, pin, rcode, rmsg);
                    st.put("done", ++done);
                    if (done < totalSteps) sleepDelay();
                }
                // 자동(새 쿠폰 전 회원 입력)만 processed로 기록 → 중복 방지. 수동·등록 catch-up은 기록하지 않는다.
                if ("auto".equals(source) && anyDefinitive) { processedPins.add(pin); save(); }
            }
        } finally {
            st.put("running", false);
            st.put("finishedAt", (double) System.currentTimeMillis());
            running.set(false);
        }
    }

    private void sleepDelay() {
        int lo = delayMinMs, hi = Math.max(lo, delayMaxMs);
        long wait = (hi > lo) ? ThreadLocalRandom.current().nextLong(lo, hi + 1L) : lo;
        try { Thread.sleep(wait); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /** 현재/마지막 배치 상태(없으면 idle). */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sidecarEnabled", sidecar.enabled());
        out.put("autoRedeem", autoRedeem);
        out.put("probePin", probePin);
        out.put("delayMinMs", delayMinMs);
        out.put("delayMaxMs", delayMaxMs);
        out.put("baselined", baselined);
        synchronized (processedPins) { out.put("processedPins", new ArrayList<>(processedPins)); }
        out.put("busy", running.get());
        out.put("run", runState);
        return out;
    }

    // ─────────────────────────── 자동 트리거(스케줄러가 호출) ───────────────────────────

    /**
     * 스케줄러 주기 호출. 최초 1회는 현재 유효 쿠폰을 모두 '처리 완료'로 기록(baseline)만 하고 입력하지 않는다
     * (배포 시점의 기존 백로그를 쏟아붓지 않기 위함). 이후엔 자동 on이면 '미처리(=새로 뜬)' 쿠폰 한 건을 전 회원에 입력.
     */
    public void check() {
        if (!baselined) { baselineNow(); return; }
        if (!autoRedeem || !sidecar.enabled() || running.get()) return;
        for (String code : activeCouponCodes()) {
            if (processedPins.contains(code)) continue;
            try {
                System.out.println("[coupon] 새 쿠폰 자동 입력 시작: " + code);
                startBatch(store.all(), List.of(code), "auto", false);
            } catch (Exception e) {
                System.err.println("[coupon] 자동 입력 시작 실패(" + code + "): " + e.getMessage());
            }
            return;   // 한 틱에 한 건만(부하 분산)
        }
    }

    /** 최초 1회: 현재 유효한 쿠폰 전부를 '처리 완료'로 기록 → 이후 새로 뜨는 쿠폰만 자동 입력된다. */
    private synchronized void baselineNow() {
        List<String> codes = activeCouponCodes();
        processedPins.addAll(codes);
        baselined = true;
        save();
        System.out.println("[coupon] baseline: 현재 유효 쿠폰 " + codes.size() + "개를 처리 완료로 기록(이후 새 쿠폰만 자동 입력)");
    }

    /** 현재 유효(활성) 쿠폰 코드 목록(공지 추적 소스). */
    private List<String> activeCouponCodes() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : activeCoupons()) {
            String code = str(c.get("code"));
            if (code != null && !code.isBlank()) out.add(code.trim());
        }
        return out;
    }

    // ─────────────────────────── 설정 ───────────────────────────

    public synchronized void updateSettings(Boolean auto, String probe, Integer dMin, Integer dMax) {
        if (auto != null) autoRedeem = auto;
        if (probe != null) probePin = probe.trim();
        if (dMin != null) delayMinMs = Math.max(0, dMin);
        if (dMax != null) delayMaxMs = Math.max(delayMinMs, dMax);
        save();
    }

    /** 자동으로 처리 완료 표시된 쿠폰 기록 삭제(해당 쿠폰 자동 재시도 허용). */
    public synchronized void clearProcessed(String pin) {
        if (pin == null || pin.isBlank()) processedPins.clear();
        else processedPins.remove(pin.trim());
        save();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
