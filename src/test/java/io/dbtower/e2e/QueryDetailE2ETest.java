package io.dbtower.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.setDefaultAssertionTimeout;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쿼리 상세 화면 E2E (B3) — 버튼 여덟 개를 토글·더보기로 바꾼 자리를 실제 브라우저에서 본다.
 *
 * <p>대상 DB가 필요 없다. Top Query 표는 {@code page.route}로 응답을 대신 채운다 — 표를 그리려면 통계 조회가
 * 성공해야 하는데, 이 화면에서 확인하려는 것은 데이터가 아니라 조작 계층(무엇이 보이고, 눌렀을 때 요청이
 * 나가는가)이기 때문이다. 같은 이유로 심층 진단 요청도 가로채서 "나가지 않았음"을 단언한다.
 *
 * <p>돌리는 법: {@code ./gradlew playwrightInstall && DBTOWER_E2E=1 ./gradlew test --tests '*QueryDetailE2ETest'}
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_E2E", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dbtower.security.api-token=test-api-token")
class QueryDetailE2ETest {

    private static final String PASSWORD = "e2e-qd-pass-1234";
    private static final String USER = "e2e-qd-requester";
    private static final String QID = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String SQL =
            "SELECT o.id, o.status, o.total FROM orders o WHERE o.status = 'failed' AND o.total > 1000 ORDER BY o.id";
    private static final String QUERY_STATS = """
            [{"queryId":"%s","queryText":"%s","calls":120,"totalTimeMs":4820.5,"rowsExamined":24000,
              "loadPct":100.0,"avgLatencyMs":40.2,"rowsExaminedAvg":200.0,"callsPerSec":null,"plan":null}]"""
            .formatted(QID, SQL);
    private static final String EXPLAIN = """
            {"plan":"Seq Scan on orders  (cost=0.00..1834.00 rows=1000 width=40)","findings":["Seq Scan — 인덱스 부재로 테이블 풀스캔"]}""";
    private static final String DEEP = """
            {"rootCauses":[],"notes":[],"plan":"Seq Scan on orders  (cost=0.00..1834.00 rows=1000 width=40)"}""";

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
    /** 심층 진단 요청이 실제로 나갔는지 — "더보기는 섹션만 연다"를 요청 가로채기로 확인한다 */
    private final List<String> deepRequests = new CopyOnWriteArrayList<>();
    private DatabaseInstance instance;

