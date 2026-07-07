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
}
