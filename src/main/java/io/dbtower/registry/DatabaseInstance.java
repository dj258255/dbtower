package io.dbtower.registry;

import io.dbtower.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 관제 대상으로 등록된 DB 인스턴스. 등록 이후 모든 기능은 DbmsOperator 인터페이스로만 다룬다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DatabaseInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DbmsType type;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port;

    /** 접속 대상 데이터베이스(스키마) 이름 */
    @Column(nullable = false)
    private String dbName;

    @Column(nullable = false)
    private String username;

    /**
     * AES-256-GCM으로 암호화 저장(키 미설정 시 평문 — SecretCipher 참고).
     * 접두사 디스패치("enc:v1:") 덕에 암호화 도입 전 평문 행도 그대로 읽힌다.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 전송 암호화 강제(TLS) — Atlas·Azure SQL·RDS(rds.force_ssl)처럼 TLS를 강제하는 관리형
     * 서비스에 붙기 위한 옵션. 기종별 반영: MySQL sslMode=REQUIRED, PG sslmode=require,
     * MSSQL encrypt=true, Oracle tcps, Mongo sslSettings. 서버 인증서 검증은 JVM truststore
     * 기본을 따른다 — 사설 CA는 truststore에 등록해야 하며, 검증을 끄는 옵션은 일부러 안 둔다.
     */
    @Column(nullable = false)
    private boolean useTls = false;

    /**
     * 수집 활성화 (Phase F, 스케일 제어) — 문제 인스턴스를 <b>일시 격리</b>하는 스위치.
     * false면 스냅샷 수집·운영 경보 폴러가 이 인스턴스를 건너뛴다(등록 정보는 남긴다). 폭주하거나
     * 접속이 불안정한 대상을 삭제하지 않고 잠시 관제에서 빼, 그 대상 때문에 전체 수집이 느려지는 걸 막는다.
     * 기본 true(등록 즉시 관제). V9에서 컬럼 추가, 기존 행은 true로 백필. @ColumnDefault로 컬럼을 생략한
     * INSERT(레거시 데이터·raw insert)도 true로 채워, Flyway(DEFAULT TRUE)와 Hibernate 생성 스키마를 맞춘다.
     */
    @org.hibernate.annotations.ColumnDefault("true")
    @Column(nullable = false)
    private boolean collectionEnabled = true;

    /**
     * 담당 팀/Slack 채널 라벨 (심화 아크 4 — 레퍼런스 "Slack Group" 대응). 웹훅 알림·상세 패널에
     * "이 DB는 누구 소관인가"를 표시한다. Phase 3 LBAC(팀 스코핑)가 같은 컬럼을 재사용할 예정.
     * null=미지정(강제 아님 — 표기만 생략).
     */
    @Column(length = 100)
    private String teamLabel;

    /**
     * 콘솔 딥링크 (레퍼런스 "AWS Link: Performance Insight" 대응) — 조직이 쓰는 외부 콘솔 URL.
     * RDS면 PI, 셀프호스트면 Grafana, 혹은 내부 위키 — AWS SDK 연동 대신 URL 일반화로 흡수한다.
     * http/https만 허용(등록 API에서 검증) — 화면 링크(href)로 들어가므로 스킴 제한이 곧 방어선.
     */
    @Column(length = 500)
    private String consoleUrl;

    public DatabaseInstance(String name, DbmsType type, String host, int port,
                            String dbName, String username, String password) {
        this(name, type, host, port, dbName, username, password, false);
    }

    public DatabaseInstance(String name, DbmsType type, String host, int port,
                            String dbName, String username, String password, boolean useTls) {
        this.name = name;
        this.type = type;
        this.host = host;
        this.port = port;
        this.dbName = dbName;
        this.username = username;
        this.password = password;
        this.useTls = useTls;
    }

    /**
     * 접속 정보 갱신 — 멱등 등록(upsert)용. 이름은 논리 식별자라 바꾸지 않고,
     * IaC(Ansible/K8s/Terraform)가 같은 이름으로 재등록하면 접속 정보만 최신으로 덮는다.
     * createdAt은 유지(최초 등록 시각) — "언제부터 관제했나"의 의미를 지킨다.
     */
    public void updateConnection(DbmsType type, String host, int port,
                                 String dbName, String username, String password, boolean useTls) {
        this.type = type;
        this.host = host;
        this.port = port;
        this.dbName = dbName;
        this.username = username;
        this.password = password;
        this.useTls = useTls;
    }

    /** 수집 활성/격리 토글 (Phase F). 문제 인스턴스를 삭제하지 않고 관제에서 잠시 뺀다. */
    public void setCollectionEnabled(boolean enabled) {
        this.collectionEnabled = enabled;
    }

    /**
     * 이 DB가 올라간 호스트의 node_exporter를 가리키는 Prometheus 라벨 셀렉터 (Phase 5, V16 —
     * 예: instance="db-node-3:9100"). 디스크는 DB가 아니라 호스트 자원이라 별도 매핑이 필요하다.
     * null = 미지정(전 노드 집계 — 단일 노드 데모에선 그 자체로 정확).
     */
    @Column(length = 200)
    private String nodeFilter;

    /**
     * 조직 태그 (V30 — 레퍼런스의 환경·리전·클러스터 선택 대응). 전부 선택(null=미지정)이고 필터·표기용.
     * environment=prod/staging/dev, region=자유 라벨(ap-northeast-2·on-prem-dc1 등),
     * clusterLabel=복제 그룹·서비스 묶음. AWS 고유 개념을 셀프호스트 이기종에 일반화해 담는다.
     */
    @Column(length = 50)
    private String environment;

    @Column(length = 50)
    private String region;

    @Column(length = 100)
    private String clusterLabel;

    /** 담당 라벨·콘솔 링크·노드 매핑·조직 태그 갱신 — 접속 정보와 별개의 운영 메타라 updateConnection과 분리. */
    public void updateMeta(String teamLabel, String consoleUrl, String nodeFilter,
                           String environment, String region, String clusterLabel) {
        this.teamLabel = teamLabel;
        this.consoleUrl = consoleUrl;
        this.nodeFilter = nodeFilter;
        this.environment = environment;
        this.region = region;
        this.clusterLabel = clusterLabel;
    }

    /**
     * 서버 그룹 키 (Phase 4 — 서버 공유 인지). 등록 단위는 DB(host·port·dbName)라 같은 서버에
     * DB 여러 개를 등록하면 서버 전역 신호(복제 상태·세션·데드락)가 인스턴스 수만큼 중복 감지·경보된다.
     * 같은 host:port = 같은 DBMS 서버로 보고 그룹당 1회만 판정한다. 엔티티 추가 없이 계산 키로 시작 —
     * 호스트명은 대소문자 무관(DNS)이라 소문자 정규화하되, DNS 해석은 하지 않는다(결정적·정직한 키).
     */
    public String serverKey() {
        return host.toLowerCase() + ":" + port;
    }
}
