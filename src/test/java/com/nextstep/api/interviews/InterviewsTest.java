package com.nextstep.api.interviews;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

class InterviewsTest extends IntegrationTestSupport {

    @Test
    void storesUtcInstantAndOriginalZone() {
        TestBrowser b = signedInUser();
        String app = createApplication(b);
        Response created = b.post("/api/v1/interviews", map("applicationId", app, "type", "PHONE_SCREEN",
                "localDate", "2030-03-12", "localTime", "10:00", "timeZone", "Asia/Manila",
                "durationMinutes", 45, "meetingUrl", "", "interviewerName", " Jun "));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("startsAt")).isEqualTo("2030-03-12T02:00:00Z");
        assertThat(created.text("timeZone")).isEqualTo("Asia/Manila");
        assertThat(created.text("localTime")).isEqualTo("10:00");
        assertThat(created.text("meetingUrl")).as("blank link is allowed").isNull();
        assertThat(created.text("interviewerName")).isEqualTo("Jun");
        assertThat(b.get("/api/v1/applications/" + app).text("status")).as("interviews never change status").isEqualTo("SAVED");
    }

    @Test
    void rejectsInvalidTimesLinksAndPartialStartChanges() {
        TestBrowser b = signedInUser();
        String app = createApplication(b);
        Response gap = b.post("/api/v1/interviews", map("applicationId", app, "type", "OTHER",
                "localDate", "2026-03-08", "localTime", "02:30", "timeZone", "America/New_York"));
        assertThat(gap.fieldError("localTime")).contains("doesn't exist");
        Response overlap = b.post("/api/v1/interviews", map("applicationId", app, "type", "OTHER",
                "localDate", "2026-11-01", "localTime", "01:15", "timeZone", "America/New_York"));
        assertThat(overlap.fieldError("localTime")).contains("happens twice");
        Response badLink = b.post("/api/v1/interviews", map("applicationId", app, "type", "OTHER",
                "localDate", "2030-01-01", "localTime", "10:00", "timeZone", "UTC", "meetingUrl", "ftp://example.com"));
        assertThat(badLink.fieldError("meetingUrl")).isNotBlank();
        assertThat(b.post("/api/v1/interviews", map("applicationId", app, "type", "OTHER",
                "localDate", "2030-01-01", "localTime", "10:00", "timeZone", "UTC", "durationMinutes", 0))
                .fieldError("durationMinutes")).isNotBlank();

        String interview = createInterview(b, app, "2030-01-01", "10:00", "UTC");
        Response partial = b.patch("/api/v1/interviews/" + interview, map("localTime", "11:00"));
        assertThat(partial.status()).isEqualTo(422);
        assertThat(partial.fieldError("localDate")).isNotBlank();
        Response moved = b.patch("/api/v1/interviews/" + interview, map("localDate", "2030-01-01", "localTime", "11:00",
                "timeZone", "Europe/London", "notes", "Bring portfolio"));
        assertThat(moved.text("startsAt")).isEqualTo("2030-01-01T11:00:00Z");
        assertThat(moved.text("notes")).isEqualTo("Bring portfolio");
        assertThat(b.patch("/api/v1/interviews/" + interview, map("notes", null)).text("notes")).isNull();
    }

    @Test
    void upcomingWindowIsNowInclusiveTo168HoursExclusive() {
        TestBrowser b = signedInUser();
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        clock.set(now.minusSeconds(3600));
        String app = createApplication(b);
        String atNow = createInterview(b, app, "2030-01-01", "00:00", "UTC");
        String lastMinute = createInterview(b, app, "2030-01-07", "23:59", "UTC");
        String atEnd = createInterview(b, app, "2030-01-08", "00:00", "UTC");
        String past = createInterview(b, app, "2029-12-31", "23:59", "UTC");

        clock.set(now);
        Response upcoming = b.get("/api/v1/interviews?view=next7days");
        assertThat(upcoming.ids("items")).containsExactly(atNow, lastMinute);
        var section = b.get("/api/v1/dashboard").json().get("upcomingInterviews");
        assertThat(section.get("total").asLong()).isEqualTo(2);
        assertThat(b.get("/api/v1/interviews?view=future").ids("items")).containsExactly(atNow, lastMinute, atEnd);
        assertThat(b.get("/api/v1/interviews?view=past").ids("items")).containsExactly(past);
        assertThat(b.get("/api/v1/applications/" + app + "/interviews").total()).as("past interviews stay on the application").isEqualTo(4);
    }
}

