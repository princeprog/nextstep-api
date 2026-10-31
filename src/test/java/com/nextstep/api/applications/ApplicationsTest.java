package com.nextstep.api.applications;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

class ApplicationsTest extends IntegrationTestSupport {

    @Test
    void createValidatesFieldsAndKeepsPlainTextNotes() {
        TestBrowser b = signedInUser();
        Response invalid = b.post("/api/v1/applications", map(
                "company", "   ", "jobTitle", "", "jobUrl", "javascript:alert(1)", "contactEmail", "nope",
                "salaryMin", "5000", "salaryMax", "100", "status", "APPLIED"));
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.fieldError("company")).isNotBlank();
        assertThat(invalid.fieldError("jobTitle")).isNotBlank();
        assertThat(invalid.fieldError("jobUrl")).isNotBlank();
        assertThat(invalid.fieldError("contactEmail")).isNotBlank();
        assertThat(invalid.fieldError("salaryMax")).isNotBlank();
        assertThat(invalid.fieldError("salaryCurrency")).isNotBlank();
        assertThat(invalid.fieldError("salaryPeriod")).isNotBlank();
        assertThat(invalid.fieldError("appliedDate")).as("APPLIED requires a real applied date").isNotBlank();

        assertThat(b.post("/api/v1/applications", map("company", "A", "jobTitle", "B", "salaryMin", "10",
                "salaryCurrency", "ZZZ", "salaryPeriod", "YEAR")).fieldError("salaryCurrency")).isNotBlank();
        assertThat(b.post("/api/v1/applications", map("company", "A", "jobTitle", "B", "salaryMin", "10.123",
                "salaryCurrency", "USD", "salaryPeriod", "YEAR")).fieldError("salaryMin")).isNotBlank();

        String notes = "<script>alert('x')</script>\n  Indented line";
        Response created = b.post("/api/v1/applications", map(
                "company", "  Harbor Labs ", "jobTitle", "Frontend Developer", "notes", notes,
                "salaryMin", "60000", "salaryMax", "80000.5", "salaryCurrency", "php", "salaryPeriod", "MONTH",
                "jobUrl", "https://example.com/jobs/1"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("status")).isEqualTo("SAVED");
        assertThat(created.text("company")).isEqualTo("Harbor Labs");
        assertThat(created.text("notes")).isEqualTo(notes);
        assertThat(created.text("salaryMin")).isEqualTo("60000.00");
        assertThat(created.text("salaryMax")).isEqualTo("80000.50");
        assertThat(created.text("salaryCurrency")).isEqualTo("PHP");

        Response duplicate = b.post("/api/v1/applications", map("company", "harbor labs", "jobTitle", "frontend developer"));
        assertThat(duplicate.status()).as("duplicates are allowed").isEqualTo(201);
        assertThat(b.get("/api/v1/applications/duplicates?company=Harbor%20Labs&jobTitle=Frontend%20Developer").json()
                .get("count").asLong()).isEqualTo(2);
    }

    @Test
    void patchChangesOnlySentFieldsAndExplicitNullClears() {
        TestBrowser b = signedInUser();
        String id = createApplication(b, "location", "Manila", "contactName", "Mara", "notes", "Keep me");
        Response patched = b.patch("/api/v1/applications/" + id, map("location", null, "jobTitle", "Senior Engineer"));
        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.text("location")).isNull();
        assertThat(patched.text("jobTitle")).isEqualTo("Senior Engineer");
        assertThat(patched.text("contactName")).isEqualTo("Mara");
        assertThat(patched.text("notes")).isEqualTo("Keep me");

        Response statusViaPatch = b.patch("/api/v1/applications/" + id, map("status", "OFFER"));
        assertThat(statusViaPatch.status()).isEqualTo(422);
        assertThat(statusViaPatch.fieldError("status")).isNotBlank();

        Response clearRequired = b.patch("/api/v1/applications/" + id, map("company", null, "contactName", "Changed"));
        assertThat(clearRequired.status()).isEqualTo(422);
        assertThat(b.get("/api/v1/applications/" + id).text("contactName")).as("failed PATCH changes nothing").isEqualTo("Mara");

