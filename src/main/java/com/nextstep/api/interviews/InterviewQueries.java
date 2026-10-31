package com.nextstep.api.interviews;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.nextstep.api.common.PageParams;

@Component
public class InterviewQueries {

    /** next7days is the dashboard window: now inclusive to now + 168 hours exclusive. */
    public enum View { all, future, next7days, past }

    public static final Duration UPCOMING_WINDOW = Duration.ofHours(168);

    public record Result(List<UUID> ids, long total) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public InterviewQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Result listActive(UUID ownerId, View view, PageParams page, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .addValue("windowEnd", OffsetDateTime.ofInstant(now.plus(UPCOMING_WINDOW), ZoneOffset.UTC));
        String condition = switch (view) {
            case all -> "TRUE";
            case future -> "i.starts_at >= CAST(:now AS timestamptz)";
            case next7days -> "i.starts_at >= CAST(:now AS timestamptz) AND i.starts_at < CAST(:windowEnd AS timestamptz)";
            case past -> "i.starts_at < CAST(:now AS timestamptz)";
        };
        String order = switch (view) {
            case future, next7days -> " ORDER BY i.starts_at ASC, i.id ASC";
            case all, past -> " ORDER BY i.starts_at DESC, i.id ASC";
        };
        String from = " FROM interviews i JOIN applications a ON a.id = i.application_id"
                + " WHERE i.owner_id = :owner AND a.archived_at IS NULL AND " + condition;
        Long total = jdbc.queryForObject("SELECT count(*)" + from, params, Long.class);
        params.addValue("limit", page.pageSize()).addValue("offset", page.offset());
        List<UUID> ids = jdbc.query("SELECT i.id" + from + order + " LIMIT :limit OFFSET :offset", params,
                (rs, row) -> rs.getObject(1, UUID.class));
        return new Result(ids, total == null ? 0 : total);
    }

    public Result listForApplication(UUID ownerId, UUID applicationId, PageParams page) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("application", applicationId);
        String from = " FROM interviews i WHERE i.owner_id = :owner AND i.application_id = :application";
        Long total = jdbc.queryForObject("SELECT count(*)" + from, params, Long.class);
        params.addValue("limit", page.pageSize()).addValue("offset", page.offset());
        List<UUID> ids = jdbc.query("SELECT i.id" + from + " ORDER BY i.starts_at DESC, i.id ASC LIMIT :limit OFFSET :offset",
                params, (rs, row) -> rs.getObject(1, UUID.class));
        return new Result(ids, total == null ? 0 : total);
    }
}

