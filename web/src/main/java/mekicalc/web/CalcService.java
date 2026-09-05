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
import mekicalc.timeline.Action;
import mekicalc.timeline.Analyzer;
import mekicalc.timeline.ArtifactAssembler;
import mekicalc.timeline.BuffAccumulator;
import mekicalc.timeline.BuffSource;
import mekicalc.timeline.DamageFormula;
import mekicalc.timeline.DealEvent;
import mekicalc.timeline.DealExpander;
import mekicalc.timeline.DmgType;
import mekicalc.timeline.ModState;
import mekicalc.timeline.Preset;
import mekicalc.timeline.Slot;
import mekicalc.timeline.Stat;
import mekicalc.timeline.Schedule;
import mekicalc.timeline.Scheduler;
import mekicalc.timeline.Timeline;
import mekicalc.timeline.ir.ArtifactBook;
import mekicalc.timeline.ir.EffectIR;
import mekicalc.timeline.ir.SkillBook;
import mekicalc.timeline.ir.SkillIR;

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
    private static final int JOB_HERO = 4;
    private static final int JOB_BOWMASTER = 5;
    private static final double NORMAL_SECONDS = 90;   // 일반몹(챕터 스테이지) 기준 전투시간
    private static final double BOSS_SECONDS = 70;      // 보스(챕터 보스) 기준 전투시간

    private final GameContent content;
    private final VisionOcr ocr;
    private volatile SkillBook nlBook;   // 나이트로드 IR(타임라인 엔진용), 최초 요청 시 지연 로딩
    private volatile ArtifactBook nlArtBook;   // 유물 IR(타임라인 조립용), 최초 요청 시 지연 로딩
    private volatile mekicalc.timeline.ir.SupporterBook supBook;   // 동료 IR(v3.1 동료 엔진용), 최초 요청 시 지연 로딩
    private volatile Map<String, Integer> nlArtSlugToIdx;   // 카탈로그 슬러그id → manual idx(이름 조인), 지연 구축

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
        if (job == JOB_HERO) return content.skillDataHero;
        if (job == JOB_BOWMASTER) return content.skillDataBow;
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
    /** 대상 최대체력(monster_hp.json). target="boss"=챕터 보스, 아니면 스테이지 일반몹(사냥 기준). 데이터 없으면 null. 표시 전용. */
    @SuppressWarnings("unchecked")
    private Double targetHp(String chapter, String target) {
        Object cObj = content.monsterHp == null ? null : content.monsterHp.get("chapters");
        if (!(cObj instanceof Map) || chapter == null || chapter.isEmpty()) return null;
        Object chObj = ((Map<String, Object>) cObj).get(chapter);
        if (!(chObj instanceof Map)) return null;
        Map<String, Object> c = Json.asMap(chObj);
        if (c == null || c.isEmpty()) return null;
        if ("boss".equals(target)) return c.get("boss") instanceof Number ? ((Number) c.get("boss")).doubleValue() : null;
        String stage = target != null && target.startsWith("stage:") ? target.substring(6) : target;
        return stageDef(c, stage);   // stageDef=stages[스테이지] 조회(방어/체력 공통 구조)
    }
    /** 챕터 도전(monster_challenge.json) 스테이지 스탯. which="mob"|"boss"(그 스테이지 고유 보스, 챕터 최종보스 아님), key="hp"|"def". 데이터 없으면 null. */
    @SuppressWarnings("unchecked")
    private Double challengeStat(String chapter, String stageTarget, String which, String key) {
        Object cObj = content.monsterChallenge == null ? null : content.monsterChallenge.get("chapters");
        if (!(cObj instanceof Map) || chapter == null || chapter.isEmpty()) return null;
        Object chObj = ((Map<String, Object>) cObj).get(chapter);
        if (!(chObj instanceof Map)) return null;
        Object stagesObj = Json.asMap(chObj).get("stages");
        if (!(stagesObj instanceof Map)) return null;
        String stage = stageTarget != null && stageTarget.startsWith("stage:") ? stageTarget.substring(6) : stageTarget;
        Object stObj = ((Map<String, Object>) stagesObj).get(stage);
        if (!(stObj instanceof Map)) return null;
        Object node = ((Map<String, Object>) stObj).get(which);
        if (!(node instanceof Map)) return null;   // boss=null(ch1~2 스테이지 보스 없음) 포함
        Object v = ((Map<String, Object>) node).get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
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
            m.put("combine", d.combine.name());   // SUM | DIMINISHING | MULT_PCT — 변경 실험의 옵션 가산식용
            m.put("max", d.max);                    // DIMINISHING cap(공속150·방관100·쿨감%100)
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
            if (dm.hasQualitative(b) || (job == 0 && COMMON_JOB_QUAL.contains(d.id))) ids.add(d.id);   // 공용: 기본공격/스킬 데미지도 딜 미환산으로 표시
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
                    dn.put("relicShares", relicShares(nd, b, false, NORMAL_SECONDS, artifacts, enemyDebuff, art, soloDpsOf(dn), comp));
                    db.put("relicShares", relicShares(nd, b, true, BOSS_SECONDS, artifacts, enemyDebuff, art, soloDpsOf(db), comp));
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
     *
     * <p><b>동료 대상 선반영 유물</b>(아르웬의 유리구두=소환 지속시간)은 미소환 기준 딜 변화가 0이라, 대신
     * <b>소환 종합 딜</b>(전투 평균, 동료 포함)을 기준으로 그 유물이 없을 때 대비 증가율(%)을 {@code pct}로 넣는다.
     */
    private List<Map<String, Object>> relicShares(SkillDps nd, StatBlock b, boolean boss, double seconds,
                                                  List<ArtifactEffects.Equip> artifacts, boolean enemyDebuff,
                                                  ArtifactEffects artFull, double fullSoloDps, CompanionDps comp) {
        List<Map<String, Object>> out = new ArrayList<>();
        double fullSummonTotal = Double.NaN;   // 동료 유물이 있을 때만(비쌈) 지연 계산
        for (int i = 0; i < artifacts.size(); i++) {
            ArtifactEffects.Equip removed = artifacts.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", artFull.nameOf(removed.id));
            // 동료 대상 선반영(소환 지속시간 등): 소환 종합 딜 기준 기여(미소환 기준 0이므로 별도 처리).
            Map<String, Double> compReflected = comp == null ? java.util.Collections.emptyMap()
                    : artFull.preReflectedCompanionStats(removed, b, boss);
            if (!compReflected.isEmpty()) {
                if (Double.isNaN(fullSummonTotal)) fullSummonTotal = combatTotalDps(nd, b, boss, seconds, comp, artFull, enemyDebuff);
                StatBlock bC = new StatBlock(b);
                for (Map.Entry<String, Double> e : compReflected.entrySet()) bC.set(e.getKey(), bC.get(e.getKey()) - e.getValue());
                double withoutTotal = combatTotalDps(nd, bC, boss, seconds, comp, artFull, enemyDebuff);
                double pct = withoutTotal > 0 ? Math.max(0, (fullSummonTotal - withoutTotal) / withoutTotal * 100) : 0;
                row.put("dps", 0.0);
                row.put("pct", safe(pct));
                row.put("base", "summon");   // 소환 종합 딜 기준(프론트가 solo 기준 대신 이 pct를 씀)
                out.add(row);
                continue;
            }
            // 일반: 미소환(solo) DPS에서 그 유물을 뺐을 때 감소분.
            List<ArtifactEffects.Equip> without = new ArrayList<>(artifacts);
            without.remove(i);
            ArtifactEffects artW = without.isEmpty() ? null : new ArtifactEffects(content.artifactRoot, without);
            // 선반영 유물이면 프리셋에 이미 녹아든 각성 스탯을 빼고 재계산한다(compute 유물이면 빈 맵이라 b 그대로).
            StatBlock bW = new StatBlock(b);
            for (Map.Entry<String, Double> e : artFull.preReflectedPlayerStats(removed, b, boss).entrySet())
                bW.set(e.getKey(), bW.get(e.getKey()) - e.getValue());
            double dpsW = nd.dpsSimulateBreakdown(bW, boss, seconds, simCtx(artW, bW, enemyDebuff, 0))[2];
            row.put("dps", safe(Math.max(0, fullSoloDps - dpsW)));
            out.add(row);
        }
        return out;
    }

    /**
     * 소환 종합 딜(전투 평균) = 플레이어 블렌드 딜(소환 가동률 가중) + 동료 전투시간 평균 딜. detailFor의 {@code dps.total}과
     * 동일 기준으로, 주어진 base 스탯 b(소환지속 차감본 등)로 재계산한다. 동료 없으면 플레이어 딜만.
     */
    private double combatTotalDps(SkillDps nd, StatBlock b, boolean boss, double seconds,
                                  CompanionDps comp, ArtifactEffects art, boolean enemyDebuff) {
        double playerRecv = comp == null ? 0 : nd.playerRecvBonus(nd.buffed(b).stats);
        double uptime = comp == null ? 0 : comp.summonUptime(b);
        double Rp = 1 + playerRecv;
        Map<String, Double> parts = blendPlayerBySkill(nd, b, boss, comp, uptime, Rp, seconds, art, enemyDebuff);
        double total = 0; for (double v : parts.values()) total += v;
        if (comp != null) {
            StatBlock inh = nd.fullBuffStats(b, boss, seconds, simCtx(art, b, enemyDebuff, 1));
            comp.playerRecvBonus(playerRecv + artRecv(art, inh, boss, seconds));
            total += comp.dpsOverSeconds(inh, boss, seconds)[2];
        }
        return total;
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

    private SkillBook nlBook() {
        SkillBook b = nlBook;
        if (b == null) {
            synchronized (this) {
                if (nlBook == null) nlBook = SkillBook.load(content.dataDir.resolve("skills_calc/nightlord_manual.json"));
                b = nlBook;
            }
        }
        return b;
    }

    private mekicalc.timeline.ir.SupporterBook supBook() {
        mekicalc.timeline.ir.SupporterBook b = supBook;
        if (b == null) {
            synchronized (this) {
                if (supBook == null) supBook = mekicalc.timeline.ir.SupporterBook.load(
                        content.dataDir.resolve("skills_calc/supporter_manual.json"),
                        content.dataDir.resolve("skills_calc/supporter_seed.json"));
                b = supBook;
            }
        }
        return b;
    }

    /** 사용 가능한 동료 목록(supporter_manual.json 등록분) — 프론트 선택 드롭다운용. */
    // ★배포 게이트: 인게임 실측 검증이 끝난 동료만 노출·계산. 미검증(나이트로드 등)은 데이터는 보유하되 숨김 — 검증 후 클래스 추가.
    private static final java.util.Set<String> AVAILABLE_SUPPORTER_CLASSES = java.util.Set.of("Bishop", "NightLord", "WindBreaker", "Viper", "ArchMageFP");
    private boolean supporterAvailable(String code) {
        if (code == null || code.isEmpty()) return false;
        mekicalc.timeline.ir.SupporterIR s = supBook().supporter(code);
        return s != null && AVAILABLE_SUPPORTER_CLASSES.contains(s.clazz());
    }

    public List<Map<String, Object>> supporters() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (mekicalc.timeline.ir.SupporterIR s : supBook().all()) {
            if (!AVAILABLE_SUPPORTER_CLASSES.contains(s.clazz())) continue;   // 미검증 동료 숨김(비숍만 노출)
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", s.code());
            m.put("name", s.name());
            m.put("class", s.clazz());
            m.put("grade", s.grade());
            m.put("levelCap", supGradeLevelCap(s.grade()));
            out.add(m);
        }
        return out;
    }

    private static int supGradeLevelCap(String grade) {
        return switch (grade == null ? "" : grade) {
            case "cadet" -> 100; case "rare" -> 50; case "epic" -> 30; case "unique" -> 10; case "legend" -> 16;
            default -> 1;
        };
    }

    private ArtifactBook nlArtBook() {
        ArtifactBook b = nlArtBook;
        if (b == null) {
            synchronized (this) {
                if (nlArtBook == null) nlArtBook = ArtifactBook.load(content.dataDir.resolve("skills_calc/artifact_manual.json"));
                b = nlArtBook;
            }
        }
        return b;
    }

    /**
     * {@link #buildTimeline}의 산출물 묶음 — 유물까지 구운 Timeline과 표시에 쓰는 중간 결과들.
     * nlTimeline(표시)과 TimelineDealModel(딜 재평가)이 공유한다.
     */
    record TimelineBuild(
            Timeline tl, Preset preset, double monDef, boolean boss, int simMs,
            Scheduler sched, Schedule schedule, DealExpander de, SkillBook book,
            ArtifactAssembler.Result artRes, List<BuffSource> sources,
            List<Map<String, Object>> cycleRows, List<Map<String, Object>> derivedRows,
            List<ArtifactAssembler.StackSeg> stackSegs,
            mekicalc.timeline.WindowSet ws) {}   // ★버프/디버프 값-윈도우 집합 — 임의 시각 실효 ModState 조회(동료 스냅샷·적 디버프 경계 정확)

    /**
     * stats(유물 딜 미포함 프리셋) + artifacts/potentials로 유물까지 구운 Timeline을 만든다.
     * nlTimeline의 tl 생성부(스케줄→전개→버프누적→유물조립)를 추출한 것 — 딜 재평가(큐브 추천)에서 재사용한다.
     */
    TimelineBuild buildTimeline(Map<String, Double> stats, String mode,
                                List<Map<String, Object>> casts, String chapter, String target, int simMs,
                                List<Map<String, Object>> artifacts, List<Map<String, Object>> potentials,
                                Integer enemies, Double kills, String contentOverride, int artOffsetMs,
                                boolean preActivateProc, boolean autoFillBasic, java.util.Set<Integer> skillsOff,
                                boolean fillSkills, boolean optimize) {
        return buildTimeline(stats, mode, casts, chapter, target, simMs, artifacts, potentials, enemies, kills,
                contentOverride, artOffsetMs, preActivateProc, autoFillBasic, skillsOff, fillSkills, optimize,
                java.util.List.of(), java.util.List.of());
    }

    /**
     * @param compSpdWindows  동료→플레이어 시간창 공속 {start,end,pct} — 스케줄 공격빈도에 반영(2패스, 없으면 비움)
     * @param compBuffSources 동료→플레이어 버프 소스 — 파생/순환/육각 유물 계산 전에 슬롯 반영(없으면 비움)
     */
    TimelineBuild buildTimeline(Map<String, Double> stats, String mode,
                                List<Map<String, Object>> casts, String chapter, String target, int simMs,
                                List<Map<String, Object>> artifacts, List<Map<String, Object>> potentials,
                                Integer enemies, Double kills, String contentOverride, int artOffsetMs,
                                boolean preActivateProc, boolean autoFillBasic, java.util.Set<Integer> skillsOff,
                                boolean fillSkills, boolean optimize,
                                List<double[]> compSpdWindows, List<BuffSource> compBuffSources) {
        boolean boss = isBossTarget(target);
        Double def = targetDef(chapter, target);
        double monDef = def == null ? 0 : def;
        if (simMs <= 0) simMs = (int) ((boss ? BOSS_SECONDS : NORMAL_SECONDS) * 1000);

        stats = withPotential(stats, potentials);   // 유물 잠재를 큐브와 동일 규칙(방관/공속 곱, 그 외 합)으로 프리셋에 합성
        // 유물 조립을 스케줄러보다 먼저 — 상시 공속(헬레나)을 프리셋 공속에 합산해 공격 빈도(→onAttack 유물·전체 DPS)에 반영
        List<ArtifactAssembler.Equip> equips = toTimelineEquips(artifacts);
        String artContent = (contentOverride != null && !contentOverride.isEmpty())
                ? contentOverride : (boss ? "챕터보스" : "챕터사냥");
        // 벞지(유저 프리셋 버프지속%) — 유물 시간창 버프에도 적용(무녀구슬 자체분은 assemble이 사전합산). 디버프·상시·순환 제외.
        double userBuffDur = stats.getOrDefault("BUFF_DURATION", 0.0);
        ArtifactAssembler.Ctx artCtx = new ArtifactAssembler.Ctx(boss, artContent, enemies, kills, preActivateProc, artOffsetMs, userBuffDur);
        ArtifactAssembler asm = new ArtifactAssembler(nlArtBook());
        ArtifactAssembler.Result artRes = asm.assemble(equips, artCtx, simMs);
        if (artRes.artSpeed() != 0 || artRes.artCdrPct() != 0 || artRes.artBuffDurPct() != 0 || artRes.artSummonDurPct() != 0) {
            stats = new LinkedHashMap<>(stats);
            // ★공속·쿨감은 고유 산식(체감)으로 합성 — 유저 입력(유물 미포함) base에 유물 상시분을 체감 합성. 선형합 금지.
            if (artRes.artSpeed() != 0) stats.put("ATK_SPEED", Stat.ATK_SPEED.combine(stats.getOrDefault("ATK_SPEED", 0.0), artRes.artSpeed()));                          // 헬레나 등 공속→공격빈도
            if (artRes.artCdrPct() != 0) stats.put("COOLDOWN_REDUCTION_PCT", Stat.COOLDOWN_REDUCTION_PCT.combine(stats.getOrDefault("COOLDOWN_REDUCTION_PCT", 0.0), artRes.artCdrPct())); // 영혼계약서 쿨감→발동빈도
            if (artRes.artBuffDurPct() != 0) stats.merge("BUFF_DURATION", artRes.artBuffDurPct(), Double::sum);  // 무녀구슬 버프지속
            if (artRes.artSummonDurPct() != 0) stats.merge("SUMMON_DURATION", artRes.artSummonDurPct(), Double::sum); // 유리구두 동료소환지속(동료엔진서 반영)
        }
        Preset preset = new Preset(stats);
        SkillBook book = nlBook();
        Scheduler sched = new Scheduler(book, preset, skillsOff == null ? java.util.Set.of() : skillsOff);
        sched.setOptimize("manual".equals(mode) ? optimize : true);   // 자동=항상 최적화, 수동=토글
        for (double[] w : compSpdWindows) sched.addExternalSpeedWindow((int) w[0], (int) w[1], w[2]);   // 동료 소환창 공속 → 공격빈도

        Schedule schedule;
        if ("manual".equals(mode) && casts != null) {
            List<Scheduler.ManualCast> mc = new ArrayList<>();
            for (Map<String, Object> c : casts) {
                int t = (int) Math.round(numOf(c.get("timeMs")));
                int idx = (int) numOf(c.get("skillIdx"));
                if (idx > 0) mc.add(new Scheduler.ManualCast(t, idx));
            }
            schedule = sched.placeManual(mc, simMs, autoFillBasic, fillSkills);
        } else {
            schedule = sched.placeAuto(simMs);
        }

        DealExpander de = new DealExpander(book, preset);
        Timeline tl = de.expand(schedule, simMs);
        // 아드(스케줄러 onActiveSkill) + 베놈(DealExpander onAttack) 윈도우 병합 → BuffAccumulator(약화 게이트)
        java.util.Map<Integer, java.util.List<int[]>> combinedWin = new java.util.HashMap<>(schedule.eventWindows());
        combinedWin.putAll(de.onAttackWindows());
        Schedule schedule2 = new Schedule(schedule.actions(), combinedWin);
        BuffAccumulator ba = new BuffAccumulator(book, preset);
        List<BuffSource> sources = ba.buildSources(schedule2, simMs);
        ba.accumulate(tl, schedule2, simMs);

        // 위에서 미리 assemble한 유물 결과를 슬롯에 반영(상시/시간창·성배 발동창).
        asm.accumulate(tl, artRes, simMs);
        // ★동료→플레이어 버프를 순환·파생·육각 계산 '전에' 슬롯에 반영 — 동료 버프가 파생 유물 입력(크확·공속)·데미지에 포함되도록.
        mekicalc.timeline.SupporterEngine.accumulateBuffSources(tl, compBuffSources, simMs);
        // ★WindowSet 값-윈도우: accumulateCycle '전에' 방출(cycleWindows가 base+동료 크확을 읽어 순환 발동을 동일 재현).
        //   base 입력(스킬+유물+동료)에 순환/육각/약화/파생 윈도우를 더해, 임의 시각 ws.at(t)가 딜타임 slot.mods()와 일치.
        List<BuffSource> wsBase = new ArrayList<>(sources);
        wsBase.addAll(artRes.sources());
        wsBase.addAll(compBuffSources);
        List<BuffSource> wsWindows = new ArrayList<>(wsBase);
        wsWindows.addAll(asm.valueWindows(tl, preset, artRes, artOffsetMs, simMs, de.attackInstances(), preActivateProc, wsBase));
        mekicalc.timeline.WindowSet ws = new mekicalc.timeline.WindowSet(wsWindows, simMs);
        List<Map<String, Object>> cycleRows = asm.accumulateCycle(tl, preset, artRes, simMs);   // 순환(크확 파티션) — base 뒤·파생 앞(순환 크확이 파생 입력에 반영). 창별 실제효과 행 반환(간트 툴팁)
        List<BuffSource> allSrc = new ArrayList<>(sources);   // 스킬+유물 소스 = 파생 입력(크확·공속) 경계의 원천
        allSrc.addAll(artRes.sources());
        allSrc.addAll(compBuffSources);   // 동료 버프도 파생 입력 경계(표시 세그먼트) 원천
        List<Map<String, Object>> derivedRows = asm.accumulateDerived(tl, preset, artRes, simMs, allSrc);   // derived 표시행 — 입력 버프의 실제 경계(정확한 시간) 기준
        asm.accumulateDmgMult(tl, preset, artRes, boss, artOffsetMs);   // 육각(DMG 곱중첩) — 스택(사전활성 offset 반영)
        asm.accumulateUptime(tl, de.attackInstances(), preActivateProc, artRes);   // 은펜던트·천도명약(onAttack 약화 확률누산, 베놈과 동일 스트림)
        // 성배(onKill)는 이제 assemble에서 확률누산 발동 창을 sources로 만들어 accumulate에서 반영됨
        List<ArtifactAssembler.StackSeg> stackSegs = asm.stackSegments(artRes, boss, artOffsetMs, de.attackInstances(), preActivateProc, simMs);

        return new TimelineBuild(tl, preset, monDef, boss, simMs, sched, schedule, de, book,
                artRes, sources, cycleRows, derivedRows, stackSegs, ws);
    }

    /**
     * 나이트로드 정밀 타임라인 엔진 실행(스킬 전용, 유물·동료 미조립). 자동/수동 배치를 시뮬해
     * 종합 DPS·스킬별 기여·액션 시퀀스·버프 윈도우(간트 시각화용)를 반환한다.
     *
     * @param mode    "manual"이면 casts대로 배치, 그 외 자동 로테이션
     * @param casts   수동 배치 [{timeMs, skillIdx}] (자동이면 무시)
     * @param simMs   시뮬 길이(ms). 0이면 일반몹 90초/보스 70초 기본.
     */
    public Map<String, Object> nlTimeline(Map<String, Double> stats, String mode,
                                          List<Map<String, Object>> casts, String chapter, String target, int simMs,
                                          List<Map<String, Object>> artifacts, List<Map<String, Object>> potentials,
                                          Integer enemies, Double kills, String contentOverride, int artOffsetMs,
                                          boolean preActivateProc, boolean autoFillBasic, java.util.Set<Integer> skillsOff,
                                          boolean fillSkills, boolean optimize,
                                          String companionCode, int companionLevel, int firstSummonMs, boolean companionInstantFinalDmg) {
        // ── 동료(v3.1): 스냅샷용 1차 빌드 → 동료 확정 → 동료 공속(스케줄)·버프(파생/순환/육각 입력)를 반영해 2차 재조립 ──
        TimelineBuild tb = buildTimeline(stats, mode, casts, chapter, target, simMs, artifacts, potentials,
                enemies, kills, contentOverride, artOffsetMs, preActivateProc, autoFillBasic, skillsOff, fillSkills, optimize);
        simMs = tb.simMs();   // 기본(0)→실제 시뮬 길이 확정(동료·2차에 동일 적용)

        mekicalc.timeline.SupporterEngine.CompanionResult comp = null;
        if (supporterAvailable(companionCode)) {   // 미검증 동료 코드는 무시(비숍만 계산)
            // ★계승 스냅샷·적 디버프는 1차 빌드 WindowSet에서 '정확한 시각' fold로 읽음(유물 직접누적 육각/파생/순환/약화 + 경계 정확).
            comp = mekicalc.timeline.SupporterEngine.run(tb.preset(), tb.ws(), companionCode,
                    companionLevel <= 0 ? 1 : companionLevel, Math.max(0, firstSummonMs), simMs, tb.boss(), tb.monDef(), supBook(), companionInstantFinalDmg);
            // 동료→플레이어 버프 소스(파생 입력·데미지) + 공속 시간창(스케줄 공격빈도)
            List<BuffSource> compBuffs = new ArrayList<>();
            List<double[]> compSpd = new ArrayList<>();
            for (mekicalc.timeline.SupporterTimeline.PlayerBuff pb : comp.playerBuffInjections()) {
                if ("companion".equals(pb.target())) continue;   // 동료 자기버프(표시용)는 플레이어에 주입 안 함
                compBuffs.add(mekicalc.timeline.SupporterEngine.toBuffSource(pb));
                if ("AttackSpeed".equals(pb.stat()) && !pb.toEnemy())
                    compSpd.add(new double[]{pb.startMs(), pb.endMs(), pb.value() / 10.0});
            }
            tb = buildTimeline(stats, mode, casts, chapter, target, simMs, artifacts, potentials,
                    enemies, kills, contentOverride, artOffsetMs, preActivateProc, autoFillBasic, skillsOff, fillSkills, optimize,
                    compSpd, compBuffs);   // 2차: 동료 반영본
        }

        Timeline tl = tb.tl();
        Preset preset = tb.preset();
        double monDef = tb.monDef();
        boolean boss = tb.boss();
        simMs = tb.simMs();
        Scheduler sched = tb.sched();
        Schedule schedule = tb.schedule();
        DealExpander de = tb.de();
        SkillBook book = tb.book();
        ArtifactAssembler.Result artRes = tb.artRes();
        List<BuffSource> sources = tb.sources();
        List<Map<String, Object>> cycleRows = tb.cycleRows();
        List<Map<String, Object>> derivedRows = tb.derivedRows();
        List<ArtifactAssembler.StackSeg> stackSegs = tb.stackSegs();

        Analyzer an = new Analyzer(tl, preset, monDef, boss, simMs);

        double total = an.totalDamage();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "manual".equals(mode) ? "manual" : "auto");
        out.put("simMs", simMs);
        out.put("boss", boss);
        out.put("monsterDef", monDef);
        // 대상 표시 정보: 일반몹/보스의 방어력·체력을 각각 노출. 사냥=일반몹만, 보스=보스만, 도전=웨이브 일반몹+스테이지 보스 둘 다.
        boolean challenge = "챕터도전".equals(contentOverride);
        out.put("challenge", challenge);   // 프론트 라벨('챕터 도전')용 — 스테이지 보스 유무와 무관하게 확정
        if (boss) {
            if (monDef > 0) out.put("bossDef", monDef);
            Double bh = targetHp(chapter, "boss"); if (bh != null) out.put("bossHp", bh);
        } else {
            if (monDef > 0) out.put("mobDef", monDef);
            Double mh = challenge ? challengeStat(chapter, target, "mob", "hp") : targetHp(chapter, target);
            if (mh != null) out.put("mobHp", mh);
            if (challenge) {   // 챕터 도전 = 웨이브 방어 후 '그 스테이지 고유 보스'(챕터 최종보스 아님)
                Double bd = challengeStat(chapter, target, "boss", "def"); if (bd != null) out.put("bossDef", bd);
                Double bh = challengeStat(chapter, target, "boss", "hp"); if (bh != null) out.put("bossHp", bh);
            }
        }
        out.put("dps", safe(an.dps()));
        out.put("total", safe(total));
        out.put("cooldowns", sched.effectiveCooldowns());   // 실효 쿨(ms) — 프론트 배치 자동시각용
        out.put("baseSlotMs", sched.baseSlotMs());           // 실효 공속 슬롯(ms, 유물 반영)
        // 액션 기준 순간 DPS 점(스킬·평타 시각마다). 프론트가 액션 수 기준으로 집계(뭉뚱그림)·단조곡선 렌더.
        java.util.TreeSet<Integer> atSet = new java.util.TreeSet<>();
        for (Action a : schedule.actions()) if (a.timeMs() >= 0 && a.timeMs() < simMs) atSet.add(a.timeMs());
        int[] actionTimes = new int[atSet.size()];
        { int ii = 0; for (int t : atSet) actionTimes[ii++] = t; }
        double[] adps = an.actionIntervalDps(actionTimes);
        List<Object> dpsPoints = new ArrayList<>(actionTimes.length);
        for (int i = 0; i < actionTimes.length; i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", actionTimes[i]);
            p.put("dps", safe(adps[i]));
            dpsPoints.add(p);
        }
        out.put("dpsPoints", dpsPoints);

        List<Map<String, Object>> perSkill = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : an.perSkillDamage().entrySet()) {
            int idx = e.getKey();
            long hits = an.skillHits(idx);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("idx", idx);
            row.put("name", book.skill(idx) == null ? String.valueOf(idx) : book.skill(idx).name());
            row.put("damage", safe(e.getValue()));
            row.put("pct", safe(total > 0 ? e.getValue() / total * 100 : 0));
            row.put("hits", hits);
            row.put("per1", safe(hits > 0 ? e.getValue() / hits : 0));
            row.put("casts", an.skillCasts(idx));   // 시전 횟수(도트 틱 제외)
            perSkill.add(row);
        }
        out.put("perSkill", perSkill);

        List<Map<String, Object>> acts = new ArrayList<>();
        for (Action a : schedule.actions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", a.timeMs());
            m.put("basic", a.basic());
            m.put("idx", a.skill().idx());
            m.put("name", a.basic() ? "평타" : a.skill().name());
            m.put("dmg", safe(an.skillDamageAt(a.skill().idx(), a.timeMs())));   // 그 순간 데미지(툴팁)
            int dur = dotDurationMs(a.skill());   // 도트/지속딜이면 간트에 바로 표시
            if (dur > 0) m.put("durMs", dur);
            acts.add(m);
        }
        out.put("actions", acts);

        // 딜 라인: 스킬별 실제 딜 시각(진한 틱) + 도트/지속/주기면 활성 구간(옅은 막대).
        // 라인 대상 = 즉발 외 딜(도트/주기/프록)이 있는 스킬. 그 경우 즉발(첫 타격, 캐스트 시각)도 틱에 포함(strong)해 전체 타격을 표시.
        Map<Integer, java.util.Set<Integer>> castTimes = new LinkedHashMap<>();
        int basicIdx = -1;
        for (Action a : schedule.actions()) {
            if (a.basic()) { if (basicIdx < 0) basicIdx = a.skill().idx(); }
            else castTimes.computeIfAbsent(a.skill().idx(), k -> new java.util.HashSet<>()).add(a.timeMs());
        }
        Map<Integer, Double> perSkillDmg = an.perSkillDamage();   // idx → 총딜(정렬용)
        final int fBasicIdx = basicIdx;
        List<Map<String, Object>> dealLines = new ArrayList<>();
        for (int idx : tl.dealingSkills()) {
            if (idx == fBasicIdx) continue;   // 평타(=쇼다운 챌린지)는 액션바에 있으니 딜 라인 생략(중복 방지)
            SkillIR sk = book.skill(idx);
            if (sk == null) continue;
            java.util.Set<Integer> castSet = castTimes.getOrDefault(idx, java.util.Set.of());
            List<Integer> hits = new ArrayList<>(), overTime = new ArrayList<>();
            for (int t : new java.util.TreeSet<>(tl.timesOf(idx))) {
                if (t < 0 || t >= simMs) continue;
                hits.add(t);
                if (!castSet.contains(t)) overTime.add(t);   // 즉발(캐스트) 외 딜 = 도트/주기/프록
            }
            if (overTime.isEmpty()) continue;   // 즉발만 → 라인 불필요(액션바로 충분)
            // 지속 막대 = DealExpander가 기록한 실제 지속 윈도우[발동, 발동+지속](마스터리 반영).
            // ★틱 시각과 연동하지 않는다 — duration은 별개 개념이라 '마지막 타격 후 유지되는 무-딜 구간'도 막대에 포함한다.
            List<Map<String, Object>> wins = new ArrayList<>();
            List<int[]> dw = de.dotWindows().get(idx);
            if (dw != null) for (int[] w : dw) wins.add(win(w[0], w[1], simMs));
            List<Map<String, Object>> tickObjs = new ArrayList<>(hits.size());
            for (int t : hits) {   // 모든 딜 시각(즉발 첫타격 포함)
                Map<String, Object> tk = new LinkedHashMap<>();
                tk.put("t", t);
                tk.put("dmg", safe(an.skillDamageAt(idx, t)));   // 그 틱 순간 데미지(툴팁)
                if (castSet.contains(t)) tk.put("strong", true);  // 즉발(캐스트) = 강한 첫 타격 → 굵게
                tickObjs.add(tk);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", sk.name());
            row.put("ticks", tickObjs);
            row.put("windows", wins);
            row.put("_dmg", perSkillDmg.getOrDefault(idx, 0.0));
            dealLines.add(row);
        }
        dealLines.sort((x, y) -> Double.compare((Double) y.getOrDefault("_dmg", 0.0), (Double) x.getOrDefault("_dmg", 0.0)));   // 딜 지분(총딜) 내림차순
        for (Map<String, Object> r : dealLines) r.remove("_dmg");
        out.put("dealLines", dealLines);

        List<Map<String, Object>> buffs = new ArrayList<>();
        for (BuffSource bs : sources) buffs.add(buffRow(bs, simMs, false));
        for (BuffSource bs : artRes.sources()) buffs.add(buffRow(bs, simMs, true));
        for (BuffSource bs : artRes.speedDisplay()) buffs.add(buffRow(bs, simMs, true));   // 유물 상시공속 표시(딜엔 preset으로 반영, 여기선 간트 바만)
        buffs.addAll(derivedRows);   // 파생(라이브 크확·공속 후처리) 대표 효과 행
        buffs.addAll(cycleRows);     // 순환(크확 파티션) 창별 표시행 — 각 창의 실제 적용 효과(effects) 포함
        // 스택 세그먼트의 실효 효과값(스택 반영). 라벨→원본 스펙 매칭. 약화=value(‰)×스택, 육각=preset.dmg×value(‰)×스택.
        Map<String, ArtifactAssembler.UptimeSpec> upByLabel = new LinkedHashMap<>();
        for (ArtifactAssembler.UptimeSpec u : artRes.uptimes()) upByLabel.put(u.label(), u);
        Map<String, ArtifactAssembler.DmgMultSpec> dmByLabel = new LinkedHashMap<>();
        for (ArtifactAssembler.DmgMultSpec dm : artRes.dmgMults()) dmByLabel.put(dm.label(), dm);
        for (ArtifactAssembler.StackSeg sg : stackSegs) {            // 육각·은펜던트·천도명약 시각별 스택 세그먼트
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", sg.label());
            m.put("start", sg.start());
            m.put("end", sg.end());
            m.put("always", sg.start() == 0 && sg.end() >= simMs);
            m.put("artifact", true);
            m.put("inst", sg.inst());
            m.put("stacks", sg.stacks());
            ArtifactAssembler.UptimeSpec u = upByLabel.get(sg.label());
            ArtifactAssembler.DmgMultSpec dm = dmByLabel.get(sg.label());
            if (u != null && "Weakness".equals(u.stat()))               // 약화 스택 = 적 받는 피해 증가
                m.put("effects", List.of(statEff("WEAKNESS", u.value() / 10.0 * sg.stacks())));
            else if (dm != null)                                        // 육각 = 데미지 곱중첩 실효 %p
                m.put("effects", List.of(statEff("DMG", preset.dmg() * dm.value() / 1000.0 * sg.stacks())));
            buffs.add(m);
        }
        out.put("buffs", buffs);

        // 유물 스킵 진단(적용 안 된 효과 + 사유). 검증·UI 표시용.
        List<Map<String, Object>> artSkips = new ArrayList<>();
        for (ArtifactAssembler.Skip sk : artRes.skips()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("idx", sk.idx());
            m.put("name", sk.name());
            m.put("effIdx", sk.effIdx());
            m.put("reason", sk.reason());
            artSkips.add(m);
        }
        out.put("artifactSkips", artSkips);

        // ── 동료 출력(스킬별 딜 + 종합) ──
        if (comp != null) {
            mekicalc.timeline.ir.SupporterIR sup = supBook().supporter(companionCode);
            // 표시 이름 = 타입 기반(평타·액티브N·패시브N·소환). 동료 스킬은 인게임 표시명이 없어 임의명 대신 유형으로.
            Map<Integer, String> dispName = new LinkedHashMap<>();
            if (sup != null) {
                int na = 0, np = 0;
                for (var sk : sup.skills()) {
                    String nm;
                    if (sk.isBasicAttack()) nm = "평타";
                    else if (sk.isEnter()) nm = "소환";
                    else if (sk.isActive()) nm = "액티브 " + (++na);
                    else nm = "패시브 " + (++np);   // onStart/periodic/onAttack
                    dispName.put(sk.idx(), nm);
                }
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", companionCode);
            c.put("name", sup == null ? companionCode : sup.name());
            c.put("level", companionLevel <= 0 ? 1 : companionLevel);
            c.put("summonCount", comp.summonCount());
            c.put("dps", safe(comp.dps()));
            c.put("total", safe(comp.totalDamage()));
            List<Map<String, Object>> cps = new ArrayList<>();
            double cTotal = comp.totalDamage();
            for (Map.Entry<Integer, Double> e : comp.perSkillDamage().entrySet()) {
                long hits = comp.perSkillHits().getOrDefault(e.getKey(), 0L);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("idx", e.getKey());
                row.put("name", dispName.getOrDefault(e.getKey(), String.valueOf(e.getKey())));
                row.put("damage", safe(e.getValue()));
                row.put("dps", safe(simMs > 0 ? e.getValue() / (simMs / 1000.0) : 0));
                row.put("pct", safe(cTotal > 0 ? e.getValue() / cTotal * 100 : 0));
                row.put("hits", hits);
                row.put("per1", safe(hits > 0 ? e.getValue() / hits : 0));       // 한줄 데미지
                row.put("casts", comp.perSkillCasts().getOrDefault(e.getKey(), 0L));   // 시전 횟수
                cps.add(row);
            }
            cps.sort((x, y) -> Double.compare((Double) y.get("damage"), (Double) x.get("damage")));
            c.put("perSkill", cps);

            // 간트: 소환창 [start,end]
            List<Map<String, Object>> summons = new ArrayList<>();
            for (int[] w : comp.summonWindows()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("start", w[0]); m.put("end", w[1]);
                summons.add(m);
            }
            c.put("summons", summons);

            // 간트: 동료 스킬별 딜 틱(절대 시각)
            List<Map<String, Object>> cLines = new ArrayList<>();
            for (Map.Entry<Integer, List<double[]>> e : comp.dealTicks().entrySet()) {
                String nm = dispName.getOrDefault(e.getKey(), String.valueOf(e.getKey()));
                List<Map<String, Object>> ticks = new ArrayList<>();
                double sum = 0;
                for (double[] tk : e.getValue()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("t", (int) tk[0]); t.put("dmg", safe(tk[1]));
                    ticks.add(t); sum += tk[1];
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("idx", e.getKey()); row.put("name", nm); row.put("ticks", ticks); row.put("_dmg", sum);
                cLines.add(row);
            }
            cLines.sort((x, y) -> Double.compare((Double) y.get("_dmg"), (Double) x.get("_dmg")));
            for (Map<String, Object> r : cLines) r.remove("_dmg");
            c.put("dealLines", cLines);

            // 간트: 동료→플레이어 버프 창(스탯별)
            List<Map<String, Object>> cBuffs = new ArrayList<>();
            for (mekicalc.timeline.SupporterTimeline.PlayerBuff b : comp.playerBuffInjections()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("skillIdx", b.skillIdx());
                m.put("skillName", dispName.getOrDefault(b.skillIdx(), b.skillName()));
                m.put("stat", b.stat());
                m.put("value", b.value());
                m.put("start", b.startMs());
                m.put("end", b.endMs());
                m.put("toEnemy", b.toEnemy());
                m.put("target", b.target());   // player/both/companion/enemy → 프론트 라벨(플레이어/동료/플레이어·동료 적용)
                cBuffs.add(m);
            }
            c.put("buffs", cBuffs);

            out.put("companion", c);
            out.put("dpsWithCompanion", safe(an.dps() + comp.dps()));       // 플레이어(동료버프 반영)+동료
            out.put("totalWithCompanion", safe(total + comp.totalDamage()));
        }

        // ── 유물별 기여도: 각 유물을 하나씩 빼고 전체 딜(플레이어+동료)을 재계산 → 감소분 = 그 유물 기여 ──
        // ★단순·정확 우선(유저 확정): '하나 빼기'라 유물 수만큼 재sim(느릴 수 있음). 유물의 잠재도 함께 제외.
        if (artifacts != null && !artifacts.isEmpty()) {
            double baseWhole = total + (comp != null ? comp.totalDamage() : 0.0);
            List<Map<String, Object>> artContrib = new ArrayList<>();
            for (int i = 0; i < artifacts.size(); i++) {
                Object aidObj = artifacts.get(i).get("id");
                String aid = aidObj == null ? null : aidObj.toString();
                List<Map<String, Object>> less = new ArrayList<>(artifacts);
                less.remove(i);
                List<Map<String, Object>> lessPots = new ArrayList<>();   // 이 유물의 잠재 줄도 제외(같은 id 태그)
                if (potentials != null) for (Map<String, Object> p : potentials) {
                    Object pid = p.get("id");
                    if (aid != null && pid != null && aid.equals(pid.toString())) continue;
                    lessPots.add(p);
                }
                double without = timelineWholeTotal(stats, mode, casts, chapter, target, simMs, less, lessPots,
                        enemies, kills, contentOverride, artOffsetMs, preActivateProc, autoFillBasic, skillsOff, fillSkills, optimize,
                        companionCode, companionLevel, firstSummonMs, companionInstantFinalDmg);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", aid);
                row.put("damage", safe(Math.max(0, baseWhole - without)));   // 기여 = 전체 딜 감소분(음수=0)
                artContrib.add(row);
            }
            artContrib.sort((x, y) -> Double.compare((Double) y.get("damage"), (Double) x.get("damage")));
            out.put("artifactContrib", artContrib);
        }
        return out;
    }

    /**
     * 유물별 기여도용 — 주어진 유물/잠재로 전체 딜(플레이어+동료)만 계산(표시 데이터 없이).
     * nlTimeline의 딜 산출부와 동일 경로(동료 있으면 실험실과 같은 2패스: 스냅샷→run→동료버프 반영).
     */
    private double timelineWholeTotal(Map<String, Double> stats, String mode, List<Map<String, Object>> casts,
                                      String chapter, String target, int simMs,
                                      List<Map<String, Object>> artifacts, List<Map<String, Object>> potentials,
                                      Integer enemies, Double kills, String contentOverride, int artOffsetMs,
                                      boolean preActivateProc, boolean autoFillBasic, java.util.Set<Integer> skillsOff,
                                      boolean fillSkills, boolean optimize,
                                      String companionCode, int companionLevel, int firstSummonMs, boolean companionInstantFinalDmg) {
        TimelineBuild tb = buildTimeline(stats, mode, casts, chapter, target, simMs, artifacts, potentials,
                enemies, kills, contentOverride, artOffsetMs, preActivateProc, autoFillBasic, skillsOff, fillSkills, optimize);
        int sMs = tb.simMs();
        double compTotal = 0;
        if (supporterAvailable(companionCode)) {
            mekicalc.timeline.SupporterEngine.CompanionResult comp = mekicalc.timeline.SupporterEngine.run(
                    tb.preset(), tb.ws(), companionCode, companionLevel <= 0 ? 1 : companionLevel,
                    Math.max(0, firstSummonMs), sMs, tb.boss(), tb.monDef(), supBook(), companionInstantFinalDmg);
            List<BuffSource> compBuffs = new ArrayList<>();
            List<double[]> compSpd = new ArrayList<>();
            for (mekicalc.timeline.SupporterTimeline.PlayerBuff pb : comp.playerBuffInjections()) {
                if ("companion".equals(pb.target())) continue;
                compBuffs.add(mekicalc.timeline.SupporterEngine.toBuffSource(pb));
                if ("AttackSpeed".equals(pb.stat()) && !pb.toEnemy())
                    compSpd.add(new double[]{pb.startMs(), pb.endMs(), pb.value() / 10.0});
            }
            tb = buildTimeline(stats, mode, casts, chapter, target, sMs, artifacts, potentials, enemies, kills,
                    contentOverride, artOffsetMs, preActivateProc, autoFillBasic, skillsOff, fillSkills, optimize, compSpd, compBuffs);
            compTotal = comp.totalDamage();
        }
        Analyzer an = new Analyzer(tb.tl(), tb.preset(), tb.monDef(), tb.boss(), sMs);
        return an.totalDamage() + compTotal;
    }

    /** BuffSource → 간트 표시행. artifact=유물 소스 구분. effects=이 소스가 주는 효과+값(툴팁). */
    private static Map<String, Object> buffRow(BuffSource bs, int simMs, boolean artifact) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", bs.label());
        m.put("start", bs.startMs());
        m.put("end", bs.endMs());
        m.put("always", bs.startMs() == 0 && bs.endMs() >= simMs);   // 상시(숙련·페이크) 표시 구분용
        m.put("artifact", artifact);
        if (bs.inst() >= 0) m.put("inst", bs.inst());   // 유물 인스턴스 인덱스(프론트 그룹핑·오염방지 키)
        if (bs.dormant()) m.put("dormant", true);       // 무실효(대상 불일치·마리수 미충족) — 흐리게 표시
        List<Map<String, Object>> effs = new ArrayList<>();
        for (var e : bs.adds().entrySet()) if (e.getValue() != 0) effs.add(statEff(e.getKey().name(), e.getValue()));
        if (bs.finalDmgFrac() != 0) effs.add(statEff("FINAL_DMG", bs.finalDmgFrac() * 100.0));   // 분율→표시%
        if (!effs.isEmpty()) m.put("effects", effs);
        return m;
    }

    /** {stat(영문키), value(소수1자리)} 효과 표시 항목. 프론트가 한글명으로 변환. */
    private static Map<String, Object> statEff(String stat, double value) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("stat", stat);
        e.put("value", Math.round(value * 10) / 10.0);
        return e;
    }

    /**
     * 요청 유물 배열 [{id, awaken}] → 타임라인 조립기 Equip 목록.
     * id는 카탈로그 슬러그("deadmans_charm") 또는 숫자 idx("301") 둘 다 허용(이름 조인으로 해소). 매칭 실패는 스킵.
     */
    private List<ArtifactAssembler.Equip> toTimelineEquips(List<Map<String, Object>> artifacts) {
        List<ArtifactAssembler.Equip> out = new ArrayList<>();
        if (artifacts == null) return out;
        Map<String, Integer> slug = nlArtSlugToIdx();
        for (Map<String, Object> e : artifacts) {
            Object id = e.get("id");
            if (id == null) continue;
            String s = id.toString().trim();
            Integer idx = slug.get(s);
            if (idx == null) {
                try { idx = (int) Double.parseDouble(s); } catch (NumberFormatException ignore) { continue; }   // "402"·"402.0" 모두 허용
            }
            out.add(new ArtifactAssembler.Equip(idx, (int) numOf(e.get("awaken"))));
        }
        return out;
    }

    /**
     * 유물 잠재를 프리셋 스탯에 합성(큐브와 동일 경로). 잠재 옵션 → 딜 스탯 델타 → {@code combine}
     * (방관/공속 체감 곱연산·주스탯% 전파는 큐브가 쓰는 규칙 그대로). 잠재 없으면 원본 그대로.
     */
    private Map<String, Double> withPotential(Map<String, Double> stats, List<Map<String, Object>> potentials) {
        List<Map<String, Object>> opts = potentialStatOpts(potentials);
        if (opts.isEmpty()) return stats;
        DamageModel dm = content.damageModel(Map.of());
        StatBlock cur = new StatBlock();
        stats.forEach(cur::set);
        return dm.combine(cur, deltaOfReq(dm, opts)).asMap();
    }

    /** 잠재 옵션 [{type, grade, value?}] → 딜 스탯 옵션 [{statId, value(표시%)}]. 생존/CC중뎀·미상 타입은 제외. */
    private List<Map<String, Object>> potentialStatOpts(List<Map<String, Object>> potentials) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (potentials == null || potentials.isEmpty()) return out;
        Map<String, Map<String, Object>> byType = potentialOptionsByType();
        for (Map<String, Object> p : potentials) {
            Object type = p.get("type");
            if (type == null) continue;
            Map<String, Object> opt = byType.get(type.toString());
            if (opt == null || !Boolean.TRUE.equals(opt.get("deal")) || opt.get("statId") == null) continue;
            Double permille = resolvePotentialValue(p, opt);   // 천분율
            if (permille == null || permille == 0) continue;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("statId", opt.get("statId").toString());
            o.put("value", permille / 10.0);   // 천분율 → 표시%(combine/deltaOfReq 단위)
            out.add(o);
        }
        return out;
    }

    /** 잠재 1줄의 값(천분율): value(미스틱 롤) 우선, 없으면 등급 기본값(배열이면 최저 롤). */
    private static Double resolvePotentialValue(Map<String, Object> p, Map<String, Object> opt) {
        if (p.get("value") instanceof Number n) return n.doubleValue();
        Object grade = p.get("grade");
        if (grade == null || !(opt.get("values") instanceof Map)) return null;
        Object gv = ((Map<?, ?>) opt.get("values")).get(grade.toString());
        if (gv instanceof Number n) return n.doubleValue();
        if (gv instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Number n) return n.doubleValue();
        return null;
    }

    /** 잠재 참조(artifact_potential.json)의 options를 type→옵션 맵으로. */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> potentialOptionsByType() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        Object opts = content.artifactPotential == null ? null : content.artifactPotential.get("options");
        if (opts instanceof List) for (Object o : (List<Object>) opts) {
            if (o instanceof Map<?, ?> opt && opt.get("type") != null)
                m.put(opt.get("type").toString(), (Map<String, Object>) opt);
        }
        return m;
    }

    /** 카탈로그(슬러그 id + 이름) ↔ 유물 IR(idx + 이름) 이름 조인 맵. 신규 유물(카탈로그 미등재)은 조인 제외. */
    private Map<String, Integer> nlArtSlugToIdx() {
        Map<String, Integer> m = nlArtSlugToIdx;
        if (m == null) {
            synchronized (this) {
                if (nlArtSlugToIdx == null) {
                    Map<String, Integer> nameToIdx = new LinkedHashMap<>();
                    for (var a : nlArtBook().artifacts()) nameToIdx.put(a.name(), a.idx());
                    Map<String, Integer> built = new LinkedHashMap<>();
                    for (Map<String, Object> a : content.artifacts) {
                        Object id = a.get("id"), name = a.get("name");
                        if (id == null || name == null) continue;
                        Integer idx = nameToIdx.get(name.toString());
                        if (idx != null) built.put(id.toString(), idx);
                    }
                    nlArtSlugToIdx = Map.copyOf(built);
                }
                m = nlArtSlugToIdx;
            }
        }
        return m;
    }

    private static double numOf(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }

    /** 딜 라인 활성 구간 1개 [start, min(end,sim)) → {start,end} 맵. */
    private static Map<String, Object> win(int start, int end, int simMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start", start);
        m.put("end", Math.min(end, simMs));
        return m;
    }

    /** 스킬의 deal 도트 효과 틱 간격(ms). 없으면 null(=도트/지속 아님, 순간 프록 등). 딜 라인 막대 그룹핑용. */
    private static Integer dotTickMs(SkillIR s) {
        for (EffectIR e : s.effects()) {
            if (!"deal".equals(e.role())) continue;
            boolean dot = e.isDot() || (e.tickMs() != null && e.durationMs() != null);
            if (dot && e.tickMs() != null && e.tickMs() > 0) return e.tickMs();
        }
        return null;
    }

    /** 스킬의 deal 효과 중 도트/지속딜(tick+duration)의 최대 지속시간(ms). 없으면 0. 간트 지속딜 바 표시용. */
    private static int dotDurationMs(SkillIR s) {
        int max = 0;
        for (EffectIR e : s.effects()) {
            if (!"deal".equals(e.role())) continue;
            boolean dot = e.isDot() || (e.tickMs() != null && e.durationMs() != null);
            if (dot && e.durationMs() != null) max = Math.max(max, e.durationMs());
        }
        return max;
    }

    /** 수동 배치 드롭다운용 나이트로드 시전 가능 스킬 목록(active 트리거·전투기여). idx 오름차순 + 맨 위 평타. */
    public List<Map<String, Object>> nlSkills() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillIR s : nlBook().skills()) {
            if (!"active".equals(s.trigger())) continue;
            boolean combat = s.hasDeal() || s.role().contains("buff") || s.role().contains("debuff");
            if (!combat) continue;   // utility 단독(쉐도우 러쉬) 제외
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("idx", s.idx());
            m.put("name", s.name());
            m.put("requireLevel", s.requireLevel());
            m.put("deal", s.hasDeal());
            out.add(m);
        }
        out.sort((x, y) -> Integer.compare((int) x.get("idx"), (int) y.get("idx")));
        // 수동(평타 자동채움 끔)에서 평타도 직접 배치할 수 있게 대표 평타 1종을 맨 위에 노출.
        // 실제 시전은 스케줄러가 레벨에 맞는 최고차수 평타로 재선정하므로 어떤 basicAttack idx를 보내도 동일.
        SkillIR basic = null;
        for (SkillIR s : nlBook().skills()) {
            if (!"basicAttack".equals(s.trigger())) continue;
            if (basic == null || s.requireLevel() > basic.requireLevel()) basic = s;
        }
        if (basic != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("idx", basic.idx());
            m.put("name", "평타");
            m.put("requireLevel", 0);
            m.put("deal", true);
            m.put("basic", true);
            out.add(0, m);
        }
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
        out.put("potential", content.artifactPotential);   // 잠재 참조(slotRule·grades·options)
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
     * 동료 계승 상수(프론트 자동계산용). 등급+동료레벨(+플레이어레벨 보정)으로 공격력 계승비율을 프론트가 산출한다.
     * @return {inheritByLevel, gradeLevelCap, compareLevel}
     */
    public Map<String, Object> companionMeta() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> root = content.companionRoot;
        for (String k : new String[]{"inheritByLevel", "gradeLevelCap", "compareLevel"}) {
            Object v = root.get(k);
            if (v != null) out.put(k, v);
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
        out.put("playerGift", cd.playerGift(b, boss).get("buffs"));   // 동료가 플레이어에게 주는 버프(공속버프 등, 가동률 평균)
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
                                        String companionId, String companionGrade, int companionLevel, double ratio, CubeCtx ctx) {
        StatBlock current = block(stats);
        boolean boss = isBossTarget(target);
        DealModel dm = cubeDealModel(current, conv, boss, buffs, hitInterval, chapter, target,
                companionId, companionGrade, companionLevel, ratio, ctx);
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
     * 신엔진(timeline) 딜 모델 — 큐브 추천/비교의 output을 종합 DPS(타임라인 총딜)로 준다.
     * baseline 타임라인을 1회 만들어 캐시하고, 상시 스탯 델타는 재sim 없이 base 프리셋만 갈아끼워 Analyzer로 재평가한다.
     * 스케줄에 영향을 주는 스탯(공속·쿨감·벞지·소지·스킬레벨)이 바뀔 때만 타임라인을 다시 만든다.
     * 스탯 합성(combine/uncombine/aggregate)·정성 판정은 구엔진 {@link DamageModel} 규칙을 그대로 위임(큐브 합성 규칙 동일).
     */
    final class TimelineDealModel implements DealModel {
        private static final java.util.Set<String> RESIM_STATS = java.util.Set.of(
                "ATK_SPEED", "COOLDOWN_REDUCTION", "COOLDOWN_REDUCTION_PCT",
                "BUFF_DURATION", "SUMMON_DURATION", "SKILL_LEVELS",
                "SKILL_LEVEL_1", "SKILL_LEVEL_2", "SKILL_LEVEL_3", "SKILL_LEVEL_4");   // 개별 스킬레벨도 계수 변경 → 재sim
        private static final String BASIC_ATK_TARGETS = "BASIC_ATK_TARGETS";

        private final DamageModel dm;
        private final String chapter, target, contentOverride;
        private final boolean boss;
        private final double monDef;
        private final int simMs, artOffsetMs;
        private final List<Map<String, Object>> artifacts, potentials;
        private final Integer enemies;
        private final Double kills;
        private final boolean preActivateProc, autoFillBasic, fillSkills, optimize;
        private final java.util.Set<Integer> skillsOff;
        private final String companionCode; private final int companionLevel, firstSummonMs;   // 동료(빌드의 한 축) — 큐브 딜에도 반영
        private final boolean companionInstantFinalDmg;   // 인스턴트 최종뎀 보너스 체크박스

        // 대표 딜(계수·타수·마스터리=1·1·0) — 그룹 딜 = perHit(BASIC1)×basicW + perHit(SKILL1)×skillW.
        private static final DealEvent BASIC1 = new DealEvent(-1, DmgType.BASIC, 1.0, 1, 0.0, false);
        private static final DealEvent SKILL1 = new DealEvent(-1, DmgType.SKILL, 1.0, 1, 0.0, false);

        private ArtifactAssembler.Result baseArt;  // 유물 조립(스케줄 기여분 + 파생 입력 스탯 판정용)
        private java.util.Set<String> resimStats = RESIM_STATS;  // 재sim 트리거 = 스케줄 스탯 + 유물 파생 입력(크확/DMG 등, baseArt 확정 후 확장)
        private StatBlock curRef; private double curOut;   // 마지막 output(increase의 before 재사용)
        // ★재sim 결과(Sim)=resimStats(스케줄+유물 파생입력) 값에만 의존 → 그 튜플로 전역 캐시. 동료 재sim도 여기 1회(같은 스케줄 재사용).
        private final Map<String, Sim> simBySched = new java.util.HashMap<>();
        private boolean primed;   // 첫 호출로 baseArt·resimStats 확정(sched-tuple 키 확정) 후부터 캐시

        // ── 동료(빌드의 한 축) — 동료는 소환창·계승 스냅샷=★스케줄 스탯(RESIM_STATS)에만 의존(크확·DMG 등 파생 입력엔 스케줄 불변).
        // 그래서 동료 계산은 RESIM_STATS 튜플로 별도 캐시 → 파생 옵션(크확/DMG) 재sim에선 동료 재sim 스킵(닫힌식 반응). ──
        private mekicalc.timeline.ir.SupporterIR compSup; private int compLvl; private boolean compResolved;
        private final Map<String, CompCache> compCache = new java.util.HashMap<>();   // RESIM_STATS 튜플 → 동료 계산(재sim 최소화)
        /** 한 스케줄의 동료 계산 — 플레이어 주입 버프(cb/cs) + 자체딜 대표 그룹·스냅샷·기준값. 非스케줄 델타는 evalGroups에서 inheritedFor로 스케일. */
        record CompCache(List<BuffSource> cb, List<double[]> cs, List<GroupAgg> compGroups, double compBase, double compRefResponse, mekicalc.timeline.ModState snapshot) {}

        /** 같은 버프상태(ModState)를 공유하는 타격들의 집계 — basicW/skillW = Σ 계수×타수×(1+마스터리). */
        record GroupAgg(ModState m, double basicW, double skillW) {}
        /** 한 번 재sim한 결과 — 플레이어 그룹(동료 버프 반영) + 동료 자체딜(그 스케줄 기준: 대표 소환 그룹·스냅샷·총딜). */
        record Sim(List<GroupAgg> player, List<GroupAgg> comp, double compBase, double compRefResponse, mekicalc.timeline.ModState snapshot) {}

        TimelineDealModel(DamageModel dm, String chapter, String target,
                          List<Map<String, Object>> artifacts, List<Map<String, Object>> potentials,
                          Integer enemies, Double kills, String contentOverride, int artOffsetMs,
                          boolean preActivateProc, boolean autoFillBasic, java.util.Set<Integer> skillsOff,
                          boolean fillSkills, boolean optimize, int simMs,
                          String companionCode, int companionLevel, int firstSummonMs, boolean companionInstantFinalDmg) {
            this.dm = dm;
            this.chapter = chapter; this.target = target;
            this.boss = isBossTarget(target);
            Double d = targetDef(chapter, target);
            this.monDef = d == null ? 0 : d;
            this.artifacts = artifacts; this.potentials = potentials;
            this.enemies = enemies; this.kills = kills;
            this.contentOverride = contentOverride; this.artOffsetMs = artOffsetMs;
            this.preActivateProc = preActivateProc; this.autoFillBasic = autoFillBasic;
            this.skillsOff = skillsOff; this.fillSkills = fillSkills; this.optimize = optimize;
            this.simMs = simMs <= 0 ? (int) ((boss ? BOSS_SECONDS : NORMAL_SECONDS) * 1000) : simMs;
            this.companionCode = companionCode; this.companionLevel = companionLevel; this.firstSummonMs = firstSummonMs;
            this.companionInstantFinalDmg = companionInstantFinalDmg;
        }

        @Override public double output(StatBlock b) {
            if (b == curRef) return curOut;
            Map<String, Double> m = b.asMap();
            double o = evalGroups(simFor(m), m);
            curRef = b; curOut = o;
            return o;
        }

        @Override public double increase(StatBlock current, StatBlock delta) {
            double before = output(current);
            if (before <= 0) return 0;
            Map<String, Double> cm = combine(current, delta).asMap();
            return evalGroups(simFor(cm), cm) / before - 1.0;   // 비스케줄 델타=같은 sched-tuple 재사용(닫힌식), 스케줄 델타=새 튜플 1회 재sim
        }

        /** map의 재sim(Sim)을 sched-tuple 캐시에서 — resimStats 값이 같으면 재sim 안 하고 재사용(동료 포함). 비스케줄 차이는 evalGroups에서 프리셋으로. */
        private Sim simFor(Map<String, Double> map) {
            if (!primed) { Sim s = groupsFor(map); primed = true; simBySched.put(schedTuple(map), s); return s; }   // 첫 호출=resimStats 확정
            return simBySched.computeIfAbsent(schedTuple(map), k -> groupsFor(map));
        }

        /** 재sim스탯값 튜플(재sim 캐시 키) = 스케줄 스탯 + 유물 파생 입력. 이 값들이 같으면 Sim(플레이어 그룹·동료) 동일. */
        private String schedTuple(Map<String, Double> m) {
            StringBuilder sb = new StringBuilder();
            for (String s : resimStats) sb.append(s).append('=').append(m.getOrDefault(s, 0.0)).append(',');
            return sb.toString();
        }

        /**
         * 한 스탯셋(map)의 재sim — 플레이어 그룹(동료 버프 반영) + 동료 자체딜 상태(그 스케줄 기준).
         * ★동료는 스케줄 의존(소환창·계승 스냅샷)이라 재sim마다 갱신(직업 부분과 같은 캐시 경로). 실험실과 동일 계산.
         * 비용 = 실험실 nlTimeline과 동일: 1차(스냅샷)→SupporterEngine.run→2차(동료 버프 반영). 재sim은 부위/스케줄 델타에만.
         */
        private Sim groupsFor(Map<String, Double> map) {
            if (supporterAvailable(companionCode)) {
                // 동료 계산은 RESIM_STATS(스케줄) 튜플에만 의존 → 파생(크확/DMG) 옵션 재sim에선 재사용(재sim 스킵). 非스케줄 차이는 inheritedFor 스케일.
                CompCache cc = compCache.computeIfAbsent(compTuple(map), k -> buildCompanion(map));
                // ── 2차: 동료 버프(캐시분) 반영 플레이어(파생 유물·공격 빈도) ──
                TimelineBuild bt2 = buildTimeline(map, "auto", null, chapter, target, simMs,
                        artifacts, potentials, enemies, kills, contentOverride, artOffsetMs,
                        preActivateProc, autoFillBasic, skillsOff, fillSkills, optimize, cc.cs(), cc.cb());
                return new Sim(buildGroups(bt2.tl()), cc.compGroups(), cc.compBase(), cc.compRefResponse(), cc.snapshot());
            }
            // 동료 없음 = 플레이어 단독
            TimelineBuild bt = buildTimeline(map, "auto", null, chapter, target, simMs,
                    artifacts, potentials, enemies, kills, contentOverride, artOffsetMs,
                    preActivateProc, autoFillBasic, skillsOff, fillSkills, optimize);
            if (baseArt == null) { baseArt = bt.artRes(); expandResimStats(); }
            return new Sim(buildGroups(bt.tl()), null, 0, 0, null);
        }

        /** 동료 1회 계산(RESIM_STATS 튜플별) — 스냅샷용 1차 빌드→SupporterEngine.run→주입버프·대표 그룹·기준값. 실험실 nlTimeline과 동일 경로. */
        private CompCache buildCompanion(Map<String, Double> map) {
            TimelineBuild bt1 = buildTimeline(map, "auto", null, chapter, target, simMs,   // 스냅샷용(동료 버프 미주입)
                    artifacts, potentials, enemies, kills, contentOverride, artOffsetMs,
                    preActivateProc, autoFillBasic, skillsOff, fillSkills, optimize);
            if (baseArt == null) { baseArt = bt1.artRes(); expandResimStats(); }
            if (!compResolved) { compSup = supBook().supporter(companionCode); compLvl = companionLevel <= 0 ? 1 : companionLevel; compResolved = true; }
            // ★1차 빌드 WindowSet으로 계승 스냅샷·적 디버프(정확한 시각 fold, 유물 직접누적 + 경계 정확). 동료 버프는 미반영(1차).
            mekicalc.timeline.SupporterEngine.CompanionResult comp = mekicalc.timeline.SupporterEngine.run(
                    bt1.preset(), bt1.ws(), companionCode, compLvl, Math.max(0, firstSummonMs), simMs, boss, monDef, supBook(), companionInstantFinalDmg);
            List<BuffSource> cb = new ArrayList<>();
            List<double[]> cs = new ArrayList<>();
            for (mekicalc.timeline.SupporterTimeline.PlayerBuff pb : comp.playerBuffInjections()) {
                if ("companion".equals(pb.target())) continue;   // 동료 자기버프는 플레이어 미반영
                cb.add(mekicalc.timeline.SupporterEngine.toBuffSource(pb));
                if ("AttackSpeed".equals(pb.stat()) && !pb.toEnemy()) cs.add(new double[]{pb.startMs(), pb.endMs(), pb.value() / 10.0});
            }
            List<GroupAgg> cg = comp.reprTl() != null ? buildGroups(comp.reprTl()) : null;
            mekicalc.timeline.ModState snap = comp.reprSnapshot();
            double refResp = cg != null ? evalWith(cg, inheritedFor(snap, map)) : 0;
            return new CompCache(cb, cs, cg, comp.totalDamage(), refResp, snap);
        }

        /** baseArt 확정 후 재sim 트리거 스탯 = 스케줄(RESIM_STATS) + 유물 파생 입력(크확·DMG 등). */
        private void expandResimStats() {
            java.util.Set<String> rs = new java.util.HashSet<>(RESIM_STATS);
            for (Stat st : ArtifactAssembler.derivedInputStats(baseArt)) rs.add(st.name());
            resimStats = rs;
        }

        /** 동료 재sim 캐시 키 = 스케줄 스탯(RESIM_STATS)만(파생 입력 제외 — 동료 스케줄은 파생과 무관). */
        private String compTuple(Map<String, Double> m) {
            StringBuilder sb = new StringBuilder();
            for (String s : RESIM_STATS) sb.append(s).append('=').append(m.getOrDefault(s, 0.0)).append(',');
            return sb.toString();
        }

        /** map 스탯에서의 동료 계승 base — 소환 스냅샷(그 스케줄의 고정 버프상태)에 map 실효스탯을 접어 계승(엔진과 동일 경로). */
        private Preset inheritedFor(mekicalc.timeline.ModState snapshot, Map<String, Double> map) {
            Preset playerEff = mekicalc.timeline.SupporterEngine.effectivePlayerPreset(presetFor(map), snapshot);
            return mekicalc.timeline.SupporterInherit.build(playerEff, compSup, compLvl, supBook());
        }

        /**
         * 종합 딜 = 플레이어 그룹×프리셋 + 동료 자체딜. 동료 자체딜은 그 스케줄의 대표 그룹을 "동료 자기 스탯"으로 같은 공식 평가.
         * baseline 내 非스케줄 델타(크뎀·최종뎀 등)는 inheritedFor로 동료가 자기 구조대로 반응(닫힌식·정확). 스케줄 델타는 Sim 자체가 재sim돼 반영.
         */
        private double evalGroups(Sim sim, Map<String, Double> map) {
            double playerDmg = evalWith(sim.player(), presetFor(map));
            double compDmg = 0;
            if (sim.compBase() > 0)
                compDmg = (sim.comp() != null && sim.compRefResponse() > 0)
                        ? sim.compBase() * (evalWith(sim.comp(), inheritedFor(sim.snapshot(), map)) / sim.compRefResponse())
                        : sim.compBase();
            return playerDmg + compDmg;
        }

        /** 그룹×프리셋 → 종합 딜(= Analyzer.totalDamage와 동일값, 슬롯 순회 없이 그룹 순회). */
        private double evalWith(List<GroupAgg> groups, Preset preset) {
            double dmg = 0;
            for (GroupAgg g : groups) {
                double pb = DamageFormula.perHit(preset, g.m(), BASIC1, monDef, boss);
                double ps = DamageFormula.perHit(preset, g.m(), SKILL1, monDef, boss);
                dmg += pb * g.basicW() + ps * g.skillW();
            }
            return dmg;
        }

        /** baseline 타임라인의 타격을 버프상태(ModState)별로 묶어 basic/skill 가중치(Σ 계수×타수×(1+마스터리))로 집계. */
        private List<GroupAgg> buildGroups(Timeline tl) {
            Map<String, ModState> reps = new LinkedHashMap<>();
            Map<String, double[]> ws = new LinkedHashMap<>();
            for (Slot s : tl.slots().values()) {
                String key = modKey(s.mods());
                reps.putIfAbsent(key, s.mods());
                double[] w = ws.computeIfAbsent(key, k -> new double[2]);
                for (DealEvent d : s.deals()) {
                    double cw = d.coeffFrac() * d.hits() * (1 + d.skillMastery());
                    if (d.dmgType() == DmgType.BASIC) w[0] += cw; else w[1] += cw;
                }
            }
            List<GroupAgg> out = new ArrayList<>(ws.size());
            for (Map.Entry<String, double[]> e : ws.entrySet())
                out.add(new GroupAgg(reps.get(e.getKey()), e.getValue()[0], e.getValue()[1]));
            return out;
        }

        /** ModState 동치 키 — factor에 쓰이는 합풀 값 + 최종뎀 곱만 반영(같으면 같은 factor). */
        private static String modKey(ModState m) {
            StringBuilder sb = new StringBuilder();
            for (Stat s : Stat.values()) { double v = m.get(s); if (v != 0) sb.append(s.ordinal()).append(':').append(v).append(','); }
            return sb.append('|').append(m.finalDmgMult()).toString();
        }

        /** buildTimeline과 동일한 프리셋(유물 잠재 합성 + 유물 스케줄 기여분 가산)을 재현 — 캐시/재sim 경로 공통. */
        private Preset presetFor(Map<String, Double> map) {
            Map<String, Double> s = withPotential(map, potentials);
            if (baseArt != null) {
                double sp = baseArt.artSpeed(), cd = baseArt.artCdrPct(), bd = baseArt.artBuffDurPct(), sd = baseArt.artSummonDurPct();
                if (sp != 0 || cd != 0 || bd != 0 || sd != 0) {
                    s = new LinkedHashMap<>(s);
                    if (sp != 0) s.put("ATK_SPEED", Stat.ATK_SPEED.combine(s.getOrDefault("ATK_SPEED", 0.0), sp));   // ★공속 체감(선형합 금지)
                    if (cd != 0) s.put("COOLDOWN_REDUCTION_PCT", Stat.COOLDOWN_REDUCTION_PCT.combine(s.getOrDefault("COOLDOWN_REDUCTION_PCT", 0.0), cd));   // ★쿨감 체감
                    if (bd != 0) s.merge("BUFF_DURATION", bd, Double::sum);      // 버프지속 = 합산(고유 체감식 없음)
                    if (sd != 0) s.merge("SUMMON_DURATION", sd, Double::sum);    // 소환지속 = 합산
                }
            }
            return new Preset(s);
        }

        @Override public StatBlock combine(StatBlock current, StatBlock delta) { return dm.combine(current, delta); }
        @Override public StatBlock uncombine(StatBlock r, StatBlock delta) { return dm.uncombine(r, delta); }
        @Override public double aggregate(String statId, double a, double b) { return dm.aggregate(statId, a, b); }

        @Override public boolean hasQualitative(StatBlock delta) {
            for (String id : delta.asMap().keySet()) {
                if (dm.isCustomConv(id)) continue;             // 사용자 지정 환산이 있으면 수치로 환산됨
                if (id.equals(BASIC_ATK_TARGETS)) return true; // 대상수: 단일대상 timeline이라 상황 의존 → 미환산
            }
            return false;
        }
    }

    /** 큐브 계산(추천/기댓값/비교)의 유물·컨텐츠 설정 묶음 — 나이트로드 신엔진 타임라인 조립에 쓴다(구엔진은 무시). */
    public record CubeCtx(List<Map<String, Object>> artifacts, List<Map<String, Object>> potentials,
                          Integer enemies, Double kills, String contentOverride, int artOffsetMs,
                          boolean preActivateProc, boolean autoFillBasic, Set<Integer> skillsOff,
                          boolean fillSkills, boolean optimize, int simMs,
                          String tlCompanion, int tlCompanionLevel, int tlFirstSummonMs, boolean tlCompanionInstantFinalDmg) {}   // 빌드의 동료(신엔진) — 큐브 딜에도 반영

    /** 나이트로드(신엔진 구현 직업) → 타임라인 종합 DPS 모델, 그 외 → 구엔진(1타×공속/종합 DPS 해석식). */
    private DealModel cubeDealModel(StatBlock current, Map<String, Double> conv, boolean boss, Set<String> buffs, double hitInterval,
                                    String chapter, String target, String companionId, String companionGrade, int companionLevel, double ratio,
                                    CubeCtx ctx) {
        if ((int) current.get("JOB") == JOB_NIGHTLORD)
            return new TimelineDealModel(content.damageModel(conv), chapter, target, ctx.artifacts(), ctx.potentials(),
                    ctx.enemies(), ctx.kills(), ctx.contentOverride(), ctx.artOffsetMs(), ctx.preActivateProc(),
                    ctx.autoFillBasic(), ctx.skillsOff(), ctx.fillSkills(), ctx.optimize(), ctx.simMs(),
                    ctx.tlCompanion(), ctx.tlCompanionLevel(), ctx.tlFirstSummonMs(), ctx.tlCompanionInstantFinalDmg());
        return dealModel(current, conv, boss, buffs, hitInterval, targetDef(chapter, target), companionId, companionGrade, companionLevel, ratio);
    }

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
                                         String companionId, String companionGrade, int companionLevel, double ratio, CubeCtx ctx) {
        StatBlock current = block(stats);
        boolean boss = isBossTarget(target);
        DealModel dm = cubeDealModel(current, conv, boss, buffs, hitInterval, chapter, target,
                companionId, companionGrade, companionLevel, ratio, ctx);
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

        // ★compute-once: 모든 슬라이더 위치×전체 추천(윗/아랫 모두)을 한 번에 반환 → 슬라이더·정렬·윗/아랫 필터는
        // 클라가 재fetch 없이 처리(recJson에 dealDiff·cost·recProbSum·totalProb·gainEff·upper 다 있음). pick은 딜 재계산 없음(값싸다).
        List<List<Map<String, Object>>> recsBySlider = new ArrayList<>();
        for (int s = 0; s < maxDivisions; s++) {
            double fs = maxDivisions > 1 ? (double) s / (maxDivisions - 1) : 0;
            List<Map<String, Object>> at = new ArrayList<>();
            for (CubeRecommender.EquipOpts eo : rec) {
                CubeRecommender.Rec r = CubeRecommender.pick(eo, fs);
                if (r != null) at.add(recJson(r));
            }
            recsBySlider.add(at);
        }

        // 하위호환: 요청 슬라이더/정렬/윗아랫 기준 recs(구 프론트용). 신 프론트는 recsBySlider로 클라 처리.
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
        out.put("recsBySlider", recsBySlider);
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
                                          String companionId, String companionGrade, int companionLevel, double ratio, CubeCtx ctx) {
        StatBlock current = block(stats);
        boolean boss = isBossTarget(target);
        DealModel dm = cubeDealModel(current, conv, boss, buffs, hitInterval, chapter, target,
                companionId, companionGrade, companionLevel, ratio, ctx);
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
                                           String companionId, String companionGrade, int companionLevel, double ratio, CubeCtx ctx) {
        StatBlock current = block(stats);
        boolean boss = isBossTarget(target);
        DealModel dm = cubeDealModel(current, conv, boss, buffs, hitInterval, chapter, target, companionId, companionGrade, companionLevel, ratio, ctx);
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

    /** 숨김 스탯이지만 스탯 비교엔 노출하는 장비 특수 옵션(눈장식=1레벨당 주스탯·포켓=4레벨당 주스탯%). 입력 폼엔 여전히 미노출. dm.combine이 레벨 반영해 주스탯으로 환산. */
    private static final Set<String> STAT_COMPARE_INCLUDE_HIDDEN = Set.of("MAIN_STAT_PER_LEVEL", "MAIN_STAT_PCT_PER_4LEVEL");

    /** 공용(직업 미선택)에선 스킬/기본공격 구분·로테이션이 없으므로 이 딜 구성 스탯들을 항상 '딜 미환산'으로 본다.
     *  (쿨감·지속류는 이미 qualitative라 공용에서 딜 미환산 → 여기선 기본공격/스킬 데미지만 강제) */
    private static final Set<String> COMMON_JOB_QUAL = Set.of("BASIC_ATK_DMG", "SKILL_DMG");

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
                                           String refStatId, double refValue, CubeCtx ctx) {
        StatBlock current = block(stats);
        boolean curBoss = isBossTarget(target);
        // 동료 포함(정확): 소환 가동률로 플레이어 버프가 블렌딩되므로 동소지도 플레이어 평균 DPS를 올린다.
        DealModel dm = cubeDealModel(current, conv, curBoss, buffs, hitInterval, chapter, target, companionId, companionGrade, companionLevel, ratio, ctx);

        // 보뎀/일뎀은 현재 스테이지 유형과 반대라 그대로면 딜 0 → 사용자 지정 교차 기준으로 평가한다.
        //  · 현재 일반몹 스테이지 → 보뎀은 그 챕터 '보스' 기준으로 계산
        //  · 현재 보스 스테이지 → 일뎀은 '바로 직전 스테이지'(그 챕터 최고 일반 스테이지) 기준으로 계산
        DealModel dmBossDmg = curBoss ? dm
                : cubeDealModel(current, conv, true, buffs, hitInterval, chapter, "boss", companionId, companionGrade, companionLevel, ratio, ctx);
        DealModel dmNormalDmg = !curBoss ? dm
                : cubeDealModel(current, conv, false, buffs, hitInterval, chapter, prevStageTarget(chapter), companionId, companionGrade, companionLevel, ratio, ctx);

        // 기준 스탯도 자기 컨텍스트에서 D를 측정(보뎀/일뎀을 기준으로 잡아도 딜 0이 되지 않게).
        DealModel refDm = effDm(refStatId, dm, dmBossDmg, dmNormalDmg);
        boolean refQual = refStatId != null && !refStatId.isEmpty()
                && refDm.hasQualitative(new StatBlock().with(refStatId, refValue == 0 ? 1 : refValue));
        double D = (refStatId != null && !refStatId.isEmpty() && refValue != 0)
                ? refDm.increase(current, new StatBlock().with(refStatId, refValue)) : 0.0;

        int job = (int) current.get("JOB");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StatDef d : content.stats.values()) {
            if ((d.hidden && !STAT_COMPARE_INCLUDE_HIDDEN.contains(d.id)) || d.derived || STAT_COMPARE_SKIP.contains(d.id)) continue;
            DealModel useDm = effDm(d.id, dm, dmBossDmg, dmNormalDmg);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("statId", d.id);
            // 교차 기준 안내(현재 스테이지와 반대 유형이라 다른 대상으로 환산했음을 표시)
            if ("BOSS_DMG".equals(d.id) && !curBoss) row.put("note", "보스 기준");
            else if ("NORMAL_DMG".equals(d.id) && curBoss) row.put("note", "직전 스테이지 기준");
            boolean qual = useDm.hasQualitative(new StatBlock().with(d.id, 1))
                    || (job == 0 && COMMON_JOB_QUAL.contains(d.id));   // 공용: 기본공격/스킬 데미지는 항상 딜 미환산
            if (d.id.equals(refStatId)) {
                row.put("qual", qual);
                row.put("self", true);
                row.put("value", safe(refValue));
                row.put("reachable", true);
            } else {
                row.put("self", false);
                double w = (D > 1e-12 && !qual) ? solveEquivalent(useDm, current, d.id, D) : Double.NaN;
                // 큰 값에도 딜 기여가 0인 스탯(공용 직업의 스킬 데미지 등)은 '달성 불가'가 아니라 '딜 미환산'으로 표기한다.
                if (!qual && !Double.isFinite(w) && !convertsToDamage(useDm, current, d.id)) qual = true;
                row.put("qual", qual);
                row.put("value", safe(w));
                row.put("reachable", Double.isFinite(w));
            }
            rows.add(row);
            // 프리셋 공%(ATK_PCT) 바로 아래에 '스킬/유물 유래 공격력%'(별도 곱 pool) — 실험실 합성 옵션과 동일
            if ("ATK_PCT".equals(d.id)) rows.add(statCmpRow("ATK_PCT_SKILL", dm, current, D, refStatId, refValue));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inc", safe(D));
        out.put("refStatId", refStatId);
        out.put("refQual", refQual);
        out.put("rows", rows);
        return out;
    }

    /** 스탯 비교 한 행 — 기준 대비 등가수치(self/딜미환산 포함). StatDef에 없는 합성 옵션(ATK_PCT_SKILL 등)도 동일 규칙으로 평가. */
    private Map<String, Object> statCmpRow(String sid, DealModel dm, StatBlock current, double D, String refStatId, double refValue) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("statId", sid);
        boolean qual = dm.hasQualitative(new StatBlock().with(sid, 1));
        if (sid.equals(refStatId)) {
            row.put("qual", qual); row.put("self", true); row.put("value", safe(refValue)); row.put("reachable", true);
        } else {
            row.put("self", false);
            double w = (D > 1e-12 && !qual) ? solveEquivalent(dm, current, sid, D) : Double.NaN;
            if (!qual && !Double.isFinite(w) && !convertsToDamage(dm, current, sid)) qual = true;
            row.put("qual", qual); row.put("value", safe(w)); row.put("reachable", Double.isFinite(w));
        }
        return row;
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

    /** 큰 값을 줘도 딜 증가가 0이면 현재 설정에서 딜로 환산되지 않는 스탯(공용의 스킬 데미지·완전 포화 크리 등) → '딜 미환산'. */
    private boolean convertsToDamage(DealModel dm, StatBlock current, String sid) {
        return incOne(dm, current, sid, 1e6) > 0;
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
        "반지2", "투구", "상의", "망토", "견장", "하의", "장갑", "벨트", "신발", "반지", "목걸이", "얼굴장식", "눈장식", "귀고리", "포켓");

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
            + "부위명은 반드시 다음 중 하나: 투구, 상의, 망토, 견장, 하의, 장갑, 벨트, 신발, 반지, 반지2, 목걸이, 얼굴장식, 눈장식, 귀고리, 포켓. "
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
                                         String companionId, String companionGrade, int companionLevel, double ratio, CubeCtx ctx) {
        StatBlock current = block(stats);
        boolean boss = isBossTarget(target);
        DealModel dm = cubeDealModel(current, conv, boss, buffs, hitInterval, chapter, target,
                companionId, companionGrade, companionLevel, ratio, ctx);
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
