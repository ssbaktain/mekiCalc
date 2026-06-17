package mekicalc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mekicalc.config.CharacterIO;
import mekicalc.config.GameData;
import mekicalc.engine.AcquisitionOption;
import mekicalc.engine.Recommender;
import mekicalc.model.Profile;

/**
 * 메이플 키우기 스탯 효율 계산기 — CLI 데모.
 *
 * 실행:
 *   javac -d out (모든 .java)
 *   java -cp out mekicalc.Main [dataDir]   # dataDir 기본값 "data"
 *   java -cp out mekicalc.Main --selftest  # 자체 검증
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--selftest")) {
            SelfTest.run();
            return;
        }
        if (args.length > 0 && args[0].equals("--equip")) {
            printEquip(args.length > 1 ? args[1] : "장갑", args.length > 2 ? args[2] : "MYTHIC");
            return;
        }
        if (args.length > 0 && args[0].equals("--input")) {
            inputStats();
            return;
        }
        if (args.length > 0 && args[0].equals("--equip-eval")) {
            if (args.length < 3) {
                System.out.println("사용법: --equip-eval <부위> <등급> [윗|아랫]");
                return;
            }
            equipEval(args[1], args[2], args.length > 3 ? args[3] : "윗");
            return;
        }

        Path dataDir = Path.of(args.length > 0 ? args[0] : "data");
        GameData g = GameData.load(dataDir);
        Recommender rec = new Recommender(g.model, g.sources, g.currencyValues);

        Profile p = g.profile;

        System.out.println("========================================");
        System.out.println(" 메이플 키우기 스탯 효율 계산기");
        System.out.println("========================================");
        System.out.printf(" 시나리오: 일반몹 대상 기본 공격 / 데미지중첩 %s%n",
                p.additiveDamage ? "합연산(B)" : "곱연산(A)");
        System.out.printf(" 예상 데미지(기본공격)  일반몹: %,.0f  |  보스몹: %,.0f%n",
                g.model.finalDamageNormal(g.current), g.model.finalDamageBoss(g.current));

        System.out.println();
        System.out.println(" [데미지 분해] (공통배수, ×(1+일반/보스몹데미지)하면 각 대상 데미지)");
        for (Map.Entry<String, Double> e : g.model.breakdown(g.current).entrySet()) {
            System.out.printf("   %-12s × %,.4f%n", e.getKey(), e.getValue());
        }
        System.out.println();

        // ── 기능 1: 단순 데미지 증가량 ──
        System.out.println("[기능 1] 현재 대비 최종 데미지 증가량 (확률·비용 미반영)");
        System.out.println("----------------------------------------");
        List<AcquisitionOption> byDmg = rec.byDamageOnly(g.current);
        for (AcquisitionOption o : byDmg) {
            System.out.printf(" %-12s | %-30s | 최종뎀 %+6.2f%%%n",
                    o.sourceName, o.label, o.damageIncrease * 100);
        }
        System.out.println();

        // ── 기능 2: 기댓값 포함 비교 ──
        System.out.println("[기능 2] 뽑힐 기댓값 포함 비교");
        System.out.println("----------------------------------------");
        for (AcquisitionOption o : byDmg) {
            System.out.printf(" %-12s | %-30s | 증가 %+6.2f%% | %s%n",
                    o.sourceName, o.label, o.damageIncrease * 100, o.costDescription);
        }
        System.out.println();

        // ── 기능 3: 효율 우선순위 ──
        System.out.println("[기능 3] 우선순위 추천 (효율 = 데미지증가율 / 기대비용)");
        System.out.println("----------------------------------------");
        int rank = 1;
        for (AcquisitionOption o : rec.byEfficiency(g.current)) {
            System.out.printf(" %2d위. %-12s %-30s | 증가 %+6.2f%% | 기대비용 %8.1f | 효율 %.5f%n",
                    rank++, o.sourceName, o.label,
                    o.damageIncrease * 100, o.expectedCost, o.efficiency() * 100);
        }
        System.out.println();

        // ── 정성적 고효율 후보 (공속/방관 등 수치 미산정) ──
        List<AcquisitionOption> qual = rec.qualitativeOptions(g.current);
        if (!qual.isEmpty()) {
            System.out.println("[참고] 정성적 고효율 후보 — 데미지 환산 안 함(공속/방관 등)");
            System.out.println("----------------------------------------");
            for (AcquisitionOption o : qual) {
                System.out.printf(" %-12s | %-30s | 기대비용 %8.1f | 수치 높게 뜨면 고효율로 간주%n",
                        o.sourceName, o.label, o.expectedCost);
            }
            System.out.println();
        }

        System.out.println("※ 효율 단위: 기대비용 1당 데미지 증가율(%). 재화는 currency.json 가중치로 정규화.");
        System.out.println("※ 데미지 미환산: 공격속도·방어관통(식·적방어 불명) → 정성적 판단. 대상수증가·쿨감(별도 모델 필요).");
    }

    /** 장비 잠재 표 출력: java -cp out mekicalc.Main --equip [부위] [등급] */
    private static void printEquip(String part, String grade) throws Exception {
        mekicalc.engine.equip.EquipmentPotential ep =
                mekicalc.engine.equip.EquipmentPotential.load(Path.of("data"));
        System.out.printf("=== 장비 잠재: %s / %s (윗잠=아랫잠 동일) ===%n", part, grade);
        List<List<mekicalc.engine.equip.PotentialDraw>> slots = ep.slots(part, grade);
        for (int s = 0; s < slots.size(); s++) {
            System.out.printf("%n[%d슬롯]%n", s + 1);
            double sum = 0;
            for (mekicalc.engine.equip.PotentialDraw d : slots.get(s)) {
                System.out.printf("   %s%-22s %8s  %5.2f%%%n",
                        d.special() ? "★" : "  ", d.statId(), fmt(d.value()), d.prob());
                sum += d.prob();
            }
            System.out.printf("   (표기 합 %.2f%% — 나머지는 무의미 옵션 생략)%n", sum);
        }
    }

    private static String fmt(double v) {
        return (v == Math.floor(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** 대화형 스탯 입력: 각 스탯에 대해 현재값을 보여주고, 입력하면 갱신(빈칸=유지). */
    private static void inputStats() throws Exception {
        Path dataDir = Path.of("data");
        GameData g = GameData.load(dataDir);
        mekicalc.model.StatBlock b = new mekicalc.model.StatBlock(g.current);
        java.util.Scanner sc = new java.util.Scanner(System.in);

        System.out.println("=== 내 스탯 입력 (Enter=현재값 유지) ===");
        for (mekicalc.model.StatDef def : g.stats.values()) {
            System.out.printf(" %s [%s]: ", def.name, fmt(b.get(def.id)));
            String line = sc.hasNextLine() ? sc.nextLine().trim() : "";
            if (!line.isEmpty()) {
                try { b.set(def.id, Double.parseDouble(line)); }
                catch (NumberFormatException e) { System.out.println("   (숫자 아님 → 유지)"); }
            }
        }
        CharacterIO.save(dataDir, b, g.stats.keySet());
        System.out.println("\n저장 완료: data/character.json");
        System.out.printf("현재 최종 데미지 지표: %,.0f%n", g.model.finalDamage(b));
    }

    /** 부위·등급의 각 잠재 옵션에 대해 딜 증가량 + 큐브 기댓값을 출력. */
    private static void equipEval(String part, String grade, String which) throws Exception {
        Path dataDir = Path.of("data");
        GameData g = GameData.load(dataDir);
        mekicalc.engine.equip.EquipmentPotential ep = mekicalc.engine.equip.EquipmentPotential.load(dataDir);
        String cube = which.startsWith("아") ? "에디셔널큐브" : "미라클큐브";

        List<mekicalc.engine.equip.CubeEvaluator.Result> res =
                mekicalc.engine.equip.CubeEvaluator.evaluate(ep, g.model, g.current, part, grade);
        // 딜 증가량 내림차순(정성적은 뒤로)
        res.sort((a, b) -> {
            double da = Double.isNaN(a.damageIncrease()) ? -1 : a.damageIncrease();
            double db = Double.isNaN(b.damageIncrease()) ? -1 : b.damageIncrease();
            return Double.compare(db, da);
        });

        System.out.printf("=== %s / %s / %s (%s 1개/굴림) ===%n", part, grade, which + "잠", cube);
        System.out.printf(" 현재 최종 데미지 지표: %,.0f%n", g.model.finalDamage(g.current));
        System.out.println(" " + "-".repeat(64));
        System.out.printf(" %-2s %-22s %8s | %-10s | %s%n", "", "옵션", "수치", "딜 증가량", "큐브 기댓값(등장까지)");
        System.out.println(" " + "-".repeat(64));
        for (mekicalc.engine.equip.CubeEvaluator.Result r : res) {
            String name = g.stats.get(r.statId()) != null ? g.stats.get(r.statId()).name : r.statId();
            String inc = r.qualitative() ? "정성적" : String.format("%+.2f%%", r.damageIncrease() * 100);
            System.out.printf(" %s %-22s %8s | %-10s | 평균 %6.1f개 (등장 %.2f%%)%n",
                    r.special() ? "★" : "  ", name, fmt(r.value()), inc, r.expectedCubes(), r.pPerCube() * 100);
        }
        System.out.println("\n※ 큐브 기댓값 = 3줄 중 한 줄에라도 그 옵션이 뜰 때까지 평균 큐브 수(기하분포 1/P).");
        System.out.println("※ 딜 증가량 = 그 옵션 한 줄을 얻었다고 가정한 최종 데미지 증가율. '정성적'=공속/방관 등 미환산.");
    }
}
