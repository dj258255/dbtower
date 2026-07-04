package io.dbtower.advisor;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;

import java.util.List;

/**
 * 자동 점검 규칙 하나 (Phase D2) — 인스턴스를 받아 위반 목록을 돌려준다.
 *
 * 각 Advisor는 기존 operator 조회 결과만 판정한다(읽고 조언만). 적용 가능한 기종은 supports로
 * 정직하게 선언하고, 불가 기종은 AdvisorService가 UNSUPPORTED로 표기한다(통과 위장 금지).
 */
public interface Advisor {

    /** 안정적 식별자(REST/캐시 키·테스트 고정용) */
    String id();

    /** 사람이 읽는 이름(웹 콘솔 표시) */
    String title();

    /** 이 기종에 적용 가능한가 — false면 스윕이 UNSUPPORTED로 표기하고 inspect를 호출하지 않는다 */
    boolean supports(DbmsType type);

    /**
     * 위반 목록 반환 — 비어 있으면 통과(OK). supports가 true인 기종에서만 호출된다.
     * operator의 읽기 전용 조회만 사용하고 대상 DB를 변경하지 않는다.
     */
    List<AdvisorFinding> inspect(DatabaseInstance instance, DbmsOperator operator);
}
