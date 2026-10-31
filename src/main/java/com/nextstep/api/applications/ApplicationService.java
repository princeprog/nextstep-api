package com.nextstep.api.applications;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nextstep.api.common.ApiException;
import com.nextstep.api.common.FieldErrors;
import com.nextstep.api.common.JsonInput;
import com.nextstep.api.common.NextStepProperties;
import com.nextstep.api.common.PageResponse;
import com.nextstep.api.common.TimeRules;
import com.nextstep.api.common.Validators;
import com.nextstep.api.identity.AccountPrincipal;
import com.nextstep.api.identity.AccountRepository;
import com.nextstep.api.tasks.TaskBrief;
import com.nextstep.api.tasks.TaskQueries;

@Service
public class ApplicationService {

    private final ApplicationRepository applications;
    private final ApplicationQueries queries;
    private final TaskQueries taskQueries;
    private final AccountRepository accounts;
    private final TimeRules time;
    private final NextStepProperties properties;

    public ApplicationService(ApplicationRepository applications, ApplicationQueries queries, TaskQueries taskQueries,
                              AccountRepository accounts, TimeRules time, NextStepProperties properties) {
        this.applications = applications;
        this.queries = queries;
        this.taskQueries = taskQueries;
        this.accounts = accounts;
        this.time = time;
        this.properties = properties;
    }

    @Transactional
    public ApplicationResponse create(AccountPrincipal owner, JsonInput in) {
        if (owner.isDemo() && applications.countByOwnerId(owner.id()) >= properties.getDemo().getMaxApplications()) {
            throw ApiException.conflict("DEMO_LIMIT_REACHED", "This demo has reached its application limit.");
        }
        ZoneId zone = ownerZone(owner);
        Application application = new Application();
        application.setOwnerId(owner.id());
        applyFields(application, in, true);
        ApplicationStatus status = in.present("status")
                ? Validators.enumValue(in, "status", ApplicationStatus.class, true, "Choose a status.")
                : ApplicationStatus.SAVED;
        application.setStatus(status);
        validateRecord(application, in.errors(), zone);
        in.errors().throwIfAny();
        Instant now = time.now();
        application.setCreatedAt(now);
        application.setUpdatedAt(now);
        applications.save(application);
        return toResponse(application);
    }

    @Transactional(readOnly = true)
    public ApplicationResponse get(AccountPrincipal owner, UUID id) {
        return toResponse(owned(owner, id));
    }

    @Transactional
    public ApplicationResponse update(AccountPrincipal owner, UUID id, JsonInput in) {
        ZoneId zone = ownerZone(owner);
        Application application = owned(owner, id);
        if (application.isArchived()) {
            throw ApiException.archived();
        }
        if (in.present("status")) {
            in.errors().add("status", "Change the status with the status action.");
        }
        applyFields(application, in, false);
        validateRecord(application, in.errors(), zone);
        in.errors().throwIfAny();
        application.setUpdatedAt(time.now());
        return toResponse(application);
    }

    @Transactional
    public ApplicationResponse changeStatus(AccountPrincipal owner, UUID id, JsonInput in) {
        ZoneId zone = ownerZone(owner);
        Application application = owned(owner, id);
        if (application.isArchived()) {
            throw ApiException.archived();
        }
        ApplicationStatus status = Validators.enumValue(in, "status", ApplicationStatus.class, true, "Choose a status.");
        LocalDate appliedDate = application.getAppliedDate();
        if (in.present("appliedDate")) {
            appliedDate = Validators.date(in, "appliedDate", false, null);
        }
        in.errors().throwIfAny();
        application.setStatus(status);
        application.setAppliedDate(appliedDate);
        validateRecord(application, in.errors(), zone);
        in.errors().throwIfAny();
        application.setUpdatedAt(time.now());
        return toResponse(application);
    }

    @Transactional
    public ApplicationResponse archive(AccountPrincipal owner, UUID id) {
        Application application = owned(owner, id);
        if (application.isArchived()) {
            throw ApiException.conflict("ALREADY_ARCHIVED", "This application is already archived.");
        }
        Instant now = time.now();
        application.setArchivedAt(now);
        application.setUpdatedAt(now);
        return toResponse(application);
    }

    @Transactional
    public ApplicationResponse restore(AccountPrincipal owner, UUID id) {
        Application application = owned(owner, id);
        if (!application.isArchived()) {
            throw ApiException.conflict("NOT_ARCHIVED", "This application is already active.");
        }
        application.setArchivedAt(null);
        application.setUpdatedAt(time.now());
        return toResponse(application);
    }

    @Transactional(readOnly = true)
    public PageResponse<ApplicationResponse> search(AccountPrincipal owner, ApplicationQueries.Criteria criteria) {
        ApplicationQueries.Result result = queries.search(owner.id(), criteria);
        Map<UUID, Application> byId = applications.findAllById(result.ids()).stream()
                .collect(Collectors.toMap(Application::getId, Function.identity()));
        List<Application> ordered = result.ids().stream().map(byId::get).toList();
        return PageResponse.of(toResponses(ordered), criteria.page(), result.total());
    }

