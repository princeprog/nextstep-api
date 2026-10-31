package com.nextstep.api.applications;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

class SearchAndPaginationTest extends IntegrationTestSupport {

    @Test
    void searchIsCaseInsensitiveAcrossCompanyRoleAndContact() {
        TestBrowser b = signedInUser();
        String byCompany = createApplication(b, "company", "Zephyr Robotics");
        String byTitle = createApplication(b, "jobTitle", "Zephyrine Designer");
        String byContact = createApplication(b, "contactName", "Ana ZEPHYR Reyes");
        String byEmail = createApplication(b, "contactEmail", "hr@zephyr.example.com");
        createApplication(b, "company", "Unrelated");
        createApplication(b, "company", "100% Match_Co");

        Response result = b.get("/api/v1/applications?search=zEpHyR");
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.ids("items")).containsExactlyInAnyOrder(byCompany, byTitle, byContact, byEmail);
        assertThat(b.get("/api/v1/applications?search=0%25%20M").total()).as("LIKE wildcards are literal").isEqualTo(1);
        assertThat(b.get("/api/v1/applications?search=h_Co").total()).isEqualTo(1);
    }

    @Test
    void filtersCombineBeforePaginationAndCountTheWholeSet() {
        TestBrowser b = signedInUser();
        for (int i = 0; i < 23; i++) {
            createApplication(b, "company", "Remote Co " + i, "workArrangement", "REMOTE", "location", "Cebu City",
                    "status", "APPLIED", "appliedDate", "2026-02-" + String.format("%02d", (i % 20) + 1));
        }
        createApplication(b, "company", "Remote Saved", "workArrangement", "REMOTE", "location", "Cebu City");
        createApplication(b, "company", "Onsite", "workArrangement", "ONSITE", "status", "APPLIED", "appliedDate", "2026-02-05");

        String query = "/api/v1/applications?status=APPLIED&workArrangement=REMOTE&location=cebu&pageSize=10";
        Response page1 = b.get(query);
        assertThat(page1.total()).isEqualTo(23);
        assertThat(page1.json().get("totalPages").asInt()).isEqualTo(3);
        assertThat(page1.json().get("items").size()).isEqualTo(10);
        Response page3 = b.get(query + "&page=3");
        assertThat(page3.json().get("items").size()).isEqualTo(3);
        assertThat(b.get(query + "&page=4").json().get("items").size()).isZero();

        Response range = b.get("/api/v1/applications?appliedFrom=2026-02-05&appliedTo=2026-02-06");
        // Feb 5 (i=4 and the onsite record) and Feb 6 (i=5): both bounds are inclusive.
        assertThat(range.total()).as("inclusive date bounds").isEqualTo(3);

        Response dashboard = b.get("/api/v1/dashboard");
        assertThat(dashboard.json().get("statusCounts").get("APPLIED").asLong()).as("counts are not page-limited").isEqualTo(24);
    }

    @Test
    void sortingIsStableAndMissingValuesSortLast() {
        TestBrowser b = signedInUser();
        List<String> sameCompany = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            sameCompany.add(createApplication(b, "company", "Same Name"));
        }
        Set<String> seen = new HashSet<>();
        for (int page = 1; page <= 3; page++) {
            seen.addAll(b.get("/api/v1/applications?sort=company&pageSize=3&page=" + page).ids("items"));
        }
        assertThat(seen).as("no duplicates or gaps across pages").containsExactlyInAnyOrderElementsOf(sameCompany);

        TestBrowser c = signedInUser();
        String noDate = createApplication(c, "company", "No Date");
        String older = createApplication(c, "company", "Older", "status", "APPLIED", "appliedDate", "2026-01-01");
        String newer = createApplication(c, "company", "Newer", "status", "APPLIED", "appliedDate", "2026-02-01");
        assertThat(c.get("/api/v1/applications?sort=appliedDate&direction=desc").ids("items")).containsExactly(newer, older, noDate);
        assertThat(c.get("/api/v1/applications?sort=appliedDate&direction=asc").ids("items")).containsExactly(older, newer, noDate);

        createTask(c, newer, "2030-05-01", null, "UTC");
        createTask(c, older, "2030-04-01", null, "UTC");
        String completed = createTask(c, noDate, "2030-01-01", null, "UTC");
        c.post("/api/v1/tasks/" + completed + "/complete", null);
        assertThat(c.get("/api/v1/applications?sort=nextTaskDue").ids("items"))
                .as("completed tasks are ignored; no open task sorts last").containsExactly(older, newer, noDate);
        assertThat(c.get("/api/v1/applications?sort=nextTaskDue&direction=desc").ids("items")).containsExactly(newer, older, noDate);
        assertThat(c.get("/api/v1/applications?sort=company").ids("items")).containsExactly(newer, noDate, older);
    }

    @Test
    void invalidListOptionsAreRejected() {
        TestBrowser b = signedInUser();
        for (String query : new String[] {"pageSize=101", "page=0", "sort=salary", "direction=up", "status=HIRED",
                "archive=deleted", "appliedFrom=2026-03-01&appliedTo=2026-02-01", "appliedFrom=2026-02-30"}) {
            Response response = b.get("/api/v1/applications?" + query);
            assertThat(response.status()).as(query).isEqualTo(400);
            assertThat(response.code()).isEqualTo("INVALID_QUERY");
        }
        Response empty = b.get("/api/v1/applications");
        assertThat(empty.total()).isZero();
        assertThat(empty.json().get("totalPages").asInt()).isZero();
        assertThat(b.get("/api/v1/tasks?view=soon").status()).isEqualTo(400);
        assertThat(b.get("/api/v1/interviews?view=soon").status()).isEqualTo(400);
        assertThat(b.post("/api/v1/tasks", map()).status()).isEqualTo(422);
    }
}

