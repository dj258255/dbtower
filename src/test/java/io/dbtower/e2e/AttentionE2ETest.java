package io.dbtower.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
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
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.setDefaultAssertionTimeout;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 확인이 필요한 DB(#55)와 원인 지름길(#56).
 *
 * <p>헬스 스코어·백업 신선도는 주기 집계 스냅샷이라 테스트 컨텍스트에서는 비어 있다 — 두 보고서를 가로채 결정적으로 만든다.
 * 인스턴스는 닿지 않는 주소로 등록하고 헬스는 up으로 가로챈다(down 판정이면 대상 조회를 건너뛰어 지름길이 조회를 내지 않는다).</p>
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_E2E", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dbtower.security.api-token=test-api-token")
class AttentionE2ETest {

    private static final String PASSWORD = "e2e-attn-pass-1234";
    private static final String USER = "e2e-attn-viewer";

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
    static void launchBrowser() {
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
        users.findByUsername(USER).orElseGet(() -> users.save(
                new PlatformUser(USER, encoder.encode(PASSWORD), PlatformUser.Role.VIEWER)));
    }

    @AfterEach
    void cleanup() {
        // 라우트를 먼저 걷어낸 뒤 컨텍스트를 닫는다 — 늦게 도착한 가로채기 이벤트가 닫힌 페이지에서 터진다(180절)
        for (BrowserContext context : contexts) {
            for (Page page : context.pages()) {
                try {
                    if (!page.isClosed()) page.unrouteAll();
                } catch (RuntimeException ignored) {
                    // 이미 닫힌 페이지
                }
            }
            context.close();
        }
        contexts.clear();
        seeded.forEach(instances::delete);
        seeded.clear();
        users.findByUsername(USER).ifPresent(users::delete);
    }

    @Test
    void 첫_화면이_확인이_필요한_DB와_사유를_먼저_보이고_표는_접는다() {
        Fleet f = fleet();
        Page page = login(f);

        assertThat(page.locator("#attention")).isVisible();
        assertThat(page.locator("#attention-title")).hasText("확인이 필요한 DB 2대");
        assertThat(page.locator("#attention-total")).containsText("3대 중");
        Locator rows = page.locator("#attention-list .attention-row");
        assertThat(rows).hasCount(2);
        // 나쁜 순(서버 정렬) 그대로, A 등급은 오르지 않는다
        assertThat(rows.nth(0)).containsText("e2e-attn-down");
        assertThat(rows.nth(0)).containsText("다운");
        assertThat(rows.nth(0).locator(".attn-chip").first()).hasText("가용성 −45");
        assertThat(rows.nth(1)).containsText("e2e-attn-slow");
        assertThat(page.locator("#attention-list")).not().containsText("e2e-attn-ok");
        assertThat(page.locator("#attention-backup")).containsText("백업 없음 2대");

        // 표 둘은 접혀 있고, 누르면 펼친다
        assertThat(page.locator("#fleet-row")).isHidden();
        page.locator("#attention-more").click();
        assertThat(page.locator("#fleet-row")).isVisible();
        assertThat(page.locator("#attention-more")).hasAttribute("aria-expanded", "true");
        screenshot(page, "attention-first-screen.png");
    }

    @Test
    void 이상_감지_칩은_그_인스턴스를_열고_시점_비교를_낸다() {
        Fleet f = fleet();
        Page page = login(f);
        Locator chip = page.locator(".attn-chip[data-id='" + f.slow.getId() + "'][data-signal='ANOMALY']");
        assertThat(chip).hasText("이상 감지 −12");

        // 비교 결과를 가로채 표 모양까지 본다(#81) — 부하는 단위 %와 증감 %p
        page.route("**/api/instances/*/compare?*", route -> fulfill(route, "{\"totalCallsChangePct\":120.5,\"avgLatencyChangePct\":-10,"
                + "\"rowsExaminedChangePct\":5,\"newQueryCount\":1,\"queries\":["
                + "{\"queryId\":\"1\",\"queryText\":\"SELECT * FROM orders WHERE status = $1\",\"newQuery\":false,"
                + "\"baseQps\":10,\"targetQps\":30,\"qpsChangePct\":200,\"baseAvgMs\":2,\"targetAvgMs\":2,\"latencyChangePct\":0,"
                + "\"baseRowsPerCall\":1,\"targetRowsPerCall\":1,\"rowsPerCallChangePct\":0},"
                + "{\"queryId\":\"2\",\"queryText\":\"SELECT 1\",\"newQuery\":true,"
                + "\"baseQps\":0,\"targetQps\":10,\"qpsChangePct\":null,\"baseAvgMs\":0,\"targetAvgMs\":1,\"latencyChangePct\":null,"
                + "\"baseRowsPerCall\":0,\"targetRowsPerCall\":1,\"rowsPerCallChangePct\":null}]}"));
        Request compare = page.waitForRequest(
                req -> req.url().contains("/api/instances/" + f.slow.getId() + "/compare?"), chip::click);
        System.out.printf("MEASURE 원인 지름길 비교 요청: %s%n", compare.url());
        assertThat(compare.url()).contains("baseFrom=").contains("targetFrom=");
        assertThat(page.locator("#time-more")).hasAttribute("open", "");
        assertThat(page.locator(".tab[data-tab='top']")).hasClass(Pattern.compile("active"));
        assertThat(page.locator(".instance-card.selected")).containsText("e2e-attn-slow");
        Locator load = page.locator("#top-table tbody tr").first().locator("td").first();
        assertThat(load).hasText(Pattern.compile("^85\\.71% \\(▼ 14\\.29%p\\)$"));
        System.out.printf("MEASURE 비교 표 부하 칸: %s%n", load.textContent());
    }

