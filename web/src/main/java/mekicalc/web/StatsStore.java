package mekicalc.web;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import mekicalc.json.Json;

/**
 * 관리자 접속 통계 집계. {@code logs/access.jsonl}(1패스 스트림)을 기간(days)만큼 잘라
 * 시간/일별 접속 추이·기능 사용량·로그인 비율·직업 분포로 요약한다. 직업 분포는 로그의 job 필드(실사용)와
 * 가입 유저 프리셋(data/users/*.json 활성 스탯프리셋 JOB) 두 축을 함께 반환한다.
 *
 * <p>봇 폴러(127.0.0.1·::1)·{@code /api/bot/*}·정적 인프라 호출은 지표에서 제외한다.
 * 대용량 파일 대비 결과를 짧게(60초) 캐시한다. KST(UTC+9) 기준으로 버킷팅.
 */
public final class StatsStore {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final long CACHE_MS = 60_000;
    private static final Set<String> BOT_IPS = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    /** JOB 번호 → 한글명(#job-sel과 동일). */
    private static final Map<Integer, String> JOB_NAMES = Map.of(
            0, "공용", 1, "나이트로드", 2, "다크나이트", 3, "비숍", 4, "히어로", 5, "보우마스터");

    /** 기능(사용자 화면) 경로 → 라벨. 여기 있는 경로만 '기능 사용량'에 집계(인프라 호출 제외). */
    private static final Map<String, String> FEATURE_LABELS = new LinkedHashMap<>();
    static {
        FEATURE_LABELS.put("/api/target-damage", "목표 데미지");
        FEATURE_LABELS.put("/api/damage", "데미지 분석");
        FEATURE_LABELS.put("/api/nl-timeline", "빌드·실험실");
        FEATURE_LABELS.put("/api/slotdraws", "큐브 슬롯 조회");
        FEATURE_LABELS.put("/api/recommend", "큐브 우선순위 추천");
        FEATURE_LABELS.put("/api/evaluate", "큐브 기댓값");
        FEATURE_LABELS.put("/api/optcompare", "큐브 옵션 비교");
        FEATURE_LABELS.put("/api/compare", "내 옵션 변경");
        FEATURE_LABELS.put("/api/stat-compare", "스탯 비교");
        FEATURE_LABELS.put("/api/gear-compare", "장비 비교");
        FEATURE_LABELS.put("/api/gear-ocr", "장비 인식(OCR)");
        FEATURE_LABELS.put("/api/companion-dps", "동료 계산");
    }

    private final Path accessFile;
    private final Path usersDir;
    private final Map<Integer, Map<String, Object>> cache = new HashMap<>();
    private final Map<Integer, Long> cacheAt = new HashMap<>();

    public StatsStore(Path dataDir) {
        this.accessFile = dataDir.resolveSibling("logs").resolve("access.jsonl");
        this.usersDir = dataDir.resolve("users");
    }

    /** 최근 days일 접속 통계(캐시 60초). days≤1이면 시간별, 아니면 일별 버킷. */
    public synchronized Map<String, Object> stats(int days) {
        int d = Math.max(1, Math.min(90, days));
        long now = System.currentTimeMillis();
        Long at = cacheAt.get(d);
        if (at != null && now - at < CACHE_MS && cache.containsKey(d)) return cache.get(d);
        Map<String, Object> out = compute(d);
        cache.put(d, out);
        cacheAt.put(d, now);
        return out;
    }

