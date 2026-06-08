package io.dbtower.registry;

import io.dbtower.security.EncryptedStringConverter;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 관제 대상으로 등록된 DB 인스턴스. 등록 이후 모든 기능은 DbmsOperator 인터페이스로만 다룬다. */
@Entity
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

    protected DatabaseInstance() {
    }

    public DatabaseInstance(String name, DbmsType type, String host, int port,
                            String dbName, String username, String password) {
        this.name = name;
        this.type = type;
        this.host = host;
        this.port = port;
        this.dbName = dbName;
        this.username = username;
        this.password = password;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public DbmsType getType() { return type; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getDbName() { return dbName; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
