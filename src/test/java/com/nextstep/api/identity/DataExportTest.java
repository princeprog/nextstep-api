package com.nextstep.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

import tools.jackson.databind.JsonNode;

class DataExportTest extends IntegrationTestSupport {

    @Test
    void exportHoldsEveryOwnedRecordAndNothingElse() {
        TestBrowser owner = signedInUser("Asia/Manila");
        String email = owner.get("/api/v1/me").text("email");
        String applicationId = createApplication(owner, "notes", "Line one\nLine two", "salaryMin", "50000",
                "salaryMax", "60000", "salaryCurrency", "PHP", "salaryPeriod", "MONTH");
        createTask(owner, applicationId, "2030-01-01", "09:30", "Asia/Manila");
        createInterview(owner, applicationId, "2030-01-02", "10:00", "Asia/Manila");
        String archivedId = createApplication(owner, "company", "Archived Co");
        assertThat(owner.post("/api/v1/applications/" + archivedId + "/archive", null).status()).isEqualTo(200);
        TestBrowser other = signedInUser();
        createApplication(other, "company", "Someone Else Inc");

        Response export = owner.get("/api/v1/me/export");
        assertThat(export.status()).isEqualTo(200);
        assertThat(export.headers().firstValue("content-disposition"))
                .hasValueSatisfying(value -> assertThat(value).startsWith("attachment").contains("nextstep-export-"));
        assertThat(export.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));

        JsonNode json = export.json();
        assertThat(json.get("format").asString()).isEqualTo("nextstep-export/1");
        assertThat(json.get("account").get("email").asString()).isEqualTo(email);
        assertThat(json.get("applications")).hasSize(2);
        assertThat(json.get("tasks")).hasSize(1);
        assertThat(json.get("interviews")).hasSize(1);
        JsonNode first = json.get("applications").get(0);
        assertThat(first.get("notes").asString()).isEqualTo("Line one\nLine two");
        assertThat(first.get("salaryMin").asString()).isEqualTo("50000.00");
        assertThat(json.get("applications").get(1).get("archivedAt").isNull()).isFalse();
        assertThat(json.get("tasks").get(0).get("dueTime").asString()).isEqualTo("09:30");
        assertThat(export.body()).doesNotContain("Someone Else Inc").doesNotContainIgnoringCase("password");
    }

    @Test
    void demoWorkspacesCanExportTheirFictionalData() {
        TestBrowser demo = browser();
        demo.refreshCsrf();
        assertThat(demo.post("/api/v1/auth/demo", null).status()).isEqualTo(201);
        Response export = demo.get("/api/v1/me/export");
        assertThat(export.status()).isEqualTo(200);
        assertThat(export.json().get("applications")).hasSize(8);
        assertThat(export.json().get("account").get("email").isNull()).isTrue();
    }
}
