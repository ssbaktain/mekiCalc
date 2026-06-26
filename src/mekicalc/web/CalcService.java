package mekicalc.web;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mekicalc.config.ConversionIO;
import mekicalc.config.CubeStateIO;
import mekicalc.config.PresetIO;
import mekicalc.config.RangeIO;
import mekicalc.json.Json;
import mekicalc.engine.CommonDealModel;
import mekicalc.engine.DamageModel;
import mekicalc.engine.DealModel;
import mekicalc.engine.equip.CubeEvaluator;
import mekicalc.engine.equip.CubeRecommender;
import mekicalc.engine.equip.PotentialDraw;
import mekicalc.engine.skill.CompanionDps;
import mekicalc.engine.skill.NightlordDealModel;
import mekicalc.engine.skill.NightlordDps;
import mekicalc.engine.skill.SkillDpsModel;
import mekicalc.model.StatBlock;
import mekicalc.model.StatDef;

/**
 * 무상태 계산 서비스. (읽기전용 {@link GameContent} + 요청 입력) → 결과.
 *
 * <p>인스턴스 필드로 "현재 스탯"을 들지 않는다. 모든 계산은 호출 인자로 받은 입력만으로
 * 수행하므로 여러 요청이 한 인스턴스를 동시에 써도 안전하다. 데스크톱 {@code AppModel}의
 * 계산부(데미지·큐브 평가·추천·내 옵션 비교)를 무상태로 추출한 것이다.
 */
public final class CalcService {

    /** 등급 코드 → 한글 표시(데스크톱 AppModel.GRADE_KO와 동일, ui 비의존 위해 복제). */
    private static final Map<String, String> GRADE_KO = Map.of(
            "NORMAL", "노말", "RARE", "레어", "EPIC", "에픽",
            "UNIQUE", "유니크", "LEGENDARY", "레전드리", "MYTHIC", "미스틱");

    private static final int JOB_NIGHTLORD = 1;
    private static final double NORMAL_SECONDS = 90;   // 일반몹(챕터 스테이지) 기준 전투시간
    private static final double BOSS_SECONDS = 70;      // 보스(챕터 보스) 기준 전투시간

    private final GameContent content;

    public CalcService(GameContent content) {
        this.content = content;
    }

    // ──────────────────── 공통 헬퍼 ────────────────────

    /** 스탯 표시 이름(없으면 id 그대로). */
    private String statName(String id) {
        StatDef d = content.stats.get(id);
        return d != null ? d.name : id;
    }

    /** 입력 맵 → StatBlock. */
    private static StatBlock block(Map<String, Double> stats) {
        StatBlock b = new StatBlock();
        for (Map.Entry<String, Double> e : stats.entrySet()) b.set(e.getKey(), e.getValue());
        return b;
    }

    /** JSON에 안전한 숫자(NaN/Infinity는 null로). */
    private static Object safe(double d) {
        return Double.isFinite(d) ? d : null;
    }

    /**
     * 직업별 딜 모델. JOB=나이트로드면 종합 DPS, 아니면 공용(1타×공속). 둘 다 보스/일반몹 모드 반영.
     * 데스크톱 {@code AppModel.dealModel()}을 무상태로 옮긴 것.
     */
    private DealModel dealModel(StatBlock current, Map<String, Double> conv, boolean boss, Set<String> buffs, double hitInterval) {
        return dealModel(current, conv, boss, buffs, hitInterval, null);
    }
    /** targetDef!=null이면 나이트로드 딜 모델이 그 방어배수로 방관까지 환산. */
    private DealModel dealModel(StatBlock current, Map<String, Double> conv, boolean boss, Set<String> buffs, double hitInterval, Double targetDef) {
        return dealModel(current, conv, boss, buffs, hitInterval, targetDef, null, null, 0, 0);
    }
    /**
     * companionId!=null이면 동료(해석식) DPS를 출력에 합산하고 소환 중 동료 버프를 플레이어에 블렌딩 → 동소지·동료딜·동료버프를 큐브가 환산.
     * 동료 효율 계산은 빠른 해석식({@link CompanionDps#dpsOverSecondsAnalytic})을 쓴다(전투시간 보스 70 / 일반 90초 기준).
     */
    private DealModel dealModel(StatBlock current, Map<String, Double> conv, boolean boss, Set<String> buffs, double hitInterval, Double targetDef,
                               String companionId, String grade, int companionLevel, double ratio) {
        DamageModel dm = content.damageModel(conv);
        if ((int) current.get("JOB") == JOB_NIGHTLORD && content.skillData != null) {
            try {
                NightlordDps nd = new NightlordDps(dm, new SkillDpsModel(content.skillData), buffs, hitInterval);
                CompanionDps comp = null;
                if (companionId != null && !companionId.isEmpty()) {
                    comp = new CompanionDps(dm, content.companionRoot, companionId, grade, companionLevel, ratio).singleTarget(true);
                    if (!comp.valid()) comp = null;
                }
                return new NightlordDealModel(nd, dm, boss, targetDef, comp, boss ? BOSS_SECONDS : NORMAL_SECONDS);
            } catch (RuntimeException ex) {
                // 안전 폴백: 공용 모델
            }
        }
        return new CommonDealModel(dm, boss);
    }

    /** 대상(챕터/스테이지)의 몹 방어력(서버 전용). target="boss"=챕터 보스, 아니면 스테이지. 데이터 없으면 null(방관 미환산). */
    @SuppressWarnings("unchecked")
    private Double targetDef(String chapter, String target) {
        Object cObj = content.monsterDefense == null ? null : content.monsterDefense.get("chapters");
        if (!(cObj instanceof Map) || chapter == null) return null;
        Map<String, Object> c = Json.asMap(((Map<String, Object>) cObj).get(chapter));
        if (c == null || c.isEmpty()) return null;
        if ("boss".equals(target)) return c.get("boss") instanceof Number ? ((Number) c.get("boss")).doubleValue() : null;
        String stage = target != null && target.startsWith("stage:") ? target.substring(6) : target;
        return stageDef(c, stage);
    }
    /** 큐브 대상 모드: 스테이지가 보스면 보스 계산. */
    private static boolean isBossTarget(String target) { return "boss".equals(target); }

