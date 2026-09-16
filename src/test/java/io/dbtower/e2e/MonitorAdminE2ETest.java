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
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.setDefaultAssertionTimeout;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모니터링 탭·관리 화면 E2E (B4) — 조작 계층을 실제 브라우저에서 본다.
 *
 * <p>대상 DB가 필요 없다. 여기서 확인하는 것은 데이터가 아니라 <b>무엇이 보이고 눌렀을 때 요청이 나가는가</b>다:
 * 역할 select를 바꿔도 PATCH가 나가지 않고 "적용"을 눌러야 나가는지, 제공 도구가 접혀 있는지, 복사가 명령만
 * 가져가는지, 값이 없는 필터가 감춰지는지.
 *
 * <p>돌리는 법: {@code ./gradlew playwrightInstall && DBTOWER_E2E=1 ./gradlew test --tests '*MonitorAdminE2ETest'}
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_E2E", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dbtower.security.api-token=test-api-token")
class MonitorAdminE2ETest {

    private static final String PASSWORD = "e2e-mon-pass-1234";
    private static final String ADMIN = "e2e-mon-admin";
    private static final String TARGET = "e2e-mon-viewer";

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
    /** 역할 PATCH가 실제로 나갔는지 — "바꾸는 즉시 보내지 않는다"를 요청 가로채기로 확인한다 */
    private final List<String> rolePatches = new CopyOnWriteArrayList<>();

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
        rolePatches.clear();
        users.findByUsername(ADMIN).orElseGet(() -> users.save(
                new PlatformUser(ADMIN, encoder.encode(PASSWORD), PlatformUser.Role.ADMIN)));
        users.findByUsername(TARGET).orElseGet(() -> users.save(
                new PlatformUser(TARGET, encoder.encode(PASSWORD), PlatformUser.Role.VIEWER)));
    }

    @AfterEach
    void cleanup() {
        contexts.forEach(BrowserContext::close);
        contexts.clear();
        seeded.forEach(instances::delete);
        seeded.clear();
        users.findByUsername(ADMIN).ifPresent(users::delete);
        users.findByUsername(TARGET).ifPresent(users::delete);
    }

    private DatabaseInstance instance(String name, String environment) {
        DatabaseInstance instance = new DatabaseInstance(name, DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p");
        if (environment != null) instance.updateMeta(null, null, null, environment, null, null);
        seeded.add(instances.save(instance));
        return seeded.getLast();
    }

    /**
     * 역할 select는 값을 바꿔도 서버로 가지 않는다 — 되돌릴 수 없는 요청이 오조작으로 나가지 않게(B4).
     * "적용"을 눌러야 나가고, "취소"는 원래 값으로 되돌린다.
     */
    @Test
    void 역할_변경은_적용을_눌러야_서버로_간다() {
        instance("e2e-mon-a", null);
        Page page = consoleAs(ADMIN, true);
        page.route("**/api/security/users/*/role", route -> {
            rolePatches.add(route.request().url());
            fulfillJson(route, "{\"username\":\"x\",\"role\":\"OPERATOR\",\"note\":\"다음 로그인부터 적용됩니다\"}");
        });

        Locator row = page.locator("#users-table tbody tr").filter(new Locator.FilterOptions().setHasText(TARGET));
        row.waitFor();
        Locator select = row.locator("[data-user-role]");
        assertThat(select).hasValue("VIEWER");

        // 바꾸면 그 행에만 적용·취소가 나타난다 — 아직 요청은 나가지 않는다
        select.selectOption("OPERATOR");
        assertThat(row.locator("[data-role-apply]")).isVisible();
        assertThat(row.locator("[data-role-cancel]")).isVisible();
        page.waitForTimeout(700);
        assertThat(rolePatches).as("select만 바꿨는데 PATCH가 나갔다").isEmpty();
        row.scrollIntoViewIfNeeded();   // 사용자 표가 화면 밖이라 사진에 안 담긴다
        screenshot(page, "monitor-users-role-pending.png");

        // 취소는 원래 값으로 되돌리고 버튼을 감춘다
        row.locator("[data-role-cancel]").click();
        assertThat(row.locator("[data-user-role]")).hasValue("VIEWER");
        assertThat(row.locator("[data-role-apply]")).isHidden();
        assertThat(rolePatches).isEmpty();

        // 적용을 눌러야 나간다
        row.locator("[data-user-role]").selectOption("OPERATOR");
        row.locator("[data-role-apply]").click();
        page.waitForCondition(() -> !rolePatches.isEmpty());
        assertThat(rolePatches).as("적용이 역할 변경을 부르지 않았다").hasSize(1);
        assertThat(page.locator("#users-msg")).containsText("다음 로그인부터 적용됩니다");
    }

    /** 자기 자신을 ADMIN에서 내리면 그 자리에서 알린다 — 서버는 "마지막 ADMIN"만 막는다. */
    @Test
    void 자기_관리자_권한을_내리면_행에_경고가_뜬다() {
        instance("e2e-mon-b", null);
        Page page = consoleAs(ADMIN, true);

        Locator row = page.locator("#users-table tbody tr").filter(new Locator.FilterOptions().setHasText(ADMIN));
        row.waitFor();
        assertThat(row.locator(".user-role-warn")).isHidden();

        row.locator("[data-user-role]").selectOption("OPERATOR");
        assertThat(row.locator(".user-role-warn")).isVisible();
        assertThat(row.locator(".user-role-warn")).hasText("내 관리자 권한이 없어집니다");
        row.scrollIntoViewIfNeeded();
        screenshot(page, "monitor-users-self-demote.png");

        // 다른 사람을 내릴 때는 경고가 뜨지 않는다
        Locator other = page.locator("#users-table tbody tr").filter(new Locator.FilterOptions().setHasText(TARGET));
        other.locator("[data-user-role]").selectOption("OPERATOR");
        assertThat(other.locator(".user-role-warn")).isHidden();
    }

    /** 제공 도구는 접혀 있고, 복사는 code 안의 명령만 가져간다(설명 글자가 섞이지 않는다). */
    @Test
    void 제공_도구는_접혀_있고_복사는_명령만_가져간다() {
        instance("e2e-mon-c", null);
        Page page = consoleAs(ADMIN, true);

        // 관리자에게는 서비스 토큰이 들어간 명령이 채워지고, 보조 줄이 그 사실을 말한다
        assertThat(page.locator("#mcp-cmd-http")).containsText("--header \"Authorization: Bearer");
        assertThat(page.locator("#mcp-http-note")).hasText("서비스 토큰이 들어 있습니다. 공유하지 마세요.");
        assertThat(page.locator("#mcp-cmd-http")).not().containsText("(");

        assertThat(page.locator("#mcp-tools")).isHidden();
        assertThat(page.locator("#btn-mcp-tools")).hasText(Pattern.compile("제공 도구 \\d+개 보기"));
        int count = Integer.parseInt(page.locator("#btn-mcp-tools").textContent().replaceAll("\\D", ""));
        assertThat(count).isGreaterThan(10);
        screenshot(page, "monitor-mcp-collapsed.png");

        page.click("#btn-mcp-tools");
        assertThat(page.locator("#mcp-tools")).isVisible();
        assertThat(page.locator("#mcp-tools .mcp-tool")).hasCount(count);
        assertThat(page.locator("#btn-mcp-tools")).hasText("접기");
        // 항목을 누르면 그 항목의 설명이 펼쳐진다(툴팁이 아니다)
        Locator first = page.locator("#mcp-tools .mcp-tool").first();
        assertThat(first).hasAttribute("aria-expanded", "false");
        first.click();
        assertThat(first).hasAttribute("aria-expanded", "true");
        screenshot(page, "monitor-mcp-tools.png");

        // 복사 — 클립보드에는 code의 글자만 들어간다
        page.locator(".mcp-cmd-row [data-copy='mcp-cmd-http']").click();
        String copied = (String) page.evaluate("navigator.clipboard.readText()");
        assertThat(copied).isEqualTo(page.locator("#mcp-cmd-http").textContent().trim());
        assertThat(copied).startsWith("claude mcp add --transport http dbtower").doesNotContain("로그인").doesNotContain("(");
    }

    /** 값이 없는 필터는 감춘다 — 늘 "전체"만 보이는 select는 동작하지 않는 것처럼 읽힌다(사용자 지적). */
    @Test
    void 값이_없는_인스턴스뿐이면_필터_줄이_감춰진다() {
        instance("e2e-mon-d", null);
        instance("e2e-mon-e", null);
        Page page = consoleAs(ADMIN, false);
        assertThat(page.locator("#inst-count")).containsText("대");

        assertThat(page.locator(".instance-filter-row")).isHidden();
        assertThat(page.locator("#inst-search")).isVisible();     // 검색 칸은 남는다
        screenshot(page, "monitor-filter-hidden.png");
    }

    /** 서로 다른 값이 둘 이상일 때만 그 필터가 보인다 — 기종 필터도 같은 규칙. */
    @Test
    void 서로_다른_값이_둘_이상이면_그_필터만_보인다() {
        instance("e2e-mon-f", "prod");
        instance("e2e-mon-g", "staging");
        Page page = consoleAs(ADMIN, false);
        assertThat(page.locator("#inst-count")).containsText("대");

        assertThat(page.locator(".instance-filter-row")).isVisible();
        // 환경은 두 값이라 보이고, 리전·클러스터·팀은 값이 없어 감춰진다. 기종은 MYSQL 하나뿐이라 감춰진다
        assertThat(page.locator(".cs:has(#inst-env)")).isVisible();
        assertThat(page.locator(".cs:has(#inst-region)")).isHidden();
        assertThat(page.locator(".cs:has(#inst-cluster)")).isHidden();
        assertThat(page.locator(".cs:has(#inst-team)")).isHidden();
        assertThat(page.locator(".cs:has(#inst-engine)")).isHidden();
        screenshot(page, "monitor-filter-visible.png");
    }

    /** 관리자로 관제를 연다. infra 그룹(제공 도구·사용자)까지 열어 둔다. */
    private Page consoleAs(String username, boolean openInfra) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setTimezoneId("Asia/Seoul").setViewportSize(1512, 900));
        context.setDefaultTimeout(20_000);
        context.grantPermissions(List.of("clipboard-read", "clipboard-write"),
                new BrowserContext.GrantPermissionsOptions().setOrigin(base()));
        contexts.add(context);
        Page page = context.newPage();
        page.navigate(base() + "/login.html");
        page.fill("#username", username);
        page.fill("#password", PASSWORD);
        page.click("button[type=submit]");
        page.waitForURL(Pattern.compile("^(?!.*/login).*$"));
        page.navigate(base() + "/" + (seeded.isEmpty() ? "" : "?instance=" + seeded.getFirst().getId()));
        page.locator("#inst-count").waitFor();
        if (openInfra) {
            page.locator(".tab[data-tab='monitor']").click();   // Monitoring 탭 안에 서브내비가 있다
            page.locator(".mon-tab[data-mon='infra']").click();
            page.locator("#users-card").waitFor();
        }
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
