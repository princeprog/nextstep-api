package com.nextstep.api.identity;

import java.time.Duration;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nextstep.api.common.ApiException;
import com.nextstep.api.common.JsonInput;
import com.nextstep.api.common.NextStepProperties;
import com.nextstep.api.common.RateLimiter;
import com.nextstep.api.common.Validators;
import com.nextstep.api.demo.DemoService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Duration HOUR = Duration.ofHours(1);

    private final AccountService accountService;
    private final DemoService demoService;
    private final AuthenticationManager authenticationManager;
    private final SessionSupport sessions;
    private final RateLimiter rateLimiter;
    private final PasswordPolicy passwordPolicy;
    private final NextStepProperties properties;
    private final AccountRepository accounts;

    public AuthController(AccountService accountService, DemoService demoService,
                          AuthenticationManager authenticationManager, SessionSupport sessions, RateLimiter rateLimiter,
                          PasswordPolicy passwordPolicy, NextStepProperties properties, AccountRepository accounts) {
        this.accountService = accountService;
        this.demoService = demoService;
        this.authenticationManager = authenticationManager;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
        this.passwordPolicy = passwordPolicy;
        this.properties = properties;
        this.accounts = accounts;
    }

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }

    /**
     * Always 202 with the same body for valid input: whether the email is new or already has an account is only told
     * to the inbox. No session starts; the person signs in after confirming the email.
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody(required = false) JsonNode body,
                                                        HttpServletRequest request) {
        requireSignedOut();
        var limits = properties.getRateLimit();
        rateLimiter.check("register", ClientAddress.of(request), limits.getRegisterPerHour(), HOUR);
        AccountService.Registration registration = accountService.validateRegistration(JsonInput.of(body));
        rateLimiter.check("register-email", registration.email(), limits.getRegisterPerEmailPerHour(), HOUR);
        limitEmailIntake();
        try {
            accountService.register(registration);
        } catch (DataIntegrityViolationException concurrentRegistration) {
            // The same new email registered twice at once; the other request created the account and queued its link.
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(Map.of("email", registration.email()));
    }

    @PostMapping("/login")
    public ResponseEntity<MeResponse> login(@RequestBody(required = false) JsonNode body,
                                            HttpServletRequest request, HttpServletResponse response) {
        requireSignedOut();
        JsonInput in = JsonInput.of(body);
        String email = Validators.normalizeEmail(in.string("email"));
        String password = in.string("password");
        if (!in.errors().has("email") && (email == null || email.isEmpty())) {
            in.errors().add("email", "Enter your email address.");
        }
        if (!in.errors().has("password") && (password == null || password.isEmpty())) {
            in.errors().add("password", "Enter your password.");
        }
        in.errors().throwIfAny();
        var limits = properties.getRateLimit();
        String client = ClientAddress.of(request);
        Duration window = limits.getLoginWindow();
        // Every attempt is counted before the password is checked, so simultaneous requests can't slip past a limit.
        // A successful sign-in takes its counts back: only failures use up an address's or an email's allowance, and
        // the email allowance is much larger, so one attacker can't lock the owner out.
        rateLimiter.check("login-ip", client, limits.getLoginPerWindow(), window);
        rateLimiter.check(AccountService.LOGIN_EMAIL_BUCKET, email, limits.getLoginFailuresPerEmailPerWindow(), window);
        AccountPrincipal principal;
        try {
            var result = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
            principal = (AccountPrincipal) result.getPrincipal();
        } catch (AuthenticationException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.", null);
        }
        rateLimiter.refund("login-ip", client, window);
        rateLimiter.refund(AccountService.LOGIN_EMAIL_BUCKET, email, window);
        sessions.signIn(request, response, principal);
        Account account = accounts.findById(principal.id()).orElseThrow();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(MeResponse.of(account));
    }

    @PostMapping("/demo")
    public ResponseEntity<MeResponse> demo(@RequestBody(required = false) JsonNode body,
                                           HttpServletRequest request, HttpServletResponse response) {
        requireSignedOut();
        JsonInput in = JsonInput.of(body);
        var zone = Validators.zone(in, "timeZone", false);
        in.errors().throwIfAny();
        rateLimiter.check("demo", ClientAddress.of(request), properties.getRateLimit().getDemoPerHour(), HOUR);
        // A cap across all visitors keeps a flood from many addresses from filling the database with demos.
        rateLimiter.check("demo-global", "all", properties.getRateLimit().getDemoGlobalPerHour(), HOUR);
        Account account = demoService.createDemo(zone);
        sessions.signIn(request, response, AccountPrincipal.of(account));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(MeResponse.of(account));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.signOut(request);
        return ResponseEntity.noContent().build();
    }

    /** Confirms an email from its link. It doesn't sign anyone in: following a link proves the inbox, not the password. */
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        limitLinkAttempts(request);
        JsonInput in = JsonInput.of(body);
        String token = in.string("token");
        in.errors().throwIfAny();
        accountService.verifyEmail(token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-email/resend")
    public ResponseEntity<Map<String, String>> resendVerification(@RequestBody(required = false) JsonNode body,
                                                                  HttpServletRequest request) {
        String email = emailRequest(body, request, "resend-email");
        accountService.resendVerification(email);
        return accepted();
    }

    /** Same answer whether or not the email has an account; only an existing account's inbox gets a link. */
    @PostMapping("/password-reset")
    public ResponseEntity<Map<String, String>> requestPasswordReset(@RequestBody(required = false) JsonNode body,
                                                                    HttpServletRequest request) {
        String email = emailRequest(body, request, "reset-email");
        accountService.requestPasswordReset(email);
        return accepted();
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(@RequestBody(required = false) JsonNode body,
                                                     HttpServletRequest request) {
        limitLinkAttempts(request);
        JsonInput in = JsonInput.of(body);
        String token = in.string("token");
        if (!in.errors().has("token") && !accountService.isUsableResetLink(token)) {
            throw AccountService.invalidLink();
        }
        String password = passwordPolicy.check(in, "password", "Enter a new password.");
        in.errors().throwIfAny();
        accountService.resetPassword(token, password);
        // Every session of the account was deleted; end this browser's session too, whoever it belonged to.
        sessions.signOut(request);
        return ResponseEntity.noContent().build();
    }

    private void limitLinkAttempts(HttpServletRequest request) {
        var limits = properties.getRateLimit();
        rateLimiter.check("link-ip", ClientAddress.of(request), limits.getTokenAttemptsPerWindow(), limits.getLoginWindow());
    }

    private String emailRequest(JsonNode body, HttpServletRequest request, String perEmailBucket) {
        var limits = properties.getRateLimit();
        rateLimiter.check("email-request-ip", ClientAddress.of(request), limits.getEmailRequestsPerHour(), HOUR);
        JsonInput in = JsonInput.of(body);
        String email = AccountService.requiredEmail(in);
        in.errors().throwIfAny();
        // Limits apply to any address, registered or not, so they reveal nothing and stop email bombing.
        rateLimiter.check(perEmailBucket, email, limits.getEmailRequestsPerEmailPerHour(), HOUR);
        limitEmailIntake();
        return email;
    }

    /**
     * Requests that can queue an email share one hourly cap, equal to the sending cap. Without it, requests from many
     * addresses could queue mail faster than it is sent and delay everyone's links for hours. The cap applies whether
     * or not an email ends up queued, so a refusal says nothing about any account.
     */
    private void limitEmailIntake() {
        rateLimiter.check("email-intake", "all", properties.getMail().getGlobalPerHour(), HOUR);
    }

    private static ResponseEntity<Map<String, String>> accepted() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(Map.of());
    }

    private void requireSignedOut() {
        if (SessionSupport.currentPrincipal() != null) {
            throw ApiException.conflict("ALREADY_SIGNED_IN", "You're already signed in. Sign out first to switch workspaces.");
        }
    }
}
