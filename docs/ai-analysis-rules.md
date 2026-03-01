# 실행계획 판단 규칙 (기종별)

RuleBasedAnalyzer가 사용하는 비효율 판단 기준과 그 근거를 정리한다.
확장3에서 LLM 1차 분석을 붙일 때 이 문서가 그대로 프롬프트의 판단 기준이 된다.
(같은 입력에 같은 판정 — AI에게 판단을 통째로 맡기지 않고 기준을 명시하는 이유)

## MySQL

| 신호 | 판정 | 근거 |
|---|---|---|
| access_type=ALL | 테이블 풀스캔 | 인덱스 부재 또는 앞 와일드카드 LIKE 등으로 인덱스 사용 불가 |
| using_filesort=true | 별도 정렬 | ORDER BY가 인덱스 순서로 해결되지 않음 |
| using_temporary_table=true | 임시 테이블 | GROUP BY/DISTINCT가 인덱스로 해결되지 않음 |
| access_type=index | 인덱스 풀스캔 | 인덱스 전체 스캔 — 커버링이어도 행수 비례 비용 |

## PostgreSQL

| 신호 | 판정 | 근거 |
|---|---|---|
| Seq Scan | 순차 스캔 | 작은 테이블이면 정상일 수 있음 — 행수와 함께 판단 |
| Nested Loop + 안쪽 Seq Scan | 조인 폭발 | 바깥 행수 x 안쪽 풀스캔 |
| Sort Method: external | 디스크 스필 | work_mem 초과 정렬 |

## SQL Server

| 신호 | 판정 | 근거 |
|---|---|---|
| Table Scan | 힙 풀스캔 | 클러스터드 인덱스 부재 |
| Clustered Index Scan | 사실상 풀스캔 | 조건이 인덱스 선두 컬럼을 타지 못함 |
| Sort | 정렬 비용 | 인덱스 정렬 순서 활용 검토 |

## 채워 넣을 것 (직접 작성)

- 각 규칙의 예외 상황 (예: 통계 오래됨, 소형 테이블의 Seq Scan이 더 빠른 경우)
- db-hobby에서 B+Tree·실행기 구현하며 확인한 원리와 연결
- 실제 재현 사례: 샘플 워크로드에서 각 신호를 일부러 발생시키고 규칙이 잡는지 검증한 기록