    private Map<String, Object> compute(int days) {
        boolean hourly = days <= 1;
        long unit = hourly ? 3600L : 86400L;
        long ks = KST.getTotalSeconds();
        long nowEpoch = OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond();
        long nowBucket = ((nowEpoch + ks) / unit) * unit - ks;   // 현재 버킷 시작(UTC epoch)
        // ★'오늘'(hourly) = KST 00시부터 지금까지(시간별). N일 = 오늘 포함 최근 N일(일별). 둘 다 시작 버킷 정렬.
        long startBucket = hourly
                ? ((nowEpoch + ks) / 86400L) * 86400L - ks          // 오늘 KST 00:00
                : nowBucket - (days - 1) * unit;                    // 오늘 포함 최근 days일
        long cutoff = startBucket;

        // 버킷: key(정렬용 epoch초) → 집계. ★방문자 = /api/bootstrap 고유 IP(=실제 앱 로드, §14). 스캐너·크롤러는
        // bootstrap을 안 부르니 자연히 제외된다. requests는 전체 비봇 요청량.
        Map<Long, long[]> bucketReq = new HashMap<>();          // bucketKey → {requests}
        Map<Long, Set<String>> bucketBoot = new HashMap<>();    // bucketKey → 방문자(bootstrap 고유 IP)
        Map<String, Long> featureCount = new HashMap<>();       // path → count
        Set<String> bootIps = new HashSet<>();                  // 전체 방문자(bootstrap 고유 IP)
        Set<String> loggedInIps = new HashSet<>();              // 로그인한 적 있는 IP(전체)
        Map<String, Integer> ipJob = new HashMap<>();           // IP → 마지막 직업(실사용)

        if (Files.exists(accessFile)) {
            try (Stream<String> lines = Files.lines(accessFile, StandardCharsets.UTF_8)) {
                for (java.util.Iterator<String> it = lines.iterator(); it.hasNext(); ) {
                    String ln = it.next();
                    if (ln.isBlank()) continue;
                    Map<String, Object> m;
                    try { m = Json.asMap(Json.parse(ln)); } catch (Exception e) { continue; }
                    Object tsO = m.get("ts");
                    if (tsO == null) continue;
                    long epoch;
                    try { epoch = OffsetDateTime.parse(tsO.toString()).toEpochSecond(); } catch (Exception e) { continue; }
                    if (epoch < cutoff) continue;
                    String ip = str(m.get("ip"));
                    if (ip == null || BOT_IPS.contains(ip)) continue;           // 봇 폴러·로컬 제외
                    String path = str(m.get("path"));
                    if (path == null || path.startsWith("/api/bot/")) continue;  // 봇 브릿지 제외
                    String user = str(m.get("user"));

                    long bk = bucketKey(epoch, hourly);
                    bucketReq.computeIfAbsent(bk, k -> new long[1])[0]++;
                    if (user != null) loggedInIps.add(ip);
                    if ("/api/bootstrap".equals(path)) { bucketBoot.computeIfAbsent(bk, k -> new HashSet<>()).add(ip); bootIps.add(ip); }
                    if (FEATURE_LABELS.containsKey(path)) featureCount.merge(path, 1L, Long::sum);
                    Object jb = m.get("job");
                    if (jb instanceof Number) { int j = ((Number) jb).intValue(); if (j > 0) ipJob.put(ip, j); }
                }
            } catch (Exception ignore) { }
        }

        // 시계열: 시작~현재 버킷을 빈 구간(0 포함) 연속으로 채워 1시간/1일 단위가 끊기지 않게.
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(hourly ? "HH시" : "MM-dd");
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (long k = startBucket; k <= nowBucket; k += unit) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("t", OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(k), KST).format(fmt));
            b.put("requests", bucketReq.containsKey(k) ? bucketReq.get(k)[0] : 0L);
            b.put("visitors", bucketBoot.getOrDefault(k, Set.of()).size());
            buckets.add(b);
        }

        // 기능 사용량(라벨, 내림차순)
        List<Map<String, Object>> features = new ArrayList<>();
        featureCount.entrySet().stream()
                .sorted((a, c) -> Long.compare(c.getValue(), a.getValue()))
                .forEach(e -> { Map<String, Object> f = new LinkedHashMap<>();
                    f.put("path", e.getKey()); f.put("label", FEATURE_LABELS.get(e.getKey())); f.put("count", e.getValue());
                    features.add(f); });

        // 직업 분포(실사용: IP별 마지막 직업)
        Map<Integer, Long> jobUse = new HashMap<>();
        for (int j : ipJob.values()) jobUse.merge(j, 1L, Long::sum);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("granularity", hourly ? "hour" : "day");
        out.put("buckets", buckets);
        out.put("features", features);
        long loggedInVisitors = bootIps.stream().filter(loggedInIps::contains).count();
        Map<String, Object> login = new LinkedHashMap<>();
        login.put("loggedIn", loggedInVisitors);
        login.put("anon", Math.max(0, bootIps.size() - loggedInVisitors));
        out.put("login", login);
        out.put("jobs", jobList(jobUse));
        out.put("jobsPresets", jobList(presetJobs()));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("visitors", bootIps.size());
        summary.put("requests", bucketReq.values().stream().mapToLong(a -> a[0]).sum());
        summary.put("loggedIn", loggedInVisitors);
        out.put("summary", summary);
        return out;
    }

    /** 가입 유저 프리셋의 활성 스탯프리셋 JOB 집계(직업 미설정 0 제외). */
    private Map<Integer, Long> presetJobs() {
        Map<Integer, Long> jobs = new HashMap<>();
        if (!Files.isDirectory(usersDir)) return jobs;
        try (Stream<Path> files = Files.list(usersDir)) {
            for (java.util.Iterator<Path> it = files.iterator(); it.hasNext(); ) {
                Path p = it.next();
                if (!p.toString().endsWith(".json")) continue;
                try {
                    Map<String, Object> d = Json.asMap(Json.parse(Files.readString(p, StandardCharsets.UTF_8)));
                    Map<String, Object> sp = Json.asMap(d.get("statPresets"));
                    if (sp == null) continue;
                    Object active = sp.get("active");
                    Map<String, Object> presets = Json.asMap(sp.get("presets"));
                    if (presets == null || presets.isEmpty()) continue;
                    Object one = active != null && presets.containsKey(active.toString())
                            ? presets.get(active.toString()) : presets.values().iterator().next();
                    Map<String, Object> stats = Json.asMap(Json.asMap(one).get("stats"));
                    if (stats == null) continue;
                    Object jb = stats.get("JOB");
                    if (jb instanceof Number) { int j = ((Number) jb).intValue(); if (j > 0) jobs.merge(j, 1L, Long::sum); }
                } catch (Exception ignore) { }
            }
        } catch (Exception ignore) { }
        return jobs;
    }

    private static List<Map<String, Object>> jobList(Map<Integer, Long> counts) {
        List<Map<String, Object>> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> { Map<String, Object> j = new LinkedHashMap<>();
                    j.put("job", e.getKey()); j.put("name", JOB_NAMES.getOrDefault(e.getKey(), "직업" + e.getKey()));
                    j.put("count", e.getValue()); out.add(j); });
        return out;
    }

    private static long bucketKey(long epochSec, boolean hourly) {
        long kst = epochSec + KST.getTotalSeconds();
        long unit = hourly ? 3600 : 86400;
        return (kst / unit) * unit - KST.getTotalSeconds();   // 버킷 시작(UTC epoch)
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
