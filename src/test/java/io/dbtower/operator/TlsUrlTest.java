package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TLS 강제 옵션(useTls)의 기종별 접속 문자열 반영 검증.
 *
 * 원칙 두 가지를 고정한다:
 * 1) useTls=false면 기존 URL과 바이트 단위로 동일 — 기존 등록의 하위 호환.
 * 2) useTls=true면 암호화를 강제하되, 인증서 검증을 끄는 우회(trustServerCertificate=true 등)는
 *    만들지 않는다 — 검증 우회 옵션은 "TLS를 켰다"는 착각만 주는 보안 구멍이라서다.
 */
class TlsUrlTest {

    private DatabaseInstance instance(DbmsType type, boolean tls) {
        return new DatabaseInstance("t", type, "db.example.com", 1234, "sample", "u", "p", tls);
    }

    /** 각 Operator의 jdbcUrl()은 protected — 리플렉션으로 호출한다(테스트 전용). */
    private String url(AbstractJdbcOperator op) throws Exception {
        Method m = AbstractJdbcOperator.class.getDeclaredMethod("jdbcUrl");
        m.setAccessible(true);
        return (String) m.invoke(op);
    }

    @Test
    void MySQL_useTls면_sslMode_REQUIRED_아니면_기존_URL_그대로() throws Exception {
        assertThat(url(new MySqlOperator(instance(DbmsType.MYSQL, false), null, null)))
                .isEqualTo("jdbc:mysql://db.example.com:1234/sample?connectTimeout=3000&socketTimeout=15000");
        assertThat(url(new MySqlOperator(instance(DbmsType.MYSQL, true), null, null)))
                .endsWith("&sslMode=REQUIRED");
    }

    @Test
    void PostgreSQL_useTls면_sslmode_require() throws Exception {
        assertThat(url(new PostgresOperator(instance(DbmsType.POSTGRESQL, false), null, null)))
                .doesNotContain("sslmode");
        assertThat(url(new PostgresOperator(instance(DbmsType.POSTGRESQL, true), null, null)))
                .endsWith("&sslmode=require");
    }

    @Test
    void MSSQL_useTls면_encrypt_true에_인증서_검증_유지() throws Exception {
        assertThat(url(new MsSqlOperator(instance(DbmsType.MSSQL, false), null, null)))
                .contains("encrypt=false");
        String tls = url(new MsSqlOperator(instance(DbmsType.MSSQL, true), null, null));
        assertThat(tls).contains("encrypt=true");
        // 검증 우회 금지 — trustServerCertificate는 반드시 false
        assertThat(tls).contains("trustServerCertificate=false");
    }

    @Test
    void Oracle_useTls면_TCPS_아니면_기존_EZConnect_그대로() throws Exception {
        assertThat(url(new OracleOperator(instance(DbmsType.ORACLE, false), null, null)))
                .isEqualTo("jdbc:oracle:thin:@//db.example.com:1234/sample");
        assertThat(url(new OracleOperator(instance(DbmsType.ORACLE, true), null, null)))
                .startsWith("jdbc:oracle:thin:@tcps://");
    }
}
