package com.nextstep.api.applications;

import java.math.BigDecimal;
import java.time.Instant;

import com.nextstep.api.tasks.TaskBrief;

public record ApplicationResponse(
        String id,
        String company,
        String jobTitle,
        String status,
        String jobUrl,
        String location,
        String workArrangement,
        String employmentType,
        String salaryMin,
        String salaryMax,
        String salaryCurrency,
        String salaryPeriod,
        String appliedDate,
        String contactName,
        String contactEmail,
        String notes,
        boolean archived,
        String archivedAt,
        String createdAt,
        String updatedAt,
        TaskBrief nextTask) {

    public static ApplicationResponse of(Application a, TaskBrief nextTask) {
        return new ApplicationResponse(
                a.getId().toString(),
                a.getCompany(),
                a.getJobTitle(),
                a.getStatus().name(),
                a.getJobUrl(),
                a.getLocation(),
                name(a.getWorkArrangement()),
                name(a.getEmploymentType()),
                money(a.getSalaryMin()),
                money(a.getSalaryMax()),
                a.getSalaryCurrency(),
                name(a.getSalaryPeriod()),
                a.getAppliedDate() == null ? null : a.getAppliedDate().toString(),
                a.getContactName(),
                a.getContactEmail(),
                a.getNotes(),
                a.isArchived(),
                instant(a.getArchivedAt()),
                instant(a.getCreatedAt()),
                instant(a.getUpdatedAt()),
                nextTask);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** Money is serialized as a string with two decimals to avoid floating-point errors in clients. */
    static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}

