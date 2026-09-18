package io.dbtower.aiops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.advisor.AdvisorService;
import io.dbtower.aiops.internal.job.AiOperationReaper;
import io.dbtower.analysis.AiAnalyzer;
import io.dbtower.backup.BackupFreshness;
import io.dbtower.backup.BackupFreshnessService;
import io.dbtower.finops.internal.FinOpsService;
import io.dbtower.insight.BaselineService;
import io.dbtower.insight.ComparisonService;
import io.dbtower.insight.ComparisonService.CompareResult;
import io.dbtower.insight.ComparisonService.WindowSummary;
import io.dbtower.insight.QueryDiff;
import io.dbtower.insight.WaitEventHistoryService;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import io.dbtower.score.HealthScoreView;
import io.dbtower.score.internal.ScoreService;
import io.dbtower.slo.SloService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 운영 작업 관통 검증 — 컨트롤러 -> 보안 -> 서비스·워크플로 -> 실제 사실 수집기 -> 결과 검증 -> H2.
 *
 * <p>사실 원천(점수·쿼리 비교·백업 등)은 H2에 스냅샷 테이블이 없어 목으로 넣고, 사실 수집기·수치 대조·전이 표·
 * Outbox 선점은 실빈으로 태운다(IncidentReportIntegrationTest와 같은 방침). 모델은 목이다 — 로컬에 claude CLI가 있어
 * 실빈이면 실제 모델을 부른다. 실제 모델까지 이은 관통은 docs/verify/VERIFICATION.md 169절에 따로 남긴다.</p>
 */
@SpringBootTest(properties = {"dbtower.aiops.reaper-initial-delay-ms=3600000", "dbtower.aiops.max-active-per-requester=3",
        "dbtower.aiops.max-active-alert-jobs=3"})
