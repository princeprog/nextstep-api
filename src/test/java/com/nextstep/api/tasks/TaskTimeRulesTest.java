package com.nextstep.api.tasks;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

class TaskTimeRulesTest extends IntegrationTestSupport {

    private long section(TestBrowser b, String name) {
        return b.get("/api/v1/dashboard").json().get(name).get("total").asLong();
    }

    @Test
    void dateOnlyTaskStaysDueUntilTheEndOfItsLocalDay() {
        TestBrowser b = signedInUser();
        clock.set(Instant.parse("2026-03-10T04:00:00Z"));
        String app = createApplication(b);
        String task = createTask(b, app, "2026-03-10", null, "Asia/Manila");

        clock.set(Instant.parse("2026-03-10T15:59:59Z")); // 23:59:59 in Manila
        assertThat(b.get("/api/v1/tasks/" + task).text("dueState")).isEqualTo("DUE_TODAY");
        assertThat(section(b, "upcomingTasks")).isEqualTo(1);
        assertThat(section(b, "overdueTasks")).isZero();

        clock.set(Instant.parse("2026-03-10T16:00:00Z")); // midnight in Manila
        assertThat(b.get("/api/v1/tasks/" + task).text("dueState")).isEqualTo("OVERDUE");
        assertThat(section(b, "upcomingTasks")).isZero();
        assertThat(section(b, "overdueTasks")).isEqualTo(1);
        assertThat(b.get("/api/v1/tasks?view=overdue").ids("items")).containsExactly(task);
    }

    @Test
    void timedTaskIsDueAtItsInstantAndOverdueAfterIt() {
        TestBrowser b = signedInUser();
        clock.set(Instant.parse("2026-03-10T00:00:00Z"));
        String app = createApplication(b);
        Response created = b.post("/api/v1/tasks", map("applicationId", app, "title", "Call", "dueDate", "2026-03-10",
                "dueTime", "09:00", "timeZone", "Asia/Manila"));
        assertThat(created.text("dueAt")).isEqualTo("2026-03-10T01:00:00Z");
        String task = created.text("id");

        clock.set(Instant.parse("2026-03-10T01:00:00Z"));
        assertThat(b.get("/api/v1/tasks/" + task).text("dueState")).as("exactly at the instant it is still due").isEqualTo("DUE_TODAY");
        assertThat(section(b, "overdueTasks")).isZero();

        clock.set(Instant.parse("2026-03-10T01:00:01Z"));
        assertThat(b.get("/api/v1/tasks/" + task).text("dueState")).isEqualTo("OVERDUE");
        assertThat(section(b, "overdueTasks")).isEqualTo(1);
        assertThat(section(b, "upcomingTasks")).isZero();
    }

    @Test
    void upcomingCoversTodayThroughSixMoreLocalDates() {
        TestBrowser b = signedInUser();
        clock.set(Instant.parse("2026-03-10T04:00:00Z")); // 12:00 in Manila
        String app = createApplication(b);
        String lastDay = createTask(b, app, "2026-03-16", null, "Asia/Manila");
        String tooLate = createTask(b, app, "2026-03-17", null, "Asia/Manila");
        assertThat(b.get("/api/v1/tasks?view=upcoming").ids("items")).containsExactly(lastDay);
        assertThat(b.get("/api/v1/tasks/" + tooLate).text("dueState")).isEqualTo("LATER");
        assertThat(b.get("/api/v1/tasks/" + lastDay).text("dueState")).isEqualTo("UPCOMING");
    }

    @Test
    void dueStateUsesEachTasksOwnZone() {
        TestBrowser b = signedInUser();
        clock.set(Instant.parse("2026-03-10T00:00:00Z"));
        String app = createApplication(b);
        String auckland = createTask(b, app, "2026-03-10", null, "Pacific/Auckland");
        String losAngeles = createTask(b, app, "2026-03-10", null, "America/Los_Angeles");

        clock.set(Instant.parse("2026-03-10T12:00:00Z")); // 01:00 on Mar 11 in Auckland, 05:00 on Mar 10 in Los Angeles
        assertThat(b.get("/api/v1/tasks/" + auckland).text("dueState")).isEqualTo("OVERDUE");
        assertThat(b.get("/api/v1/tasks/" + losAngeles).text("dueState")).isEqualTo("DUE_TODAY");
        assertThat(b.get("/api/v1/tasks?view=overdue").ids("items")).containsExactly(auckland);
        assertThat(b.get("/api/v1/tasks?view=upcoming").ids("items")).containsExactly(losAngeles);
    }

