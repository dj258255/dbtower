package io.dbtower.operator.model;

/**
 * 승인된 변경 문장 하나를 변경 계정으로 실행하는 계획. 플랫폼이 문장을 해석해 만들고, 오퍼레이터는 한 트랜잭션 안에서
 * 캡처·실행·대조·커밋(또는 드라이런이면 롤백)만 한다.
 *
 * @param kind           행 이미지를 어떻게 잡을지 — UNCAPTURED는 사람이 "롤백 경로 없음"을 인정한 실행
 * @param statement      승인된 원문 그대로(편집 금지)
 * @param table          캡처 대상 테이블(원문 표기). DDL·UNCAPTURED면 null
 * @param captureFrom    UPDATE·DELETE 사본 조회의 FROM 대상(원문 표기, 별칭 포함). 락이 테이블 뒤 힌트로 붙는 기종(SQL Server)이
 *                       있어 조건과 나눠 받고, 락을 건 조회 문장은 오퍼레이터가 조립한다
 * @param captureTail    원문의 WHERE(또는 ORDER BY·LIMIT) 이하. 조건이 없으면 빈 문자열
 * @param probeSql       변경 전후로 실행계획·응답시간을 잴 검증 조회(SELECT). 없으면 null
 * @param maxRows        캡처 상한 — 넘으면 실행하지 않는다(되돌릴 사본을 못 남기는 변경은 이 경로가 아니다)
 * @param timeoutSeconds 문장·락 대기 상한
 * @param dryRun         true면 끝에서 반드시 롤백한다
 */
public record ChangePlan(Kind kind, String statement, String table, String captureFrom, String captureTail,
                         String probeSql, int maxRows, int timeoutSeconds, boolean dryRun) {

    public enum Kind { UPDATE, DELETE, INSERT, DDL, UNCAPTURED }
}
