package io.dbtower.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.Playwright;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import io.dbtower.security.internal.domain.PlatformUser;
import io.dbtower.security.internal.persistence.PlatformUserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.setDefaultAssertionTimeout;

/**
 * AI 운영 작업(169절)의 콘솔 화면 E2E — 접수 폼과 카드, 결과 딥링크가 실제 브라우저에서 동작하는지 본다.
 *
 * <p>이 화면은 Slack·경보·콘솔 세 입구가 만나는 자리인데, 셋 다 "작업이 만들어졌다"까지만 서버가 보증하고
 * 그 뒤 사람이 보는 화면은 MockMvc가 볼 수 없다. 카드의 행 수, 접수 뒤 탭 전환, 알림이 건 딥링크가
 * 실제로 그 작업을 펼치는지는 브라우저에서 눌러야만 드러난다 — PersonaUiE2ETest와 같은 이유로 같은 하네스를 쓴다.</p>
 *
 * <p>대상 DB가 필요 없다. 실행면(릴레이·워커)은 여기 없다 — 작업이 접수 뒤 RECEIVED로 서 있는 것이 정상이고,
 * 진행·완료를 기다리는 단언은 두지 않는다. 그래서 인스턴스는 닿지 않는 주소(127.0.0.1:1)로 등록한다.</p>
 *
 * <p>돌리는 법: {@code ./gradlew playwrightInstall && DBTOWER_E2E=1 ./gradlew test --tests '*AiOperationConsoleE2ETest'}</p>
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_E2E", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dbtower.security.api-token=test-api-token",
                // 이 클래스는 한 사람이 메서드마다 작업을 맡긴다. 운영 한도(3건)는 모델 호출 비용 가드라
                // 화면이 보는 값이 아니고, 여기서 확인하려는 네 동작과도 무관하다 — 테스트가 한도에 걸려
                // "진행 중인 작업이 3건"으로 거절당하는 것은 화면 결함이 아니라 픽스처의 문제다.
                "dbtower.aiops.max-active-per-requester=20"})
class AiOperationConsoleE2ETest {

    private static final String PASSWORD = "aiop-e2e-pass-1234";
    private static final String REQUESTER = "e2e-aiop-requester";
    private static final String OTHER_REQUESTER = "e2e-aiop-other";
    private static final String OPERATOR = "e2e-aiop-operator";
    private static final String PROMPT = "e2e 접수 확인 — 최근 한 시간 slow query 증가 원인";
    private static final Map<String, PlatformUser.Role> ACCOUNTS = Map.of(
            REQUESTER, PlatformUser.Role.REQUESTER,
            OTHER_REQUESTER, PlatformUser.Role.REQUESTER,
            OPERATOR, PlatformUser.Role.OPERATOR);

    private static Playwright playwright;
    private static Browser browser;

    @LocalServerPort
    int port;

    @Autowired
    PlatformUserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    DatabaseInstanceRepository instances;

    private final List<BrowserContext> contexts = new ArrayList<>();
    private DatabaseInstance instance;

    @BeforeAll
    static void launchBrowser() {
        // 동작 대기는 컨텍스트 기본값(20초)을 쓰는데 단언은 따로 5초가 기본이다. 이 화면은 대상이 닿지 않는 주소라
        // 인스턴스를 열 때마다 로더 열여덟 개가 실패를 물고 돌아온다 — 5초는 화면이 틀려서가 아니라 그 대기열 때문에 걸린다
        setDefaultAssertionTimeout(20_000);
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void closeBrowser() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void seed() {
        ACCOUNTS.forEach((name, role) -> users.findByUsername(name)
                .orElseGet(() -> users.save(new PlatformUser(name, encoder.encode(PASSWORD), role))));
        // 포트 1은 연결 거부가 즉시 돌아온다 — 대상 DB 없이 화면 분기만 본다
        instance = instances.save(new DatabaseInstance("e2e-aiop-mysql", DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p"));
    }

    @AfterEach
    void cleanup() {
        contexts.forEach(BrowserContext::close);
        contexts.clear();
        instances.delete(instance);
        ACCOUNTS.keySet().forEach(name -> users.findByUsername(name).ifPresent(users::delete));
    }

    @Test
    void 접수를_누르면_접수_안내가_뜨고_진단_카드에_그_작업이_나타난다() {
        Page page = consoleAs(REQUESTER);

        submitJob(page, "QUERY_DIAGNOSIS", "30");

        assertThat(page.locator("#aiop-submit-status")).containsText("쿼리 진단 작업");
        assertThat(page.locator("#aiop-submit-status")).containsText("접수했습니다");
        // 접수는 화면을 Monitoring 탭의 진단 그룹으로 옮긴다 — 카드가 안 보이면 사람은 안내만 받고 작업을 못 본다
        assertThat(page.locator(".tab[data-tab='monitor']")).hasClass(Pattern.compile("active"));
        assertThat(page.locator(".mon-group[data-group='diag']")).isVisible();

        Locator rows = page.locator("#aiops-table tbody .aiop-row");
        rows.first().waitFor();
        assertThat(rows).hasCount(1);
        assertThat(rows.first()).containsText("접수");
        assertThat(rows.first()).containsText("쿼리 진단");
        assertThat(rows.first()).containsText("e2e-aiop-mysql");
        assertThat(rows.first()).containsText(REQUESTER);
        // 접수 직후 그 작업이 펼쳐진다 — 요청 문장이 화면에 남아야 "무엇을 맡겼는지"를 확인할 수 있다
        assertThat(page.locator("#aiops-detail .aiop-prompt")).containsText("slow query");
    }

    @Test
    void 버튼을_연달아_두_번_눌러도_작업은_한_건만_생긴다() {
        Page page = consoleAs(REQUESTER);
        // 작업 유형·구간은 팝오버 안에 있다 — 입력창에 글이 있어야 여는 버튼이 살아난다(syncChatComposer)
        page.fill("#diagnose-question", PROMPT);
        page.click("#btn-aiop-open");
        pick(page, page.locator("#aiop-new-type"), "QUERY_DIAGNOSIS");
        pick(page, page.locator("#aiop-new-window"), "60");

        // 사람이 두 번 누르는 속도 그대로 — 사이에 응답을 기다리지 않는다. 응답을 기다린 뒤 두 번째를 누르면
        // 그것은 "두 번 누르기"가 아니라 "두 번 맡기기"라 이 테스트가 확인하려는 것이 아니다
        page.locator("#btn-aiop-submit").dblclick();

        assertThat(page.locator("#aiop-submit-status")).containsText("접수했습니다");
        Locator rows = page.locator("#aiops-table tbody .aiop-row");
        rows.first().waitFor();
        // 두 번째 클릭이 살아 있으면 여기서 2건이 된다 — 화면은 접수 중 버튼을 잠근다
        assertThat(rows).hasCount(1);
    }

    @Test
    void 알림이_건_딥링크로_들어가면_모니터링_진단이_열리고_그_작업이_펼쳐진다() {
        Page page = consoleAs(REQUESTER);
        String jobId = submitJob(page, "REGRESSION_EXPLANATION", "60");

        // Slack 결과 알림이 실제로 보내는 주소 그대로 — integrations/ai-ops-gateway의 job_link()
        page.navigate(base() + "/?aiop=" + jobId);

        assertThat(page.locator(".tab[data-tab='monitor']")).hasClass(Pattern.compile("active"));
        assertThat(page.locator(".mon-group[data-group='diag']")).isVisible();
        assertThat(page.locator("#aiops-detail")).isVisible();
        assertThat(page.locator("#aiops-detail")).containsText("회귀 원인");
        assertThat(page.locator(".aiop-row[data-job='" + jobId + "']")).hasClass(Pattern.compile("open"));
    }

    /**
     * AI 소견·근거에 섞인 PostgreSQL queryid(부호 있는 10진수)는 표와 같은 16진수 축약으로 보이고 원래 값은 title에 남는다(#82).
     * 결과는 모델 호출 없이 가로채 결정적으로 만든다 — 작업 자체는 실제로 접수한다.
     */
    @Test
    void AI_근거의_쿼리_ID는_표와_같은_16진수로_보인다() {
        Page page = consoleAs(REQUESTER);
        String jobId = submitJob(page, "REGRESSION_EXPLANATION", "60");
        page.route("**/api/ai-operations/" + jobId, route -> {
            String body = route.fetch().text();
            String result = "\"result\":{\"aiOpinion\":\"쿼리 `-2885330479908940062`의 평균이 그대로다.\","
                    + "\"evidence\":[\"F9 쿼리 -2885330479908940062(SELECT version()): 평균 0.017ms\"],"
                    + "\"facts\":[],\"references\":[],\"nextActions\":[],\"uncertainties\":[],\"ruleFindings\":[],\"unverifiedClaims\":[]}";
            String patched = body.replaceFirst("\"result\":null", result).replaceFirst("\"status\":\"[A-Z_]+\"", "\"status\":\"COMPLETED\"");
            route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType("application/json").setBody(patched));
        });
        page.navigate(base() + "/?aiop=" + jobId);

        Locator evidence = page.locator("#aiops-detail .aiop-block li .qid-inline").first();
        assertThat(evidence).hasText("d7f53f…96e2");
        assertThat(evidence).hasAttribute("title", "쿼리 ID -2885330479908940062");
        assertThat(page.locator("#aiops-detail .aiop-opinion .qid-inline")).hasText("d7f53f…96e2");
        assertThat(page.locator("#aiops-detail")).not().containsText("-2885330479908940062");
    }

    @Test
    void 취소_버튼은_요청자_본인과_운영자에게만_보이고_다른_요청자에게는_안_보인다() {
        Page creator = consoleAs(REQUESTER);
        String jobId = submitJob(creator, "QUERY_DIAGNOSIS", "30");
        // 접수 직후 화면이 그 작업을 펼친다. 작업은 RECEIVED로 서 있으므로 취소할 수 있는 상태다
        assertThat(creator.locator("#aiops-detail .aiop-detail-head")).isVisible();
        assertThat(creator.locator("#aiop-cancel")).isVisible();

        // 만들지 않은 요청자 — 서버는 403으로 막으므로 화면은 눌러서 403을 받는 버튼을 만들지 않는다.
        // 그 사람이 작업을 실제로 열었다는 사실을 먼저 남긴다 — 목록에서 안 보여 못 연 것이면 아래 단언은 헛돈다
        Page other = openJobFromCard(OTHER_REQUESTER, jobId);
        assertThat(other.locator("#aiops-detail")).containsText("slow query");
        assertThat(other.locator("#aiop-cancel")).hasCount(0);

        // 운영자는 서버가 허용한다 — 화면이 그 규칙과 어긋나면 사람은 할 수 있는 일을 못 한다
        assertThat(openJobFromCard(OPERATOR, jobId).locator("#aiop-cancel")).isVisible();
    }

    /** 접수 폼을 채워 맡기고, 화면이 만든 작업 id를 카드의 행에서 읽어 온다(data-job이 전체 id다). */
    private String submitJob(Page page, String type, String windowMinutes) {
        // 유형·구간은 입력창 아래 팝오버 안에 있다 — 글을 먼저 넣어야 여는 버튼이 살아난다(syncChatComposer)
        page.fill("#diagnose-question", PROMPT);
        page.click("#btn-aiop-open");
        pick(page, page.locator("#aiop-new-type"), type);
        pick(page, page.locator("#aiop-new-window"), windowMinutes);
        page.click("#btn-aiop-submit");
        Locator row = page.locator("#aiops-table tbody .aiop-row").first();
        row.waitFor();
        return row.getAttribute("data-job");
    }

    /**
     * Monitoring 탭의 진단 그룹에서 그 작업을 눌러 펼친 뒤의 페이지를 돌려준다.
     * 카드는 숨은 그룹 안에 있어서 탭을 열지 않으면 행이 화면에 없다 — 상세가 열리기 전에 단언하면
     * "버튼이 없는 것"이 아니라 "안 연 것"이 되므로, 여기서 연 뒤에만 단언하게 한다.
     */
    private Page openJobFromCard(String username, String jobId) {
        Page page = consoleAs(username);
        page.locator(".tab[data-tab='monitor']").click();
        page.locator(".mon-tab[data-mon='diag']").click();
        Locator row = page.locator("#aiops-table tbody .aiop-row[data-job='" + jobId + "']").first();
        row.waitFor();
        row.locator(".aiop-open").click();
        page.locator("#aiops-detail .aiop-detail-head").waitFor();
        return page;
    }

    /** 관제 화면을 그 인스턴스로 연다 — 대상 선택이 끝나야 접수 폼이 살아난다(선택 전에는 "인스턴스를 먼저 선택하세요"). */
    private Page consoleAs(String username) {
        Page page = loginAs(username);
        page.navigate(base() + "/?instance=" + instance.getId());
        page.locator("#time-panel").waitFor();
        return page;
    }

    private Page loginAs(String username) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setTimezoneId("Asia/Seoul").setViewportSize(1512, 900));
        context.setDefaultTimeout(20_000);
        contexts.add(context);
        Page page = context.newPage();
        page.navigate(base() + "/login.html");
        page.fill("#username", username);
        page.fill("#password", PASSWORD);
        page.click("button[type=submit]");
        page.waitForURL(Pattern.compile("^(?!.*/login).*$"));
        return page;
    }

    private String base() {
        return "http://localhost:" + port;
    }
    /** 커스텀 드롭다운에서 값을 고른다 — 네이티브 select는 감춰져 있고, 사람이 누르는 것은 버튼과 떠 있는 목록이다(#40) */
    static void pick(Page page, Locator select, String value) {
        String label = (String) select.evaluate("(s, v) => [...s.options].find((o) => o.value === v).text", value);
        select.locator("xpath=..").locator(".cs-btn").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.OPTION,
                new Page.GetByRoleOptions().setName(label).setExact(true)).click();
    }

}
