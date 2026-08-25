package mekicalc.timeline;

import mekicalc.timeline.ir.SkillIR;

/**
 * 스케줄러가 배치한 행동 1건. 평타 or 액티브 스킬 시전.
 *
 * @param timeMs 발동 시각(ms)
 * @param skill  발동 스킬(평타면 최고차수 평타 스킬)
 * @param basic  평타 여부
 */
public record Action(int timeMs, SkillIR skill, boolean basic) {

    public static Action basic(int timeMs, SkillIR basicSkill) {
        return new Action(timeMs, basicSkill, true);
    }

    public static Action cast(int timeMs, SkillIR skill) {
        return new Action(timeMs, skill, false);
    }

    public int skillIdx() { return skill.idx(); }
}
