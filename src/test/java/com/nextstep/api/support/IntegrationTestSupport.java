package com.nextstep.api.support;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nextstep.api.identity.BreachedPasswordChecker;
import com.nextstep.api.support.TestBrowser.Response;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTestSupport.TestClockConfig.class)
public abstract class IntegrationTestSupport {

    /**
     * Every test context and the second API instance in SharedRateLimitTest must hash identifiers with the same key.
     * It is generated per test run, so no key-like literal lives in the repository.
     */
    public static final String TEST_IDENTIFIER_KEY = UUID.randomUUID() + "-" + UUID.randomUUID();

    /** The password used by the registration helpers. */
    public static final String PASSWORD = "a-strong-passphrase";

    private static final AtomicInteger IP_COUNTER = new AtomicInteger(1);

    @TestConfiguration
    public static class TestClockConfig {
        @Bean
        @Primary
        public MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        public TestMailbox testMailbox() {
            return new TestMailbox();
        }

        /** Stands in for Pwned Passwords: any password containing "breached" counts as breached. */
        @Bean
        @Primary
        public BreachedPasswordChecker testBreachedPasswords() {
            return password -> password.contains("breached");
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres postgres = TestPostgres.get();
        registry.add("spring.datasource.url", postgres::jdbcUrl);
        registry.add("spring.datasource.username", postgres::user);
        registry.add("spring.datasource.password", postgres::password);
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("nextstep.security.identifier-key", () -> TEST_IDENTIFIER_KEY);
        // The highest hourly email limit the production check allows, so the production-profile tests can start too.
        registry.add("nextstep.mail.global-per-hour", () -> "10000");
        // Tests send queued email themselves (sendDue) or right after a request commits; the background retry would
        // only race with them.
        registry.add("nextstep.mail.poll-interval", () -> "PT1H");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TestMailbox mailbox;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** A new browser with its own cookie jar and a distinct documentation-range client address. */
    protected TestBrowser browser() {
        int n = IP_COUNTER.getAndIncrement();
        String ip = "203.0." + (113 + (n / 250) % 10) + "." + (n % 250 + 1);
        return new TestBrowser(port, objectMapper, ip, sessionCookieName());
    }

    /** The session cookie name this context uses; the production profile prefixes it with __Host-. */
    protected String sessionCookieName() {
        return TestBrowser.COOKIE;
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /** Starts a registration from a fresh browser and returns that browser (with its CSRF token). */
    protected TestBrowser register(String email, String password, String timeZone) {
        TestBrowser browser = browser();
        browser.refreshCsrf();
        Response response = browser.post("/api/v1/auth/register", map(
                "displayName", "Test User", "email", email, "password", password, "timeZone", timeZone));
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        return browser;
    }

    /** Registers and confirms the email with the link from the captured confirmation email. */
    protected void registerVerified(String email, String password, String timeZone) {
        TestBrowser browser = register(email, password, timeZone);
        String token = TestMailbox.token(mailbox.await(email, "Confirm your email"));
        Response verified = browser.post("/api/v1/auth/verify-email", map("token", token));
        assertThat(verified.status()).as(verified.body()).isEqualTo(204);
    }

    /** Signs in from a fresh browser and returns it with a fresh CSRF token. */
    protected TestBrowser signIn(String email, String password) {
        TestBrowser browser = browser();
        browser.refreshCsrf();
        Response response = browser.post("/api/v1/auth/login", map("email", email, "password", password));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        browser.refreshCsrf();
        return browser;
    }

    /** One sign-in attempt from a fresh browser and address; returns the response whatever it is. */
    protected Response loginAttempt(String email, String password) {
        TestBrowser browser = browser();
        browser.refreshCsrf();
        return browser.post("/api/v1/auth/login", map("email", email, "password", password));
    }

    /**
     * Waits until nothing is queued for the account any more. An email arrives in the mailbox just before its batch
     * commits, so a test that checks what happens after sending (older links retired) waits for this first.
     */
    protected void awaitOutboxEmpty(String email) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer queued = jdbc.queryForObject("""
                    SELECT count(*) FROM email_outbox o JOIN accounts a ON a.id = o.account_id WHERE a.email = ?
                    """, Integer.class, email);
            if (queued != null && queued == 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("Emails for " + email + " are still queued");
    }

    /** Registers, verifies, and signs in a new normal account. */
    protected TestBrowser signedInUser(String timeZone) {
        String email = uniqueEmail();
        registerVerified(email, PASSWORD, timeZone);
        return signIn(email, PASSWORD);
    }

    protected TestBrowser signedInUser() {
        return signedInUser("Asia/Manila");
    }

    protected String createApplication(TestBrowser browser, Object... extraFields) {
        var body = map("company", "Acme Corp", "jobTitle", "Engineer");
        for (int i = 0; i < extraFields.length; i += 2) {
            body.put((String) extraFields[i], extraFields[i + 1]);
        }
        TestBrowser.Response response = browser.post("/api/v1/applications", body);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.text("id");
    }

    protected String createTask(TestBrowser browser, String applicationId, String dueDate, String dueTime, String zone) {
        TestBrowser.Response response = browser.post("/api/v1/tasks", map(
                "applicationId", applicationId, "title", "Follow up", "dueDate", dueDate, "dueTime", dueTime, "timeZone", zone));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.text("id");
    }

    protected String createInterview(TestBrowser browser, String applicationId, String date, String time, String zone) {
        TestBrowser.Response response = browser.post("/api/v1/interviews", map(
                "applicationId", applicationId, "type", "TECHNICAL", "localDate", date, "localTime", time, "timeZone", zone));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.text("id");
    }

    protected Clock clock() {
        return clock;
    }
}

