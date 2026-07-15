package io.dbtower.security.internal;

import io.dbtower.security.SecretCipher;

/**
 * JPA 프로바이더가 컨버터를 Spring 밖에서 직접 생성하는 경우를 위한 정적 브리지.
 *
 * Spring Boot는 Hibernate에 SpringBeanContainer를 붙여 AttributeConverter를 빈으로
 * 주입해 주므로 평소에는 이 홀더를 거치지 않는다(EncryptedStringConverter의 주입 생성자 경로).
 * 다만 그 연결이 없는 환경(순수 JPA 부트스트랩 등)에서 컨버터가 기본 생성자로 만들어질 때를
 * 대비해, SecretCipher 빈이 생성되는 시점에 자신을 여기 등록해 둔다.
 */
public final class SecretCipherHolder {

    private static volatile SecretCipher instance;

    private SecretCipherHolder() {
    }

    public static void set(SecretCipher cipher) {
        instance = cipher;
    }

    public static SecretCipher get() {
        return instance;
    }
}
