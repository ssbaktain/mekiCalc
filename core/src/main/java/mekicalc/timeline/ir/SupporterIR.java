package mekicalc.timeline.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 동료 1기(코드=등급 단위, supporter_manual.json의 supporters[] 원소).
 *
 * <p>딜 = 계승(공격력 부분·나머지 100%) + 자기 스킬(§_clt2_SUPPORTER_ENGINE). 옵션(collection/equip)=계정전역·딜분리(used:false).
 *
 * @param code            SUPPORTER_{class}_{step}
 * @param clazz           ClassType(Bishop 등)
 * @param name            표시명
 * @param grade           rare/epic/unique/legend/cadet
 * @param creatureIndex   개체 인덱스(9{class}{grade})
 * @param spawnDurationMs 소환 지속 base(30000)
 * @param summonCooldownMs 소환 쿨(90000)
 * @param baseRatio       LinkedAttackStatBaseRatio(1000)
 * @param levelFactor     계승 factor 열(8~12=등급별)
 * @param skills          스킬 목록
 * @param collection      도감 옵션(계정전역, 딜분리)
 * @param equip           장착 옵션(계정전역, 딜분리)
 */
public record SupporterIR(
        String code,
        String clazz,
        String name,
        String grade,
        String creatureIndex,
        int spawnDurationMs,
        int summonCooldownMs,
        int baseRatio,
        int levelFactor,
        List<SupporterSkillIR> skills,
        List<SupporterOption> collection,
        List<SupporterOption> equip
) {
    /** 동료 옵션 1건(계정전역 상시스탯, 딜분리). value 성장식은 별도(옵션→스탯 기여 기능). */
    public record SupporterOption(String stat, Double value, Boolean used) {
        static SupporterOption from(Map<String, Object> m) {
            return new SupporterOption(IRRead.str(m, "stat"), IRRead.dbl(m, "value"), IRRead.bool(m, "used"));
        }
    }

    @SuppressWarnings("unchecked")
    static SupporterIR from(Map<String, Object> m) {
        List<SupporterSkillIR> sk = new ArrayList<>();
        for (Map<String, Object> s : IRRead.mapList(m, "skills")) sk.add(SupporterSkillIR.from(s));

        Map<String, Object> inherit = (Map<String, Object>) m.get("inherit");
        int baseRatio = 1000, levelFactor = 0;
        if (inherit != null) {
            Integer br = IRRead.intg(inherit, "baseRatio");
            Integer lf = IRRead.intg(inherit, "levelFactor");
            if (br != null) baseRatio = br;
            if (lf != null) levelFactor = lf;
        }

        List<SupporterOption> coll = new ArrayList<>(), eq = new ArrayList<>();
        Map<String, Object> opts = (Map<String, Object>) m.get("options");
        if (opts != null) {
            for (Map<String, Object> o : IRRead.mapList(opts, "collection")) coll.add(SupporterOption.from(o));
            for (Map<String, Object> o : IRRead.mapList(opts, "equip")) eq.add(SupporterOption.from(o));
        }

        Integer spawn = IRRead.intg(m, "spawnDurationMs");
        Integer cd = IRRead.intg(m, "summonCooldownMs");
        return new SupporterIR(
                IRRead.str(m, "code"),
                IRRead.str(m, "class"),
                IRRead.str(m, "name"),
                IRRead.str(m, "grade"),
                IRRead.str(m, "creatureIndex"),
                spawn == null ? 30000 : spawn,
                cd == null ? 90000 : cd,
                baseRatio,
                levelFactor,
                List.copyOf(sk),
                List.copyOf(coll),
                List.copyOf(eq)
        );
    }

    /** 동료레벨 lv에서 해금된 스킬만. */
    public List<SupporterSkillIR> unlockedSkills(int lv) {
        List<SupporterSkillIR> out = new ArrayList<>();
        for (SupporterSkillIR s : skills) if (s.unlockedAt(lv)) out.add(s);
        return out;
    }
}
