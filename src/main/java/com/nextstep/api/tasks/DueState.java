package com.nextstep.api.tasks;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Due state of a task in its own stored time zone.
 * <ul>
 * <li>Date-only tasks are overdue once their local date is before today.</li>
 * <li>Timed tasks are overdue only when now is strictly after the due instant.</li>
 * <li>Upcoming covers today through the next six local dates.</li>
 * </ul>
 */
public enum DueState {
    OVERDUE, DUE_TODAY, UPCOMING, LATER, COMPLETED;

    public static DueState of(Task task, Instant now) {
        if (task.getStatus() == TaskStatus.COMPLETED) {
            return COMPLETED;
        }
        ZoneId zone = ZoneId.of(task.getTimeZone());
        LocalDate today = LocalDate.ofInstant(now, zone);
        boolean overdue = task.getDueTime() == null
                ? task.getDueDate().isBefore(today)
                : now.isAfter(task.getDueAt());
        if (overdue) {
            return OVERDUE;
        }
        if (task.getDueDate().equals(today)) {
            return DUE_TODAY;
        }
        return task.getDueDate().isAfter(today.plusDays(6)) ? LATER : UPCOMING;
    }
}

