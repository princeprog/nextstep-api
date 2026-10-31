package com.nextstep.api.support;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
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

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTestSupport.TestClockConfig.class)
public abstract class IntegrationTestSupport {

    /**
     * Every test context and the second API instance in SharedRateLimitTest must hash identifiers with the same key.
     * It is generated per test run, so no key-like literal lives in the repository.
     */
    public static final String TEST_IDENTIFIER_KEY = UUID.randomUUID() + "-" + UUID.randomUUID();

    private static final AtomicInteger IP_COUNTER = new AtomicInteger(1);

    @TestConfiguration
    public static class TestClockConfig {
        @Bean
        @Primary
        public MutableClock testClock() {
            return new MutableClock();
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
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

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

    /** Registers a new normal account and returns a signed-in browser with a fresh CSRF token. */
    protected TestBrowser signedInUser(String timeZone) {
        TestBrowser browser = browser();
        browser.refreshCsrf();
        TestBrowser.Response response = browser.post("/api/v1/auth/register", map(
                "displayName", "Test User", "email", uniqueEmail(), "password", "a-strong-passphrase", "timeZone", timeZone));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        browser.refreshCsrf();
        return browser;
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

