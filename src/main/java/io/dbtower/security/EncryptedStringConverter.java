package io.dbtower.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * 민감 문자열 컬럼의 투명 암복호화. autoApply를 켜지 않는다 —
 * 어떤 컬럼이 암호화되는지는 엔티티의 @Convert 선언에 명시적으로 드러나야 한다.
 *
 * 저장 형식은 접두사 디스패치: 암호화한 값에만 "enc:v1:"을 붙인다.
 * - 조회 시 접두사가 있으면 복호화, 없으면 그대로 반환 — 암호화 도입 전에 저장된
 *   평문 행이 마이그레이션 없이 그대로 읽히고, 그 행이 다음에 저장될 때 자연스럽게
 *   암호문으로 바뀐다(점진적 재암호화).
 * - v1은 알고리즘 버전 — 나중에 키·알고리즘을 바꿔도 접두사로 구형식을 식별해
 *   같은 방식으로 점진 전환할 수 있다.
 *
 * 키 미설정 시에는 평문 그대로 저장한다(fail-open 근거는 SecretCipher 주석 참고).
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    static final String PREFIX = "enc:v1:";

    /** null이면 정적 브리지 경로 — cipher()에서 홀더를 통해 해소한다 */
    private final SecretCipher injected;

    /** Spring 경로 — Boot가 Hibernate에 SpringBeanContainer를 연결해 이 생성자로 주입한다 */
    public EncryptedStringConverter(SecretCipher cipher) {
        this.injected = cipher;
    }

    /** JPA 직접 생성 경로 — 빈 주입이 안 되는 부트스트랩에서만 쓰이는 예비 통로 */
    public EncryptedStringConverter() {
        this.injected = null;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        SecretCipher cipher = cipher();
        if (!cipher.enabled()) {
            return attribute;
        }
        return PREFIX + cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || !dbData.startsWith(PREFIX)) {
            // 접두사가 없으면 암호화 도입 전의 평문 행 — 그대로 통과 (하위 호환)
            return dbData;
        }
        // 접두사가 있는데 키가 없으면 여기서 예외 — 평문인 척 돌려주는 것보다 시끄럽게 실패하는 편이 안전
        return cipher().decrypt(dbData.substring(PREFIX.length()));
    }

    private SecretCipher cipher() {
        if (injected != null) {
            return injected;
        }
        SecretCipher bridged = SecretCipherHolder.get();
        if (bridged == null) {
            throw new IllegalStateException(
                    "SecretCipher가 아직 준비되지 않았습니다 — Spring 컨텍스트 기동 전에 컨버터가 호출됨");
        }
        return bridged;
    }
}
