package io.dbtower.registry;

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

    /** 토이 단계라 평문 저장. 운영이라면 Vault/KMS 등 시크릿 관리로 대체해야 한다. */
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
