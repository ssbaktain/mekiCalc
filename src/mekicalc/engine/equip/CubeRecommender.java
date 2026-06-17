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

    /** 한 슬롯의 현재→추천. current null=빈 슬롯. */
    public record SlotPick(PotentialDraw current, PotentialDraw recommended, boolean changed) {}

    /** dedup된 딜증가 옵션 세트. rep=대표 배치, prob=동일옵션 등장확률 총합, repProb=대표 배치 단일 확률. */
    public record OptSet(PotentialDraw[] rep, double deal, double prob, double repProb) {}

    /** 한 장비의 분석 결과(슬라이더 무관, 캐시). curProbSingle=현재 배치 확률, curProbSum=동일옵션 총합. */
    public record EquipOpts(String part, boolean upper, double cost,
                            PotentialDraw[] curDraws, double curDeal, double curProbSingle, double curProbSum,
                            List<OptSet> shown, double[] shownSuffix, double totalProb, int totalCount) {}

    /** 슬라이더 한 지점에서 한 장비의 추천. ProbSingle=단일 배치, ProbSum=동일옵션 등장확률 총합. */
    public record Rec(String part, boolean upper, double cost, List<SlotPick> picks,
                      double curDeal, double curProbSingle, double curProbSum,
                      double recDeal, double recProbSingle, double recProbSum,
                      double dealDiff, double totalProb, double remainProb) {
        // 큐브기댓값·효율은 동일옵션 총합 기준(아무 배치나 나오면 됨).
        public double curCubes() { return curProbSum > 0 ? 1.0 / curProbSum : Double.POSITIVE_INFINITY; }
        public double recCubes() { return recProbSum > 0 ? 1.0 / recProbSum : Double.POSITIVE_INFINITY; }
        public double curEff() { return curDeal * curProbSum / cost; }
        public double recEff() { return recDeal * recProbSum / cost; }
        /** 최종 효율 = 딜 증가량 차이 × 동일옵션 등장확률 총합 ÷ 큐브가격. */
        public double gainEff() { return dealDiff * recProbSum / cost; }
    }

    private CubeRecommender() {}

    /** 한 장비를 분석해 EquipOpts 생성(조합 전부 → dedup → 정렬 → 파레토 → 확률합). */
    public static EquipOpts analyze(DamageModel model, StatBlock current, List<List<PotentialDraw>> slots,
                                    PotentialDraw[] curDraws, String part, boolean upper, double cost) {
        double curFull = model.output(current);
        // 정성적 슬롯(재획득 고정) vs 풀 슬롯(비정성: 옵션 또는 빈). 각 슬롯은 (1−옵션확률합)의 확률로 빈.
        List<Integer> pool = new ArrayList<>();
        boolean[] inPool = new boolean[3];
        double qualProb = 1.0;
        StatBlock normalCurDelta = new StatBlock();
        List<PotentialDraw> normalCurOpts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PotentialDraw d = curDraws[i];
            if (d != null && isQualitative(model, d)) { qualProb *= d.prob() / 100.0; continue; }
            pool.add(i); inPool[i] = true;
            if (d != null) {
                normalCurDelta.set(d.statId(), normalCurDelta.get(d.statId()) + d.value());
                normalCurOpts.add(d);
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
                new java.util.HashSet<>(), setDeal, setOpts);

        List<OptSet> all = new ArrayList<>();
        for (Map.Entry<String, Double> e : setDeal.entrySet()) {
            List<PotentialDraw> opts = setOpts.get(e.getKey());
            double probSum = qualProb * arrangeSum(opts, poolTables);   // 빈 확률 포함 총합
            Best b = bestArrange(opts, poolTables);                      // 최고 확률 배치(표시·단일)
            all.add(new OptSet(buildRep(pool, inPool, opts, b, curDraws), e.getValue(), probSum, qualProb * b.prob));
        }
        all.sort(Comparator.comparingDouble(OptSet::deal));   // 딜 오름차순

        // 전체/접미 확률합
        int n = all.size();
        double[] suffix = new double[n];
        double run = 0;
        for (int i = n - 1; i >= 0; i--) { run += all.get(i).prob(); suffix[i] = run; }
        double totalProb = n > 0 ? suffix[0] : 0;

        // 파레토 지배 숨김: 딜 높은 쪽부터 내려오며 maxProb 초과만 표시
        List<OptSet> shown = new ArrayList<>();
        List<Double> shownSuffixL = new ArrayList<>();
        double maxProb = -1;
        for (int i = n - 1; i >= 0; i--) {
            if (all.get(i).prob() > maxProb + 1e-15) {
                shown.add(all.get(i));
                shownSuffixL.add(suffix[i]);
                maxProb = all.get(i).prob();
            }
        }
        Collections.reverse(shown);                           // 딜 오름차순으로
        Collections.reverse(shownSuffixL);
        double[] shownSuffix = new double[shown.size()];
        for (int i = 0; i < shownSuffix.length; i++) shownSuffix[i] = shownSuffixL.get(i);

        return new EquipOpts(part, upper, cost, curDraws, curDeal, curProbSingle, curProbSum,
                shown, shownSuffix, totalProb, n);
    }

    /** 슬라이더 비율 f(0~1)에서 그 장비의 추천 1개. shown 비어있으면 null. */
    public static Rec pick(EquipOpts eo, double f) {
        int c = eo.shown().size();
        if (c == 0) return null;
        int idx = (c == 1) ? 0 : (int) Math.round(f * (c - 1));
        if (idx < 0) idx = 0; if (idx > c - 1) idx = c - 1;
        OptSet set = eo.shown().get(idx);

        List<SlotPick> picks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PotentialDraw cur = eo.curDraws()[i];
            PotentialDraw rec = set.rep()[i];
            boolean changed = (rec == null) != (cur == null)
                    || (rec != null && cur != null && !(rec.statId().equals(cur.statId()) && rec.value() == cur.value()));
            picks.add(new SlotPick(cur, rec, changed));
        }
        return new Rec(eo.part(), eo.upper(), eo.cost(), picks,
                eo.curDeal(), eo.curProbSingle(), eo.curProbSum(),
                set.deal(), set.repProb(), set.prob(),
                set.deal() - eo.curDeal(), eo.totalProb(), eo.shownSuffix()[idx]);
    }

    /** 풀 슬롯마다 옵션-or-빈을 모두 시도해 딜증가 세트(비빈 옵션 멀티셋)를 dedup 수집. */
    private static void genSets(DamageModel model, StatBlock baseline, double curBase, double curDeal,
                                List<Integer> pool, List<List<PotentialDraw>> slots, int depth, PotentialDraw[] chosen,
                                java.util.Set<String> seen, Map<String, Double> setDeal,
                                Map<String, List<PotentialDraw>> setOpts) {
        if (depth == pool.size()) {
            List<PotentialDraw> opts = new ArrayList<>();
            for (PotentialDraw d : chosen) if (d != null) opts.add(d);
            if (opts.isEmpty()) return;
            String key = setKey(opts);
            if (!seen.add(key)) return;
            StatBlock delta = new StatBlock();
            for (PotentialDraw d : opts) delta.set(d.statId(), delta.get(d.statId()) + d.value());
            double deal = curBase > 0 ? model.output(model.combine(baseline, delta)) / curBase - 1.0 : 0;
            if (deal - curDeal <= 1e-12) return;
            setDeal.put(key, deal);
            setOpts.put(key, opts);
            return;
        }
        chosen[depth] = null;   // 이 풀 슬롯은 빈
        genSets(model, baseline, curBase, curDeal, pool, slots, depth + 1, chosen, seen, setDeal, setOpts);
        for (PotentialDraw d : slots.get(pool.get(depth))) {
            chosen[depth] = d;
            genSets(model, baseline, curBase, curDeal, pool, slots, depth + 1, chosen, seen, setDeal, setOpts);
        }
        chosen[depth] = null;
    }

    /** 옵션 구성 키(슬롯 무관): 옵션들을 정렬해 합침. */
    private static String setKey(List<PotentialDraw> opts) {
        List<String> parts = new ArrayList<>();
        for (PotentialDraw d : opts) parts.add(d.statId() + ":" + d.value());
        Collections.sort(parts);
        return String.join("|", parts);
    }

    /** 대표 배치를 3슬롯 배열로(정성적 슬롯=재획득, 풀 슬롯=배정 옵션 or 빈). */
    private static PotentialDraw[] buildRep(List<Integer> pool, boolean[] inPool, List<PotentialDraw> opts,
                                            Best b, PotentialDraw[] curDraws) {
        PotentialDraw[] rep = new PotentialDraw[3];
        for (int i = 0; i < 3; i++) if (!inPool[i]) rep[i] = curDraws[i];   // 정성적 재획득
        for (int oi = 0; oi < opts.size(); oi++) rep[pool.get(b.slotOf[oi])] = opts.get(oi);
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
        return arrange(opts, 0, new boolean[slotTables.size()], slotTables);
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
