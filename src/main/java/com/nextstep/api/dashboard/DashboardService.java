package com.nextstep.api.dashboard;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.nextstep.api.applications.ApplicationStatus;
import com.nextstep.api.common.PageParams;
import com.nextstep.api.common.TimeRules;
import com.nextstep.api.identity.AccountPrincipal;
import com.nextstep.api.interviews.InterviewQueries;
import com.nextstep.api.interviews.InterviewService;
import com.nextstep.api.tasks.TaskQueries;
import com.nextstep.api.tasks.TaskService;

/**
 * Builds the whole dashboard from one consistent snapshot. Counts cover every active owned record, not a page.
 */
@Service
public class DashboardService {

    static final int PREVIEW = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final TaskQueries taskQueries;
    private final TaskService taskService;
    private final InterviewQueries interviewQueries;
    private final InterviewService interviewService;
    private final TimeRules time;

    public DashboardService(NamedParameterJdbcTemplate jdbc, TaskQueries taskQueries, TaskService taskService,
                            InterviewQueries interviewQueries, InterviewService interviewService, TimeRules time) {
        this.jdbc = jdbc;
        this.taskQueries = taskQueries;
        this.taskService = taskService;
        this.interviewQueries = interviewQueries;
        this.interviewService = interviewService;
        this.time = time;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardResponse build(AccountPrincipal owner) {
        Instant now = time.now();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            counts.put(status.name(), 0L);
        }
        jdbc.query("SELECT status, count(*) FROM applications WHERE owner_id = :owner AND archived_at IS NULL GROUP BY status",
                new MapSqlParameterSource("owner", owner.id()),
                rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                });
        PageParams preview = new PageParams(1, PREVIEW);
        TaskQueries.Result overdue = taskQueries.listActive(owner.id(), TaskQueries.View.overdue, preview, now);
        TaskQueries.Result upcoming = taskQueries.listActive(owner.id(), TaskQueries.View.upcoming, preview, now);
        InterviewQueries.Result interviews = interviewQueries.listActive(owner.id(), InterviewQueries.View.next7days, preview, now);
        return new DashboardResponse(
                counts,
                new DashboardResponse.Section<>(taskService.load(overdue.ids()), overdue.total()),
                new DashboardResponse.Section<>(taskService.load(upcoming.ids()), upcoming.total()),
                new DashboardResponse.Section<>(interviewService.load(interviews.ids()), interviews.total()));
    }
}

