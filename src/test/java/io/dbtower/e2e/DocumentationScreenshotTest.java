package io.dbtower.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문서 대표 화면 재촬영 — 평소 테스트에는 저장소 파일을 바꾸지 않으며 DBTOWER_CAPTURE=1일 때만 돈다.
 * 연결할 앱과 계정은 외부에서 주입해 실제 화면을 찍고, 캡처 전에 핵심 값과 레이아웃을 함께 검증한다.
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_CAPTURE", matches = "1")
class DocumentationScreenshotTest {

    private static final Set<String> ARMED = Set.of(
            "execute", "execute-raw", "revert", "approve", "cancel", "resolve-applied", "resolve-not-applied");

    @Test
    void Top_Query_대표_화면을_검증하고_촬영한다() {
        String baseUrl = env("DBTOWER_CAPTURE_BASE_URL", "http://127.0.0.1:8080");
        String username = env("DBTOWER_CAPTURE_USERNAME", "admin");
        String password = requiredEnv("DBTOWER_CAPTURE_PASSWORD");
        String instanceId = requiredEnv("DBTOWER_CAPTURE_INSTANCE_ID");

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setTimezoneId("Asia/Seoul").setViewportSize(1512, 900))) {
            Page page = context.newPage();
            page.navigate(baseUrl + "/login.html");
            page.fill("#username", username);
            page.fill("#password", password);
            page.click("button[type=submit]");
            page.waitForURL(Pattern.compile("^(?!.*\\/login).*$"));

            page.navigate(baseUrl + "/?instance=" + instanceId);
            Locator rows = page.locator("#top-table tbody tr");
            rows.first().waitFor();
            assertThat(rows.count()).isGreaterThan(0);
            assertThat(page.locator("#top-table .qtext .t-kw").count()).isGreaterThan(0);
            assertThat(page.locator("#top-table")).containsText("Latency(ms)");
            assertThat((Boolean) page.evaluate("""
                    [...document.querySelectorAll('#top-table tbody tr td:first-child')]
                      .map((cell) => Number.parseFloat(cell.textContent))
                      .every((value, index, values) => index === 0 || values[index - 1] >= value)
                    """)).isTrue();
            assertThat((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth")).isTrue();

            page.evaluate("""
                    () => {
                      document.querySelector('#result-panel').scrollIntoView({block: 'start'});
                      scrollBy(0, -72);
                    }
                    """);

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("docs/images/webui/165-unified-monitor-query-highlighted.jpg")));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DBTOWER_CAPTURE_FLOW", matches = "1")
    void 승인_실행_전후비교_일곱_장면을_촬영하고_역변경한다() throws Exception {
        String instanceId = requiredEnv("DBTOWER_CAPTURE_INSTANCE_ID");
        Path output = Path.of("build/capture-gif-frames");
        Files.createDirectories(output);

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch();
             BrowserContext requester = captureContext(browser);
             BrowserContext approver = captureContext(browser);
             BrowserContext operator = captureContext(browser)) {
            Page dashboard = requester.newPage();
            dashboard.navigate("http://127.0.0.1:8802/?instance=" + instanceId);
            dashboard.locator("#top-table tbody tr:has-text(\"ORDER BY created_at\")").first().click();
            dashboard.locator("#btn-advisor").click();
            dashboard.locator("#advisor-columns").fill("payment_events(merchant_id, created_at)");
            dashboard.locator("#btn-advisor-run").click();
            dashboard.locator("#btn-advisor-ticket").waitFor();
            shot(dashboard, output.resolve("01.png"));

            dashboard.locator("#btn-advisor-ticket").click();
            dashboard.locator("#wb-ticket-modal:not([hidden])").waitFor();
            shot(dashboard, output.resolve("02.png"));

            dashboard.locator("#wb-ticket-ok").click();
            dashboard.locator("#wb-ticket .tk-id").waitFor(new Locator.WaitForOptions().setTimeout(180_000));
            dashboard.locator("#wb-ticket .tk-steps, #wb-ticket .tk-ai").first()
                    .waitFor(new Locator.WaitForOptions().setTimeout(180_000));
            String ticketId = dashboard.locator("#wb-ticket .tk-id").textContent().replace("#", "").trim();
            shot(dashboard, output.resolve("03.png"));

            Page approval = ticketPage(approver, 8803, instanceId, ticketId);
            act(approval, "dry-run");
            approval.locator("#wb-ticket details.tk-exec").first()
                    .waitFor(new Locator.WaitForOptions().setTimeout(180_000));
            shot(approval, output.resolve("04.png"));

            act(approval, "approve");
            approval.locator("#wb-ticket button[data-act=\"approve\"]")
                    .waitFor(new Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED));
            shot(approval, output.resolve("05.png"));

            Page execution = ticketPage(operator, 8804, instanceId, ticketId);
            act(execution, "execute");
            Locator compare = execution.locator("#wb-ticket a:has-text(\"대시보드에서 전후\")");
            compare.waitFor(new Locator.WaitForOptions().setTimeout(180_000));
            shot(execution, output.resolve("06.png"));

            Page comparison = operator.waitForPage(compare::click);
            comparison.locator("#result-panel").waitFor();
            // 딥링크가 자동으로 시작한 첫 비교가 끝난 뒤, 스냅샷이 충분한 재현 구간으로 다시 조회한다.
            assertThat(comparison.locator("#compare-summary")).containsText("비교하지 못했습니다");
            Response compareResponse = comparison.waitForResponse(
                    response -> response.url().contains("/compare?"), () -> comparison.evaluate("""
                    () => {
                      const local = (date) => {
                        const p = (value) => String(value).padStart(2, '0');
                        return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}T${p(date.getHours())}:${p(date.getMinutes())}`;
                      };
                      const now = new Date();
                      document.querySelector('#target-to').value = local(now);
                      document.querySelector('#target-from').value = local(new Date(now - 3 * 60_000));
                      document.querySelector('#base-to').value = local(new Date(now - 3 * 60_000));
                      document.querySelector('#base-from').value = local(new Date(now - 6 * 60_000));
                      document.querySelector('#btn-compare').click();
                    }
                    """));
            assertThat(compareResponse.ok()).isTrue();
            comparison.locator("#top-table th:has-text(\"QPS\")")
                    .waitFor(new Locator.WaitForOptions().setTimeout(60_000));
            // DDL 실행은 다음 수집 배치 전에 끝날 수 있으므로 DDL 행 자체가 아니라 실제 차분 행을 검증한다.
            comparison.locator("#top-table tbody tr").first()
                    .waitFor(new Locator.WaitForOptions().setTimeout(60_000));
            assertThat(comparison.locator("#compare-summary")).not().containsText("비교하지 못했습니다");
            comparison.evaluate("""
                    () => {
                      document.querySelector('#result-panel').scrollIntoView({block: 'start'});
                      scrollBy(0, -72);
                    }
                    """);
            shot(comparison, output.resolve("07.png"));

            execution.bringToFront();
            execution.locator("#wb-ticket button[data-act=\"propose\"]").first().click();
            execution.locator("#wb-ticket-modal:not([hidden])").waitFor();
            execution.locator("#wb-ticket-ok").click();
            execution.locator("#wb-ticket .tk-id:not(:has-text(\"#" + ticketId + "\"))")
                    .waitFor(new Locator.WaitForOptions().setTimeout(180_000));
            String inverseId = execution.locator("#wb-ticket .tk-id").textContent().replace("#", "").trim();
            Page inverseApproval = ticketPage(approver, 8803, instanceId, inverseId);
            act(inverseApproval, "approve");
            inverseApproval.locator("#wb-ticket button[data-act=\"approve\"]")
                    .waitFor(new Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED));
            Page inverseExecution = ticketPage(operator, 8804, instanceId, inverseId);
            act(inverseExecution, "execute");
            inverseExecution.locator("#wb-ticket .tk-steps:has-text(\"실행 p-operator\")")
                    .waitFor(new Locator.WaitForOptions().setTimeout(180_000));
        }
    }

    private static BrowserContext captureContext(Browser browser) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setTimezoneId("Asia/Seoul").setViewportSize(1512, 900));
        context.setDefaultTimeout(60_000);
        return context;
    }

    private static Page ticketPage(BrowserContext context, int port, String instanceId, String ticketId) {
        Page page = context.newPage();
        page.navigate("http://127.0.0.1:" + port + "/?mode=workbench&instance=" + instanceId + "&ticket=" + ticketId);
        page.locator("#wb-ticket .tk-id:has-text(\"#" + ticketId + "\")").waitFor();
        page.waitForTimeout(1_200);
        return page;
    }

    private static void act(Page page, String action) {
        page.locator("#wb-ticket button[data-act=\"" + action + "\"]").click();
        if (ARMED.contains(action)) {
            page.locator("#wb-ticket button[data-act=\"" + action + "\"]:has-text(\"한 번 더\")").click();
        }
    }

    private static void shot(Page page, Path path) {
        page.waitForTimeout(700);
        page.screenshot(new Page.ScreenshotOptions().setPath(path));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 환경변수가 필요합니다");
        }
        return value;
    }
}
