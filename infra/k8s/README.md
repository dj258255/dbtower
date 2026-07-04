# DBTower 프로비저닝 연동 — Kubernetes (CloudNativePG)

K8s에서 PostgreSQL을 CR로 선언하면 CloudNativePG Operator가 프로비저닝·복제·백업·failover
(Day-1/Day-2)를 자동화한다. DBTower는 그 위의 **쿼리 수준 관제**를 맡는다 — 역할 분업이다.
클러스터가 Ready가 되면 접속 Secret을 읽어 DBTower에 자동 등록한다(생성과 관제를 잇는다).

## 구성

| 파일 | 역할 |
|---|---|
| `cluster.yml` | CloudNativePG `Cluster` CR — PostgreSQL 1인스턴스(데모) |
| `register-job.yml` | 클러스터 -app Secret을 읽어 DBTower PUT /api/instances 호출하는 Job |
| `dbtower-config.example.yml` | 등록 Job이 쓰는 DBTower URL·토큰(Secret) — 예시(실값 커밋 금지) |

## 실행 순서

```bash
# 1) CloudNativePG Operator 설치 (공식 릴리스 매니페스트)
kubectl apply --server-side -f \
  https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-1.24/releases/cnpg-1.24.1.yaml

# 2) PostgreSQL 클러스터 선언 -> Operator가 프로비저닝
kubectl apply -f cluster.yml
kubectl wait --for=condition=Ready cluster/dbtower-pg --timeout=300s

# 3) DBTower 접속 설정(Secret) 준비 후 등록 Job 실행
cp dbtower-config.example.yml dbtower-config.yml   # URL·토큰 채우기 (gitignore됨)
kubectl apply -f dbtower-config.yml
kubectl apply -f register-job.yml
kubectl logs job/dbtower-register
```

## 등록 훅 원리

CloudNativePG는 클러스터 이름 `-app` Secret에 username/password/host/port/dbname을 담는다.
`register-job.yml`은 그 Secret을 컨테이너에 마운트해 값을 읽고, DBTower의 **멱등 등록
PUT /api/instances** 를 호출한다. Job이 재실행돼도 upsert라 중복이 안 생긴다.

## 검증 수준 (정직)

이 저장소의 매니페스트는 `kubectl --dry-run=client`로 문법을 검증했다. 전체 e2e(kind로
클러스터를 띄워 Operator·CR·등록 Job까지)는 로컬 환경에서 시도했으며, 실제로 검증된
단계는 VERIFICATION 43절에 기록한다.
