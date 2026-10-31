package com.nextstep.api.common;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.nextstep.api.NextstepApiApplication;
import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestMailbox;
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
    void onlyFailedSignInsCountAgainstAnEmailAndAResetClearsThem() {
        String email = uniqueEmail();
        registerVerified(email, PASSWORD, "UTC");
        int original = properties.getRateLimit().getLoginFailuresPerEmailPerWindow();
        properties.getRateLimit().setLoginFailuresPerEmailPerWindow(4);
        try {
            for (int i = 0; i < 6; i++) {
                assertThat(signInStatus(email, PASSWORD)).as("successful sign-in %d", i + 1).isEqualTo(200);
            }
            for (int i = 0; i < 4; i++) {
                assertThat(signInStatus(email, "not-the-password")).isEqualTo(401);
            }
            assertThat(signInStatus(email, PASSWORD)).as("the email's failure allowance is used up").isEqualTo(429);

            TestBrowser owner = browser();
            owner.refreshCsrf();
            owner.post("/api/v1/auth/password-reset", map("email", email));
            String token = TestMailbox.token(mailbox.await(email, "Reset your NextStep password"));
            assertThat(owner.post("/api/v1/auth/password-reset/confirm", map("token", token, "password",
                    "a-new-strong-passphrase")).status()).isEqualTo(204);
            assertThat(signInStatus(email, "a-new-strong-passphrase")).as("a reset clears the failures").isEqualTo(200);
        } finally {
            properties.getRateLimit().setLoginFailuresPerEmailPerWindow(original);
        }
    }

    @Test
    void successfulSignInsDoNotUseUpAnAddressAllowance() {
        String email = uniqueEmail();
        registerVerified(email, PASSWORD, "UTC");
        String sharedAddress = "198.51.100.91";
        for (int i = 0; i < 12; i++) {
            TestBrowser device = new TestBrowser(port, objectMapper, sharedAddress);
            device.refreshCsrf();
            assertThat(device.post("/api/v1/auth/login", map("email", email, "password", PASSWORD)).status())
                    .as("sign-in %d from an office network", i + 1).isEqualTo(200);
        }
    }

    @Test
    void simultaneousFailedSignInsCannotPassTheAddressLimit() throws Exception {
        String address = "198.51.100.92";
        List<TestBrowser> devices = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            TestBrowser device = new TestBrowser(port, objectMapper, address);
            device.refreshCsrf();
            devices.add(device);
        }
        ExecutorService pool = Executors.newFixedThreadPool(devices.size());
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (TestBrowser device : devices) {
                results.add(pool.submit(() -> device.post("/api/v1/auth/login",
                        map("email", uniqueEmail(), "password", "not-the-password")).status()));
            }
            int failures = 0;
            for (Future<Integer> result : results) {
                int status = result.get();
                assertThat(status).isIn(401, 429);
                if (status == 401) {
                    failures++;
                }
            }
            assertThat(failures).as("each attempt is counted before the password check").isLessThanOrEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void requestsThatQueueEmailShareAnHourlyCap() {
        int original = properties.getMail().getGlobalPerHour();
        clock.set(Instant.parse("2032-02-01T10:00:00Z"));
        properties.getMail().setGlobalPerHour(3);
        try {
            for (int i = 0; i < 3; i++) {
                TestBrowser visitor = browser();
                visitor.refreshCsrf();
                assertThat(visitor.post("/api/v1/auth/password-reset", map("email", uniqueEmail())).status()).isEqualTo(202);
            }
            TestBrowser fourth = browser();
            fourth.refreshCsrf();
            TestBrowser.Response blocked = fourth.post("/api/v1/auth/password-reset", map("email", uniqueEmail()));
            assertThat(blocked.status()).as("a new address and email still hit the shared cap").isEqualTo(429);
            assertThat(blocked.code()).isEqualTo("RATE_LIMITED");
        } finally {
            properties.getMail().setGlobalPerHour(original);
        }
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
