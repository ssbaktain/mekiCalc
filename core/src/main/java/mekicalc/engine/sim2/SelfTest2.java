package mekicalc.engine.sim2;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mekicalc.engine.DamageModel;
import mekicalc.model.Profile;
import mekicalc.model.StatBlock;
import mekicalc.model.StatDef;
import mekicalc.model.StatDef.Combine;

/**
 * sim2 자가 테스트 — 합성 시나리오로 수학적 기대값과 대조한다(외부 데이터 불필요).
 * 실패 시 비정상 종료. 기존 SelfTest(gradlew selfTest)에 이식할 수 있는 형태.
 */
public final class SelfTest2 {

    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        Map<String, StatDef> defs = new HashMap<>();
        defs.put("FINAL_DMG", new StatDef("FINAL_DMG", "최종", "PCT", Combine.MULT_PCT, 0, false));
        DamageModel dm = new DamageModel(defs, Profile.defaults());
        TimelineSimulator2 sim = new TimelineSimulator2(dm);
        Content2 boss = Content2.boss1(0);
        StatBlock st = new StatBlock().with("LEVEL", 140).with("ATK_FLAT", 1000)
                .with("MIN_DMG_MULT", 100).with("MAX_DMG_MULT", 100).with("ATK_SPEED", 0);

        // [1] 평타 기준선: 공속 0 → 300초에 301타(0..300초). 계수 1.0, 타수 1.
        SkillPlan2 basic = new SkillPlan2("b", "평타", Kind2.BASIC, 0, Trigger2.ACTIVE,
                new Cast2(1.0, 1, 1, DmgTag2.BASIC, 0, 0, 0, null), List.of());
        Sim2Result r = sim.run(List.of(basic), st, boss, SimConfig.auto(300));
        double perHit = 1000 * (5000.0 / 6000.0);   // 공격력 × 방어배수(방어0)
        check("평타 타수·데미지", r.total(), perHit * 301, 1e-6);

        // [2] 액티브 쿨 버프의 가동률: cd 10s, 지속 4s → 40%. FINAL_DMG +100 → 평균 배수 1.4.
        BuffSpec2 fb = new BuffSpec2(Target2.PLAYER, Map.of("FINAL_DMG", 100.0), 4, null, 0, null, null, "a", "버프기");
        SkillPlan2 buffer = new SkillPlan2("a", "버프기", Kind2.ACTIVE, 10, Trigger2.ACTIVE, null, List.of(fb));
        r = sim.run(List.of(basic, buffer), st, boss, SimConfig.auto(3000));
        double up = r.buffReport().get("버프기:FINAL_DMG").uptimePct();
        check("쿨버프 가동률(4/10)", up, 40.0, 0.5);
        check("쿨버프 평균 딜배수", r.perSkill().get("평타") / (perHit * 3001), 1.4, 0.01);

        // [3] 결정론 proc 가동률: OnAttack 15%·지속 10s·공속0(1타/s) → 이론 1-0.85^10 = 0.8031.
        BuffSpec2 pb = new BuffSpec2(Target2.PLAYER, Map.of("FINAL_DMG", 50.0), 10, null, 0, null, null, "p", "프록버프");
        SkillPlan2 proc = new SkillPlan2("p", "프록버프", Kind2.ACTIVE, 0,
                new Trigger2(TriggerKind2.ON_ATTACK, 15, 0, false, 0, null, null, false), null, List.of(pb));
        r = sim.run(List.of(basic, proc), st, boss, SimConfig.auto(30000));
        up = r.buffReport().get("프록버프:FINAL_DMG").uptimePct();
        check("proc 버프 가동률(이론 80.3%)", up, 80.31, 1.5);

        // [4] counterEvery: 10회마다 → 3000초(3001타)에서 300회 발동, 지속 5s → 가동률 = 300×5/3000 ≈ 50%.
        BuffSpec2 cb = new BuffSpec2(Target2.PLAYER, Map.of("FINAL_DMG", 30.0), 5, null, 0, null, null, "c", "카운터버프");
        SkillPlan2 counter = new SkillPlan2("c", "카운터버프", Kind2.ACTIVE, 0,
                new Trigger2(TriggerKind2.ON_ATTACK, 100, 0, false, 10, "basicAttack", null, false), null, List.of(cb));
        r = sim.run(List.of(basic, counter), st, boss, SimConfig.auto(3000));
        up = r.buffReport().get("카운터버프:FINAL_DMG").uptimePct();
        check("counterEvery 가동률(≈50%)", up, 50.0, 1.0);

