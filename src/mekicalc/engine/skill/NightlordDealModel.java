package mekicalc.engine.skill;

import java.util.Map;
import mekicalc.engine.DamageModel;
import mekicalc.engine.DealModel;
import mekicalc.model.StatBlock;

/**
 * 나이트로드 딜 모델(종합 DPS 기반). 큐브 추천·평가의 출력값을 1타가 아닌 {@link NightlordDps#totalDps}로 준다.
 * 스탯 합성(combine/uncombine)은 기존 {@link DamageModel} 규칙을 그대로 위임한다.
 *
 * <p>대상(챕터/스테이지)이 주어지면 그 몹 방어배수 f를 곱해 <b>방관</b>까지 환산한다(전역 일관).
 * 대상수(BASIC_ATK_TARGETS)는 적 수가 본인 대상 수 이하면 무효라 상황 의존 → 항상 '딜 미환산'(원하면 커스텀 환산).
 *
 * <p>동료({@link CompanionDps})가 주어지면 출력에 동료 해석식 DPS를 합산하고, 소환 중 동료 버프를 플레이어에 블렌딩한다.
 * 이때 동소지(SUMMON_DURATION)는 소환창을 통해 네이티브로 환산되므로 커스텀 환산에서 제외한다(이중계산 방지).
 */
public final class NightlordDealModel implements DealModel {

    private static final String BASIC_ATK_TARGETS = "BASIC_ATK_TARGETS";
    private static final String DEF_PEN = "DEF_PEN";
    private static final String SUMMON_DURATION = "SUMMON_DURATION";

    private final NightlordDps nd;
    private final DamageModel base;   // 스탯 합성 위임용
    private final boolean boss;
    private final Double targetDef;   // 대상 몹 방어력(서버 전용). null이면 방관 미적용(f=1, 커스텀 환산 폴백).
    private final CompanionDps comp;  // 선택 동료(해석식). null이면 동료 미합산(동소지=커스텀 환산 폴백).
    private final double seconds;      // 전투시간(보스 70 / 일반 90). 동료 dpsOverSecondsAnalytic 정규화 기준.

    public NightlordDealModel(NightlordDps nd, DamageModel base, boolean boss) { this(nd, base, boss, null); }
    public NightlordDealModel(NightlordDps nd, DamageModel base, boolean boss, Double targetDef) {
        this(nd, base, boss, targetDef, null, 0);
    }
    public NightlordDealModel(NightlordDps nd, DamageModel base, boolean boss, Double targetDef, CompanionDps comp, double seconds) {
        this.nd = nd;
        this.base = base;
        this.boss = boss;
        this.targetDef = targetDef;
        this.comp = comp;
        this.seconds = seconds;
    }

    /**
     * 대상 방어배수 f = 5000/(6000 + 방어력 × max(0, 1 − (방관 + 동료 적방어감소 + 동료 방관)/100)). 대상 없으면 1.
     * 동료 디버프(적 방어력 감소)·동료 방관을 방관 항에 합산 → 플레이어·동료 딜에 일관 반영(단일 factor).
     */
    private double defFactor(StatBlock b) {
        if (targetDef == null) return 1.0;
        double penPct = b.get(DEF_PEN);
        if (comp != null) penPct += comp.enemyDefReducePct(b, boss) + comp.companionDefPenPct(b, boss);
        return 5000.0 / (6000.0 + targetDef * Math.max(0, 1 - penPct / 100.0));
    }

    @Override public double output(StatBlock b) {
        // 대상수 미고려 → 일반몹·보스 모두 단일대상(1마리)으로 계산. 둘의 차이는 일뎀/보뎀(BOSS_DMG/NORMAL_DMG)뿐.
        // 대상이 있으면 방관을 네이티브(f)로 환산. 없으면 커스텀 딜 환산으로 폴백(이중계산 방지).
        double conv = base.customConvFactor(b, BASIC_ATK_TARGETS);   // 단일대상이라 대상수 native=0 → 설정 시 커스텀 환산만
        if (targetDef == null) conv *= base.customConvFactor(b, DEF_PEN);
        if (comp == null) conv *= base.customConvFactor(b, SUMMON_DURATION);   // 동료 없으면 동소지 커스텀 환산
        double dps = comp == null ? nd.totalDps(b, boss) : combinedDps(b);
        return dps * defFactor(b) * conv;
    }

    /** 플레이어(동료 버프 블렌딩) + 동료 해석식 DPS. 동소지·동료버프·동료딜이 모두 스탯에 따라 반응. */
    private double combinedDps(StatBlock b) {
        NightlordDps.Buffed buf = nd.buffed(b);                         // 한 번만 계산(받피증·base DPS 공용)
        double pRecv = nd.playerRecvBonus(buf.stats);
        comp.playerRecvBonus(pRecv);                                    // 동료가 플레이어 받피증 공유받아 타격
        double player = playerBlendedTotal(b, buf, pRecv);
        StatBlock inh = nd.fullBuffStats(b);                            // 동료 계승 = 풀버프 플레이어 공격력 스냅샷
        double companion = comp.dpsOverSecondsAnalytic(inh, boss, seconds)[2];
        return player + companion;
    }

