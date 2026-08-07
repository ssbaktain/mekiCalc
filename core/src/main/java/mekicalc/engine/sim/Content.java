package mekicalc.engine.sim;

/**
 * 전투 컨텐츠 — {@link ContentProfile} 선택 기준(대상·보정식이 컨텐츠별로 다름).
 * <ul>
 *   <li>{@code CHAPTER_HUNT}   : 챕터 사냥(다수 대상·리젠. 지금은 훅만, 단일 취급).</li>
 *   <li>{@code CHAPTER_BOSS}   : 챕터 보스(단일 대상).</li>
 *   <li>{@code ARENA/WORLD_ARENA/COLOSSEUM} : PVP(제곱근 보정+레벨보정+PVP상수, 상대=본인 스탯).</li>
 *   <li>{@code PARTY_QUEST/GUILD_RAID} : 제곱근 보정.</li>
 *   <li>{@code GUILD_TRAINING} : 세제곱근 보정.</li>
 * </ul>
 */
public enum Content {
    CHAPTER_HUNT, CHAPTER_BOSS, ARENA, WORLD_ARENA, COLOSSEUM, PARTY_QUEST, GUILD_RAID, GUILD_TRAINING
}
