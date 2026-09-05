package mekicalc.timeline.ir;

import java.util.Map;

/**
 * 동료(서포터) 스킬의 개별 효과 1건(supporter_manual.json 충실 파싱본).
 *
 * <p>규약=data/skills_calc/md/_clt2_SUPPORTER_TAGS.md. 단위: coeff·value=천분율(÷10=표시%, FinalDamage ÷1000=배율).
 * <p>동료 특유: {@link #target}=player(소환주)/companion(동료자기)/enemy(적) · {@link #cond}=조건(기본 상시 가정) ·
 * {@link #used}=false면 딜무관(회복·클렌즈 등) · {@link #action}=cleanse 등 비스탯 동작.
 *
 * @param role     deal / statUp / debuff / utility / cc
 * @param stat     대상 스탯명(clt 원본, 예 MaxDamageRatio·FinalDamage·CriticalPower·ATK_PCT)
 * @param value    statUp·debuff 값(‰)
 * @param coeff    deal 계수(‰)
 * @param hits     deal 타수(없으면 1)
 * @param targetCount deal 대상 수(자식 maxHitCount 통합값)
 * @param target   player / companion / enemy (없으면 문맥 기본)
 * @param durationMs 버프/도트 지속(ms)
 * @param tickMs   도트 틱 간격(ms)
 * @param form     "dot"이면 지속피해
 * @param cond     조건 문서(예 "hpRatio>=30") — 엔진 기본 상시 가정(적용)
 * @param used     false=딜무관(딜 계산 제외, 보존만)
 * @param action   비스탯 동작(예 "cleanse")
 * @param perHpRatio 현재HP 비례 스탯
 * @param assumeMax  현재HP 비례를 HP100% 최대로 계산(value가 이미 최대값)
 * @param maxStacks  램프업 스택 상한(periodic 스택 버프, 예 최종뎀 1초마다 +1.5% 최대 15중첩)
 * @param whenState    ★범용: 이름의 상태창이 <b>활성</b>일 때만 이 effect 발동(예 서펜트스톤 중 어썰트). null=무관.
 * @param whenNotState ★범용: 이름의 상태창이 <b>비활성</b>일 때만 발동(예 서펜트스톤 아닐 때 버스트). null=무관.
 * @param chance       ★범용: 이 effect의 발동 확률(‰, 기댓값 가중). deal이면 coeff에 ×(chance/1000). 예 버스트50%→어썰트 치환(버스트 chance500·어썰트 chance500). null=100%.
 * @param tickCount    ★범용: deal을 시간분산(채널링)해 N틱 방출(예 SongOfSky 화살 13발). null=단발(즉발). tickMs=간격, tickStartMs=첫틱 오프셋.
 * @param tickStartMs  채널링 첫 틱 오프셋(ms, 시전 기준). null=tickMs.
 * @param tickBySpeed  true면 틱 시각을 <b>공속 비례</b>로 압축(간격·오프셋 ×(1000/slotMs)의 역=시전시점 slotMs/1000). 화살 발사속도가 공속에 비례.
 * @param expiresOnSummonEnd ★범용: true면 <b>설치기</b>(바닥 장판·소환체 = CreateProjectile, clt Flag ExpireOnCreatureDie).
 *                     소환 해제(소환창 windowMs) 시 함께 제거 → 도트 틱을 <b>소환창에서 절단</b>(simCap 아님).
 *                     null/false=적/플레이어 상태칸(적 도트 GetDotR·버프·디버프)이라 소환 종료 후에도 잔존.
 */
