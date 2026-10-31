package com.nextstep.api.common;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

/** Text that PostgreSQL can't store, or that breaks single-line fields, is a validation error rather than a server error. */
class InputHandlingTest extends IntegrationTestSupport {

    @Test
    void controlCharactersAreValidationErrors() {
        TestBrowser b = signedInUser();
        Response nul = b.post("/api/v1/applications", map("company", "Acme\u0000Corp", "jobTitle", "Engineer"));
        assertThat(nul.status()).as(nul.body()).isEqualTo(422);
        assertThat(nul.fieldError("company")).isNotBlank();

        Response bell = b.post("/api/v1/applications", map("company", "Acme", "jobTitle", "Engineer", "notes", "ring \u0007"));
        assertThat(bell.status()).as(bell.body()).isEqualTo(422);
        assertThat(bell.fieldError("notes")).isNotBlank();

        Response newline = b.post("/api/v1/applications", map("company", "Acme\nCorp", "jobTitle", "Engineer"));
        assertThat(newline.status()).as(newline.body()).isEqualTo(422);
        assertThat(newline.fieldError("company")).isNotBlank();

        String notes = "First line\nSecond line\twith a tab\r\nThird";
        Response multiLine = b.post("/api/v1/applications", map("company", "Acme", "jobTitle", "Engineer", "notes", notes));
        assertThat(multiLine.status()).as(multiLine.body()).isEqualTo(201);
        assertThat(multiLine.text("notes")).isEqualTo(notes);
    }

    @Test
    void controlCharactersInQueryParametersAreRejected() {
        TestBrowser b = signedInUser();
        Response search = b.get("/api/v1/applications?search=%00");
        assertThat(search.status()).as(search.body()).isEqualTo(400);
        assertThat(search.code()).isEqualTo("INVALID_QUERY");
        Response location = b.get("/api/v1/applications?location=a%01b");
        assertThat(location.status()).as(location.body()).isEqualTo(400);
        Response duplicates = b.get("/api/v1/applications/duplicates?company=%00&jobTitle=Engineer");
        assertThat(duplicates.status()).as(duplicates.body()).isEqualTo(400);
    }

    @Test
    void timeZonesUnknownToPostgresAreRejected() {
        TestBrowser b = signedInUser();
        Response patch = b.patch("/api/v1/me", map("timeZone", "SystemV/EST5"));
        assertThat(patch.status()).as(patch.body()).isEqualTo(422);
        assertThat(patch.fieldError("timeZone")).isNotBlank();
    }

    @Test
    void everyAcceptedTimeZoneIsKnownToPostgres() {
        Set<String> postgres = new HashSet<>(jdbc.queryForList("SELECT name FROM pg_timezone_names", String.class));
        List<String> unknown = ZoneId.getAvailableZoneIds().stream()
                .filter(Validators::isZone)
                .filter(zone -> !postgres.contains(zone))
                .sorted()
                .toList();
        assertThat(unknown).as("zones the API accepts but PostgreSQL can't convert").isEmpty();
    }
}
