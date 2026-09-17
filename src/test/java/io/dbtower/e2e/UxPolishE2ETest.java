package io.dbtower.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import io.dbtower.insight.CollectionStatus;
import io.dbtower.insight.internal.CollectionStatusStore;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.setDefaultAssertionTimeout;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI/UX 점검에서 나온 여섯의 화면 단언 (B9).
 *
 * <p>원칙은 하나다: 사람이 읽는 문장만 보인다. 원문 오류(서버 JSON·드라이버 영문)는 화면에 내보내지 않고
 * 원인 번호만 짧게 남긴다(148절). 문구는 짧고 사실만 쓴다.
 *
 * <p>대상 DB는 필요 없다 — 인스턴스는 닿지 않는 주소(127.0.0.1:1)로 등록한다. 그 실패가 곧 시험 재료다.
 *
 * <p>돌리는 법: {@code ./gradlew playwrightInstall && DBTOWER_E2E=1 ./gradlew test --tests '*UxPolishE2ETest'}
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_E2E", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dbtower.security.api-token=test-api-token")
class UxPolishE2ETest {

    private static final String PASSWORD = "e2e-ux-pass-1234";
    private static final String USER = "e2e-ux-viewer";
    /** 서버가 502와 함께 주는 원인 번호 — 앞 8자만 화면에 보여야 한다 */
    private static final String ERROR_ID = "367f29b0-1f62-44a0-908d-dca3e0833166";

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

    @Autowired
    CollectionStatusStore collectionStatus;

    private final List<BrowserContext> contexts = new ArrayList<>();
    private final List<DatabaseInstance> seeded = new ArrayList<>();

