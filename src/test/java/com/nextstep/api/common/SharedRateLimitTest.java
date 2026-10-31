package com.nextstep.api.common;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.nextstep.api.NextstepApiApplication;
import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestPostgres;

class SharedRateLimitTest extends IntegrationTestSupport {

    @Autowired
    NextStepProperties properties;

    @Test
    void signInLimitHoldsAcrossApiInstances() {
        try (ConfigurableApplicationContext second = startSecondInstance()) {
            int secondPort = second.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            String sameClient = "198.51.100.77";
            TestBrowser first = new TestBrowser(port, objectMapper, sameClient);
            TestBrowser other = new TestBrowser(secondPort, objectMapper, sameClient);
            first.refreshCsrf();
            other.refreshCsrf();
            for (int i = 0; i < 10; i++) {
                TestBrowser instance = i % 2 == 0 ? first : other;
                int status = instance.post("/api/v1/auth/login", map("email", uniqueEmail(), "password", "wrong-" + i)).status();
                assertThat(status).as("attempt %d", i + 1).isEqualTo(401);
            }
            assertThat(first.post("/api/v1/auth/login", map("email", uniqueEmail(), "password", "wrong")).status())
                    .as("limit reached on the first instance").isEqualTo(429);
            assertThat(other.post("/api/v1/auth/login", map("email", uniqueEmail(), "password", "wrong")).status())
                    .as("and on the second").isEqualTo(429);
        }
    }

    @Test
    void onlyFailedSignInsCountAgainstAnEmail() {
        String email = uniqueEmail();
        registerVerified(email, PASSWORD, "UTC");
        for (int i = 0; i < 12; i++) {
            assertThat(signInStatus(email, PASSWORD)).as("successful sign-in %d", i + 1).isEqualTo(200);
        }
        for (int i = 0; i < 10; i++) {
            assertThat(signInStatus(email, "not-the-password")).isEqualTo(401);
        }
        assertThat(signInStatus(email, PASSWORD)).as("ten failures lock the email for the window").isEqualTo(429);
    }

    @Test
    void demoCreationHasAGlobalCap() {
        int original = properties.getRateLimit().getDemoGlobalPerHour();
        clock.set(Instant.parse("2031-05-01T10:00:00Z"));
        properties.getRateLimit().setDemoGlobalPerHour(2);
        try {
            for (int i = 0; i < 2; i++) {
                TestBrowser visitor = browser();
                visitor.refreshCsrf();
                assertThat(visitor.post("/api/v1/auth/demo", null).status()).isEqualTo(201);
            }
            TestBrowser third = browser();
            third.refreshCsrf();
            TestBrowser.Response blocked = third.post("/api/v1/auth/demo", null);
            assertThat(blocked.status()).as("a new address still hits the global cap").isEqualTo(429);
            assertThat(blocked.code()).isEqualTo("RATE_LIMITED");
        } finally {
            properties.getRateLimit().setDemoGlobalPerHour(original);
        }
    }

    /** Signs in from a fresh address, so only the per-email limit can apply. */
    private int signInStatus(String email, String password) {
        TestBrowser device = browser();
        device.refreshCsrf();
        return device.post("/api/v1/auth/login", map("email", email, "password", password)).status();
    }

    private static ConfigurableApplicationContext startSecondInstance() {
        TestPostgres postgres = TestPostgres.get();
        return new SpringApplicationBuilder(NextstepApiApplication.class).run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.datasource.url=" + postgres.jdbcUrl(),
                "--spring.datasource.username=" + postgres.user(),
                "--spring.datasource.password=" + postgres.password(),
                "--nextstep.security.identifier-key=" + TEST_IDENTIFIER_KEY,
                "--spring.main.banner-mode=off");
    }
}
