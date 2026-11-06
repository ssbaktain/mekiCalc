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
        return out;
    }

    public List<Map<String, Object>> members() { return store.list(); }

    public boolean removeMember(long id) { return store.remove(id); }

    // ─────────────────────────── 쿠폰 사용(배치) ───────────────────────────

    /**
     * 모든 등록 회원코드에 쿠폰을 입력(비동기 배치). 이미 실행 중이면 거절한다.
     * @param source "manual" | "auto"
     * @return 시작 시점 상태 스냅샷
     */
    public Map<String, Object> redeemAll(String pin, String source) {
        String code = pin == null ? "" : pin.trim();
        if (code.isEmpty()) throw new IllegalArgumentException("쿠폰코드가 필요합니다.");
        if (!sidecar.enabled()) throw new IllegalStateException("쿠폰 사이드카가 설정되지 않았습니다.");
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("이미 쿠폰 입력이 진행 중입니다.");

        List<CouponMemberStore.Member> targets = store.all();
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("running", true);
        st.put("pin", code);
        st.put("source", source);
        st.put("total", targets.size());
        st.put("done", 0);
        st.put("startedAt", (double) System.currentTimeMillis());
        st.put("finishedAt", 0.0);
        st.put("results", Collections.synchronizedList(new ArrayList<Map<String, Object>>()));
        runState = st;

        runner.submit(() -> runBatch(code, source, targets, st));
        return status();
    }

    @SuppressWarnings("unchecked")
    private void runBatch(String pin, String source, List<CouponMemberStore.Member> targets, Map<String, Object> st) {
        List<Map<String, Object>> results = (List<Map<String, Object>>) st.get("results");
        boolean anyDefinitive = false;   // 넥슨이 실제 응답(code!=null)한 건이 하나라도 있으면 processed 처리
        try {
            for (int i = 0; i < targets.size(); i++) {
                CouponMemberStore.Member mem = targets.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", mem.id);
                row.put("nick", mem.nick);
                row.put("npaCode", mem.npaCode);
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
                st.put("done", i + 1);

                if (i < targets.size() - 1) {
                    int lo = delayMinMs, hi = Math.max(lo, delayMaxMs);
                    long wait = (hi > lo) ? ThreadLocalRandom.current().nextLong(lo, hi + 1L) : lo;
                    try { Thread.sleep(wait); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        } finally {
            st.put("running", false);
            st.put("finishedAt", (double) System.currentTimeMillis());
            // 넥슨이 실제로 응답한 배치만 '처리 완료'로 기록(자동 중복 방지). 전건 사이드카 장애면 미처리로 남겨 재시도.
            if (anyDefinitive) { processedPins.add(pin); save(); }
            running.set(false);
        }
    }

    /** 현재/마지막 배치 상태(없으면 idle). */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sidecarEnabled", sidecar.enabled());
        out.put("autoRedeem", autoRedeem);
        out.put("probePin", probePin);
        out.put("delayMinMs", delayMinMs);
        out.put("delayMaxMs", delayMaxMs);
        synchronized (processedPins) { out.put("processedPins", new ArrayList<>(processedPins)); }
        out.put("busy", running.get());
        out.put("run", runState);
        return out;
    }

    // ─────────────────────────── 자동 트리거(스케줄러가 호출) ───────────────────────────

    /** 새 쿠폰 감지 시 자동 입력. autoRedeem on·미실행·미처리 쿠폰이 있으면 한 건 시작. */
    public void check() {
        if (!autoRedeem || !sidecar.enabled() || running.get()) return;
        for (Map<String, Object> c : activeCoupons()) {
            Object codeO = c.get("code");
            if (codeO == null) continue;
            String code = codeO.toString().trim();
            if (code.isEmpty() || processedPins.contains(code)) continue;
            try {
                System.out.println("[coupon] 새 쿠폰 자동 입력 시작: " + code);
                redeemAll(code, "auto");
            } catch (Exception e) {
                System.err.println("[coupon] 자동 입력 시작 실패(" + code + "): " + e.getMessage());
            }
            return;   // 한 틱에 한 건만
        }
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
