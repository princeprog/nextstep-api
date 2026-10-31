package com.nextstep.api.identity;

import java.util.Map;

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

    private final AccountService accountService;
    private final DemoService demoService;
    private final AuthenticationManager authenticationManager;
    private final SessionSupport sessions;
    private final RateLimiter rateLimiter;
    private final NextStepProperties properties;
    private final AccountRepository accounts;

    public AuthController(AccountService accountService, DemoService demoService,
                          AuthenticationManager authenticationManager, SessionSupport sessions,
                          RateLimiter rateLimiter, NextStepProperties properties, AccountRepository accounts) {
        this.accountService = accountService;
        this.demoService = demoService;
        this.authenticationManager = authenticationManager;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.accounts = accounts;
    }

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }

    @PostMapping("/register")
    public ResponseEntity<MeResponse> register(@RequestBody(required = false) JsonNode body,
                                               HttpServletRequest request, HttpServletResponse response) {
        requireSignedOut();
        rateLimiter.check("register", ClientAddress.of(request), properties.getRateLimit().getRegisterPerHour(),
                java.time.Duration.ofHours(1));
        Account account = accountService.register(JsonInput.of(body));
        sessions.signIn(request, response, AccountPrincipal.of(account));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(MeResponse.of(account));
    }

    @PostMapping("/login")
    public ResponseEntity<MeResponse> login(@RequestBody(required = false) JsonNode body,
                                            HttpServletRequest request, HttpServletResponse response) {
        requireSignedOut();
        JsonInput in = JsonInput.of(body);
        String email = Validators.normalizeEmail(in.string("email"));
        String password = in.string("password");
        if (email == null || email.isEmpty()) {
            in.errors().add("email", "Enter your email address.");
        }
        if (password == null || password.isEmpty()) {
            in.errors().add("password", "Enter your password.");
        }
        in.errors().throwIfAny();
        var limits = properties.getRateLimit();
        rateLimiter.check("login-ip", ClientAddress.of(request), limits.getLoginPerWindow(), limits.getLoginWindow());
        rateLimiter.check("login-email", email, limits.getLoginPerWindow(), limits.getLoginWindow());
        AccountPrincipal principal;
        try {
            var result = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
            principal = (AccountPrincipal) result.getPrincipal();
        } catch (AuthenticationException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.", null);
        }
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
        rateLimiter.check("demo", ClientAddress.of(request), properties.getRateLimit().getDemoPerHour(),
                java.time.Duration.ofHours(1));
        Account account = demoService.createDemo(zone);
        sessions.signIn(request, response, AccountPrincipal.of(account));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(MeResponse.of(account));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.signOut(request);
        return ResponseEntity.noContent().build();
    }

    private void requireSignedOut() {
        if (SessionSupport.currentPrincipal() != null) {
            throw ApiException.conflict("ALREADY_SIGNED_IN", "You're already signed in. Sign out first to switch workspaces.");
        }
    }
}

