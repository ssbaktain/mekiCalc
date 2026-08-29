package mekicalc.timeline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 타임라인 분석(5단계) — TreeMap(슬롯)과 역인덱스로 전체/스킬별/구간별 딜·DPS를 낸다.
 * 원본을 변형하지 않고 계산 시 base 프리셋만 주입한다.
 */
public final class Analyzer {

    private final Timeline tl;
    private final Preset base;
    private final double monsterDef;
    private final boolean boss;
    private final int simMs;

    public Analyzer(Timeline tl, Preset base, double monsterDef, boolean boss, int simMs) {
        this.tl = tl;
        this.base = base;
        this.monsterDef = monsterDef;
        this.boss = boss;
        this.simMs = simMs;
    }

    /** 전 구간 총 딜. */
    public double totalDamage() {
        return rangeDamage(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** [fromMs, toMs) 구간 총 딜. */
    public double rangeDamage(int fromMs, int toMs) {
        double sum = 0;
        for (Slot s : tl.slots().subMap(fromMs, true, toMs, false).values()) {
            for (DealEvent d : s.deals())
                sum += DamageFormula.dealDamage(base, s.mods(), d, monsterDef, boss);
        }
        return sum;
    }

    /** 특정 스킬의 총 딜(역인덱스로 그 스킬 딜만). */
    public double skillDamage(int skillIdx) {
        double sum = 0;
        for (int t : tl.timesOf(skillIdx)) {
            Slot s = tl.slots().get(t);
            for (DealEvent d : s.deals())
                if (d.skillIdx() == skillIdx)
                    sum += DamageFormula.dealDamage(base, s.mods(), d, monsterDef, boss);
        }
        return sum;
    }

    /** 특정 스킬의 총 타수(버프 무관, hits 합). 1타 평균 = 총딜/총타수. */
    public long skillHits(int skillIdx) {
        long h = 0;
        for (int t : tl.timesOf(skillIdx)) {
            for (DealEvent d : tl.slots().get(t).deals())
                if (d.skillIdx() == skillIdx) h += d.hits();
        }
        return h;
    }

    /** 특정 스킬의 시전 횟수 = 즉발(비도트) 딜이 발생한 서로 다른 시각 수. 도트/주기 틱은 제외(1회 시전으로 침). */
    public long skillCasts(int skillIdx) {
        long c = 0;
        for (int t : tl.timesOf(skillIdx)) {
            for (DealEvent d : tl.slots().get(t).deals())
                if (d.skillIdx() == skillIdx && !d.dotTick()) { c++; break; }
        }
        return c;
    }

    public double dps() { return totalDamage() / (simMs / 1000.0); }

    /** 특정 시각 t에 특정 스킬이 넣은 딜(그 슬롯의 해당 스킬 deal 합). 툴팁 순간 데미지용. */
    public double skillDamageAt(int skillIdx, int t) {
        Slot s = tl.slots().get(t);
        if (s == null) return 0;
        double sum = 0;
        for (DealEvent d : s.deals())
            if (d.skillIdx() == skillIdx) sum += DamageFormula.dealDamage(base, s.mods(), d, monsterDef, boss);
        return sum;
    }

    /**
     * 액션 기준 순간 DPS — 각 액션을 창의 '중앙'에 두어 실제 딜 시점과 곡선이 맞도록(후행 아님).
     * 창 = [이전 액션과의 중점, 다음 액션과의 중점) (양끝은 0 / simMs). dps = 창 딜 / 창초. 슬롯 1회 순회(투포인터).
     */
    public double[] actionIntervalDps(int[] a) {
        int m = a.length;
        double[] out = new double[m];
        if (m == 0) return out;
        int[] lo = new int[m], hi = new int[m];
        for (int i = 0; i < m; i++) {
            lo[i] = i > 0 ? (a[i - 1] + a[i]) / 2 : 0;           // 이전과의 중점(현재 액션이 가운데 오게)
            hi[i] = i < m - 1 ? (a[i] + a[i + 1]) / 2 : simMs;   // 다음과의 중점
        }
        double[] dmg = new double[m];
        int ai = 0;
        for (Map.Entry<Integer, Slot> en : tl.slots().entrySet()) {
            int t = en.getKey();
            if (t < 0 || t >= simMs) continue;
            while (ai < m - 1 && t >= hi[ai]) ai++;              // t가 속한 창으로(연속 창이라 단조 전진)
            double dd = 0;
            for (DealEvent d : en.getValue().deals())
                dd += DamageFormula.dealDamage(base, en.getValue().mods(), d, monsterDef, boss);
            dmg[ai] += dd;
        }
        for (int i = 0; i < m; i++) {
            double sec = (hi[i] - lo[i]) / 1000.0;
            out[i] = sec > 0 ? dmg[i] / sec : 0;
        }
        return out;
    }

    /**
     * 순간 DPS 시계열 — sampleMs 간격으로 표본, 각 표본은 windowMs 슬라이딩 윈도우의 딜/윈도우초.
     * 고정 bin(딜 사이 빈칸=0) 대신 윈도우라 표본마다 최근 타격을 포함 → 0 구간 없이 부드러운 순간 DPS.
     * 슬롯을 시간순 배열로 만든 뒤 투포인터로 윈도우 합 유지(O(슬롯+표본)). index i 중심 = i*sampleMs + sampleMs/2.
     */

    public double[] dpsSeries(int sampleMs, int windowMs) {
        if (sampleMs <= 0) sampleMs = 500;
        if (windowMs <= 0) windowMs = 2000;
        int n = Math.max(1, (int) Math.ceil((double) simMs / sampleMs));
        // 슬롯별 딜을 시간순 배열로(0..sim 범위만)
        int cap = tl.slots().size();
        int[] times = new int[cap];
        double[] dmg = new double[cap];
        int m = 0;
        for (Map.Entry<Integer, Slot> en : tl.slots().entrySet()) {
            int t = en.getKey();
            if (t < 0 || t >= simMs) continue;
            double dd = 0;
            for (DealEvent d : en.getValue().deals())
                dd += DamageFormula.dealDamage(base, en.getValue().mods(), d, monsterDef, boss);
            times[m] = t; dmg[m] = dd; m++;
        }
        double[] out = new double[n];
        int lo = 0, hi = 0; double sum = 0;
        for (int i = 0; i < n; i++) {
            int center = i * sampleMs + sampleMs / 2;
            int a = Math.max(0, center - windowMs / 2), b = Math.min(simMs, center + windowMs / 2);
            while (hi < m && times[hi] < b) sum += dmg[hi++];   // 윈도우 우측 포함
            while (lo < m && times[lo] < a) sum -= dmg[lo++];   // 윈도우 좌측 제외
            double sec = (b - a) / 1000.0;
            out[i] = sec > 0 ? sum / sec : 0;
        }
        return out;
    }

    /** 스킬별 총딜 맵(내림차순). */
    public Map<Integer, Double> perSkillDamage() {
        Map<Integer, Double> raw = new LinkedHashMap<>();
        for (int idx : tl.dealingSkills()) raw.put(idx, skillDamage(idx));
        return raw.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .collect(LinkedHashMap::new, (mp, e) -> mp.put(e.getKey(), e.getValue()), LinkedHashMap::putAll);
    }
}
