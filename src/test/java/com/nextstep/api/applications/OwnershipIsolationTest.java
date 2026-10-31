package com.nextstep.api.applications;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;

class OwnershipIsolationTest extends IntegrationTestSupport {

    @Test
    void anotherAccountCannotReadChangeOrCountRecords() {
        TestBrowser a = signedInUser();
        TestBrowser b = signedInUser();
        String application = createApplication(a, "company", "Owner A Unique Co", "contactName", "Secret Contact");
        String task = createTask(a, application, "2030-01-01", null, "UTC");
        String interview = createInterview(a, application, "2030-01-02", "10:00", "UTC");

        for (String path : new String[] {
                "/api/v1/applications/" + application,
                "/api/v1/applications/" + application + "/tasks",
                "/api/v1/applications/" + application + "/interviews",
                "/api/v1/tasks/" + task,
                "/api/v1/interviews/" + interview}) {
            assertThat(b.get(path).status()).as(path).isEqualTo(404);
        }
        assertThat(b.patch("/api/v1/applications/" + application, map("company", "Hijacked")).status()).isEqualTo(404);
        assertThat(b.patch("/api/v1/applications/" + application + "/status", map("status", "OFFER")).status()).isEqualTo(404);
        assertThat(b.post("/api/v1/applications/" + application + "/archive", null).status()).isEqualTo(404);
        assertThat(b.post("/api/v1/applications/" + application + "/restore", null).status()).isEqualTo(404);
        assertThat(b.patch("/api/v1/tasks/" + task, map("title", "Hijacked")).status()).isEqualTo(404);
        assertThat(b.post("/api/v1/tasks/" + task + "/complete", null).status()).isEqualTo(404);
        assertThat(b.delete("/api/v1/tasks/" + task).status()).isEqualTo(404);
        assertThat(b.patch("/api/v1/interviews/" + interview, map("location", "Hijacked")).status()).isEqualTo(404);
        assertThat(b.delete("/api/v1/interviews/" + interview).status()).isEqualTo(404);

        assertThat(b.post("/api/v1/tasks", map("applicationId", application, "title", "Sneaky",
                "dueDate", "2030-01-01", "timeZone", "UTC")).status()).as("child creation on another owner's parent").isEqualTo(404);
        assertThat(b.post("/api/v1/interviews", map("applicationId", application, "type", "OTHER",
                "localDate", "2030-01-01", "localTime", "09:00", "timeZone", "UTC")).status()).isEqualTo(404);

        assertThat(b.get("/api/v1/applications?search=Owner%20A%20Unique").total()).isZero();
        assertThat(b.get("/api/v1/applications?search=Secret%20Contact").total()).isZero();
        assertThat(b.get("/api/v1/tasks").total()).isZero();
        assertThat(b.get("/api/v1/interviews").total()).isZero();
        var dashboard = b.get("/api/v1/dashboard").json();
        assertThat(dashboard.get("statusCounts").get("SAVED").asLong()).isZero();

        TestBrowser.Response unchanged = a.get("/api/v1/applications/" + application);
        assertThat(unchanged.text("company")).isEqualTo("Owner A Unique Co");
        assertThat(a.get("/api/v1/tasks/" + task).text("status")).isEqualTo("OPEN");
    }

    @Test
    void ownerIdInRequestBodyIsIgnored() {
        TestBrowser a = signedInUser();
        TestBrowser b = signedInUser();
        String bId = b.get("/api/v1/me").text("id");
        String application = createApplication(a, "ownerId", bId);
        assertThat(a.get("/api/v1/applications/" + application).status()).isEqualTo(200);
        assertThat(b.get("/api/v1/applications/" + application).status()).isEqualTo(404);
    }
}

