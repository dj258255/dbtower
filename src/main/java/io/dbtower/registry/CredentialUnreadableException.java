package io.dbtower.registry;

/**
 * 콘솔 계정 비밀번호를 풀 수 없다 — 사람이 할 일(관리 화면에서 다시 등록)을 담아 올린다.
 * {@link IllegalStateException}이라 응답은 409와 이 문장이다(GlobalExceptionHandler). 원인(키·암호문)은 로그에만 남는다.
 */
public class CredentialUnreadableException extends IllegalStateException {

    public CredentialUnreadableException(CredentialPurpose purpose, Throwable cause) {
        super((purpose == CredentialPurpose.WRITE ? "변경" : "조회") + " 계정 비밀번호를 읽을 수 없습니다. "
                + "저장할 때와 다른 암호화 키로 앱이 떠 있습니다. 관리 화면에서 이 인스턴스의 "
                + (purpose == CredentialPurpose.WRITE ? "변경" : "조회") + " 계정을 다시 등록하세요", cause);
    }
}
