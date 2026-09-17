package io.dbtower.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.DatabaseInstanceRepository;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * 대량 일괄 변경의 진행 화면(#104) — 배치가 도는 동안 사람이 무엇을 보고 무엇을 누를 수 있는가.
 *
 * <p>대상 DB도 실제 실행도 없다. 티켓·진행·배치 응답을 {@code page.route}로 채운다 — 여기서 확인하는 것은
 * 실행기가 아니라 화면이 그 상태를 어떻게 읽어 보여주는지다(실행기는 {@code BulkChangeRunTest}·{@code BulkChangeBatchIT}).
 *
 * <p>특히 두 가지를 본다. 진행 중에는 다시 물어 갱신하는가, 그리고 <b>못 잰 복제 지연을 "0초"로 적지 않는가</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DBTOWER_E2E", matches = "1")
class BulkChangeProgressE2ETest {

    private static final String PASSWORD = "e2e-bulk-pass-1234";
    private static final String USER = "e2e-bulk-operator";

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
    private final List<DatabaseInstance> seeded = new ArrayList<>();

    @BeforeAll
    static void startBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void stopBrowser() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void seedUser() {
        users.findByUsername(USER).orElseGet(() -> users.save(
                new PlatformUser(USER, encoder.encode(PASSWORD), PlatformUser.Role.OPERATOR)));
    }

    @AfterEach
    void cleanup() {
        for (BrowserContext context : contexts) {
            for (Page page : context.pages()) {
                if (!page.isClosed()) page.unrouteAll();
            }
            context.close();
        }
        contexts.clear();
        seeded.forEach(instances::delete);
        seeded.clear();
        users.findByUsername(USER).ifPresent(users::delete);
    }

    @Test
    void 진행_중에는_스스로_갱신하고_못_잰_지연을_0초로_적지_않는다() {
        DatabaseInstance target = instance("e2e-bulk-a");
        AtomicInteger statusCalls = new AtomicInteger();
        AtomicReference<String> state = new AtomicReference<>("RUNNING");

        Page page = open(target, p -> {
            p.route("**/api/instances/*/reviews", route -> fulfill(route, "[" + ticketJson() + "]"));
            p.route("**/api/workbench/tickets/*/executions", route -> fulfill(route, "[]"));
            p.route("**/api/workbench/tickets/*/bulk", route -> {
                int n = statusCalls.incrementAndGet();
                // 세 번째 조회에서 끝난다 — 화면이 스스로 다시 물었는지 이걸로 확인한다
                if (n >= 3) {
                    state.set("DONE");
                }
                fulfill(route, """
                        {"reviewId":1,"state":"%s","lastAppliedKey":"%d","affectedRows":%d,"batches":%d}"""
                        .formatted(state.get(), 1000 * n, 1000 * n, n));
            });
            p.route("**/api/workbench/tickets/*/bulk/batches", route -> fulfill(route, """
                    [{"batchNo":2,"fromKey":"1000","toKey":"2000","affectedRows":1000,"elapsedMillis":42,
                      "lagSeconds":0.4,"lagSource":"MEASURED"},
                     {"batchNo":1,"fromKey":null,"toKey":"1000","affectedRows":1000,"elapsedMillis":38,
                      "lagSeconds":null,"lagSource":"UNAVAILABLE"}]"""));
        });

        openTicket(page);
        page.locator(".tk-batches").waitFor();

        // 배치 기록 — 첫 배치의 하한은 "처음"이고, 못 읽은 지연은 0초가 아니라 "못 읽음"이다
        Locator rows = page.locator(".tk-batches tbody tr");
        assertThat(rows).hasCount(2);
        assertThat(rows.nth(0)).containsText("1000 ~ 2000");
        assertThat(rows.nth(0)).containsText("0.4초");
        assertThat(rows.nth(1)).containsText("처음 ~ 1000");
        assertThat(rows.nth(1)).containsText("못 읽음");
        assertThat(rows.nth(1)).not().containsText("0초");

        // 진행 중에는 스스로 다시 물어 상태가 바뀐다(완료까지)
        assertThat(page.locator(".tk-sub .tk-st")).hasText(Pattern.compile("완료"));
        assertThat(page.locator(".tk-body")).containsText("마지막 적용 키");

        // 끝난 뒤에는 더 묻지 않는다 — 끝난 실행을 계속 물으면 보는 사람 수만큼 메타 DB 조회가 는다
        int settled = statusCalls.get();
        page.waitForTimeout(3000);
        org.assertj.core.api.Assertions.assertThat(statusCalls.get())
                .as("완료 뒤에는 다시 묻지 않는다").isEqualTo(settled);
    }

    @Test
    void 취소는_커밋된_배치를_되돌리지_않는다고_화면이_말한다() {
        DatabaseInstance target = instance("e2e-bulk-b");
        Page page = open(target, p -> {
            p.route("**/api/instances/*/reviews", route -> fulfill(route, "[" + ticketJson() + "]"));
            p.route("**/api/workbench/tickets/*/executions", route -> fulfill(route, "[]"));
            p.route("**/api/workbench/tickets/*/bulk", route -> fulfill(route, """
                    {"reviewId":1,"state":"PAUSED_BY_USER","lastAppliedKey":"7000","affectedRows":7000,"batches":7}"""));
            p.route("**/api/workbench/tickets/*/bulk/batches", route -> fulfill(route, "[]"));
        });

        openTicket(page);
        page.locator(".tk-sub").filter(new Locator.FilterOptions().setHasText("대량 일괄 변경")).waitFor();

        assertThat(page.locator(".hint").filter(new Locator.FilterOptions().setHasText("되돌리지 않습니다")))
                .isVisible();
        // 멈춘 상태에서는 재개와 취소가 보이고, 일시정지는 보이지 않는다
        assertThat(page.locator("button[data-act=bulk-resume]")).isVisible();
        assertThat(page.locator("button[data-act=bulk-cancel]")).isVisible();
        assertThat(page.locator("button[data-act=bulk-pause]")).hasCount(0);
    }

    /** 티켓 탭을 열고 첫 티켓을 고른다. 탭을 누른 직후에는 칸이 아직 숨겨져 있어 목록이 보이기를 기다린다. */
    private static void openTicket(Page page) {
        page.click("button.wb-rtab[data-pane=tickets]");
        Locator item = page.locator("#wb-pane-tickets li[data-ticket]").first();
        item.waitFor(new Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE));
        item.click();
    }

    private DatabaseInstance instance(String name) {
        seeded.add(instances.save(new DatabaseInstance(name, DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p")));
        return seeded.getLast();
    }

    private static String ticketJson() {
        return """
                {"id":1,"status":"APPROVED","targetSql":"UPDATE orders SET status = 'DONE' WHERE status = 'PENDING'",
                 "requester":"%s","submittedAt":"2026-09-18T03:00:00","rulesVersion":"1","findings":[]}"""
                .formatted(USER);
    }

    private static void fulfill(Route route, String body) {
        route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType("application/json").setBody(body));
    }

    private Page open(DatabaseInstance target, java.util.function.Consumer<Page> extra) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setTimezoneId("Asia/Seoul").setViewportSize(1512, 900));
        context.setDefaultTimeout(20_000);
        contexts.add(context);
        Page page = context.newPage();
        extra.accept(page);
        page.navigate(base() + "/login.html");
        page.fill("#username", USER);
        page.fill("#password", PASSWORD);
        page.click("button[type=submit]");
        page.waitForURL(Pattern.compile("^(?!.*/login).*$"));
        page.navigate(base() + "/?mode=workbench&instance=" + target.getId());
        page.locator("#wb-input").waitFor();
        return page;
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }
}
