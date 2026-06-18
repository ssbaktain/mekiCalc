package mekicalc.engine.equip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekicalc.engine.DamageModel;
import mekicalc.model.StatBlock;

/**
 * 큐브 우선순위 추천 엔진 (v5: 딜순위 슬라이더 모델).
 *
 * <p>장비별(윗/아랫 별개)로 <b>현재보다 딜이 오르는 모든 3슬롯 조합</b>을 생성하고, 슬롯 위치만
 * 다르고 옵션 구성이 같은 조합은 하나로 합친다(dedup, 확률은 합산 → 상호배타라 진짜 확률).
 * 딜 오름차순 정렬 후 파레토 지배(더 높은 딜인데 확률도 높은 게 있으면 아래는 숨김)를 표시에서 제거.
 *
 * <p>슬라이더 = 딜 순위 임계값(숨김 제외 옵션 수 최대 장비 기준 분기, 작은 장비는 비율). 한 지점에서
 * 장비마다 그 순위의 옵션 1개를 추천. 정렬은 전체/남은 딜증가 확률합·딜증가량·효율 중 선택.
 */
public final class CubeRecommender {

    /** 한 슬롯의 현재→추천. current null=빈 슬롯. locked=사용자가 잠근 슬롯. */
    public record SlotPick(PotentialDraw current, PotentialDraw recommended, boolean changed, boolean locked) {}

    /** dedup된 딜증가 옵션 세트. grade=이 구성의 등급(승급 추천이면 상위 등급). */
    public record OptSet(PotentialDraw[] rep, double deal, double prob, double repProb, String grade) {}

    /** 한 장비의 분석 결과(슬라이더 무관, 캐시). curGrade=현재 등급. */
    public record EquipOpts(String part, boolean upper, double cost,
                            PotentialDraw[] curDraws, double curDeal, double curProbSingle, double curProbSum,
                            List<OptSet> shown, double[] shownSuffix, double totalProb, int totalCount,
                            boolean[] locked, String curGrade) {}

    /** 슬라이더 한 지점에서 한 장비의 추천. grade=추천 구성 등급, curGrade=현재 등급(다르면 승급 추천). */
    public record Rec(String part, boolean upper, double cost, List<SlotPick> picks,
                      double curDeal, double curProbSingle, double curProbSum,
                      double recDeal, double recProbSingle, double recProbSum,
                      double dealDiff, double totalProb, double remainProb,
                      String grade, String curGrade) {
        public double curCubes() { return curProbSum > 0 ? 1.0 / curProbSum : Double.POSITIVE_INFINITY; }
        public double recCubes() { return recProbSum > 0 ? 1.0 / recProbSum : Double.POSITIVE_INFINITY; }
        public double curEff() { return curDeal * curProbSum / cost; }
        public double recEff() { return recDeal * recProbSum / cost; }
        /** 최종 효율 = 딜 증가량 차이 × 등장 확률(동일 옵션 등장 확률 총합, 승급 반영) ÷ 큐브가격. */
        public double gainEff() { return dealDiff * recProbSum / cost; }
    }

    private CubeRecommender() {}

    /** 한 장비를 분석해 EquipOpts 생성(조합 전부 → dedup → 정렬 → 파레토 → 확률합). */
    public static EquipOpts analyze(DamageModel model, StatBlock current, List<List<PotentialDraw>> slots,
                                    PotentialDraw[] curDraws, String part, boolean upper, double cost) {
        return analyze(model, current, slots, curDraws, part, upper, cost, java.util.Map.of(), null, null, 0, null);
    }

    public static EquipOpts analyze(DamageModel model, StatBlock current, List<List<PotentialDraw>> slots,
                                    PotentialDraw[] curDraws, String part, boolean upper, double cost,
                                    Map<String, double[]> ranges) {
        return analyze(model, current, slots, curDraws, part, upper, cost, ranges, null, null, 0, null);
    }

    public static EquipOpts analyze(DamageModel model, StatBlock current, List<List<PotentialDraw>> slots,
                                    PotentialDraw[] curDraws, String part, boolean upper, double cost,
                                    Map<String, double[]> ranges, boolean[] locked) {
        return analyze(model, current, slots, curDraws, part, upper, cost, ranges, locked, null, 0, null);
    }

