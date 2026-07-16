package io.dbtower.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 백업 산출물 암호화 (3-2-1-1-0) — 왕복(암호화→판별→복호)과 게이트·변조 거부 계약 고정.
 * 산출물은 대상 DB 전체 데이터의 가장 농축된 유출면 — 평문이 파일 어디에도 남지 않아야 하고,
 * 변조된 산출물은 GCM 태그 검증으로 조용히 오염되는 대신 명확히 실패해야 한다.
 */
class BackupArtifactCipherTest {

    @TempDir
    Path dir;

    private static String randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    private Path encrypted(BackupArtifactCipher cipher, String content) throws IOException {
        Path f = dir.resolve("artifact.sql");
        try (OutputStream out = cipher.wrap(Files.newOutputStream(f))) {
            out.write(content.getBytes());
        }
        return f;
    }

    @Test
    void 암호화_왕복_후_원문이_복원되고_파일엔_평문이_없다() throws IOException {
        BackupArtifactCipher cipher = new BackupArtifactCipher(randomKey());
        String dump = "CREATE TABLE orders (id BIGINT); INSERT INTO orders VALUES (42);";
        Path f = encrypted(cipher, dump);

        assertTrue(cipher.isEncrypted(f));
        assertFalse(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("CREATE TABLE"), "산출물 파일에 평문이 남으면 암호화가 아니다");

        Path plain = cipher.decryptToTemp(f);
        try {
            assertEquals(dump, Files.readString(plain));
        } finally {
            Files.deleteIfExists(plain);
        }
    }

    @Test
    void 키_미설정이면_평문_그대로다_기능_게이트() throws IOException {
        BackupArtifactCipher off = new BackupArtifactCipher("");
        assertFalse(off.enabled());
        Path f = encrypted(off, "plain dump");
        assertFalse(off.isEncrypted(f));
        assertEquals("plain dump", Files.readString(f));
    }

    @Test
    void 변조된_산출물은_조용히_오염되는_대신_명확히_실패한다() throws IOException {
        BackupArtifactCipher cipher = new BackupArtifactCipher(randomKey());
        Path f = encrypted(cipher, "sensitive dump content");
        byte[] bytes = Files.readAllBytes(f);
        bytes[bytes.length - 3] ^= 0x01;   // 암호문 꼬리 1비트 변조
        Files.write(f, bytes);

        assertThrows(IOException.class, () -> cipher.decryptToTemp(f),
                "GCM 태그 불일치는 복호 실패여야 한다 — 변조가 성공으로 위장되면 안 된다");
    }

    @Test
    void 다른_키로는_복호할_수_없다() throws IOException {
        Path f = encrypted(new BackupArtifactCipher(randomKey()), "dump");
        BackupArtifactCipher wrongKey = new BackupArtifactCipher(randomKey());
        assertThrows(IOException.class, () -> wrongKey.decryptToTemp(f));
    }

    @Test
    void 키_길이가_틀리면_기동을_거부한다() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class, () -> new BackupArtifactCipher(shortKey),
                "짧은 키를 조용히 받으면 보안 강도가 소리 없이 깎인다");
    }
}