    @Test
    void SLO_칩은_느린_쿼리를_열고_백업_버튼은_백업_표로_간다() {
        Fleet f = fleet();
        Page page = login(f);
        page.locator(".attn-chip[data-id='" + f.slow.getId() + "'][data-signal='SLO']").click();
        assertThat(page.locator("#tab-slow")).isVisible();
        assertThat(page.locator(".tab[data-tab='slow']")).hasClass(Pattern.compile("active"));

        page.locator(".attn-chip[data-id='" + f.slow.getId() + "'][data-signal='ADVISOR']").click();
        assertThat(page.locator(".mon-tab.active")).hasAttribute("data-mon", "diag");
        assertThat(page.locator(".advisors-card")).isInViewport();

        page.locator(".attention-backup-btn").click();
        assertThat(page.locator("#fleet-row")).isVisible();
        assertThat(page.locator(".backup-freshness-panel")).isInViewport();
    }

    private record Fleet(DatabaseInstance down, DatabaseInstance slow, DatabaseInstance ok) {
    }

    private Fleet fleet() {
        return new Fleet(instance("e2e-attn-down"), instance("e2e-attn-slow"), instance("e2e-attn-ok"));
    }

    private DatabaseInstance instance(String name) {
        seeded.add(instances.save(new DatabaseInstance(name, DbmsType.POSTGRESQL, "127.0.0.1", 1, "sample", "u", "p")));
        return seeded.getLast();
    }

    private static String contribution(String signal, String state, int penalty, String summary) {
        return "{\"signal\":\"" + signal + "\",\"state\":\"" + state + "\",\"penalty\":" + penalty + ",\"summary\":\"" + summary + "\"}";
    }

    private Page login(Fleet f) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setTimezoneId("Asia/Seoul").setViewportSize(1512, 900));
        context.setDefaultTimeout(20_000);
        contexts.add(context);
        Page page = context.newPage();
        String score = "{\"generatedAt\":\"2026-09-17T14:37:11\",\"total\":3,\"partialCount\":0,"
                + "\"gradeCounts\":{\"A\":1,\"B\":0,\"C\":0,\"D\":1,\"F\":1},\"instances\":["
                + "{\"instanceId\":" + f.down.getId() + ",\"instanceName\":\"e2e-attn-down\",\"type\":\"POSTGRESQL\",\"score\":7,\"grade\":\"F\",\"down\":true,\"partial\":false,\"contributions\":["
                + contribution("HEALTH", "PENALIZED", 45, "인스턴스 다운: 시간 초과") + ","
                + contribution("BACKUP", "PENALIZED", 20, "백업 없음 (사각지대)") + "]},"
                + "{\"instanceId\":" + f.slow.getId() + ",\"instanceName\":\"e2e-attn-slow\",\"type\":\"POSTGRESQL\",\"score\":63,\"grade\":\"D\",\"down\":false,\"partial\":false,\"contributions\":["
                + contribution("SLO", "PENALIZED", 25, "SLO 위반 · 버짓 소진 535%") + ","
                + contribution("ANOMALY", "PENALIZED", 12, "이상 쿼리 2개") + ","
                + contribution("ADVISOR", "PENALIZED", 6, "치명 0 · 경고 2 · 정보 0") + ","
                + contribution("HEALTH", "OK", 0, "정상") + "]},"
                + "{\"instanceId\":" + f.ok.getId() + ",\"instanceName\":\"e2e-attn-ok\",\"type\":\"POSTGRESQL\",\"score\":96,\"grade\":\"A\",\"down\":false,\"partial\":false,\"contributions\":["
                + contribution("HEALTH", "OK", 0, "정상") + "]}]}";
        String freshness = "{\"checkedAt\":\"2026-09-17T14:37:11\",\"thresholdHours\":24,\"freshCount\":1,\"staleCount\":0,\"noBackupCount\":2,\"instances\":["
                + "{\"instanceId\":" + f.down.getId() + ",\"instanceName\":\"e2e-attn-down\",\"type\":\"POSTGRESQL\",\"status\":\"NO_BACKUP\"},"
                + "{\"instanceId\":" + f.slow.getId() + ",\"instanceName\":\"e2e-attn-slow\",\"type\":\"POSTGRESQL\",\"status\":\"NO_BACKUP\"},"
                + "{\"instanceId\":" + f.ok.getId() + ",\"instanceName\":\"e2e-attn-ok\",\"type\":\"POSTGRESQL\",\"status\":\"FRESH\"}]}";
        page.route("**/api/health-score", route -> fulfill(route, score));
        page.route("**/api/backup-freshness", route -> fulfill(route, freshness));
        page.route("**/api/instances/*/health", route -> fulfill(route,
                "{\"up\":true,\"version\":\"16.15\",\"pingMillis\":2,\"message\":\"OK\"}"));
        page.navigate(base() + "/login.html");
        page.fill("#username", USER);
        page.fill("#password", PASSWORD);
        page.click("button[type=submit]");
        page.waitForURL(Pattern.compile("^(?!.*/login).*$"));
        return page;
    }

    private static void fulfill(Route route, String body) {
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
