package com.nextstep.api.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Central time rules from the Business Rules note. Local times are resolved strictly: a local time that does not
 * exist (DST gap) or exists twice (DST overlap) is rejected and never shifted silently.
 */
@Component
public class TimeRules {

    public enum Resolution { VALID, NONEXISTENT, AMBIGUOUS }

    private final Clock clock;

    public TimeRules(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today(ZoneId zone) {
        return LocalDate.ofInstant(now(), zone);
    }

    public static Resolution check(LocalDateTime local, ZoneId zone) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            return Resolution.NONEXISTENT;
        }
        return offsets.size() == 1 ? Resolution.VALID : Resolution.AMBIGUOUS;
    }

    /** Resolves a local date and time in a zone, adding a field error to the given field when it is not unique. */
    public static Instant resolveStrict(LocalDate date, LocalTime time, ZoneId zone, FieldErrors errors, String field) {
        LocalDateTime local = LocalDateTime.of(date, time);
        switch (check(local, zone)) {
            case NONEXISTENT -> {
                errors.add(field, "That time doesn't exist in " + zone.getId()
                        + " because of a daylight-saving change. Choose another time.");
                return null;
            }
            case AMBIGUOUS -> {
                errors.add(field, "That time happens twice in " + zone.getId()
                        + " because of a daylight-saving change. Choose another time.");
                return null;
            }
            default -> {
                return ZonedDateTime.ofStrict(local, zone.getRules().getOffset(local), zone).toInstant();
            }
        }
    }

    /** Effective deadline used for ordering: the due instant, or the start of the following local day. */
    public static Instant deadline(LocalDate dueDate, Instant dueAt, ZoneId zone) {
        if (dueAt != null) {
            return dueAt;
        }
        return dueDate.plusDays(1).atStartOfDay(zone).toInstant();
    }
}