    @Transactional(readOnly = true)
    public long countDuplicates(AccountPrincipal owner, String company, String jobTitle, UUID excludeId) {
        if (company == null || company.isBlank() || jobTitle == null || jobTitle.isBlank()) {
            return 0;
        }
        return applications.countDuplicates(owner.id(), company.strip(), jobTitle.strip(), excludeId);
    }

    /** Loads an application owned by the caller, or fails with 404 regardless of whether the ID exists. */
    public Application owned(AccountPrincipal owner, UUID id) {
        return applications.findByIdAndOwnerId(id, owner.id()).orElseThrow(ApiException::notFound);
    }

    public List<ApplicationResponse> toResponses(List<Application> list) {
        Map<UUID, TaskBrief> next = taskQueries.nextOpenTasks(list.stream().map(Application::getId).toList());
        return list.stream().map(a -> ApplicationResponse.of(a, next.get(a.getId()))).toList();
    }

    private ApplicationResponse toResponse(Application application) {
        return toResponses(List.of(application)).getFirst();
    }

    private ZoneId ownerZone(AccountPrincipal owner) {
        return accounts.findById(owner.id()).map(a -> ZoneId.of(a.getTimeZone())).orElse(ZoneId.of("UTC"));
    }

    /** Applies the fields present in the request (all fields when creating). Explicit null clears optional fields. */
    private void applyFields(Application a, JsonInput in, boolean creating) {
        if (creating || in.present("company")) {
            a.setCompany(Validators.requiredText(in, "company", 160, "Enter a company name."));
        }
        if (creating || in.present("jobTitle")) {
            a.setJobTitle(Validators.requiredText(in, "jobTitle", 160, "Enter a job title."));
        }
        if (creating || in.present("jobUrl")) {
            a.setJobUrl(Validators.optionalHttpUrl(in, "jobUrl"));
        }
        if (creating || in.present("location")) {
            a.setLocation(Validators.optionalLine(in, "location", 160));
        }
        if (creating || in.present("workArrangement")) {
            a.setWorkArrangement(Validators.enumValue(in, "workArrangement", WorkArrangement.class, false, null));
        }
        if (creating || in.present("employmentType")) {
            a.setEmploymentType(Validators.enumValue(in, "employmentType", EmploymentType.class, false, null));
        }
        if (creating || in.present("salaryMin")) {
            a.setSalaryMin(amount(in, "salaryMin"));
        }
        if (creating || in.present("salaryMax")) {
            a.setSalaryMax(amount(in, "salaryMax"));
        }
        if (creating || in.present("salaryCurrency")) {
            a.setSalaryCurrency(currency(in));
        }
        if (creating || in.present("salaryPeriod")) {
            a.setSalaryPeriod(Validators.enumValue(in, "salaryPeriod", SalaryPeriod.class, false, null));
        }
        if (creating || in.present("appliedDate")) {
            a.setAppliedDate(Validators.date(in, "appliedDate", false, null));
        }
        if (creating || in.present("contactName")) {
            a.setContactName(Validators.optionalLine(in, "contactName", 160));
        }
        if (creating || in.present("contactEmail")) {
            a.setContactEmail(Validators.optionalEmail(in, "contactEmail"));
        }
        if (creating || in.present("notes")) {
            a.setNotes(Validators.optionalNotes(in, "notes", 10_000));
        }
    }

    private BigDecimal amount(JsonInput in, String field) {
        BigDecimal value = in.decimal(field);
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            in.errors().add(field, "Enter an amount of 0 or more.");
            return null;
        }
        if (!Validators.isMoney(value)) {
            in.errors().add(field, "Enter an amount with up to 10 digits and 2 decimal places.");
            return null;
        }
        return value;
    }

    private String currency(JsonInput in) {
        String value = Validators.optionalLine(in, "salaryCurrency", 3);
        if (value == null) {
            return null;
        }
        String code = value.toUpperCase(Locale.ROOT);
        try {
            if (code.length() == 3 && Currency.getInstance(code) != null) {
                return code;
            }
        } catch (IllegalArgumentException ex) {
            // reported below
        }
        in.errors().add("salaryCurrency", "Choose a valid ISO currency code, such as PHP or USD.");
        return null;
    }

    /** Rules that depend on more than one field, checked against the complete resulting record. */
    private void validateRecord(Application a, FieldErrors errors, ZoneId ownerZone) {
        boolean anyBound = a.getSalaryMin() != null || a.getSalaryMax() != null;
        if (a.getSalaryMin() != null && a.getSalaryMax() != null && a.getSalaryMin().compareTo(a.getSalaryMax()) > 0) {
            errors.add("salaryMax", "The maximum must be at least the minimum.");
        }
        if (anyBound && a.getSalaryCurrency() == null && !errors.has("salaryCurrency")) {
            errors.add("salaryCurrency", "Choose a currency for the salary range.");
        }
        if (anyBound && a.getSalaryPeriod() == null && !errors.has("salaryPeriod")) {
            errors.add("salaryPeriod", "Choose whether the salary is per hour, month, or year.");
        }
        if (a.getStatus() == ApplicationStatus.APPLIED && a.getAppliedDate() == null && !errors.has("appliedDate")) {
            errors.add("appliedDate", "Enter the date you applied.");
        }
        if (a.getAppliedDate() != null && a.getAppliedDate().isAfter(time.today(ownerZone))) {
            errors.add("appliedDate", "The applied date can't be in the future.");
        }
    }
}