        // [5] 배타 그룹: 6면 주사위, 각 지속 5s·7s 주기 → 각 면 가동률 ≈ (5/7)/6 = 11.9%.
        SkillPlan2 dice;
        {
            BuffSpec2[] faces = new BuffSpec2[6];
            for (int i = 0; i < 6; i++)
                faces[i] = new BuffSpec2(Target2.PLAYER, Map.of("FINAL_DMG", (double) (i + 1)), 5,
                        null, 0, "dice", null, "d", "면" + (i + 1));
            dice = new SkillPlan2("d", "주사위", Kind2.ACTIVE, 0,
                    new Trigger2(TriggerKind2.ON_TIMER, 100, 7, false, 0, null, null, false),
                    null, List.of(faces));
        }
        r = sim.run(List.of(basic, dice), st, boss, SimConfig.auto(4200));
        double face1 = r.buffReport().get("면1:FINAL_DMG").uptimePct();
        check("배타그룹 면1 가동률(≈11.9%)", face1, 11.9, 0.5);

        // [6] 스택 축적→소모: OnAttack 100%(icd 2s)로 스택+1(max 5), 소모기 requireState "S:5" 소모.
        //     사이클 = 축적 10s(2s×5) + 즉시 소모 → 소모기 발동 주기 ≈ 10s, 지속 4s 버프 가동률 ≈ 40%.
        BuffSpec2 stack = new BuffSpec2(Target2.PLAYER, Map.of("FINAL_DMG", 2.0),
                Double.POSITIVE_INFINITY, "S", 5, null, null, "s", "스택기");
        SkillPlan2 stacker = new SkillPlan2("s", "스택기", Kind2.ACTIVE, 2,
                new Trigger2(TriggerKind2.ON_ATTACK, 100, 0, false, 0, null, null, false), null, List.of(stack));
        BuffSpec2 mode = new BuffSpec2(Target2.PLAYER, Map.of("FINAL_DMG", 25.0), 4, null, 0, null, "Mode", "m", "모드기");
        SkillPlan2 consumer = new SkillPlan2("m", "모드기", Kind2.ACTIVE, 0,
                new Trigger2(TriggerKind2.ACTIVE, 100, 0, false, 0, null, "S:5", true), null, List.of(mode));
        r = sim.run(List.of(basic, stacker, consumer), st, boss, SimConfig.auto(3000));
        up = r.buffReport().get("모드기:FINAL_DMG").uptimePct();
        check("스택 소모 사이클 가동률(≈40%)", up, 40.0, 3.0);

        // [7] 재발동 갱신: 지속 20s 버프를 10s 쿨로 재부여 → 가동률 100%(중복 아님), 평균 딜배수 = 1+값.
        BuffSpec2 rb = new BuffSpec2(Target2.PLAYER, Map.of("FINAL_DMG", 100.0), 20, null, 0, null, null, "r", "갱신기");
        SkillPlan2 refresher = new SkillPlan2("r", "갱신기", Kind2.ACTIVE, 10, Trigger2.ACTIVE, null, List.of(rb));
        r = sim.run(List.of(basic, refresher), st, boss, SimConfig.auto(3000));
        check("갱신 버프 가동률(100%)", r.buffReport().get("갱신기:FINAL_DMG").uptimePct(), 100.0, 0.1);
        check("갱신 버프 중복 없음(배수 2.0)", r.perSkill().get("평타") / (perHit * 3001), 2.0, 0.01);

        System.out.printf("%n결과: %d PASS / %d FAIL%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static void check(String name, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        System.out.printf("[%s] %-28s got=%.4f want=%.4f (±%.2f)%n", ok ? "PASS" : "FAIL", name, got, want, tol);
        if (ok) pass++; else fail++;
    }
}
