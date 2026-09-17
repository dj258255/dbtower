package io.dbtower.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.ViewportSize;
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
 * 좁은 화면 실측 (B6) — 뷰포트를 셋으로 고정하고 가로 넘침을 잰다.
 *
 * <p>뷰포트는 Playwright로 정확히 맞춰 잰다. Chrome 확장 창 조절(innerWidth 그대로)·iframe(X-Frame-Options)·
 * headless {@code --window-size}(뷰포트가 넓게 잡힘)는 모두 틀린 결과를 냈다(AGENTS.md 화면 규칙).
 *
 * <p>대상 DB는 필요 없다 — 인스턴스는 닿지 않는 주소(127.0.0.1:1)로 등록하고 Top Query 표만 {@code page.route}로 채운다
 * (표가 비면 쿼리 상세를 열 수 없어, 이 묶음이 재는 자리 중 하나를 못 본다).
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_E2E", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dbtower.security.api-token=test-api-token")
class NarrowViewportE2ETest {

    private static final String PASSWORD = "narrow-e2e-pass-1234";
    private static final String USER = "e2e-narrow-viewer";
    /** 워크벤치는 WORKBENCH 능력이 있어야 열린다 — 관제 역할에게는 셸이 먼저 막는다 */
    private static final String WORKBENCH_USER = "e2e-narrow-requester";
    private static final List<ViewportSize> VIEWPORTS = List.of(
            new ViewportSize(390, 844), new ViewportSize(768, 1024), new ViewportSize(1280, 800));

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
        users.findByUsername(WORKBENCH_USER).orElseGet(() -> users.save(
                new PlatformUser(WORKBENCH_USER, encoder.encode(PASSWORD), PlatformUser.Role.REQUESTER)));
    }

    @AfterEach
    void cleanup() {
        // 인스턴스를 먼저 지운다 — 화면 정리(라우트 해제·컨텍스트 닫기)가 실패해도 다음 회차가 unique 충돌로 죽지 않게
        seeded.forEach(instances::delete);
        seeded.clear();
        users.findByUsername(USER).ifPresent(users::delete);
        users.findByUsername(WORKBENCH_USER).ifPresent(users::delete);
        contexts.forEach(c -> {
            try {
                c.unrouteAll();
                c.close();
            } catch (Exception ignored) {
                // 이미 닫힌 컨텍스트 — 다음 테스트는 새 컨텍스트를 쓴다
            }
        });
        contexts.clear();
    }

    /** 관제 — 인스턴스를 고르고 쿼리 상세를 연 상태에서 세 뷰포트를 잰다 */
    @Test
    void 관제는_세_뷰포트에서_가로로_넘치지_않는다() {
        DatabaseInstance db = instance();
        for (ViewportSize size : VIEWPORTS) {
            Page page = open(size, "/?instance=" + db.getId());
            page.locator("#result-panel").waitFor();
            page.locator("#top-table tbody tr").first().waitFor();
            page.locator("#top-table tbody tr").first().click();
            assertThat(page.locator("#query-detail")).isVisible();
            page.waitForTimeout(700);

            assertNoOverflow(page, "관제 " + size.width + "x" + size.height);

            // 채팅 헤더의 고정 문구는 끝까지 남는다(이름 쪽이 자리를 양보한다)
            Locator fixed = page.locator("#chat-sub .chat-sub-fixed");
            assertThat(fixed).isVisible();
            assertThat(fixed).hasText("· 읽기 도구로만 답합니다");
            assertThat(clipped(fixed)).as("고정 문구가 잘렸다").isLessThanOrEqualTo(0);

            // 쿼리 상세 토글 묶음은 줄바꿈으로 담는다 — 넘치는 대신 접힌다
            assertThat(clipped(page.locator("#query-detail .detail-views"))).as("상세 토글이 넘쳤다").isLessThanOrEqualTo(0);

            // 상세는 표 가로 스크롤 안에 갇히지 않는다 — 상자의 '보이는 폭'을 쓰고 오른쪽 끝이 화면 안에 있다(B6fix).
            // 전에는 셀 폭(=표 폭)을 써서 390px에서 토글·더보기의 오른쪽이 스크롤 밖으로 나갔다
            Locator box = page.locator("#tab-top .table-scroll").first();
            double boxLeft = box.boundingBox().x;
            double boxRight = boxLeft + box.boundingBox().width;
            System.out.printf("MEASURE %d 상세 box=[%.0f,%.0f] panel=[%.0f,%.0f] 더보기 right=%.0f 토글 right=%.0f%n",
                    size.width, boxLeft, boxRight,
                    page.locator("#query-detail").boundingBox().x,
                    page.locator("#query-detail").boundingBox().x + page.locator("#query-detail").boundingBox().width,
                    page.locator("#btn-detail-more").boundingBox().x + page.locator("#btn-detail-more").boundingBox().width,
                    page.locator("#query-detail .detail-views").boundingBox().x + page.locator("#query-detail .detail-views").boundingBox().width);
            for (String sel : List.of("#query-detail", "#query-detail .detail-head",
                    "#query-detail .detail-views", "#btn-detail-more", "#query-detail .detail-section h3 .hint")) {
                org.assertj.core.api.Assertions.assertThat(page.locator(sel).first().boundingBox().x + page.locator(sel).first().boundingBox().width)
                        .as("%s 의 오른쪽 끝이 스크롤 상자 밖이다", sel).isLessThanOrEqualTo(boxRight + 0.5);
                org.assertj.core.api.Assertions.assertThat(page.locator(sel).first().boundingBox().x)
                        .as("%s 의 왼쪽 끝이 스크롤 상자 밖이다", sel).isGreaterThanOrEqualTo(boxLeft - 0.5);
            }
            // 표를 옆으로 밀어도 상세는 상자의 보이는 자리에 그대로 있다(sticky) — 왼쪽으로 끌려나가지 않는다.
            // 오른쪽 끝까지 함께 본다: 왼쪽만 보면 x가 음수여도 통과해 버려 이 검사가 무의미해진다
            box.evaluate("el => el.scrollLeft = 240");
            page.waitForTimeout(150);
            com.microsoft.playwright.options.BoundingBox scrolled = page.locator("#query-detail").boundingBox();
            org.assertj.core.api.Assertions.assertThat(scrolled.x)
                    .as("표를 밀자 상세 왼쪽이 상자 밖으로 나갔다").isGreaterThanOrEqualTo(boxLeft - 0.5);
            org.assertj.core.api.Assertions.assertThat(scrolled.x + scrolled.width)
                    .as("표를 밀자 상세 오른쪽이 상자 밖으로 나갔다").isLessThanOrEqualTo(boxRight + 0.5);
            box.evaluate("el => el.scrollLeft = 0");
            page.waitForTimeout(150);

            screenshot(page, "narrow-console-" + size.width + ".png");
        }
    }

    /** 워크벤치 — 세 뷰포트에서 탭 줄이 페이지를 밀지 않는지 */
    @Test
    void 워크벤치는_세_뷰포트에서_가로로_넘치지_않는다() {
        DatabaseInstance db = instance();
        for (ViewportSize size : VIEWPORTS) {
            Page page = open(size, "/?mode=workbench&instance=" + db.getId(), WORKBENCH_USER);
            page.locator("#wb-input").waitFor();
            page.waitForTimeout(600);

            assertNoOverflow(page, "워크벤치 " + size.width + "x" + size.height);
            // 탭 줄은 자기 안에서 가로로 스크롤한다(페이지를 밀지 않는다) — 넘칠 수 있는 것은 이 줄뿐이다
            assertThat(((Number) page.locator(".wb-tabs").evaluate(
                    "el => el.scrollWidth - el.clientWidth")).intValue())
                    .as("탭 줄이 안에서 스크롤하지 않고 페이지를 민다").isGreaterThanOrEqualTo(0);

            screenshot(page, "narrow-workbench-" + size.width + ".png");
        }
    }

    private void assertNoOverflow(Page page, String where) {
        int scrollWidth = num(page, "() => document.documentElement.scrollWidth");
        int innerWidth = num(page, "() => window.innerWidth");
        // 넘친 요소를 찾을 때 쓰려고 값과 함께 가장 넓은 요소를 남긴다
        String widest = (String) page.evaluate(
                "() => [...document.querySelectorAll('*')]"
                        + ".filter(el => el.getBoundingClientRect().right > window.innerWidth + 1)"
                        + ".slice(0, 3).map(el => el.tagName + '.' + el.className + ':' + Math.round(el.getBoundingClientRect().right)).join(' | ')");
        System.out.printf("MEASURE %s scrollWidth=%d innerWidth=%d overflow=%d%n",
                where, scrollWidth, innerWidth, scrollWidth - innerWidth);
        assertThat(scrollWidth).as("%s — scrollWidth %d > innerWidth %d%s", where, scrollWidth, innerWidth,
                widest.isEmpty() ? "" : " 넘친 요소: " + widest).isLessThanOrEqualTo(innerWidth);
    }

    private static int num(Page page, String expression) {
        return ((Number) page.evaluate(expression)).intValue();
    }

    private static int clipped(Locator locator) {
        return ((Number) locator.evaluate("el => el.scrollWidth - el.clientWidth")).intValue();
    }

    /** Top Query 표만 대신 채운다 — 행이 없으면 쿼리 상세를 열 수 없다 */
    private Page open(ViewportSize size, String path) {
        return open(size, path, USER);
    }

    private Page open(ViewportSize size, String path, String username) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setTimezoneId("Asia/Seoul").setViewportSize(size));
        context.setDefaultTimeout(20_000);
        contexts.add(context);
        Page page = context.newPage();
        page.route("**/api/instances/*/query-stats*", route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(200).setContentType("application/json").setBody("""
                        [{"queryId":"q1","queryText":"SELECT * FROM orders WHERE customer_id = ? AND created_at > ? ORDER BY created_at DESC","loadPct":42.5,"callsPerSec":3.25,"avgLatencyMs":12.5,"rowsExaminedAvg":1200},
                         {"queryId":"q2","queryText":"SELECT count(*) FROM sessions GROUP BY device","loadPct":18.0,"callsPerSec":1.5,"avgLatencyMs":4.0,"rowsExaminedAvg":300}]""")));
        page.navigate(base() + "/login.html");
        page.fill("#username", username);
        page.fill("#password", PASSWORD);
        page.click("button[type=submit]");
        page.waitForURL(Pattern.compile("^(?!.*/login).*$"));
        page.navigate(base() + path);
        return page;
    }

    private DatabaseInstance instance() {
        seeded.add(instances.save(new DatabaseInstance("e2e-narrow-db", DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p")));
        return seeded.getLast();
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