    @BeforeAll
    static void launchBrowser() {
        // 대상이 닿지 않는 주소라 인스턴스를 열 때마다 로더 열여덟 개가 실패를 물고 돌아온다 —
        // 기본 단언 5초는 화면이 틀려서가 아니라 그 대기열 때문에 걸린다(다른 E2E와 같은 이유)
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
        deepRequests.clear();
        users.findByUsername(USER).orElseGet(() -> users.save(
                new PlatformUser(USER, encoder.encode(PASSWORD), PlatformUser.Role.REQUESTER)));
        // MySQL — 가상 인덱스(HypoPG)가 없는 기종이라 "인덱스 제안" 토글이 감춰지는 쪽을 본다
        instance = instances.save(new DatabaseInstance("e2e-qd-mysql", DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p"));
    }

    @AfterEach
    void cleanup() {
        // 라우트를 먼저 걷어낸 뒤 컨텍스트를 닫는다.
        // 컨텍스트를 그냥 닫으면 아직 처리되지 않은 가로챈 요청의 route 이벤트가 뒤늦게 도착하고,
        // Playwright가 그 이벤트를 처리하며 닫힌 페이지에 updateInterceptionPatterns()를 불러 TargetClosedError가 난다.
        // 그 예외는 디스패처 스레드에서 터져 **다음** 테스트의 호출 위로 튄다(간헐 실패의 정체).
        for (BrowserContext context : contexts) {
            for (Page page : context.pages()) {
                if (!page.isClosed()) page.unrouteAll();
            }
            context.close();
        }
        contexts.clear();
        instances.delete(instance);
        users.findByUsername(USER).ifPresent(users::delete);
    }

    @Test
    void 상세는_토글과_더보기로_접히고_SQL_ID와_표_툴팁이_동작한다() {
        Page page = consoleAs(USER);

        // 1) 표의 SQL 셀 — 네이티브 title 대신 강조된 툴팁이 뜬다(셀은 말줄임이라 전체 문장이 안 보인다)
        assertThat(page.locator("#top-table tbody tr[data-idx]").first()).containsText("orders");
        // title이 남아 있으면 네이티브 툴팁과 우리 툴팁이 겹쳐 뜬다
        assertThat(page.locator("#top-table td[data-sql-tip]").first().getAttribute("title")).isNull();
        Locator cell = page.locator("#top-table td[data-sql-tip]").first();
        // 먼저 화면 안으로 들여놓는다. hover가 스크롤을 겸하면 그 스크롤이 툴팁을 닫는다(제품 동작: 스크롤로 닫힘) —
        // 사람은 이미 보이는 셀에 마우스를 올리므로, 여기서 재현되는 것은 Playwright의 합성 동작뿐이다
        cell.scrollIntoViewIfNeeded();
        page.waitForTimeout(300);
        cell.hover();
        assertThat(page.locator("#sql-tip")).isVisible();
        assertThat(page.locator("#sql-tip .t-kw").first()).hasText("SELECT");
        assertThat(page.locator("#sql-tip")).containsText("o.status = 'failed'");
        screenshot(page, "querydetail-sql-tip.png");
        page.mouse().move(2, 2);                     // 툴팁을 닫고 표로 돌아간다
        assertThat(page.locator("#sql-tip")).isHidden();

        // 2) 상세를 연다 — 행 아무 칸이나 누르면 열린다(Load 칸을 눌러 툴팁과 겹치지 않게)
        page.locator("#top-table tbody tr[data-idx]").first().locator("td").first().click();
        assertThat(page.locator("#query-detail")).isVisible();

        // 머리줄에는 토글과 워크벤치·더보기만 있다 — 심층 진단·문의는 메뉴 안으로 들어갔다
        assertThat(page.locator(".detail-head > *")).hasCount(3);   // SQL ID + 토글 묶음 + 오른쪽 꼬리
        assertThat(page.locator("#query-detail .seg-btn")).hasCount(4);
        assertThat(page.locator("#btn-explain")).isVisible();
        assertThat(page.locator("#btn-schema")).isVisible();
        // AI 입구는 오른쪽 대화 칸 하나다(#57) — 상세에는 그 쿼리를 대화에 붙이는 버튼만 남았다
        assertThat(page.locator("#btn-ask-ai")).isVisible();
        assertThat(page.locator("#btn-antipattern")).isVisible();
        assertThat(page.locator("#btn-advisor")).isHidden();     // MySQL에는 HypoPG가 없다(159절)
        assertThat(page.locator("#btn-to-workbench")).isVisible();
        assertThat(page.locator("#btn-detail-more")).isVisible();
        // 문의는 접힌 메뉴 안에 있다. 심층 진단은 대화 칸의 "실제 실행 진단"으로 옮겼다(#57)
        assertThat(page.locator("#btn-deep")).hasCount(0);
        assertThat(page.locator("#btn-inquiry")).isHidden();

        // SQL ID는 짧게 보이고 전체 값은 title에 남는다 — 64자를 통째로 보여 주던 자리
        assertThat(page.locator("#detail-qid")).hasText("a1b2c3…8f90");
        assertThat(page.locator("#detail-qid")).hasAttribute("title", QID);

        // 아직 아무것도 조회하지 않았다 — "다시 조회"가 미리 떠 있으면 무엇을 다시 조회하는지 알 수 없다
        assertThat(page.locator("#query-detail [data-refresh=explain]")).isHidden();
        assertThat(page.locator("#query-detail [data-refresh=schema]")).isHidden();

        // 3) 토글 — 켜면 섹션이 열리고, 다시 누르면 닫힌다(보관된 결과는 조회 없이 다시 보인다)
        page.click("#btn-explain");
        assertThat(page.locator("#btn-explain")).hasAttribute("aria-pressed", "true");
        assertThat(page.locator("#plan-section")).isVisible();
        assertThat(page.locator("#detail-findings")).containsText("Seq Scan");
        assertThat(page.locator("#query-detail [data-refresh=explain]")).isVisible();   // 결과가 생겼다
        page.click("#btn-explain");
        assertThat(page.locator("#btn-explain")).hasAttribute("aria-pressed", "false");
        assertThat(page.locator("#plan-section")).isHidden();
        page.click("#btn-explain");
        assertThat(page.locator("#detail-findings")).containsText("Seq Scan");
        screenshot(page, "querydetail-toggled.png");

        // 4) SQL ID 복사 — 화면에는 짧게 보이지만 클립보드에는 전체 값이 간다
        page.click("#btn-copy-qid");
        assertThat(page.locator("#btn-copy-qid")).hasText("복사됨");
        assertThat((String) page.evaluate("navigator.clipboard.readText()")).isEqualTo(QID);

        // 5) AI에게 묻기(#57) — 쿼리를 대화에 붙이기만 한다. 실제 실행 진단은 고르고 보내야 나간다
        assertThat(page.locator(".chat-mode[data-mode='deep']")).isDisabled();   // 붙인 쿼리가 없으면 고를 수 없다
        page.click("#btn-ask-ai");
        assertThat(page.locator("#chat-attach")).isVisible();
        assertThat(page.locator("#chat-attach")).containsText("a1b2c3…8f90");
        assertThat(page.locator("#diagnose-question")).hasAttribute("placeholder", "비워 두면 이 쿼리를 판단 기준으로 분석합니다");
        page.click(".chat-mode[data-mode='deep']");
        assertThat(page.locator(".chat-mode[data-mode='deep']")).hasAttribute("aria-checked", "true");
        page.waitForTimeout(700);                    // 나갔다면 이 사이에 나간다
        assertThat(deepRequests).as("방식을 고르기만 했는데 쿼리를 실행했다").isEmpty();
        screenshot(page, "querydetail-ask-ai.png");

        // 보내기를 눌러야 나가고, 답은 대화 칸에 근거 줄과 함께 쌓인다
        page.click("#btn-diagnose");
        page.waitForCondition(() -> !deepRequests.isEmpty());
        assertThat(deepRequests).as("보내기가 진단을 부르지 않았다").hasSize(1);
        Locator answer = page.locator("#chat-log .chat-ai").last();
        assertThat(answer).containsText("근본원인 규칙 매칭 없음");
        assertThat(answer.locator(".chat-evidence")).containsText("실제 실행 계획");
        assertThat(page.locator("#chat-log .chat-user").last()).containsText("쿼리 a1b2c3…8f90");
    }

    /**
     * 채팅이 로딩 문구에서 멈추지 않는다(B3 2차). 요청자 역할·대화 0개에서도 빈 대화 화면이 곧바로 뜬다 —
     * 대상 조회가 몰리면 대화 목록 조회가 그 뒤에 줄을 서서 십여 초 걸리는데, 그동안 화면이 로딩 문구뿐이면
     * 사람은 기다릴지 다른 걸 고를지 알 수 없다.
     */
    @Test
    void 요청자에게_대화가_없어도_빈_대화_화면이_뜨고_로딩에서_멈추지_않는다() {
        Page page = consoleAs(USER);
        Locator log = page.locator("#chat-log");
        assertThat(log).containsText("무엇이든 물어보세요");
        assertThat(page.locator("#chat-log .chat-suggest .chat-chip")).hasCount(3);
    }

    /** 대화 목록 조회가 실패하면 한 줄 오류가 뜨고 로딩 문구는 사라진다 — 화면이 조용히 멈추지 않는다. */
    @Test
    void 대화_목록_조회가_실패하면_한_줄_오류가_뜬다() {
        Page page = consoleAs(USER, route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(500).setContentType("application/json")
                .setBody("{\"error\":\"서버 오류\"}")));

        Locator log = page.locator("#chat-log");
        assertThat(log.locator(".chat-error")).containsText("대화 목록을 불러오지 못했습니다");
        assertThat(log).not().containsText("대화를 불러오는 중입니다");
        screenshot(page, "querydetail-chat-list-error.png");
    }

    /**
     * 워크벤치로 넘긴 워크시트 맨 위에 출처(인스턴스·구간·쿼리·신호)가 보이고 관제로 돌아간다(#58).
     * 전에는 SQL만 넘어가 무엇 때문에 왔는지가 워크벤치에 없었다.
     */
    @Test
    void 워크벤치로_넘기면_출처_한_줄과_돌아가는_길이_보인다() {
        Page page = consoleAs(USER);
        page.locator("#top-table tbody tr[data-idx]").first().locator("td").first().click();
        page.locator("#btn-to-workbench").click();

        Locator origin = page.locator("#wb-origin");
        assertThat(origin).isVisible();
        assertThat(origin).containsText("관제에서 넘어옴");
        assertThat(origin).containsText(instance.getName());
        assertThat(origin).containsText("조회 ");
        assertThat(origin).containsText("쿼리 a1b2c3…8f90");
        assertThat(origin).containsText("부하 100%");
        assertThat(origin).containsText("평균 40.2ms");
        assertThat(page.locator("#wb-input")).isVisible();
        System.out.printf("MEASURE 넘김 출처: %s%n", origin.textContent().replaceAll("\\s+", " "));
        screenshot(page, "handoff-origin.png");

        origin.locator("button[data-origin='back']").click();
        assertThat(page.locator("#mode-monitor")).isVisible();
        assertThat(page.locator("#mode-workbench")).isHidden();
    }

    /**
     * 보던 화면이 주소에 남는다(#60) — 탭·모니터링 그룹·펼친 상세를 바꾸면 주소가 따라가고, 뒤로 가기는 그 단계로,
     * 새로고침은 같은 화면으로 돌아온다. 전에는 새로고침·링크 공유가 첫 화면으로 돌아갔다.
     */
    @Test
    void 탭과_펼친_상세가_주소에_남고_뒤로_가기와_새로고침이_그_화면으로_돌아온다() {
        Page page = consoleAs(USER);

        page.locator(".tab[data-tab='monitor']").click();
        assertThat(page).hasURL(Pattern.compile("tab=monitor"));
        page.locator(".mon-tab[data-mon='diag']").click();
        assertThat(page).hasURL(Pattern.compile("tab=monitor&mon=diag"));

        page.goBack();
        assertThat(page).hasURL(Pattern.compile("tab=monitor(?!&mon)"));
        assertThat(page.locator(".mon-tab[data-mon='perf']")).hasClass(Pattern.compile("active"));
        page.goBack();
        assertThat(page.locator(".tab[data-tab='top']")).hasClass(Pattern.compile("active"));
        assertThat(page).not().hasURL(Pattern.compile("tab="));

        page.locator("#top-table tbody tr[data-idx]").first().locator("td").first().click();
        assertThat(page.locator("#query-detail")).isVisible();
        assertThat(page).hasURL(Pattern.compile("q=" + QID));

        page.reload();
        assertThat(page.locator("#query-detail")).isVisible();
        assertThat(page.locator("#detail-qid")).hasText("a1b2c3…8f90");
        assertThat(page).hasURL(Pattern.compile("q=" + QID));
        System.out.printf("MEASURE 새로고침 뒤 주소: %s%n", page.url());
    }

    /**
     * PostgreSQL JSON 실행계획은 노드 트리로 보인다(#83) — 수백 줄 JSON 대신 노드 종류·대상·조건·비용·추정 행 한 줄씩,
     * 자기 몫 비용이 가장 큰 노드를 강조하고 원문은 접는다.
     */
    @Test
    void PostgreSQL_JSON_실행계획은_노드_트리로_보이고_가장_비싼_노드를_강조한다() {
        Page page = consoleAs(USER);
        String plan = "[{\\\"Plan\\\":{\\\"Node Type\\\":\\\"Limit\\\",\\\"Total Cost\\\":60.74,\\\"Plan Rows\\\":22,\\\"Plans\\\":["
                + "{\\\"Node Type\\\":\\\"Sort\\\",\\\"Total Cost\\\":61.24,\\\"Plan Rows\\\":222,\\\"Sort Key\\\":[\\\"o.ordered_at DESC\\\"],\\\"Plans\\\":["
                + "{\\\"Node Type\\\":\\\"Seq Scan\\\",\\\"Relation Name\\\":\\\"orders\\\",\\\"Alias\\\":\\\"o\\\",\\\"Total Cost\\\":45.0,\\\"Plan Rows\\\":222,"
                + "\\\"Filter\\\":\\\"((status)::text = 'PAID'::text)\\\"}]}]}}]";
        page.route("**/explain**", route -> fulfillJson(route, "{\"plan\":\"" + plan + "\",\"findings\":[]}"));
        page.locator("#top-table tbody tr[data-idx]").first().locator("td").first().click();
        page.locator("#btn-explain").click();

        Locator nodes = page.locator("#detail-plan .plan-node");
        assertThat(nodes).hasCount(3);
        assertThat(nodes.nth(0)).containsText("Limit");
        assertThat(nodes.nth(2)).containsText("Seq Scan");
        assertThat(nodes.nth(2)).containsText("orders o");
        assertThat(nodes.nth(2)).containsText("((status)::text = 'PAID'::text)");
        assertThat(nodes.nth(2)).hasClass(Pattern.compile("hot"));
        assertThat(nodes.nth(2)).containsText("가장 비싼 노드");
        assertThat(nodes.nth(1)).containsText("정렬 o.ordered_at DESC");
        assertThat(page.locator("#detail-plan details.plan-raw")).not().hasAttribute("open", "");
        page.locator("#detail-plan").scrollIntoViewIfNeeded();
        screenshot(page, "querydetail-plan-tree.png");
    }

    /**
     * 쿼리를 붙이고 비워 보내면 판단 기준 분석이 대화 칸에 답으로 쌓인다(#57) — 쿼리 상세의 "AI 분석" 섹션이던 것.
     * 근거 줄은 다른 답과 같은 모양이고, 대화 기록에 남지 않는다는 사실을 적는다.
     */
    @Test
    void 붙인_쿼리를_비워_보내면_판단_기준_분석이_대화_칸에_답으로_쌓인다() {
        Page page = consoleAs(USER);
        String sse = "event: plan\ndata: {\"plan\":\"Seq Scan on orders\",\"findings\":[\"Seq Scan — 인덱스 부재로 테이블 풀스캔\"]}\n\n"
                + "event: text\ndata: {\"delta\":\"풀스캔입니다.\"}\n\n"
                + "event: result\ndata: {\"plan\":\"Seq Scan on orders\",\"findings\":[\"Seq Scan — 인덱스 부재로 테이블 풀스캔\"],\"aiAnalysis\":\"판정: 인덱스가 필요합니다. status 열이 선택도가 높습니다.\"}\n\n";
        page.route("**/ai-analysis/stream", route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(200).setContentType("text/event-stream").setBody(sse)));
        page.locator("#top-table tbody tr[data-idx]").first().locator("td").first().click();
        page.click("#btn-ask-ai");
        assertThat(page.locator("#btn-diagnose")).isEnabled();      // 붙인 쿼리가 있으면 비워도 보낼 수 있다
        page.click("#btn-diagnose");

        Locator answer = page.locator("#chat-log .chat-ai").last();
        assertThat(answer).containsText("인덱스가 필요합니다");
        assertThat(answer.locator(".chat-evidence")).containsText("실행계획");
        assertThat(answer.locator(".chat-evidence")).containsText("규칙 지적 1개");
        assertThat(answer).containsText("대화 기록에는 남지 않습니다");
        assertThat(page.locator("#chat-log .chat-user").last()).containsText("이 쿼리를 판단 기준으로 분석해 줘");
        // 같은 쿼리의 상세에는 실행계획·규칙 지적이 함께 채워진다(두 번 조회하지 않는다)
        assertThat(page.locator("#detail-findings")).containsText("Seq Scan");
        screenshot(page, "chat-query-analysis.png");
    }

