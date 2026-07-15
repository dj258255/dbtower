package io.dbtower.finops.internal;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;

import java.util.List;

/**
 * 낭비 후보 분석기 하나 (D6 FinOps) — 인스턴스를 받아 "낭비 후보" 목록을 돌려준다.
 *
 * Advisor(D2)와 같은 규약: 기존 operator 조회 결과만 판정한다(읽고 조언만 — 대상 DB를 바꾸지 않는다).
 * 적용 가능한 기종은 supports로 정직하게 선언하고, 불가 기종은 FinOpsService가 UNSUPPORTED로 표기한다.
 * 절감액을 지어내지 않는다 — "신호"까지만 낸다.
 */
public interface FinOpsAnalyzer {

    /** 안정적 식별자(REST/테스트 고정용) */
    String id();

    /** 사람이 읽는 이름(웹 콘솔 카드 소제목) */
    String title();

    /** 이 기종에 적용 가능한가 — false면 서비스가 UNSUPPORTED로 표기하고 analyze를 호출하지 않는다 */
    boolean supports(DbmsType type);

    /**
     * 낭비 후보 목록 반환 — 비어 있으면 후보 없음(OK). supports가 true인 기종에서만 호출된다.
     * operator의 읽기 전용 조회만 사용하고 대상 DB를 변경하지 않는다.
     */
    List<WasteCandidate> analyze(DatabaseInstance instance, DbmsOperator operator);
}
