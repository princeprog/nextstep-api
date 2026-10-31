package com.nextstep.api.interviews;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nextstep.api.applications.Application;
import com.nextstep.api.applications.ApplicationRepository;
import com.nextstep.api.applications.ApplicationSummary;
import com.nextstep.api.common.ApiException;
import com.nextstep.api.common.JsonInput;
import com.nextstep.api.common.NextStepProperties;
import com.nextstep.api.common.PageParams;
import com.nextstep.api.common.PageResponse;
import com.nextstep.api.common.TimeRules;
import com.nextstep.api.common.Validators;
import com.nextstep.api.identity.AccountPrincipal;

@Service
public class InterviewService {

    private final InterviewRepository interviews;
    private final InterviewQueries queries;
    private final ApplicationRepository applications;
    private final TimeRules time;
    private final NextStepProperties properties;

    public InterviewService(InterviewRepository interviews, InterviewQueries queries, ApplicationRepository applications,
                            TimeRules time, NextStepProperties properties) {
        this.interviews = interviews;
        this.queries = queries;
        this.applications = applications;
        this.time = time;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PageResponse<InterviewResponse> list(AccountPrincipal owner, InterviewQueries.View view, PageParams page) {
        InterviewQueries.Result result = queries.listActive(owner.id(), view, page, time.now());
        return PageResponse.of(load(result.ids()), page, result.total());
    }

    @Transactional(readOnly = true)
    public PageResponse<InterviewResponse> listForApplication(AccountPrincipal owner, UUID applicationId, PageParams page) {
        applications.findByIdAndOwnerId(applicationId, owner.id()).orElseThrow(ApiException::notFound);
        InterviewQueries.Result result = queries.listForApplication(owner.id(), applicationId, page);
        return PageResponse.of(load(result.ids()), page, result.total());
    }

    @Transactional
    public InterviewResponse create(AccountPrincipal owner, JsonInput in) {
        UUID applicationId = Validators.uuid(in, "applicationId", "Choose an application.");
        if (applicationId != null) {
            Application parent = applications.findByIdAndOwnerId(applicationId, owner.id()).orElseThrow(ApiException::notFound);
            if (parent.isArchived()) {
                throw ApiException.archived();
            }
        }
        if (owner.isDemo() && interviews.countByOwnerId(owner.id()) >= properties.getDemo().getMaxInterviews()) {
            throw ApiException.conflict("DEMO_LIMIT_REACHED", "This demo has reached its interview limit.");
        }
        if (!owner.isDemo() && interviews.countByOwnerId(owner.id()) >= properties.getAccounts().getMaxInterviews()) {
            throw ApiException.conflict("ACCOUNT_LIMIT_REACHED", "This account has reached its limit of "
                    + properties.getAccounts().getMaxInterviews() + " interviews. Delete some to add more.");
        }
        Interview interview = new Interview();
        interview.setOwnerId(owner.id());
        interview.setApplicationId(applicationId);
        interview.setType(Validators.enumValue(in, "type", InterviewType.class, true, "Choose an interview type."));
        LocalDate date = Validators.date(in, "localDate", true, "Choose a date.");
        LocalTime localTime = Validators.time(in, "localTime", true, "Choose a start time.");
        ZoneId zone = Validators.zone(in, "timeZone", true);
        applyDetails(interview, in, true);
        in.errors().throwIfAny();
        applyStart(interview, date, localTime, zone, in);
        Instant now = time.now();
        interview.setCreatedAt(now);
        interview.setUpdatedAt(now);
        interviews.save(interview);
        return load(List.of(interview.getId()), List.of(interview)).getFirst();
    }

    @Transactional(readOnly = true)
    public InterviewResponse get(AccountPrincipal owner, UUID id) {
        Interview interview = owned(owner, id);
        return load(List.of(id), List.of(interview)).getFirst();
    }

    @Transactional
    public InterviewResponse update(AccountPrincipal owner, UUID id, JsonInput in) {
        Interview interview = owned(owner, id);
        requireActiveParent(interview);
        if (in.present("applicationId")) {
            in.errors().add("applicationId", "Interviews stay with their application.");
        }
        if (in.present("type")) {
            interview.setType(Validators.enumValue(in, "type", InterviewType.class, true, "Choose an interview type."));
        }
        boolean anyStart = in.present("localDate") || in.present("localTime") || in.present("timeZone");
        boolean allStart = in.present("localDate") && in.present("localTime") && in.present("timeZone");
        LocalDate date = null;
        LocalTime localTime = null;
        ZoneId zone = null;
        if (anyStart && !allStart) {
            String message = "Send the date, start time, and time zone together.";
            for (String field : List.of("localDate", "localTime", "timeZone")) {
                if (!in.present(field)) {
                    in.errors().add(field, message);
                }
            }
        } else if (allStart) {
            date = Validators.date(in, "localDate", true, "Choose a date.");
            localTime = Validators.time(in, "localTime", true, "Choose a start time.");
            zone = Validators.zone(in, "timeZone", true);
        }
        applyDetails(interview, in, false);
        in.errors().throwIfAny();
        if (allStart) {
            applyStart(interview, date, localTime, zone, in);
        }
        interview.setUpdatedAt(time.now());
        return load(List.of(id), List.of(interview)).getFirst();
    }

    @Transactional
    public void delete(AccountPrincipal owner, UUID id) {
        Interview interview = owned(owner, id);
        requireActiveParent(interview);
        interviews.delete(interview);
    }

    public List<InterviewResponse> load(List<UUID> ids) {
        return load(ids, interviews.findAllById(ids));
    }

    private List<InterviewResponse> load(List<UUID> ids, List<Interview> found) {
        Map<UUID, Interview> byId = found.stream().collect(Collectors.toMap(Interview::getId, Function.identity()));
        List<Interview> ordered = ids.stream().map(byId::get).toList();
        Map<UUID, Application> parents = applications.findAllById(ordered.stream().map(Interview::getApplicationId).distinct().toList())
                .stream().collect(Collectors.toMap(Application::getId, Function.identity()));
        return ordered.stream()
                .map(i -> InterviewResponse.of(i, ApplicationSummary.of(parents.get(i.getApplicationId()))))
                .toList();
    }

    private void applyDetails(Interview interview, JsonInput in, boolean creating) {
        if (creating || in.present("durationMinutes")) {
            Integer duration = in.integer("durationMinutes");
            if (duration != null && (duration < 1 || duration > 1440)) {
                in.errors().add("durationMinutes", "Enter a duration between 1 and 1440 minutes.");
            }
            interview.setDurationMinutes(duration);
        }
        if (creating || in.present("location")) {
            interview.setLocation(Validators.optionalLine(in, "location", 160));
        }
        if (creating || in.present("meetingUrl")) {
            interview.setMeetingUrl(Validators.optionalHttpUrl(in, "meetingUrl"));
        }
        if (creating || in.present("interviewerName")) {
            interview.setInterviewerName(Validators.optionalLine(in, "interviewerName", 160));
        }
        if (creating || in.present("notes")) {
            interview.setNotes(Validators.optionalNotes(in, "notes", 10_000));
        }
    }

    private void applyStart(Interview interview, LocalDate date, LocalTime localTime, ZoneId zone, JsonInput in) {
        Instant startsAt = TimeRules.resolveStrict(date, localTime, zone, in.errors(), "localTime");
        in.errors().throwIfAny();
        interview.setStartsAt(startsAt);
        interview.setTimeZone(zone.getId());
    }

    private Interview owned(AccountPrincipal owner, UUID id) {
        return interviews.findByIdAndOwnerId(id, owner.id()).orElseThrow(ApiException::notFound);
    }

    private void requireActiveParent(Interview interview) {
        Application parent = applications.findById(interview.getApplicationId()).orElseThrow(ApiException::notFound);
        if (parent.isArchived()) {
            throw ApiException.archived();
        }
    }

    static LocalDateTime local(Interview interview) {
        return LocalDateTime.ofInstant(interview.getStartsAt(), ZoneId.of(interview.getTimeZone()));
    }
}