    @BeforeAll
    static void launchBrowser() {
        // 대상이 닿지 않는 주소라 인스턴스를 열 때마다 로더가 실패를 물고 돌아온다 — 단언 시간을 넉넉히(다른 E2E와 같은 이유)
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
                    // 이미 닫힌 페이지 — 다음 테스트는 새 컨텍스트를 쓴다
                }
            }
            context.close();
        }
        contexts.clear();
        seeded.forEach(db -> collectionStatus.evict(db.getId()));
        seeded.forEach(instances::delete);
        seeded.clear();
        users.findByUsername(USER).ifPresent(users::delete);
    }

    private DatabaseInstance instance(String name) {
        seeded.add(instances.save(new DatabaseInstance(name, DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p")));
        return seeded.getLast();
    }

    /**
     * 조회 실패 카드에 서버 응답 JSON이 그대로 보이지 않는다.
     *
     * <p>전에는 공용 {@code api()}가 실패 시 응답 본문을 통째로 Error 메시지로 만들어
     * {@code 쿼리 통계를 불러오지 못했습니다: 502 {"errorId":"…","error":"…"}}가 화면에 떴다(B9).
     * 헬스를 up으로 가로채 down 판정으로 조회를 건너뛰지 않게 하고(그래야 요청이 실제로 나간다),
     * 통계 조회만 502로 응답해 그 자리를 결정적으로 만든다.
     */
    @Test
    void 조회_실패_문구에_서버_JSON이_새지_않고_오류_번호만_남는다() {
        DatabaseInstance db = instance("e2e-ux-err");
        Page page = login();
        page.route("**/api/instances/*/health", route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(200).setContentType("application/json")
                .setBody("{\"up\":true,\"version\":\"8.0.36\",\"pingMillis\":3,\"message\":\"OK\"}")));
        page.route("**/api/instances/*/query-stats*", route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(502).setContentType("application/json")
                .setBody("{\"errorId\":\"" + ERROR_ID + "\",\"error\":\"대상 데이터베이스 조회에 실패했습니다. 서버 로그를 확인하세요.\"}")));

        page.navigate(base() + "/?instance=" + db.getId());
        Locator cell = page.locator("#top-table tbody td").first();
        assertThat(cell).containsText("오류 번호");

        String text = cell.textContent();
        System.out.printf("MEASURE 조회 실패 문구: %s%n", text);
        assertThat(text).doesNotContain("{");
        assertThat(text).doesNotContain("errorId\"");
        assertThat(text).doesNotContain("502");
        assertThat(text).contains(ERROR_ID.substring(0, 8));
        screenshot(page, "ux-query-error.png");
    }

    /**
     * 닿지 않는 대상의 카드는 드라이버 영문 원문 대신 분류된 사유를 말하고, 수집 배지는 설정이 아니라 실제 상태를 말한다.
     *
     * <p>전에는 "응답" 칸에 {@code Failed to obtain JDBC Connection: Connection refused}가 그대로 떴고,
     * down인데도 수집 배지는 초록 "수집중"이라 화면이 거짓말을 했다(B9).
     */
    @Test
    void 닿지_않는_대상_카드에_드라이버_영문이_없고_수집_배지가_멈춤을_말한다() {
        DatabaseInstance db = instance("e2e-ux-down");
        Page page = login();
        page.navigate(base() + "/?instance=" + db.getId());
        page.locator("#health-" + db.getId() + ".down")
                .waitFor(new Locator.WaitForOptions().setTimeout(30_000));

        Locator ping = page.locator("#ping-" + db.getId());
        assertThat(ping).containsText("연결 안 됨");
        String reason = ping.textContent();
        System.out.printf("MEASURE 응답 칸: %s%n", reason);
        assertThat(reason).doesNotContain("Failed to obtain");
        assertThat(reason).doesNotContain("Connection refused");
        assertThat(reason).doesNotContain("JDBC");

        Locator toggle = page.locator(".collect-toggle[data-id='" + db.getId() + "']");
        assertThat(toggle).not().containsText("수집중");
        assertThat(toggle).containsText("수집 멈춤");
        assertThat(toggle.getAttribute("class")).contains("paused");
        screenshot(page, "ux-instance-down.png");
    }

    /**
     * 대상은 응답하는데 수집이 연속 실패하면 카드가 "수집중" 대신 "수집 실패"와 어디서 실패했는지를 말한다(#72).
     *
     * <p>#70에서 live-postgres의 스냅샷 저장이 매번 실패하는 동안 카드는 초록 "수집중"이었다.
     * 헬스는 up으로 가로채고(대상이 살아 있는 경우), 수집 결과는 실제 API가 메타 DB에서 읽게 행을 넣는다.
     * 테스트 컨텍스트의 수집기도 이 닿지 않는 대상에 실패를 더할 수 있어 단계 문구는 둘 중 하나로 본다.</p>
     */
    @Test
    void 대상은_살아_있는데_수집이_연속_실패하면_카드가_수집_실패와_사유를_말한다() {
        DatabaseInstance db = instance("e2e-ux-collect-fail");
        // 운영 앱은 JVM 기본 시간대를 UTC로 고정하고(DbtowerApplication.main) 수집기가 그 시각을 쓴다 — 테스트 JVM은 main을 거치지 않아 UTC로 맞춘다
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        collectionStatus.recordSuccess(db.getId(), now.minusMinutes(20));
        collectionStatus.recordFailure(db.getId(), now.minusMinutes(2), CollectionStatus.STORE);
        collectionStatus.recordFailure(db.getId(), now.minusMinutes(1), CollectionStatus.STORE);
        Page page = login();
        page.route("**/api/instances/*/health", route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(200).setContentType("application/json")
                .setBody("{\"up\":true,\"version\":\"16.15\",\"pingMillis\":2,\"message\":\"OK\"}")));
        page.navigate(base() + "/?instance=" + db.getId());

        Locator toggle = page.locator(".collect-toggle[data-id='" + db.getId() + "']");
        assertThat(toggle).containsText("수집 실패");
        assertThat(toggle.getAttribute("class")).contains("failing");
        Locator note = page.locator(".collect-note[data-id='" + db.getId() + "']");
        assertThat(note).isVisible();
        assertThat(note).containsText("연속");
        assertThat(note).containsText("마지막 성공");
        assertThat(note).hasText(Pattern.compile("(플랫폼 저장 실패|대상 통계 조회 실패) · 연속 \\d+회 · 마지막 성공 .+"));
        System.out.printf("MEASURE 수집 실패 사유: %s (기록한 마지막 성공 UTC %s)%n", note.textContent(), now.minusMinutes(20));
        screenshot(page, "ux-collect-failing.png");

        // 한 번 성공하면 원래대로 — 다시 읽을 때 "수집중"
        collectionStatus.recordSuccess(db.getId(), LocalDateTime.now(ZoneOffset.UTC));
        page.reload();
        assertThat(page.locator(".collect-toggle[data-id='" + db.getId() + "']")).containsText("수집중");
        assertThat(page.locator(".collect-note[data-id='" + db.getId() + "']")).isHidden();
    }

    /** 화면 제목은 한국어다 — 탭·사이드바, 그리고 맨 위 두 카드에 남아 있던 긴 괄호 설명(B9 4·5절) */
    @Test
    void 제목과_탭_글자가_한국어이고_긴_괄호_설명이_없다() {
        DatabaseInstance db = instance("e2e-ux-label");
        Page page = login();
        page.navigate(base() + "/?instance=" + db.getId());
        page.locator("#result-panel").waitFor();

        assertThat(page.locator(".sidebar-title")).hasText("인스턴스");
        assertThat(page.locator(".tab[data-tab=\"top\"]")).hasText("상위 쿼리");
        assertThat(page.locator(".tab[data-tab=\"slow\"]")).hasText("느린 쿼리");
        assertThat(page.locator(".tab[data-tab=\"monitor\"]")).hasText("모니터링");
        // 모니터링 탭은 무엇을 하는 탭인지로 부른다 — "구성"과 "거버넌스"는 이름만으로 차이가 드러나지 않았다(#64)
        assertThat(page.locator(".mon-tab[data-mon=\"config\"]")).hasText("설정 비교");
        assertThat(page.locator(".mon-tab[data-mon=\"gov\"]")).hasText("변경 승인");
        // 선택한 카드의 상세는 접속 사실만 — 기종은 이름 옆 아이콘과 가운데 운영 종합이 말한다
        Locator fieldNames = page.locator(".instance-card.selected .inst-field .k");
        assertThat(fieldNames.filter(new Locator.FilterOptions().setHasText("호스트"))).hasCount(1);
        assertThat(fieldNames.filter(new Locator.FilterOptions().setHasText("기종"))).hasCount(0);
        // 괄호 안에 설명을 몰아넣지 않는다 — 사실 한 문장과 제약만(179절)
        org.assertj.core.api.Assertions.assertThat(page.locator(".health-score-panel .hint").first().textContent())
                .doesNotContain("(");
        org.assertj.core.api.Assertions.assertThat(page.locator(".backup-freshness-panel .hint").first().textContent())
                .doesNotContain("(");
        screenshot(page, "ux-labels.png");
    }

    private Page login() {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setTimezoneId("Asia/Seoul").setViewportSize(1512, 900));
        context.setDefaultTimeout(20_000);
        contexts.add(context);
        Page page = context.newPage();
        page.navigate(base() + "/login.html");
        page.fill("#username", USER);
        page.fill("#password", PASSWORD);
        page.click("button[type=submit]");
        page.waitForURL(Pattern.compile("^(?!.*/login).*$"));
        return page;
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
