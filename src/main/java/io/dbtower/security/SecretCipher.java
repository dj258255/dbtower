package io.dbtower.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 인스턴스 접속 비밀번호 같은 민감 문자열의 AES-256-GCM 암복호화.
 *
 * 키는 base64로 인코딩한 32바이트(DBTOWER_ENCRYPTION_KEY). 암호화마다 랜덤 12바이트 IV를
 * 새로 뽑아 암호문 앞에 붙이고 전체를 base64로 내보낸다 — 같은 평문도 저장할 때마다
 * 다른 암호문이 되고, GCM 인증 태그(128비트)가 변조를 복호화 시점에 잡아낸다.
 *
 * 키 미설정 시 암호화 비활성으로 기동한다(fail-open). 랜덤 키를 만들어 fail-closed로
 * 가면 재기동 시 이전 키가 사라져 기존 암호문을 영영 못 푸는 더 큰 사고가 된다 —
 * (기동마다 새로 만들면 그만인 API 토큰과 달리, 암호화 키는 저장된 데이터와 운명을 같이한다).
 * 대신 WARN 로그로 평문 저장 상태임을 명확히 알린다.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    /** null이면 암호화 비활성(키 미설정) */
    private final SecretKey key;

    public SecretCipher(@Value("${dbtower.security.encryption-key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            this.key = null;
            log.warn("DBTOWER_ENCRYPTION_KEY 미설정 — 인스턴스 비밀번호가 평문으로 저장됩니다. "
                    + "base64 인코딩 32바이트 키를 설정하세요 (예: openssl rand -base64 32)");
        } else {
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(encodedKey.trim());
            } catch (IllegalArgumentException e) {
                // 키가 '있는데 잘못된' 것은 미설정과 다르다 — 조용히 평문으로 넘어가면 안 되므로 기동 실패
                throw new IllegalStateException("DBTOWER_ENCRYPTION_KEY가 base64가 아닙니다", e);
            }
            if (raw.length != KEY_BYTES) {
                throw new IllegalStateException(
                        "DBTOWER_ENCRYPTION_KEY는 base64 인코딩 " + KEY_BYTES + "바이트여야 합니다 (현재 "
                                + raw.length + "바이트)");
            }
            this.key = new SecretKeySpec(raw, "AES");
        }
        SecretCipherHolder.set(this);
    }

    /** 키가 설정되어 암호화가 활성인지 — 호출자는 이 값에 따라 저장 형식을 결정한다 */
    public boolean enabled() {
        return key != null;
    }

    /** 평문을 base64(IV + 암호문 + GCM 태그)로. 비활성 상태에서 부르면 예외 — 호출자가 enabled()로 분기할 계약 */
    public String encrypt(String plain) {
        requireEnabled();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            // IV를 암호문 앞에 붙여 한 덩어리로 — 복호화에 필요한 재료를 값 자체가 들고 다닌다
            byte[] out = new byte[IV_BYTES + cipherText.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(cipherText, 0, out, IV_BYTES, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("암호화 실패", e);
        }
    }

    /** encrypt가 만든 base64를 평문으로. 변조된 값은 GCM 태그 검증에서 예외로 드러난다 */
    public String decrypt(String encoded) {
        requireEnabled();
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("암호문이 IV보다 짧습니다 — 손상된 값");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // AEADBadTagException(변조·키 불일치)과 base64 손상을 하나의 의미로 — "이 값은 이 키로 만든 것이 아니다"
            throw new IllegalStateException("복호화 실패 — 변조되었거나 다른 키로 암호화된 값", e);
        }
    }

    private void requireEnabled() {
        if (key == null) {
            throw new IllegalStateException("암호화 키 미설정 — enabled()를 먼저 확인해야 한다");
        }
    }
}
