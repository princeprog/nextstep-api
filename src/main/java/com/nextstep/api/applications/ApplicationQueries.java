package com.nextstep.api.applications;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.nextstep.api.common.PageParams;

/** Owner-scoped search with filtering, sorting, and counting done in the database before pagination. */
@Component
public class ApplicationQueries {

    public enum Sort { updatedAt, company, appliedDate, nextTaskDue }

    public record Criteria(
            String search,
            List<ApplicationStatus> statuses,
            WorkArrangement workArrangement,
            EmploymentType employmentType,
            String location,
            LocalDate appliedFrom,
            LocalDate appliedTo,
            boolean archived,
            Sort sort,
            boolean ascending,
            PageParams page) {
    }

    public record Result(List<UUID> ids, long total) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ApplicationQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Result search(UUID ownerId, Criteria criteria) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("owner", ownerId);
        StringBuilder where = new StringBuilder(" FROM applications a WHERE a.owner_id = :owner AND a.archived_at IS ")
                .append(criteria.archived() ? "NOT NULL" : "NULL");
        if (criteria.search() != null) {
            where.append(" AND (a.company ILIKE :q ESCAPE '!' OR a.job_title ILIKE :q ESCAPE '!'"
                    + " OR a.contact_name ILIKE :q ESCAPE '!' OR a.contact_email ILIKE :q ESCAPE '!')");
            params.addValue("q", "%" + escapeLike(criteria.search()) + "%");
        }
        if (criteria.statuses() != null && !criteria.statuses().isEmpty()) {
            where.append(" AND a.status IN (:statuses)");
            params.addValue("statuses", criteria.statuses().stream().map(Enum::name).toList());
        }
        if (criteria.workArrangement() != null) {
            where.append(" AND a.work_arrangement = :workArrangement");
            params.addValue("workArrangement", criteria.workArrangement().name());
        }
        if (criteria.employmentType() != null) {
            where.append(" AND a.employment_type = :employmentType");
            params.addValue("employmentType", criteria.employmentType().name());
        }
        if (criteria.location() != null) {
            where.append(" AND a.location ILIKE :location ESCAPE '!'");
            params.addValue("location", "%" + escapeLike(criteria.location()) + "%");
        }
        if (criteria.appliedFrom() != null) {
            where.append(" AND a.applied_date >= :appliedFrom");
            params.addValue("appliedFrom", criteria.appliedFrom());
        }
        if (criteria.appliedTo() != null) {
            where.append(" AND a.applied_date <= :appliedTo");
            params.addValue("appliedTo", criteria.appliedTo());
        }
        Long total = jdbc.queryForObject("SELECT count(*)" + where, params, Long.class);
        String direction = criteria.ascending() ? "ASC" : "DESC";
        String order = switch (criteria.sort()) {
            case updatedAt -> " ORDER BY a.updated_at " + direction + ", a.id ASC";
            case company -> " ORDER BY lower(a.company) " + direction + ", a.id ASC";
            case appliedDate -> " ORDER BY a.applied_date " + direction + " NULLS LAST, a.id ASC";
            case nextTaskDue -> " ORDER BY (SELECT min(t.deadline_at) FROM tasks t WHERE t.application_id = a.id"
                    + " AND t.status = 'OPEN') " + direction + " NULLS LAST, a.id ASC";
        };
        params.addValue("limit", criteria.page().pageSize()).addValue("offset", criteria.page().offset());
        List<UUID> ids = jdbc.query("SELECT a.id" + where + order + " LIMIT :limit OFFSET :offset", params,
                (rs, row) -> rs.getObject(1, UUID.class));
        return new Result(ids, total == null ? 0 : total);
    }

    static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}

