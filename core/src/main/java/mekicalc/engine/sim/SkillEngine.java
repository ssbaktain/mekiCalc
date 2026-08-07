package mekicalc.engine.sim;

import java.util.List;
import mekicalc.model.StatBlock;

/**
 * 스킬 엔진 — 직업 스킬 데이터(skills_v2)와 캐릭터 스탯·유저 선택으로부터
 * 시뮬용 {@link SkillPlan} 목록을 산출한다.
 *
 * <p>책임: 계수 성장·쿨감(7초 특수규칙)·타수/대상 수·평타 대상 수·마스터리·버프 지속시간을
 * 모두 반영한 "완성 스펙"을 만드는 것. 사용 순서나 데미지 계산은 하지 않는다(스케줄러·데미지 엔진).
 */
public interface SkillEngine {

    /**
     * @param stats 캐릭터 유효 스탯(레벨·쿨감·평타 대상 수·스킬 레벨 등).
     * @param sel   유저 스킬 선택(켜진 액티브 + 평타).
     * @return 평타 1개 + 켜진 액티브들의 {@link SkillPlan} 목록(버프/디버프는 grants에).
     */
    List<SkillPlan> plan(StatBlock stats, SkillSelection sel);
}
