package com.nextstep.api.tasks;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import com.nextstep.api.applications.ApplicationSummary;

public record TaskResponse(
        String id,
        String applicationId,
        ApplicationSummary application,
        String title,
        String note,
        String dueDate,
        String dueTime,
        String timeZone,
        String dueAt,
        String status,
        String completedAt,
        String dueState,
        String createdAt,
        String updatedAt) {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public static TaskResponse of(Task t, ApplicationSummary application, Instant now) {
        return new TaskResponse(
                t.getId().toString(),
                t.getApplicationId().toString(),
                application,
                t.getTitle(),
                t.getNote(),
                t.getDueDate().toString(),
                formatTime(t.getDueTime()),
                t.getTimeZone(),
                instant(t.getDueAt()),
                t.getStatus().name(),
                instant(t.getCompletedAt()),
                DueState.of(t, now).name(),
                instant(t.getCreatedAt()),
                instant(t.getUpdatedAt()));
    }

    static String formatTime(LocalTime time) {
        return time == null ? null : time.format(HH_MM);
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}

