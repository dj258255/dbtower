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

    /**
     * 플랫폼 역할 — 쓰는 사람 기준(VERIFICATION 135절). 포함 관계는 PlatformRoles가 정한다:
     * ADMIN은 APPROVER·OPERATOR를, 두 역할은 REQUESTER를, REQUESTER는 VIEWER를 포함한다.
     * APPROVER와 OPERATOR는 서로를 포함하지 않는다 — 한 사람이 변경을 승인하고 실행까지 겸하지 않게.
     */
    public enum Role {
        /** 관제만 본다 — 대시보드·리포트 조회, 추정 실행계획. 대상 DB의 행 값은 보지 않는다 */
        VIEWER,
        /** 개발자·데이터 요청자 — 워크벤치 조회(행 값)와 변경 요청 */
        REQUESTER,
        /** DBA 리드 — 변경 요청 승인·반려, 승인 전 드라이런 */
        APPROVER,
        /** DBA 운영 — 승인 티켓 실행·되돌리기, 백업·세션 종료·심층 진단처럼 대상 DB에 닿는 일 */
        OPERATOR,
        /** 플랫폼 관리자 — 위 전부 + 인스턴스·접속 계정·보안·감사 */
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

    public void updateRole(Role role) {
        this.role = role;
    }
}
