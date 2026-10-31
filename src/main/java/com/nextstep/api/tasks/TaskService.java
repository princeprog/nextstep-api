package com.nextstep.api.tasks;

import java.time.Instant;
import java.time.LocalDate;
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
public class TaskService {

    private final TaskRepository tasks;
    private final TaskQueries queries;
    private final ApplicationRepository applications;
    private final TimeRules time;
    private final NextStepProperties properties;

    public TaskService(TaskRepository tasks, TaskQueries queries, ApplicationRepository applications, TimeRules time,
                       NextStepProperties properties) {
        this.tasks = tasks;
        this.queries = queries;
        this.applications = applications;
        this.time = time;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> list(AccountPrincipal owner, TaskQueries.View view, PageParams page) {
        TaskQueries.Result result = queries.listActive(owner.id(), view, page, time.now());
        return PageResponse.of(load(result.ids()), page, result.total());
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> listForApplication(AccountPrincipal owner, UUID applicationId, PageParams page) {
        ownedApplication(owner, applicationId);
        TaskQueries.Result result = queries.listForApplication(owner.id(), applicationId, page);
        return PageResponse.of(load(result.ids()), page, result.total());
    }

    @Transactional
    public TaskResponse create(AccountPrincipal owner, JsonInput in) {
        UUID applicationId = Validators.uuid(in, "applicationId", "Choose an application.");
        if (applicationId != null) {
            Application parent = ownedApplication(owner, applicationId);
            if (parent.isArchived()) {
                throw ApiException.archived();
            }
        }
        if (owner.isDemo() && tasks.countByOwnerId(owner.id()) >= properties.getDemo().getMaxTasks()) {
            throw ApiException.conflict("DEMO_LIMIT_REACHED", "This demo has reached its task limit.");
        }
        Task task = new Task();
        task.setOwnerId(owner.id());
        task.setApplicationId(applicationId);
        task.setTitle(Validators.requiredText(in, "title", 200, "Enter a task title."));
        task.setNote(Validators.optionalNotes(in, "note", 2000));
        LocalDate dueDate = Validators.date(in, "dueDate", true, "Choose a due date.");
        LocalTime dueTime = Validators.time(in, "dueTime", false, null);
        ZoneId zone = Validators.zone(in, "timeZone", true);
        in.errors().throwIfAny();
        applySchedule(task, dueDate, dueTime, zone, in);
        Instant now = time.now();
        task.setStatus(TaskStatus.OPEN);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        tasks.save(task);
        return toResponse(task);
    }

    @Transactional(readOnly = true)
    public TaskResponse get(AccountPrincipal owner, UUID id) {
        return toResponse(owned(owner, id));
    }

    @Transactional
    public TaskResponse update(AccountPrincipal owner, UUID id, JsonInput in) {
        Task task = owned(owner, id);
        requireActiveParent(task);
        if (in.present("applicationId")) {
            in.errors().add("applicationId", "Tasks stay with their application.");
        }
        String title = in.present("title") ? Validators.requiredText(in, "title", 200, "Enter a task title.") : task.getTitle();
        String note = in.present("note") ? Validators.optionalNotes(in, "note", 2000) : task.getNote();
        LocalDate dueDate = in.present("dueDate") ? Validators.date(in, "dueDate", true, "Choose a due date.") : task.getDueDate();
        LocalTime dueTime = in.present("dueTime") ? Validators.time(in, "dueTime", false, null) : task.getDueTime();
        ZoneId zone = in.present("timeZone") ? Validators.zone(in, "timeZone", true) : ZoneId.of(task.getTimeZone());
        in.errors().throwIfAny();
        task.setTitle(title);
        task.setNote(note);
        applySchedule(task, dueDate, dueTime, zone, in);
        task.setUpdatedAt(time.now());
        return toResponse(task);
    }

    @Transactional
    public TaskResponse complete(AccountPrincipal owner, UUID id) {
        Task task = owned(owner, id);
        requireActiveParent(task);
        if (task.getStatus() != TaskStatus.COMPLETED) {
            Instant now = time.now();
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(now);
            task.setUpdatedAt(now);
        }
        return toResponse(task);
    }

    @Transactional
    public TaskResponse reopen(AccountPrincipal owner, UUID id) {
        Task task = owned(owner, id);
        requireActiveParent(task);
        if (task.getStatus() != TaskStatus.OPEN) {
            task.setStatus(TaskStatus.OPEN);
            task.setCompletedAt(null);
            task.setUpdatedAt(time.now());
        }
        return toResponse(task);
    }

    @Transactional
    public void delete(AccountPrincipal owner, UUID id) {
        Task task = owned(owner, id);
        requireActiveParent(task);
        tasks.delete(task);
    }

    /** Builds responses in the given ID order, with parent application summaries. */
    public List<TaskResponse> load(List<UUID> ids) {
        Map<UUID, Task> byId = tasks.findAllById(ids).stream().collect(Collectors.toMap(Task::getId, Function.identity()));
        List<Task> ordered = ids.stream().map(byId::get).toList();
        return toResponses(ordered);
    }

    private List<TaskResponse> toResponses(List<Task> list) {
        Map<UUID, Application> parents = applications.findAllById(list.stream().map(Task::getApplicationId).distinct().toList())
                .stream().collect(Collectors.toMap(Application::getId, Function.identity()));
        Instant now = time.now();
        return list.stream()
                .map(t -> TaskResponse.of(t, ApplicationSummary.of(parents.get(t.getApplicationId())), now))
                .toList();
    }

    private TaskResponse toResponse(Task task) {
        return toResponses(List.of(task)).getFirst();
    }

    private void applySchedule(Task task, LocalDate dueDate, LocalTime dueTime, ZoneId zone, JsonInput in) {
        Instant dueAt = null;
        if (dueTime != null) {
            dueAt = TimeRules.resolveStrict(dueDate, dueTime, zone, in.errors(), "dueTime");
            in.errors().throwIfAny();
        }
        task.setDueDate(dueDate);
        task.setDueTime(dueTime);
        task.setTimeZone(zone.getId());
        task.setDueAt(dueAt);
        task.setDeadlineAt(TimeRules.deadline(dueDate, dueAt, zone));
    }

    private Task owned(AccountPrincipal owner, UUID id) {
        return tasks.findByIdAndOwnerId(id, owner.id()).orElseThrow(ApiException::notFound);
    }

    private Application ownedApplication(AccountPrincipal owner, UUID id) {
        return applications.findByIdAndOwnerId(id, owner.id()).orElseThrow(ApiException::notFound);
    }

    private void requireActiveParent(Task task) {
        Application parent = applications.findById(task.getApplicationId()).orElseThrow(ApiException::notFound);
        if (parent.isArchived()) {
            throw ApiException.archived();
        }
    }
}

