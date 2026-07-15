package io.dbtower.insight.internal;

import io.dbtower.operator.model.DbParameter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 파라미터 드리프트 (B6) — 같은 역할의 두 인스턴스 설정값(max_connections·work_mem 등)을 비교해
 * "왜 저 장비만 느리지"의 단골 원인인 설정 차이를 드러낸다. B7 스키마 diff와 같은 구조:
 * operator가 읽어 온 파라미터 목록 두 개만 받아 순수 비교만 한다(대상 DB 접근 없음).
 *
 * 분류는 3가지다: 양쪽에 있으나 값이 다르면 변경(changed), left에만 있으면 leftOnly, right에만 있으면
 * rightOnly. 같은 기종끼리만 의미가 있으며(파라미터 이름 체계가 기종마다 아예 다름), 기종이 다르면
 * 경고를 싣는다 — 대부분 '한쪽에만'으로 나와 비교가 무의미하기 때문.
 */
@Service
public class ParameterDiffService {

    /** 값이 다른 파라미터 하나 — 양쪽에 존재하지만 값이 갈린다 */
    public record ParameterDrift(String name, String leftValue, String rightValue) {
    }

    /**
     * 전체 diff 결과.
     * @param identical 차이가 하나도 없으면 true
     * @param warning   기종이 다를 때의 주의 문구(없으면 null)
     * @param changed   양쪽에 있으나 값이 다른 파라미터(이름순)
     * @param leftOnly  left에만 있는 파라미터
     * @param rightOnly right에만 있는 파라미터
     */
    public record ParameterDiff(String leftType, String rightType, boolean identical, String warning,
                                List<ParameterDrift> changed,
                                List<DbParameter> leftOnly, List<DbParameter> rightOnly) {
    }

    public ParameterDiff diff(String leftType, List<DbParameter> left,
                              String rightType, List<DbParameter> right) {
        Map<String, DbParameter> leftByName = byName(left);
        Map<String, DbParameter> rightByName = byName(right);

        List<ParameterDrift> changed = new ArrayList<>();
        List<DbParameter> leftOnly = new ArrayList<>();
        for (Map.Entry<String, DbParameter> e : leftByName.entrySet()) {
            DbParameter r = rightByName.get(e.getKey());
            if (r == null) {
                leftOnly.add(e.getValue());
            } else if (!Objects.equals(e.getValue().value(), r.value())) {
                changed.add(new ParameterDrift(e.getKey(), e.getValue().value(), r.value()));
            }
        }
        List<DbParameter> rightOnly = new ArrayList<>();
        for (Map.Entry<String, DbParameter> e : rightByName.entrySet()) {
            if (!leftByName.containsKey(e.getKey())) {
                rightOnly.add(e.getValue());
            }
        }

        boolean identical = changed.isEmpty() && leftOnly.isEmpty() && rightOnly.isEmpty();
        return new ParameterDiff(leftType, rightType, identical, warning(leftType, rightType),
                changed, leftOnly, rightOnly);
    }

    /** 기종이 다르면 이름 체계가 아예 달라 비교가 무의미하다는 점을 정직하게 경고로 싣는다 */
    private static String warning(String leftType, String rightType) {
        if (!Objects.equals(leftType, rightType)) {
            return "기종이 다릅니다(" + leftType + " vs " + rightType
                    + ") — 파라미터 이름 체계가 아예 달라(예: max_connections vs processes) 대부분 "
                    + "'한쪽에만'으로 나옵니다. 파라미터 드리프트는 같은 기종끼리 비교해야 의미가 있습니다.";
        }
        return null;
    }

    /** 이름 -> 파라미터. TreeMap으로 이름순 안정 출력(비교·표시가 결정적) */
    private static Map<String, DbParameter> byName(List<DbParameter> params) {
        Map<String, DbParameter> m = new TreeMap<>();
        params.forEach(p -> m.put(p.name(), p));
        return m;
    }
}
