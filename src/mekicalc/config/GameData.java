package mekicalc.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.engine.DamageModel;
import mekicalc.engine.source.EnhanceSource;
import mekicalc.engine.source.PotentialSource;
import mekicalc.engine.source.StatSource;
import mekicalc.json.Json;
import mekicalc.model.Profile;
import mekicalc.model.StatBlock;
import mekicalc.model.StatDef;

/**
 * data/ 폴더의 JSON 파일들을 읽어 런타임 모델로 조립한다.
 *
 * 읽는 파일:
 *   stats.json     - 스탯 정의(id, 이름, 단위, combine 규칙)
 *   profile.json   - 전투 시나리오 + 미확정 상수
 *   currency.json  - 재화별 상대 가치
 *   sources.json   - 스탯 획득 수단(잠재류/주흔작)
 *   character.json - 현재 캐릭터 스탯(표시값)
 */
public final class GameData {

    public final Map<String, StatDef> stats;
    public final Profile profile;
    public final Map<String, Double> currencyValues;
    public final List<StatSource> sources;
    public final StatBlock current;
    public final DamageModel model;

    private GameData(Map<String, StatDef> stats, Profile profile,
                     Map<String, Double> currencyValues, List<StatSource> sources,
                     StatBlock current) {
        this.stats = stats;
        this.profile = profile;
        this.currencyValues = currencyValues;
        this.sources = sources;
        this.current = current;
        this.model = new DamageModel(stats, profile);
    }

    public static GameData load(Path dataDir) throws IOException {
        Map<String, StatDef> stats = new LinkedHashMap<>();
        for (Object o : Json.asList(read(dataDir, "stats.json"))) {
            StatDef d = StatDef.fromJson(Json.asMap(o));
            stats.put(d.id, d);
        }

        Profile profile = Profile.fromJson(Json.asMap(read(dataDir, "profile.json")));

        Map<String, Double> currency = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.asMap(read(dataDir, "currency.json")).entrySet()) {
            if (e.getValue() instanceof Double) currency.put(e.getKey(), (Double) e.getValue());
        }

        List<StatSource> sources = new ArrayList<>();
        for (Object o : Json.asList(read(dataDir, "sources.json"))) {
            Map<String, Object> m = Json.asMap(o);
            String type = Json.str(m, "type");
            switch (type) {
                case "POTENTIAL": sources.add(PotentialSource.fromJson(m)); break;
                case "ENHANCE":   sources.add(EnhanceSource.fromJson(m)); break;
                default: throw new IllegalArgumentException("알 수 없는 소스 type: " + type);
            }
        }

        StatBlock current = new StatBlock();
        Map<String, Object> chStats = Json.asMap(Json.asMap(read(dataDir, "character.json")).get("stats"));
        for (Map.Entry<String, Object> e : chStats.entrySet()) {
            if (e.getValue() instanceof Double) current.with(e.getKey(), (Double) e.getValue());
        }

        return new GameData(stats, profile, currency, sources, current);
    }

    private static Object read(Path dir, String file) throws IOException {
        return Json.parse(Files.readString(dir.resolve(file)));
    }
}
