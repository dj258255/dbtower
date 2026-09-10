package io.dbtower.registry;

/**
 * 콘솔 계정의 용도. 조회(READ)와 승인된 변경의 실행(WRITE)을 서로 다른 DB 계정으로 나눈다 —
 * 조회 경로에서 쓰기 권한이 아예 없으면, 분류기가 틀려도 DB가 거부한다.
 */
public enum CredentialPurpose {
    READ,
    WRITE
}
