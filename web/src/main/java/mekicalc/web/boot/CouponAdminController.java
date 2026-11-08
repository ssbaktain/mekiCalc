package mekicalc.web.boot;

import static mekicalc.web.boot.Req.num;
import static mekicalc.web.boot.Req.strOpt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.web.CouponService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 쿠폰 자동 입력 API. {@link AdminAuthInterceptor}가 /api/admin/** 를 관리자 세션으로
 * 게이팅하므로 여기서 권한 재검사는 하지 않는다.
 *
 * <ul>
 *   <li>회원코드 등록(조회 검증)/삭제/목록</li>
 *   <li>"지금 적용"(수동 배치) + 진행 상태</li>
 *   <li>자동 입력 on/off·프로브 핀·딜레이 설정</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/coupon")
public class CouponAdminController {

    private final CouponService coupon;

    public CouponAdminController(CouponService coupon) {
        this.coupon = coupon;
    }

    /** 회원코드 목록 + 현재 상태(설정·진행). */
    @GetMapping("/members")
    public Object members() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("members", coupon.members());
        out.put("status", coupon.status());
        return out;
    }

    /** 회원코드 사전 검증(등록 전 미리보기). body: {npaCode}. */
    @PostMapping("/validate")
    public Object validate(@RequestBody Map<String, Object> req) {
        String npa = strOpt(req, "npaCode");
        if (npa == null || npa.isBlank()) throw new IllegalArgumentException("회원코드가 필요합니다.");
        return coupon.validate(npa);
    }

    /** 회원코드 등록(조회 검증 — 명백히 잘못된 코드는 거절). body: {nick?, npaCode}. */
    @PostMapping("/members")
    public Object add(@RequestBody Map<String, Object> req) {
        String npa = strOpt(req, "npaCode");
        if (npa == null || npa.isBlank()) throw new IllegalArgumentException("회원코드가 필요합니다.");
        return coupon.addMember(strOpt(req, "nick"), npa);
    }

    /** 회원코드 삭제. */
    @DeleteMapping("/members/{id}")
    public Object remove(@PathVariable long id) {
        return Map.of("ok", coupon.removeMember(id));
    }

    /** 현재/마지막 배치 상태(진행률·회원별 결과). */
    @GetMapping("/status")
    public Object status() {
        return coupon.status();
    }

    /** 현재 유효(활성) 쿠폰 목록(수동 선택 UI용). */
    @GetMapping("/available")
    public Object available() {
        return Map.of("coupons", coupon.availableCoupons());
    }

    /** "지금 적용"(수동, 전 회원 × 단일 쿠폰). body: {pin}. 이미 진행 중이면 오류. */
    @PostMapping("/redeem")
    public Object redeem(@RequestBody Map<String, Object> req) {
        String pin = strOpt(req, "pin");
        if (pin == null || pin.isBlank()) throw new IllegalArgumentException("쿠폰코드가 필요합니다.");
        return coupon.redeemAll(pin, "manual");
    }

    /** 수동(세분화): 선택한 회원 × 선택한 쿠폰 입력. body: {memberIds:[long], pins:[string]}. */
    @PostMapping("/redeem-select")
    public Object redeemSelect(@RequestBody Map<String, Object> req) {
        List<Long> ids = new ArrayList<>();
        Object mi = req.get("memberIds");
        if (mi instanceof List) for (Object o : (List<?>) mi) if (o instanceof Number) ids.add(((Number) o).longValue());
        List<String> pins = new ArrayList<>();
        Object pp = req.get("pins");
        if (pp instanceof List) for (Object o : (List<?>) pp) if (o != null) pins.add(o.toString());
        return coupon.redeemSelected(ids, pins);
    }

    /** 설정 변경. body: {autoRedeem?, registerCatchup?, probePin?, delayMinMs?, delayMaxMs?}. */
    @PostMapping("/settings")
    public Object settings(@RequestBody Map<String, Object> req) {
        Boolean auto = (req.get("autoRedeem") instanceof Boolean) ? (Boolean) req.get("autoRedeem") : null;
        Boolean catchup = (req.get("registerCatchup") instanceof Boolean) ? (Boolean) req.get("registerCatchup") : null;
        String probe = req.containsKey("probePin") ? strOpt(req, "probePin") : null;
        Integer dMin = req.containsKey("delayMinMs") ? (int) num(req, "delayMinMs", 2000) : null;
        Integer dMax = req.containsKey("delayMaxMs") ? (int) num(req, "delayMaxMs", 3000) : null;
        coupon.updateSettings(auto, catchup, probe, dMin, dMax);
        return coupon.status();
    }

    /** 자동 처리 완료 기록 삭제(해당 쿠폰 자동 재시도 허용). body: {pin?} (없으면 전체). */
    @PostMapping("/processed/clear")
    public Object clearProcessed(@RequestBody(required = false) Map<String, Object> req) {
        coupon.clearProcessed(req == null ? null : strOpt(req, "pin"));
        return coupon.status();
    }
}
