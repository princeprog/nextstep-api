package com.nextstep.api.demo;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.nextstep.api.common.NextStepProperties;
import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

class DemoWorkspaceTest extends IntegrationTestSupport {

    @Autowired
    DemoService demoService;

    @Autowired
    NextStepProperties properties;

    private TestBrowser startDemo() {
        TestBrowser b = browser();
        b.refreshCsrf();
        Response created = b.post("/api/v1/auth/demo", map("timeZone", "Asia/Manila"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.text("kind")).isEqualTo("DEMO");
        assertThat(created.text("email")).isNull();
        b.refreshCsrf();
        return b;
    }

    @Test
    void eachDemoIsSeparateAndSeededWithFictionalData() {
        TestBrowser first = startDemo();
        TestBrowser second = startDemo();
        assertThat(first.get("/api/v1/me").text("id")).isNotEqualTo(second.get("/api/v1/me").text("id"));

        Response apps = first.get("/api/v1/applications?pageSize=100");
        assertThat(apps.total()).isEqualTo(8);
        assertThat(apps.body()).doesNotContain("@gmail").contains("example.com");
        var dashboard = first.get("/api/v1/dashboard").json();
        assertThat(dashboard.get("overdueTasks").get("total").asLong()).isPositive();
        assertThat(dashboard.get("upcomingTasks").get("total").asLong()).isPositive();
        assertThat(dashboard.get("upcomingInterviews").get("total").asLong()).isPositive();

        String firstAppId = apps.ids("items").getFirst();
        assertThat(first.patch("/api/v1/applications/" + firstAppId, map("notes", "Changed in demo one")).status()).isEqualTo(200);
        assertThat(second.get("/api/v1/applications/" + firstAppId).status()).as("guessing IDs fails").isEqualTo(404);
        assertThat(second.get("/api/v1/applications?search=Changed").total()).isZero();
        assertThat(second.get("/api/v1/applications").total()).isEqualTo(8);
    }

    @Test
    void demoExpiresHardAndCleanupRemovesOnlyExpiredDemos() {
        TestBrowser normal = signedInUser();
        String normalApp = createApplication(normal);
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(start);
        TestBrowser demo = startDemo();
        String demoId = demo.get("/api/v1/me").text("id");
        assertThat(demo.get("/api/v1/me").text("demoExpiresAt")).isEqualTo(start.plus(Duration.ofHours(24)).toString());

        clock.set(start.plus(Duration.ofHours(24)).minusSeconds(1));
        assertThat(demo.get("/api/v1/dashboard").status()).isEqualTo(200);
        assertThat(demoService.cleanupExpired()).as("not yet expired").isZero();

        clock.set(start.plus(Duration.ofHours(24)));
        Response expired = demo.get("/api/v1/applications");
        assertThat(expired.status()).isEqualTo(401);
        assertThat(expired.code()).isEqualTo("DEMO_EXPIRED");
        assertThat(demo.get("/api/v1/me").status()).isEqualTo(401);

        assertThat(demoService.cleanupExpired()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = CAST(? AS uuid)", Integer.class, demoId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM applications WHERE owner_id = CAST(? AS uuid)", Integer.class, demoId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, demoId)).isZero();
        assertThat(normal.get("/api/v1/applications/" + normalApp).status()).as("normal accounts are untouched").isEqualTo(200);
    }

    @Test
    void demoCannotDeleteAccountAndHasResourceLimits() {
        TestBrowser demo = startDemo();
        assertThat(demo.delete("/api/v1/me").code()).isEqualTo("DEMO_ACCOUNT");
        int original = properties.getDemo().getMaxApplications();
        try {
            properties.getDemo().setMaxApplications(9);
            assertThat(demo.post("/api/v1/applications", map("company", "One more", "jobTitle", "Role")).status()).isEqualTo(201);
            Response limited = demo.post("/api/v1/applications", map("company", "Too many", "jobTitle", "Role"));
            assertThat(limited.status()).isEqualTo(409);
            assertThat(limited.code()).isEqualTo("DEMO_LIMIT_REACHED");
        } finally {
            properties.getDemo().setMaxApplications(original);
        }
        assertThat(demo.post("/api/v1/auth/logout", null).status()).isEqualTo(204);
        assertThat(demo.get("/api/v1/me").status()).isEqualTo(401);
    }

    @Test
    void demoCreationIsRateLimitedPerClient() {
        TestBrowser b = browser();
        int lastStatus = 0;
        for (int i = 0; i < 6; i++) {
            b.setSessionCookie(null);
            b.refreshCsrf();
            lastStatus = b.post("/api/v1/auth/demo", null).status();
            if (lastStatus == 201) {
                b.refreshCsrf();
                b.post("/api/v1/auth/logout", null);
            }
        }
        assertThat(lastStatus).isEqualTo(429);
    }
}

