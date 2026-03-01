# DBHub — 이기종 DBMS 운영 관리 플랫폼

MySQL·PostgreSQL·SQL Server처럼 서로 다른 DBMS를 하나의 플랫폼에서 등록하고,
모니터링·슬로우 쿼리 분석·시점 비교·백업 정책 적용까지 일괄 관리하는 컨트롤 플레인입니다.

같은 "백업"이라도 mysqldump / pg_basebackup / BACKUP DATABASE로 구문이 전부 다르고,
쿼리 통계도 performance_schema / pg_stat_statements / DMV로 소스가 전부 다릅니다.
DBHub는 이 차이를 `DbmsOperator` 인터페이스 뒤로 숨겨, 사용자는 추상화된 정책만 다루게 합니다.

## 왜 만들었나

DB 이슈가 나면 개발자는 지표가 흩어진 여러 도구를 오가다 결국 DBA에게 문의하게 되고,
DBA는 같은 질문에 반복적으로 답하게 됩니다. 반복되는 운영 작업을 플랫폼으로 자동화하면
관리 대상 DB가 늘어도 필요한 사람 손이 선형으로 늘지 않습니다.
당근 KDMS, 토스 등 사내 DB 플랫폼 사례를 참고해 그 축소판을 직접 설계했습니다.

## 핵심 기능

| 기능 | 설명 |
|---|---|
| 이기종 등록 | DB 인스턴스를 등록하면 기종에 맞는 Operator가 자동 연결 (등록 시 접속 검증) |
| 통합 쿼리 통계 | 기종별 통계 소스를 하나의 API로 — 쿼리별 호출수·누적 시간·읽은 행수 |
| 슬로우 쿼리 | MySQL slow_log 테이블 / PG·MSSQL 통계 기반 상위 조회 |
| 시점 비교 | 평소 구간 vs 문제 구간의 쿼리별 QPS·레이턴시 증감률 + 신규 쿼리 표시 |
| 실행계획 분석 | EXPLAIN + 기종별 비효율 판단 규칙(풀스캔·filesort·Seq Scan 등) 자동 지적 |
| 백업 정책 (확장1) | "30분 주기 전체 백업" 같은 추상 정책을 기종별 구문으로 실행 |
| 통합 모니터링 (확장2) | Prometheus + Grafana, 복제 상태 통합 뷰 |
| 알림·AI 분석 (확장3) | 임계치 알림 + 실행계획 규칙을 프롬프트로 쓰는 1차 자동 분석 |

## 실행

```bash
# 관리 대상 DB 3종 기동
docker compose up -d

# 플랫폼 기동
./gradlew bootRun
```

인스턴스 등록:

```bash
curl -X POST localhost:8080/api/instances -H 'Content-Type: application/json' -d '{
  "name": "local-mysql", "type": "MYSQL",
  "host": "127.0.0.1", "port": 13306, "dbName": "sample",
  "username": "root", "password": "dbhub1234"
}'
```

주요 API:

```
GET  /api/instances                     등록 목록
GET  /api/instances/{id}/health         헬스체크 (버전·응답시간)
GET  /api/instances/{id}/query-stats    쿼리별 누적 통계 상위 N
GET  /api/instances/{id}/slow-queries   슬로우 쿼리 상위 N
POST /api/instances/{id}/explain        실행계획 + 규칙 기반 비효율 분석
GET  /api/instances/{id}/compare        시점 비교 (base 구간 vs target 구간)
```

## 설계 문서

- [docs/DESIGN.md](docs/DESIGN.md) — 인터페이스 경계, 시점 비교 데이터 모델, 성능 개선 계획
- [docs/ai-analysis-rules.md](docs/ai-analysis-rules.md) — 기종별 실행계획 판단 규칙과 근거
