package com.nextstep.api.applications;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nextstep.api.common.ApiException;
import com.nextstep.api.common.FieldErrors;
import com.nextstep.api.common.JsonInput;
import com.nextstep.api.common.PageParams;
import com.nextstep.api.common.PageResponse;
import com.nextstep.api.common.QueryErrors;
import com.nextstep.api.common.Validators;
import com.nextstep.api.identity.AccountPrincipal;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ApplicationResponse> list(@AuthenticationPrincipal AccountPrincipal owner,
                                                  @RequestParam(required = false) String page,
                                                  @RequestParam(required = false) String pageSize,
                                                  @RequestParam(required = false) String search,
                                                  @RequestParam(required = false) List<String> status,
                                                  @RequestParam(required = false) String workArrangement,
                                                  @RequestParam(required = false) String employmentType,
                                                  @RequestParam(required = false) String location,
                                                  @RequestParam(required = false) String appliedFrom,
                                                  @RequestParam(required = false) String appliedTo,
                                                  @RequestParam(required = false) String archive,
                                                  @RequestParam(required = false) String sort,
                                                  @RequestParam(required = false) String direction) {
        FieldErrors errors = new FieldErrors();
        PageParams pageParams = PageParams.parse(page, pageSize, errors);
        List<ApplicationStatus> statuses = new ArrayList<>();
        if (status != null) {
            for (String value : status) {
                for (String part : value.split(",")) {
                    if (part.isBlank()) {
                        continue;
                    }
                    ApplicationStatus parsed = Validators.parseEnum(ApplicationStatus.class, part.strip());
                    if (parsed == null) {
                        errors.add("status", "Unknown status.");
                    } else {
                        statuses.add(parsed);
                    }
                }
            }
        }
        WorkArrangement arrangement = optionalEnum(WorkArrangement.class, workArrangement, "workArrangement", errors);
        EmploymentType type = optionalEnum(EmploymentType.class, employmentType, "employmentType", errors);
        LocalDate from = optionalDate(appliedFrom, "appliedFrom", errors);
        LocalDate to = optionalDate(appliedTo, "appliedTo", errors);
        if (from != null && to != null && from.isAfter(to)) {
            errors.add("appliedTo", "The end date must be on or after the start date.");
        }
        boolean archived = false;
        if (archive != null && !archive.isBlank()) {
            if (archive.equals("archived")) {
                archived = true;
            } else if (!archive.equals("active")) {
                errors.add("archive", "Use active or archived.");
            }
        }
        ApplicationQueries.Sort sortField = ApplicationQueries.Sort.updatedAt;
        if (sort != null && !sort.isBlank()) {
            sortField = Validators.parseEnum(ApplicationQueries.Sort.class, sort.strip());
            if (sortField == null) {
                errors.add("sort", "Unknown sort option.");
                sortField = ApplicationQueries.Sort.updatedAt;
            }
        }
        boolean ascending = switch (sortField) {
            case company, nextTaskDue -> true;
            case updatedAt, appliedDate -> false;
        };
        if (direction != null && !direction.isBlank()) {
            if (direction.equals("asc")) {
                ascending = true;
            } else if (direction.equals("desc")) {
                ascending = false;
            } else {
                errors.add("direction", "Use asc or desc.");
            }
        }
        rejectControlCharacters(search, "search", errors);
        rejectControlCharacters(location, "location", errors);
        QueryErrors.throwIfAny(errors);
        String searchText = search == null || search.isBlank() ? null : search.strip();
        String locationText = location == null || location.isBlank() ? null : location.strip();
        return service.search(owner, new ApplicationQueries.Criteria(searchText, statuses, arrangement, type,
                locationText, from, to, archived, sortField, ascending, pageParams));
    }

    @GetMapping("/duplicates")
    public Map<String, Long> duplicates(@AuthenticationPrincipal AccountPrincipal owner,
                                        @RequestParam(required = false) String company,
                                        @RequestParam(required = false) String jobTitle,
                                        @RequestParam(required = false) String excludeId) {
        FieldErrors errors = new FieldErrors();
        rejectControlCharacters(company, "company", errors);
        rejectControlCharacters(jobTitle, "jobTitle", errors);
        QueryErrors.throwIfAny(errors);
        UUID exclude = excludeId == null || excludeId.isBlank() ? null : Validators.parseUuid(excludeId);
        return Map.of("count", service.countDuplicates(owner, company, jobTitle, exclude));
    }

    @PostMapping
    public ResponseEntity<ApplicationResponse> create(@AuthenticationPrincipal AccountPrincipal owner,
                                                      @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(owner, JsonInput.of(body)));
    }

    @GetMapping("/{id}")
    public ApplicationResponse get(@AuthenticationPrincipal AccountPrincipal owner, @PathVariable String id) {
        return service.get(owner, parseId(id));
    }

    @PatchMapping("/{id}")
    public ApplicationResponse update(@AuthenticationPrincipal AccountPrincipal owner, @PathVariable String id,
                                      @RequestBody(required = false) JsonNode body) {
        return service.update(owner, parseId(id), JsonInput.of(body));
    }

    @PatchMapping("/{id}/status")
    public ApplicationResponse status(@AuthenticationPrincipal AccountPrincipal owner, @PathVariable String id,
                                      @RequestBody(required = false) JsonNode body) {
        return service.changeStatus(owner, parseId(id), JsonInput.of(body));
    }

    @PostMapping("/{id}/archive")
    public ApplicationResponse archive(@AuthenticationPrincipal AccountPrincipal owner, @PathVariable String id) {
        return service.archive(owner, parseId(id));
    }

    @PostMapping("/{id}/restore")
    public ApplicationResponse restore(@AuthenticationPrincipal AccountPrincipal owner, @PathVariable String id) {
        return service.restore(owner, parseId(id));
    }

    /** Malformed IDs are treated like missing records. */
    public static UUID parseId(String id) {
        UUID parsed = Validators.parseUuid(id);
        if (parsed == null) {
            throw ApiException.notFound();
        }
        return parsed;
    }

    /** Free-text query values go to PostgreSQL, which can't store or compare NUL characters. */
    private static void rejectControlCharacters(String value, String field, FieldErrors errors) {
        if (Validators.hasUnsupportedControl(value)) {
            errors.add(field, Validators.CONTROL_MESSAGE);
        }
    }

    private static <E extends Enum<E>> E optionalEnum(Class<E> type, String value, String field, FieldErrors errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        E parsed = Validators.parseEnum(type, value.strip());
        if (parsed == null) {
            errors.add(field, "Unknown option.");
        }
        return parsed;
    }

    private static LocalDate optionalDate(String value, String field, FieldErrors errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        LocalDate parsed = Validators.parseDate(value.strip());
        if (parsed == null) {
            errors.add(field, "Use a valid date (YYYY-MM-DD).");
        }
        return parsed;
    }
}

