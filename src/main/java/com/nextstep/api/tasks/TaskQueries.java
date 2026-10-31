package com.nextstep.api.tasks;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.nextstep.api.common.PageParams;
import com.nextstep.api.common.TimeRules;

/** Task list queries. Due-state SQL mirrors {@link DueState} so lists, previews, and counts agree. */
@Component
public class TaskQueries {

    public enum View { all, open, completed, overdue, upcoming }

    public record Result(List<UUID> ids, long total) {
    }

    static final String NOW = "CAST(:now AS timestamptz)";
    static final String OVERDUE = "((t.due_time IS NULL AND " + NOW + " >= t.deadline_at)"
            + " OR (t.due_time IS NOT NULL AND " + NOW + " > t.deadline_at))";
    static final String UPCOMING = "t.status = 'OPEN' AND NOT " + OVERDUE
            + " AND t.due_date <= CAST((" + NOW + " AT TIME ZONE t.time_zone) AS date) + 6";

    private final NamedParameterJdbcTemplate jdbc;
    private final TaskRepository tasks;
    private final TimeRules time;

    public TaskQueries(NamedParameterJdbcTemplate jdbc, TaskRepository tasks, TimeRules time) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.time = time;
    }

    /** Tasks whose parent application is active, filtered by view, ordered by effective deadline then ID. */
    public Result listActive(UUID ownerId, View view, PageParams page, Instant now) {
        MapSqlParameterSource params = params(ownerId, now);
        String from = " FROM tasks t JOIN applications a ON a.id = t.application_id"
                + " WHERE t.owner_id = :owner AND a.archived_at IS NULL AND " + condition(view);
        Long total = jdbc.queryForObject("SELECT count(*)" + from, params, Long.class);
        params.addValue("limit", page.pageSize()).addValue("offset", page.offset());
        List<UUID> ids = jdbc.query("SELECT t.id" + from + " ORDER BY t.deadline_at ASC, t.id ASC LIMIT :limit OFFSET :offset",
                params, (rs, row) -> rs.getObject(1, UUID.class));
        return new Result(ids, total == null ? 0 : total);
    }

    public Result listForApplication(UUID ownerId, UUID applicationId, PageParams page) {
        MapSqlParameterSource params = params(ownerId, time.now()).addValue("application", applicationId);
        String from = " FROM tasks t WHERE t.owner_id = :owner AND t.application_id = :application";
        Long total = jdbc.queryForObject("SELECT count(*)" + from, params, Long.class);
        params.addValue("limit", page.pageSize()).addValue("offset", page.offset());
        List<UUID> ids = jdbc.query("SELECT t.id" + from + " ORDER BY t.deadline_at ASC, t.id ASC LIMIT :limit OFFSET :offset",
                params, (rs, row) -> rs.getObject(1, UUID.class));
        return new Result(ids, total == null ? 0 : total);
    }

    /** The earliest open task for each application. */
    public Map<UUID, TaskBrief> nextOpenTasks(Collection<UUID> applicationIds) {
        Map<UUID, TaskBrief> result = new HashMap<>();
        if (applicationIds.isEmpty()) {
            return result;
        }
        List<UUID> ids = jdbc.query("SELECT DISTINCT ON (t.application_id) t.id FROM tasks t"
                        + " WHERE t.application_id IN (:ids) AND t.status = 'OPEN'"
                        + " ORDER BY t.application_id, t.deadline_at ASC, t.id ASC",
                new MapSqlParameterSource("ids", applicationIds), (rs, row) -> rs.getObject(1, UUID.class));
        Instant now = time.now();
        for (Task task : tasks.findAllById(ids)) {
            result.put(task.getApplicationId(), TaskBrief.of(task, now));
        }
        return result;
    }

    static String condition(View view) {
        return switch (view) {
            case all -> "TRUE";
            case open -> "t.status = 'OPEN'";
            case completed -> "t.status = 'COMPLETED'";
            case overdue -> "t.status = 'OPEN' AND " + OVERDUE;
            case upcoming -> UPCOMING;
        };
    }

    static MapSqlParameterSource params(UUID ownerId, Instant now) {
        return new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }
}

