# DBTower 설계 문서

## 1. 문제 정의

여러 기종의 DBMS(MySQL·PostgreSQL·MSSQL·Oracle·MongoDB)를 운영하는 조직에서 반복되는 문제:

1. **지표 분산** — 쿼리 통계·슬로우 쿼리·실행계획이 기종마다 다른 도구/구문에 흩어져 있다
2. **커뮤니케이션 비용** — 개발자가 스스로 DB 이슈를 분석하지 못해 DBA 문의가 반복된다
3. **운영 작업의 기종 종속** — 백업·계정·정책 작업이 기종별 구문에 묶여 자동화가 어렵다

목표: 반복 운영을 추상화·자동화해 **관리 대상이 늘어도 운영 인력이 선형으로 늘지 않게** 한다. (DBRE)

## 2. 아키텍처

```
[Web/curl] ──REST──> [Spring Boot API]
                        ├── registry   : 이기종 인스턴스 등록/헬스 (PG 'dbtower' DB에 메타 저장 — 자신도 관리 대상으로 등록)
                        ├── operator   : DbmsOperator 인터페이스 + 기종별 어댑터
                        ├── insight    : 스냅샷 수집 스케줄러 + 시점 비교
                        ├── backup     : 백업 정책 + 폴러 + 실행 이력
                        └── analysis   : 실행계획 규칙 기반 분석
                              │
              ┌───────────────┼────────────────┐
        [MySQL 8.4]    [PostgreSQL 16]    [SQL Server 2022]
        performance_    pg_stat_           dm_exec_query_stats
        schema digest   statements         (플랜 캐시 DMV)
```

## 3. 핵심 결정과 이유

### 3.1 인터페이스 경계 — DbmsOperator

플랫폼의 모든 기능은 `health / queryStats / slowQueries / explain / backup / replicationState`
여섯 개 연산에만 의존한다. 기종 분기는 팩토리 한 곳에만 존재한다.

- 이렇게 나눈 이유: 운영 작업의 "무엇"(백업하라)과 "어떻게"(mysqldump vs BACKUP DATABASE)를 분리.
  새 DBMS 지원이 구현체 1개 추가로 끝나는지가 이 설계의 성공 기준
- 검증 방법: 새 기종을 추가할 때 플랫폼 코드 수정이 0인지 확인한다 — MSSQL로 1차, Oracle·MongoDB(비 JDBC)로 2차 검증 완료 (VERIFICATION 18절)

### 3.2 시점 비교 — 왜 상위 쿼리 나열로는 부족한가

부하 상위 쿼리가 곧 장애 원인이 아닐 수 있다. 평소에도 높던 쿼리일 수 있고,
진짜 범인은 "새로 유입된 쿼리"거나 "평소 대비 급증한 쿼리"다. (당근 KDMS의 시점 비교와 같은 문제의식)

- 데이터 모델: 통계 소스의 calls/totalTime은 **누적 카운터** → 주기 스냅샷을 쌓고,
  구간 양 끝 배치의 차분 = 구간 내 실제 발생량
- 구간 길이가 달라도 비교 가능하도록 QPS로 정규화
- base 구간에 없던 쿼리는 newQuery로 표시

### 3.3 기종별 통계 소스의 차이 (추상화가 필요한 실제 근거)

| | MySQL | PostgreSQL | MSSQL | Oracle | MongoDB |
|---|---|---|---|---|---|
| 소스 | performance_schema digest | pg_stat_statements | dm_exec_query_stats | V$SQL | system.profile |
| 정규화 방식 | 텍스트 앞 N바이트 | 파싱 결과 기반 | query_hash | sql_id (child cursor 합산 필요) | queryHash |
| 함정 | max_digest_length(1024) 초과 시 긴 쿼리가 뭉개짐 → 4096으로 상향 | 프리로드 필요 | 플랜 캐시 축출 시 통계 소실 | 커서 축출 시 통계 소실, V$ 조회에 별도 권한 | capped collection이라 가득 차면 덮어씀 (누적 카운터가 아님) |
| 시간 단위 | 피코초 | ms | 마이크로초 | 마이크로초 | ms |

같은 "쿼리 통계"인데 소스·정규화·단위가 전부 다르다 — 이 표가 인터페이스 추상화의 존재 이유다.

### 3.4 시점 비교 검증에서 배운 것 (실측 기록)

샘플 워크로드(점조회 400회 → 점조회 800회 + 신규 풀스캔 LIKE 60회)로 검증한 결과:

