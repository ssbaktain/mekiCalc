package mekicalc.timeline;

import java.util.EnumMap;
import java.util.Map;

/**
 * 버프/디버프 1건의 "누적 명세" — 활성 윈도우 [start,end)와 그 안에서 슬롯에 더할 수정치.
 *
 * <ul>
 *   <li>{@link #adds} — 합풀 기여(%, 표시퍼센트 단위). 슬롯 ModState의 합풀에 더한다.
 *   <li>{@link #finalDmgFrac} — 이 소스의 최종뎀 분율(배율). 같은 스킬 내 효과·마스터리는 미리 합산돼 있다.
 *       슬롯에 최종뎀 곱소스로 1개 push(스킬끼리 곱).
 * </ul>
 */
public final class BuffSource {

    final String label;
    final int startMs;
    final int endMs;
    final Map<Stat, Double> adds = new EnumMap<>(Stat.class);
    double finalDmgFrac = 0.0;
    int inst = -1;   // 유물 인스턴스 인덱스(equipped 위치) — 중복 유물 오염방지·표시 그룹핑 키. 스킬 버프=-1.
    int skillIdx = -1;   // 이 버프를 만든 스킬/마스터리 게이트 인덱스(툴팁 아이콘 /img/skill/{idx}.png). 유물=-1.
    boolean dormant = false;   // 활성이지만 현재 무실효(대상 불일치·마리수 미충족 등) — 딜 반영 제외, 표시는 흐리게

    BuffSource(String label, int startMs, int endMs) {
        this.label = label;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    BuffSource skill(int idx) { this.skillIdx = idx; return this; }   // 아이콘 매핑용 스킬 인덱스 지정(체이닝)

    void add(Stat s, double pct) { adds.merge(s, pct, Double::sum); }

    // ── 검증/진단용 접근자 ──
    public String label()        { return label; }
    public int startMs()         { return startMs; }
    public int endMs()           { return endMs; }
    public double finalDmgFrac() { return finalDmgFrac; }
    public int inst()            { return inst; }
    public int skillIdx()        { return skillIdx; }
    public boolean dormant()     { return dormant; }
    public Map<Stat, Double> adds() { return adds; }

    /** 이 소스를 슬롯 상태에 반영. */
    void applyTo(ModState mods) {
        for (var e : adds.entrySet()) mods.addStat(e.getKey(), e.getValue());
        if (finalDmgFrac != 0.0) mods.addFinalDmgSource(finalDmgFrac);
    }
}
