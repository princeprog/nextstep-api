package com.nextstep.api.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the production profile with settings that are only safe locally. Messages name the setting but
 * never print its value.
 */
@Component
@Profile("production")
class ProductionSettingsCheck implements InitializingBean {

    static final int MAX_PER_CLIENT_LIMIT = 100;
    static final int MAX_GLOBAL_DEMO_LIMIT = 5_000;
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "0.0.0.0");

    private final Environment environment;
    private final NextStepProperties properties;

    ProductionSettingsCheck(Environment environment, NextStepProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems(environment, properties);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Production settings are not safe to start with:\n - "
                    + String.join("\n - ", problems));
        }
    }

    static List<String> problems(Environment env, NextStepProperties properties) {
        List<String> problems = new ArrayList<>();
        if (!env.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            problems.add("Session cookies must be Secure (server.servlet.session.cookie.secure=true).");
        }
        if (!env.getProperty("server.servlet.session.cookie.name", "").startsWith("__Host-")) {
            problems.add("The session cookie name must start with __Host- so only the app's HTTPS origin can set it.");
        }
        if (!isPublicHttps(properties.getAppPublicOrigin())) {
            problems.add("APP_PUBLIC_ORIGIN must be the app's public https origin.");
        }
        String key = properties.getSecurity().getIdentifierKey();
        if (key == null || key.strip().length() < 32) {
            problems.add("IDENTIFIER_HASH_KEY must be set to at least 32 characters and shared by every API instance.");
        }
        if (env.getProperty("spring.datasource.password", "").isEmpty()) {
            problems.add("DATABASE_PASSWORD must be set.");
        }
        if (!"smtp".equals(properties.getMail().getMode())) {
            problems.add("MAIL_MODE must be smtp; the log mode writes sign-in links to the log instead of sending them.");
        }
        if (env.getProperty("spring.mail.host", "").isBlank()) {
            problems.add("MAIL_HOST must be set to the email provider's SMTP host.");
        }
        String from = properties.getMail().getFrom();
        if (from == null || from.isBlank() || from.contains("localhost")) {
            problems.add("MAIL_FROM must be an address on the domain the email provider sends for.");
        }
        NextStepProperties.RateLimit limits = properties.getRateLimit();
        if (limits.getDemoPerHour() > MAX_PER_CLIENT_LIMIT || limits.getRegisterPerHour() > MAX_PER_CLIENT_LIMIT
                || limits.getLoginPerWindow() > MAX_PER_CLIENT_LIMIT
                || limits.getEmailRequestsPerHour() > MAX_PER_CLIENT_LIMIT
                || limits.getTokenAttemptsPerWindow() > MAX_PER_CLIENT_LIMIT
                || limits.getDemoGlobalPerHour() > MAX_GLOBAL_DEMO_LIMIT) {
            problems.add("Rate limits are set to local test values; remove the relaxed overrides.");
        }
        return problems;
    }

    private static boolean isPublicHttps(String origin) {
        try {
            URI uri = new URI(origin == null ? "" : origin.strip());
            return "https".equals(uri.getScheme()) && uri.getHost() != null && !LOCAL_HOSTS.contains(uri.getHost());
        } catch (URISyntaxException ex) {
            return false;
        }
    }
}
