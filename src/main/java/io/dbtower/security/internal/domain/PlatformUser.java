package io.dbtower.security.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 플랫폼 사용자. 관리 대상 DB의 계정과는 완전히 별개 —
 * 이 계정은 "DBTower에 누가 들어올 수 있나"를, DatabaseInstance의 계정은
 * "DBTower가 대상 DB에 어떻게 붙나"를 담당한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    /**
     * 팀 라벨(LBAC, Phase 3·V14) — 지정되면 이 사용자는 같은 라벨의 인스턴스(+ 라벨 없는 전역
     * 인스턴스)만 본다. null = 스코프 없음(전역 — 기존 사용자 하위 호환). ADMIN은 라벨과 무관하게
     * 전역(관리자가 자기 눈을 가리면 관리가 안 된다). 라벨 변경은 다음 로그인부터 적용된다
     * (스코프가 인증 authority에 실리므로).
     */
    @Column(length = 100)
    private String teamLabel;

    public PlatformUser(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public void updateTeamLabel(String teamLabel) {
        this.teamLabel = teamLabel;
    }
}
