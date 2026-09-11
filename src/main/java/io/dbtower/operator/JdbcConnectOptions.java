package io.dbtower.operator;

import java.util.Map;

/**
 * JDBC 접속의 기종별 조정값 — 로그인 단계에 드라이버로 넘길 속성과, 로그인이 끝난 커넥션에 되돌릴 네트워크 읽기 제한.
 *
 * @param loginProperties            접속할 때 드라이버에 넘기는 속성(예: Oracle {@code oracle.net.CONNECT_TIMEOUT}). 비밀값은 넣지 않는다.
 * @param networkTimeoutAfterLoginMs 로그인 뒤 {@code Connection.setNetworkTimeout} 값(0은 제한 없음). null이면 URL·드라이버 기본 그대로 둔다.
 */
public record JdbcConnectOptions(Map<String, String> loginProperties, Integer networkTimeoutAfterLoginMs) {

    public static final JdbcConnectOptions DEFAULT = new JdbcConnectOptions(Map.of(), null);

    public JdbcConnectOptions {
        loginProperties = Map.copyOf(loginProperties);
    }
}