@AutoConfigureMockMvc
class AiOperationFlowIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired DatabaseInstanceRepository instances;
    @Autowired JdbcTemplate jdbc;
    @Autowired AiOperationReaper reaper;

    // 인터페이스(ScoreQuery·FinOpsQuery)를 목으로 두면 구체 클래스를 주입받는 컨트롤러가 깨져 구체 서비스를 목으로 둔다
    @MockitoBean ScoreService scoreQuery;
    @MockitoBean ComparisonService comparisonService;
    @MockitoBean WaitEventHistoryService waitEvents;
    @MockitoBean BaselineService baselineService;
    @MockitoBean BackupFreshnessService backupFreshness;
    @MockitoBean SloService sloService;
    @MockitoBean AdvisorService advisorService;
    @MockitoBean FinOpsService finOps;
    @MockitoBean AiAnalyzer analyzer;

    private Long teamA;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from ai_operation_outbox");
        jdbc.update("delete from ai_operation_result");
        jdbc.update("delete from ai_operation_job");
        DatabaseInstance instance = new DatabaseInstance("orders-db", DbmsType.POSTGRESQL, "localhost", 1, "app", "u", "p");
        instance.updateMeta("team-a", null, null, "prod", null, null);
        teamA = instances.save(instance).getId();

        when(scoreQuery.scoreFor(anyLong())).thenReturn(new HealthScoreView(teamA, 55, "F", false));
        WindowSummary base = new WindowSummary(1000, 12000.0, 12.0, 5000, 4);
        WindowSummary target = new WindowSummary(980, 47530.0, 48.5, 90000, 4);
        QueryDiff q1 = new QueryDiff("q1", "SELECT * FROM orders WHERE customer_id = 42", 3.2, 3.1, -3.1,
                12.0, 48.5, 304.2, 1200.0, 98000.0, 8066.7, false);
        when(comparisonService.compare(anyLong(), any(), any(), any(), any()))
                .thenReturn(new CompareResult(base, target, -2.0, 304.2, 1700.0, 0, List.of(q1)));
        when(waitEvents.inWindow(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        when(backupFreshness.freshnessFor(anyLong())).thenReturn(new BackupFreshness(teamA, "orders-db",
                DbmsType.POSTGRESQL, null, null, null, null, false, BackupFreshness.Status.NO_BACKUP, 24));
        when(analyzer.isEnabled()).thenReturn(true);
        when(analyzer.backend()).thenReturn("cli");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from ai_operation_outbox");
        jdbc.update("delete from ai_operation_result");
        jdbc.update("delete from ai_operation_job");
        instances.deleteById(teamA);
    }

    @Test
    @WithMockUser(username = "alice", roles = "VIEWER")
    void 사람이_올리면_본문의_요청자와_팀은_무시되고_인증_주체로_기록된다() throws Exception {
        JsonNode job = submit("""
                {"type": "QUERY_DIAGNOSIS", "instanceId": %d, "prompt": "느려진 쿼리", "requester": "mallory",
                 "team": "team-z", "trigger": "WEB"}""".formatted(teamA)).andExpect(status().isAccepted()).json();

        assertThat(job.path("requester").asText()).isEqualTo("alice");
        assertThat(job.path("submittedBy").asText()).isEqualTo("alice");
        assertThat(job.path("scopeTeam").isNull()).isTrue();
        assertThat(job.path("status").asText()).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("select count(*) from ai_operation_outbox where status = 'PENDING'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @WithMockUser(username = "api-token", roles = "ADMIN")
    void 게이트웨이_요청은_큐를_거쳐_사실_수집_분석_검증까지_이어진다() throws Exception {
        JsonNode job = submit("""
                {"requestId": "slack-Ev1", "type": "INCIDENT_TRIAGE", "instanceId": %d, "prompt": "orders-db 느려요",
                 "trigger": "SLACK", "requester": "slack:U1", "team": "team-a", "replyChannel": "C1", "replyThread": "171.1"}
                """.formatted(teamA)).andExpect(status().isAccepted()).json();
        String jobId = job.path("jobId").asText();
        assertThat(job.path("requester").asText()).isEqualTo("slack:U1");
        assertThat(job.path("submittedBy").asText()).isEqualTo("api-token");

        // 같은 Slack 이벤트 재전송은 같은 작업 — 두 번째 Outbox 이벤트를 만들지 않는다
        JsonNode again = submit("""
                {"requestId": "slack-Ev1", "type": "INCIDENT_TRIAGE", "instanceId": %d, "prompt": "orders-db 느려요",
                 "trigger": "SLACK", "requester": "slack:U1", "team": "team-a"}""".formatted(teamA)).json();
        assertThat(again.path("jobId").asText()).isEqualTo(jobId);

        // 릴레이 — 선점한 이벤트는 다시 선점되지 않고, 틀린 토큰으로는 발행 완료를 찍지 못한다
        JsonNode events = call(post("/api/ai-operations/outbox/claim").param("limit", "10")).json();
        assertThat(events).hasSize(1);
        assertThat(call(post("/api/ai-operations/outbox/claim")).json()).isEmpty();
        String eventId = events.get(0).path("eventId").asText();
        call(post("/api/ai-operations/outbox/" + eventId + "/published").contentType("application/json")
                .content("{\"claimToken\": \"wrong\"}")).andExpect(status().isConflict());
        call(post("/api/ai-operations/outbox/" + eventId + "/published").contentType("application/json")
                .content("{\"claimToken\": \"" + events.get(0).path("claimToken").asText() + "\"}"))
                .andExpect(status().isOk());

        // 실행기 — 같은 작업이 두 번 배달되면 두 번째 선점은 409
        JsonNode claimed = call(post("/api/ai-operations/" + jobId + "/claim").contentType("application/json")
                .content("{\"workerId\": \"w1\"}")).andExpect(status().isOk()).json();
        String lease = claimed.path("leaseToken").asText();
        call(post("/api/ai-operations/" + jobId + "/claim")).andExpect(status().isConflict());
        call(post("/api/ai-operations/" + jobId + "/facts").header("X-Lease-Token", "stolen"))
                .andExpect(status().isConflict());

        JsonNode facts = call(post("/api/ai-operations/" + jobId + "/facts").header("X-Lease-Token", lease))
                .andExpect(status().isOk()).json();
        assertThat(facts.path("facts").toString()).contains("헬스 스코어 55점").contains("48.5ms")
                // 사실에 실리는 쿼리는 리터럴이 가려진다
                .doesNotContain("customer_id = 42");
        assertThat(facts.path("ruleFindings").toString())
                .contains("성공한 백업 이력이 없습니다").contains("쿼리 q1 평균 지연 304.2% 증가");

        call(post("/api/ai-operations/" + jobId + "/retrieving").header("X-Lease-Token", lease))
                .andExpect(status().isOk());

        // 모델이 사실에 없는 수치(350)를 지어내고 인덱스 생성을 권했지만 approvalRequired는 false로 답했다
        when(analyzer.complete(eq(AiAnalyzer.CallSite.AIOPS), anyString(), anyString())).thenReturn(Optional.of("""
                {"opinion": "q1 평균 지연이 12.0ms에서 48.5ms로 늘었고 커넥션 350개가 대기 중이다.",
                 "evidence": ["F3 q1 평균 48.5ms", "G1 백업 없음"], "uncertainties": ["실행계획 미확인"],
                 "nextActions": ["orders(customer_id) 인덱스 생성 검토"], "approvalRequired": false}"""));
        JsonNode done = call(post("/api/ai-operations/" + jobId + "/analyze").header("X-Lease-Token", lease)
                .contentType("application/json").content("""
                        {"references": [{"id": "runbook-7", "source": "runbook", "title": "인덱스 누락 대응",
                         "snippet": "호출당 행이 급증하면 실행계획부터 확인한다", "score": 0.81}]}"""))
                .andExpect(status().isOk()).json();

        assertThat(done.path("status").asText()).isEqualTo("COMPLETED");
        JsonNode result = done.path("result");
        assertThat(result.path("unverifiedClaims").toString()).contains("사실 목록에 없는 수치: 350");
        assertThat(result.path("approvalRequired").asBoolean()).isTrue();
        assertThat(result.path("nextActions").toString()).contains("승인 티켓");
        assertThat(result.path("references").toString()).contains("runbook-7");
        assertThat(result.path("promptVersion").asText()).startsWith("aiops-v1/rules-");

        // 알림 기록은 한 번만 — 다시 배달된 실행기는 alreadyNotified를 보고 Slack에 두 번 쓰지 않는다
        assertThat(call(post("/api/ai-operations/" + jobId + "/notified").header("X-Lease-Token", lease)).json()
                .path("alreadyNotified").asBoolean()).isFalse();
        assertThat(call(post("/api/ai-operations/" + jobId + "/notified").header("X-Lease-Token", lease)).json()
                .path("alreadyNotified").asBoolean()).isTrue();
    }

    @Test
    @WithMockUser(username = "api-token", roles = "ADMIN")
    void AI가_꺼져_있으면_실패가_아니라_규칙_판정만_담아_완료한다() throws Exception {
        when(analyzer.isEnabled()).thenReturn(false);
        String jobId = submitAsGateway("BACKUP_RISK_REVIEW", "slack:U2");
        String lease = claim(jobId);
        call(post("/api/ai-operations/" + jobId + "/facts").header("X-Lease-Token", lease)).andExpect(status().isOk());
        JsonNode done = call(post("/api/ai-operations/" + jobId + "/analyze").header("X-Lease-Token", lease))
                .andExpect(status().isOk()).json();

        assertThat(done.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.path("result").path("aiOpinion").isNull()).isTrue();
        assertThat(done.path("result").path("ruleFindings").toString()).contains("성공한 백업 이력이 없습니다");
        assertThat(done.path("result").path("uncertainties").toString()).contains("AI 백엔드가 꺼져 있어");
        verify(analyzer, never()).complete(any(), anyString(), anyString());
    }

    @Test
    @WithMockUser(username = "api-token", roles = "ADMIN")
    void 분석_중_취소된_작업의_늦은_결과는_버려진다() throws Exception {
        String jobId = submitAsGateway("QUERY_DIAGNOSIS", "slack:U3");
        String lease = claim(jobId);
        call(post("/api/ai-operations/" + jobId + "/facts").header("X-Lease-Token", lease)).andExpect(status().isOk());
        // 모델을 기다리는 사이 사람이 취소했다
        when(analyzer.complete(eq(AiAnalyzer.CallSite.AIOPS), anyString(), anyString())).thenAnswer(inv -> {
            jdbc.update("update ai_operation_job set status = 'CANCELLED', version = version + 1 where job_id = ?", jobId);
            return Optional.of("{\"opinion\": \"늦은 소견\", \"evidence\": [\"F1\"]}");
        });
        call(post("/api/ai-operations/" + jobId + "/analyze").header("X-Lease-Token", lease))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select ai_opinion from ai_operation_result where job_id = ?", String.class, jobId))
                .isNull();
    }

    @Test
    @WithMockUser(username = "api-token", roles = "ADMIN")
    void 리스가_만료된_작업과_아무도_가져가지_않은_작업은_리퍼가_실패로_드러낸다() throws Exception {
        String leased = submitAsGateway("SLO_RISK_REVIEW", "slack:U4");
        claim(leased);
        String unclaimed = submitAsGateway("SLO_RISK_REVIEW", "slack:U5");
        jdbc.update("update ai_operation_job set lease_until = ? where job_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), leased);
        jdbc.update("update ai_operation_job set updated_at = ? where job_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusHours(2)), unclaimed);

        reaper.sweep();

        assertThat(jobStatus(leased)).isEqualTo("FAILED");
        assertThat(jobStatus(unclaimed)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select failure_reason from ai_operation_job where job_id = ?", String.class, leased))
                .contains("리스 만료");
    }

    @Test
    @WithMockUser(username = "api-token", roles = "ADMIN")
    void 일시_오류로_다시_배달된_실행기는_같은_단계부터_이어간다() throws Exception {
        String jobId = submitAsGateway("QUERY_DIAGNOSIS", "slack:U6");
        String lease = claim(jobId);
        call(post("/api/ai-operations/" + jobId + "/facts").header("X-Lease-Token", lease)).andExpect(status().isOk());
        // 첫 실행기가 사실을 받은 뒤 응답을 잃었다 — 같은 토큰으로 같은 단계를 다시 부르면 재수집된다
        call(post("/api/ai-operations/" + jobId + "/facts").header("X-Lease-Token", lease)).andExpect(status().isOk());
        assertThat(call(get("/api/ai-operations/" + jobId + "/lease-view").header("X-Lease-Token", lease)).json()
                .path("status").asText()).isEqualTo("COLLECTING");
        // 뒤로는 못 간다
        call(post("/api/ai-operations/" + jobId + "/claim")).andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "api-token", roles = "ADMIN")
    void 접수_뒤_다른_팀으로_옮겨진_인스턴스는_선점하지_않고_실패를_알릴_토큰만_준다() throws Exception {
        JsonNode job = submit("""
                {"type": "BACKUP_RISK_REVIEW", "instanceId": %d, "prompt": "백업 봐줘", "requester": "slack:U7",
                 "team": "team-a", "replyChannel": "C1"}""".formatted(teamA)).andExpect(status().isAccepted()).json();
        String jobId = job.path("jobId").asText();
        DatabaseInstance moved = instances.findById(teamA).orElseThrow();
        moved.updateMeta("team-b", null, null, "prod", null, null);
        instances.save(moved);

        JsonNode claimed = call(post("/api/ai-operations/" + jobId + "/claim")).andExpect(status().isConflict()).json();
        assertThat(claimed.path("job").path("status").asText()).isEqualTo("FAILED");
        assertThat(claimed.path("job").path("failureReason").asText()).contains("팀 범위");
        String notifyOnly = claimed.path("leaseToken").asText();
        // 알림 기록은 되지만 작업을 진행시키지는 못한다
        call(post("/api/ai-operations/" + jobId + "/facts").header("X-Lease-Token", notifyOnly))
                .andExpect(status().isConflict());
        call(post("/api/ai-operations/" + jobId + "/notified").header("X-Lease-Token", notifyOnly))
                .andExpect(status().isOk());
    }

    @Test
    void 실행기_경로와_재시도는_역할로_막힌다() throws Exception {
        mvc.perform(post("/api/ai-operations/any/claim").with(csrf()).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ai-operations/outbox/claim").with(csrf()).with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ai-operations/any/retry").with(csrf()).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ai-operations")).andExpect(status().isUnauthorized());
    }

    @Test
    void 다른_팀의_인스턴스와_작업은_보이지_않는다() throws Exception {
        var teamB = user("bob").authorities(() -> "ROLE_VIEWER", () -> "TEAM_team-b");
        mvc.perform(post("/api/ai-operations").with(csrf()).with(teamB).contentType("application/json")
                        .content("{\"type\": \"QUERY_DIAGNOSIS\", \"instanceId\": " + teamA + ", \"prompt\": \"x\"}"))
                .andExpect(status().isNotFound());

        var memberA = user("carol").authorities(() -> "ROLE_VIEWER", () -> "TEAM_team-a");
        String body = mvc.perform(post("/api/ai-operations").with(csrf()).with(memberA).contentType("application/json")
                        .content("{\"type\": \"QUERY_DIAGNOSIS\", \"instanceId\": " + teamA + ", \"prompt\": \"x\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = MAPPER.readTree(body).path("jobId").asText();

        mvc.perform(get("/api/ai-operations/" + jobId).with(teamB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/ai-operations").with(teamB)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/ai-operations/" + jobId).with(user("dave").authorities(() -> "ROLE_VIEWER",
                () -> "TEAM_team-a"))).andExpect(status().isOk());
        // 같은 팀이라도 요청자가 아니고 운영자가 아니면 취소하지 못한다
        mvc.perform(post("/api/ai-operations/" + jobId + "/cancel").with(csrf())
                        .with(user("dave").authorities(() -> "ROLE_VIEWER", () -> "TEAM_team-a")))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "api-token", roles = "ADMIN")
    void 요청자별_진행_중_작업_수를_넘기면_접수하지_않는다() throws Exception {
        for (int i = 0; i < 3; i++) {
            submitAsGateway("QUERY_DIAGNOSIS", "slack:spammer");
        }
        submit("""
                {"type": "QUERY_DIAGNOSIS", "instanceId": %d, "prompt": "또", "requester": "slack:spammer"}"""
                .formatted(teamA)).andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "api-token", roles = "ADMIN")
    void 경보에서_시작된_작업은_요청자가_달라도_전체_상한에_묶이고_사람이_맡긴_작업은_막지_않는다() throws Exception {
        // 경보의 요청자는 alert:<인스턴스>라 인스턴스마다 다르다 — 요청자별 상한(3)만으로는 넷째도 들어갔다(170절 5번)
        for (int i = 0; i < 3; i++) {
            submitAsAlert("alert:db-" + i).andExpect(status().isAccepted());
        }
        submitAsAlert("alert:db-3").andExpect(status().isConflict());

        // 경보 상한은 경보에만 걸린다 — 같은 순간 사람이 게이트웨이로 맡긴 작업은 자리가 남아 있어야 한다
        submitAsGateway("QUERY_DIAGNOSIS", "slack:U1");
        assertThat(jdbc.queryForObject("select count(*) from ai_operation_job where trigger_source = 'ALERT'", Integer.class))
                .isEqualTo(3);
    }

    // ---- 도우미 ----

    private Json submitAsAlert(String requester) throws Exception {
        return submit("""
                {"type": "INCIDENT_TRIAGE", "instanceId": %d, "prompt": "수집 정지 경보", "trigger": "ALERT",
                 "requester": "%s", "team": "team-a"}""".formatted(teamA, requester));
    }

    private String submitAsGateway(String type, String requester) throws Exception {
        return submit("""
                {"type": "%s", "instanceId": %d, "prompt": "점검해줘", "trigger": "SLACK", "requester": "%s"}"""
                .formatted(type, teamA, requester)).andExpect(status().isAccepted()).json().path("jobId").asText();
    }

    private String claim(String jobId) throws Exception {
        return call(post("/api/ai-operations/" + jobId + "/claim")).andExpect(status().isOk()).json()
                .path("leaseToken").asText();
    }

    private String jobStatus(String jobId) {
        return jdbc.queryForObject("select status from ai_operation_job where job_id = ?", String.class, jobId);
    }

    private Json submit(String body) throws Exception {
        return call(post("/api/ai-operations").contentType("application/json").content(body));
    }

    private Json call(MockHttpServletRequestBuilder request)
            throws Exception {
        return new Json(mvc.perform(request.with(csrf())));
    }

    private record Json(ResultActions actions) {
        Json andExpect(ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        JsonNode json() throws Exception {
            return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString());
        }
    }
}
