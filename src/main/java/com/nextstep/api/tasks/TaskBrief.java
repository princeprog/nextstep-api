package com.nextstep.api.tasks;

import java.time.Instant;

/** Compact view of an application's next open task. */
public record TaskBrief(String id, String title, String dueDate, String dueTime, String timeZone, String dueAt,
                        String dueState) {

    public static TaskBrief of(Task task, Instant now) {
        return new TaskBrief(task.getId().toString(), task.getTitle(), task.getDueDate().toString(),
                TaskResponse.formatTime(task.getDueTime()), task.getTimeZone(),
                task.getDueAt() == null ? null : task.getDueAt().toString(), DueState.of(task, now).name());
    }
}

