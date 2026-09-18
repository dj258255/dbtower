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
            Locator rows = page.locator("#top-table tbody tr[data-idx]");
            rows.first().waitFor();
            assertThat(rows.count()).isGreaterThan(0);
            assertThat(page.locator("#top-table .qtext .t-kw").count()).isGreaterThan(0);
            assertThat(page.locator("#top-table")).containsText("지연(ms)");
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
            comparison.locator("#top-table tbody tr[data-idx]").first()
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

    /**
     * UX 2차로 바뀐 화면 셋 — 확인이 필요한 DB 카드(#55·#56), 실행계획 트리(#83), AI 대화 칸(#57).
     *
     * <p>Top Query 촬영과 같은 규율을 따른다: 찍기 전에 화면이 실제로 그 상태인지 값으로 확인한다.
     * 캡처만 하고 검증하지 않으면 빈 화면·오류 화면을 문서에 올리게 된다.
     */
    @Test
    void UX_2차로_바뀐_화면_셋을_검증하고_촬영한다() {
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

            // 1. 첫 화면 — 확인이 필요한 DB 카드. 헬스 스코어 보고서가 와야 카드가 생긴다(40초 넘게 걸릴 수 있다)
            page.navigate(baseUrl + "/");
            Locator attention = page.locator("#attention");
            attention.waitFor(new Locator.WaitForOptions().setTimeout(120_000));
            assertThat(attention.textContent()).contains("확인이 필요한");
            assertThat(page.locator("#attention .attention-row").count()).isGreaterThan(0);
            assertThat((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth")).isTrue();
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("docs/images/webui/200-console-attention.jpg")));

            // 2. 쿼리 상세 — PostgreSQL 실행계획 트리. 노드가 하나라도 있어야 트리다
            page.navigate(baseUrl + "/?instance=" + instanceId);
            Locator rows = page.locator("#top-table tbody tr[data-idx]");
            rows.first().waitFor(new Locator.WaitForOptions().setTimeout(120_000));
            // 계획을 뜰 수 있는 것은 SELECT뿐이다 — 모니터 계정의 explain은 SELECT만 허용한다(안전 원칙).
            // 그리고 트리를 보이려면 노드가 여럿이어야 한다. pg_database_size 같은 한 노드짜리는 트리가 아니라 한 줄이다.
            Locator candidates = rows.filter(new Locator.FilterOptions()
                    .setHasText(Pattern.compile("select.+from", Pattern.CASE_INSENSITIVE)));
            candidates.first().waitFor(new Locator.WaitForOptions().setTimeout(60_000));
            Locator planTree = page.locator("#detail-plan .plan-node");
            int nodes = 0;
            for (int i = 0; i < candidates.count() && nodes < 2; i++) {
                candidates.nth(i).locator("td").first().click();
                page.locator("#btn-explain").click();
                try {
                    planTree.first().waitFor(new Locator.WaitForOptions().setTimeout(20_000));
                } catch (RuntimeException ignored) {
                    continue;   // 이 쿼리는 계획을 못 떴다 — 다음 후보로
                }
                nodes = planTree.count();
            }
            assertThat(nodes).as("노드가 여럿인 계획을 찾지 못했다").isGreaterThan(1);
            page.evaluate("() => document.querySelector('#detail-plan').scrollIntoView({block: 'center'})");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("docs/images/webui/201-querydetail-plan-tree.jpg")));

            // 3. AI 대화 칸 — 입구가 하나로 모였고 방식 셋을 거기서 고른다
            Locator ask = page.locator("#btn-ask-ai").first();
            if (ask.count() > 0) {
                ask.click();
            }
            Locator modes = page.locator("#chat-modes");
            modes.waitFor(new Locator.WaitForOptions().setTimeout(30_000));
            assertThat(modes.textContent()).contains("지금 답하기");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Path.of("docs/images/webui/202-console-ai-chat.jpg")));
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
