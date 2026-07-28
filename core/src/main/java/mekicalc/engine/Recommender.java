package mekicalc.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import mekicalc.engine.source.StatSource;
import mekicalc.model.StatBlock;

/**
 * 세 가지 기능을 한 곳에서 제공하는 추천기.
 *
 * <ol>
 *   <li>{@link #evaluate}      : 모든 수단의 선택지를 현재 스탯 기준으로 평가(데미지 증가율 채움)</li>
 *   <li>{@link #byDamageOnly}  : 기능1 — 데미지 증가율만으로 정렬(기댓값/비용 무시)</li>
 *   <li>{@link #byEfficiency}  : 기능3 — 기대비용 대비 효율로 정렬(우선순위 추천)</li>
 * </ol>
 */
public final class Recommender {

    private final DamageModel model;
    private final List<StatSource> sources;
    private final Map<String, Double> currencyValues;

    public Recommender(DamageModel model, List<StatSource> sources, Map<String, Double> currencyValues) {
        this.model = model;
        this.sources = sources;
        this.currencyValues = currencyValues;
    }

    /** 모든 소스의 모든 선택지를 평가해 데미지 증가율/정성적 플래그를 채운 목록을 반환. */
    public List<AcquisitionOption> evaluate(StatBlock current) {
        List<AcquisitionOption> all = new ArrayList<>();
        for (StatSource s : sources) {
            for (AcquisitionOption opt : s.options(currencyValues)) {
                opt.qualitative = model.hasQualitative(opt.delta);
                opt.damageIncrease = model.increase(current, opt.delta);
                all.add(opt);
            }
        }
        return all;
    }

    /** 데미지로 환산 가능한(정성적 아님) 선택지만. */
    private List<AcquisitionOption> numeric(StatBlock current) {
        List<AcquisitionOption> out = new ArrayList<>();
        for (AcquisitionOption o : evaluate(current)) if (!o.qualitative) out.add(o);
        return out;
    }

    /** 기능1: 단순 데미지 증가율 내림차순(정성적 제외). */
    public List<AcquisitionOption> byDamageOnly(StatBlock current) {
        List<AcquisitionOption> all = numeric(current);
        all.sort(Comparator.comparingDouble((AcquisitionOption o) -> o.damageIncrease).reversed());
        return all;
    }

    /** 기능3: 효율(기대비용 대비 증가율) 내림차순(정성적 제외). */
    public List<AcquisitionOption> byEfficiency(StatBlock current) {
        List<AcquisitionOption> all = numeric(current);
        all.sort(Comparator.comparingDouble(AcquisitionOption::efficiency).reversed());
        return all;
    }

    /** 공속/방관 등 수치 미산정 선택지(정성적 고효율 후보). 기대비용 오름차순. */
    public List<AcquisitionOption> qualitativeOptions(StatBlock current) {
        List<AcquisitionOption> out = new ArrayList<>();
        for (AcquisitionOption o : evaluate(current)) if (o.qualitative) out.add(o);
        out.sort(Comparator.comparingDouble(o -> o.expectedCost));
        return out;
    }
}
