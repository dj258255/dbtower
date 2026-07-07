package io.dbtower.operator;

import java.util.List;

/**
 * 데드락(교착) 한 건의 기록 (3차 아크 D-축) — "서로가 서로의 락을 기다려 아무도 못 나아가는" 순간을,
 * DB가 이미 남긴 흔적에서 <b>설정 변경 0으로</b> 읽어온다.
 *
 * <p>기종마다 관측 입도가 근본적으로 다르다. SQL Server는 system_health 확장 이벤트가 데드락마다
 * victim·관여 프로세스·경합 리소스를 XML 리포트로 남겨 여러 건을 읽을 수 있고(NATIVE·롤링 파일),
 * MySQL은 SHOW ENGINE INNODB STATUS가 <b>가장 최근 1건</b>만 텍스트로 보여준다(그 이전은 덮여 사라짐).
 * PostgreSQL은 개별 사건 기록이 없고 pg_stat_database.deadlocks <b>누적 카운터</b>뿐이라 이 레코드가 아니라
 * OpsAlert의 카운터 델타로 다룬다.
 *
 * <p>공통 한계: 어느 엔진이든 "최근"만 남는 롤링 저장이라, 오래된 데드락은 관측 범위 밖이다 — 이 사실을
 * 응답·알림에 정직하게 표기한다(과거 전수 이력을 보장하지 않는다).
 *
 * @param detectedAt  발생 시각(엔진이 보고한 문자열/ISO). 정확 시각을 못 주는 엔진은 근사 표기 또는 null
 * @param statements  교착에 관여한 문장들(victim 포함). 엔진이 SQL을 안 남기면 빈 목록
 * @param victim      어느 쪽이 롤백됐는지 요약(프로세스/트랜잭션 식별). 모르면 null
 * @param resource    경합한 리소스/락 요약(테이블·인덱스·키 등). 모르면 null
 * @param source      획득 방식 라벨 — "MSSQL system_health XE" / "MySQL INNODB STATUS"
 */
public record DeadlockEvent(String detectedAt, List<String> statements,
                            String victim, String resource, String source) {
}
