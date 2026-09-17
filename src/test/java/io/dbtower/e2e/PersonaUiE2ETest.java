package io.dbtower.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.BoundingBox;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DatabaseInstanceRepository;
import io.dbtower.registry.DbmsType;
import io.dbtower.review.internal.domain.ReviewRequest;
import io.dbtower.review.internal.persistence.ReviewRequestRepository;
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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역할별 화면 E2E — 실제 서버(랜덤 포트)에 실제 브라우저로 역할마다 로그인해 버튼·메뉴·안내를 본다.
 *
 * <p>136절에서 결함 둘(관제 역할의 워크벤치 안내가 셸 격자 첫 칸에 끼임, 리뷰 카드 승인 시각이 UTC 원문)은 MockMvc·단위 테스트가 아니라
 * 사람이 화면을 눌러서야 나왔다. 같은 종류의 회귀를 CI가 잡게 하려는 테스트다. 브라우저가 필요해 DBTOWER_E2E=1일 때만 돈다
 * (로컬: {@code ./gradlew playwrightInstall && DBTOWER_E2E=1 ./gradlew test --tests '*PersonaUiE2ETest'}).
 *
 * <p>대상 DB는 필요 없다 — 인스턴스는 닿지 않는 주소로 등록하고 티켓은 저장소에 직접 넣는다. 화면은 헬스·스키마 조회 실패를 정직 표기할 뿐
 * 역할 분기는 그대로 그린다. 리뷰 제출 API를 쓰지 않는 이유는 AI 소견 호출이 로컬 환경(claude CLI 유무)에 따라 달라지기 때문이다.
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_E2E", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dbtower.security.api-token=test-api-token")
class PersonaUiE2ETest {

    private static final String PASSWORD = "persona-e2e-pass-1234";
    private static final ZoneId BROWSER_ZONE = ZoneId.of("Asia/Seoul");
    private static final Map<String, PlatformUser.Role> PERSONAS = Map.of(
            "e2e-viewer", PlatformUser.Role.VIEWER,
            "e2e-requester", PlatformUser.Role.REQUESTER,
            "e2e-approver", PlatformUser.Role.APPROVER,
            "e2e-operator", PlatformUser.Role.OPERATOR,
            "e2e-admin", PlatformUser.Role.ADMIN);

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
    ReviewRequestRepository reviews;

    private final List<BrowserContext> contexts = new ArrayList<>();
    private DatabaseInstance instance;
    private ReviewRequest pending;
    private ReviewRequest approved;

