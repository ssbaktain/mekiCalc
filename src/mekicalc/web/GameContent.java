package mekicalc.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mekicalc.config.GameData;
import mekicalc.engine.DamageModel;
import mekicalc.engine.equip.EquipmentPotential;
import mekicalc.engine.skill.SkillData;
import mekicalc.engine.source.StatSource;
import mekicalc.json.Json;
import mekicalc.model.Profile;
import mekicalc.model.StatDef;

/**
 * 웹 서버가 시작할 때 <b>한 번만</b> 로드하는 읽기전용 게임 데이터(모든 요청이 공유).
 *
 * <p>데스크톱의 {@link GameData}와 달리 개인 데이터(현재 스탯·환산 설정)를 들지 않는다.
 * 무상태 원칙에 따라 현재 스탯·커스텀 환산은 요청마다 클라이언트가 보내고,
 * 그때그때 {@link #damageModel(Map)}으로 데미지 모델을 새로 조립한다.
 */
public final class GameContent {

    /** 스탯 정의(id → StatDef), stats.json 순서. */
    public final Map<String, StatDef> stats;
    public final Profile profile;
    /** 재화별 상대 가치(미라클큐브/에디셔널큐브 등). */
    public final Map<String, Double> currency;
    public final List<StatSource> sources;
    /** 장비 잠재 옵션 표(부위·등급·슬롯별 후보). 큐브 추천·평가·비교에 사용. */
    public final EquipmentPotential equip;
    /** 나이트로드 종합 DPS 데이터(없으면 null → 공용 모델 폴백). */
    public final SkillData skillData;
    /** 메인 동료 데이터(companions.json의 companions 배열, 원본 맵). 없으면 빈 목록(동료 탭 "준비 중"). */
    public final List<Map<String, Object>> companions;
    /** companions.json 전체 맵(소환쿨/유지·계승비율·등급평타 등 상수 포함). DPS 계산용. */
    public final Map<String, Object> companionRoot;
    /** monster_defense.json 전체 맵(챕터별 스테이지 일반몹/보스 방어력). 대상별 절대 데미지용. 없으면 빈 맵. */
    public final Map<String, Object> monsterDefense;
    /** monster_hp.json 전체 맵(챕터별 스테이지 일반몹/보스 체력). 평타컷 계산용(서버 전용). 없으면 빈 맵. */
    public final Map<String, Object> monsterHp;
    /** artifact.json 전체 맵(유물 데이터 + 슬롯 임계 등 상수). 없으면 빈 맵. */
    public final Map<String, Object> artifactRoot;
    /** 유물 목록(artifact.json의 artifacts 배열, 원본 맵). 없으면 빈 목록(유물 탭 "준비 중"). */
    public final List<Map<String, Object>> artifacts;
    /** 데이터 폴더 경로(개발 환경 개인 데이터 불러오기용 — 배포 시 개인 파일 없으면 빈 값). */
    public final Path dataDir;

    private GameContent(Map<String, StatDef> stats, Profile profile,
                        Map<String, Double> currency, List<StatSource> sources,
                        EquipmentPotential equip, SkillData skillData,
                        List<Map<String, Object>> companions, Map<String, Object> companionRoot,
                        Map<String, Object> monsterDefense, Map<String, Object> monsterHp,
                        Map<String, Object> artifactRoot, List<Map<String, Object>> artifacts, Path dataDir) {
        this.stats = stats;
        this.profile = profile;
        this.currency = currency;
        this.sources = sources;
        this.equip = equip;
        this.skillData = skillData;
        this.companions = companions;
        this.companionRoot = companionRoot;
        this.monsterDefense = monsterDefense;
        this.monsterHp = monsterHp;
        this.artifactRoot = artifactRoot;
        this.artifacts = artifacts;
        this.dataDir = dataDir;
    }

    /** data 폴더에서 게임 데이터를 읽어온다. 개인 데이터(character/conversions)는 무시한다. */
    public static GameContent load(Path dataDir) throws IOException {
        GameData g = GameData.load(dataDir);   // stats/profile/currency/sources 재사용(model/current은 버림)
        EquipmentPotential equip = EquipmentPotential.load(dataDir);
        SkillData skillData;
        try {
            skillData = SkillData.load(dataDir);
        } catch (Exception e) {
            skillData = null;   // 스킬 데이터 없거나 손상 → 나이트로드는 공용 모델로 폴백
        }
        Map<String, Object> compRoot = loadCompanionRoot(dataDir);
        List<Map<String, Object>> comps = new ArrayList<>();
        Object arr = compRoot.get("companions");
        if (arr != null) for (Object o : Json.asList(arr)) comps.add(Json.asMap(o));
        Map<String, Object> monDef = loadJsonMap(dataDir, "monster_defense.json");
        Map<String, Object> monHp = loadJsonMap(dataDir, "monster_hp.json");
        Map<String, Object> artRoot = loadJsonMap(dataDir, "artifact.json");
        List<Map<String, Object>> arts = new ArrayList<>();
        Object artArr = artRoot.get("artifacts");
        if (artArr != null) for (Object o : Json.asList(artArr)) arts.add(Json.asMap(o));
        return new GameContent(g.stats, g.profile, g.currencyValues, g.sources, equip, skillData,
                comps, compRoot, monDef, monHp, artRoot, arts, dataDir);
    }

    /** dataDir의 JSON 파일을 맵으로 읽는다. 없거나 손상 시 빈 맵. */
    private static Map<String, Object> loadJsonMap(Path dataDir, String name) {
        try {
            Path f = dataDir.resolve(name);
            if (Files.exists(f)) return Json.asMap(Json.parse(Files.readString(f)));
        } catch (Exception e) {
            System.err.println(name + " 읽기 실패(빈 맵): " + e);
        }
        return new java.util.LinkedHashMap<>();
    }

    /** companions.json 전체 맵(상수 + companions 배열). 없거나 손상 시 빈 맵. */
    private static Map<String, Object> loadCompanionRoot(Path dataDir) {
        try {
            Path f = dataDir.resolve("companions.json");
            if (Files.exists(f)) return Json.asMap(Json.parse(Files.readString(f)));
        } catch (Exception e) {
            System.err.println("companions.json 읽기 실패(빈 맵): " + e);
        }
        return new java.util.LinkedHashMap<>();
    }

    /**
     * 요청별 커스텀 환산(statId → 1단위당 딜%)으로 데미지 모델을 새로 만든다.
     * customConv가 비어 있으면 정성적 스탯은 환산되지 않는 기본 모델이 된다.
     */
    public DamageModel damageModel(Map<String, Double> customConv) {
        return new DamageModel(stats, profile, customConv);
    }
}
