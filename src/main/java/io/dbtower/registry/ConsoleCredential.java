package io.dbtower.registry;

/**
 * 워크벤치 콘솔 계정의 접속 자격증명 — 모니터 계정(DatabaseInstance의 username/password)과 분리된 조회·변경 계정.
 * toString이 비밀번호를 찍지 않게 덮는다: record 기본 toString은 모든 컴포넌트를 출력해 로그 한 줄로 새기 쉽다.
 */
public record ConsoleCredential(String username, String password) {

    @Override
    public String toString() {
        return "ConsoleCredential[username=" + username + ", password=****]";
    }
}
