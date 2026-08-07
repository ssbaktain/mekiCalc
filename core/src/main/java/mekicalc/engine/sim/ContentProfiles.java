package mekicalc.engine.sim;

import java.util.LinkedHashMap;
import java.util.Map;
import mekicalc.json.Json;

/**
 * 컨텐츠 프로파일 팩토리 — {@code data/monster_defense.json}(챕터별 몬스터 방어력, 인게임 검증)로부터
 * {@link ContentProfile}을 생성한다. 방어력 체인은 [[mekicalc-monster-defense]] 참고.
 *
 * <p>구조: {@code chapters[챕터].stages[던전]}=일반몹 방어력, {@code chapters[챕터].boss}=챕터 보스 방어력.
 * HP는 별도 데이터([[mekicalc-monster-hp]])라 인자로 받는다(0이면 TTK 미산출).
 */
public final class ContentProfiles {

    private final Map<String, Map<String, Object>> chapters;

    private ContentProfiles(Map<String, Map<String, Object>> chapters) {
        this.chapters = chapters;
    }

    /** monster_defense.json 문자열로 로드. */
    public static ContentProfiles load(String monsterDefenseJson) {
        Map<String, Object> root = Json.asMap(Json.parse(monsterDefenseJson));
        Map<String, Object> ch = Json.asMap(root.get("chapters"));
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        if (ch != null) for (Map.Entry<String, Object> e : ch.entrySet()) m.put(e.getKey(), Json.asMap(e.getValue()));
        return new ContentProfiles(m);
    }

    /** 챕터 사냥(다수 대상). stage=던전 인덱스, hp=대상 HP(0=TTK 미산출), mobs=동시 대상 수(≈84). */
    public ContentProfile chapterHunt(int chapter, int stage, double hp, int mobs) {
        return ContentProfile.multi(Content.CHAPTER_HUNT, stageDefense(chapter, stage), hp, mobs);
    }

    /** 챕터 보스(단일 대상). */
    public ContentProfile chapterBoss(int chapter, double hp) {
        Map<String, Object> c = chapters.get(String.valueOf(chapter));
        return ContentProfile.plain(Content.CHAPTER_BOSS, c != null ? Json.num(c, "boss", 0) : 0, hp);
    }

    private double stageDefense(int chapter, int stage) {
        Map<String, Object> c = chapters.get(String.valueOf(chapter));
        if (c == null) return 0;
        Map<String, Object> stages = Json.asMap(c.get("stages"));
        return stages != null ? Json.num(stages, String.valueOf(stage), 0) : 0;
    }
}
