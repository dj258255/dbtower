# DBTower 프로비저닝 연동 — Ansible (온프레미스/VM)

DB가 태어나는 순간 관제탑(DBTower)에 자동 등록한다. 이 플레이북은 대상 DB에
**모니터링 전용 최소 권한 계정**을 만들고(`docs/least-privilege.md` 기준), DBTower의
**멱등 등록 API(PUT /api/instances)** 로 등록한다. 두 번 돌려도 중복·에러 없이 수렴한다.

## 무엇을 하나

1. 대상 PostgreSQL에 `dbtower_monitor` 계정 생성 (LOGIN + pg_read_all_stats — 최소 권한)
2. DBTower에 그 계정으로 인스턴스 등록 (PUT — 같은 이름이면 갱신)

Operator(CloudNativePG 등)가 Day-1/Day-2 운영을 맡는 K8s와 달리, 온프레미스/VM은
설치·계정·등록을 사람이 하던 것을 Ansible로 코드화한다. "생성과 관제를 잇는다"의 VM 판.

## 실행

```bash
# 1) 비밀값을 vars 파일로 (커밋 금지 — .gitignore 처리됨)
cp inventory/secrets.example.yml inventory/secrets.yml
$EDITOR inventory/secrets.yml   # monitor_password, dbtower_token 채우기

# 2) 실행 (멱등 — 재실행 안전)
ansible-playbook -i inventory/hosts.yml register-db.yml -e @inventory/secrets.yml
```

## 변수 (inventory/group_vars/all.yml + secrets.yml)

| 변수 | 설명 |
|---|---|
| `target_host` / `target_port` / `target_db` | 대상 DB 접속 정보 |
| `target_admin_user` / `target_admin_password` | 계정 생성용 관리자 계정 |
| `monitor_user` / `monitor_password` | 만들 모니터링 계정 |
| `dbtower_url` / `dbtower_token` | 관제탑 주소·ADMIN 토큰 |
| `instance_name` / `instance_type` | 등록 이름·기종 |

비밀번호·토큰은 `secrets.yml`(gitignore)로만 주입한다 — 저장소에 실제 값은 없다.

## 멱등성

- 계정 생성은 `community.postgresql.postgresql_user`(없으면 CREATE, 있으면 권한만 보정)
- 등록은 PUT(upsert) — 같은 `instance_name`이면 접속 정보만 갱신
- 두 번째 실행은 changed가 최소로 수렴한다