    /**
     * 범위 제약 + 슬롯 잠금 + 등급업 체인 포함 분석.
     * grade=현재 등급, stack=현재 스택, ep≠null이면 상위 등급 종결 구성을 "승급 고려 확률"로 후보에 추가.
     */
    public static EquipOpts analyze(DamageModel model, StatBlock current, List<List<PotentialDraw>> slots,
                                    PotentialDraw[] curDraws, String part, boolean upper, double cost,
                                    Map<String, double[]> ranges, boolean[] locked,
                                    String grade, int stack, EquipmentPotential ep) {
        String curGrade = grade != null ? grade : "";
        double curFull = model.output(current);
        // 고정 슬롯(정성적 재획득·잠금) vs 풀 슬롯(돌릴 수 있는 슬롯: 옵션 또는 빈).
        List<Integer> pool = new ArrayList<>();
        boolean[] inPool = new boolean[3];
        double qualProb = 1.0;   // 고정(핀) 슬롯 유지 확률: 정성적 재획득 + 잠긴 빈 슬롯
        StatBlock normalCurDelta = new StatBlock();
        List<PotentialDraw> normalCurOpts = new ArrayList<>();
        List<PotentialDraw> requiredOpts = new ArrayList<>();   // 잠긴 옵션: 반드시 포함(어느 슬롯이든 재획득)
        List<String> keptStats = new ArrayList<>();   // 승급해도 유지할 스탯(잠금+정성적 재획득), 개수 보존(멀티셋)
        for (int i = 0; i < 3; i++) {
            PotentialDraw d = curDraws[i];
            boolean lk = locked != null && i < locked.length && locked[i];
            if (lk && d == null) { qualProb *= emptyProb(slots.get(i)); continue; }            // 잠긴 빈 슬롯: 빈 채 유지
            if (!lk && d != null && isQualitative(model, d)) {                                  // 정성적 재획득
                qualProb *= d.prob() / 100.0; keptStats.add(d.statId()); continue;
            }
            // 일반 슬롯 + 잠긴 옵션 슬롯: 풀에 포함. 잠긴 옵션은 '반드시 포함'으로 표시해 어느 슬롯이든 재획득(any-slot 합산).
            pool.add(i); inPool[i] = true;
            if (d != null) {
                normalCurDelta.set(d.statId(), normalCurDelta.get(d.statId()) + d.value());
                normalCurOpts.add(d);
                if (lk) { requiredOpts.add(d); keptStats.add(d.statId()); }
            }
        }
        List<List<PotentialDraw>> poolTables = new ArrayList<>();
        for (int i : pool) poolTables.add(slots.get(i));

        StatBlock baseline = model.uncombine(current, normalCurDelta);
        double curBase = model.output(baseline);
        double curDeal = curBase > 0 ? curFull / curBase - 1.0 : 0;

        // 기존 단일 = 현재 풀 배치 그대로(옵션 prob / 빈 슬롯 emptyProb), 기존 총합 = 동일 옵션 배치 합산.
        double curProbSingle = qualProb;
        for (int i : pool) {
            PotentialDraw d = curDraws[i];
            curProbSingle *= (d != null) ? d.prob() / 100.0 : emptyProb(slots.get(i));
        }
        double curProbSum = qualProb * arrangeSum(normalCurOpts, poolTables);

        // 딜증가 세트 생성: 풀 슬롯마다 옵션-or-빈 → 딜 계산 → 비빈 옵션 멀티셋으로 dedup.
        Map<String, Double> setDeal = new LinkedHashMap<>();
        Map<String, List<PotentialDraw>> setOpts = new LinkedHashMap<>();
        genSets(model, baseline, curBase, curDeal, pool, slots, 0, new PotentialDraw[pool.size()],
                new java.util.HashSet<>(), setDeal, setOpts, current, ranges, requiredOpts, java.util.List.of());
        // 잠금/현재 상태 때문에 범위를 만족하는 개선 후보가 하나도 없으면(잠금 > 범위 우선),
        // 범위를 무시하고 다시 생성해 best-effort로 추천한다(정성적 스탯과 같은 취급).
        if (setDeal.isEmpty() && ranges != null && !ranges.isEmpty()) {
            genSets(model, baseline, curBase, curDeal, pool, slots, 0, new PotentialDraw[pool.size()],
                    new java.util.HashSet<>(), setDeal, setOpts, current, java.util.Map.of(), requiredOpts, java.util.List.of());
        }

        List<OptSet> all = new ArrayList<>();
        for (Map.Entry<String, Double> e : setDeal.entrySet()) {
            List<PotentialDraw> opts = setOpts.get(e.getKey());
            double probSum = qualProb * arrangeSum(opts, poolTables);   // 빈 확률 포함 총합(any-slot)
            Best b = bestArrange(opts, poolTables);                      // 최고 확률 배치(단일 확률용)
            all.add(new OptSet(buildRep(inPool, locked, opts, curDraws, slots),
                    e.getValue(), probSum, qualProb * b.prob, curGrade));
        }

        // 등급업 체인: 상위 등급 종결 구성을 후보에 추가.
        // 비교축(prob) = "승급 + 옵션 뽑기"의 총 기대 큐브의 역수 = 1 / (E_reach + max(0, 1/arrangeSum − 1)).
        //   E_reach = 현재 등급 → 목표 등급까지 단계별 승급 기대 큐브의 '합'(선형성).
        //   −1: 승급시키는 마지막 큐브가 목표 등급의 첫 옵션 굴림도 겸함 → 옵션 뽑기에서 1큐브 공짜(음수 방지로 0 floor).
        //   같은 등급 후보의 prob(=1/기대큐브)와 같은 단위라 그대로 섞어서 파레토 비교 가능.
        // 다음 등급(한 단계 위) 한 큐브 후보 (deal, arrangeSum) — '한 큐브 스펙업 확률'의 승급 1단계 반영용.
        List<double[]> nextStep = new ArrayList<>();
        if (ep != null && grade != null && ep.gradeIndex(grade) >= 0) {
            StatBlock fullDelta = new StatBlock();   // 이 층의 현재 모든 옵션(승급 시 전부 새로 굴림)
            for (PotentialDraw d : curDraws) if (d != null) fullDelta.set(d.statId(), fullDelta.get(d.statId()) + d.value());
            StatBlock baseFull = model.uncombine(current, fullDelta);
            List<String> grs = ep.grades();
            int ci = ep.gradeIndex(grade);
            double eReach = 0.0;   // 누적 승급 기대 큐브(현재 등급 → 목표 등급)
            for (int gi = ci; gi < grs.size() - 1; gi++) {
                double eStep = ep.gradeUpCubes(grs.get(gi), gi == ci ? Math.max(0, stack) : 0);
                if (eStep <= 0) break;   // 승급 정의 없음(최상위) → 더 못 올라감
                eReach += eStep;
                String to = grs.get(gi + 1);
                List<List<PotentialDraw>> tg = ep.slots(part, to);
                Map<String, Double> sd = new LinkedHashMap<>();
                Map<String, List<PotentialDraw>> so = new LinkedHashMap<>();
                genSets(model, baseFull, curBase, curDeal, java.util.List.of(0, 1, 2), tg, 0,
                        new PotentialDraw[3], new java.util.HashSet<>(), sd, so, current, ranges, java.util.List.of(), keptStats);
                for (Map.Entry<String, Double> e : sd.entrySet()) {
                    List<PotentialDraw> opts = so.get(e.getKey());
                    double pSum = arrangeSum(opts, tg);              // 목표 등급 동일옵션 등장확률 총합
                    double pBest = bestArrange(opts, tg).prob;       // 목표 등급 단일 최고배치(표시용)
                    // 승급시키는 큐브가 새 등급 첫 굴림도 겸함 → 옵션 뽑기에서 1큐브 공짜(−1, 음수 방지).
                    double reach = pSum > 0 ? 1.0 / (eReach + Math.max(0.0, 1.0 / pSum - 1.0)) : 0;
                    double repP = pBest > 0 ? 1.0 / (eReach + Math.max(0.0, 1.0 / pBest - 1.0)) : 0;
                    PotentialDraw[] rep = buildRep(new boolean[]{true, true, true}, null, opts, new PotentialDraw[3], tg);
                    all.add(new OptSet(rep, e.getValue(), reach, repP, to));
                    if (gi == ci) nextStep.add(new double[]{e.getValue(), pSum});   // 한 단계 위 한 큐브 확률
                }
            }
        }

        all.sort(Comparator.comparingDouble(OptSet::deal));   // 딜 오름차순

        // 전체/잔여 = '한 큐브 리롤'로 더 좋은 옵션이 나올 확률(승급 1단계 반영, 항상 0~100%).
        //   g = 이 큐브가 승급할 확률(천장 도달 시 1). 한 큐브 = (1−g)×같은 등급 + g×다음 등급.
        double gEff = 0.0;
        if (ep != null && grade != null && ep.gradeIndex(grade) >= 0) {
            double gRaw = ep.gradeUpProb(grade);
            int ceil = ep.gradeUpCeiling(grade);
            if (gRaw > 0) gEff = (ceil > 0 && stack >= ceil) ? 1.0 : gRaw;
        }
        double sameAll = 0;   // 같은 등급 개선 후보 per-cube 확률 합
        for (OptSet o : all) if (java.util.Objects.equals(o.grade(), curGrade)) sameAll += o.prob();
        double nextAll = 0;   // 다음 등급 한 큐브 개선 확률 합
        for (double[] ns : nextStep) nextAll += ns[1];
        // 전체 = 기존 옵션(현재)보다 좋은 옵션이 한 큐브에 나올 확률(모든 후보가 현재보다 좋음).
        double totalProb = (1 - gEff) * sameAll + gEff * nextAll;

        int n = all.size();
        // 파레토 지배 숨김 + 각 표시 옵션의 '잔여(추천 옵션보다 좋은) 한 큐브 확률'.
        List<OptSet> shown = new ArrayList<>();
        List<Double> shownRemainL = new ArrayList<>();
        double maxProb = -1;
        for (int i = n - 1; i >= 0; i--) {
            if (all.get(i).prob() > maxProb + 1e-15) {
                shown.add(all.get(i));
                double d = all.get(i).deal();
                double sameGt = 0, nextGt = 0;
                for (OptSet o : all)
                    if (java.util.Objects.equals(o.grade(), curGrade) && o.deal() > d + 1e-12) sameGt += o.prob();
                for (double[] ns : nextStep) if (ns[0] > d + 1e-12) nextGt += ns[1];
                shownRemainL.add((1 - gEff) * sameGt + gEff * nextGt);
                maxProb = all.get(i).prob();
            }
        }
        Collections.reverse(shown);                           // 딜 오름차순으로
        Collections.reverse(shownRemainL);
        double[] shownSuffix = new double[shown.size()];
        for (int i = 0; i < shownSuffix.length; i++) shownSuffix[i] = shownRemainL.get(i);

        return new EquipOpts(part, upper, cost, curDraws, curDeal, curProbSingle, curProbSum,
                shown, shownSuffix, totalProb, n, locked != null ? locked : new boolean[3], curGrade);
    }

