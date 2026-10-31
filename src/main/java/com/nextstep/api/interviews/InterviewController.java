package com.nextstep.api.interviews;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nextstep.api.applications.ApplicationController;
import com.nextstep.api.common.FieldErrors;
import com.nextstep.api.common.JsonInput;
import com.nextstep.api.common.PageParams;
import com.nextstep.api.common.PageResponse;
import com.nextstep.api.common.QueryErrors;
import com.nextstep.api.common.Validators;
import com.nextstep.api.identity.AccountPrincipal;

import tools.jackson.databind.JsonNode;

@RestController
public class InterviewController {

    private final InterviewService service;

    public InterviewController(InterviewService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/interviews")
    public PageResponse<InterviewResponse> list(@AuthenticationPrincipal AccountPrincipal owner,
                                                @RequestParam(required = false) String view,
                                                @RequestParam(required = false) String page,
                                                @RequestParam(required = false) String pageSize) {
        FieldErrors errors = new FieldErrors();
        PageParams pageParams = PageParams.parse(page, pageSize, errors);
        InterviewQueries.View parsed = InterviewQueries.View.all;
        if (view != null && !view.isBlank()) {
            parsed = Validators.parseEnum(InterviewQueries.View.class, view.strip());
            if (parsed == null) {
                errors.add("view", "Use all, future, next7days, or past.");
            }
        }
        QueryErrors.throwIfAny(errors);
        return service.list(owner, parsed, pageParams);
    }

    @GetMapping("/api/v1/applications/{id}/interviews")
    public PageResponse<InterviewResponse> forApplication(@AuthenticationPrincipal AccountPrincipal owner,
                                                          @PathVariable String id,
                                                          @RequestParam(required = false) String page,
                                                          @RequestParam(required = false) String pageSize) {
        FieldErrors errors = new FieldErrors();
        PageParams pageParams = PageParams.parse(page, pageSize, errors);
        QueryErrors.throwIfAny(errors);
        return service.listForApplication(owner, ApplicationController.parseId(id), pageParams);
    }

    @PostMapping("/api/v1/interviews")
    public ResponseEntity<InterviewResponse> create(@AuthenticationPrincipal AccountPrincipal owner,
                                                    @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(owner, JsonInput.of(body)));
    }

    @GetMapping("/api/v1/interviews/{id}")
    public InterviewResponse get(@AuthenticationPrincipal AccountPrincipal owner, @PathVariable String id) {
        return service.get(owner, ApplicationController.parseId(id));
    }

    @PatchMapping("/api/v1/interviews/{id}")
    public InterviewResponse update(@AuthenticationPrincipal AccountPrincipal owner, @PathVariable String id,
                                    @RequestBody(required = false) JsonNode body) {
        return service.update(owner, ApplicationController.parseId(id), JsonInput.of(body));
    }

    @DeleteMapping("/api/v1/interviews/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AccountPrincipal owner, @PathVariable String id) {
        service.delete(owner, ApplicationController.parseId(id));
        return ResponseEntity.noContent().build();
    }
}

