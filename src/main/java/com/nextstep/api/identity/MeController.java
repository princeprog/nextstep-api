package com.nextstep.api.identity;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nextstep.api.common.JsonInput;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final AccountService accountService;
    private final SessionSupport sessions;

    public MeController(AccountService accountService, SessionSupport sessions) {
        this.accountService = accountService;
        this.sessions = sessions;
    }

    @GetMapping
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(MeResponse.of(accountService.get(principal.id())));
    }

    @PatchMapping
    public MeResponse update(@AuthenticationPrincipal AccountPrincipal principal,
                             @RequestBody(required = false) JsonNode body) {
        return MeResponse.of(accountService.updateProfile(principal.id(), JsonInput.of(body)));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AccountPrincipal principal, HttpServletRequest request) {
        accountService.delete(principal.id());
        sessions.signOut(request);
        return ResponseEntity.noContent().build();
    }
}

