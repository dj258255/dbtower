# DBHub 로드맵

목표: 이기종 DBMS 운영 관리 플랫폼(관제탑). 여러 DBMS의 반복 운영 작업(모니터링·진단·백업·알림)을
하나의 추상화 뒤로 자동화해서, 관리 대상 DB가 늘어도 운영 부담이 선형으로 늘지 않게 한다(DBRE).

기준 참고: 당근 DB 밋업 1회 "개발자를 위한 DB 분석 도구와 방법"(KDMS Database Insight) —
분석은 docs/reference/kdms-meetup/ANALYSIS.md.

## 완료

| 단계 | 내용 | 검증 (VERIFICATION.md) |
|---|---|---|
| MVP1 | 이기종 등록 + DbmsOperator 추상화 (MySQL/PostgreSQL/SQL Server) + 헬스체크 | 1절 |
| MVP2 | 시점 비교 — 누적 카운터 스냅샷 구간 차분, QPS 정규화, 신규 쿼리 감지 | 3절 |
| MVP3 | EXPLAIN + 규칙 기반 비효율 분석 (기종별 판단 규칙 + 근거 문서화) | 2절, ai-analysis-rules.md |
| 성능 아크 1 | DriverManager -> HikariCP: 수집 47.1 -> 11.8ms (MySQL, 최대 4.0배) | 6절 |
| 성능 아크 2 | JPA saveAll -> JDBC batchUpdate: 행당 1.51 -> 0.11ms (PG, 13.8배) | 7절 |
| 성능 아크 3 | 도그푸딩 — DBHub로 DBHub 자신 진단, 복합 인덱스로 21.269 -> 0.062ms (343배) | 9절 |
| 성능 아크 4 | max_digest_length 1024 vs 4096 side-by-side — 긴 쿼리 digest 병합 재현·해소 | 10절 |
| 성능 아크 5 | k6 부하: 10 VU 2,832 req/s, P95 5.86ms, 실패 0 | 11절 |
| 확장 1 | 백업 정책 — 추상 정책 + 기종별 실행(mysqldump/pg_dump/BACKUP DATABASE), 보안 보강 | 12·14절 |
| 확장 2 | 모니터링 통합 — Prometheus/Grafana/exporters + 복제 상태 통합 뷰 | 13절 |
| 확장 3 | 쿼리 회귀 자동 감지(4규칙+쿨다운) + Discord/Slack 웹훅 + AI 1차 분석 | 15절 |

## 다음 (KDMS 갭 분석 기반)

### 확장 4 — 웹 UI (진행 중, 우선순위 1)

KDMS Database Insight 화면의 축소판. JD의 "API 서버부터 사용자에게 보여지는 Web까지" 충족.

- 인스턴스 선택 (등록된 이기종 목록 + 헬스 상태)
- 활동 그래프(구간별 QPS) 위에서 조회/비교 구간 선택
- Top Query 표: load% / QPS / 평균 레이턴시 / rows-per-call — 비교 시 증감 색상 + NEW 뱃지
- 쿼리 클릭 -> EXPLAIN 결과 + 규칙 기반 지적 + AI 1차 분석
- 구현: Spring Boot가 서빙하는 정적 SPA(빌드 체인 없음) — 백엔드가 본질이라 프론트는
  의존성 없이 얇게. `java -jar` 하나로 화면까지 뜨게 해서 과제 제출·데모를 단순하게 유지

### 확장 5 — MCP 서버 (우선순위 2)

compare / explain / health / query-stats를 MCP tool로 노출해 AI 에이전트가 DBHub를
도구로 쓰게 한다. "알림을 받은 사람이 화면을 여는" push형에 더해, "AI가 필요할 때 스스로
조회하는" pull형 채널 확보 (KDMS의 최신 방향과 동일).

### 문서 보강 (우선순위 3, 저비용)

- events_statements_summary_by_digest 가득참(digests_size) 대응 규칙 — 80% 초과 시 Truncate
- prepared statement와 통계 가시성 트레이드오프 가이드
- AAS(Average Active Session) 개념과 load% 랭킹의 관계 정리

## 범위 밖 (의도적으로 안 한다)

- Wait Event 분석, 파티션 자동 관리, Schema Diff, DB 생성 자동화 — KDMS 본체 기능이지만
  이 프로젝트의 핵심 서사(추상화 + 시점 비교 + 자동 회귀 감지)와 겹치지 않아 제외.
  "무엇을 안 했는지"를 아는 것도 범위 관리의 일부.