        Response salaryOnly = b.patch("/api/v1/applications/" + id, map("salaryMin", "100"));
        assertThat(salaryOnly.status()).as("the merged record is validated").isEqualTo(422);
    }

    @Test
    void statusChangesFollowRules() {
        TestBrowser b = signedInUser();
        String id = createApplication(b);
        String task = createTask(b, id, "2030-01-01", null, "UTC");
        String interview = createInterview(b, id, "2030-01-02", "09:30", "UTC");

        Response missingDate = b.patch("/api/v1/applications/" + id + "/status", map("status", "APPLIED"));
        assertThat(missingDate.status()).isEqualTo(422);
        assertThat(missingDate.fieldError("appliedDate")).isNotBlank();
        assertThat(b.get("/api/v1/applications/" + id).text("status")).isEqualTo("SAVED");

        assertThat(b.patch("/api/v1/applications/" + id + "/status", map("status", "APPLIED", "appliedDate", "2999-01-01"))
                .fieldError("appliedDate")).as("future applied dates are rejected").isNotBlank();
        assertThat(b.patch("/api/v1/applications/" + id + "/status", map("status", "APPLIED", "appliedDate", "2026-01-15"))
                .text("status")).isEqualTo("APPLIED");
        for (String status : new String[] {"OFFER", "SAVED", "WITHDRAWN", "INTERVIEW", "REJECTED", "APPLIED"}) {
            Response changed = b.patch("/api/v1/applications/" + id + "/status", map("status", status));
            assertThat(changed.status()).as(status).isEqualTo(200);
            assertThat(changed.text("status")).isEqualTo(status);
        }
        assertThat(b.patch("/api/v1/applications/" + id + "/status", map("status", "APPLIED", "appliedDate", null)).status())
                .isEqualTo(422);
        assertThat(b.patch("/api/v1/applications/" + id + "/status", map("status", "UNKNOWN")).status()).isEqualTo(422);

        assertThat(b.get("/api/v1/tasks/" + task).text("status")).as("status changes leave tasks alone").isEqualTo("OPEN");
        assertThat(b.get("/api/v1/interviews/" + interview).status()).isEqualTo(200);
    }

    @Test
    void archiveHidesAndRestoreReturnsTheSameRecordWithChildren() {
        TestBrowser b = signedInUser();
        clock.set(Instant.parse("2026-03-10T04:00:00Z"));
        String id = createApplication(b, "notes", "Line one\nLine two");
        String task = createTask(b, id, "2026-03-09", null, "Asia/Manila");
        String interview = createInterview(b, id, "2026-03-12", "10:00", "Asia/Manila");
        assertThat(b.get("/api/v1/dashboard").json().get("overdueTasks").get("total").asLong()).isEqualTo(1);

        Response archived = b.post("/api/v1/applications/" + id + "/archive", null);
        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.json().get("archived").asBoolean()).isTrue();
        assertThat(b.get("/api/v1/applications").ids("items")).doesNotContain(id);
        assertThat(b.get("/api/v1/applications?archive=archived").ids("items")).contains(id);
        var dashboard = b.get("/api/v1/dashboard").json();
        assertThat(dashboard.get("statusCounts").get("SAVED").asLong()).isZero();
        assertThat(dashboard.get("overdueTasks").get("total").asLong()).isZero();
        assertThat(dashboard.get("upcomingInterviews").get("total").asLong()).isZero();
        assertThat(b.get("/api/v1/tasks").total()).isZero();
        assertThat(b.get("/api/v1/interviews").total()).isZero();

        assertThat(b.get("/api/v1/applications/" + id).status()).as("archived detail stays readable").isEqualTo(200);
        assertThat(b.get("/api/v1/applications/" + id + "/tasks").total()).isEqualTo(1);
        assertThat(b.get("/api/v1/tasks/" + task).json().get("application").get("archived").asBoolean()).isTrue();
        assertThat(b.patch("/api/v1/applications/" + id, map("notes", "x")).code()).isEqualTo("APPLICATION_ARCHIVED");
        assertThat(b.patch("/api/v1/applications/" + id + "/status", map("status", "OFFER")).status()).isEqualTo(409);
        assertThat(b.post("/api/v1/tasks/" + task + "/complete", null).status()).isEqualTo(409);
        assertThat(b.patch("/api/v1/tasks/" + task, map("title", "x")).status()).isEqualTo(409);
        assertThat(b.delete("/api/v1/tasks/" + task).status()).isEqualTo(409);
        assertThat(b.delete("/api/v1/interviews/" + interview).status()).isEqualTo(409);
        assertThat(b.post("/api/v1/tasks", map("applicationId", id, "title", "New", "dueDate", "2026-03-20",
                "timeZone", "UTC")).status()).isEqualTo(409);
        assertThat(b.post("/api/v1/applications/" + id + "/archive", null).status()).isEqualTo(409);

        Response restored = b.post("/api/v1/applications/" + id + "/restore", null);
        assertThat(restored.status()).isEqualTo(200);
        assertThat(restored.text("notes")).isEqualTo("Line one\nLine two");
        assertThat(restored.text("status")).isEqualTo("SAVED");
        dashboard = b.get("/api/v1/dashboard").json();
        assertThat(dashboard.get("statusCounts").get("SAVED").asLong()).isEqualTo(1);
        assertThat(dashboard.get("overdueTasks").get("total").asLong()).as("restored task is immediately overdue").isEqualTo(1);
        assertThat(dashboard.get("upcomingInterviews").get("total").asLong()).isEqualTo(1);
        assertThat(b.get("/api/v1/tasks/" + task).text("status")).isEqualTo("OPEN");
    }

    @Test
    void malformedIdsAndBodiesAreHandled() {
        TestBrowser b = signedInUser();
        assertThat(b.get("/api/v1/applications/not-a-uuid").status()).isEqualTo(404);
        assertThat(b.send("POST", "/api/v1/applications", "just a string", true).status()).isEqualTo(400);
        Response error = b.get("/api/v1/applications/00000000-0000-0000-0000-000000000000");
        assertThat(error.status()).isEqualTo(404);
        assertThat(error.text("requestId")).isNotBlank();
        assertThat(error.body()).doesNotContain("Exception");
    }
}