    /** 관제 화면을 그 인스턴스로 연다. 표를 그릴 응답은 라우트로 대신 채운다. */
    private Page consoleAs(String username) {
        return consoleAs(username, null);
    }

    private Page consoleAs(String username, Consumer<Route> conversations) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setTimezoneId("Asia/Seoul").setViewportSize(1512, 900));
        context.setDefaultTimeout(20_000);
        // SQL ID 복사 버튼을 실제로 눌러 보려면 클립보드 권한이 필요하다(http://localhost는 보안 컨텍스트다)
        context.grantPermissions(List.of("clipboard-read", "clipboard-write"),
                new BrowserContext.GrantPermissionsOptions().setOrigin(base()));
        contexts.add(context);
        Page page = context.newPage();
        // 앱이 뜨자마자 조회가 나가므로 이동 전에 건다
        page.route("**/query-stats**", route -> fulfillJson(route, QUERY_STATS));
        page.route("**/explain**", route -> fulfillJson(route, EXPLAIN));
        if (conversations != null) page.route("**/conversations**", conversations::accept);
        page.route("**/deep-diagnose**", route -> {
            deepRequests.add(route.request().url());
            fulfillJson(route, DEEP);
        });
        page.navigate(base() + "/login.html");
        page.fill("#username", username);
        page.fill("#password", PASSWORD);
        page.click("button[type=submit]");
        page.waitForURL(Pattern.compile("^(?!.*/login).*$"));
        page.navigate(base() + "/?instance=" + instance.getId());
        page.locator("#top-table tbody tr[data-idx]").first().waitFor();
        return page;
    }

    private static void fulfillJson(Route route, String body) {
        route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType("application/json").setBody(body));
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private void screenshot(Page page, String name) {
        try {
            Path dir = Path.of("build/e2e");
            Files.createDirectories(dir);
            page.screenshot(new Page.ScreenshotOptions().setPath(dir.resolve(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
