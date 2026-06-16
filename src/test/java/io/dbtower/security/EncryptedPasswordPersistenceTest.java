package io.dbtower.security;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨버터가 실제 JPA 저장 경로에서 동작함을 증명한다 — 단위 테스트가 아니라 통합으로.
 *
 * JPA AttributeConverter는 원래 프로바이더가 직접 생성해 Spring 빈 주입이 안 되는 것이
 * 함정인데, Spring Boot는 Hibernate에 SpringBeanContainer를 연결해 컨버터를 빈으로
 * 해소한다. 이 테스트가 통과한다는 것 = 주입 생성자 경로(SecretCipher 주입)가 실제로
 * 쓰인다는 것 — 만약 기본 생성자로 직접 생성됐다면 정적 브리지가 받쳐 주고, 그마저
 * 없으면 저장 시점에 예외가 나므로 "조용히 평문 저장"으로 새는 경우는 없다.
 */
@SpringBootTest(properties = "dbtower.security.encryption-key=" + SecretCipherTest.TEST_KEY)
class EncryptedPasswordPersistenceTest {

    @Autowired
    DatabaseInstanceRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 저장하면_DB_컬럼에는_암호문이_남고_조회하면_평문으로_돌아온다() {
        DatabaseInstance saved = repository.save(new DatabaseInstance(
                "enc-roundtrip", DbmsType.MYSQL, "localhost", 3306, "app", "root", "plain-pw"));

        // 메타 DB의 실제 컬럼 값 — 평문이 아니라 enc:v1: 접두 암호문이어야 한다
        String rawColumn = jdbc.queryForObject(
                "select password from database_instance where id = ?", String.class, saved.getId());
        assertThat(rawColumn).startsWith("enc:v1:");
        assertThat(rawColumn).doesNotContain("plain-pw");

        // JPA로 다시 읽으면 컨버터가 복호화한 평문 — 여기까지 통과해야 빈 주입이 증명된다
        DatabaseInstance loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getPassword()).isEqualTo("plain-pw");
    }

    @Test
    void 암호화_도입_전의_평문_행도_그대로_읽힌다() {
        // 컨버터를 거치지 않고 평문을 직접 심는다 — 기존 운영 데이터를 재현
        jdbc.update("insert into database_instance"
                        + " (name, type, host, port, db_name, username, password, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, now())",
                "legacy-row", "POSTGRESQL", "localhost", 5432, "app", "postgres", "legacy-plain-pw");
        Long id = jdbc.queryForObject(
                "select id from database_instance where name = 'legacy-row'", Long.class);

        DatabaseInstance loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getPassword()).isEqualTo("legacy-plain-pw");
    }
}
