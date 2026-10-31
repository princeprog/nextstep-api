package com.nextstep.api.dashboard;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nextstep.api.identity.AccountPrincipal;

@RestController
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/dashboard")
    public ResponseEntity<DashboardResponse> dashboard(@AuthenticationPrincipal AccountPrincipal owner) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.build(owner));
    }
}