public record SupporterEffectIR(
        String role,
        String stat,
        Double value,
        Double coeff,
        Integer hits,
        Integer targetCount,
        String target,
        Integer durationMs,
        Integer tickMs,
        String form,
        String cond,
        Boolean used,
        String action,
        Boolean perHpRatio,
        Boolean assumeMax,
        Integer maxStacks,
        String whenState,
        String whenNotState,
        Integer chance,
        Integer tickCount,
        Integer tickStartMs,
        Boolean tickBySpeed,
        Boolean expiresOnSummonEnd,
        Boolean individualDur
) {
    /** 발동 확률 배율(기댓값). chance 없으면 1.0. */
    public double chanceMult() { return chance == null ? 1.0 : chance / 1000.0; }
    /** ★중첩 지속 방식: true=발동마다 독립 창(은펜던트식, 게임설명 "지속시간은 개별 적용"). null/false=공유 타이머(기본, 재발동 시 전체 갱신+스택+1, 만료 시 전 스택 동시 소멸). */
    public boolean individualDurOn() { return Boolean.TRUE.equals(individualDur); }
    /** 시간분산(채널링) 딜인가(tickCount>0). */
    public boolean isChanneled() { return tickCount != null && tickCount > 0; }
    public boolean tickBySpeedOn() { return Boolean.TRUE.equals(tickBySpeed); }
    static SupporterEffectIR from(Map<String, Object> m) {
        return new SupporterEffectIR(
                IRRead.str(m, "role"),
                IRRead.str(m, "stat"),
                IRRead.dbl(m, "value"),
                IRRead.dbl(m, "coeff"),
                IRRead.intg(m, "hits"),
                IRRead.intg(m, "targetCount"),
                IRRead.str(m, "target"),
                IRRead.intg(m, "durationMs"),
                IRRead.intg(m, "tickMs"),
                IRRead.str(m, "form"),
                IRRead.str(m, "cond"),
                IRRead.bool(m, "used"),
                IRRead.str(m, "action"),
                IRRead.bool(m, "perHpRatio"),
                IRRead.bool(m, "assumeMax"),
                IRRead.intg(m, "maxStacks"),
                IRRead.str(m, "whenState"),
                IRRead.str(m, "whenNotState"),
                IRRead.intg(m, "chance"),
                IRRead.intg(m, "tickCount"),
                IRRead.intg(m, "tickStartMs"),
                IRRead.bool(m, "tickBySpeed"),
                IRRead.bool(m, "expiresOnSummonEnd"),
                IRRead.bool(m, "individualDur")
        );
    }

    /** 설치기(바닥 장판·소환체)인가 — 소환 해제 시 함께 제거(도트 틱을 소환창에서 절단). */
    public boolean installer() { return Boolean.TRUE.equals(expiresOnSummonEnd); }

    /** ★범용 상태 게이팅: 상태창 활성/비활성 조건을 현재 상태집합으로 평가. whenState/whenNotState 미지정이면 항상 true. */
    public boolean stateActive(java.util.function.Predicate<String> stateActiveAt) {
        if (whenState != null && !stateActiveAt.test(whenState)) return false;
        if (whenNotState != null && stateActiveAt.test(whenNotState)) return false;
        return true;
    }

    /** 딜 무관(used:false)이면 true — 딜 계산에서 제외. */
    public boolean unused()      { return Boolean.FALSE.equals(used); }
    public boolean isDeal()      { return "deal".equals(role) && !unused(); }
    public boolean isDot()       { return "dot".equals(form); }
    public int     hitsOr1()     { return hits == null ? 1 : hits; }
    public int     targetsOr1()  { return targetCount == null ? 1 : targetCount; }
    /** 대상=플레이어(소환주)인가. target 미지정 시 문맥 기본은 호출부에서 판단. "both"=플레이어+동료 양쪽. */
    public boolean toPlayer()    { return "player".equals(target) || "both".equals(target); }
    public boolean toCompanion() { return "companion".equals(target) || "both".equals(target); }
    public boolean toEnemy()     { return "enemy".equals(target); }
    public int     maxStacksOr1(){ return maxStacks == null ? 1 : maxStacks; }

    /**
     * 컨텐츠 조건(boss/nonBoss) 게이팅 — 이 effect가 현재 컨텐츠에서 활성인가.
     * ★정책(_conventions.cond): boss/nonBoss만 실제 boss 플래그로 게이팅, 나머지 cond(hpRatio 등)는 상시 충족 가정.
     */
    public boolean condActive(boolean boss) {
        if ("boss".equals(cond)) return boss;
        if ("nonBoss".equals(cond)) return !boss;
        return true;
    }
}
