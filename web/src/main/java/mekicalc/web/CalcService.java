package mekicalc.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
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
import mekicalc.engine.skill.ArtifactEffects;
import mekicalc.engine.skill.CompanionDps;
import mekicalc.engine.skill.NightlordDealModel;
import mekicalc.engine.skill.SkillDps;
import mekicalc.engine.skill.SkillData;
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
    private static final int JOB_DARK_KNIGHT = 2;
    private static final int JOB_BISHOP = 3;
    private static final double NORMAL_SECONDS = 90;   // 일반몹(챕터 스테이지) 기준 전투시간
    private static final double BOSS_SECONDS = 70;      // 보스(챕터 보스) 기준 전투시간

    private final GameContent content;
    private final VisionOcr ocr;

    public CalcService(GameContent content) {
        this(content, buildOcr(content.dataDir));
    }

    public CalcService(GameContent content, VisionOcr ocr) {
        this.content = content;
        this.ocr = ocr;
    }

    /**
     * 비전 OCR 제공자 폴백 체인 구성: Mistral(기본·정확) → Groq(2차·가용성 백업) → Gemini(하위호환) 순.
     * 앞 제공자가 쿼터 초과·오류면 다음으로 넘어간다. 키가 있는 제공자만 체인에 포함(없으면 비활성).
     * (실측: mistral-medium은 나란한 두 박스 레이아웃·소수점까지 정확, Groq scout는 라벨 오독이 있어 2차로 둠.)
     */
    private static VisionOcr buildOcr(java.nio.file.Path dataDir) {
        OcrConfig oc = OcrConfig.load(dataDir);
        return FallbackOcr.of(
                new MistralOcr(oc),
                new GroqOcr(oc),
                new GeminiOcr(GeminiConfig.load(dataDir)));
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
    /** 직업 id에 맞는 스킬 데이터(없으면 null → 공용 모델 폴백). */
    private SkillData skillDataFor(int job) {
        if (job == JOB_NIGHTLORD) return content.skillData;
        if (job == JOB_DARK_KNIGHT) return content.skillDataDk;
        if (job == JOB_BISHOP) return content.skillDataBishop;
        return null;
    }

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
        SkillData sd = skillDataFor((int) current.get("JOB"));
        if (sd != null) {
            try {
                SkillDps nd = new SkillDps(dm, new SkillDpsModel(sd), buffs, hitInterval);
                CompanionDps comp = null;
                if (companionId != null && !companionId.isEmpty()) {
                    comp = new CompanionDps(dm, content.companionRoot, companionId, grade, companionLevel, ratio);
                    if (!comp.valid()) comp = null;
                }
                return new NightlordDealModel(nd, dm, boss, targetDef, comp, boss ? BOSS_SECONDS : NORMAL_SECONDS);
            } catch (RuntimeException ex) {
                // 안전 폴백: 공용 모델
            }
        }
        return new CommonDealModel(dm, boss, targetDef);
    }

    /** 대상(챕터/스테이지)의 몹 방어력(서버 전용). target="boss"=챕터 보스, 아니면 스테이지. 데이터 없으면 null(방관 미환산). */
    @SuppressWarnings("unchecked")
    private Double targetDef(String chapter, String target) {
        Object cObj = content.monsterDefense == null ? null : content.monsterDefense.get("chapters");
        if (!(cObj instanceof Map) || chapter == null || chapter.isEmpty()) return null;   // 대상 미선택(빈 챕터)=방어력 미적용
        Object chObj = ((Map<String, Object>) cObj).get(chapter);
        if (!(chObj instanceof Map)) return null;
        Map<String, Object> c = Json.asMap(chObj);
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
        out.put("ocrEnabled", ocrEnabled());   // 장비 비교 이미지 인식 사용 가능 여부(클라 버튼 표시용)
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
        return previewDamage(stats, customConv, buffs, hitInterval, null, null, 0, 0, null);
    }

    /**
     * 예상 데미지(헤드라인 1타 + DPS 분해). 동료 선택(companionId 등)이 주어지면 동료의 <b>90초 한 사이클</b> 평균 DPS를
     * 분해에 추가하고 전체에 합산한다(소환 유지/소환쿨 반영). 동료 미선택/무효 시 종전과 동일.
     */
    public Map<String, Object> previewDamage(Map<String, Double> stats, Map<String, Double> customConv, Set<String> buffs, double hitInterval,
                                             String companionId, String grade, int companionLevel, double inheritRatio,
                                             List<ArtifactEffects.Equip> artifacts) {
        StatBlock b = block(stats);
        DamageModel m = content.damageModel(customConv);
        CompanionDps comp = companionId == null ? null
                : new CompanionDps(m, content.companionRoot, companionId, grade, companionLevel, inheritRatio);
        if (comp != null && !comp.valid()) comp = null;
        ArtifactEffects art = (artifacts == null || artifacts.isEmpty()) ? null : new ArtifactEffects(content.artifactRoot, artifacts);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> detail = new LinkedHashMap<>();
        // DPS 모델 직업(나이트로드)은 평타가 버프·상시 패시브 공퍼의 영향을 받으므로,
        // 옛 공용 1타 대신 버프(체크된 것만) 평균 반영 평타 1타(타수 중 한 타) 기대딜을 보여준다.
        SkillData sdPrev = skillDataFor((int) b.get("JOB"));
        if (sdPrev != null) {
            try {
                SkillDps nd = new SkillDps(m, new SkillDpsModel(sdPrev), buffs, hitInterval);
                // 불타는용암 debuffCase 판정: 내 직업(받피 디버프 보유) 또는 동료가 적에 디버프를 주는가.
                boolean enemyDebuff = art != null && (nd.playerRecvBonus(b) > 1e-9
                        || (comp != null && (comp.enemyDefReducePct(b, false) > 0 || comp.enemyDefReducePct(b, true) > 0)));
                // 동료 계승 스냅샷 = 풀버프 + 유물(발동 중 소환 시 육각목걸이 DMG 등 계승). 대상별(일반/보스)로 유물 다름.
                StatBlock inhN = b, inhB = b;
                double uptime = 0, Rp = 1, playerRecv = 0;
                if (comp != null) {
                    inhN = nd.fullBuffStats(b, false, NORMAL_SECONDS, simCtx(art, b, enemyDebuff, 1));
                    inhB = nd.fullBuffStats(b, true, BOSS_SECONDS, simCtx(art, b, enemyDebuff, 1));
                    playerRecv = nd.playerRecvBonus(nd.buffed(b).stats);
                    uptime = comp.summonUptime(b);
                    Rp = 1 + playerRecv;
                }
                // 딜 상세 = 대상별로 solo(동료 미소환) + summon(동료 소환) 상태의 {평균/최소/최대 평타딜, DPS} + 동료카드/도넛용 dps/shares.
                Map<String, Object> dn = detailFor(nd, b, false, NORMAL_SECONDS, comp, inhN, uptime, Rp, playerRecv, art, enemyDebuff);
                Map<String, Object> db = detailFor(nd, b, true, BOSS_SECONDS, comp, inhB, uptime, Rp, playerRecv, art, enemyDebuff);
                if (art != null && artifacts != null && !artifacts.isEmpty()) {   // 유물별 딜 기여(미소환 DPS에서 그 유물 뺐을 때 감소분)
                    dn.put("relicShares", relicShares(nd, b, false, NORMAL_SECONDS, artifacts, enemyDebuff, art, soloDpsOf(dn)));
                    db.put("relicShares", relicShares(nd, b, true, BOSS_SECONDS, artifacts, enemyDebuff, art, soloDpsOf(db)));
                }
                out.put("normal", ((Map<?, ?>) dn.get("solo")).get("avg"));   // 헤드라인 = 동료 미소환 평균 평타딜
                out.put("boss", ((Map<?, ?>) db.get("solo")).get("avg"));
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
     * 한 대상(boss)의 딜 상세: solo(동료 미소환)·summon(동료 소환) 상태별 {평균/최소/최대 평타딜, DPS} + 동료카드/도넛용 dps·shares.
     * summon DPS = 플레이어(동료 버프)×받피 + 동료 소환 중 딜. 동료 없으면 summon 생략.
     */
    private Map<String, Object> detailFor(SkillDps nd, StatBlock b, boolean boss, double seconds, CompanionDps comp,
                                          StatBlock inh, double uptime, double Rp, double playerRecv,
                                          ArtifactEffects art, boolean enemyDebuff) {
        Map<String, Object> det = new LinkedHashMap<>();
        SkillDps.SimContext ctxSolo = simCtx(art, b, enemyDebuff, 0);
        Map<String, Object> soloState = stateMap(nd.basicHitStatsCtx(b, boss, seconds, ctxSolo), nd.dpsSimulateBreakdown(b, boss, seconds, ctxSolo)[2]);
        soloState.put("shares", sharesFromParts(nd.dpsSimulateBySkill(b, boss, seconds, ctxSolo), 1.0, 0));   // 미소환 출처별(동료 조각 없음) — 합 = solo dps
        det.put("solo", soloState);
        if (comp != null) {
            Map<String, Object> gift = comp.playerGift(b, boss);        // 가동률 평균 — 평균딜·DPS용
            @SuppressWarnings("unchecked")
            Map<String, Object> giftBuffs = (Map<String, Object>) gift.get("buffs");
            double compAtkPct = giftBuffs.containsKey("ATK_PCT") ? ((Number) giftBuffs.remove("ATK_PCT")).doubleValue() : 0;
            StatBlock summonInput = new StatBlock(b);
            CompanionDps.applyGift(summonInput, giftBuffs);
            nd.setExternalAtkPct(compAtkPct);
            SkillDps.SimContext ctxSummon = simCtx(art, b, enemyDebuff, 1);
            double avgHit = nd.basicHitStatsCtx(summonInput, boss, seconds, ctxSummon)[2];   // 평균 = 동료·플레이어 버프 가동률평균
            double sumPlayer = nd.dpsSimulateBreakdown(summonInput, boss, seconds, ctxSummon)[2];
            Map<String, Double> summonParts = nd.dpsSimulateBySkill(summonInput, boss, seconds, ctxSummon);   // 소환 중 출처별(동료 버프 반영) — 원복 전에
            nd.setExternalAtkPct(0);
            // 최대딜 = 동료 버프 전부 ON + 플레이어 풀버프, 최소딜 = 동료 상시 패시브만 + 플레이어 무버프.
            double maxHit = giftState(nd, b, boss, seconds, comp.playerGiftRange(b, boss, true),  art, enemyDebuff, 1);
            double minHit = giftState(nd, b, boss, seconds, comp.playerGiftRange(b, boss, false), art, enemyDebuff, 0);
            double recvBonus = ((Number) gift.get("recvBonus")).doubleValue();
            double recvScale = Rp > 0 ? 1 + recvBonus / Rp : 1;
            comp.playerRecvBonus(playerRecv + artRecv(art, inh, boss, seconds));
            double compWin = comp.dpsBreakdown(inh, boss)[2];
            Map<String, Object> summonState = stateMap(new double[]{minHit, maxHit, avgHit}, sumPlayer * recvScale + compWin);
            summonState.put("shares", sharesFromParts(summonParts, recvScale, compWin));   // 소환 중: 플레이어(받피증 스케일) + 동료 조각 — 합 = summon dps
            det.put("summon", summonState);
        }
        // 동료 카드·도넛용: 출처별(평타·스킬…) 블렌드 지분 하나로 딜 상세(pp)와 딜 비중(shares)을 함께 산출 →
        // 두 뷰의 평타/스킬 비율이 항상 일치(같은 시뮬 기준).
        Map<String, Double> parts = blendPlayerBySkill(nd, b, boss, comp, uptime, Rp, seconds, art, enemyDebuff);
        double basic = parts.getOrDefault("평타", 0.0);
        double totalP = 0; for (double v : parts.values()) totalP += v;
        double[] pp = {basic, totalP - basic, totalP};   // [평타만, 스킬만, 전체]
        double solo = comp == null ? pp[2] : nd.dpsSimulateBreakdown(b, boss, seconds, ctxSolo)[2];
        if (comp != null) comp.playerRecvBonus(playerRecv + artRecv(art, inh, boss, seconds));
        det.put("dps", dpsMap(pp, comp, inh, boss, seconds, solo));
        det.put("shares", sharesList(parts, comp, inh, boss, seconds));
        return det;
    }

    private static Map<String, Object> stateMap(double[] hit, double dps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("min", safe(hit[0]));
        m.put("max", safe(hit[1]));
        m.put("avg", safe(hit[2]));
        m.put("dps", safe(dps));
        return m;
    }

    /**
     * 동료 선물(피크/바닥)을 적용한 상태의 평타 1타를 계산해 원하는 롤만 반환.
     * idx: 0=최소딜(플레이어 무버프×최소롤), 1=최대딜(플레이어 풀버프×최대롤), 2=평균.
     */
    private double giftState(SkillDps nd, StatBlock b, boolean boss, double seconds,
                             Map<String, Object> gift, ArtifactEffects art, boolean enemyDebuff, int idx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> gb = new LinkedHashMap<>((Map<String, Object>) gift.get("buffs"));
        double atkPct = gb.containsKey("ATK_PCT") ? ((Number) gb.remove("ATK_PCT")).doubleValue() : 0;
        StatBlock in = new StatBlock(b);
        CompanionDps.applyGift(in, gb);
        nd.setExternalAtkPct(atkPct);
        double v = nd.basicHitStatsCtx(in, boss, seconds, simCtx(art, b, enemyDebuff, 1))[idx];
        nd.setExternalAtkPct(0);
        return v;
    }

    /**
     * 플레이어 <b>출처별</b>(평타·각 스킬…) DPS 맵 — 동료가 있으면 소환 가동률만큼 동료 버프/받피증을 블렌딩.
     * 소환 중 = 동료 버프(target player/both) 적용 + 동료 받피증 공유, 평상시 = 본인. comp=null이면 본인 그대로.
     * (기존 blendPlayer의 출처별 버전 — [평타/스킬/전체]는 이 맵에서 도출해 도넛·딜상세가 같은 기준을 쓰게 한다.)
     */
    private static Map<String, Double> blendPlayerBySkill(SkillDps nd, StatBlock b, boolean boss, CompanionDps comp, double uptime, double Rp,
                                                          double seconds, ArtifactEffects art, boolean enemyDebuff) {
        Map<String, Double> base = nd.dpsSimulateBySkill(b, boss, seconds, simCtx(art, b, enemyDebuff, 0));   // 홈=시뮬(동료 없음)
        if (comp == null) return base;
        Map<String, Object> gift = comp.playerGift(b, boss);
        @SuppressWarnings("unchecked")
        Map<String, Object> giftBuffs = (Map<String, Object>) gift.get("buffs");
        // 동료 공격력%는 별도 곱버킷이 아니라 플레이어 스킬 공퍼와 '합산 후 한 번 곱'(스킬 공퍼끼리 합연산) → skillAtkSum에 주입.
        double compAtkPct = giftBuffs.containsKey("ATK_PCT") ? ((Number) giftBuffs.remove("ATK_PCT")).doubleValue() : 0;
        StatBlock summonInput = new StatBlock(b);
        CompanionDps.applyGift(summonInput, giftBuffs);              // 공퍼 외 버프(최종뎀·크리·최대뎀배율 등) 적용
        nd.setExternalAtkPct(compAtkPct);                           // 동료 공퍼를 플레이어 스킬 공퍼 합에 합산
        Map<String, Double> summon = nd.dpsSimulateBySkill(summonInput, boss, seconds, simCtx(art, b, enemyDebuff, 1));   // 소환 중
        nd.setExternalAtkPct(0);                                    // 원복(평상시 계산 오염 방지)
        double recvBonus = ((Number) gift.get("recvBonus")).doubleValue();
        double recvScale = Rp > 0 ? 1 + recvBonus / Rp : 1;          // 동료 받피증을 플레이어 받피증 위에 합연산
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : base.entrySet()) out.merge(e.getKey(), e.getValue() * (1 - uptime), Double::sum);
        for (Map.Entry<String, Double> e : summon.entrySet()) out.merge(e.getKey(), e.getValue() * recvScale * uptime, Double::sum);
        return out;
    }

    /** 유물 적 받피증(분수) — 동료와 공유. inh=계승 스냅샷(공속으로 은펜던트 중첩 추정). art=null이면 0. */
    private static double artRecv(ArtifactEffects art, StatBlock inh, boolean boss, double seconds) {
        return art == null ? 0 : art.enemyRecvDmgPct(boss, 1 + inh.get("ATK_SPEED") / 100.0, seconds, -1) / 100.0;
    }

    /** 유물 시뮬 컨텍스트. art=null이면 null(순수 플레이어). base=파생 델타 기준(프리셋 원본), compUptime=동료 소환 가동률. */
    private static SkillDps.SimContext simCtx(ArtifactEffects art, StatBlock base, boolean enemyDebuff, double compUptime) {
        if (art == null) return null;
        SkillDps.SimContext cx = new SkillDps.SimContext();
        cx.artifacts = art;
        cx.base = base;
        cx.enemyDebuff = enemyDebuff;
        cx.compUptime = compUptime;
        return cx;
    }

    /**
     * {평타, 스킬, 동료(사이클), 동료(소환중), 전체} DPS 맵. bd = 블렌딩된 플레이어 [평타만, 스킬만, 전체].
     * companion = 90초 한 사이클 평균(소환쿨 반영), companionWindow = 소환 시작~종료 동안의 순수 DPS(가동 중).
     * 전체 DPS는 사이클 기준 동료를 합산(상시 운영 평균).
     */
    private static Map<String, Object> dpsMap(double[] bd, CompanionDps comp, StatBlock player, boolean boss, double seconds, double playerSolo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("basic", safe(bd[0]));
        m.put("skill", safe(bd[1]));
        double companion = (comp == null) ? 0 : comp.dpsOverSeconds(player, boss, seconds)[2];   // 전투시간(70/90초) 평균
        double[] cwin = (comp == null) ? null : comp.dpsBreakdown(player, boss);   // 소환 중 [평타, 스킬, 합]
        double companionWindow = cwin == null ? 0 : cwin[2];                        // 소환 기간 기준 DPS
        m.put("companion", comp == null ? null : safe(companion));
        m.put("companionWindow", comp == null ? null : safe(companionWindow));
        if (comp != null) {   // 동료 기여 카드 상세용(소환 중 기준)
            m.put("companionBasic", safe(cwin[0]));
            m.put("companionSkill", safe(cwin[1]));
            m.put("companionRefRatio", safe(comp.refRatioPct(player)));   // 참고 계승 비율%
            m.put("companionUptime", safe(comp.summonUptime(player)));    // 소환 가동률(0~1)
            m.put("companionDetail", comp.detail(player, boss));          // 항목별 동료 딜(소환창 기준, 방어배수는 클라에서)
        }
        m.put("summonSeconds", comp == null ? null : safe(comp.window(player)));   // 소환 지속(초) — SUMMON_DURATION 반영
        m.put("windowSeconds", seconds);                                            // 전투시간(70/90초)
        double total = bd[2] + companion;
        m.put("total", safe(total));
        m.put("playerSolo", safe(playerSolo));   // 동료 없을 때 플레이어 단독 총합
        // 동료 기여도 = (플레이어+동료) / 플레이어 단독 − 1. 방어배수는 비율이라 상쇄(전·후 동일).
        m.put("companionContrib", (comp == null || playerSolo <= 0) ? null : safe(total / playerSolo - 1));
        return m;
    }

    /**
     * 홈 도넛용 지분 목록 = 플레이어 출처별(평타·각 스킬…) 블렌드 DPS + 동료 한 조각.
     * playerParts는 {@link #blendPlayerBySkill} 결과(딜 상세 pp와 동일 출처)라 별도 스케일 없이 그대로 쓴다 →
     * 딜 비중과 딜 상세의 평타/스킬 비율이 항상 일치. 각 항목 = {name, dps, companion?}, 내림차순.
     * 동료 조각의 dps는 참고값(사이클 평균)이자 '동료 존재' 플래그 — 클라 도넛은 소환창 총딜로 다시 계산한다.
     */
    private static List<Map<String, Object>> sharesList(Map<String, Double> playerParts, CompanionDps comp, StatBlock inh, boolean boss, double seconds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : playerParts.entrySet()) {
            if (e.getValue() <= 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey());
            row.put("dps", safe(e.getValue()));
            out.add(row);
        }
        out.sort((a, b2) -> Double.compare(((Number) b2.get("dps")).doubleValue(), ((Number) a.get("dps")).doubleValue()));
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

    /**
     * 출처별 parts(평타·각 스킬…)를 {name, dps} 배열로(dps 내림차순) 변환한다. 각 값에 {@code scale}을 곱하고
     * (소환 상태의 받피증 스케일 등), {@code companionDps}가 양수면 '동료' 조각을 끝에 추가한다.
     * solo(scale=1, companionDps=0)·summon(scale=recvScale, companionDps=소환 중 동료 DPS) 상태별 딜 비중 산출용.
     */
    /** 딜 상세 맵에서 미소환(solo) DPS를 꺼낸다(없으면 0). */
    private static double soloDpsOf(Map<String, Object> det) {
        Object s = det.get("solo");
        if (!(s instanceof Map)) return 0;
        Object d = ((Map<?, ?>) s).get("dps");
        return d instanceof Number ? ((Number) d).doubleValue() : 0;
    }

    /**
     * 유물별 딜 기여 = 미소환(solo) DPS에서 그 유물 하나를 뺐을 때의 감소분. compute 유물만 값이 나오고
     * 선반영/컨텐츠/생존 유물은 엔진 딜 기여가 0이라 0으로 표시된다. 각 유물마다 시뮬 1회(유물 수만큼).
     */
    private List<Map<String, Object>> relicShares(SkillDps nd, StatBlock b, boolean boss, double seconds,
                                                  List<ArtifactEffects.Equip> artifacts, boolean enemyDebuff,
                                                  ArtifactEffects artFull, double fullSoloDps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < artifacts.size(); i++) {
            ArtifactEffects.Equip removed = artifacts.get(i);
            List<ArtifactEffects.Equip> without = new ArrayList<>(artifacts);
            without.remove(i);
            ArtifactEffects artW = without.isEmpty() ? null : new ArtifactEffects(content.artifactRoot, without);
            // 선반영 유물이면 프리셋에 이미 녹아든 각성 스탯을 빼고 재계산한다(compute 유물이면 빈 맵이라 b 그대로).
            StatBlock bW = new StatBlock(b);
            for (Map.Entry<String, Double> e : artFull.preReflectedPlayerStats(removed, b, boss).entrySet())
                bW.set(e.getKey(), bW.get(e.getKey()) - e.getValue());
            double dpsW = nd.dpsSimulateBreakdown(bW, boss, seconds, simCtx(artW, bW, enemyDebuff, 0))[2];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", artFull.nameOf(removed.id));
            row.put("dps", safe(Math.max(0, fullSoloDps - dpsW)));
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> sharesFromParts(Map<String, Double> parts, double scale, double companionDps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : parts.entrySet()) {
            if (e.getValue() <= 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey());
            row.put("dps", safe(e.getValue() * scale));
            out.add(row);
        }
        out.sort((a, b2) -> Double.compare(((Number) b2.get("dps")).doubleValue(), ((Number) a.get("dps")).doubleValue()));
        if (companionDps > 0) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "동료");
            row.put("dps", safe(companionDps));
            row.put("companion", true);
            out.add(row);
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
        SkillData sdBuff = skillDataFor((int) b.get("JOB"));
        if (sdBuff != null) {
            try {
                SkillDps nd = new SkillDps(content.damageModel(Map.of()), new SkillDpsModel(sdBuff));
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

    /** 방어배수 f = 5000/(6000 + 방어력 × max(0, 1 − 총방관%/100)). def==null이면 1. 전 경로(홈·동료·큐브) 공용 공식. */
    private static double defenseFactor(Double def, double totalPenPct) {
        if (def == null) return 1.0;
        return 5000.0 / (6000.0 + def * Math.max(0, 1 - totalPenPct / 100.0));
    }

    /** 동료 디버프 방관 보정(%) = 적 방어력 감소 + 동료 방관. 동료 없으면 0. 방관 항에 더해 방어배수에 반영. */
    private double companionPenBonus(StatBlock b, Map<String, Double> conv, boolean boss,
                                     String companionId, String grade, int level, double ratio) {
        if (companionId == null || companionId.isEmpty()) return 0;
        CompanionDps comp = new CompanionDps(content.damageModel(conv), content.companionRoot, companionId, grade, level, ratio);
        if (!comp.valid()) return 0;
        return comp.enemyDefReducePct(b, boss) + comp.companionDefPenPct(b, boss);
    }

    /**
     * 선택 대상(챕터/스테이지)의 방어배수만 반환. 배수 = 5000/(6000 + 방어력 × max(0,1 − (방관+동료보정)%/100)).
     * 방어력 수치는 응답에 미포함(서버 전용) — 클라는 이 배수를 표시 데미지에 곱해 '해당 몹 기준' 딜을 보여준다.
     * 동료가 주어지면 동료의 적 방어력 감소·방관을 함께 반영(홈 카드도 큐브와 동일 기준). 방어력 데이터 없으면 factor=1.
     */
    public Map<String, Object> targetDamage(String chapter, String target, double penPct,
                                            Map<String, Double> stats, Map<String, Double> conv,
                                            String companionId, String grade, int level, double ratio) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean boss = isBossTarget(target);
        out.put("boss", boss);
        Double def = targetDef(chapter, target);
        double bonus = companionPenBonus(block(stats), conv, boss, companionId, grade, level, ratio);
        out.put("factor", safe(defenseFactor(def, penPct + bonus)));
        return out;
    }

    /** 유물 탭용 메타: 유물 목록(id/name/grade/category) + 슬롯 임계(slotByAwakenSum). */
    public Map<String, Object> artifactCatalog() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> a : content.artifacts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.get("id"));
            m.put("name", a.get("name"));
            m.put("grade", a.get("grade"));
            m.put("category", a.get("category"));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("slotByAwakenSum", content.artifactRoot.get("slotByAwakenSum"));
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
    public Map<String, Object> companionPreview(Map<String, Double> stats, Map<String, Double> conv, String chapter, String target,
                                                String companionId, String grade, int level, double ratio) {
        return companionPreview(stats, conv, chapter, target, companionId, grade, level, ratio, null);
    }

    public Map<String, Object> companionPreview(Map<String, Double> stats, Map<String, Double> conv, String chapter, String target,
                                                String companionId, String grade, int level, double ratio, Set<String> buffs) {
        boolean boss = isBossTarget(target);
        StatBlock raw = block(stats);
        DamageModel dm = content.damageModel(conv);
        // 동료 계승 기준 = 풀버프 스냅샷 + 플레이어 받피증(나이트로드 모델 있을 때). 그 외 직업은 원본 스탯·받피증 0.
        StatBlock b = raw;
        double recvBonus = 0;
        SkillData sdComp = skillDataFor((int) raw.get("JOB"));
        if (sdComp != null) {
            try {
                SkillDps nd = new SkillDps(dm, new SkillDpsModel(sdComp), buffs, 0);
                b = nd.fullBuffStats(raw);
                recvBonus = nd.playerRecvBonus(nd.buffed(raw).stats);
            } catch (RuntimeException ex) { b = raw; recvBonus = 0; }
        }
        CompanionDps cd = new CompanionDps(dm, content.companionRoot, companionId, grade, level, ratio).playerRecvBonus(recvBonus);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", cd.valid());
        if (!cd.valid()) return out;
        // 방어배수: 스탯 프리셋 대상(챕터/스테이지) + 동료 방어감소·방관 → 홈·큐브와 동일 기준으로 동료 딜도 '해당 몹 기준'.
        double pen = b.get("DEF_PEN") + cd.enemyDefReducePct(b, boss) + cd.companionDefPenPct(b, boss);
        double f = defenseFactor(targetDef(chapter, target), pen);
        double[] bd = cd.dpsBreakdown(b, boss);   // 30초 시간진행 시뮬레이션 [평타, 스킬, 합](받피증 포함)
        out.put("ratioUsed", ratio);
        out.put("refRatio", cd.refRatioPct(b));
        out.put("uptime", cd.summonUptime(b));
        out.put("basicDps", safe(bd[0] * f));
        out.put("skillDps", safe(bd[1] * f));
        out.put("ownDps", safe(bd[2] * f));
        double[] cyc = cd.cycleBreakdown(b, boss);   // 90초 한 사이클 평균(소환쿨 고려) — 예상 데미지 통합 기준
        out.put("cycleBasicDps", safe(cyc[0] * f));
        out.put("cycleSkillDps", safe(cyc[1] * f));
        out.put("cycleDps", safe(cyc[2] * f));
        out.put("detail", scaleMap(cd.detail(b, boss), f));   // 항목별 DPS(방어배수 반영)
        out.put("statsView", cd.statsView(b, boss));   // 동료 유효 스탯(자체버프 반영 확인용 — 방어배수 미적용)
        return out;
    }

    /** Map의 숫자 값에만 배수 f를 곱한 사본(라벨·비숫자 보존). 동료 항목별 DPS에 방어배수 적용용. */
    private static Map<String, Object> scaleMap(Map<String, Object> m, double f) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet())
            r.put(e.getKey(), e.getValue() instanceof Number ? safe(((Number) e.getValue()).doubleValue() * f) : e.getValue());
        return r;
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
        StatBlock base = dm.uncombine(current, deltaOfReq(dm, oldOpts));   // 기존 옵션 제거
        StatBlock result = dm.combine(base, deltaOfReq(dm, newOpts));      // 새 옵션 적용
        Map<String, Object> sm = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : result.asMap().entrySet()) sm.put(e.getKey(), e.getValue());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stats", sm);
        return out;
    }

    /** [{statId,value},…] → 같은 스탯끼리 모델 규칙대로 모은 StatBlock(방관 등 체감은 곱연산, 그 외 합산). */
    private static StatBlock deltaOfReq(DealModel dm, List<Map<String, Object>> opts) {
        StatBlock b = new StatBlock();
        for (Map<String, Object> o : opts) {
            String sid = String.valueOf(o.get("statId"));
            double val = o.get("value") instanceof Double ? (Double) o.get("value") : 0;
            b.set(sid, dm.aggregate(sid, b.get(sid), val));
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
            delta.set(sid, dm.aggregate(sid, delta.get(sid), val));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (draws.isEmpty()) { m.put("empty", true); m.put("inc", 0.0); m.put("prob", 0.0); m.put("qual", false); return m; }
        m.put("empty", false);
        m.put("inc", safe(dm.increase(current, delta)));
        m.put("qual", dm.hasQualitative(delta));
        m.put("prob", safe(CubeRecommender.appearProb(draws, content.equip.slots(part, grade))));
        return m;
    }

    // ──────────────────── 장비 비교(프리셋 없음) ────────────────────

    /** 장비 비교 UI 스펙(부위별 주/부옵션 풀). gear_options.json 원본 맵을 그대로 내려준다. */
    public Map<String, Object> gearOptions() {
        return content.gearOptions;
    }

    /**
     * 두 장비(왼쪽=현재 장착, 오른쪽=후보)의 딜을 비교한다. 현재 총스탯이 왼쪽 장비를 포함한다고 보고,
     * 왼쪽 옵션을 빼서 base를 만든 뒤 오른쪽 옵션을 얹어 딜을 다시 계산한다.
     * 상단 표기용 = "오른쪽이 왼쪽보다 딜 +deltaPct". 딜 무관 옵션은 클라가 애초에 안 보낸다.
     * @return {leftOut, rightOut, deltaPct, qual, newStats}
     */
    public Map<String, Object> gearCompare(Map<String, Double> stats, Map<String, Double> conv, String chapter, String target,
                                           List<Map<String, Object>> leftOpts, List<Map<String, Object>> rightOpts,
                                           Set<String> buffs, double hitInterval,
                                           String companionId, String companionGrade, int companionLevel, double ratio) {
        StatBlock current = block(stats);
        boolean boss = isBossTarget(target);
        DealModel dm = dealModel(current, conv, boss, buffs, hitInterval, targetDef(chapter, target), companionId, companionGrade, companionLevel, ratio);
        StatBlock lDelta = deltaOfReq(dm, leftOpts);
        StatBlock rDelta = deltaOfReq(dm, rightOpts);
        StatBlock base = dm.uncombine(current, lDelta);       // 현재 장비(왼쪽) 제거
        StatBlock rightBlock = dm.combine(base, rDelta);      // 후보(오른쪽) 장착
        double leftOut = dm.output(current);
        double rightOut = dm.output(rightBlock);
        double deltaPct = leftOut > 0 ? rightOut / leftOut - 1.0 : 0.0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leftOut", safe(leftOut));
        out.put("rightOut", safe(rightOut));
        out.put("deltaPct", safe(deltaPct));
        out.put("qual", dm.hasQualitative(lDelta) || dm.hasQualitative(rDelta));
        Map<String, Object> sm = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : rightBlock.asMap().entrySet()) sm.put(e.getKey(), e.getValue());
        out.put("newStats", sm);
        return out;
    }

    // ──────────────────── 스탯 비교(등가 환산) ────────────────────

    /** 스탯 비교에서 제외할 스탯: 레벨(딜 모델 밖)·기본 공격 대상 수(상황 의존). 나머지 입력 가능한 스탯 전부 비교. */
    private static final Set<String> STAT_COMPARE_SKIP = Set.of("LEVEL", "BASIC_ATK_TARGETS");

    /**
     * 기준 스탯 한 줄(refStatId=refValue)이 올려주는 딜 증가량 D와 <b>동일한 딜</b>을 내려면
     * 다른 각 스탯이 얼마나 필요한지(등가 증가량)를 계산한다. 각 스탯 y에 대해 f(w)=increase(현재,{y:w})가
     * 0에서 단조증가하므로 f(w)=D를 푼다(대다수 스탯은 선형이라 1회 외삽으로 해, 방관·크리포화 등은 이분탐색).
     *
     * @return {inc:D, refStatId, refQual, rows:[{statId, value, self, qual, reachable}]}
     *         value=등가 증가량(NaN이면 reachable=false), qual=그 스탯이 현재 설정에서 딜 미환산이면 true.
     */
    public Map<String, Object> statCompare(Map<String, Double> stats, Map<String, Double> conv, String chapter, String target,
                                           Set<String> buffs, double hitInterval,
                                           String companionId, String companionGrade, int companionLevel, double ratio,
                                           String refStatId, double refValue) {
        StatBlock current = block(stats);
        boolean curBoss = isBossTarget(target);
        // 동료 포함(정확): 소환 가동률로 플레이어 버프가 블렌딩되므로 동소지도 플레이어 평균 DPS를 올린다.
        DealModel dm = dealModel(current, conv, curBoss, buffs, hitInterval, targetDef(chapter, target), companionId, companionGrade, companionLevel, ratio);

        // 보뎀/일뎀은 현재 스테이지 유형과 반대라 그대로면 딜 0 → 사용자 지정 교차 기준으로 평가한다.
        //  · 현재 일반몹 스테이지 → 보뎀은 그 챕터 '보스' 기준으로 계산
        //  · 현재 보스 스테이지 → 일뎀은 '바로 직전 스테이지'(그 챕터 최고 일반 스테이지) 기준으로 계산
        DealModel dmBossDmg = curBoss ? dm
                : dealModel(current, conv, true, buffs, hitInterval, targetDef(chapter, "boss"), companionId, companionGrade, companionLevel, ratio);
        DealModel dmNormalDmg = !curBoss ? dm
                : dealModel(current, conv, false, buffs, hitInterval, targetDef(chapter, prevStageTarget(chapter)), companionId, companionGrade, companionLevel, ratio);

        // 기준 스탯도 자기 컨텍스트에서 D를 측정(보뎀/일뎀을 기준으로 잡아도 딜 0이 되지 않게).
        DealModel refDm = effDm(refStatId, dm, dmBossDmg, dmNormalDmg);
        boolean refQual = refStatId != null && !refStatId.isEmpty()
                && refDm.hasQualitative(new StatBlock().with(refStatId, refValue == 0 ? 1 : refValue));
        double D = (refStatId != null && !refStatId.isEmpty() && refValue != 0)
                ? refDm.increase(current, new StatBlock().with(refStatId, refValue)) : 0.0;

        List<Map<String, Object>> rows = new ArrayList<>();
        for (StatDef d : content.stats.values()) {
            if (d.hidden || d.derived || STAT_COMPARE_SKIP.contains(d.id)) continue;
            DealModel useDm = effDm(d.id, dm, dmBossDmg, dmNormalDmg);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("statId", d.id);
            // 교차 기준 안내(현재 스테이지와 반대 유형이라 다른 대상으로 환산했음을 표시)
            if ("BOSS_DMG".equals(d.id) && !curBoss) row.put("note", "보스 기준");
            else if ("NORMAL_DMG".equals(d.id) && curBoss) row.put("note", "직전 스테이지 기준");
            boolean qual = useDm.hasQualitative(new StatBlock().with(d.id, 1));
            row.put("qual", qual);
            if (d.id.equals(refStatId)) {
                row.put("self", true);
                row.put("value", safe(refValue));
                row.put("reachable", true);
            } else {
                row.put("self", false);
                double w = (D > 1e-12 && !qual) ? solveEquivalent(useDm, current, d.id, D) : Double.NaN;
                row.put("value", safe(w));
                row.put("reachable", Double.isFinite(w));
            }
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inc", safe(D));
        out.put("refStatId", refStatId);
        out.put("refQual", refQual);
        out.put("rows", rows);
        return out;
    }

    /** 보뎀은 보스 컨텍스트 모델로, 일뎀은 일반 컨텍스트 모델로, 그 외는 현재 모델로 평가(스테이지 교차 기준). */
    private static DealModel effDm(String statId, DealModel cur, DealModel bossDmg, DealModel normalDmg) {
        if ("BOSS_DMG".equals(statId)) return bossDmg;
        if ("NORMAL_DMG".equals(statId)) return normalDmg;
        return cur;
    }

    /** 챕터의 '바로 직전 스테이지'(최고 일반 스테이지) 대상 문자열("stage:N"). 스테이지 데이터 없으면 null. */
    @SuppressWarnings("unchecked")
    private String prevStageTarget(String chapter) {
        Object cObj = content.monsterDefense == null ? null : content.monsterDefense.get("chapters");
        if (!(cObj instanceof Map) || chapter == null || chapter.isEmpty()) return null;
        Object chObj = ((Map<String, Object>) cObj).get(chapter);
        if (!(chObj instanceof Map)) return null;
        Object sObj = ((Map<String, Object>) chObj).get("stages");
        if (!(sObj instanceof Map)) return null;
        int max = -1;
        for (Object k : ((Map<String, Object>) sObj).keySet()) {
            try { max = Math.max(max, Integer.parseInt(String.valueOf(k))); } catch (NumberFormatException ignored) { /* 비정수 키 무시 */ }
        }
        return max < 0 ? null : ("stage:" + max);
    }

    /**
     * f(w)=increase(현재,{sid:w})=target 을 만족하는 w(≥0)를 찾는다. f는 0에서 단조증가.
     * 선형 스탯은 1회 외삽으로 즉시 끝나고(2회 호출), 비선형(방관·공속 체감·벞지/동소지 포화·스킬레벨)은
     * 좁은 이분탐색으로 좁히되 <b>상대오차 0.01%면 조기 종료</b>한다(표시 2자리에 충분).
     *
     * <p>성능: {@code increase}는 동료가 있으면 한 번에 ~16ms라, 호출 수를 최소화한다. 선형 외삽으로 상한을
     * 근접시켜 이분 반복을 크게 줄이고(옛 최대 140회 → 보통 10여 회), 큰 값에도 딜이 0이면 2회 만에 도달불가 판정.
     */
    /**
     * 탐색 상한. 이보다 큰 값이 필요하면 사실상 '도달 불가'로 본다. 동시에 딜 계산에 <b>말도 안 되는 수치</b>가
     * 들어가는 걸 막는다(예: 동소지가 포화라 못 미치면 옛 코드는 1e15까지 키웠는데, 소환창 W=지속×(1+동소지/100)이
     * 그만큼 폭증해 시뮬 스텝 수(W/DT)가 사실상 무한 → 요청 자체가 멈춤). 상한을 두어 근본 차단.
     */
    private static final double SOLVE_MAX_W = 1e7;

    private double solveEquivalent(DealModel dm, StatBlock current, String sid, double target) {
        double f1 = incOne(dm, current, sid, 1.0);
        double hi, fhi;
        if (f1 > 0) {
            hi = Math.min(SOLVE_MAX_W, Math.max(1.0, target / f1));   // 선형 외삽으로 상한을 해 근처에(상한 클램프)
            fhi = incOne(dm, current, sid, hi);
            if (nearTarget(fhi, target)) return hi;       // 선형 스탯: 여기서 끝(2회 호출)
        } else {
            hi = 1e6; fhi = incOne(dm, current, sid, 1e6);
            if (!(fhi > 0)) return Double.NaN;            // 큰 값에도 딜 0(크리 포화·대상없는 방관 등) → 도달 불가
        }
        int guard = 0;
        while (fhi < target) {                            // 상한이 부족하면 ×8로 빠르게 확장(상한 SOLVE_MAX_W에서 멈춤)
            if (hi >= SOLVE_MAX_W) return Double.NaN;      // 상한에서도 target 미달 → 포화로 도달 불가
            hi = Math.min(SOLVE_MAX_W, hi * 8);
            if (++guard > 40) return Double.NaN;
            fhi = incOne(dm, current, sid, hi);
        }
        double lo = 0;
        for (int i = 0; i < 40; i++) {
            double mid = 0.5 * (lo + hi);
            double fm = incOne(dm, current, sid, mid);
            if (nearTarget(fm, target)) return mid;                       // 딜 상대오차 0.01% → 조기 종료
            if (fm < target) lo = mid; else hi = mid;
            if (hi - lo <= 1e-6 * Math.max(1.0, hi)) break;              // w 수렴 → 종료
        }
        return 0.5 * (lo + hi);
    }

    /** 목표 딜 증가율에 상대오차 0.01% 이내면 적중으로 본다(스탯 비교 표시 정밀도에 충분). */
    private static boolean nearTarget(double f, double target) {
        return Math.abs(f - target) <= 1e-4 * Math.max(1e-9, target);
    }

    private double incOne(DealModel dm, StatBlock current, String sid, double w) {
        return dm.increase(current, new StatBlock().with(sid, w));
    }

    // ──────────────────── 장비 비교 OCR(Gemini 비전) ────────────────────

    public boolean ocrEnabled() { return ocr != null && ocr.enabled(); }

    /**
     * 장비 스크린샷(base64) → 비전 모델로 좌/우 박스의 옵션을 인식한다.
     *
     * <p>모델에는 <b>화면의 한글 라벨과 값만 원문 그대로</b> 추출하게 하고(딜 라벨 enum 강제 X),
     * statId 매핑·주(main)/부(sub) 분류·비딜 제거는 서버가 결정적으로 처리한다. enum을 강제하면
     * 실제 옵션이 비딜(명중/방어력)일 때 모델이 딜 옵션으로 환각하는 문제가 있어 이 구조로 바꿈.
     *
     * @return {left:{main:[{statId,value}],sub:[...]}, right:{...}} — 이 부위 허용 옵션만 필터링.
     */
    public Map<String, Object> gearOcr(String imageBase64, String mimeType, String part) throws Exception {
        if (ocr == null || !ocr.enabled()) throw new IllegalStateException("OCR이 설정되지 않았습니다.");
        if (imageBase64 == null || imageBase64.isEmpty()) throw new IllegalArgumentException("이미지가 없습니다.");

        // 부위 이름은 읽지 않는다. 딜 계산 = 현재스탯 - 좌옵션 + 우옵션 이고 부위 라벨은 계산에 안 들어가며
        // 프리셋도 없으므로, 인식된 주/부 옵션을 모두 담을 수 있는 부위를 고르기만 하면 된다(옵션만 정확하면 됨).
        // 그래서 모든 부위의 옵션 합집합으로 먼저 추출한 뒤, 옵션을 커버하는 부위를 서버가 고른다.
        LinkedHashSet<String> unionMain = allMainStats();
        LinkedHashSet<String> unionSub = allSubStats();

        // 대비 강화 전처리: 흐릿한 회색 필터 오버레이는 배경으로 묻히고 흰색 옵션 글씨는 또렷해져 인식률이 오른다.
        String ocrImage = imageBase64, ocrMime = mimeType;
        byte[] enhanced = enhanceForOcr(imageBase64);
        if (enhanced != null) { ocrImage = Base64.getEncoder().encodeToString(enhanced); ocrMime = "image/png"; }

        // 비전 모델은 드물게 좌측 박스를 통째로 누락한다. 좌측이 비면 1회 재시도(실패에서만 발동).
        Map<String, Object> left = null, right = null;
        String ocrPartName = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> raw = ocr.extract(ocrImage, ocrMime, ocrPrompt(), ocrSchema());
            left = mapOcrSide(raw.get("left"), unionMain, unionSub);
            right = mapOcrSide(raw.get("right"), unionMain, unionSub);
            // 좌 박스 부위명 우선(현재 장비), 없으면 우 박스 부위명.
            ocrPartName = firstNonBlank(String.valueOf(raw.get("leftPart")), String.valueOf(raw.get("rightPart")));
            if (!sideEmpty(left)) break;
        }

        // 화면의 '등급 부위명'에서 읽은 부위를 우선 사용. 못 읽었으면 좌 옵션으로 역추론(폴백).
        String canon = canonicalPart(ocrPartName);
        String effPart = (canon != null) ? canon : inferPart(left, part);
        Map<String, Object> partDef = gearPartDef(effPart);
        if (partDef == null) throw new IllegalArgumentException("알 수 없는 부위: " + part);
        LinkedHashSet<String> pMain = partMainStats(partDef), pSub = partSubStats(partDef);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("part", effPart);   // 클라가 부위 드롭다운을 여기에 맞춰 전환
        out.put("left", filterSide(left, pMain, pSub));
        out.put("right", filterSide(right, pMain, pSub));
        return out;
    }

    /** 한 박스 결과가 완전히 비었는지(주·부 모두 없음). */
    private static boolean sideEmpty(Map<String, Object> side) {
        Object m = side.get("main"), s = side.get("sub");
        return (!(m instanceof List) || ((List<?>) m).isEmpty()) && (!(s instanceof List) || ((List<?>) s).isEmpty());
    }

    /** 모든 부위 주 옵션 statId 합집합(추출 시 아무 것도 안 걸리게 union으로 매핑). */
    private LinkedHashSet<String> allMainStats() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (Object p : asListOr(content.gearOptions.get("parts")))
            for (Object mo : asListOr(Json.asMap(p).get("main"))) s.add(String.valueOf(Json.asMap(mo).get("stat")));
        return s;
    }

    /** 모든 부위 부 옵션 statId 합집합(공통 + 모든 특옵). */
    private LinkedHashSet<String> allSubStats() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (Object so : asListOr(content.gearOptions.get("commonSub"))) s.add(so.toString());
        for (Object p : asListOr(content.gearOptions.get("parts")))
            for (Object so : asListOr(Json.asMap(p).get("special"))) s.add(so.toString());
        return s;
    }

    private static LinkedHashSet<String> partMainStats(Map<String, Object> partDef) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (Object mo : asListOr(partDef.get("main"))) s.add(String.valueOf(Json.asMap(mo).get("stat")));
        return s;
    }

    private LinkedHashSet<String> partSubStats(Map<String, Object> partDef) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (Object so : asListOr(content.gearOptions.get("commonSub"))) s.add(so.toString());
        for (Object so : asListOr(partDef.get("special"))) s.add(so.toString());
        return s;
    }

    /**
     * 인식된 좌측 옵션(주+부)을 모두 담을 수 있는 부위를 고른다. 계산은 부위와 무관하므로 어떤 것을 골라도
     * 결과는 같다(풀이 같은 부위끼리는 구분 불가하나 무방). 현재 선택 부위가 이미 커버하면 그대로 유지(불필요한 전환 방지).
     */
    // OCR이 읽은 '등급 부위명' 텍스트에서 우리 부위명으로 정규화. 등급 접두어·공백은 무시하고 부위명 포함 여부로 매칭.
    // "반지2"를 "반지"보다 먼저 검사해야 반지2가 반지로 오분류되지 않는다(반지2 ⊃ 반지). 얼굴/눈 장식은 전체명 매칭이라 겹치지 않음.
    private static final List<String> OCR_PART_NAMES = List.of(
        "반지2", "투구", "상의", "망토", "견장", "하의", "장갑", "벨트", "신발", "반지", "목걸이", "얼굴장식", "눈장식", "귀고리");

    private String canonicalPart(String ocrName) {
        if (ocrName == null) return null;
        String t = ocrName.replaceAll("\\s+", "");
        if (t.isEmpty() || t.equals("null")) return null;
        for (String p : OCR_PART_NAMES) {
            if (t.contains(p) && gearPartDef(p) != null) return p;   // 실제 존재하는 부위만
        }
        return null;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty() && !a.equals("null")) return a;
        if (b != null && !b.trim().isEmpty() && !b.equals("null")) return b;
        return "";
    }

    private String inferPart(Map<String, Object> left, String userPart) {
        Set<String> mains = sideStatIds(left == null ? null : left.get("main"));
        Set<String> subs = sideStatIds(left == null ? null : left.get("sub"));
        String first = null;
        for (Object p : asListOr(content.gearOptions.get("parts"))) {
            Map<String, Object> pd = Json.asMap(p);
            if (partMainStats(pd).containsAll(mains) && partSubStats(pd).containsAll(subs)) {
                String name = String.valueOf(pd.get("part"));
                if (name.equals(userPart)) return userPart;   // 현재 선택이 맞으면 유지
                if (first == null) first = name;
            }
        }
        return first != null ? first : userPart;   // 커버하는 부위 없음(이상 케이스) → 유저 선택 유지
    }

    private static Set<String> sideStatIds(Object arr) {
        Set<String> s = new LinkedHashSet<>();
        for (Object o : asListOr(arr)) {
            Object id = Json.asMap(o).get("statId");
            if (id != null) s.add(id.toString());
        }
        return s;
    }

    /** 한 박스를 지정 부위의 주/부 풀로 필터(선택된 부위가 커버하므로 보통 그대로). */
    private static Map<String, Object> filterSide(Map<String, Object> side, Set<String> pMain, Set<String> pSub) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", filterOpts(side == null ? null : side.get("main"), pMain));
        out.put("sub", filterOpts(side == null ? null : side.get("sub"), pSub));
        return out;
    }

    private static List<Object> filterOpts(Object arr, Set<String> allowed) {
        List<Object> out = new ArrayList<>();
        for (Object o : asListOr(arr)) {
            Object id = Json.asMap(o).get("statId");
            if (id != null && allowed.contains(id.toString())) out.add(o);
        }
        return out;
    }

    private Map<String, Object> gearPartDef(String part) {
        for (Object o : asListOr(content.gearOptions.get("parts"))) {
            Map<String, Object> m = Json.asMap(o);
            if (part != null && part.equals(m.get("part"))) return m;
        }
        return null;
    }

    /** 주 옵션(상단 고정 블록)에 나타나는 스탯의 정규화 라벨(분류용). 이 라벨로 시작하는 앞줄들을 main으로 본다. */
    private static final Set<String> MAIN_NORM = Set.of("공격력", "최대HP", "방어력", "회피", "명중", "최대MP", "주스탯");

    /** 한글 라벨(정규화) → statId 역매핑. 지연 초기화. '주스탯'은 flat/pct 충돌이라 제외하고 섹션으로 구분. */
    private volatile Map<String, String> labelToStat;

    private Map<String, String> labelToStat() {
        Map<String, String> m = labelToStat;
        if (m != null) return m;
        m = new HashMap<>();
        // 비딜 주 옵션(stats.json에 없음) — gear_options의 한글명 사용
        for (Map.Entry<String, Object> e : asMapOr(content.gearOptions.get("nonDealLabels")).entrySet()) {
            m.put(normLabel(String.valueOf(e.getValue())), e.getKey());
        }
        // 딜 옵션(공통 부옵션 + 모든 부위 특옵 + 주 옵션 딜 스탯) — stats.json 한글명 사용
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Object so : asListOr(content.gearOptions.get("commonSub"))) ids.add(so.toString());
        for (Object p : asListOr(content.gearOptions.get("parts"))) {
            Map<String, Object> pm = Json.asMap(p);
            for (Object mo : asListOr(pm.get("main"))) ids.add(String.valueOf(Json.asMap(mo).get("stat")));
            for (Object so : asListOr(pm.get("special"))) ids.add(so.toString());
        }
        for (String id : ids) {
            if ("MAIN_STAT_FLAT".equals(id) || "MAIN_STAT_PCT".equals(id)) continue;   // '주스탯' 충돌 → 섹션으로 구분
            String norm = normLabel(statName(id));
            if (!norm.isEmpty()) m.putIfAbsent(norm, id);
        }
        labelToStat = m;
        return m;
    }

    /** 라벨(정규화) → statId. '주스탯'은 주 옵션이면 flat, 부 옵션이면 %(귀고리 특옵). 미해석은 null. */
    private String resolveStat(String norm, boolean main) {
        if ("주스탯".equals(norm)) return main ? "MAIN_STAT_FLAT" : "MAIN_STAT_PCT";
        return labelToStat().get(norm);
    }

    /**
     * 한 박스의 원문 [{label,value}] 목록을 {main:[{statId,value}], sub:[...]}로 매핑한다.
     * 분류: 앞에서부터 (index<3 && 주 옵션형 라벨)이면 main, 그 지점 이후는 전부 sub. 비딜 주 옵션이
     * 누락돼도 남은 줄이 sub로 정확히 분류되도록 count가 아닌 라벨 타입으로 경계를 잡는다.
     * main은 이 부위 주 옵션 statId만, sub는 이 부위 딜 풀 statId만 남긴다(비딜 부옵션=명중/방어력 등은 제거).
     */
    private Map<String, Object> mapOcrSide(Object sideObj, Set<String> mainStats, Set<String> subStats) {
        List<Object> rows = asListOr(sideObj);
        int split = 0;
        for (int i = 0; i < rows.size() && i < 3; i++) {
            if (MAIN_NORM.contains(normLabel(labelOf(rows.get(i))))) split = i + 1; else break;
        }
        List<Map<String, Object>> main = new ArrayList<>();
        for (int i = 0; i < split; i++) {
            Map<String, Object> o = resolveRow(rows.get(i), true);
            if (o != null && mainStats.contains(o.get("statId"))) main.add(o);
        }
        List<Map<String, Object>> sub = new ArrayList<>();
        for (int i = split; i < rows.size(); i++) {
            Map<String, Object> o = resolveRow(rows.get(i), false);
            if (o != null && subStats.contains(o.get("statId"))) sub.add(o);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", main); out.put("sub", sub);
        return out;
    }

    private Map<String, Object> resolveRow(Object rowObj, boolean main) {
        String label = labelOf(rowObj);
        Double val = parseNum(valueOf(rowObj));
        if (label == null || val == null) return null;
        String statId = resolveStat(normLabel(label), main);
        if (statId == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statId", statId); m.put("value", val);
        return m;
    }

    private static String labelOf(Object rowObj) {
        if (!(rowObj instanceof Map)) return null;
        Object l = ((Map<?, ?>) rowObj).get("label");
        return l == null ? null : l.toString();
    }

    private static Object valueOf(Object rowObj) {
        return (rowObj instanceof Map) ? ((Map<?, ?>) rowObj).get("value") : null;
    }

    /** 라벨 정규화: 공백·"(+)"·"%"·"+" 제거(HP/MP 등 라틴은 유지). 스탯명 접미사와 화면 라벨을 같은 형태로. */
    private static String normLabel(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s", "").replace("(+)", "").replace("%", "").replace("+", "");
    }

    private static final java.util.regex.Pattern NUM_TOKEN =
            java.util.regex.Pattern.compile("-?[0-9][0-9,]*(?:\\.[0-9]+)?");

    /**
     * 값 파싱: 숫자면 그대로, 문자열이면 <b>첫 번째 숫자 토큰만</b> 취한다. 새 장비 박스는 한 옵션에
     * 흰 숫자(실제 값)+초록 숫자(증가분)가 같이 나올 수 있어, 앞의 흰 값만 쓰고 뒤 델타는 버린다.
     * (쉼표=천단위 구분 제거). 실패 null.
     */
    private static Double parseNum(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v == null) return null;
        java.util.regex.Matcher m = NUM_TOKEN.matcher(v.toString());
        if (!m.find()) return null;
        try { return Double.parseDouble(m.group().replace(",", "")); } catch (NumberFormatException e) { return null; }
    }

    /**
     * OCR 정확도용 대비 강화. 각 채널에 레벨(입력 lo~hi → 0~255) 보정을 적용해
     * 어두운/흐릿한 회색(필터 오버레이 등)은 검정으로 밀고 밝은 흰색 글씨는 또렷하게 만든다.
     * 색(초록 증가분·노랑 등)은 채널별로 처리해 대체로 보존된다. 실패 시 null(원본 사용).
     */
    private static final int OCR_LEVEL_LO = 120, OCR_LEVEL_HI = 230;
    private static byte[] enhanceForOcr(String base64) {
        try {
            byte[] data = Base64.getDecoder().decode(base64);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) return null;
            int w = img.getWidth(), h = img.getHeight();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            double span = OCR_LEVEL_HI - OCR_LEVEL_LO;
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                img.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    int argb = row[x];
                    int r = level((argb >> 16) & 0xFF, span);
                    int g = level((argb >> 8) & 0xFF, span);
                    int b = level(argb & 0xFF, span);
                    row[x] = (r << 16) | (g << 8) | b;
                }
                out.setRGB(0, y, w, 1, row, 0, w);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;   // 전처리 실패 시 원본 이미지로 진행
        }
    }
    private static int level(int c, double span) {
        double v = (c - OCR_LEVEL_LO) / span;
        if (v < 0) v = 0; else if (v > 1) v = 1;
        return (int) Math.round(v * 255);
    }

    private static String ocrPrompt() {
        return "당신은 모바일 게임 '메이플 키우기'의 장비 상세 화면을 읽는 OCR 도구입니다.\n"
            + "이미지에 장비 상세 박스가 1~2개 있습니다(보통 왼쪽=현재 장비, 오른쪽=새 장비).\n"
            + "각 박스의 '장착 효과' 목록에 있는 모든 옵션 행을 위에서부터 순서대로, 하나도 빠짐없이 추출하세요.\n"
            + "- label: 화면에 보이는 한글 옵션 이름 그대로(예: '공격력', '최대 HP', '주 스탯', '크리티컬 데미지', "
            + "'최대 데미지 배율', '방어력', '명중').\n"
            + "- value: 그 옵션의 숫자 값(쉼표·% 포함해도 됨). 예: '15,735', '15.8%', '11'.\n"
            + "- 새 장비(오른쪽) 박스에서는 한 옵션에 숫자가 둘(흰색=실제 값, 초록색=현재 장비 대비 증가분) 보일 수 있습니다. "
            + "반드시 흰색(왼쪽·실제 값)만 value에 넣고, 초록색 증가분은 절대 넣지 마세요.\n"
            + "- 상단의 주 옵션(보통 3개: 공격력·최대 HP·부위 고유)과 하단의 부 옵션을 모두 포함하세요. "
            + "최대 HP·방어력·명중처럼 딜과 무관해 보여도 반드시 포함하세요.\n"
            + "- 세트 효과·설명문·강화 수치 등 옵션 이름이 아닌 텍스트는 제외하세요.\n"
            + "- 화면에 '프리셋 설정' 같은 필터·설정 패널이나, 장비 박스 위에 반투명하게 겹쳐 보이는 회색 스탯 목록이 "
            + "있을 수 있습니다. 이 필터 목록의 항목은 이름 오른쪽에 '숫자 값이 아니라 동그란 체크박스/토글'이 붙어 있습니다. "
            + "반면 진짜 장비 옵션은 이름 오른쪽에 반드시 '숫자 값'이 있습니다. "
            + "이름 오른쪽에 숫자 값이 없는(체크박스만 있는) 항목, 흐릿한 회색 항목은 필터이므로 절대 넣지 마세요. "
            + "특히 '일반 몬스터 데미지', 'N차 스킬 레벨', 'N차 스킬 데미지' 같은 항목이 회색이거나 값 없이 체크박스만 있으면 "
            + "그것은 필터 목록이니 반드시 제외하세요.\n"
            + "- 어떤 옵션의 숫자 값은 보이는데 그 왼쪽의 옵션 이름이 필터에 가려 확실히 안 보이면, 이름을 추측하지 말고 "
            + "그 행 전체를 건너뛰세요(없는 스탯을 지어내지 마세요).\n"
            + "- 각 박스에서 장비 이름(흰 글씨) 바로 아래, 아이콘 오른쪽의 '등급 부위명' 텍스트"
            + "(예: '레전드리 하의', '유니크 반지2', '에픽 얼굴장식')에서 부위명만 읽으세요. "
            + "왼쪽 박스 부위명은 leftPart, 오른쪽 박스 부위명은 rightPart에 넣습니다. "
            + "부위명은 반드시 다음 중 하나: 투구, 상의, 망토, 견장, 하의, 장갑, 벨트, 신발, 반지, 반지2, 목걸이, 얼굴장식, 눈장식, 귀고리. "
            + "등급 접두어(레전드리·유니크·에픽·중급·최상급 등)는 빼고 부위명만. 확실히 못 읽으면 빈 문자열.\n"
            + "- 왼쪽 박스는 left, 오른쪽 박스는 right. 박스가 하나뿐이면 그 박스를 left에 넣고 right는 빈 배열.\n"
            + "반드시 JSON만 출력: {\"leftPart\":\"...\",\"rightPart\":\"...\",\"left\":[{\"label\":\"...\",\"value\":\"...\"}],\"right\":[{\"label\":\"...\",\"value\":\"...\"}]}";
    }

    /** 응답 스키마: 각 박스 {label,value} 배열(원문 라벨/값). statId 매핑은 서버가 수행. */
    private static Object ocrSchema() {
        Map<String, Object> label = new LinkedHashMap<>(); label.put("type", "STRING");
        Map<String, Object> value = new LinkedHashMap<>(); value.put("type", "STRING");
        Map<String, Object> optProps = new LinkedHashMap<>(); optProps.put("label", label); optProps.put("value", value);
        Map<String, Object> optItem = new LinkedHashMap<>();
        optItem.put("type", "OBJECT"); optItem.put("properties", optProps); optItem.put("required", List.of("label", "value"));
        Map<String, Object> arr = new LinkedHashMap<>(); arr.put("type", "ARRAY"); arr.put("items", optItem);
        Map<String, Object> partStr = new LinkedHashMap<>(); partStr.put("type", "STRING");
        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("leftPart", partStr); rootProps.put("rightPart", partStr);
        rootProps.put("left", arr); rootProps.put("right", arr);
        Map<String, Object> root = new LinkedHashMap<>(); root.put("type", "OBJECT"); root.put("properties", rootProps);
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMapOr(Object o) { return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked")
    private static List<Object> asListOr(Object o) { return (o instanceof List) ? (List<Object>) o : new ArrayList<>(); }

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
        for (PotentialDraw d : curDraws) if (d != null) leftDelta.set(d.statId(), dm.aggregate(d.statId(), leftDelta.get(d.statId()), d.value()));
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
        for (PotentialDraw d : rOpts) rightDelta.set(d.statId(), dm.aggregate(d.statId(), rightDelta.get(d.statId()), d.value()));
        double rightDeal = curBase > 0 ? dm.output(dm.combine(baseline, rightDelta)) / curBase - 1.0 : 0;
        double dealDiff = rightDeal - leftDeal;

        List<PotentialDraw> lOpts = new ArrayList<>();
        for (PotentialDraw d : curDraws) if (d != null) lOpts.add(d);
        double leftProbSum = CubeRecommender.appearProb(lOpts, leftSlots);

        List<List<PotentialDraw>> rightTables = content.equip.slots(part, rightGrade);
        double arrange = CubeRecommender.appearProb(rOpts, rightTables);
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
