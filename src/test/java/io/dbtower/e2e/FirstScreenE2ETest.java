package io.dbtower.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.setDefaultAssertionTimeout;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 처음 들어온 사람의 화면 (B6) — 역할마다, 상황마다 첫 화면이 무엇을 말하는지 본다.
 *
 * <p>보이는 내용은 먼저 설계한다: 빈 자리에는 "지금 무엇이 없고 다음에 무엇을 누르면 되는지" 한 문장이 있어야 하고,
 * 같은 뜻이 화면 두 곳에 겹치면 안 되며, 역할상 할 수 없는 일을 권하면 안 된다. 그 셋을 문장으로 단언한다.
 * 대상 DB는 필요 없다 — 인스턴스는 닿지 않는 주소(127.0.0.1:1)로 등록한다.
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_E2E", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dbtower.security.api-token=test-api-token")
class FirstScreenE2ETest {

    private static final String PASSWORD = "firstscreen-e2e-pass-1234";
    private static final Map<String, PlatformUser.Role> PERSONAS = Map.of(
            "e2e-fs-viewer", PlatformUser.Role.VIEWER,
            "e2e-fs-requester", PlatformUser.Role.REQUESTER,
            "e2e-fs-approver", PlatformUser.Role.APPROVER,
            "e2e-fs-operator", PlatformUser.Role.OPERATOR,
            "e2e-fs-admin", PlatformUser.Role.ADMIN);
    /** 로그인 직후 첫 화면이 관제(/)인 역할 — 요청자만 워크벤치로 간다(PlatformRoles.homeFor) */
    private static final List<String> CONSOLE_ROLES = List.of("e2e-fs-viewer", "e2e-fs-approver", "e2e-fs-operator", "e2e-fs-admin");

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
        PERSONAS.forEach((name, role) -> users.findByUsername(name)
                .orElseGet(() -> users.save(new PlatformUser(name, encoder.encode(PASSWORD), role))));
    }

    @AfterEach
    void cleanup() {
        contexts.forEach(BrowserContext::close);
        contexts.clear();
        seeded.forEach(instances::delete);
        seeded.clear();
        PERSONAS.keySet().forEach(name -> users.findByUsername(name).ifPresent(users::delete));
    }

    private DatabaseInstance instance() {
        seeded.add(instances.save(new DatabaseInstance("e2e-fs-db", DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p")));
        return seeded.getLast();
    }

    /** A — 관제, 인스턴스를 아직 안 고름. 역할마다 같은 자리에 무엇이 보이는가 */
    @Test
    void 관제_인스턴스를_안_고른_첫_화면() {
        instance();
        for (String role : CONSOLE_ROLES) {
            Page page = loginAs(role);
            // 인스턴스가 있는데 함대 카드가 "등록된 인스턴스가 없습니다"라고 하면 화면이 거짓말을 한다(집계 스냅샷이 빈 것뿐이다)
            assertThat(page.locator("#score-result")).not().containsText("등록된 인스턴스가 없습니다");
            assertThat(page.locator("#inst-onboarding")).isHidden();
            assertThat(page.locator("#fleet-row")).isVisible();
            // 사이드바 목록은 "검색하거나 필터를 고르세요"로 다음 행동을 말한다(등록은 안내하지 않는다 — 이미 있다)
            assertThat(page.locator("#instance-list")).containsText("위에서 검색하거나 필터를 선택하면");
            assertThat(page.locator("#chat-sub")).containsText("왼쪽에서 인스턴스를 고르세요");
            // 인스턴스가 있으면 대화 영역이 "왼쪽에서 고르면 물어볼 수 있습니다"를 말하고 placeholder도 사실이다
            assertThat(page.locator("#chat-log")).containsText("왼쪽에서 인스턴스를 고르면 그 DB에 물어볼 수 있습니다");
            assertThat(page.locator("#diagnose-question")).hasAttribute("placeholder", "왼쪽에서 인스턴스를 고르면 물어볼 수 있습니다");
            screenshot(page, "first-console-none-" + role.replace("e2e-fs-", "") + ".png");
            contexts.getLast().close();
        }
    }

    /** B — 관제, 인스턴스를 골랐지만 대상에 닿지 않아 수집된 스냅샷이 없음 */
    @Test
    void 관제_인스턴스를_고른_뒤_스냅샷이_없을_때() {
        DatabaseInstance db = instance();
        Page page = loginAs("e2e-fs-viewer", "?instance=" + db.getId());
        page.locator("#result-panel").waitFor();
        page.waitForTimeout(6000);
        // 빈 그래프는 빈 상자를 남기지 않는다 — 한 줄만 남고 상자는 접힌다
        assertThat(page.locator("#chart-empty")).isVisible();
        assertThat(page.locator("#chart-empty")).containsText("이 구간에 수집된 스냅샷이 없습니다");
        assertThat(page.locator("#activity-chart")).isHidden();
        org.assertj.core.api.Assertions.assertThat(page.locator("#chart-wrap").boundingBox().height)
                .as("빈 차트 틀이 아직 크다").isLessThanOrEqualTo(80.0);
        // 대상에 닿지 못한 표도 사유를 말한다(빈 채로 멈추지 않는다)
        assertThat(page.locator("#top-table tbody")).containsText("불러오지 못했습니다");
        assertThat(page.locator("#score-result")).not().containsText("등록된 인스턴스가 없습니다");
        screenshot(page, "first-console-picked.png");
        fullPage(page, "first-console-picked-full.png");
    }

    /** C — 워크벤치 처음 들어옴(워크시트가 없어 자동 생성됨) */
    @Test
    void 워크벤치_처음_들어온_화면() {
        instance();
        Page page = loginAs("e2e-fs-requester");
        assertThat(page).hasURL(Pattern.compile("mode=workbench"));
        page.locator("#wb-input").waitFor();
        page.waitForTimeout(1200);
        assertThat(page.locator("#wb-sheets .wb-sheet-tab")).hasCount(1);
        screenshot(page, "first-workbench-new.png");
    }

    /** D — 워크벤치, 볼 수 있는 인스턴스가 0개 */
    @Test
    void 워크벤치_인스턴스가_0개일_때() {
        Page page = loginAs("e2e-fs-requester");
        page.locator("#wb-input").waitFor();
        page.waitForTimeout(800);
        assertThat(page.locator("#wb-instance-note")).isVisible();
        assertThat(page.locator("#wb-instance-note")).containsText("볼 수 있는 인스턴스가 없습니다");
        assertThat(page.locator("#wb-instance-note")).containsText("ADMIN에게 등록을 요청하세요");
        // 스키마 자리는 비운다 — 고를 수 없는 인스턴스를 "고르세요"라고 하지 않고, 같은 뜻을 두 곳에 쓰지도 않는다
        assertThat(page.locator("#wb-tree")).hasText("");
        screenshot(page, "first-workbench-none.png");
    }

    /** E — 등록된 인스턴스가 0개. 관리자와 관제 역할의 문구가 달라야 한다(할 수 없는 일을 권하지 않는다) */
    @Test
    void 등록된_인스턴스가_0개일_때() {
        // 관리자에게는 실제 입구를, 관제 역할에게는 요청할 곳을 말한다(할 수 없는 일을 권하지 않는다)
        Page admin = loginAs("e2e-fs-admin");
        admin.locator("#inst-onboarding").waitFor();
        assertThat(admin.locator("#inst-onboarding")).containsText("POST /api/instances");
        // 같은 문장이 화면 두 곳에 겹치지 않는다 — 함대 카드 둘은 접혀 있다
        assertThat(admin.locator("#fleet-row")).isHidden();
        assertThat(admin.locator("#score-result")).isHidden();
        assertThat(admin.locator("#freshness-result")).isHidden();
        // 사이드바는 "검색하거나 필터를 고르세요"라고 하지 않는다(찾을 것이 없다)
        assertThat(admin.locator("#instance-list")).not().containsText("검색하거나 필터");
        assertThat(admin.locator("#inst-count")).hasText("0대");
        // 대화 칸은 같은 말을 세 번 하지 않는다 — 사실은 부제 한 곳이 말하고, 대화 영역·placeholder는 비어 있다(B6fix).
        // 전에는 placeholder가 "왼쪽에서 인스턴스를 고르면"이라 0대일 때 할 수 없는 일을 권했다
        assertThat(admin.locator("#chat-sub")).containsText("인스턴스가 등록되면");
        assertThat(admin.locator("#chat-log")).hasText("");
        assertThat(admin.locator("#diagnose-question")).hasAttribute("placeholder", "");
        // 누를 수 없는 "새 대화"(전환·목록)는 감춘다
        assertThat(admin.locator("#chat-switch")).isHidden();
        assertThat(admin.locator("#chat-new")).isHidden();
        screenshot(admin, "first-console-empty-admin.png");

        Page viewer = loginAs("e2e-fs-viewer");
        viewer.locator("#inst-onboarding").waitFor();
        assertThat(viewer.locator("#inst-onboarding")).containsText("관리자에게 요청하세요");
        assertThat(viewer.locator("#inst-onboarding")).not().containsText("POST /api/instances");
        assertThat(viewer.locator("#chat-sub")).containsText("인스턴스가 등록되면");
        screenshot(viewer, "first-console-empty-viewer.png");
    }

    private Page loginAs(String username) {
        return loginAs(username, "");
    }

    private Page loginAs(String username, String query) {
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
        if (!query.isEmpty()) page.navigate(base() + "/" + query);
        return page;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private void screenshot(Page page, String name) {
        shot(page, name, false);
    }

    private void fullPage(Page page, String name) {
        shot(page, name, true);
    }

    private void shot(Page page, String name, boolean whole) {
        try {
            Path dir = Path.of("build/e2e");
            Files.createDirectories(dir);
            page.screenshot(new Page.ScreenshotOptions().setPath(dir.resolve(name)).setFullPage(whole));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
