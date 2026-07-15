# DBTower 셀프호스트 이미지 (배터리 포함)
#
# 왜 배터리 포함인가: 백업/복원 기능이 DB 클라이언트로 shell-out한다
#   MySQL=mysqldump, PostgreSQL=pg_dump/psql, MongoDB=mongodump/mongorestore.
#   이 바이너리들이 이미지에 없으면 셀프호스트 사용자의 백업이 조용히 실패한다 —
#   "정직하게 동작하는 제품" 원칙에 따라 번들한다.
#   SQL Server 백업은 서버사이드 T-SQL(BACKUP DATABASE)이라 클라이언트가 필요 없고,
#   Oracle 백업은 UNSUPPORTED(정직 표기)라 oracle-client도 필요 없다 — 그래서 안 넣는다.

# ---- 1) 빌드 스테이지 ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
# 래퍼·빌드 스크립트를 먼저 복사해 의존성 레이어를 소스 변경과 분리(캐시 적중률)
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
# bootJar만 실행 — build/jar를 부르면 실행 불가능한 -plain.jar도 생겨 COPY가 모호해진다.
# 테스트는 CI(ci.yml)가 게이트하므로 이미지 빌드에선 제외.
RUN ./gradlew --no-daemon clean bootJar -x test && cp build/libs/*.jar /app.jar

# ---- 2) 런타임 스테이지 ----
FROM eclipse-temurin:21-jre-jammy

# 백업/복원이 shell-out하는 DB 클라이언트 번들
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates curl gnupg lsb-release \
        default-mysql-client; \
    # PostgreSQL 16 클라이언트(PGDG) — Debian/Ubuntu 기본 pg_dump는 서버보다 낮아
    #   pg_dump는 서버 이상 버전이어야 하므로 PGDG에서 16을 맞춰 넣는다(앱 자신이 겪은 스큐)
    install -d /usr/share/postgresql-common/pgdg; \
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
        -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc; \
    echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
        > /etc/apt/sources.list.d/pgdg.list; \
    # MongoDB Database Tools(mongodump/mongorestore)
    curl -fsSL https://pgp.mongodb.com/server-7.0.asc | gpg --dearmor \
        -o /usr/share/keyrings/mongodb-server-7.0.gpg; \
    echo "deb [signed-by=/usr/share/keyrings/mongodb-server-7.0.gpg] https://repo.mongodb.org/apt/ubuntu $(lsb_release -cs)/mongodb-org/7.0 multiverse" \
        > /etc/apt/sources.list.d/mongodb-org-7.0.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends postgresql-client-16 mongodb-database-tools; \
    # 빌드 전용 도구 정리(런타임 healthcheck용 curl·ca-certificates는 남긴다)
    apt-get purge -y gnupg lsb-release; apt-get autoremove -y; \
    rm -rf /var/lib/apt/lists/*

# 비루트 실행
RUN useradd -r -u 1001 -m dbtower
WORKDIR /app
COPY --from=build /app.jar /app/dbtower.jar
# AI 1차 분석의 판단 기준(dbtower.security.rules-path=docs/ai-analysis-rules.md, 작업 디렉터리 /app 기준).
# 이미지에 포함하지 않으면 AiAnalyzer가 빈 프롬프트로 판정해 "일관 판정" 가치가 사라진다.
COPY docs/ai-analysis-rules.md /app/docs/ai-analysis-rules.md
# 백업 산출물 기본 위치(application.yml dbtower.backup.dir=./backups) — 볼륨 마운트 지점
RUN mkdir -p /app/backups && chown -R dbtower:dbtower /app
USER 1001

EXPOSE 8080
ENV JAVA_OPTS=""
# actuator 헬스로 컨테이너 상태 판정(readiness는 앱 부팅 후 200)
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/dbtower.jar"]