    /** 슬라이더 비율 f(0~1)에서 그 장비의 추천 1개. shown 비어있으면 null. */
    public static Rec pick(EquipOpts eo, double f) {
        int c = eo.shown().size();
        if (c == 0) return null;
        int idx = (c == 1) ? 0 : (int) Math.round(f * (c - 1));
        if (idx < 0) idx = 0; if (idx > c - 1) idx = c - 1;
        OptSet set = eo.shown().get(idx);

        boolean crossGrade = set.grade() != null && !set.grade().equals(eo.curGrade());
        // 잠긴 스탯 개수(승급 추천 표시용): 같은 스탯이라도 잠근 '개수'만큼만 (잠금) 표시.
        Map<String, Integer> lockLeft = new LinkedHashMap<>();
        if (eo.locked() != null) {
            for (int i = 0; i < 3; i++) {
                if (eo.locked()[i] && eo.curDraws()[i] != null) lockLeft.merge(eo.curDraws()[i].statId(), 1, Integer::sum);
            }
        }
        List<SlotPick> picks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PotentialDraw cur = eo.curDraws()[i];
            PotentialDraw rec = set.rep()[i];
            boolean lockedSlot;
            if (crossGrade) {   // 승급: 잠긴 스탯이 올라온 슬롯에 잠근 개수만큼만 표시
                lockedSlot = rec != null && lockLeft.getOrDefault(rec.statId(), 0) > 0;
                if (lockedSlot) lockLeft.merge(rec.statId(), -1, Integer::sum);
            } else {
                lockedSlot = eo.locked() != null && i < eo.locked().length && eo.locked()[i];
            }
            boolean changed = !lockedSlot && ((rec == null) != (cur == null)
                    || (rec != null && cur != null && !(rec.statId().equals(cur.statId()) && rec.value() == cur.value())));
            picks.add(new SlotPick(cur, rec, changed, lockedSlot));
        }
        return new Rec(eo.part(), eo.upper(), eo.cost(), picks,
                eo.curDeal(), eo.curProbSingle(), eo.curProbSum(),
                set.deal(), set.repProb(), set.prob(),
                set.deal() - eo.curDeal(), eo.totalProb(), eo.shownSuffix()[idx],
                set.grade(), eo.curGrade());
    }

    /** 풀 슬롯마다 옵션-or-빈을 모두 시도해 딜증가 세트(비빈 옵션 멀티셋)를 dedup 수집. */
    private static void genSets(DamageModel model, StatBlock baseline, double curBase, double curDeal,
                                List<Integer> pool, List<List<PotentialDraw>> slots, int depth, PotentialDraw[] chosen,
                                java.util.Set<String> seen, Map<String, Double> setDeal,
                                Map<String, List<PotentialDraw>> setOpts,
                                StatBlock current, Map<String, double[]> ranges, List<PotentialDraw> required,
                                List<String> requiredStats) {
        if (depth == pool.size()) {
            List<PotentialDraw> opts = new ArrayList<>();
            for (PotentialDraw d : chosen) if (d != null) opts.add(d);
            if (opts.isEmpty()) return;
            if (!containsAllOpts(opts, required)) return;        // 잠긴 옵션(같은 등급): stat+value로 반드시 포함
            if (!containsAllStats(opts, requiredStats)) return;  // 유지 스탯(승급): stat만 반드시 포함(수치는 그 등급값)
            String key = setKey(opts);
            if (!seen.add(key)) return;
            StatBlock delta = new StatBlock();
            for (PotentialDraw d : opts) delta.set(d.statId(), delta.get(d.statId()) + d.value());
            StatBlock combined = model.combine(baseline, delta);
            if (!withinRanges(combined, current, ranges)) return;   // 허용 범위 밖 후보 제외
            double deal = curBase > 0 ? model.output(combined) / curBase - 1.0 : 0;
            if (deal - curDeal <= 1e-12) return;
            setDeal.put(key, deal);
            setOpts.put(key, opts);
            return;
        }
        chosen[depth] = null;   // 이 풀 슬롯은 빈
        genSets(model, baseline, curBase, curDeal, pool, slots, depth + 1, chosen, seen, setDeal, setOpts, current, ranges, required, requiredStats);
        for (PotentialDraw d : slots.get(pool.get(depth))) {
            chosen[depth] = d;
            genSets(model, baseline, curBase, curDeal, pool, slots, depth + 1, chosen, seen, setDeal, setOpts, current, ranges, required, requiredStats);
        }
        chosen[depth] = null;
    }

    /** opts가 requiredStats의 각 스탯을 '필요 개수만큼'(수치 무관) 포함하는지(멀티셋). */
    private static boolean containsAllStats(List<PotentialDraw> opts, List<String> requiredStats) {
        if (requiredStats == null || requiredStats.isEmpty()) return true;
        Map<String, Integer> need = new LinkedHashMap<>();
        for (String s : requiredStats) need.merge(s, 1, Integer::sum);
        Map<String, Integer> have = new LinkedHashMap<>();
        for (PotentialDraw d : opts) have.merge(d.statId(), 1, Integer::sum);
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            if (have.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    /** opts(멀티셋)가 required 옵션들을 모두 포함하는지(stat+value 기준). */
    private static boolean containsAllOpts(List<PotentialDraw> opts, List<PotentialDraw> required) {
        if (required == null || required.isEmpty()) return true;
        List<PotentialDraw> rest = new ArrayList<>(opts);
        for (PotentialDraw r : required) {
            if (!removeOnce(rest, r)) return false;
        }
        return true;
    }

    /** list에서 o와 같은(stat+value) 첫 항목 1개 제거. 제거했으면 true. */
    private static boolean removeOnce(List<PotentialDraw> list, PotentialDraw o) {
        for (int i = 0; i < list.size(); i++) {
            PotentialDraw d = list.get(i);
            if (d.statId().equals(o.statId()) && d.value() == o.value()) { list.remove(i); return true; }
        }
        return false;
    }

    private static boolean isLockedAt(boolean[] locked, int i) {
        return locked != null && i < locked.length && locked[i];
    }

    /**
     * 추천 후보(combined)의 각 고려 스탯 결과값이 [현재값+lo, 현재값+hi] 안인지.
     * ranges가 비어 있으면 항상 true(기존 동작).
     */
    private static boolean withinRanges(StatBlock combined, StatBlock current, Map<String, double[]> ranges) {
        if (ranges == null || ranges.isEmpty()) return true;
        for (Map.Entry<String, double[]> e : ranges.entrySet()) {
            double val = combined.get(e.getKey());
            double base = current.get(e.getKey());
            if (val < base + e.getValue()[0] - 1e-9 || val > base + e.getValue()[1] + 1e-9) return false;
        }
        return true;
    }

    /** 옵션 구성 키(슬롯 무관): 옵션들을 정렬해 합침. */
    private static String setKey(List<PotentialDraw> opts) {
        List<String> parts = new ArrayList<>();
        for (PotentialDraw d : opts) parts.add(d.statId() + ":" + d.value());
        Collections.sort(parts);
        return String.join("|", parts);
    }

    /**
     * 표시용 대표 배치(확률 계산과 무관, 보기용). 고정해야 하는 슬롯은 제자리에, 나머지 새 옵션은
     * 확률이 가장 높은 배치로 보여준다(동일 옵션이라도 헷갈리지 않게).
     * 고정: ①비풀 슬롯(정성적 재획득·잠긴 빈) ②잠긴 옵션 ③기존과 동일한 옵션(변경 없음).
     */
    private static PotentialDraw[] buildRep(boolean[] inPool, boolean[] locked, List<PotentialDraw> opts,
                                            PotentialDraw[] curDraws, List<List<PotentialDraw>> slots) {
        PotentialDraw[] rep = new PotentialDraw[3];
        List<PotentialDraw> remaining = new ArrayList<>(opts);
        for (int i = 0; i < 3; i++) if (!inPool[i]) rep[i] = curDraws[i];   // ① 정성적 재획득 / 잠긴 빈
        for (int i = 0; i < 3; i++) {                                       // ② 잠긴 옵션: 원래 위치 고정
            if (rep[i] == null && inPool[i] && isLockedAt(locked, i) && curDraws[i] != null
                    && removeOnce(remaining, curDraws[i])) rep[i] = curDraws[i];
        }
        for (int i = 0; i < 3; i++) {                                       // ③ 기존과 동일(변경 없음): 제자리 표시
            if (rep[i] == null && inPool[i] && curDraws[i] != null
                    && removeOnce(remaining, curDraws[i])) rep[i] = curDraws[i];
        }
        // ④ 남은 새 옵션: 자유 슬롯에 최고 확률 배치로 표시
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < 3; i++) if (rep[i] == null && inPool[i]) free.add(i);
        List<List<PotentialDraw>> freeTables = new ArrayList<>();
        for (int i : free) freeTables.add(slots.get(i));
        Best b = bestArrange(remaining, freeTables);
        if (b.prob > 0 && b.slotOf.length == remaining.size()) {
            for (int oi = 0; oi < remaining.size(); oi++) rep[free.get(b.slotOf[oi])] = remaining.get(oi);
        } else {                                                           // 폴백: 좌→우
            int ri = 0;
            for (int i : free) { if (ri >= remaining.size()) break; rep[i] = remaining.get(ri++); }
        }
        return rep;
    }

    private static boolean isQualitative(DamageModel model, PotentialDraw d) {
        return model.hasQualitative(new StatBlock().with(d.statId(), d.value()));
    }

    /** 슬롯이 빈(무의미)으로 나올 확률 = 1 − 옵션 확률 합. */
    public static double emptyProb(List<PotentialDraw> table) {
        double s = 0;
        for (PotentialDraw d : table) s += d.prob() / 100.0;
        return Math.max(0, 1.0 - s);
    }

    /** 최고 확률 배치(표시·단일 등장확률용). slotOf[옵션idx] = 풀 테이블 인덱스. */
    private static final class Best { double prob = -1; int[] slotOf = new int[0]; }

    private static Best bestArrange(List<PotentialDraw> opts, List<List<PotentialDraw>> tables) {
        Best b = new Best();
        bestRec(opts, 0, new boolean[tables.size()], new int[opts.size()], 1.0, tables, b);
        return b;
    }

    private static void bestRec(List<PotentialDraw> opts, int idx, boolean[] used, int[] cur, double prob,
                                List<List<PotentialDraw>> tables, Best b) {
        if (idx == opts.size()) {
            double pe = prob;
            for (int s = 0; s < tables.size(); s++) if (!used[s]) pe *= emptyProb(tables.get(s));
            if (pe > b.prob) { b.prob = pe; b.slotOf = cur.clone(); }
            return;
        }
        PotentialDraw o = opts.get(idx);
        for (int s = 0; s < tables.size(); s++) {
            if (used[s]) continue;
            double p = probInTable(o, tables.get(s));
            if (p <= 0) continue;
            used[s] = true; cur[idx] = s;
            bestRec(opts, idx + 1, used, cur, prob * p, tables, b);
            used[s] = false;
        }
    }

    /**
     * 동일 옵션 등장 확률 총합: 옵션들을 서로 다른 슬롯(테이블)에 배치하는 모든 경우의 확률 곱 합.
     * 슬롯마다 옵션 테이블·확률이 달라 배치마다 확률이 다르므로 전부 더한다(각 배치는 상호배타).
     */
    public static double arrangeSum(List<PotentialDraw> opts, List<List<PotentialDraw>> slotTables) {
        // arrange()는 옵션 리스트의 '순서'대로 슬롯에 배치하므로, 동일 옵션(스탯+수치)이 여럿이면
        // 같은 결과를 순열 수만큼 중복으로 더한다 → ∏(mi!)로 나눠 보정(안 하면 확률 합이 100%를 넘음).
        return arrange(opts, 0, new boolean[slotTables.size()], slotTables) / identicalPermFactor(opts);
    }

    /** 동일 옵션(스탯+수치) 중복 보정 배수 ∏(mi!). 모두 서로 다르면 1. */
    private static double identicalPermFactor(List<PotentialDraw> opts) {
        Map<String, Integer> cnt = new LinkedHashMap<>();
        for (PotentialDraw d : opts) cnt.merge(d.statId() + "=" + d.value(), 1, Integer::sum);
        double f = 1.0;
        for (int mlt : cnt.values()) for (int k = 2; k <= mlt; k++) f *= k;
        return f;
    }

    private static double arrange(List<PotentialDraw> opts, int idx, boolean[] used,
                                  List<List<PotentialDraw>> tables) {
        if (idx == opts.size()) {
            double pe = 1.0;   // 남은(미사용) 슬롯은 빈 → 빈 확률 곱
            for (int s = 0; s < tables.size(); s++) if (!used[s]) pe *= emptyProb(tables.get(s));
            return pe;
        }
        double sum = 0;
        PotentialDraw o = opts.get(idx);
        for (int s = 0; s < tables.size(); s++) {
            if (used[s]) continue;
            double p = probInTable(o, tables.get(s));
            if (p <= 0) continue;
            used[s] = true;
            sum += p * arrange(opts, idx + 1, used, tables);
            used[s] = false;
        }
        return sum;
    }

    private static double probInTable(PotentialDraw o, List<PotentialDraw> table) {
        for (PotentialDraw d : table)
            if (d.statId().equals(o.statId()) && d.value() == o.value()) return d.prob() / 100.0;
        return 0;
    }
}
