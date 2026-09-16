package io.dbtower.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.dbtower.mcp.internal.domain.Conversation;
import io.dbtower.mcp.internal.domain.ConversationTurn;
import io.dbtower.mcp.internal.persistence.ConversationRepository;
import io.dbtower.mcp.internal.persistence.ConversationTurnRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.setDefaultAssertionTimeout;

/**
 * 관제 AI 채팅의 대화 목록 E2E (B2) — 서버에 저장된 대화를 열고, 목록에서 바꾸고, 지우는 흐름을 실제 브라우저에서 본다.
 *
 * <p>이 흐름은 MockMvc가 볼 수 없다: 대화를 고르는 순간 화면이 갈아끼워지는지, 삭제 확인이 그 자리에서 바뀌는지,
 * 답 서식이 글자로 안전하게 그려지는지는 브라우저에서 눌러야 드러난다. AI 백엔드는 필요 없다 — 턴은 저장소에 직접
 * 넣고(진단 한 번을 돌리지 않는다) 화면이 그 턴을 어떻게 그리는지만 본다.
 *
 * <p>돌리는 법: {@code ./gradlew playwrightInstall && DBTOWER_E2E=1 ./gradlew test --tests '*ChatConversationE2ETest'}
 */
@EnabledIfEnvironmentVariable(named = "DBTOWER_E2E", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dbtower.security.api-token=test-api-token")
class ChatConversationE2ETest {

    private static final String PASSWORD = "e2e-chat-pass-1234";
    private static final String USER = "e2e-chat-viewer";
    private static final String TITLE_LATEST = "결제 실패 원인";
    private static final String TITLE_OLDER = "복제 지연";
    private static final String QUESTION_LATEST = "결제 실패가 왜 늘었어?";
    private static final String QUESTION_OLDER = "복제가 왜 밀려?";
    private static final String ANSWER_OLDER = "지연은 0초입니다.";
    // 목록·인라인 코드·그리고 esc가 먼저인지 보는 태그 — 답 서식은 이 셋이면 충분하다
    private static final String ANSWER_LATEST = """
            주문 상태별로 세어 보세요.

            - `paid` 주문은 12% 줄었습니다
            - `failed` 주문은 3배 늘었습니다

            <script>alert(1)</script>""";
    // 저장된 도구 호출(결과 본문 없음, 176절) — 메타 줄의 "도구 N개"와 접힌 도구 줄을 보려면 하나 있어야 한다
    private static final String TOOL_CALLS =
            "[{\"step\":1,\"tool\":\"sessions\",\"arguments\":\"instanceId=1\",\"reason\":\"누가 막고 있나\","
                    + "\"resultSnippet\":\"\",\"rejected\":false}]";

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
    ConversationRepository conversations;

    @Autowired
    ConversationTurnRepository turns;

    private final List<BrowserContext> contexts = new ArrayList<>();
    private DatabaseInstance instance;
    private DatabaseInstance longNameInstance;

    @BeforeAll
    static void launchBrowser() {
        // 대상이 닿지 않는 주소라 인스턴스를 열 때마다 로더 열여덟 개가 실패를 물고 돌아온다 —
        // 기본 단언 5초는 화면이 틀려서가 아니라 그 대기열 때문에 걸린다(AiOperationConsoleE2ETest와 같은 이유)
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
        // 포트 1은 연결 거부가 즉시 돌아온다 — 대상 DB 없이 화면 분기만 본다
        instance = instances.save(new DatabaseInstance("e2e-chat-mysql", DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p"));
        // 같은 밀리초에 만들면 목록 순서가 흔들린다 — 마지막 대화 시각을 명시적으로 벌려 둔다
        Conversation older = conversationOf(TITLE_OLDER, LocalDateTime.now().minusHours(2));
        Conversation latest = conversationOf(TITLE_LATEST, LocalDateTime.now());
        turns.save(new ConversationTurn(older.getId(), QUESTION_OLDER, ANSWER_OLDER, "복제 지연", "high", "mock", "[]", 4200));
        turns.save(new ConversationTurn(latest.getId(), QUESTION_LATEST, ANSWER_LATEST, "주문 상태 분포 변화", "medium", "mock", TOOL_CALLS, 19800));
    }

    @AfterEach
    void cleanup() {
        contexts.forEach(BrowserContext::close);
        contexts.clear();
        turns.deleteAll();
        conversations.deleteAll();
        instances.delete(instance);
        if (longNameInstance != null) {
            instances.delete(longNameInstance);
            longNameInstance = null;
        }
        users.findByUsername(USER).ifPresent(users::delete);
    }

    @Test
    void 저장된_대화를_열고_목록에서_바꾸고_지운다() {
        Page page = consoleAs(USER);
        Locator log = page.locator("#chat-log");

        // 1) 가장 최근 대화가 열려 있고, 서버에 저장된 턴이 그대로 보인다
        assertThat(log).containsText(QUESTION_LATEST);
        assertThat(log).containsText("주문 상태별로 세어 보세요");
        assertThat(page.locator("#chat-switch-label")).hasText(TITLE_LATEST);
        screenshot(page, "chat-conversation.png");

        // 2) 답 서식 — 목록은 ul/li, 인라인 코드는 code. 그리고 esc가 먼저라 태그가 살아나지 않는다
        assertThat(page.locator("#chat-log ul li")).hasCount(2);
        assertThat(page.locator("#chat-log .chat-text code")).hasCount(2);
        assertThat(page.locator("#chat-log .chat-text code").first()).hasText("paid");
        assertThat(log).containsText("<script>alert(1)</script>");
        assertThat(page.locator("#chat-log script")).hasCount(0);

        // 3) 도구 줄은 기본으로 접혀 있고, 메타 줄의 버튼이 그 자리를 연다(진행 중이면 펼친 채로 쌓인다)
        Locator tools = page.locator("#chat-log .chat-tools-used");
        assertThat(tools).hasCount(1);
        assertThat(tools).isHidden();
        assertThat(page.locator("#chat-log .chat-meta-tools")).hasText("도구 1개 (sessions)");
        page.locator("#chat-log .chat-meta-tools").click();
        assertThat(tools).isVisible();
        assertThat(tools).containsText("누가 막고 있나");

        // 4) 목록 — 최신순 두 줄, 지금 대화가 선택 표시
        page.click("#chat-switch");
        Locator rows = page.locator("#chat-list .chat-list-row");
        assertThat(rows).hasCount(2);
        assertThat(rows.nth(0)).containsText(TITLE_LATEST);
        assertThat(rows.nth(1)).containsText(TITLE_OLDER);
        assertThat(rows.nth(0)).hasClass(Pattern.compile("sel"));
        screenshot(page, "chat-conversation-list.png");

        // 5) 다른 대화로 바꾸면 그 대화의 턴으로 갈아끼워진다
        rows.nth(1).locator(".chat-list-item").click();
        assertThat(log).containsText(QUESTION_OLDER);
        assertThat(log).not().containsText(QUESTION_LATEST);
        assertThat(page.locator("#chat-switch-label")).hasText(TITLE_OLDER);

        // 6) 삭제는 그 자리에서 한 번 더 묻는다 — window.confirm이 뜨지 않는다
        page.click("#chat-switch");
        Locator olderRow = page.locator("#chat-list .chat-list-row")
                .filter(new Locator.FilterOptions().setHasText(TITLE_OLDER));
        olderRow.hover();
        olderRow.locator(".chat-list-del").click();
        assertThat(page.locator("#chat-list")).containsText("삭제할까요?");
        screenshot(page, "chat-conversation-delete-confirm.png");
        page.locator("#chat-list .chat-list-yes").click();

        // 지운 대화가 열려 있었으니 빈 대화로 돌아간다
        assertThat(page.locator("#chat-list .chat-list-row")).hasCount(1);
        assertThat(page.locator("#chat-list")).containsText(TITLE_LATEST);
        assertThat(page.locator("#chat-switch-label")).hasText("새 대화");
        assertThat(log).containsText("무엇이든 물어보세요");
        screenshot(page, "chat-conversation-deleted.png");

        // 7) 데이터 보호 한 줄 — 마우스를 올리거나 초점이면 툴팁이 뜬다(네이티브 title이 아니다)
        assertThat(page.locator("#chat-privacy-tip")).isHidden();
        page.locator("#chat-privacy").hover();
        assertThat(page.locator("#chat-privacy-tip")).isVisible();
        screenshot(page, "chat-privacy-tip.png");
    }

    /**
     * 긴 인스턴스 이름 — 부제가 첫 줄에 있던 때는 전환 버튼이 폭을 먹어 "읽기 도구로만 답합니다"가 잘렸다(B2 2차).
     * 이제 부제는 다른 줄에서 칸 폭 전체를 쓰고, 넘치면 이름 쪽만 줄어든다.
     */
    @Test
    void 긴_인스턴스_이름이어도_부제의_고정_문구는_잘리지_않는다() {
        longNameInstance = instances.save(new DatabaseInstance(
                "e2e-chat-mysql-ap-northeast-2-prod-replica-01-readonly",
                DbmsType.MYSQL, "127.0.0.1", 1, "sample", "u", "p"));
        Page page = consoleAs(longNameInstance, USER);

        Locator fixed = page.locator("#chat-sub .chat-sub-fixed");
        assertThat(fixed).isVisible();
        assertThat(fixed).hasText("· 읽기 도구로만 답합니다");
        // 폭·넘침은 숫자 단언이라 AssertJ로 본다(같은 이름의 Playwright assertThat이 이 파일에 이미 있다)
        org.assertj.core.api.Assertions.assertThat(clipped(fixed)).as("고정 문구가 잘렸다").isLessThanOrEqualTo(0);
        // 대신 이름 쪽이 줄어든다 — 잘린 것이 아니라 자리를 양보한 것이다
        org.assertj.core.api.Assertions.assertThat(clipped(page.locator("#chat-sub .chat-sub-name")))
                .as("긴 이름이 말줄임되지 않았다").isGreaterThan(0);
        // 전환 버튼은 칸의 45%를 넘지 않는다
        double ratio = ((Number) page.locator(".chat-switch-wrap").evaluate(
                "el => el.getBoundingClientRect().width / el.parentElement.getBoundingClientRect().width")).doubleValue();
        org.assertj.core.api.Assertions.assertThat(ratio).as("전환 버튼이 헤더 폭을 너무 먹는다").isLessThanOrEqualTo(0.46);
        // 대화 목록 로드가 끝난 화면을 남긴다 — 헤더만 보려는 사진에 "불러오는 중"이 박히면 안 된다
        page.locator("#chat-log .chat-suggest").waitFor();
        screenshot(page, "chat-header-long-name.png");
    }

    /** 넘친 폭(px). 0 이하면 잘리지 않았다는 뜻이다. */
    private static int clipped(Locator locator) {
        return ((Number) locator.evaluate("el => el.scrollWidth - el.clientWidth")).intValue();
    }

    private Conversation conversationOf(String title, LocalDateTime updatedAt) {
        Conversation conversation = new Conversation(USER, instance.getId(), title);
        ReflectionTestUtils.setField(conversation, "updatedAt", updatedAt);
        return conversations.save(conversation);
    }

    /** 관제 화면을 그 인스턴스로 연다 — 딥링크(?instance=)가 대화를 여는 경로 그대로 들어간다. */
    private Page consoleAs(String username) {
        return consoleAs(instance, username);
    }

    private Page consoleAs(DatabaseInstance target, String username) {
        Page page = loginAs(username);
        page.navigate(base() + "/?instance=" + target.getId());
        page.locator("#time-panel").waitFor();
        return page;
    }

    private Page loginAs(String username) {
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
        return page;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    /** 화면을 build/에 남긴다 — 판정 근거를 사람이 다시 볼 수 있게(경로는 결과 파일에 적는다). */
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
