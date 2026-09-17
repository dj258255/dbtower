package io.dbtower.security;

/**
 * 저장된 비밀을 이 프로세스가 풀 수 없다 — 키가 없거나, 저장할 때와 다른 키로 떠 있거나, 값이 손상됐다.
 *
 * <p>일반 {@link IllegalStateException}과 구분하는 이유: 호출자가 "비밀을 못 읽었다"를 알아야
 * 사람이 할 일(다시 등록)을 알려 줄 수 있다. 전에는 키 없이 뜬 앱에서 워크벤치 인스턴스 목록이
 * "암호화 키 미설정 — enabled()를 먼저 확인해야 한다"라는 개발자용 문장으로 통째로 실패했다.</p>
 */
public class SecretUnreadableException extends IllegalStateException {

    public SecretUnreadableException(String message) {
        super(message);
    }

    public SecretUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }
}