    @BeforeAll
    static void launchBrowser() {
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
        // 포트 1은 연결 거부가 즉시 돌아온다 — 대상 없이 화면 분기만 본다
        instance = instances.save(new DatabaseInstance("e2e-mysql", DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p"));
        pending = reviews.save(new ReviewRequest(instance.getId(), "UPDATE customers SET grade = 'GOLD' WHERE id = 4",
                "e2e 대기 티켓", "e2e-requester", "", null, 2, false, null));
        ReviewRequest toApprove = new ReviewRequest(instance.getId(), "UPDATE customers SET grade = 'VIP' WHERE id = 3",
                "e2e 승인된 티켓", "e2e-requester", "", null, 2, false, null);
        toApprove.decide(ReviewRequest.Status.APPROVED, "e2e-approver", "e2e 승인");
        approved = reviews.save(toApprove);
    }

    @AfterEach
    void cleanup() {
        contexts.forEach(BrowserContext::close);
        reviews.deleteAll(List.of(pending, approved));
        instances.delete(instance);
        PERSONAS.keySet().forEach(name -> users.findByUsername(name).ifPresent(users::delete));
    }

    @Test
    void 요청자는_워크벤치에_관제는_대시보드에_도착한다() {
        assertThat(loginAs("e2e-requester").url()).endsWith("/?mode=workbench");
        assertThat(loginAs("e2e-viewer").url()).endsWith("/");
    }

    @Test
    void 관제에게는_워크벤치_입구와_변경_요청_입력이_없고_직접_들어오면_안내가_화면_폭으로_뜬다() {
        Page page = loginAs("e2e-viewer");
        page.navigate(base() + "/?instance=" + instance.getId() + "&view=review");
        page.locator("#review-list .rv-item").first().waitFor();
        assertThat(page.locator("#nav-workbench")).isHidden();
        assertThat(page.locator("#review-submit")).isHidden();
        assertThat(page.locator("#review-list a[href*='mode=workbench']")).hasCount(0);

        page.navigate(base() + "/workbench.html");
        Locator guard = page.getByText("요청자(REQUESTER) 이상 역할이 필요합니다", new Page.GetByTextOptions().setExact(false));
        guard.waitFor();
        BoundingBox box = guard.boundingBox();
        // 수정 전에는 셸 격자의 첫 칸(인스턴스 목록 폭 약 170px)에 끼어 세로로 늘어졌다(136절 결함 1)
        assertThat(box.width).as("안내 폭").isGreaterThan(400);
    }

    @Test
    void 같은_티켓에서_승인자는_실행하지_못하고_운영자는_승인하지_못한다() {
        Page approver = loginAs("e2e-approver");
        assertThat(ticketActions(approver, pending)).contains("dry-run", "approve", "reject").doesNotContain("execute");
        assertThat(ticketActions(approver, approved)).contains("dry-run").doesNotContain("execute", "approve");
        assertThat(approver.locator("#wb-ticket")).containsText("실행은 운영자(OPERATOR)가 합니다");

        Page operator = loginAs("e2e-operator");
        assertThat(ticketActions(operator, pending)).contains("dry-run").doesNotContain("approve", "reject");
        assertThat(ticketActions(operator, approved)).contains("dry-run", "execute").doesNotContain("approve");

        Page requester = loginAs("e2e-requester");
        assertThat(ticketActions(requester, pending)).containsExactly("cancel");
    }

    @Test
    void 워크벤치는_스키마와_채팅을_함께_보이고_티켓은_가운데_결과_탭에서_연다() {
        // 147절 전에는 오른쪽 탭 하나에 채팅·스키마·변경 티켓이 있어 서로를 가렸다 — 테이블을 채팅에 붙이려면 탭을 오갔다
        Page page = loginAs("e2e-requester");
        page.navigate(base() + "/workbench.html?instance=" + instance.getId());
        page.locator("#wb-tree").waitFor();
        assertThat(page.locator("#wb-tree")).isVisible();
        assertThat(page.locator("#wb-ask")).isVisible();
        assertThat(page.locator(".wb-ctab")).hasCount(0);

        page.navigate(base() + "/workbench.html?instance=" + instance.getId() + "&ticket=" + pending.getId());
        page.locator("#wb-ticket .tk-head").waitFor();
        assertThat(page.locator("#wb-pane-tickets")).isVisible();
        assertThat(page.locator(".wb-rtab[data-pane='tickets']")).hasClass(Pattern.compile("active"));
        assertThat(page.locator("#wb-ask")).isVisible();
        assertThat(page.locator("#wb-tree")).isVisible();
    }

    @Test
    void 리뷰_카드의_승인_시각은_브라우저_시간대로_보이고_UTC_원문은_툴팁에_남는다() {
        Page page = loginAs("e2e-viewer");
        page.navigate(base() + "/?instance=" + instance.getId() + "&view=review");
        // 수정 전에는 서버의 오프셋 없는 UTC 원문을 잘라 찍어 워크벤치와 9시간 어긋났다(136절 결함 2)
        Locator time = page.locator("#review-list .rv-decided span[title]").first();
        time.waitFor();
        String raw = time.getAttribute("title").replace("UTC 원문: ", "").trim();
        String expected = LocalDateTime.parse(raw).atOffset(ZoneOffset.UTC).atZoneSameInstant(BROWSER_ZONE)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        assertThat(time.textContent()).isEqualTo(expected);
    }

    @Test
    void 실시간을_켜면_실제_서버의_스트림이_붙고_안_보이는_그룹으로_가면_연결을_닫는다() {
        Page page = loginAs("e2e-viewer");
        page.navigate(base() + "/?instance=" + instance.getId());
        page.locator("#result-panel").waitFor();
        page.locator(".tab[data-tab=\"monitor\"]").click();
        Locator toggle = page.locator("#live-toggle");
        Locator status = page.locator("#live-status");

        toggle.click();
        assertThat(toggle).hasAttribute("aria-pressed", "true");
        // 대상은 포트 1이라 조회는 실패한다. 실패도 프레임으로 와야 화면이 "세션 0건"과 "못 쟀다"를 구분한다(140절).
        // MockMvc가 못 보는 경로 — 실제 톰캣의 비동기 디스패치와 보안 필터를 지나 EventSource까지 온 프레임이다
        assertThat(status).containsText("조회 실패", new LocatorAssertions.ContainsTextOptions().setTimeout(20_000));

        page.locator(".mon-tab[data-mon=\"diag\"]").click();
        assertThat(status).containsText("일시정지");

        page.locator(".mon-tab[data-mon=\"perf\"]").click();
        // 상태 줄은 한국어다(B9) — "실시간 · …"
        assertThat(status).containsText("실시간", new LocatorAssertions.ContainsTextOptions().setTimeout(20_000));
    }

    @Test
    void 사용자_역할_카드는_관리자에게만_있다() {
        Page admin = loginAs("e2e-admin");
        admin.locator("#user-chip .role-badge").waitFor();
        assertThat(admin.locator("#users-card")).not().hasAttribute("hidden", "");
        assertThat(admin.locator("#users-table tbody")).containsText("e2e-operator");

        Page operator = loginAs("e2e-operator");
        operator.locator("#user-chip .role-badge").waitFor();
        assertThat(operator.locator("#users-card")).hasAttribute("hidden", "");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 유한하지_않거나_음수인_표시값은_깨진_숫자_대신_미확보로_보인다() {
        Page page = loginAs("e2e-viewer");
        page.locator("#user-chip .role-badge").waitFor();
        List<String> rendered = (List<String>) page.evaluate("""
                async () => {
                  const { bytes } = await import('/workbench/table-detail.js');
                  const { renderWorkload } = await import('/workbench/diff.js');
                  const holder = document.createElement('div');
                  holder.innerHTML = renderWorkload({
                    baseFrom: '2026-09-01T00:00:00', baseTo: '2026-09-01T01:00:00',
                    targetFrom: '2026-09-02T00:00:00', targetTo: '2026-09-02T01:00:00',
                    result: {
                      base: {avgLatencyMs: NaN, totalCalls: Infinity, totalRowsExamined: 1},
                      target: {avgLatencyMs: Infinity, totalCalls: 2, totalRowsExamined: 3},
                      avgLatencyChangePct: Infinity, newQueryCount: 0, queries: []
                    }
                  });
                  return [fmtNum(NaN), fmtNum(Infinity), fmtBytes(-1), fmtBytes(Infinity),
                    bytes(-1), bytes(Infinity), holder.textContent];
                }
                """);
        assertThat(rendered.subList(0, 4)).containsExactly("-", "-", "-", "-");
        assertThat(rendered.subList(4, 6)).containsExactly("미확보", "미확보");
        assertThat(rendered.get(6)).doesNotContain("NaN", "Infinity", "∞");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 쿼리_표는_SQL을_강조하되_태그를_실행하지_않는다() {
        Page page = loginAs("e2e-viewer");
        page.locator("#user-chip .role-badge").waitFor();
        List<Object> rendered = (List<Object>) page.evaluate("""
                () => {
                  const holder = document.createElement('td');
                  holder.className = 'qtext';
                  holder.innerHTML = queryTextHtml(
                    "SELECT COUNT(`id`), 'ok', 42 FROM `orders` WHERE note = '<img src=x onerror=alert(1)>'"
                  );
                  document.body.append(holder);
                  const color = (selector) => getComputedStyle(holder.querySelector(selector)).color;
                  return [
                    holder.querySelectorAll('.t-kw').length,
                    holder.querySelectorAll('.t-fn').length,
                    holder.querySelectorAll('.t-id').length,
                    holder.querySelectorAll('.t-str').length,
                    holder.querySelectorAll('.t-num').length,
                    holder.querySelector('img') === null,
                    color('.t-kw'),
                    color('.t-id'),
                    holder.textContent
                  ];
                }
                """);
        assertThat(rendered.subList(0, 6)).containsExactly(3, 1, 2, 2, 1, true);
        // 색은 175절 하늘 원톤 기준(B3에서 남아 있던 보라를 걷어냈다) — 키워드 #075985 7.56:1, 식별자 #334155 10.36:1
        assertThat(rendered.subList(6, 8)).containsExactly("rgb(7, 89, 133)", "rgb(51, 65, 85)");
        assertThat(rendered.get(8)).isEqualTo(
                "SELECT COUNT(`id`), 'ok', 42 FROM `orders` WHERE note = '<img src=x onerror=alert(1)>'");
    }

    @Test
    void 구조_정의는_좁은_화면에_맞고_미확보_경고와_이스케이프를_지킨다() {
        Page page = loginAs("e2e-requester");
        page.setContent("<link rel='stylesheet' href='/style.css'><link rel='stylesheet' href='/workbench.css'>"
                + "<main id='schema-fixture' style='padding:16px;min-width:0'></main>");
        page.evaluate("""
                async () => {
                  const { renderSchemaDiff } = await import('/workbench/diff.js');
                  const definition = {name: 'ck_amount', definition: "amount > 0 AND note <> '<img src=x onerror=alert(1)>'", state: 'validated=true'};
                  const trigger = {name: 'tr_orders_audit', definition: 'CREATE TRIGGER tr_orders_audit BEFORE UPDATE ON orders EXECUTE FUNCTION '
                    + 'long_identifier_'.repeat(25) + '()', state: 'O'};
                  const changed = {identical:false, complete:true, changedTables:[{table:'orders',
                    checks:{added:[definition]}, triggers:{changed:[{name:trigger.name, left:trigger, right:{...trigger, state:'D'}}]}}]};
                  const partial = {identical:true, complete:false, warning:'트리거 미확보: TRIGGER 권한 확인 필요'};
                  document.querySelector('#schema-fixture').innerHTML = renderSchemaDiff(changed) + renderSchemaDiff(partial);
                }
                """);
        assertThat(page.locator("#schema-fixture").textContent()).contains("CHECK ck_amount", "트리거 tr_orders_audit", "미확보", "TRIGGER 권한");
        assertThat(page.locator("#schema-fixture img")).hasCount(0);
        for (int width : List.of(390, 1512)) {
            page.setViewportSize(width, 900);
            assertThat((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth"))
                    .as("구조 비교 가로 넘침, viewport=" + width).isTrue();
            page.screenshot(new Page.ScreenshotOptions().setFullPage(true)
                    .setPath(Path.of("build/reports/schema-diff-156-" + width + ".png")));
        }
    }

    private Page loginAs(String username) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setTimezoneId(BROWSER_ZONE.getId()).setViewportSize(1512, 900));
        context.setDefaultTimeout(20_000);
        contexts.add(context);
        Page page = context.newPage();
        page.navigate(base() + "/login.html");
        page.fill("#username", username);
        page.fill("#password", PASSWORD);
        page.click("button[type=submit]");
        page.waitForURL(Pattern.compile("^(?!.*/login).*$"));
        return page;
    }

    @SuppressWarnings("unchecked")
    private List<String> ticketActions(Page page, ReviewRequest ticket) {
        page.navigate(base() + "/workbench.html?instance=" + instance.getId() + "&ticket=" + ticket.getId());
        Locator buttons = page.locator("#wb-ticket .tk-actions button[data-act]");
        buttons.first().waitFor();
        return ((List<String>) page.locator("#wb-ticket .tk-actions button[data-act]")
                .evaluateAll("els => els.map((e) => e.dataset.act)")).stream()
                .filter(act -> !"to-editor".equals(act)).toList();
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
