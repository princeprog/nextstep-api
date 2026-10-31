package com.nextstep.api.interviews;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.nextstep.api.applications.ApplicationSummary;

public record InterviewResponse(
        String id,
        String applicationId,
        ApplicationSummary application,
        String type,
        String startsAt,
        String timeZone,
        String localDate,
        String localTime,
        Integer durationMinutes,
        String location,
        String meetingUrl,
        String interviewerName,
        String notes,
        String createdAt,
        String updatedAt) {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public static InterviewResponse of(Interview i, ApplicationSummary application) {
        LocalDateTime local = LocalDateTime.ofInstant(i.getStartsAt(), ZoneId.of(i.getTimeZone()));
        return new InterviewResponse(
                i.getId().toString(),
                i.getApplicationId().toString(),
                application,
                i.getType().name(),
                i.getStartsAt().toString(),
                i.getTimeZone(),
                local.toLocalDate().toString(),
                local.toLocalTime().format(HH_MM),
                i.getDurationMinutes(),
                i.getLocation(),
                i.getMeetingUrl(),
                i.getInterviewerName(),
                i.getNotes(),
                instant(i.getCreatedAt()),
                instant(i.getUpdatedAt()));
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}