- 신규 쿼리 플래그, QPS +100%, rows/call 8,000(풀스캔), 구간 읽은행수 +58,428% 전부 검출
- **함정**: 누적 카운터 차분 방식은 구간 경계가 스냅샷 배치 시각을 포함해야 한다.
  경계를 배치 1초 뒤로 잡으면 그 사이 발생량이 전부 이전 배치에 흡수돼 delta가 0이 된다.
  KDMS가 CPU 그래프 "드래그"로 구간을 선택하게 한 이유 — 사용자가 임의 시각을 입력하면
  이 함정에 빠진다. 개선: 배치 시각 목록 API를 제공하고, 요청 구간을 가장 가까운 배치로 스냅

### 3.5 안전 원칙

- explain은 SELECT만 허용 (관리 플랫폼이 임의 DML 실행 금지)
- MSSQL 실행계획은 쿼리 재실행 대신 플랜 캐시 조회 (운영 부하 방지)
- 수집 실패는 인스턴스 단위로 격리 (한 대상 장애가 전체 수집을 막지 않음)
- 접속 계정은 API 응답에 노출하지 않음. 저장 암호화는 TODO(운영이라면 Vault/KMS)

## 4. 성능 개선 아크 (측정 → 개선 → 수치 검증)

포트폴리오의 핵심 서사. 각 항목은 반드시 before/after 수치와 함께 기록한다.

| # | 병목 (의도적 초기 구현) | 개선 | 측정 방법 |
|---|---|---|---|
| 1 | 수집마다 DriverManager 새 커넥션 | 인스턴스별 HikariCP 풀 | 완료 — MySQL 47.1→11.8ms(4.0배), PG 34.1→14.4ms, MSSQL 49.5→14.6ms (VERIFICATION.md 6절) |
| 2 | 스냅샷 JPA saveAll 단건 insert | JDBC batchUpdate + reWriteBatchedInserts | 완료 — PG 행당 1.51->0.11ms(13.8배), MySQL 2.33->0.95ms (VERIFICATION.md 7절) |
| 3 | 시점 비교 조회 풀스캔 | (instanceId, capturedAt) 복합 인덱스 | 완료 — 도그푸딩으로 Seq Scan 진단 후 21.269->0.062ms(343배) (VERIFICATION.md 9절) |
| 4 | max_digest_length 1024 | 4096 상향 | 완료 — 기본값에선 다른 쿼리 2개가 digest 1개로 병합됨을 side-by-side 재현 (VERIFICATION.md 10절) |
| 5 | API 전체 | k6 부하 | 완료 — 10VU 30s, 2,832 req/s, P95 5.86ms, 실패 0% (VERIFICATION.md 11절) |

## 5. 로드맵 (이 문서의 완성 기준: 확장3까지 — 이후는 ROADMAP.md)

확장3 이후(확장4~6 채널·5기종, Phase A 운영 안전 ~ Phase E 셀프호스트 제품화)는
[ROADMAP.md](ROADMAP.md)로 이관해 관리했고 전부 완료됐다. 아래는 초기 설계 시점의 기록이다.

- [x] MVP1: 이기종 등록 + Operator 추상화 (MySQL/PG/MSSQL)
- [x] MVP2: 시점 비교 검증 — 신규 쿼리/급증/rows-call 폭증 검출 (VERIFICATION.md 3절)
- [x] MVP3: EXPLAIN 규칙 분석 — 3종 감지 + 판단 근거 문서화 (db-hobby 원리 연결) — ai-analysis-rules.md
- [x] 성능 개선 아크 1~5 (VERIFICATION.md 6~11절)
- [x] 확장1: 백업 정책 (추상 정책 -> 기종별 실행) — VERIFICATION.md 12절
- [x] 확장2: Prometheus/Grafana + 복제 상태 — VERIFICATION.md 13절
- [x] 확장3: 회귀 자동 감지 + 웹훅(Discord/Slack) + 규칙 프롬프트 기반 AI 1차 분석 — VERIFICATION.md 15절

## 6. 참고

- 당근 SRE 밋업 "개발자를 위한 DB 분석 도구와 방법" (KDMS 데이터베이스 인사이트) — 시점 비교·판단 기준 명시 프롬프트·MCP 연동
- AWS Performance Insights의 AAS 개념과 한계
- db-hobby(자작 RDBMS)에서 구현한 WAL·MVCC·2PL — 지표를 해석하는 내부 지식의 출처
