package mekicalc.timeline.ir;

import mekicalc.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 동료 IR 전체 — supporter_manual.json(동료 딜 IR) + supporter_seed.json.inheritFactor(계승/옵션 성장 테이블) 로드본.
 * 규약=data/skills_calc/md/_clt2_SUPPORTER_TAGS.md, 엔진=_clt2_SUPPORTER_ENGINE.md.
 *
 * <p>계승% = Factor[동료레벨][levelFactor]/10 (만렙 90%캡) · 옵션성장배율 = Factor[동료레벨][옵션Factor]/1000.
 * 같은 SupporterLevelStatFactorTable 두 컬럼군을 공유(계승 8~12·옵션 1/4/5/6/7/13).
 */
public final class SupporterBook {

    private final Map<String, SupporterIR> byCode;
    private final Map<Integer, double[]> inheritFactor;   // 동료레벨 → Factor[14열]
    private final GradeBonus uniqueLegendaryOwnFinalDamage;

    /** 유니크/레전 공통 등급보너스(동료 자기 최종뎀, 동료레벨 5/8/10). */
    public record GradeBonus(List<String> grades, List<int[]> byLevel, String stat, String target) {
        /** 동료레벨 lv에서 누적 값(‰). grade가 대상 등급이 아니면 0. */
        public double sumUpToLevel(String grade, int lv) {
            if (grades != null && !grades.contains(grade)) return 0;
            double sum = 0;
            for (int[] lvVal : byLevel) if (lv >= lvVal[0]) sum += lvVal[1];
            return sum;
        }
    }

    private SupporterBook(Map<String, SupporterIR> byCode, Map<Integer, double[]> inheritFactor, GradeBonus gb) {
        this.byCode = Map.copyOf(byCode);
        this.inheritFactor = Map.copyOf(inheritFactor);
        this.uniqueLegendaryOwnFinalDamage = gb;
    }

    @SuppressWarnings("unchecked")
    public static SupporterBook load(Path manualFile, Path seedFile) {
        Map<String, Object> manual = readJson(manualFile);
        Map<String, Object> seed = readJson(seedFile);

        Map<String, SupporterIR> sups = new LinkedHashMap<>();
        for (Map<String, Object> m : IRRead.mapList(manual, "supporters")) {
            SupporterIR ir = SupporterIR.from(m);
            sups.put(ir.code(), ir);
        }

        // inheritFactor: {level(str) -> [14 numbers]}
        Map<Integer, double[]> inh = new LinkedHashMap<>();
        Object rawInh = seed.get("inheritFactor");
        if (rawInh instanceof Map<?, ?> im) {
            for (Map.Entry<?, ?> e : im.entrySet()) {
                int lv = Integer.parseInt(e.getKey().toString().trim());
                List<?> arr = (List<?>) e.getValue();
                double[] f = new double[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    Object v = arr.get(i);   // 덤프 원본이라 문자열 숫자일 수 있음
                    f[i] = (v instanceof Number n) ? n.doubleValue() : Double.parseDouble(v.toString().trim());
                }
                inh.put(lv, f);
            }
        }

        GradeBonus gb = parseGradeBonus(manual);
        return new SupporterBook(sups, inh, gb);
    }

    @SuppressWarnings("unchecked")
    private static GradeBonus parseGradeBonus(Map<String, Object> manual) {
        Object gbRoot = manual.get("_gradeBonus");
        if (!(gbRoot instanceof Map<?, ?> gm)) return null;
        Object ul = ((Map<String, Object>) gm).get("uniqueLegendaryOwnFinalDamage");
        if (!(ul instanceof Map<?, ?> ulm)) return null;
        Map<String, Object> u = (Map<String, Object>) ulm;
        List<String> grades = IRRead.strList(u, "grades");
        List<int[]> byLevel = new ArrayList<>();
        for (Map<String, Object> lv : IRRead.mapList(u, "byCompanionLevel")) {
            Integer l = IRRead.intg(lv, "level");
            Double v = IRRead.dbl(lv, "value");
            if (l != null && v != null) byLevel.add(new int[]{l, (int) Math.round(v)});
        }
        return new GradeBonus(grades, byLevel, IRRead.str(u, "stat"), IRRead.str(u, "target"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(Path file) {
        try {
            return (Map<String, Object>) Json.parse(Files.readString(file));
        } catch (IOException e) {
            throw new RuntimeException("동료 IR 파일 읽기 실패: " + file, e);
        }
    }

    // ── 접근자 ──────────────────────────────────────────
    public SupporterIR supporter(String code) { return byCode.get(code); }
    public java.util.Collection<SupporterIR> all() { return byCode.values(); }
    public GradeBonus gradeBonus() { return uniqueLegendaryOwnFinalDamage; }

    /** 동료레벨의 Factor 배열(14열). 레벨 상한 초과 시 최대 레벨로 클램프. */
    public double[] factorAt(int companionLevel) {
        double[] f = inheritFactor.get(companionLevel);
        if (f != null) return f;
        int max = 0;
        for (int lv : inheritFactor.keySet()) max = Math.max(max, lv);
        return inheritFactor.getOrDefault(Math.max(1, Math.min(companionLevel, max)), new double[14]);
    }

    /** 공격력 계승비율(%) = Factor[동료레벨][levelFactor]/10 (만렙 90%캡은 테이블 값 자체에 반영됨). */
    public double inheritRatioPct(int levelFactor, int companionLevel) {
        double[] f = factorAt(companionLevel);
        return (levelFactor >= 0 && levelFactor < f.length) ? f[levelFactor] / 10.0 : 0.0;
    }

    /** 옵션 성장 배율 = Factor[동료레벨][옵션Factor컬럼]/1000 (Lv1=1.0×). */
    public double optionMult(int optionFactorCol, int companionLevel) {
        double[] f = factorAt(companionLevel);
        return (optionFactorCol >= 0 && optionFactorCol < f.length) ? f[optionFactorCol] / 1000.0 : 1.0;
    }
}
