package io.dbtower.operator;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 백업 산출물 저장 암호화 (3-2-1-1-0의 마지막 조각 — 산출물이 유출돼도 데이터가 아니게).
 *
 * 백업 파일은 대상 DB의 전체 데이터를 담는 가장 농축된 유출면인데, 지금까지 로컬·원격 보관 모두
 * 평문이었다. 키(dbtower.backup.encryption-key, base64 32바이트 — 비밀번호 암호화 키와 같은 형식,
 * 다른 용도라 별도 키)를 설정하면 산출물을 AES-256-GCM 스트리밍으로 암호화해 쓴다.
 * 파일 형식: MAGIC("DBTENC1\n" 8B) + IV(12B) + GCM 암호문(+태그). 파일명은 그대로 —
 * 체인 보충·ts 마커 등 파일명 규약이 암호화와 무관하게 동작한다.
 *
 * 미설정이면 현행 평문(기능 게이트) — 단, 배포 프로필 fail-closed는 비밀번호 키(SecretCipher)의
 * 정책이고 산출물 키는 선택으로 둔다(외부 보관을 안 쓰는 로컬 데모까지 강제하지 않는다).
 * 복호는 두 곳: 복원 검증(BackupService가 임시 평문으로 풀어 기존 검증 경로에 전달)과
 * 사람의 수동 복원(PITR 문안이 복호 절차를 안내). 원격 보관은 암호문 그대로 올라간다 —
 * 오프사이트가 곧 신뢰 경계 밖이라 암호문 업로드가 목적 그 자체다.
 */
@Component
public class BackupArtifactCipher {

    private static final Logger log = LoggerFactory.getLogger(BackupArtifactCipher.class);

    static final byte[] MAGIC = "DBTENC1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    /** null = 암호화 비활성(키 미설정) */
    private final SecretKey key;

    public BackupArtifactCipher(@Value("${dbtower.backup.encryption-key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(encodedKey.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("dbtower.backup.encryption-key는 base64 32바이트(AES-256)여야 합니다 — 현재 " + raw.length + "바이트");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    @PostConstruct
    void register() {
        // BackupCommands는 정적 유틸(모든 산출물 쓰기의 단일 관문)이라 빈 주입이 닿지 않는다 —
        // 시그니처를 5개 Operator에 전파하는 대신 기동 시 1회 정적 등록으로 관문을 유지한다.
        BackupCommands.artifactCipher(this);
        if (enabled()) {
            log.info("백업 산출물 암호화 활성(AES-256-GCM) — 로컬·원격 보관 모두 암호문으로 저장된다");
        }
    }

    public boolean enabled() {
        return key != null;
    }

    /** 산출물 쓰기 스트림 래핑 — 비활성이면 그대로 반환(현행 평문). */
    public OutputStream wrap(OutputStream raw) throws IOException {
        if (!enabled()) {
            return raw;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            raw.write(MAGIC);
            raw.write(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new CipherOutputStream(raw, cipher);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("산출물 암호화 초기화 실패: " + e.getMessage(), e);
        }
    }

    /** 파일이 이 형식으로 암호화됐는가 — MAGIC 헤더 검사(키 미설정 노드도 판별은 가능). */
    public boolean isEncrypted(Path artifact) {
        try (InputStream in = Files.newInputStream(artifact)) {
            byte[] head = in.readNBytes(MAGIC.length);
            return Arrays.equals(head, MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 복원 검증용 임시 평문 — 호출부가 사용 후 반드시 삭제한다(평문 수명 최소화).
     * GCM 태그 검증이 복호와 함께 이뤄지므로, 변조된 산출물은 여기서 명확히 실패한다(조용한 오염 방지).
     */
    public Path decryptToTemp(Path artifact) throws IOException {
        if (!enabled()) {
            throw new IOException("암호화된 산출물인데 복호 키가 없다(dbtower.backup.encryption-key 미설정)");
        }
        Path temp = Files.createTempFile("dbtower-plain-", "-" + artifact.getFileName());
        try (InputStream in = Files.newInputStream(artifact);
             OutputStream out = Files.newOutputStream(temp)) {
            byte[] head = in.readNBytes(MAGIC.length);
            if (!Arrays.equals(head, MAGIC)) {
                throw new IOException("암호화 산출물이 아니다: " + artifact);
            }
            byte[] iv = in.readNBytes(IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            new CipherInputStream(in, cipher).transferTo(out);
            return temp;
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw new IOException("산출물 복호 실패(키 불일치 또는 변조): " + e.getMessage(), e);
        }
    }
}
