package mekicalc.timeline;

import mekicalc.timeline.ir.SupporterBook;
import mekicalc.timeline.ir.SupporterIR;

import java.util.HashMap;
import java.util.Map;

/**
 * 동료 계승 — 소환 시점 플레이어 실시간 스탯(동적 스냅샷 B) → 동료 base {@link Preset}.
 * 규약 = _clt2_SUPPORTER_ENGINE.md §2, _clt2_SUPPORTER_TAGS.md §4.
 *
 * <ul>
 *   <li><b>공격력만 부분 계승</b>: 동료 ATK_FLAT = player.ATK_FLAT × (1+공%/100) × 계승비율. 동료 ATK_PCT=0(계승분 resolved).
 *   <li><b>나머지 스탯 100% 계승</b>: 크확·크뎀·공속·STAT_PROP·DMG·평타뎀·스킬뎀·방관·보스뎀·일반뎀·최소최대뎀·최종뎀.
 *   <li><b>미계승</b>: 쿨감·1~4스렙·모스렙·소환지속%·버프지속% (동료 딜에 미적용).
 *   <li>계승비율% = Factor[동료레벨][levelFactor]/10 (만렙 90%캡) × compareLevel 플레이어 보정.
 * </ul>
 */
public final class SupporterInherit {

    private SupporterInherit() {}

    /** compareLevel(플레이어 차수레벨 미만이면 감산) — 등급별. rare/cadet은 보정 없음. */
    private static int compareLevel(String grade) {
        return switch (grade == null ? "" : grade) {
            case "epic" -> 30;
            case "unique" -> 60;
            case "legend" -> 100;
            default -> 0;
        };
    }

    /** 최종 공격력 계승비율(%) = Factor/10 × compareLevel 플레이어 보정. */
    public static double inheritRatioPct(SupporterBook book, SupporterIR sup, int companionLevel, int playerLevel) {
        double pct = book.inheritRatioPct(sup.levelFactor(), companionLevel);
        int cmp = compareLevel(sup.grade());
        if (cmp > 0 && playerLevel < cmp && playerLevel > 0) pct *= (double) playerLevel / cmp;
        return pct;
    }

    /** 소환 지속(ms) = 30000 × (1 + 소환지속%/100). 소환지속%=플레이어 스탯(SUMMON_DURATION, 소환 시점). */
    public static int windowMs(Preset player, SupporterIR sup) {
        double summonDurPct = player.get("SUMMON_DURATION");
        return (int) Math.round(sup.spawnDurationMs() * (1 + summonDurPct / 100.0));
    }

    /**
     * 소환 시점 플레이어 실시간 프리셋 → 동료 base 프리셋.
     * @param player 소환 순간의 플레이어 실효 스탯(base+그 시점 버프 반영). 동적 스냅샷(B).
     */
    public static Preset build(Preset player, SupporterIR sup, int companionLevel, SupporterBook book) {
        double ratio = inheritRatioPct(book, sup, companionLevel, player.level()) / 100.0;
        double inheritedAtk = player.atkFlat() * (1 + player.atkPct() / 100.0) * ratio;

        Map<String, Double> s = new HashMap<>();
        s.put("ATK_FLAT", inheritedAtk);
        s.put("ATK_PCT", 0.0);                     // 계승분은 ATK_FLAT에 resolved. 동료 자기 공%버프는 ModState로.
        // ── 나머지 스탯 100% 계승(소환 순간 플레이어 값) ──
        s.put("CRIT_RATE", player.critRate());
        s.put("CRIT_DMG", player.critDmg());
        s.put("ATK_SPEED", player.atkSpeed());
        s.put("STAT_PROP_DMG", player.statProp());
        s.put("DMG", player.dmg());
        s.put("DMG_ADD", player.dmgAdd());
        s.put("DMG_AMP", player.dmgAmp());
        s.put("BASIC_ATK_DMG", player.basicAtkDmg());
        s.put("SKILL_DMG", player.skillDmg());
        s.put("DEF_PEN", player.defPen());
        s.put("BOSS_DMG", player.bossDmg());
        s.put("NORMAL_DMG", player.normalDmg());
        s.put("MIN_DMG_MULT", player.minMult());
        s.put("MAX_DMG_MULT", player.maxMult());
        s.put("FINAL_DMG", player.finalDmg());
        s.put("LEVEL", (double) player.level());   // 딜 공식엔 미사용이나 안전 복사
        // 미계승(0/생략): COOLDOWN_REDUCTION_PCT·COOLDOWN_REDUCTION·SKILL_LEVEL_*·SKILL_LEVELS·SUMMON_DURATION·BUFF_DURATION·ATK_PCT_SKILL
        return new Preset(s);
    }
}
