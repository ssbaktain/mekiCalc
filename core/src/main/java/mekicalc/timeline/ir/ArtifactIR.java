package mekicalc.timeline.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 유물 1종(artifact_manual.json 1건, 충실 파싱본). 스킬 IR와 같은 단위 관습·stat명·BuffSource 타깃을 공유한다.
 *
 * <p>{@code _}-접두 필드(_op/_cltStat/_raw/_survival)는 사람 참고용이라 무시하되,
 * {@code _defer}만은 "기본 계산에서 건너뜀"을 뜻하는 지시자라 {@link #defer}로 승격해 읽는다(계약 §1).
 *
 * @param idx         유물 idx
 * @param name        이름
 * @param grade       등급(epic/unique/legend)
 * @param trigger     유물레벨 기본 트리거(period/combatStart/onKill/onAttack/onDamage/onState/companionSummoned). 없으면 상시.
 * @param periodMs    period 주기(ms)
 * @param chance      발동 확률(‰)
 * @param cooldownMs  재사용 대기(ms)
 * @param startDelayMs 첫 발동 지연(ms)
 * @param refresh     매 발동 시 자기 이전효과 제거 후 재적용(비누적, clt ClearState). 순환.
 * @param defer       유물레벨 _defer(피격/상태의존 → 컨텐츠모드 보류). true면 전 effect 스킵.
 * @param content     컨텐츠 한정 목록(비면 상시). 현재 컨텐츠가 여기 없으면 이 유물 미적용.
 * @param effects     효과 목록
 */
public record ArtifactIR(
        int idx,
        String name,
        String grade,
        String trigger,
        Integer periodMs,
        Integer chance,
        Integer cooldownMs,
        Integer startDelayMs,
        boolean refresh,
        boolean defer,
        boolean fixedDuration,
        List<String> content,
        List<Effect> effects
) {
    @SuppressWarnings("unchecked")
    static ArtifactIR from(Map<String, Object> m) {
        List<Effect> effs = new ArrayList<>();
        for (Map<String, Object> e : IRRead.mapList(m, "effects")) effs.add(Effect.from(e));
        return new ArtifactIR(
                IRRead.strInt(m, "idx"),
                IRRead.str(m, "name"),
                IRRead.str(m, "grade"),
                IRRead.str(m, "trigger"),
                IRRead.intg(m, "periodMs"),
                IRRead.intg(m, "chance"),
                IRRead.intg(m, "cooldownMs"),
                IRRead.intg(m, "startDelayMs"),
                Boolean.TRUE.equals(IRRead.bool(m, "refresh")),
                m.containsKey("_defer"),
                Boolean.TRUE.equals(IRRead.bool(m, "fixedDuration")),   // 강제소멸형(양초 등) — 벞지 미적용
                IRRead.strList(m, "content"),
                List.copyOf(effs)
        );
    }

    /**
     * 유물 효과 1건. role별로 쓰는 필드가 달라 전부 nullable. 값은 각성차수 배열({@link #values}, aw0~aw5, ‰).
     *
     * @param stat         대상 스탯명(스킬 IR와 동일 규약)
     * @param values       각성차수별 값(‰). derived 전용 효과는 null.
     * @param unit         "flat"(합)·"pct"(곱) 예외 판별
     * @param durationMs   지속(ms) — 시한부 창
     * @param delayMs      전투시작/발동 후 시작 지연(ms)
     * @param maxStacks    최대 중첩
     * @param individualDur 중첩분 개별 지속(발동마다 독립창)
     * @param dmgMult      "현재 수치의 X%" 곱 누적(육각목걸이)
     * @param bossOnly     보스 대상만 적용
     * @param bossZero     보스면 미적용(비보스 전용)
     * @param minEnemies   적 N명 이상일 때만
     * @param perEnemyMax  주변 적 1명당(최대 N명) — 값이 명수만큼 배수
     * @param mpAtLeast    MP ≥ N‰ 조건
     * @param assumeMax    최대치 가정(머쉬맘 명중초과)
     * @param targetMode   "single"(적1)·"multi"(적2+)
     * @param require      대상 상태 조건(buff/debuff/shield)
     * @param priorityGroup 겹침-배타 그룹명(그룹 내 최고 priority만)
     * @param priority     그룹 내 우선순위
     * @param target       "self"·"companion"·"enemy"
     * @param trigger      effect레벨 트리거 오버라이드
     * @param chance       effect레벨 확률 오버라이드(‰)
     * @param defer        effect레벨 _defer
     * @param hasDerived   라이브 스탯 후처리(비율/초과분) 여부
     * @param derivedFrom  파생 입력 스탯명(CriticalChance·AttackSpeed)
     * @param derivedRatio 비율형 파생: 입력×ratio(‰, 각성별). 고대책·헬레나
     * @param derivedThreshold 초과분형 파생: 입력이 이 값(‰) 초과분만. 물병(600=공속60%)
     * @param derivedStep  초과분형: step(‰)마다 1회. 물병(30=3%)
     * @param derivedPerStep 초과분형: 1회당 값(‰, 각성별). 물병(5=0.5%[5각10=1%])
     * @param derivedMaxCount 초과분형: 최대 발동 횟수. 물병(20)
     * @param critLt       크확 < N‰ 조건
     * @param critGte      크확 ≥ N‰ 조건
     * @param critExcessStep 크확 초과분형(순환): step(‰, 100=10%)마다 value 1회 추가
     * @param critExcessMax  크확 초과분형: 최대 발동 횟수(10)
     * @param contentMult  컨텐츠별 배수 [{content,ratio}]
     */
    public record Effect(
            String stat,
            List<Double> values,
            String unit,
            Integer durationMs,
            Integer delayMs,
            Integer maxStacks,
            boolean individualDur,
            boolean dmgMult,
            boolean bossOnly,
            boolean bossZero,
            Integer minEnemies,
            Integer perEnemyMax,
            Integer mpAtLeast,
            boolean assumeMax,
            String targetMode,
            String require,
            String priorityGroup,
            Integer priority,
            String target,
            String trigger,
            Integer chance,
            boolean defer,
            boolean hasDerived,
            String derivedFrom,
            List<Double> derivedRatio,
            Integer derivedThreshold,
            Integer derivedStep,
            List<Double> derivedPerStep,
            Integer derivedMaxCount,
            Integer critLt,
            Integer critGte,
            Integer critExcessStep,
            Integer critExcessMax,
            List<ContentMult> contentMult
    ) {
        @SuppressWarnings("unchecked")
        static Effect from(Map<String, Object> m) {
            List<Double> vals = numList(m.get("values"));
            Integer perMax = null;
            Object pe = m.get("perEnemy");
            if (pe instanceof Map<?, ?> pm) perMax = IRRead.intg((Map<String, Object>) pm, "max");

            // derived: {from, ratio[6각]} 비율형 또는 {from, threshold, step, perStep[6각], maxCount} 초과분형
            String dFrom = null;
            List<Double> dRatio = null, dPerStep = null;
            Integer dThresh = null, dStep = null, dMax = null;
            Object dv = m.get("derived");
            if (dv instanceof Map<?, ?> dm0) {
                Map<String, Object> dm = (Map<String, Object>) dm0;
                dFrom = IRRead.str(dm, "from");
                dRatio = numList(dm.get("ratio"));
                dPerStep = numList(dm.get("perStep"));
                dThresh = IRRead.intg(dm, "threshold");
                dStep = IRRead.intg(dm, "step");
                dMax = IRRead.intg(dm, "maxCount");
            }

            // critExcess: {step, maxCount} — 크확 100% 초과분형(순환의 평타뎀)
            Integer ceStep = null, ceMax = null;
            Object ce = m.get("critExcess");
            if (ce instanceof Map<?, ?> cem0) {
                Map<String, Object> cem = (Map<String, Object>) cem0;
                ceStep = IRRead.intg(cem, "step");
                ceMax = IRRead.intg(cem, "maxCount");
            }

            List<ContentMult> cm = new ArrayList<>();
            for (Map<String, Object> c : IRRead.mapList(m, "contentMult"))
                cm.add(new ContentMult(IRRead.strList(c, "content"), IRRead.dbl(c, "ratio")));

            return new Effect(
                    IRRead.str(m, "stat"),
                    vals == null ? null : List.copyOf(vals),
                    IRRead.str(m, "unit"),
                    IRRead.intg(m, "durationMs"),
                    IRRead.intg(m, "delayMs"),
                    IRRead.intg(m, "maxStacks"),
                    Boolean.TRUE.equals(IRRead.bool(m, "individualDur")),
                    Boolean.TRUE.equals(IRRead.bool(m, "dmgMult")),
                    Boolean.TRUE.equals(IRRead.bool(m, "bossOnly")),
                    Boolean.TRUE.equals(IRRead.bool(m, "bossZero")),
                    IRRead.intg(m, "minEnemies"),
                    perMax,
                    IRRead.intg(m, "mpAtLeast"),
                    Boolean.TRUE.equals(IRRead.bool(m, "assumeMax")),
                    IRRead.str(m, "targetMode"),
                    IRRead.str(m, "require"),
                    IRRead.str(m, "priorityGroup"),
                    IRRead.intg(m, "priority"),
                    IRRead.str(m, "target"),
                    IRRead.str(m, "trigger"),
                    IRRead.intg(m, "chance"),
                    m.containsKey("_defer"),
                    m.containsKey("derived"),
                    dFrom,
                    dRatio,
                    dThresh,
                    dStep,
                    dPerStep,
                    dMax,
                    IRRead.intg(m, "critLt"),
                    IRRead.intg(m, "critGte"),
                    ceStep,
                    ceMax,
                    List.copyOf(cm)
            );
        }

        /** JSON 숫자 배열 → List&lt;Double&gt;. null/비배열이면 null. */
        private static List<Double> numList(Object o) {
            if (!(o instanceof List<?> l)) return null;
            List<Double> out = new ArrayList<>();
            for (Object x : l) out.add(((Number) x).doubleValue());
            return List.copyOf(out);
        }

        /** 각성차수(0~5) 값(‰). derived 전용(values null)이면 null. */
        public Double valueAt(int awaken) {
            if (values == null || values.isEmpty()) return null;
            int i = Math.max(0, Math.min(values.size() - 1, awaken));
            return values.get(i);
        }

        /** 각성차수(0~5) 파생 ratio(‰). 없으면 null. */
        public Double derivedRatioAt(int awaken) { return pick(derivedRatio, awaken); }

        /** 각성차수(0~5) 파생 perStep(‰). 없으면 null. */
        public Double derivedPerStepAt(int awaken) { return pick(derivedPerStep, awaken); }

        private static Double pick(List<Double> xs, int awaken) {
            if (xs == null || xs.isEmpty()) return null;
            return xs.get(Math.max(0, Math.min(xs.size() - 1, awaken)));
        }
    }

    /** 컨텐츠별 배수 1건: 현재 컨텐츠가 {@link #content}에 있으면 값 ×{@link #ratio}. */
    public record ContentMult(List<String> content, Double ratio) {}
}
