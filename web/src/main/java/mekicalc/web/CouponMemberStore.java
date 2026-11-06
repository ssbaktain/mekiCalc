package mekicalc.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 쿠폰 자동 입력 대상 회원코드 레지스트리(파일: data/coupon_members.json).
 * 관리자가 넥슨 회원코드(npaCode)를 등록해 두면, 새 쿠폰이 뜰 때 {@link CouponService}가
 * 각 회원코드에 대해 넥슨 쿠폰 사이트로 자동 입력한다.
 *
 * <p>개인정보(게임 회원코드)라 gitignore·배포 제외. 저장은 BanStore와 동일하게
 * synchronized + {@code .tmp} 원자적 교체. {@code npaCode} 기준 중복 등록을 막는다.
 */
public final class CouponMemberStore {

    public static final class Member {
        public long id;
        public String nick;        // 관리자 식별용 표시명(캐릭터명 등)
        public String npaCode;      // 넥슨 회원코드(쿼리 파라미터)
        public long createdAt;      // epoch ms
        public Boolean valid;       // 등록 시 조회 검증 결과(null=미검증)
        public String lastCheckCode;    // 마지막 검증 응답 code
        public String lastCheckMsg;     // 마지막 검증 응답 message
        public String lastRedeemPin;    // 마지막 사용 시도 쿠폰
        public String lastRedeemCode;   // 마지막 사용 응답 code
        public String lastRedeemMsg;    // 마지막 사용 응답 message
        public long lastRedeemAt;       // 마지막 사용 시도 시각(epoch ms)

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", (double) id);
            m.put("nick", nick);
            m.put("npaCode", npaCode);
            m.put("createdAt", (double) createdAt);
            m.put("valid", valid);
            m.put("lastCheckCode", lastCheckCode);
            m.put("lastCheckMsg", lastCheckMsg);
            m.put("lastRedeemPin", lastRedeemPin);
            m.put("lastRedeemCode", lastRedeemCode);
            m.put("lastRedeemMsg", lastRedeemMsg);
            m.put("lastRedeemAt", (double) lastRedeemAt);
            return m;
        }
        static Member fromMap(Map<String, Object> m) {
            Member x = new Member();
            x.id = (long) Json.num(m, "id", 0);
            x.nick = str(m, "nick");
            x.npaCode = str(m, "npaCode");
            x.createdAt = (long) Json.num(m, "createdAt", 0);
            x.valid = (m.get("valid") instanceof Boolean) ? (Boolean) m.get("valid") : null;
            x.lastCheckCode = str(m, "lastCheckCode");
            x.lastCheckMsg = str(m, "lastCheckMsg");
            x.lastRedeemPin = str(m, "lastRedeemPin");
            x.lastRedeemCode = str(m, "lastRedeemCode");
            x.lastRedeemMsg = str(m, "lastRedeemMsg");
            x.lastRedeemAt = (long) Json.num(m, "lastRedeemAt", 0);
            return x;
        }
        private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : v.toString(); }

        public Map<String, Object> toClientMap() { return toMap(); }
    }

    private final Path file;
    private final List<Member> members = new ArrayList<>();   // 등록 순(append)
    private long seq = 0;

    public CouponMemberStore(Path dataDir) {
        this.file = dataDir.resolve("coupon_members.json");
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            for (Object o : Json.asList(Json.parse(Files.readString(file, StandardCharsets.UTF_8)))) {
                Member x = Member.fromMap(Json.asMap(o));
                members.add(x);
                if (x.id > seq) seq = x.id;
            }
        } catch (Exception e) { System.err.println("coupon_members.json 읽기 실패(무시): " + e); }
    }

    private synchronized void save() {
        try {
            List<Object> arr = new ArrayList<>(members.size());
            for (Member x : members) arr.add(x.toMap());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(arr), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { System.err.println("coupon_members.json 저장 실패: " + e); }
    }

    /** npaCode 정규화(공백 제거·대문자). 넥슨 회원코드는 영숫자. */
    public static String normalize(String npa) {
        return npa == null ? "" : npa.trim().toUpperCase();
    }

    public synchronized List<Member> all() { return new ArrayList<>(members); }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>(members.size());
        for (Member x : members) out.add(x.toClientMap());
        return out;
    }

    public synchronized Member byNpa(String npaCode) {
        String n = normalize(npaCode);
        for (Member x : members) if (n.equals(normalize(x.npaCode))) return x;
        return null;
    }

    public synchronized Member get(long id) {
        for (Member x : members) if (x.id == id) return x;
        return null;
    }

    /** 신규 등록. npaCode 중복이면 기존 Member를 그대로 반환(중복 추가 안 함). */
    public synchronized Member add(String nick, String npaCode) {
        String n = normalize(npaCode);
        if (n.isEmpty()) throw new IllegalArgumentException("회원코드가 필요합니다.");
        Member exist = byNpa(n);
        if (exist != null) return exist;
        Member x = new Member();
        x.id = ++seq;
        x.nick = (nick == null || nick.isBlank()) ? n : nick.trim();
        x.npaCode = n;
        x.createdAt = System.currentTimeMillis();
        members.add(x);
        save();
        return x;
    }

    public synchronized boolean remove(long id) {
        boolean r = members.removeIf(x -> x.id == id);
        if (r) save();
        return r;
    }

    /** 검증 결과 기록(등록 시/재검증). */
    public synchronized void markCheck(long id, Boolean valid, String code, String msg) {
        Member x = get(id);
        if (x == null) return;
        x.valid = valid;
        x.lastCheckCode = code;
        x.lastCheckMsg = msg;
        save();
    }

    /** 사용 시도 결과 기록. */
    public synchronized void markRedeem(long id, String pin, String code, String msg) {
        Member x = get(id);
        if (x == null) return;
        x.lastRedeemPin = pin;
        x.lastRedeemCode = code;
        x.lastRedeemMsg = msg;
        x.lastRedeemAt = System.currentTimeMillis();
        save();
    }
}
