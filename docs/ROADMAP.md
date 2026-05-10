# DBTower 로드맵

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
| 성능 아크 3 | 도그푸딩 — DBTower로 DBTower 자신 진단, 복합 인덱스로 21.269 -> 0.062ms (343배) | 9절 |
| 성능 아크 4 | max_digest_length 1024 vs 4096 side-by-side — 긴 쿼리 digest 병합 재현·해소 | 10절 |
| 성능 아크 5 | k6 부하: 10 VU 2,832 req/s, P95 5.86ms, 실패 0 | 11절 |
| 확장 1 | 백업 정책 — 추상 정책 + 기종별 실행(mysqldump/pg_dump/BACKUP DATABASE), 보안 보강 | 12·14절 |
| 확장 2 | 모니터링 통합 — Prometheus/Grafana/exporters + 복제 상태 통합 뷰 | 13절 |
| 확장 3 | 쿼리 회귀 자동 감지(4규칙+쿨다운) + Discord/Slack 웹훅 + AI 1차 분석 | 15절 |
| 확장 4 | 웹 UI — 활동 그래프 구간 드래그, 시점 비교(증감·NEW 뱃지), EXPLAIN+AI 분석 화면 | 16절 |
| 확장 5 | MCP 서버 — JSON-RPC 직접 구현, 도구 8종으로 AI 에이전트에 pull형 분석 채널 제공 | 17절 |
| 확장 6 | Oracle·MongoDB 추가 — 5기종. "새 기종 = 구현체 1개" 실측(플랫폼 코드 0줄), 비 JDBC 증명 | 18절 |

## 다음 (KDMS 갭 분석 기반)

### 문서 보강 (우선순위 1, 저비용)

- events_statements_summary_by_digest 가득참(digests_size) 대응 규칙 — 80% 초과 시 Truncate
- prepared statement와 통계 가시성 트레이드오프 가이드
- AAS(Average Active Session) 개념과 load% 랭킹의 관계 정리

### 품질 (우선순위 2)

- 핵심 로직 단위 테스트 — 시점 비교 차분·경계, 회귀 감지 규칙·쿨다운, 백업 명령 렌더링 주입 방어, MCP 프로토콜
- GitHub Actions CI (gradle test)

## 범위 밖 (의도적으로 안 한다)

- Wait Event 분석, 파티션 자동 관리, Schema Diff, DB 생성 자동화 — KDMS 본체 기능이지만
  이 프로젝트의 핵심 서사(추상화 + 시점 비교 + 자동 회귀 감지)와 겹치지 않아 제외.
  "무엇을 안 했는지"를 아는 것도 범위 관리의 일부.