    @Test
    void daylightSavingGapsAndOverlapsAreRejected() {
        TestBrowser b = signedInUser();
        String app = createApplication(b);
        Response gap = b.post("/api/v1/tasks", map("applicationId", app, "title", "Gap", "dueDate", "2026-03-08",
                "dueTime", "02:30", "timeZone", "America/New_York"));
        assertThat(gap.status()).isEqualTo(422);
        assertThat(gap.fieldError("dueTime")).contains("doesn't exist");
        Response overlap = b.post("/api/v1/tasks", map("applicationId", app, "title", "Overlap", "dueDate", "2026-11-01",
                "dueTime", "01:30", "timeZone", "America/New_York"));
        assertThat(overlap.status()).isEqualTo(422);
        assertThat(overlap.fieldError("dueTime")).contains("happens twice");
        assertThat(b.post("/api/v1/tasks", map("applicationId", app, "title", "Bad zone", "dueDate", "2026-11-01",
                "timeZone", "Nowhere/Land")).fieldError("timeZone")).isNotBlank();
        assertThat(b.post("/api/v1/tasks", map("applicationId", app, "title", "Bad date", "dueDate", "2026-02-30",
                "timeZone", "UTC")).fieldError("dueDate")).isNotBlank();
        assertThat(b.get("/api/v1/applications/" + app + "/tasks").total()).as("rejected writes change nothing").isZero();

        String task = createTask(b, app, "2026-03-08", "01:30", "America/New_York");
        assertThat(b.patch("/api/v1/tasks/" + task, map("dueTime", "02:15")).status()).isEqualTo(422);
        assertThat(b.get("/api/v1/tasks/" + task).text("dueTime")).isEqualTo("01:30");
    }

    @Test
    void completingAndReopeningMovesTasksBetweenSections() {
        TestBrowser b = signedInUser();
        clock.set(Instant.parse("2026-03-10T04:00:00Z"));
        String app = createApplication(b);
        String overdue = createTask(b, app, "2026-03-01", null, "Asia/Manila");
        assertThat(section(b, "overdueTasks")).isEqualTo(1);

        Response completed = b.post("/api/v1/tasks/" + overdue + "/complete", null);
        assertThat(completed.text("status")).isEqualTo("COMPLETED");
        assertThat(completed.text("completedAt")).isEqualTo("2026-03-10T04:00:00Z");
        assertThat(b.post("/api/v1/tasks/" + overdue + "/complete", null).text("completedAt"))
                .as("repeated completion is idempotent").isEqualTo("2026-03-10T04:00:00Z");
        assertThat(section(b, "overdueTasks")).isZero();
        assertThat(section(b, "upcomingTasks")).isZero();
        assertThat(b.get("/api/v1/tasks?view=completed").ids("items")).containsExactly(overdue);

        Response reopened = b.post("/api/v1/tasks/" + overdue + "/reopen", null);
        assertThat(reopened.text("status")).isEqualTo("OPEN");
        assertThat(reopened.text("completedAt")).isNull();
        assertThat(reopened.text("dueState")).isEqualTo("OVERDUE");
        assertThat(section(b, "overdueTasks")).isEqualTo(1);

        assertThat(b.delete("/api/v1/tasks/" + overdue).status()).isEqualTo(204);
        assertThat(b.get("/api/v1/tasks/" + overdue).status()).isEqualTo(404);
    }

    @Test
    void profileTimeZoneChangesDoNotRewriteExistingRecords() {
        TestBrowser b = signedInUser("Asia/Manila");
        String app = createApplication(b);
        String task = createTask(b, app, "2030-06-01", "09:00", "Asia/Manila");
        String interview = createInterview(b, app, "2030-06-02", "10:00", "Asia/Manila");
        String startsAt = b.get("/api/v1/interviews/" + interview).text("startsAt");

        assertThat(b.patch("/api/v1/me", map("timeZone", "Europe/London")).text("timeZone")).isEqualTo("Europe/London");
        Response taskAfter = b.get("/api/v1/tasks/" + task);
        assertThat(taskAfter.text("timeZone")).isEqualTo("Asia/Manila");
        assertThat(taskAfter.text("dueAt")).isEqualTo("2030-06-01T01:00:00Z");
        Response interviewAfter = b.get("/api/v1/interviews/" + interview);
        assertThat(interviewAfter.text("startsAt")).isEqualTo(startsAt);
        assertThat(interviewAfter.text("timeZone")).isEqualTo("Asia/Manila");
    }
}

