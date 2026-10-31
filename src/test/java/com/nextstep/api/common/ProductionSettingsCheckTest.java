package com.nextstep.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSettingsCheckTest {

    @Test
    void acceptsProductionReadySettings() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.servlet.session.cookie.secure", "true")
                .withProperty("server.servlet.session.cookie.name", "__Host-NEXTSTEP_SESSION")
                .withProperty("spring.datasource.password", "generated-password");
        NextStepProperties properties = new NextStepProperties();
        properties.setAppPublicOrigin("https://app.nextstep.example");
        properties.getSecurity().setIdentifierKey("k".repeat(32));
        assertThat(ProductionSettingsCheck.problems(env, properties)).isEmpty();
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
        assertThat(problems).hasSize(6);
        assertThat(String.join(" ", problems)).doesNotContain("too-short-secret").doesNotContain("localhost");
    }
}