    /** 추천 범위 맵(statId→Range) → 추천 엔진용 오프셋(enabled만, statId→{lo, hi}). */
    private static Map<String, double[]> rangeOffsets(Map<String, RangeIO.Range> ranges) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, RangeIO.Range> e : ranges.entrySet()) {
            RangeIO.Range r = e.getValue();
            if (r.enabled()) out.put(e.getKey(), new double[]{r.loBound(), r.hiBound()});
        }
        return out;
    }

    /** mycube 한 줄의 3슬롯을 PotentialDraw[]로(확률은 잠재 테이블에서 매칭). 빈 슬롯은 null. */
    private static PotentialDraw[] currentDraws(CubeStateIO.Line line, List<List<PotentialDraw>> slots) {
        PotentialDraw[] cur = new PotentialDraw[3];
        CubeStateIO.Slot[] ss = line.slots();
        for (int i = 0; i < 3; i++) {
            CubeStateIO.Slot s = (ss != null && i < ss.length) ? ss[i] : null;
            if (s == null) continue;
            PotentialDraw match = null;
            for (PotentialDraw d : slots.get(i))
                if (d.statId().equals(s.stat()) && d.value() == s.value()) { match = d; break; }
            cur[i] = (match != null) ? match : new PotentialDraw(s.stat(), s.value(), 0, false, false);
        }
        return cur;
    }

    private static boolean isLineEmpty(CubeStateIO.Line line) {
        if (line.slots() == null) return true;
        for (CubeStateIO.Slot s : line.slots()) if (s != null) return false;
        return true;
    }

    private String statUnit(String id) {
        StatDef d = content.stats.get(id);
        return d != null ? d.unit : "";
    }

    private Object drawJson(PotentialDraw d) {
        if (d == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statId", d.statId());
        m.put("name", statName(d.statId()));
        m.put("unit", statUnit(d.statId()));
        m.put("value", d.value());
        m.put("special", d.special());
        m.put("yellow", d.yellow());
        return m;
    }

    // ──────────────────── 메타데이터 ────────────────────

    /** 스탯 입력 화면 구성용 스탯 정의 목록(직접 입력 대상만: hidden/derived 제외). */
    public List<Map<String, Object>> statDefs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StatDef d : content.stats.values()) {
            if (d.hidden || d.derived) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id);
            m.put("name", d.name);
            m.put("unit", d.unit);
            m.put("group", d.group);
            m.put("qualitative", d.qualitative);
            out.add(m);
        }
        return out;
    }

    /** 큐브 화면 드롭다운용: 부위·등급(코드+한글) + 환산/범위 설정 대상 스탯. */
    public Map<String, Object> meta() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parts", new ArrayList<>(content.equip.parts()));
        out.put("grades", new ArrayList<>(content.equip.grades()));
        Map<String, Object> ko = new LinkedHashMap<>();
        for (String code : content.equip.grades()) ko.put(code, GRADE_KO.getOrDefault(code, code));
        out.put("gradeKo", ko);

        // 커스텀 딜 환산 설정 대상: 정성적 스탯 + 스킬 레벨 스탯(stats.json 순서)
        List<Map<String, Object>> conv = new ArrayList<>();
        for (StatDef d : content.stats.values()) {
            if (d.qualitative || ConversionIO.SKILL_LEVEL_IDS.contains(d.id)) conv.add(statBrief(d));
        }
        out.put("convertibleStats", conv);

        // 추천 옵션 범위 설정 대상: 크확·공속 + 정성적 스탯
        List<Map<String, Object>> rng = new ArrayList<>();
        for (String id : RangeIO.EXTRA_IDS) {
            StatDef d = content.stats.get(id);
            if (d != null) rng.add(statBrief(d));
        }
        for (StatDef d : content.stats.values()) {
            if (d.qualitative && !RangeIO.EXTRA_IDS.contains(d.id)) rng.add(statBrief(d));
        }
        out.put("rangeStats", rng);
        return out;
    }

    private static Map<String, Object> statBrief(StatDef d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id);
        m.put("name", d.name);
        m.put("unit", d.unit);
        return m;
    }

    /**
     * 선택한 직업·환산에서 "딜 미환산"(데미지로 환산 못 하는) 스탯 id 목록.
     * 직업에 종합 DPS 모델이 있으면(나이트로드) 공속·쿨감·스킬레벨 등은 환산되므로 제외된다.
     * @return {"ids": [statId, …]}  (보스 기준)
     */
    public Map<String, Object> qualitativeMarkers(int job, Map<String, Double> conv) {
        StatBlock probe = new StatBlock();
        probe.set("JOB", job);
        DealModel dm = dealModel(probe, conv, true, null, 0);   // 보스 기준(BASIC_ATK_TARGETS는 보스에서만 미환산). 버프·피격주기는 미환산 판정과 무관.
        List<String> ids = new ArrayList<>();
        for (StatDef d : content.stats.values()) {
            if (d.hidden || d.derived) continue;
            StatBlock b = new StatBlock();
            b.set(d.id, 1);
            if (dm.hasQualitative(b)) ids.add(d.id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ids", ids);
        return out;
    }

    // ──────────────────── 예상 데미지 ────────────────────

    public Map<String, Object> previewDamage(Map<String, Double> stats, Map<String, Double> customConv, Set<String> buffs, double hitInterval) {
        return previewDamage(stats, customConv, buffs, hitInterval, null, null, 0, 0);
    }

    /**
     * 예상 데미지(헤드라인 1타 + DPS 분해). 동료 선택(companionId 등)이 주어지면 동료의 <b>90초 한 사이클</b> 평균 DPS를
     * 분해에 추가하고 전체에 합산한다(소환 유지/소환쿨 반영). 동료 미선택/무효 시 종전과 동일.
     */
    public Map<String, Object> previewDamage(Map<String, Double> stats, Map<String, Double> customConv, Set<String> buffs, double hitInterval,
                                             String companionId, String grade, int companionLevel, double inheritRatio) {
        StatBlock b = block(stats);
        DamageModel m = content.damageModel(customConv);
        CompanionDps comp = companionId == null ? null
                : new CompanionDps(m, content.companionRoot, companionId, grade, companionLevel, inheritRatio);
        if (comp != null && !comp.valid()) comp = null;
        if (comp != null) comp.singleTarget(true);   // 카드/홈은 "1마리 기준" — 플레이어 dpsBreakdown(singleTarget)과 동일 기준으로 동료도 1마리
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> detail = new LinkedHashMap<>();
        // DPS 모델 직업(나이트로드)은 평타가 버프·상시 패시브 공퍼의 영향을 받으므로,
        // 옛 공용 1타 대신 버프(체크된 것만) 평균 반영 평타 1타(타수 중 한 타) 기대딜을 보여준다.
        if ((int) b.get("JOB") == JOB_NIGHTLORD && content.skillData != null) {
            try {
                NightlordDps nd = new NightlordDps(m, new SkillDpsModel(content.skillData), buffs, hitInterval);
                double[] n = nd.basicHitStats(b, false), bo = nd.basicHitStats(b, true);
                out.put("normal", safe(n[2]));   // 헤드라인 = 1타 평균딜(플레이어 본인)
                out.put("boss", safe(bo[2]));
                // 동료는 풀버프 스냅샷 계승 + 플레이어 받피증 공유(같은 적 타격).
                StatBlock inh = b;
                double uptime = 0, Rp = 1;
                if (comp != null) {
                    inh = nd.fullBuffStats(b);
                    comp.playerRecvBonus(nd.playerRecvBonus(nd.buffed(b).stats));
                    uptime = comp.summonUptime(b);
                    Rp = 1 + nd.playerRecvBonus(nd.buffed(b).stats);
                }
                // 플레이어 DPS는 동료 버프/받피증을 소환 가동률만큼 블렌딩(소환 중=버프받음, 평상시=본인).
                double[] pn = blendPlayer(nd, b, false, comp, uptime, Rp);
                double[] pb = blendPlayer(nd, b, true, comp, uptime, Rp);
                // 카드 DPS는 1마리(단일 대상) 기준 — 일반몹도 대상수 미반영(평타 1타 표기와 일관). 추천/평가는 대상수 반영(2-arg) 유지.
                // 전투시간 기준: 일반몹(스테이지)=90초, 보스(챕터보스)=70초. 동료 기여는 이 시간창 평균.
                Map<String, Object> dn = hitMap(n[0], n[1], n[2], dpsMap(pn, comp, inh, false, NORMAL_SECONDS));
                Map<String, Object> db = hitMap(bo[0], bo[1], bo[2], dpsMap(pb, comp, inh, true, BOSS_SECONDS));
                dn.put("shares", sharesList(nd, b, false, pn[2], comp, inh, NORMAL_SECONDS));   // 홈 도넛(스킬별 지분 + 동료)
                db.put("shares", sharesList(nd, b, true, pb[2], comp, inh, BOSS_SECONDS));
                // 플레이어 DPS 검증용: 해석식 vs 시뮬(버프 겹침 타이밍) — 동료 미포함 순수 플레이어 기준
                dn.put("playerAnalytic", safe(nd.dpsBreakdown(b, false, true)[2]));
                dn.put("playerSim", safe(nd.dpsSimulate(b, false, true, NORMAL_SECONDS)));
                db.put("playerAnalytic", safe(nd.dpsBreakdown(b, true, true)[2]));
                db.put("playerSim", safe(nd.dpsSimulate(b, true, true, BOSS_SECONDS)));
                detail.put("normal", dn);
                detail.put("boss", db);
                out.put("detail", detail);
                return out;
            } catch (RuntimeException ex) {
                // 안전 폴백: 공용 1타
            }
        }
        // 공용 직업: 1타 평균 = finalDamage, 최소/최대는 데미지범위로 환산, DPS는 모델 없어 null.
        double avgN = m.finalDamageNormal(b), avgB = m.finalDamageBoss(b);
        double[] rn = rangeMinMax(b, avgN), rb = rangeMinMax(b, avgB);
        out.put("normal", safe(avgN));
        out.put("boss", safe(avgB));
        detail.put("normal", hitMap(rn[0], rn[1], avgN, null));   // 공용 직업: DPS 모델 없음
        detail.put("boss", hitMap(rb[0], rb[1], avgB, null));
        out.put("detail", detail);
        return out;
    }

    /** {min,max,avg,dps} 맵(JSON 안전). dps는 {basic,skill,total} 맵 또는 null(모델 없는 직업). */
    private static Map<String, Object> hitMap(double min, double max, double avg, Object dps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("min", safe(min));
        m.put("max", safe(max));
        m.put("avg", safe(avg));
        m.put("dps", dps);
        return m;
    }

    /**
     * 플레이어 [평타, 스킬, 전체] DPS — 동료가 있으면 소환 가동률만큼 동료 버프/받피증을 블렌딩.
     * 소환 중 = 동료 버프(target player/both) 적용 + 동료 받피증 공유, 평상시 = 본인. comp=null이면 본인 그대로.
     */
    private static double[] blendPlayer(NightlordDps nd, StatBlock b, boolean boss, CompanionDps comp, double uptime, double Rp) {
        double[] base = nd.dpsBreakdown(b, boss, true);
        if (comp == null) return base;
        Map<String, Object> gift = comp.playerGift(b, boss);
        @SuppressWarnings("unchecked")
        Map<String, Object> giftBuffs = (Map<String, Object>) gift.get("buffs");
        // 동료 공격력%는 별도 곱버킷이 아니라 플레이어 스킬 공퍼와 '합산 후 한 번 곱'(스킬 공퍼끼리 합연산) → skillAtkSum에 주입.
        double compAtkPct = giftBuffs.containsKey("ATK_PCT") ? ((Number) giftBuffs.remove("ATK_PCT")).doubleValue() : 0;
        StatBlock summonInput = new StatBlock(b);
        CompanionDps.applyGift(summonInput, giftBuffs);              // 공퍼 외 버프(최종뎀·크리·최대뎀배율 등) 적용
        nd.setExternalAtkPct(compAtkPct);                           // 동료 공퍼를 플레이어 스킬 공퍼 합에 합산
        double[] summon = nd.dpsBreakdown(summonInput, boss, true);
        nd.setExternalAtkPct(0);                                    // 원복(평상시 계산 오염 방지)
        double recvBonus = ((Number) gift.get("recvBonus")).doubleValue();
        double recvScale = Rp > 0 ? 1 + recvBonus / Rp : 1;          // 동료 받피증을 플레이어 받피증 위에 합연산
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) out[i] = base[i] * (1 - uptime) + summon[i] * recvScale * uptime;
        return out;
    }

    /**
     * {평타, 스킬, 동료(사이클), 동료(소환중), 전체} DPS 맵. bd = 블렌딩된 플레이어 [평타만, 스킬만, 전체].
     * companion = 90초 한 사이클 평균(소환쿨 반영), companionWindow = 소환 시작~종료 동안의 순수 DPS(가동 중).
     * 전체 DPS는 사이클 기준 동료를 합산(상시 운영 평균).
     */
    private static Map<String, Object> dpsMap(double[] bd, CompanionDps comp, StatBlock player, boolean boss, double seconds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("basic", safe(bd[0]));
        m.put("skill", safe(bd[1]));
        double companion = (comp == null) ? 0 : comp.dpsOverSeconds(player, boss, seconds)[2];   // 전투시간(70/90초) 평균
        double companionWindow = (comp == null) ? 0 : comp.dpsBreakdown(player, boss)[2];         // 소환 기간 기준 DPS
        m.put("companion", comp == null ? null : safe(companion));
        m.put("companionWindow", comp == null ? null : safe(companionWindow));
        m.put("summonSeconds", comp == null ? null : safe(comp.window(player)));   // 소환 지속(초) — SUMMON_DURATION 반영
        m.put("windowSeconds", seconds);                                            // 전투시간(70/90초)
        m.put("total", safe(bd[2] + companion));
        return m;
    }

    /**
     * 홈 도넛용 지분 목록 = 플레이어 스킬별 DPS(블렌딩 총합에 맞춰 스케일) + 동료 한 조각.
     * 스킬별 분해는 base(동료 버프 전) 기준이라, 합이 블렌딩된 플레이어 총합(blendedTotal)과 같도록 비례 보정한다.
     * 각 항목 = {name, dps, companion?}. 내림차순(동료는 값 그대로 끼움).
     */
    private static List<Map<String, Object>> sharesList(NightlordDps nd, StatBlock b, boolean boss,
                                                        double blendedTotal, CompanionDps comp, StatBlock inh, double seconds) {
        List<Map<String, Object>> raw = nd.dpsBySkill(b, boss, true);
        double baseTotal = 0;
        for (Map<String, Object> r : raw) baseTotal += ((Number) r.get("dps")).doubleValue();
        double scale = baseTotal > 0 ? blendedTotal / baseTotal : 1;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", r.get("name"));
            row.put("dps", safe(((Number) r.get("dps")).doubleValue() * scale));
            out.add(row);
        }
        if (comp != null) {
            double companion = comp.dpsOverSeconds(inh, boss, seconds)[2];
            if (companion > 0) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", "동료");
                row.put("dps", safe(companion));
                row.put("companion", true);
                out.add(row);
            }
        }
        return out;
    }

    /** 평균(평균 데미지범위 반영값) → [최소, 최대] (최소·최대 데미지범위 배율로 환산). 공용 모델용. */
    private static double[] rangeMinMax(StatBlock b, double avg) {
        double mn = b.get("MIN_DMG_MULT"), mx = b.get("MAX_DMG_MULT");
        double fAvg = (mn > mx) ? mn / 100.0 : (mn + mx) / 200.0;
        if (fAvg <= 0) return new double[]{avg, avg};
        return new double[]{avg * (mn / 100.0) / fAvg, avg * ((mn > mx ? mn : mx) / 100.0) / fAvg};
    }

    // ──────────────────── 버프 목록(편집기) ────────────────────

    /** 현재 스탯(직업·레벨·스킬레벨·쿨감 등) 기준 스킬 편집기 목록(버프 + 딜 스킬). DPS 모델 없는 직업은 빈 목록. */
    public Map<String, Object> buffList(Map<String, Double> stats) {
        StatBlock b = block(stats);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", (int) b.get("JOB"));
        List<Map<String, Object>> buffs = new ArrayList<>();
        List<Map<String, Object>> dealSkills = new ArrayList<>();
        if ((int) b.get("JOB") == JOB_NIGHTLORD && content.skillData != null) {
            try {
                NightlordDps nd = new NightlordDps(content.damageModel(Map.of()), new SkillDpsModel(content.skillData));
                buffs = nd.buffInfos(b);
                dealSkills = nd.dealSkillInfos(b);
            } catch (RuntimeException ex) {
                // 빈 목록 폴백
            }
        }
        out.put("buffs", buffs);
        out.put("dealSkills", dealSkills);
        return out;
    }

    // ──────────────────── 동료 ────────────────────

    /**
     * 대상 카탈로그(드롭다운용) — 방어력 수치는 빼고 <b>구조만</b>(챕터 → 스테이지 인덱스 목록 + 보스 유무).
     * 방어력은 우리 데이터라 클라이언트에 노출하지 않는다(절대 데미지 계산은 {@link #targetDamage}에서 서버가 수행).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> targetCatalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> chapters = new LinkedHashMap<>();
        Object cObj = content.monsterDefense == null ? null : content.monsterDefense.get("chapters");
        if (cObj instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) cObj).entrySet()) {
                Map<String, Object> c = Json.asMap(e.getValue());
                Map<String, Object> outC = new LinkedHashMap<>();
                Object st = c.get("stages");
                if (st instanceof Map) outC.put("stages", new ArrayList<>(((Map<String, Object>) st).keySet()));
                outC.put("boss", c.get("boss") != null);
                chapters.put(e.getKey(), outC);
            }
        }
        out.put("chapters", chapters);
        return out;
    }

    /** 챕터 c의 방어력 맵에서 스테이지 인덱스의 방어력(없으면 null). 서버 전용. */
    private Double stageDef(Map<String, Object> c, String stage) {
        Object st = c.get("stages");
        if (!(st instanceof Map)) return null;
        Object v = ((Map<?, ?>) st).get(stage);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    /** monster_hp.json에서 챕터 c의 스테이지 일반몹 체력(없으면 null). 서버 전용(클라 미노출). */
    @SuppressWarnings("unchecked")
    private Double stageHp(String chapter, String stage) {
        Object cObj = content.monsterHp == null ? null : content.monsterHp.get("chapters");
        if (!(cObj instanceof Map)) return null;
        Map<String, Object> c = Json.asMap(((Map<String, Object>) cObj).get(chapter));
        if (c == null) return null;
        Object st = c.get("stages");
        if (!(st instanceof Map)) return null;
        Object v = ((Map<?, ?>) st).get(stage);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    /** monster_hp.json에서 챕터 c의 보스 체력(없으면 null). 서버 전용. */
    @SuppressWarnings("unchecked")
    private Double bossHp(String chapter) {
        Object cObj = content.monsterHp == null ? null : content.monsterHp.get("chapters");
        if (!(cObj instanceof Map)) return null;
        Map<String, Object> c = Json.asMap(((Map<String, Object>) cObj).get(chapter));
        if (c == null) return null;
        Object v = c.get("boss");
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    /** 체력이 추정값(기본 k 사용 챕터)인지. 실측 보정된 챕터는 false. 클라에 "추정" 표시용. */
    @SuppressWarnings("unchecked")
    private boolean hpEstimated(String chapter) {
        Object cObj = content.monsterHp == null ? null : content.monsterHp.get("chapters");
        if (!(cObj instanceof Map)) return true;
        Map<String, Object> c = Json.asMap(((Map<String, Object>) cObj).get(chapter));
        if (c == null) return true;
        Object v = c.get("estimated");
        return !(v instanceof Boolean) || (Boolean) v;
    }

    /** 평타컷 = ⌈체력 ÷ (방어반영 평타딜)⌉. 체력·평타딜이 유효하지 않으면 null, 1 미만이면 1로 보정. */
    private Long cutCount(Double hp, double hitWithDef) {
        if (hp == null || hp <= 0 || hitWithDef <= 0) return null;
        double n = Math.ceil(hp / hitWithDef);
        if (n < 1) n = 1;
        if (n > Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) n;
    }

    /**
     * 선택 대상(챕터/스테이지)의 방어배수만 반환. 배수 = 5000/(6000 + 방어력 × (1 − 방관%/100)).
     * 방어력 수치는 응답에 미포함(서버 전용) — 클라는 이 배수를 표시 데미지에 곱해 '해당 몹 기준' 딜을 보여준다.
     * 방어력 데이터가 없으면 factor=1(미반영).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> targetDamage(String chapter, String target, double penPct) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean boss = "boss".equals(target);
        out.put("boss", boss);
        Object cObj = content.monsterDefense == null ? null : content.monsterDefense.get("chapters");
        Map<String, Object> chapters = cObj instanceof Map ? (Map<String, Object>) cObj : new LinkedHashMap<>();
        Map<String, Object> c = Json.asMap(chapters.get(chapter));
        Double def = null;
        if (c != null && !c.isEmpty()) {
            if (boss) def = c.get("boss") instanceof Number ? ((Number) c.get("boss")).doubleValue() : null;
            else {
                String stage = target != null && target.startsWith("stage:") ? target.substring(6) : target;
                def = stageDef(c, stage);
            }
        }
        double pen = penPct / 100.0;
        double f = (def != null) ? 5000.0 / (6000.0 + def * (1 - pen)) : 1.0;
        out.put("factor", safe(f));
        return out;
    }

    /** 메인 동료 목록(원본) — 동료 탭 드롭다운용. normalOnly(지망생)은 거의 안 쓰여 목록에서 제외(데이터는 보존). */
    public List<Map<String, Object>> companions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : content.companions) {
            Object no = c.get("normalOnly");
            if (no instanceof Boolean && (Boolean) no) continue;   // 지망생 제외(목록만)
            out.add(c);
        }
        return out;
    }

    /**
     * 메인 동료 DPS 기여 미리보기(Stage 1: 계승·소환 가동률·동료 평타). 계승비율은 입력값(ratio) 사용(A안).
     * @return {valid, ratioUsed, refRatio(참고), uptime, basicDps}
     */
    public Map<String, Object> companionPreview(Map<String, Double> stats, Map<String, Double> conv, boolean boss,
                                                String companionId, String grade, int level, double ratio) {
        return companionPreview(stats, conv, boss, companionId, grade, level, ratio, null);
    }

    public Map<String, Object> companionPreview(Map<String, Double> stats, Map<String, Double> conv, boolean boss,
                                                String companionId, String grade, int level, double ratio, Set<String> buffs) {
        StatBlock raw = block(stats);
        DamageModel dm = content.damageModel(conv);
        // 동료 계승 기준 = 풀버프 스냅샷 + 플레이어 받피증(나이트로드 모델 있을 때). 그 외 직업은 원본 스탯·받피증 0.
        StatBlock b = raw;
        double recvBonus = 0;
        if ((int) raw.get("JOB") == JOB_NIGHTLORD && content.skillData != null) {
            try {
                NightlordDps nd = new NightlordDps(dm, new SkillDpsModel(content.skillData), buffs, 0);
                b = nd.fullBuffStats(raw);
                recvBonus = nd.playerRecvBonus(nd.buffed(raw).stats);
            } catch (RuntimeException ex) { b = raw; recvBonus = 0; }
        }
        CompanionDps cd = new CompanionDps(dm, content.companionRoot, companionId, grade, level, ratio).playerRecvBonus(recvBonus);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", cd.valid());
        if (!cd.valid()) return out;
        double[] bd = cd.dpsBreakdown(b, boss);   // 30초 시간진행 시뮬레이션 [평타, 스킬, 합](받피증 포함)
        out.put("ratioUsed", ratio);
        out.put("refRatio", cd.refRatioPct(b));
        out.put("uptime", cd.summonUptime(b));
        out.put("basicDps", safe(bd[0]));
        out.put("skillDps", safe(bd[1]));
        out.put("ownDps", safe(bd[2]));
        double[] cyc = cd.cycleBreakdown(b, boss);   // 90초 한 사이클 평균(소환쿨 고려) — 예상 데미지 통합 기준
        out.put("cycleBasicDps", safe(cyc[0]));
        out.put("cycleSkillDps", safe(cyc[1]));
        out.put("cycleDps", safe(cyc[2]));
        out.put("detail", cd.detail(b, boss));   // 항목별 DPS(평타·스킬별) — 검증용
        out.put("statsView", cd.statsView(b, boss));   // 동료 유효 스탯(자체버프 반영 확인용)
        return out;
    }

    /**
     * 공격력 계승 비율 데이터 수집: (캐릭터 레벨, 동료, 등급, 동료 레벨, 비율)을 companion_ratios.jsonl에 한 줄씩 누적.
     * 추후 캐릭/동료 레벨 → 계승비율 공식 모델링용. 저장할 때마다 호출(클라가 ts 포함해 보냄).
     */
    public Map<String, Object> recordAllyRatio(Map<String, Object> req) {
        try {
            // jsonl(한 줄=한 레코드) — Json.write는 여러 줄이라 단일 값(j)을 모아 한 줄로 조립.
            String line = "{\"ts\":" + j(req.get("ts"))
                    + ",\"charLevel\":" + j(req.get("charLevel"))
                    + ",\"companionId\":" + j(req.get("companionId"))
                    + ",\"grade\":" + j(req.get("grade"))
                    + ",\"companionLevel\":" + j(req.get("companionLevel"))
                    + ",\"ratio\":" + j(req.get("ratio")) + "}\n";
            Files.writeString(content.dataDir.resolve("companion_ratios.jsonl"), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /** 단일 JSON 값(문자열은 이스케이프된 따옴표, 숫자는 그대로). Json.write가 값 끝에 개행을 붙이므로 strip. */
    private static String j(Object o) {
        return Json.write(o).strip();
    }

    // ──────────────────── 큐브 기댓값(평가) ────────────────────

    /**
     * 부위·등급의 잠재 옵션 평가. slot이 null이면 전체(3줄 중 한 줄 등장 기준),
     * 0~2이면 그 슬롯만. 딜 증가량 내림차순(정성적은 뒤로).
     */
    public Map<String, Object> evaluate(String part, String grade, Integer slot,
                                        Map<String, Double> stats, Map<String, Double> conv, String chapter, String target,
                                        Set<String> buffs, double hitInterval,
                                        String companionId, String companionGrade, int companionLevel, double ratio) {
        StatBlock current = block(stats);
        boolean boss = isBossTarget(target);
        DealModel dm = dealModel(current, conv, boss, buffs, hitInterval, targetDef(chapter, target), companionId, companionGrade, companionLevel, ratio);
        List<CubeEvaluator.Result> res = (slot == null)
                ? CubeEvaluator.evaluate(content.equip, dm, current, part, grade)
                : CubeEvaluator.evaluateSlot(content.equip, dm, current, part, grade, slot);
        res.sort((a, b) -> Double.compare(rank(b), rank(a)));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (CubeEvaluator.Result r : res) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("statId", r.statId());
            m.put("name", statName(r.statId()));
            m.put("unit", statUnit(r.statId()));
            m.put("value", r.value());
            m.put("special", r.special());
            m.put("yellow", r.yellow());
            m.put("qualitative", r.qualitative());
            m.put("damageIncrease", r.qualitative() ? null : safe(r.damageIncrease()));
            m.put("pPerCube", safe(r.pPerCube()));
            m.put("expectedCubes", safe(r.expectedCubes()));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", rows);
        return out;
    }

    private static double rank(CubeEvaluator.Result r) {
        return Double.isNaN(r.damageIncrease()) ? -1 : r.damageIncrease();
    }

    // ──────────────────── 큐브 우선순위 추천 ────────────────────

    /**
     * 내 큐브(cubeState) 전체를 분석해 슬라이더 한 지점의 추천을 반환한다.
     * @param ranges      추천 옵션 수치 범위(statId→Range)
     * @param sliderValue 0 ~ maxDivisions-1
     * @param sortKey     0=딜 증가량, 1=등장 확률, 2=1회 리롤, 3=최종 효율
     * @return {"maxDivisions": n, "recs": [...]}
     */
    public Map<String, Object> recommend(Map<String, Double> stats, Map<String, Double> conv, String chapter, String target,
                                         Object cubeStateJson, Map<String, RangeIO.Range> ranges,
                                         int sliderValue, int sortKey, boolean upper, boolean lower,
                                         Set<String> buffs, double hitInterval,
                                         String companionId, String companionGrade, int companionLevel, double ratio) {
        StatBlock current = block(stats);
        boolean boss = isBossTarget(target);
        DealModel dm = dealModel(current, conv, boss, buffs, hitInterval, targetDef(chapter, target), companionId, companionGrade, companionLevel, ratio);
        Map<String, CubeStateIO.PartCube> cubeState = CubeStateIO.parse(cubeStateJson);
        Map<String, double[]> offsets = rangeOffsets(ranges);
        double upPrice = content.currency.getOrDefault("미라클큐브", 1.0);
        double loPrice = content.currency.getOrDefault("에디셔널큐브", 2.0);

        List<CubeRecommender.EquipOpts> rec = new ArrayList<>();
        for (Map.Entry<String, CubeStateIO.PartCube> e : cubeState.entrySet()) {
            CubeStateIO.PartCube pc = e.getValue();
            if (pc.upper() != null) addLayer(rec, dm, current, e.getKey(), pc.upper(), true, upPrice, offsets);
            if (pc.lower() != null) addLayer(rec, dm, current, e.getKey(), pc.lower(), false, loPrice, offsets);
        }
        int maxDivisions = 1;
        for (CubeRecommender.EquipOpts eo : rec) maxDivisions = Math.max(maxDivisions, eo.shown().size());

        double f = maxDivisions > 1 ? (double) sliderValue / (maxDivisions - 1) : 0;
        List<CubeRecommender.Rec> picks = new ArrayList<>();
        for (CubeRecommender.EquipOpts eo : rec) {
            if (eo.upper() && !upper) continue;
            if (!eo.upper() && !lower) continue;
            CubeRecommender.Rec r = CubeRecommender.pick(eo, f);
            if (r != null) picks.add(r);
        }
        final int sk = sortKey;
        picks.sort((a, b) -> Double.compare(sortValue(b, sk), sortValue(a, sk)));

        List<Map<String, Object>> recs = new ArrayList<>();
        for (CubeRecommender.Rec r : picks) recs.add(recJson(r));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxDivisions", maxDivisions);
        out.put("recs", recs);
        return out;
    }

    private void addLayer(List<CubeRecommender.EquipOpts> rec, DealModel dm, StatBlock current,
                          String part, CubeStateIO.Line line, boolean upper, double price,
                          Map<String, double[]> offsets) {
        if (line.grade() == null || isLineEmpty(line)) return;
        List<List<PotentialDraw>> slots = content.equip.slots(part, line.grade());
        PotentialDraw[] curDraws = currentDraws(line, slots);
        boolean[] locked = line.locked() != null ? line.locked() : new boolean[3];
        CubeRecommender.EquipOpts eo = CubeRecommender.analyze(
                dm, current, slots, curDraws, part, upper, price, offsets,
                locked, line.grade(), line.stack(), content.equip);
        if (!eo.shown().isEmpty()) rec.add(eo);
    }

    private static double sortValue(CubeRecommender.Rec r, int key) {
        switch (key) {
            case 0: return r.dealDiff() / r.cost();
            case 1: return r.recProbSum() / r.cost();
            case 2: return r.totalProb() / r.cost();
            default: return r.gainEff();
        }
    }

    private Map<String, Object> recJson(CubeRecommender.Rec r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("part", r.part());
        m.put("upper", r.upper());
        m.put("grade", r.grade());
        m.put("curGrade", r.curGrade());
        m.put("crossGrade", r.grade() != null && !r.grade().equals(r.curGrade()));
        m.put("cost", r.cost());
        m.put("curDeal", safe(r.curDeal()));
        m.put("recDeal", safe(r.recDeal()));
        m.put("dealDiff", safe(r.dealDiff()));
        m.put("recProbSum", safe(r.recProbSum()));
        m.put("recCubes", safe(r.recCubes()));
        m.put("totalProb", safe(r.totalProb()));
        m.put("remainProb", safe(r.remainProb()));
        m.put("gainEff", safe(r.gainEff()));
        List<Map<String, Object>> picks = new ArrayList<>();
        for (CubeRecommender.SlotPick p : r.picks()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("cur", drawJson(p.current()));
            pm.put("rec", drawJson(p.recommended()));
            pm.put("changed", p.changed());
            pm.put("locked", p.locked());
            picks.add(pm);
        }
        m.put("picks", picks);
        return m;
    }

    // ──────────────────── 옵션 변경 적용(스탯 재계산) ────────────────────

    /**
     * 한 큐브 줄의 옵션을 oldOpts→newOpts로 바꿨을 때의 새 스탯 총합을 계산한다.
     * 현재 스탯에서 기존 옵션을 빼고(uncombine) 새 옵션을 더한다(combine) — 주스탯→공격력 전파·combine 규칙 반영.
     * @return {"stats": {statId: 값}}
     */
    public Map<String, Object> applyStats(Map<String, Double> stats, Map<String, Double> conv,
                                          List<Map<String, Object>> oldOpts, List<Map<String, Object>> newOpts) {
        StatBlock current = block(stats);
        DamageModel dm = content.damageModel(conv);
        StatBlock base = dm.uncombine(current, deltaOfReq(oldOpts));   // 기존 옵션 제거
        StatBlock result = dm.combine(base, deltaOfReq(newOpts));      // 새 옵션 적용
        Map<String, Object> sm = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : result.asMap().entrySet()) sm.put(e.getKey(), e.getValue());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stats", sm);
        return out;
    }

    /** [{statId,value},…] → 같은 스탯끼리 합산한 StatBlock. */
    private static StatBlock deltaOfReq(List<Map<String, Object>> opts) {
        StatBlock b = new StatBlock();
        for (Map<String, Object> o : opts) {
            String sid = String.valueOf(o.get("statId"));
            double val = o.get("value") instanceof Double ? (Double) o.get("value") : 0;
            b.set(sid, b.get(sid) + val);
        }
        return b;
    }

    // ──────────────────── 옵션 비교(좌/우 자유 선택) ────────────────────

    /**
     * 좌/우 각각 부위·등급·옵션을 자유롭게 골라, 각 측의 딜 증가량·동일 옵션 등장 확률 총합·
     * 큐브 기댓값(1/p)·정성 여부를 계산한다(데스크톱 큐브 옵션 비교와 동일). 효율(등장확률×딜증가량)은 클라가 표기.
     */
    public Map<String, Object> optCompare(String lPart, String lGrade, List<Map<String, Object>> lOpts,
                                          String rPart, String rGrade, List<Map<String, Object>> rOpts,
                                          Map<String, Double> stats, Map<String, Double> conv, String chapter, String target,
                                          Set<String> buffs, double hitInterval,
                                          String companionId, String companionGrade, int companionLevel, double ratio) {
        StatBlock current = block(stats);
        boolean boss = isBossTarget(target);
        DealModel dm = dealModel(current, conv, boss, buffs, hitInterval, targetDef(chapter, target), companionId, companionGrade, companionLevel, ratio);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("left", sideEval(lPart, lGrade, lOpts, current, dm));
        out.put("right", sideEval(rPart, rGrade, rOpts, current, dm));
        return out;
    }

    private Map<String, Object> sideEval(String part, String grade, List<Map<String, Object>> opts,
                                         StatBlock current, DealModel dm) {
        List<PotentialDraw> draws = new ArrayList<>();
        StatBlock delta = new StatBlock();
        for (Map<String, Object> o : opts) {
            String sid = String.valueOf(o.get("statId"));
            double val = o.get("value") instanceof Double ? (Double) o.get("value") : 0;
            draws.add(new PotentialDraw(sid, val, 0, false, false));
            delta.set(sid, delta.get(sid) + val);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (draws.isEmpty()) { m.put("empty", true); m.put("inc", 0.0); m.put("prob", 0.0); m.put("qual", false); return m; }
        m.put("empty", false);
        m.put("inc", safe(dm.increase(current, delta)));
        m.put("qual", dm.hasQualitative(delta));
        m.put("prob", safe(CubeRecommender.arrangeSum(draws, content.equip.slots(part, grade))));
        return m;
    }

    // ──────────────────── 내 옵션 비교 ────────────────────

    /**
     * 내 큐브 한 줄(part의 윗/아랫잠)을 기준으로, 같은 부위에서 오른쪽(가정) 등급·옵션으로 바뀌었을 때의
     * 딜 증가량 차이·최종 효율·1회 리롤 두 확률을 계산한다(데스크톱 AppModel.myCompare와 동일 정의).
     * @param rightOpts 오른쪽 가정 옵션 [{statId, value}, …]
     */
    public Map<String, Object> myCompare(Map<String, Double> stats, Map<String, Double> conv, String chapter, String target,
                                         Object cubeStateJson, Map<String, RangeIO.Range> ranges,
                                         String part, boolean upper, String rightGrade,
                                         List<Map<String, Object>> rightOpts, Set<String> buffs, double hitInterval,
                                         String companionId, String companionGrade, int companionLevel, double ratio) {
        StatBlock current = block(stats);
        boolean boss = isBossTarget(target);
        DealModel dm = dealModel(current, conv, boss, buffs, hitInterval, targetDef(chapter, target), companionId, companionGrade, companionLevel, ratio);
        Map<String, CubeStateIO.PartCube> cubeState = CubeStateIO.parse(cubeStateJson);
        Map<String, double[]> offsets = rangeOffsets(ranges);

        CubeStateIO.PartCube pc = cubeState.get(part);
        CubeStateIO.Line line = pc == null ? null : (upper ? pc.upper() : pc.lower());
        if (line == null || line.grade() == null || isLineEmpty(line)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("valid", false);
            return out;
        }

        String leftGrade = line.grade();
        List<List<PotentialDraw>> leftSlots = content.equip.slots(part, leftGrade);
        PotentialDraw[] curDraws = currentDraws(line, leftSlots);
        boolean[] locked = line.locked() != null ? line.locked() : new boolean[3];
        double cost = upper ? content.currency.getOrDefault("미라클큐브", 1.0)
                            : content.currency.getOrDefault("에디셔널큐브", 2.0);

        // 좌/우 딜: 현재 줄을 뺀 baseline 기준(추천 엔진과 동일).
        StatBlock leftDelta = new StatBlock();
        for (PotentialDraw d : curDraws) if (d != null) leftDelta.set(d.statId(), leftDelta.get(d.statId()) + d.value());
        StatBlock baseline = dm.uncombine(current, leftDelta);
        double curBase = dm.output(baseline);
        double leftDeal = curBase > 0 ? dm.output(current) / curBase - 1.0 : 0;

        List<PotentialDraw> rOpts = new ArrayList<>();
        for (Map<String, Object> o : rightOpts) {
            String sid = String.valueOf(o.get("statId"));
            double val = o.get("value") instanceof Double ? (Double) o.get("value") : 0;
            rOpts.add(new PotentialDraw(sid, val, 0, false, false));
        }
        StatBlock rightDelta = new StatBlock();
        for (PotentialDraw d : rOpts) rightDelta.set(d.statId(), rightDelta.get(d.statId()) + d.value());
        double rightDeal = curBase > 0 ? dm.output(dm.combine(baseline, rightDelta)) / curBase - 1.0 : 0;
        double dealDiff = rightDeal - leftDeal;

        List<PotentialDraw> lOpts = new ArrayList<>();
        for (PotentialDraw d : curDraws) if (d != null) lOpts.add(d);
        double leftProbSum = CubeRecommender.arrangeSum(lOpts, leftSlots);

        List<List<PotentialDraw>> rightTables = content.equip.slots(part, rightGrade);
        double arrange = CubeRecommender.arrangeSum(rOpts, rightTables);
        double rightProbSum = arrange;
        int li = content.equip.gradeIndex(leftGrade), ri = content.equip.gradeIndex(rightGrade);
        if (ri > li && li >= 0) {
            List<String> grs = content.equip.grades();
            double eReach = 0;
            boolean ok = true;
            for (int gi = li; gi < ri; gi++) {
                double eStep = content.equip.gradeUpCubes(grs.get(gi), gi == li ? Math.max(0, line.stack()) : 0);
                if (eStep <= 0) { ok = false; break; }
                eReach += eStep;
            }
            rightProbSum = (ok && arrange > 0) ? 1.0 / (eReach + Math.max(0.0, 1.0 / arrange - 1.0)) : 0;
        }

        double gainEff = cost > 0 ? dealDiff * rightProbSum / cost : 0;
        CubeRecommender.EquipOpts eoFull = CubeRecommender.analyze(
                dm, baseline, leftSlots, new PotentialDraw[3], part, upper, cost, java.util.Map.of(),
                new boolean[3], leftGrade, line.stack(), content.equip);
        double remainProb = eoFull.remainProbAbove(rightDeal);

        CubeRecommender.EquipOpts eo = CubeRecommender.analyze(
                dm, current, leftSlots, curDraws, part, upper, cost, offsets,
                locked, leftGrade, line.stack(), content.equip);

        boolean leftQual = dm.hasQualitative(deltaOf(lOpts));
        boolean rightQual = dm.hasQualitative(deltaOf(rOpts));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", true);
        out.put("leftDeal", safe(leftDeal));
        out.put("rightDeal", safe(rightDeal));
        out.put("dealDiff", safe(dealDiff));
        out.put("leftProbSum", safe(leftProbSum));
        out.put("rightProbSum", safe(rightProbSum));
        out.put("gainEff", safe(gainEff));
        out.put("totalProb", safe(eo.totalProb()));
        out.put("remainProb", safe(remainProb));
        out.put("leftQual", leftQual);
        out.put("rightQual", rightQual);
        out.put("cost", cost);
        return out;
    }

    private static StatBlock deltaOf(List<PotentialDraw> opts) {
        StatBlock b = new StatBlock();
        for (PotentialDraw d : opts) if (d != null) b.set(d.statId(), d.value());
        return b;
    }

    // ──────────────────── 개발용: 내 저장 데이터 불러오기 ────────────────────

    /**
     * 데이터 폴더의 개인 데이터(활성 스탯 프리셋·커스텀 환산·내 큐브)를 한 번에 돌려준다.
     * <b>개발 편의용</b>: 로컬 테스트에서 매번 값을 입력하지 않도록 한다. 배포 환경에는 개인 파일이
     * 없으므로(빌드·git 제외) 모두 빈 값으로 응답한다. 웹앱의 무상태 원칙은 그대로(저장은 클라가).
     * @return {"stats": {id:val}, "conversions": {id:perUnit}, "cubeState": {…}}
     */
    public Map<String, Object> myData() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            PresetIO.Store store = PresetIO.load(content.dataDir);
            for (PresetIO.Preset p : store.presets()) {
                if (p.name().equals(store.active())) {
                    for (Map.Entry<String, Double> e : p.stats().asMap().entrySet()) stats.put(e.getKey(), e.getValue());
                    break;
                }
            }
        } catch (Exception ignored) { /* 개인 데이터 없음 → 빈 스탯 */ }
        out.put("stats", stats);

        Map<String, Object> conv = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Double> e : ConversionIO.enabledPerUnit(content.dataDir).entrySet()) conv.put(e.getKey(), e.getValue());
        } catch (Exception ignored) { /* 없음 → 빈 환산 */ }
        out.put("conversions", conv);

        Map<String, Object> cube = new LinkedHashMap<>();
        try {
            java.nio.file.Path f = content.dataDir.resolve(CubeStateIO.FILE);
            if (Files.exists(f)) {
                for (Map.Entry<String, Object> e : Json.asMap(Json.parse(Files.readString(f))).entrySet()) {
                    if (!e.getKey().startsWith("_")) cube.put(e.getKey(), e.getValue());
                }
            }
        } catch (Exception ignored) { /* 없음/손상 → 빈 큐브 */ }
        out.put("cubeState", cube);
        return out;
    }

    /** 부위·등급의 슬롯별 옵션 후보(내 옵션 비교 오른쪽 드롭다운 채우기용). */
    public Map<String, Object> slotDraws(String part, String grade) {
        List<List<PotentialDraw>> slots = content.equip.slots(part, grade);
        List<List<Object>> out = new ArrayList<>();
        for (List<PotentialDraw> slot : slots) {
            List<Object> rows = new ArrayList<>();
            for (PotentialDraw d : slot) rows.add(drawJson(d));
            out.add(rows);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slots", out);
        return m;
    }
}
