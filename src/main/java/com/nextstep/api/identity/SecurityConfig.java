package com.nextstep.api.identity;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import com.nextstep.api.common.ApiErrors;
import com.nextstep.api.common.NextStepProperties;

import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder(NextStepProperties properties) {
        // Argon2id: 16-byte salt, 32-byte hash, parallelism 1, 19 MiB memory, 2 iterations (OWASP minimum profile).
        // Only a few hashes run at once, so a burst of sign-in attempts can't exhaust the server's memory.
        return new BoundedPasswordEncoder(new Argon2PasswordEncoder(16, 32, 1, 19_456, 2),
                properties.getSecurity().getMaxConcurrentPasswordHashes(), Duration.ofSeconds(10));
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    @Bean
    public AuthenticationManager authenticationManager(AccountAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
                                                   SecurityContextRepository securityContextRepository,
                                                   ObjectMapper objectMapper, Clock clock) throws Exception {
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/openapi.yaml", "/api/v1/health",
                                "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/demo",
                                "/api/v1/auth/verify-email", "/api/v1/auth/verify-email/resend",
                                "/api/v1/auth/password-reset", "/api/v1/auth/password-reset/confirm").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> ApiErrors.write(objectMapper, request, response,
                                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue."))
                        .accessDeniedHandler((request, response, e) -> {
                            if (e instanceof CsrfException) {
                                ApiErrors.write(objectMapper, request, response, HttpStatus.FORBIDDEN, "CSRF_INVALID",
                                        "Your security token is missing or expired. Refresh and try again.");
                            } else {
                                ApiErrors.write(objectMapper, request, response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                        "You don't have access to this action.");
                            }
                        }))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .addFilterBefore(new DemoSessionFilter(clock, objectMapper), AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
