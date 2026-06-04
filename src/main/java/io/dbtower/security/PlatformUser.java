package io.dbtower.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * 플랫폼 사용자. 관리 대상 DB의 계정과는 완전히 별개 —
 * 이 계정은 "DBTower에 누가 들어올 수 있나"를, DatabaseInstance의 계정은
 * "DBTower가 대상 DB에 어떻게 붙나"를 담당한다.
 */
@Entity
public class PlatformUser {

    public enum Role {
        /** 조회·진단(통계, 시점 비교, explain) — 개발자용 */
        VIEWER,
        /** + 인스턴스 등록/삭제, 백업 실행, 토큰 조회 — 플랫폼 관리자용 */
        ADMIN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /** BCrypt 해시 — 평문/복호화 가능한 형태는 저장하지 않는다 */
    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    protected PlatformUser() {
    }

    public PlatformUser(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
}
