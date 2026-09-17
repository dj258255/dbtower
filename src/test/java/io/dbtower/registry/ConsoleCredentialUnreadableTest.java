package io.dbtower.registry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장할 때와 다른 키로 앱이 뜬 경우 — 암호화된 콘솔 계정 하나가 워크벤치 인스턴스 목록 전체를 실패시켰다.
 * 목록 화면 왼쪽 아래에 "암호화 키 미설정 — enabled()를 먼저 확인해야 한다"가 그대로 떴다.
 *
 * <p>여기서는 이 테스트 키로는 풀 수 없는 암호문(다른 키로 만든 것과 같다)을 원시 JDBC로 심어, 목록은 뜨고 실제로 비밀번호가
 * 필요한 순간에만 사람이 할 일을 담은 문장으로 막히는지 본다.</p>
 */
@SpringBootTest(properties = "dbtower.security.encryption-key=" + ConsoleCredentialUnreadableTest.TEST_KEY)
@AutoConfigureMockMvc
class ConsoleCredentialUnreadableTest {

    // SecretCipherTest.TEST_KEY와 같은 값 — 그쪽 상수는 security 패키지 전용이라 옮겨 적는다
    static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Autowired DatabaseInstanceRepository instances;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConsoleCredentialService credentials;
    @Autowired MockMvc mvc;

    private Long instanceId;

    @BeforeEach
    void seed() {
        instanceId = instances.save(new DatabaseInstance("unreadable-cred", DbmsType.MYSQL, "127.0.0.1", 1,
                "sample", "u", "p")).getId();
        jdbc.update("insert into instance_credential (instance_id, purpose, username, password, updated_at)"
                + " values (?, 'READ', 'reader', 'plain-read-pw', now())", instanceId);
        // 이 키로 만든 것이 아닌 암호문 — 복호화하면 GCM 태그 검증이 실패한다
        byte[] other = new byte[44];
        new SecureRandom().nextBytes(other);
        jdbc.update("insert into instance_credential (instance_id, purpose, username, password, updated_at)"
                + " values (?, 'WRITE', 'writer', ?, now())", instanceId, "enc:v1:" + Base64.getEncoder().encodeToString(other));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from instance_credential where instance_id = ?", instanceId);
        instances.deleteById(instanceId);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 풀_수_없는_계정이_있어도_요약은_비밀번호를_읽지_않아_실패하지_않는다() {
        List<CredentialSummary> summaries = credentials.summaries(instanceId);

        assertThat(summaries).extracting(CredentialSummary::purpose)
                .containsExactly(CredentialPurpose.READ, CredentialPurpose.WRITE);
        assertThat(summaries).extracting(CredentialSummary::username).containsExactly("reader", "writer");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 실제로_비밀번호가_필요할_때만_사람이_할_일을_담은_문장으로_막힌다() {
        assertThat(credentials.find(instanceId, CredentialPurpose.READ)).get()
                .extracting(ConsoleCredential::password).isEqualTo("plain-read-pw");

        assertThatThrownBy(() -> credentials.find(instanceId, CredentialPurpose.WRITE))
                .isInstanceOf(CredentialUnreadableException.class)
                .hasMessageContaining("변경 계정")
                .hasMessageContaining("다시 등록")
                // 개발자용 문장이 화면으로 새지 않는다
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("enabled()").doesNotContain("GCM"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 워크벤치_인스턴스_목록은_그대로_뜬다() throws Exception {
        String body = mvc.perform(get("/api/workbench/instances"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("unreadable-cred");
    }
}
