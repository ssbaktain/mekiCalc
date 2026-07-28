package mekicalc.engine.source;

import java.util.List;
import java.util.Map;
import mekicalc.engine.AcquisitionOption;
import mekicalc.model.StatBlock;

/**
 * 스탯을 얻을 수 있는 수단(소스)의 공통 인터페이스.
 *
 * <p>구현체: 장비 윗잠/아랫잠·어빌리티·용사의여정·유물잠재({@link PotentialSource}),
 * 주흔작({@link EnhanceSource}).
 *
 * <p>각 소스는 "현재 스탯(current)" 을 받아, 자신이 제공할 수 있는 선택지들을
 * 기대 시도/기대 비용까지 계산해 돌려준다. (데미지 증가율은 호출측에서 채움)
 */
public interface StatSource {

    String id();

    String name();

    /**
     * @param currencyValues 재화 id -> 1단위당 상대 가치 (서로 다른 재화를 비교하기 위함)
     * @return 이 소스가 제공하는 선택지 목록
     */
    List<AcquisitionOption> options(Map<String, Double> currencyValues);
}
