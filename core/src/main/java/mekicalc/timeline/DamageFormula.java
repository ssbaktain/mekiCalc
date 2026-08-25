package mekicalc.timeline;

/**
 * 데미지 공식(인게임 소수점 검증본, 2026-08-18). base 프리셋 + 슬롯 ModState + DealEvent로 1타 데미지를 낸다.
 *
 * <pre>
 * baseAtk = ATK_FLAT × (1+(프리셋공%+ATK_PCT_ADD)/100) × (1+ATK_PCT/100)  // 스킬/유물 공%는 곱, unit:flat만 합
 * fDmg    = (1+(DMG+DMG_ADD+Δ)/100) × (1+(DMG_AMP+Δ)/100)
 * fStat   = 1 + (STAT_PROP+Δ)/100
 * fCrit   = 1 + min((CRIT_RATE+Δ)/100,1) × ((CRIT_DMG+Δ)/100)
 * fRange  = (min>max)? max/100 : (min+max)/200                     // 최소>최대면 MAX천장
 * fFinal  = (1+FINAL_DMG/100) × Π(1+최종뎀소스)                      // 선반영=프리셋, 후반영/액티브=소스곱
 * tf      = (1+타입뎀/100) × (1+피해마스터리)                         // ★곱
 * fTgt    = 1 + (일반몹/보스뎀+Δ)/100
 * fWeak   = 1 + 약화/100
 * defF    = 5000 / (6000 + 몹방어 × (1−방관/100))
 * 1타     = baseAtk·fDmg·fStat·fCrit·fRange·fFinal·tf·fTgt·fWeak·defF·계수
 * </pre>
 */
public final class DamageFormula {

    private DamageFormula() {}

    /** DealEvent 1개의 1타 데미지(hits 제외). */
    public static double perHit(Preset b, ModState m, DealEvent d, double monsterDef, boolean boss) {
        // 프리셋 공격력 = 깡공×(1+프리셋공%)(스킬 제외 resolved). 스킬/유물 공%는 그 위에 곱(ATK_PCT),
        // unit:flat 예외만 프리셋 공%에 합산(ATK_PCT_ADD).
        double atkPctAdd = b.atkPct() + m.get(Stat.ATK_PCT_ADD);
        // 스킬/유물 공격력%(곱 pool) + 가상 스탯 실험 주입분(ATK_PCT_SKILL) — 프리셋 공격력 위에 별도 곱.
        double skillAtkPct = m.get(Stat.ATK_PCT) + b.get("ATK_PCT_SKILL");
        double baseAtk = b.atkFlat() * (1 + atkPctAdd / 100.0) * (1 + skillAtkPct / 100.0);

        double dmg = b.dmg() + b.dmgAdd() + m.get(Stat.DMG);
        double fDmg = (1 + dmg / 100.0) * (1 + (b.dmgAmp() + m.get(Stat.DMG_AMP)) / 100.0);
        double fStat = 1 + (b.statProp() + m.get(Stat.STAT_PROP)) / 100.0;

        // 크리티컬 디버프(적 크저감·적받크뎀)는 여기 최종 단계에서만 크확/크뎀에 합류. 크확/크뎀 파생·증폭엔 이미 미포함(분류가 다름).
        double critR = Math.min((b.critRate() + m.get(Stat.CRIT_RATE) + m.get(Stat.CRIT_RATE_ENEMY)) / 100.0, 1.0);
        double critD = b.critDmg() + m.get(Stat.CRIT_DMG) + m.get(Stat.CRIT_DMG_ENEMY);
        double fCrit = 1 + critR * (critD / 100.0);

        double mn = b.minMult(), mx = b.maxMult() + m.get(Stat.MAX_DMG_MULT);
        double fRange = (mn > mx) ? mx / 100.0 : (mn + mx) / 200.0;

        double fFinal = (1 + b.finalDmg() / 100.0) * m.finalDmgMult();

        double typeDmg = (d.dmgType() == DmgType.BASIC)
                ? b.basicAtkDmg() + m.get(Stat.BASIC_ATK_DMG)
                : b.skillDmg() + m.get(Stat.SKILL_DMG);
        double tf = (1 + typeDmg / 100.0) * (1 + d.skillMastery());

        double tgt = boss ? b.bossDmg() + m.get(Stat.BOSS_DMG) : b.normalDmg() + m.get(Stat.NORMAL_DMG);
        double fTgt = 1 + tgt / 100.0;

        double fWeak = 1 + m.get(Stat.WEAKNESS) / 100.0;

        // 방관은 소스끼리 곱연산 합성(체감): 1-(1-프리셋방관)(1-유물등 추가방관). 단순 합이면 과대평가.
        double defPenFrac = 1 - (1 - b.defPen() / 100.0) * (1 - m.get(Stat.DEF_PEN) / 100.0);
        double defF = 5000.0 / (6000.0 + monsterDef * (1 - defPenFrac));

        return baseAtk * fDmg * fStat * fCrit * fRange * fFinal * tf * fTgt * fWeak * defF * d.coeffFrac();
    }

    /** DealEvent의 슬롯 데미지(= 1타 × 타수). */
    public static double dealDamage(Preset b, ModState m, DealEvent d, double monsterDef, boolean boss) {
        return perHit(b, m, d, monsterDef, boss) * d.hits();
    }
}
