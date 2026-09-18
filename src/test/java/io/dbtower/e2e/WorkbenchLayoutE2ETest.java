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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.setDefaultAssertionTimeout;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 워크벤치 배치 E2E (B5) — 인스턴스 고르기·워크시트 탭·버튼을 실제 브라우저에서 본다.
 *
 * <p>대상 DB가 필요 없다. 스키마·워크시트 응답은 {@code page.route}로 대신 채운다 — 여기서 확인하는 것은
 * 데이터가 아니라 <b>무엇이 보이고, 실패가 드러나고, 눌렀을 때 요청이 나가는가</b>다.
 *
 * <p>돌리는 법: {@code ./gradlew playwrightInstall && DBTOWER_E2E=1 ./gradlew test --tests '*WorkbenchLayoutE2ETest'}
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_E2E", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkbenchLayoutE2ETest {

    private static final String PASSWORD = "e2e-wb-pass-1234";
    private static final String USER = "e2e-wb-requester";
    private static final Pattern INSTANCE_IN_PATH = Pattern.compile("/instances/(\\d+)/");

    private record StubSheet(long id, String title, Integer latestVersion) {
    }

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
    /** 인스턴스별 워크시트 — 생성·보관·이름 변경이 이 목록에 반영된다(요청 가로채기의 상태) */
    private final Map<Long, List<StubSheet>> sheets = new ConcurrentHashMap<>();
    private final AtomicLong nextSheetId = new AtomicLong(900);
    private final List<String> sheetDeletes = new CopyOnWriteArrayList<>();
    private final List<String> sheetPatches = new CopyOnWriteArrayList<>();

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
        sheetDeletes.clear();
        sheetPatches.clear();
        sheets.clear();
        users.findByUsername(USER).orElseGet(() -> users.save(
                new PlatformUser(USER, encoder.encode(PASSWORD), PlatformUser.Role.REQUESTER)));
    }

    @AfterEach
    void cleanup() {
        // 라우트를 먼저 걷어낸 뒤 컨텍스트를 닫는다.
        //
        // 왜: 컨텍스트를 그냥 닫으면, 아직 처리되지 않은 가로챈 요청의 route 이벤트가 그 뒤에 도착한다.
        // Playwright는 그 이벤트를 처리하면서 PageImpl.updateInterceptionPatterns()를 부르는데, 그때 페이지는 이미 닫혀
        // TargetClosedError가 난다. 그 예외는 디스패처 스레드에서 터져 **다음** 테스트가 기다리던 호출 위로 튄다 —
        // "간헐적으로 다른 테스트가 TargetClosedError로 죽는" 증상의 정체다(스택이 updateInterceptionPatterns를 가리킨다).
        // unrouteAll()은 (기본 동작으로) 진행 중인 핸들러를 기다린 뒤 라우트를 지우므로, 닫힌 페이지로 늦게 오는 이벤트가 없다.
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

    private static List<StubSheet> mutableListOf(StubSheet... items) {
        return new CopyOnWriteArrayList<>(List.of(items));
    }

    private DatabaseInstance instance(String name) {
        seeded.add(instances.save(new DatabaseInstance(name, DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p")));
        return seeded.getLast();
    }

    /** 인스턴스 목록이 실패하면 그 사유가 인스턴스 자리에 보인다 — 워크시트 자리에 쓰던 것을 옮겼다(B5). */
    @Test
    void 인스턴스_목록이_실패하면_인스턴스_자리에_사유가_보인다() {
        DatabaseInstance a = instance("e2e-wb-g");
        sheets.put(a.getId(), mutableListOf(new StubSheet(1, "시트-G", 1)));
        Page page = openWorkbench(a, p -> p.route("**/api/workbench/instances", route ->
                route.fulfill(new Route.FulfillOptions().setStatus(500).setContentType("application/json")
                        .setBody("{\"error\":\"목록 조회 실패\"}"))));

        assertThat(page.locator("#wb-instance-note")).isVisible();
        assertThat(page.locator("#wb-instance-note")).containsText("인스턴스 목록을 불러오지 못했습니다");
        assertThat(page.locator("#wb-instance-note")).containsText("목록 조회 실패");
        // 사유가 워크시트 자리로 새지 않는다
        assertThat(page.locator("#wb-sheets .wb-msg")).hasCount(0);
        screenshot(page, "workbench-instance-error.png");
    }

    /** 인스턴스를 바꾸면 스키마 영역과 워크시트 탭이 그 인스턴스 것으로 함께 바뀐다 — 요청자 지적의 핵심. */
    @Test
    void 인스턴스를_바꾸면_스키마와_워크시트_탭이_그_인스턴스_것으로_바뀐다() {
        DatabaseInstance a = instance("e2e-wb-a");
        DatabaseInstance b = instance("e2e-wb-b");
        sheets.put(a.getId(), mutableListOf(new StubSheet(1, "시트-A", 3)));
        sheets.put(b.getId(), mutableListOf(new StubSheet(2, "시트-B", null)));
        Page page = openWorkbench(a, null);

        assertThat(page.locator("#wb-tree .tree-root-name")).hasText("db-" + a.getId());
        assertThat(page.locator("#wb-sheets .wb-sheet-tab")).containsText("시트-A");
        // 트리 사용법은 늘 떠 있는 줄이 아니라 정보 아이콘 툴팁이다 — 초점으로도 뜨고 Esc로 닫힌다(B5)
        assertThat(page.locator(".wb-tree-hint")).hasCount(0);
        assertThat(page.locator("#wb-tree-tip")).isHidden();
        page.locator("#wb-tree-info").focus();
        assertThat(page.locator("#wb-tree-tip")).isVisible();
        page.keyboard().press("Escape");
        assertThat(page.locator("#wb-tree-tip")).isHidden();
        screenshot(page, "workbench-full.png");

        // 관제와 같은 드롭다운(네이티브 select를 감싼 .cs)으로 바꾼다
        page.locator(".wb-left .cs-btn").click();
        page.locator(".cs-panel-floating .cs-opt").filter(new Locator.FilterOptions().setHasText(b.getName())).click();

        assertThat(page.locator("#wb-tree .tree-root-name")).hasText("db-" + b.getId());
        assertThat(page.locator("#wb-sheets .wb-sheet-tab")).containsText("시트-B");
        screenshot(page, "workbench-instance-switched.png");
    }

    /** 워크시트 목록이 실패하면 탭 줄에 사유가 보인다 — 전에는 편집기가 이전 상태 그대로 멈춰 있었다. */
    @Test
    void 워크시트_목록이_실패하면_탭_줄에_사유가_보인다() {
        DatabaseInstance a = instance("e2e-wb-c");
        sheets.put(a.getId(), mutableListOf(new StubSheet(1, "시트-C", 1)));
        Page page = openWorkbench(a, p -> p.route("**/api/workbench/instances/*/worksheets", route -> {
            if ("GET".equals(route.request().method())) {
                route.fulfill(new Route.FulfillOptions().setStatus(500).setContentType("application/json")
                        .setBody("{\"error\":\"서버 오류\"}"));
            } else {
                fulfillSheets(route);
            }
        }));

        assertThat(page.locator("#wb-sheets .wb-msg")).containsText("워크시트를 불러오지 못했습니다");
        assertThat(page.locator("#wb-sheets .wb-msg")).containsText("서버 오류");
        screenshot(page, "workbench-worksheet-error.png");
    }

    /** 스키마 요청 실패는 트리 자리에 사유와 함께 남는다 — 전에는 "불러오지 못했습니다"만 말했다. */
    @Test
    void 스키마_요청이_실패하면_트리에_사유가_남는다() {
        DatabaseInstance a = instance("e2e-wb-d");
        sheets.put(a.getId(), mutableListOf(new StubSheet(1, "시트-D", 1)));
        Page page = openWorkbench(a, p -> p.route("**/api/instances/*/schema", route -> {
            route.fulfill(new Route.FulfillOptions().setStatus(500).setContentType("application/json")
                    .setBody("{\"error\":\"스키마 조회 실패\"}"));
        }));

        assertThat(page.locator("#wb-tree .wb-msg.error")).containsText("스키마를 불러오지 못했습니다");
        assertThat(page.locator("#wb-tree .wb-msg.error")).containsText("스키마 조회 실패");
        screenshot(page, "workbench-schema-error.png");
    }

    /** 탭 +는 새 워크시트, ×는 그 자리에서 한 번 더 묻고 나서야 보관한다(되돌릴 수 없는 요청). */
    @Test
    void 탭_더하기와_보관_인라인_확인() {
        DatabaseInstance a = instance("e2e-wb-e");
        sheets.put(a.getId(), mutableListOf(new StubSheet(1, "시트-E", 1)));
        Page page = openWorkbench(a, null);
        assertThat(page.locator("#wb-sheets .wb-sheet-tab")).hasCount(1);

        page.click("#wb-sheet-add");
        assertThat(page.locator("#wb-sheets .wb-sheet-tab")).hasCount(2);

        // × → 확인. 여기서는 DELETE가 나가지 않는다
        Locator tab = page.locator("#wb-sheets .wb-sheet-tab").first();
        tab.hover();
        tab.locator(".wb-tab-x").click();
        assertThat(page.locator("#wb-sheets .wb-tab-confirm")).containsText("보관할까요?");
        page.waitForTimeout(600);
        assertThat(sheetDeletes).as("확인 전에 DELETE가 나갔다").isEmpty();
        screenshot(page, "workbench-archive-confirm.png");

        page.locator("#wb-sheets .wb-tab-no").click();
        assertThat(page.locator("#wb-sheets .wb-tab-confirm")).hasCount(0);
        assertThat(sheetDeletes).isEmpty();

        // 다시 × → 보관을 눌러야 나간다
        Locator again = page.locator("#wb-sheets .wb-sheet-tab").first();
        again.hover();
        again.locator(".wb-tab-x").click();
        page.locator("#wb-sheets .wb-tab-yes").click();
        page.waitForCondition(() -> !sheetDeletes.isEmpty());
        assertThat(sheetDeletes).hasSize(1);
        // 보관 뒤 화면이 실제로 정리됐는지까지 본다 — 보관된 탭이 사라지고 남은 탭 하나만 보인다.
        // (요청이 아직 날아다니는 채로 테스트를 끝내면 정리 단계가 그 요청과 겹친다)
        assertThat(page.locator("#wb-sheets .wb-sheet-tab")).hasCount(1);
        assertThat(page.locator("#wb-sheets .wb-sheet-tab")).containsText("새 워크시트");
    }

    /** 활성 탭 더블클릭 → 그 자리에서 이름 바꾸기(#wb-title이 탭 안으로 들어온다). */
    @Test
    void 활성_탭_더블클릭으로_이름을_바꾼다() {
        DatabaseInstance a = instance("e2e-wb-f");
        sheets.put(a.getId(), mutableListOf(new StubSheet(1, "옛 이름", 2)));
        Page page = openWorkbench(a, null);

        assertThat(page.locator("#wb-title")).isHidden();
        page.locator("#wb-sheets .wb-sheet-tab.active").dblclick();
        assertThat(page.locator("#wb-sheets .wb-sheet-tab.active #wb-title")).isVisible();
        screenshot(page, "workbench-rename.png");

        page.fill("#wb-title", "새 이름");
        page.press("#wb-title", "Enter");

        assertThat(page.locator("#wb-sheets .wb-sheet-tab.active")).containsText("새 이름");
        assertThat(sheetPatches).hasSize(1);
    }

    /**
     * 대화 칸에는 대화만 — 직접 실행·되돌림 버전 카드는 탭의 "최신 vN"이 여는 버전 기록에 모인다(#63).
     * 전에는 실행할 때마다 "v1 · 직접 실행한 SQL" 카드가 대화 칸에 쌓여 대화가 기록 목록처럼 밀렸다.
     */
    @Test
    void 직접_실행_버전은_대화_칸이_아니라_버전_기록에_모인다() {
        DatabaseInstance a = instance("e2e-wb-v");
        sheets.put(a.getId(), mutableListOf(new StubSheet(1, "버전 시트", 3)));
        String version = "{\"versionNo\":%d,\"title\":%s,\"source\":\"%s\",\"principal\":\"e2e\",\"createdAt\":\"2026-09-17T10:0%d:00\",\"sql\":\"SELECT %d\",\"restoredFrom\":null}";
        String timeline = "[" + "{\"type\":\"VERSION\",\"version\":" + version.formatted(1, "null", "RUN", 1, 1) + "},"
                + "{\"type\":\"MESSAGE\",\"message\":{\"role\":\"USER\",\"content\":\"주문 상태별 건수\",\"valuesShared\":false}},"
                + "{\"type\":\"MESSAGE\",\"message\":{\"role\":\"ASSISTANT\",\"content\":\"상태별로 묶었습니다.\",\"sql\":\"SELECT status, count(*) FROM orders GROUP BY status\","
                + "\"tier\":\"READ\",\"version\":" + version.formatted(2, "\"AI 제안\"", "AI", 2, 2) + "}},"
                + "{\"type\":\"VERSION\",\"version\":" + version.formatted(3, "null", "RUN", 3, 3) + "}]";
        Page page = openWorkbench(a, p -> p.route("**/api/workbench/worksheets/*/timeline", route -> fulfillJson(route, timeline)));

        Locator chat = page.locator("#wb-timeline");
        assertThat(chat).containsText("상태별로 묶었습니다.");
        assertThat(chat.locator(".checkpoint")).hasCount(1);            // AI 답에 붙은 제안 버전만
        assertThat(chat.locator(".checkpoint")).containsText("v2");
        assertThat(chat).not().containsText("직접 실행");

        Locator badge = page.locator("#wb-sheets .wb-sheet-tab.active #wb-version");
        assertThat(badge).hasText("최신 v3");
        badge.click();
        Locator panel = page.locator("#wb-version-panel");
        assertThat(panel).isVisible();
        assertThat(badge).hasAttribute("aria-expanded", "true");
        assertThat(panel.locator(".checkpoint")).hasCount(3);
        assertThat(panel.locator(".checkpoint .cp-title").first()).hasText("v3 · 직접 실행");
        screenshot(page, "workbench-version-panel.png");

        page.keyboard().press("Escape");
        assertThat(panel).isHidden();
    }

    /**
     * 결과표는 원시 값을 그대로 보이지 않는다(#62) — 부동소수 오차는 넷째 자리까지(원래 값은 title),
     * 숫자는 오른쪽 정렬, PostgreSQL의 "<insufficient privilege>"는 권한 안내로.
     */
    @Test
    void 결과표는_긴_소수와_권한_없음_원문을_다듬어_보인다() {
        DatabaseInstance a = instance("e2e-wb-g");
        sheets.put(a.getId(), mutableListOf(new StubSheet(1, "결과 시트", null)));
        String result = """
                {"result":{"columns":[{"name":"query","typeName":"text"},{"name":"total_plan_time","typeName":"float8"},{"name":"calls","typeName":"int8"},{"name":"note","typeName":"text"}],
                 "rows":[["<insufficient privilege>",0.19754200000000002,12345,null]],
                 "rowCount":1,"truncated":false,"elapsedMs":3,"maskedColumns":[]},"version":null}""";
        Page page = openWorkbench(a, p -> p.route("**/api/workbench/instances/*/query", route -> fulfillJson(route, result)));
        // 워크시트가 열리기 전에 실행을 누르면 실행이 조용히 나가지 않는다(run은 열린 워크시트가 있어야 보낸다) —
        // 느린 CI에서 이 순서가 뒤집혀 결과표가 비었다. 활성 탭을 기다린 뒤 입력하고, 결과 행을 기다린다
        page.locator("#wb-sheets .wb-sheet-tab.active").waitFor();
        page.locator("#wb-input").click();
        page.keyboard().type("SELECT 1");
        page.locator("#wb-run").click();
        page.locator("#wb-grid table.grid tbody tr").first().waitFor();

        Locator cells = page.locator("#wb-grid table.grid tbody tr").first().locator("td");
        assertThat(cells.nth(1)).hasText("권한 없음");
        assertThat(cells.nth(1)).hasAttribute("title", Pattern.compile("pg_read_all_stats"));
        assertThat(cells.nth(2)).hasText("0.1975");
        assertThat(cells.nth(2)).hasAttribute("title", "0.19754200000000002");
        assertThat(cells.nth(2)).hasClass(Pattern.compile("num"));
        assertThat(cells.nth(3)).hasText("12345");
        assertThat(cells.nth(4)).hasText("NULL");
        screenshot(page, "workbench-grid-values.png");
    }

    private Page openWorkbench(DatabaseInstance target, Consumer<Page> extra) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setTimezoneId("Asia/Seoul").setViewportSize(1512, 900));
        context.setDefaultTimeout(20_000);
        contexts.add(context);
        Page page = context.newPage();
        page.route("**/api/instances/*/schema", route -> fulfillJson(route, schemaJson(instanceIdOf(route))));
        page.route("**/api/workbench/instances/*/worksheets", this::fulfillSheets);
        page.route("**/api/workbench/worksheets/*", this::fulfillSheetMutation);
        if (extra != null) extra.accept(page);
        page.navigate(base() + "/login.html");
        page.fill("#username", USER);
        page.fill("#password", PASSWORD);
        page.click("button[type=submit]");
        page.waitForURL(Pattern.compile("^(?!.*/login).*$"));
        page.navigate(base() + "/?mode=workbench&instance=" + target.getId());
        page.locator("#wb-input").waitFor();
        return page;
    }

    private long instanceIdOf(Route route) {
        Matcher m = INSTANCE_IN_PATH.matcher(route.request().url());
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    /** 스키마 스텁 — database 이름을 인스턴스마다 다르게 두어 "그 인스턴스 것으로 바뀌었나"를 잴 수 있게 한다 */
    private static String schemaJson(long instanceId) {
        return """
                {"type":"MYSQL","database":"db-%d","truncated":false,"tableCap":500,
                 "tables":[{"name":"orders","kind":"TABLE","primaryKey":["id"],
                   "columns":[{"name":"id","type":"bigint","nullable":false},{"name":"status","type":"varchar","nullable":true}],
                   "indexes":[{"name":"PRIMARY","columns":["id"],"unique":true}]}]}"""
                .formatted(instanceId);
    }

    private void fulfillSheets(Route route) {
        long instanceId = instanceIdOf(route);
        List<StubSheet> list = sheets.computeIfAbsent(instanceId, k -> new CopyOnWriteArrayList<>());
        if ("POST".equals(route.request().method())) {
            StubSheet created = new StubSheet(nextSheetId.incrementAndGet(), "새 워크시트", null);
            list.add(created);
            fulfillJson(route, sheetJson(created, instanceId));
            return;
        }
        fulfillJson(route, list.stream().map((s) -> sheetJson(s, instanceId)).toList().toString());
    }

    /** PATCH(이름·자동 저장)·DELETE(보관) 스텁 — 목록 상태에 반영해 다음 GET이 같은 것을 돌려주게 한다 */
    private void fulfillSheetMutation(Route route) {
        String url = route.request().url();
        long id = Long.parseLong(url.substring(url.lastIndexOf('/') + 1));
        String method = route.request().method();
        if ("DELETE".equals(method)) {
            sheetDeletes.add(url);
            sheets.values().forEach((list) -> list.removeIf((s) -> s.id() == id));
            route.fulfill(new Route.FulfillOptions().setStatus(204));
            return;
        }
        // PATCH — 본문의 title / currentSql을 그대로 반영한다(본문 파싱은 하지 않고 title만 정규식으로 뽑는다)
        String body = route.request().postData();
        sheetPatches.add(url);
        String title = body == null ? null : match(body, "\"title\"\\s*:\\s*\"([^\"]*)\"");
        sheets.values().forEach((list) -> {
            for (int i = 0; i < list.size(); i++) {
                StubSheet s = list.get(i);
                if (s.id() == id && title != null) list.set(i, new StubSheet(s.id(), title, s.latestVersion()));
            }
        });
        fulfillJson(route, sheetJson(new StubSheet(id, title == null ? "" : title, null), 0));
    }

    private static String match(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String sheetJson(StubSheet sheet, long instanceId) {
        return """
                {"id":%d,"instanceId":%d,"title":"%s","currentSql":"","latestVersion":%s,"updatedAt":"2026-09-16T10:00:00"}"""
                .formatted(sheet.id(), instanceId, sheet.title(),
                        sheet.latestVersion() == null ? "null" : String.valueOf(sheet.latestVersion()));
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
