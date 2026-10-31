package com.nextstep.api.identity;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nextstep.api.common.ApiException;
import com.nextstep.api.common.JsonInput;
import com.nextstep.api.common.NextStepProperties;
import com.nextstep.api.common.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final AccountService accountService;
    private final AccountExportService exportService;
    private final PasswordPolicy passwordPolicy;
    private final SessionSupport sessions;
    private final RateLimiter rateLimiter;
    private final NextStepProperties properties;

    public MeController(AccountService accountService, AccountExportService exportService, PasswordPolicy passwordPolicy,
                        SessionSupport sessions, RateLimiter rateLimiter, NextStepProperties properties) {
        this.accountService = accountService;
        this.exportService = exportService;
        this.passwordPolicy = passwordPolicy;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
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

    /** Changes the password after re-checking the current one; signs out every other session and rotates this one. */
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AccountPrincipal principal,
                                               @RequestBody(required = false) JsonNode body,
                                               HttpServletRequest request, HttpServletResponse response) {
        requireNormal(principal);
        // Counted before the breached-password lookup and the password hash, so simultaneous requests can't run past
        // the limit. Only a wrong current password keeps its count.
        countPasswordAttempt(principal);
        JsonInput in = JsonInput.of(body);
        String current = requiredPassword(in, "currentPassword", "Enter your current password.");
        String next = passwordPolicy.check(in, "newPassword", "Enter a new password.");
        if (current != null && current.equals(next)) {
            in.errors().add("newPassword", "Choose a password that's different from your current one.");
        }
        if (!in.errors().isEmpty()) {
            refundPasswordAttempt(principal);
            in.errors().throwIfAny();
        }
        if (!accountService.changePassword(principal.id(), current, next, request.getSession().getId())) {
            throw ApiException.validation(Map.of("currentPassword", "That isn't your current password."));
        }
        refundPasswordAttempt(principal);
        sessions.signIn(request, response, principal);
        return ResponseEntity.noContent().build();
    }

    /** Downloads everything stored for the account as JSON. Available before verification and in demos too. */
    @GetMapping("/export")
    public ResponseEntity<AccountExport> export(@AuthenticationPrincipal AccountPrincipal principal) {
        rateLimiter.check("export", principal.id().toString(), properties.getRateLimit().getExportsPerHour(), Duration.ofHours(1));
        AccountExport export = exportService.export(principal.id());
        String filename = "nextstep-export-" + LocalDate.now(ZoneOffset.UTC) + ".json";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(export);
    }

    /** Permanently deletes the account after re-checking its password. */
    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AccountPrincipal principal,
                                       @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        requireNormal(principal);
        JsonInput in = JsonInput.of(body);
        String password = requiredPassword(in, "password", "Enter your password to confirm.");
        in.errors().throwIfAny();
        countPasswordAttempt(principal);
        if (!accountService.delete(principal.id(), password)) {
            throw ApiException.validation(Map.of("password", "That password isn't correct."));
        }
        sessions.signOut(request);
        return ResponseEntity.noContent().build();
    }

    private static void requireNormal(AccountPrincipal principal) {
        if (principal.isDemo()) {
            throw ApiException.conflict("DEMO_ACCOUNT",
                    "Demo workspaces don't have a password or a permanent account. Leave the demo instead; it is removed when it expires.");
        }
    }

    private static String requiredPassword(JsonInput in, String field, String blankMessage) {
        String value = in.string(field);
        if (!in.errors().has(field) && (value == null || value.isEmpty())) {
            in.errors().add(field, blankMessage);
            return null;
        }
        return value;
    }

    // Attempts are counted before the password is checked and outside the database transaction, so simultaneous
    // requests can't slip past the limit and a rolled-back attempt still counts. A correct password takes its count back.
    private void countPasswordAttempt(AccountPrincipal principal) {
        var limits = properties.getRateLimit();
        rateLimiter.check("password-check", principal.id().toString(), limits.getPasswordChecksPerWindow(),
                limits.getLoginWindow());
    }

    private void refundPasswordAttempt(AccountPrincipal principal) {
        rateLimiter.refund("password-check", principal.id().toString(), properties.getRateLimit().getLoginWindow());
    }
}
