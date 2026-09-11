# 데모 대상 PostgreSQL = 공식 postgres:16 + HypoPG.
# 인덱스 어드바이저(가상 인덱스로 생성 전 비용 비교)는 HypoPG가 있어야 동작하는데 공식 이미지에는 없다.
# 예전 compose는 공식 이미지를 그대로 써서, 데모를 띄우면 인덱스 제안이 UNSUPPORTED로만 끝났다(VERIFICATION 137절).
# 공식 이미지에 PGDG apt 저장소가 이미 설정돼 있어 같은 메이저 버전 패키지를 그대로 받는다.
FROM postgres:16

RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-16-hypopg \
    && rm -rf /var/lib/apt/lists/*
