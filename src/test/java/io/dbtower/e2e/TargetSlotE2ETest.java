package io.dbtower.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.setDefaultAssertionTimeout;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대상이 응답하지 않을 때 브라우저 연결이 독차지되지 않는지 보는 E2E (B7, 이슈 #31).
 *
 * <p>대상은 127.0.0.1:1(닿지 않는 주소)이라 대상 조회는 연결 제한 시간까지 걸린다. 관제 화면은 인스턴스를 고르면
 * 로더 약 20개를 한꺼번에 보내는데, 평문 HTTP/1.1에서는 브라우저가 호스트당 연결을 6개만 열어 느린 대상 조회가
 * 그 자리를 다 잡으면 대화 목록·리뷰 같은 플랫폼 조회와 사용자가 누른 조작이 서버에 닿지도 못한다.
 *
 * <p>돌리는 법: {@code ./gradlew playwrightInstall && DBTOWER_E2E=1 ./gradlew test --tests '*TargetSlotE2ETest'}
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_E2E", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dbtower.security.api-token=test-api-token")
class TargetSlotE2ETest {

    private static final String PASSWORD = "e2e-slot-pass-1234";
    private static final String USER = "e2e-slot-viewer";

    /**
     * 대상 DB를 만지는 경로만 고른다 — 플랫폼 DB만 읽는 조회(activity·anomalies·plan-changes·reviews·conversations·
     * backup-runs·pitr-window·ai-operations)와 Prometheus 조회(metrics)는 여기 들어오지 않는다(근거는 서버 컨트롤러).
     */
    private static final Pattern TARGET_SUFFIX = Pattern.compile(
            "/(health|overview|query-stats|rows-metric|slow-queries|replication|replication-slots|wait-events|"
                    + "sessions|latency-percentiles|slo|partitions|advisors|finops|deadlocks)$");

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
        // 라우트를 먼저 걷어낸 뒤 컨텍스트를 닫는다 — 늦게 도착한 가로채기 이벤트가 닫힌 페이지에서 터지는 것을 막는다
        for (BrowserContext context : contexts) {
            for (Page page : context.pages()) {
                if (!page.isClosed()) {
                    try {
                        page.unrouteAll();
                    } catch (RuntimeException ignored) {
                        // 이미 닫힌 페이지 — 다음 테스트는 새 컨텍스트를 쓴다
                    }
                }
            }
            context.close();
        }
        contexts.clear();
        seeded.forEach(instances::delete);
        seeded.clear();
        users.findByUsername(USER).ifPresent(users::delete);
    }

    /** 닿지 않는 주소의 인스턴스 — 대상 조회가 연결 제한 시간까지 걸린다 */
    private DatabaseInstance instance(String name) {
        seeded.add(instances.save(new DatabaseInstance(name, DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p")));
        return seeded.getLast();
    }

    /**
     * 인스턴스를 고른 직후 대화 목록이 2초 안에 온다 — 대상 조회가 브라우저 연결을 다 쥐고 있으면
     * 대화 목록은 그 뒤에 줄을 서서 10초쯤 뒤에야 도착한다.
     */
    @Test
    void 대화_목록은_대상_조회에_밀리지_않고_2초_안에_온다() {
        DatabaseInstance db = instance("e2e-slot-a");
        Page page = login();
        Map<Request, Long> startedAt = new ConcurrentHashMap<>();
        List<Long> elapsedMs = new CopyOnWriteArrayList<>();
        page.onRequest(req -> {
            if (req.url().contains("/conversations")) startedAt.put(req, System.nanoTime());
        });
        page.onResponse(res -> {
            Long t0 = startedAt.remove(res.request());
            if (t0 != null) elapsedMs.add((System.nanoTime() - t0) / 1_000_000);
        });

        page.navigate(base() + "/?instance=" + db.getId());
        page.waitForCondition(() -> !elapsedMs.isEmpty());
        long ms = elapsedMs.getFirst();
        System.out.printf("MEASURE 대화 목록 요청->응답 %dms%n", ms);
        assertThat(ms).as("대화 목록이 대상 조회 뒤에 줄을 섰다(%dms)", ms).isLessThan(2_000);
    }

    /** 어느 순간에도 동시에 진행 중인 대상 조회가 2를 넘지 않는다 — 넘으면 그만큼 플랫폼 조회·조작이 밀린다. */
    @Test
    void 동시에_진행_중인_대상_조회는_2를_넘지_않는다() {
        DatabaseInstance db = instance("e2e-slot-b");
        Page page = login();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        page.onRequest(req -> {
            if (isTarget(req.url(), db.getId())) {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            }
        });
        page.onResponse(res -> {
            if (isTarget(res.url(), db.getId())) inFlight.decrementAndGet();
        });
        page.onRequestFailed(req -> {
            if (isTarget(req.url(), db.getId())) inFlight.decrementAndGet();
        });

        page.navigate(base() + "/?instance=" + db.getId());
        page.locator("#result-panel").waitFor();
        page.waitForTimeout(6_000);
        System.out.printf("MEASURE 동시 대상 조회 최대 %d개%n", peak.get());
        assertThat(peak.get()).as("대상 조회가 %d개까지 동시에 나갔다", peak.get()).isLessThanOrEqualTo(2);
    }

    /**
     * 헬스가 down으로 판정된 대상은 다시 고를 때 대상 조회를 보내지 않고, 카드마다 사유 한 줄과
     * "다시 시도"를 남긴다 — 다시 시도는 그 카드만 조회한다.
     */
    @Test
    void down으로_판정된_대상은_조회를_보내지_않고_사유와_다시_시도를_남긴다() {
        DatabaseInstance db = instance("e2e-slot-c");
        Page page = login();
        page.navigate(base() + "/?instance=" + db.getId());
        // 첫 선택은 판정 전이라 큐를 거쳐 조회가 나간다 — 헬스가 down으로 판정될 때까지 기다린다
        page.locator("#health-" + db.getId() + ".down")
                .waitFor(new Locator.WaitForOptions().setTimeout(30_000));

        List<String> targetRequests = new CopyOnWriteArrayList<>();
        page.onRequest(req -> {
            if (isTarget(req.url(), db.getId())) targetRequests.add(URI.create(req.url()).getPath());
        });
        // 같은 인스턴스를 다시 고른다 — 이번엔 down 판정이 있으므로 대상 조회가 나가지 않아야 한다
        page.locator("#instance-list .instance-card[data-id='" + db.getId() + "']").click();
        page.waitForTimeout(1_500);
        System.out.printf("MEASURE down 재선택 뒤 대상 조회 %d건 %s%n", targetRequests.size(), targetRequests);
        assertThat(targetRequests).as("down으로 판정된 대상에 조회를 보냈다").isEmpty();

        // 카드마다 사유 한 줄 + 다시 시도
        assertThat(page.locator("#slow-table")).containsText("대상에 연결되지 않아 조회하지 않았습니다.");
        assertThat(page.locator("#top-table")).containsText("대상에 연결되지 않아 조회하지 않았습니다.");
        // 탭 줄 위에 떠 있던 공용 안내 상자(운영 종합 카드)는 없앴다(B9) — 사유는 각 결과 영역이 자기 자리에서 말한다
        assertThat(page.locator("#overview-card")).isHidden();
        // 느린 쿼리 카드는 Monitoring 탭 안이라 DOM에는 있지만 화면에는 안 보인다 — 사유·버튼은 그대로 있다
        assertThat(page.locator("#slow-table [data-target-retry]")).hasCount(1);
        // 지금 보이는 카드(Top Query)의 다시 시도는 실제로 누를 수 있는 자리에 있다
        assertThat(page.locator("#top-table [data-target-retry]")).isVisible();
        screenshot(page, "target-slot-skipped.png");

        // 다시 시도는 그 카드만 조회한다 — 느린 쿼리 요청이 정확히 한 번 나간다
        // (느린 쿼리 카드는 숨은 탭에 있으므로 클릭 이벤트를 직접 보낸다 — 사람이 탭을 열고 누르는 것과 같은 핸들러)
        List<String> retried = new CopyOnWriteArrayList<>();
        page.onRequest(req -> {
            if (URI.create(req.url()).getPath().endsWith("/slow-queries")) retried.add(req.url());
        });
        page.locator("#slow-table [data-target-retry]").dispatchEvent("click");
        page.waitForCondition(() -> !retried.isEmpty());
        page.waitForTimeout(400);
        assertThat(retried).as("다시 시도가 그 카드만 조회하지 않았다").hasSize(1);
    }

    /**
     * 인스턴스를 빠르게 A에서 B로 바꾸면 A의 대기 중(아직 안 보낸) 대상 조회는 나가지 않는다 —
     * 새 대상의 화면을 옛 대상의 응답으로 채우지 않는다.
     */
    @Test
    void 인스턴스를_빠르게_바꾸면_이전_대상의_대기_조회는_나가지_않는다() {
        DatabaseInstance a = instance("e2e-slot-d");
        DatabaseInstance b = instance("e2e-slot-e");
        Page page = login();
        List<String> aTargets = new CopyOnWriteArrayList<>();
        page.onRequest(req -> {
            String path = URI.create(req.url()).getPath();
            if (isTarget(req.url(), a.getId())) aTargets.add(path);
        });

        // 필터로 두 카드를 함께 띄운다(대상 조회는 아직 안 나감) — 그리고 A를 고른다
        page.navigate(base() + "/");
        page.fill("#inst-search", "e2e-slot");
        page.locator("#instance-list .instance-card[data-id='" + a.getId() + "']").click();
        page.locator("#instance-list .instance-card[data-id='" + b.getId() + "']").click();

        // 전환 직후의 수를 기억하고, 그 뒤로 늘지 않는지 본다. 늘었다면 A의 대기분이 뒤늦게 나간 것이다
        int atSwitch = aTargets.size();
        page.waitForTimeout(1_200);
        System.out.printf("MEASURE A 대상 조회 %d건 (전환 시점 %d) %s%n", aTargets.size(), atSwitch, aTargets);
        assertThat(atSwitch).as("동시 2개 제한 전에 %d건이 나갔다", atSwitch).isLessThanOrEqualTo(2);
        assertThat(aTargets).as("대상을 바꿨는데 A의 대기 중 조회가 나갔다").hasSize(atSwitch);
    }

    private static boolean isTarget(String url, long instanceId) {
        String path = URI.create(url).getPath();
        return path.startsWith("/api/instances/" + instanceId + "/") && TARGET_SUFFIX.matcher(path).find();
    }

    /** 로그인까지만 — 대상 선택은 각 테스트가 자기 방식으로 한다 */
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
