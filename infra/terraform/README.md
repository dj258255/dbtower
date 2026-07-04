# DBTower 프로비저닝 연동 — Terraform (AWS RDS)

클라우드에서는 Terraform이 DB를 만든다. 이 모듈은 RDS 인스턴스를 선언하고, 생성 후
엔드포인트를 읽어 DBTower의 **멱등 등록 PUT /api/instances** 를 호출한다(생성과 관제를 잇는다).
K8s는 Operator, VM은 Ansible, 클라우드는 Terraform — 같은 자리의 세 판이다.

## 구성

| 파일 | 역할 |
|---|---|
| `main.tf` | `aws_db_instance`(RDS) + 생성 후 DBTower 등록(local-exec PUT) |
| `variables.tf` | 리전·엔진·계정·DBTower URL/토큰 (비밀은 sensitive) |
| `outputs.tf` | RDS 엔드포인트·주소·포트 |
| `terraform.tfvars.example` | 비밀값 placeholder — 실값은 `terraform.tfvars`(gitignore) |

## 실행 순서

```bash
cp terraform.tfvars.example terraform.tfvars   # 비밀 채우기 (커밋 안 됨)
terraform init
terraform validate
terraform plan     # AWS 자격증명 필요
terraform apply    # 실제 RDS 생성 — AWS 자격증명·과금 발생
```

## 검증 수준 (정직)

이 저장소에서는 **`terraform/tofu init·validate·fmt`까지만 실행했다** (OpenTofu v1.12.3,
aws provider v5.100 스키마로 `validate` 통과). **`apply`는 실행하지 않았다** — 실제 RDS
생성은 AWS 자격증명이 필요하고 클라우드 리소스·과금이 발생하기 때문이다. 즉 이 모듈은
"문법·스키마가 유효한 상태"까지 검증됐고, 실제 프로비저닝 e2e는 자격증명이 있는 환경에서
수행한다. (같은 등록 흐름의 실제 e2e는 K8s(CloudNativePG)·Ansible 파트에서 확인 — VERIFICATION 42·43절)

## 모니터링 계정 주의

RDS 마스터 계정으로 붙지 않는다. 생성 후 `dbtower_monitor` 최소 권한 계정을 부여하고
(docs/least-privilege.md) 그 계정으로 등록한다 — 등록 payload의 username/password가 그것이다.
