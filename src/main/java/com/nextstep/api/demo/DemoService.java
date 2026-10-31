package com.nextstep.api.demo;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nextstep.api.applications.Application;
import com.nextstep.api.applications.ApplicationRepository;
import com.nextstep.api.applications.ApplicationStatus;
import com.nextstep.api.applications.EmploymentType;
import com.nextstep.api.applications.SalaryPeriod;
import com.nextstep.api.applications.WorkArrangement;
import com.nextstep.api.common.NextStepProperties;
import com.nextstep.api.common.TimeRules;
import com.nextstep.api.identity.Account;
import com.nextstep.api.identity.AccountKind;
import com.nextstep.api.identity.AccountRepository;
import com.nextstep.api.identity.SessionSupport;
import com.nextstep.api.interviews.Interview;
import com.nextstep.api.interviews.InterviewRepository;
import com.nextstep.api.interviews.InterviewType;
import com.nextstep.api.tasks.Task;
import com.nextstep.api.tasks.TaskRepository;
import com.nextstep.api.tasks.TaskStatus;

/** Creates isolated, temporary demo workspaces filled with fictional records, and removes expired ones. */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    private final AccountRepository accounts;
    private final ApplicationRepository applications;
    private final TaskRepository tasks;
    private final InterviewRepository interviews;
    private final SessionSupport sessions;
    private final TimeRules time;
    private final NextStepProperties properties;

    public DemoService(AccountRepository accounts, ApplicationRepository applications, TaskRepository tasks,
                       InterviewRepository interviews, SessionSupport sessions, TimeRules time,
                       NextStepProperties properties) {
        this.accounts = accounts;
        this.applications = applications;
        this.tasks = tasks;
        this.interviews = interviews;
        this.sessions = sessions;
        this.time = time;
        this.properties = properties;
    }

    @Transactional
    public Account createDemo(ZoneId requestedZone) {
        ZoneId zone = requestedZone == null ? ZoneId.of("UTC") : requestedZone;
        Instant now = time.now();
        Account account = new Account();
        account.setKind(AccountKind.DEMO);
        account.setDisplayName("Demo visitor");
        account.setTimeZone(zone.getId());
        account.setDemoExpiresAt(now.plus(Duration.ofHours(properties.getDemo().getTtlHours())));
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        accounts.saveAndFlush(account);
        seed(account.getId(), zone, now);
        return account;
    }

    /** Deletes expired demo workspaces and their sessions. Normal accounts are never selected. */
    @Transactional
    public int cleanupExpired() {
        List<UUID> expired = accounts.findExpiredDemoIds(time.now());
        if (expired.isEmpty()) {
            return 0;
        }
        sessions.deleteAllSessions(expired);
        accounts.deleteAllByIdInBatch(expired);
        log.info("Removed {} expired demo workspace(s)", expired.size());
        return expired.size();
    }

    private void seed(UUID owner, ZoneId zone, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, zone);

        Application harbor = app(owner, now, "Harbor Labs", "Frontend Developer", ApplicationStatus.INTERVIEW,
                today.minusDays(12), WorkArrangement.REMOTE, EmploymentType.FULL_TIME, "Remote (Philippines)",
                "Mara Santos", "mara.santos@example.com",
                "Recruiter call went well.\nPrepare a short walkthrough of a recent UI project.",
                "60000", "80000", "PHP", SalaryPeriod.MONTH, "harbor-labs-frontend");
        Application northwind = app(owner, now, "Northwind Studio", "Product Designer", ApplicationStatus.APPLIED,
                today.minusDays(5), WorkArrangement.HYBRID, EmploymentType.FULL_TIME, "Cebu City",
                "Paolo Reyes", "paolo.reyes@example.com", "Referred by a former classmate.",
                null, null, null, null, "northwind-product-designer");
        Application lumen = app(owner, now, "Lumen Analytics", "Data Analyst", ApplicationStatus.SAVED,
                null, WorkArrangement.REMOTE, EmploymentType.CONTRACT, null,
                null, null, "Six-month contract. Check whether SQL take-home is required.",
                null, null, null, null, "lumen-data-analyst");
        Application bluepeak = app(owner, now, "Bluepeak Health", "UX Researcher", ApplicationStatus.OFFER,
                today.minusDays(30), WorkArrangement.ONSITE, EmploymentType.FULL_TIME, "Makati City",
                "Lea Fernandez", "lea.fernandez@example.com", "Offer received. Compare benefits before replying.",
                "1200000", "1400000", "PHP", SalaryPeriod.YEAR, "bluepeak-ux-researcher");
        app(owner, now, "Cedar & Pine Co.", "Marketing Coordinator", ApplicationStatus.REJECTED,
                today.minusDays(40), WorkArrangement.HYBRID, EmploymentType.FULL_TIME, "Quezon City",
                null, null, "Polite rejection email. Asked to keep in touch.",
                null, null, null, null, "cedar-pine-marketing");
        app(owner, now, "Orbit Logistics", "Operations Analyst", ApplicationStatus.WITHDRAWN,
                today.minusDays(20), WorkArrangement.ONSITE, EmploymentType.FULL_TIME, "Pasig City",
                null, null, "Withdrew after learning the role required relocation.",
                null, null, null, null, "orbit-operations-analyst");
        Application tala = app(owner, now, "Tala Fintech", "Junior Backend Engineer", ApplicationStatus.APPLIED,
                today.minusDays(2), WorkArrangement.REMOTE, EmploymentType.FULL_TIME, null,
                "Jun Navarro", "jun.navarro@example.com", null,
                null, null, null, null, "tala-backend-engineer");
        app(owner, now, "Kestrel Games", "QA Tester", ApplicationStatus.SAVED,
                null, WorkArrangement.ONSITE, EmploymentType.INTERNSHIP, "Davao City",
                null, null, null, null, null, null, null, "kestrel-qa-tester");

        task(owner, harbor, zone, now, "Send a thank-you note to Mara", today.minusDays(1), null, false);
        task(owner, harbor, zone, now, "Prepare portfolio walkthrough", today.plusDays(2), LocalTime.of(18, 0), false);
        task(owner, northwind, zone, now, "Follow up on application status", today, null, false);
        task(owner, tala, zone, now, "Finish the take-home exercise", today.plusDays(5), null, false);
        task(owner, lumen, zone, now, "Tailor résumé for the analytics role", today.plusDays(10), null, false);
        task(owner, bluepeak, zone, now, "Review offer details", today.minusDays(3), null, true);

        interview(owner, harbor, zone, now, InterviewType.TECHNICAL, today.plusDays(2), LocalTime.of(10, 0), 60,
                "https://example.com/meet/harbor-labs", "Mara Santos");
        interview(owner, bluepeak, zone, now, InterviewType.FINAL, today.minusDays(7), LocalTime.of(14, 0), 45,
                null, "Lea Fernandez");
        interview(owner, northwind, zone, now, InterviewType.PHONE_SCREEN, today.plusDays(9), LocalTime.of(11, 0), 30,
                null, "Paolo Reyes");
    }

    private Application app(UUID owner, Instant now, String company, String title, ApplicationStatus status,
                            LocalDate appliedDate, WorkArrangement arrangement, EmploymentType type, String location,
                            String contactName, String contactEmail, String notes, String salaryMin, String salaryMax,
                            String currency, SalaryPeriod period, String slug) {
        Application a = new Application();
        a.setOwnerId(owner);
        a.setCompany(company);
        a.setJobTitle(title);
        a.setStatus(status);
        a.setAppliedDate(appliedDate);
        a.setWorkArrangement(arrangement);
        a.setEmploymentType(type);
        a.setLocation(location);
        a.setContactName(contactName);
        a.setContactEmail(contactEmail);
        a.setNotes(notes);
        a.setSalaryMin(salaryMin == null ? null : new BigDecimal(salaryMin));
        a.setSalaryMax(salaryMax == null ? null : new BigDecimal(salaryMax));
        a.setSalaryCurrency(currency);
        a.setSalaryPeriod(period);
        a.setJobUrl("https://example.com/jobs/" + slug);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return applications.save(a);
    }

    private void task(UUID owner, Application parent, ZoneId zone, Instant now, String title, LocalDate dueDate,
                      LocalTime dueTime, boolean completed) {
        Task t = new Task();
        t.setOwnerId(owner);
        t.setApplicationId(parent.getId());
        t.setTitle(title);
        t.setDueDate(dueDate);
        t.setTimeZone(zone.getId());
        if (dueTime != null && TimeRules.check(LocalDateTime.of(dueDate, dueTime), zone) == TimeRules.Resolution.VALID) {
            t.setDueTime(dueTime);
            t.setDueAt(ZonedDateTime.of(dueDate, dueTime, zone).toInstant());
        }
        t.setDeadlineAt(TimeRules.deadline(dueDate, t.getDueAt(), zone));
        t.setStatus(completed ? TaskStatus.COMPLETED : TaskStatus.OPEN);
        t.setCompletedAt(completed ? now : null);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        tasks.save(t);
    }

    private void interview(UUID owner, Application parent, ZoneId zone, Instant now, InterviewType type, LocalDate date,
                           LocalTime start, int minutes, String meetingUrl, String interviewer) {
        Interview i = new Interview();
        i.setOwnerId(owner);
        i.setApplicationId(parent.getId());
        i.setType(type);
        // Seed times are chosen by us; a DST-adjusted instant is acceptable for fictional data.
        i.setStartsAt(ZonedDateTime.of(date, start, zone).toInstant());
        i.setTimeZone(zone.getId());
        i.setDurationMinutes(minutes);
        i.setMeetingUrl(meetingUrl);
        i.setInterviewerName(interviewer);
        i.setCreatedAt(now);
        i.setUpdatedAt(now);
        interviews.save(i);
    }
}