    /** 소환 가동률만큼 동료 버프/받피증을 블렌딩한 플레이어 전체 DPS. {@code CalcService.blendPlayer}의 엔진 버전. */
    @SuppressWarnings("unchecked")
    private double playerBlendedTotal(StatBlock b, NightlordDps.Buffed buf, double pRecv) {
        double base0 = nd.dpsBreakdownWith(buf, boss)[2];        // buf 재사용(buffed 재계산 없음)
        Map<String, Object> gift = comp.playerGiftAnalytic(b, boss);   // 해석식(시뮬 480스텝 제거) — 큐브 핫패스 성능

        Map<String, Object> giftBuffs = (Map<String, Object>) gift.get("buffs");
        double compAtkPct = giftBuffs.containsKey("ATK_PCT") ? ((Number) giftBuffs.remove("ATK_PCT")).doubleValue() : 0;
        StatBlock summonInput = new StatBlock(b);
        CompanionDps.applyGift(summonInput, giftBuffs);                 // 공퍼 외 버프(최종뎀·크리 등) 적용
        nd.setExternalAtkPct(compAtkPct);                              // 동료 공퍼를 플레이어 스킬 공퍼 합에 합산
        double summon = nd.dpsBreakdown(summonInput, boss)[2];
        nd.setExternalAtkPct(0);                                       // 원복
        double recvBonus = ((Number) gift.get("recvBonus")).doubleValue();
        double Rp = 1 + pRecv;
        double recvScale = Rp > 0 ? 1 + recvBonus / Rp : 1;            // 동료 받피증을 플레이어 받피증 위에 합연산
        double uptime = comp.summonUptime(b);
        return base0 * (1 - uptime) + summon * recvScale * uptime;
    }

    @Override public StatBlock combine(StatBlock current, StatBlock delta) { return base.combine(current, delta); }
    @Override public StatBlock uncombine(StatBlock r, StatBlock delta) { return base.uncombine(r, delta); }
    @Override public double aggregate(String statId, double a, double b) { return base.aggregate(statId, a, b); }

    /**
     * 닫힌식 가능 델타: 곱배수 스탯({@link DamageModel#MULT_STATS}) + (대상 있으면) 방관. 종합 DPS가 (globalMultiplier×f)에 균일 반영.
     * 동료가 합산돼도 곱배수 스탯은 플레이어·동료를 같은 비율로 곱하므로(동료가 크리/뎀/계승공격력을 균일 계승) 닫힌식이 정확하다(검증: 전 곱배수 스탯 오차 0%).
     */
    private boolean closedForm(StatBlock delta) {
        if (delta.asMap().isEmpty()) return false;
        for (String id : delta.asMap().keySet()) {
            if (DamageModel.MULT_STATS.contains(id)) continue;
            if (DEF_PEN.equals(id) && targetDef != null) continue;
            return false;
        }
        return true;
    }

    /**
     * 곱배수 스탯·방관만의 델타는 종합 DPS가 (버프 반영 globalMultiplier × 방어배수 f)에 균일 반영되므로
     * 전체 모델(스킬 루프) 없이 그 비율로 증가율을 정확히 계산(닫힌식 빠른 경로).
     * 그 외(스킬레벨·공속·쿨감·대상수·버프지속·평타/스킬뎀·동료 합산 등)는 전체 모델로 폴백.
     */
    @Override public double increase(StatBlock current, StatBlock delta) {
        if (closedForm(delta)) {
            double a0 = base.globalMultiplier(nd.buffed(current).stats, boss) * defFactor(current);
            if (a0 > 0) {
                StatBlock cb = combine(current, delta);
                double a1 = base.globalMultiplier(nd.buffed(cb).stats, boss) * defFactor(cb);
                return a1 / a0 - 1.0;
            }
        }
        // 전체 모델 폴백(비곱배수). baseOutput 캐시로 같은 baseline 반복 호출(큐브 추천 enumeration) 시 output(current) 재계산 방지.
        double before = baseOutput(current);
        if (before <= 0) return 0;
        return output(combine(current, delta)) / before - 1.0;
    }

    private StatBlock cachedBase;       // baseline 참조 캐시(추천 enumeration이 동일 baseline으로 수천 번 increase 호출)
    private double cachedBaseOutput;
    private double baseOutput(StatBlock current) {
        if (current != cachedBase) { cachedBase = current; cachedBaseOutput = output(current); }
        return cachedBaseOutput;
    }

    @Override public boolean hasQualitative(StatBlock delta) {
        for (String id : delta.asMap().keySet()) {
            if (base.isCustomConv(id)) continue;             // 사용자 지정 환산이 있으면 수치로 환산됨
            if (id.equals(BASIC_ATK_TARGETS)) return true;   // 대상수: 상황 의존 → 항상 '딜 미환산'
        }
        return false;
    }
}
