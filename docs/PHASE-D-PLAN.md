# Phase D 구현 계획 — 자율 진단 (Autonomous Insight)

> 이 문서는 구현 담당(다음 세션)이 추가 조사 없이 바로 착수할 수 있게 쓴 실행 계획서다.
> 배경 근거·상세 스펙·기존 코드 지도·준수 규칙·검증 시나리오를 전부 담는다.

## 0. 한 문장 요약

DBTower를 "사람이 모는 관제탑"에서 **"스스로 보고, 묶어서 알리고, 물어보면 원인을 설명하는
관제탑"** 으로 승격한다. 시점 비교·Wait Event·세션·Advisor 규칙 등 이미 있는 조각을
자율화(automation)로 엮는 것이지, 새 영역으로 확장하는 것이 아니다.

## 1. 왜 이 방향인가 (근거 — 전부 실존)

### 1-1. DBA의 실제 고통 (2026 설문)
- DBA 약 75%가 알림 피로(alert fatigue), 절반이 "심각한 영향" — 더 많은 알림이 아니라
  **덜, 묶인, 우선순위 있는 알림**이 필요 ([SolarWinds State of Database 2025](https://www.solarwinds.com/blog/the-state-of-database-burnout-ai-battle-for-balance))
- 상시 파이어파이팅·번아웃. "50ms였던 쿼리가 800ms", "커넥션 풀 조용한 고갈" 같은
  **조용한 저하**를 사람이 못 잡는다 ([New Relic](https://newrelic.com/blog/infrastructure-monitoring/database-monitoring-tools))
- 관측성 카테고리의 방향: "자동으로 뭘 볼지 우선순위, 관련 알림 클러스터링, 사람 감독 하의
  자율 시스템" ([ClickHouse — What is observability](https://clickhouse.com/resources/engineering/what-is-observability))

### 1-2. 실존 제품 3종이 같은 것을 판다 (시장 검증)
- **[Amazon DevOps Guru for RDS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/devops-guru-for-rds.html)**:
  DB Load(AAS)에 ML 베이스라인(정상 패턴 ~2일 학습) → 이상 감지 → 이상마