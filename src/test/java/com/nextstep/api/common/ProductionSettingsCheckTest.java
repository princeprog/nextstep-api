package com.nextstep.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSettingsCheckTest {

    @Test
    void acceptsProductionReadySettings() {
        assertThat(ProductionSettingsCheck.problems(readyEnvironment(), readyProperties())).isEmpty();
    }

    @Test
    void plainSmtpIsAllowedOnlyToARelayOnThisMachine() {
        MockEnvironment remote = readyEnvironment()
                .withProperty("spring.mail.properties.mail.smtp.starttls.required", "false")
                .withProperty("spring.mail.properties.mail.smtp.auth", "false");
        assertThat(ProductionSettingsCheck.problems(remote, readyProperties()))
                .anyMatch(problem -> problem.startsWith("MAIL_STARTTLS"))
                .anyMatch(problem -> problem.startsWith("MAIL_SMTP_AUTH"));
        MockEnvironment local = remote.withProperty("spring.mail.host", "127.0.0.1");
        assertThat(ProductionSettingsCheck.problems(local, readyProperties())).isEmpty();
    }

    @Test
    void refusesEveryKindOfRelaxedLimit() {
        NextStepProperties exports = readyProperties();
        exports.getRateLimit().setExportsPerHour(1000);
        assertThat(ProductionSettingsCheck.problems(readyEnvironment(), exports)).hasSize(1);
        NextStepProperties email = readyProperties();
        email.getMail().setGlobalPerHour(100_000);
        assertThat(ProductionSettingsCheck.problems(readyEnvironment(), email)).hasSize(1);
    }

    @Test
    void reportsEveryLocalOnlySettingWithoutPrintingValues() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.servlet.session.cookie.secure", "false")
                .withProperty("server.servlet.session.cookie.name", "NEXTSTEP_SESSION")
                .withProperty("spring.datasource.password", "");
        NextStepProperties properties = new NextStepProperties();
        properties.setAppPublicOrigin("http://localhost:3000");
        properties.getSecurity().setIdentifierKey("too-short-secret");
        properties.getRateLimit().setLoginPerWindow(1000);
        List<String> problems = ProductionSettingsCheck.problems(env, properties);
        // Insecure cookie, cookie name, origin, identifier key, database password, mail mode, mail host, sender, limits.
        assertThat(problems).hasSize(9);
        assertThat(String.join(" ", problems)).doesNotContain("too-short-secret").doesNotContain("localhost");
    }

    private static MockEnvironment readyEnvironment() {
        return new MockEnvironment()
                .withProperty("server.servlet.session.cookie.secure", "true")
                .withProperty("server.servlet.session.cookie.name", "__Host-NEXTSTEP_SESSION")
                .withProperty("spring.datasource.password", "generated-password")
                .withProperty("spring.mail.host", "smtp.example.net")
                .withProperty("spring.mail.properties.mail.smtp.starttls.required", "true")
                .withProperty("spring.mail.properties.mail.smtp.auth", "true");
    }

    private static NextStepProperties readyProperties() {
        NextStepProperties properties = new NextStepProperties();
        properties.setAppPublicOrigin("https://app.nextstep.example");
        properties.getSecurity().setIdentifierKey("k".repeat(32));
        properties.getMail().setMode("smtp");
        properties.getMail().setFrom("NextStep <no-reply@nextstep.example>");
        return properties;
    }
}
