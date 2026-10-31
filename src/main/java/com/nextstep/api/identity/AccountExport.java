package com.nextstep.api.identity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import com.nextstep.api.applications.Application;
import com.nextstep.api.interviews.Interview;
import com.nextstep.api.tasks.Task;

/** Everything stored for an account, as a portable JSON document (format nextstep-export/1). */
public record AccountExport(
        String format,
        String exportedAt,
        AccountData account,
        List<ApplicationData> applications,
        List<TaskData> tasks,
        List<InterviewData> interviews) {

    public static final String FORMAT = "nextstep-export/1";

    public record AccountData(String id, String kind, String displayName, String email, String timeZone,
                              String createdAt, String emailVerifiedAt, String demoExpiresAt) {
        static AccountData of(Account a) {
            return new AccountData(a.getId().toString(), a.getKind().name(), a.getDisplayName(), a.getEmail(),
                    a.getTimeZone(), text(a.getCreatedAt()), text(a.getEmailVerifiedAt()), text(a.getDemoExpiresAt()));
        }
    }

    public record ApplicationData(String id, String company, String jobTitle, String status, String jobUrl,
                                  String location, String workArrangement, String employmentType, String salaryMin,
                                  String salaryMax, String salaryCurrency, String salaryPeriod, String appliedDate,
                                  String contactName, String contactEmail, String notes, String archivedAt,
                                  String createdAt, String updatedAt) {
        static ApplicationData of(Application a) {
            return new ApplicationData(a.getId().toString(), a.getCompany(), a.getJobTitle(), name(a.getStatus()),
                    a.getJobUrl(), a.getLocation(), name(a.getWorkArrangement()), name(a.getEmploymentType()),
                    money(a.getSalaryMin()), money(a.getSalaryMax()), a.getSalaryCurrency(), name(a.getSalaryPeriod()),
                    a.getAppliedDate() == null ? null : a.getAppliedDate().toString(), a.getContactName(),
                    a.getContactEmail(), a.getNotes(), text(a.getArchivedAt()), text(a.getCreatedAt()),
                    text(a.getUpdatedAt()));
        }
    }

    public record TaskData(String id, String applicationId, String title, String note, String dueDate, String dueTime,
                           String timeZone, String status, String completedAt, String createdAt, String updatedAt) {
        static TaskData of(Task t) {
            return new TaskData(t.getId().toString(), t.getApplicationId().toString(), t.getTitle(), t.getNote(),
                    t.getDueDate().toString(), t.getDueTime() == null ? null : t.getDueTime().toString(), t.getTimeZone(),
                    name(t.getStatus()), text(t.getCompletedAt()), text(t.getCreatedAt()), text(t.getUpdatedAt()));
        }
    }

    public record InterviewData(String id, String applicationId, String type, String startsAt, String timeZone,
                                Integer durationMinutes, String location, String meetingUrl, String interviewerName,
                                String notes, String createdAt, String updatedAt) {
        static InterviewData of(Interview i) {
            return new InterviewData(i.getId().toString(), i.getApplicationId().toString(), name(i.getType()),
                    text(i.getStartsAt()), i.getTimeZone(), i.getDurationMinutes(), i.getLocation(), i.getMeetingUrl(),
                    i.getInterviewerName(), i.getNotes(), text(i.getCreatedAt()), text(i.getUpdatedAt()));
        }
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
