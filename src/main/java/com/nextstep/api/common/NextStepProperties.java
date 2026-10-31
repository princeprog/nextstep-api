package com.nextstep.api.common;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application settings. Values are mutable so integration tests can tighten limits temporarily. */
@ConfigurationProperties(prefix = "nextstep")
public class NextStepProperties {

    private String appPublicOrigin = "http://localhost:3000";
    private final Demo demo = new Demo();
    private final RateLimit rateLimit = new RateLimit();

    public String getAppPublicOrigin() { return appPublicOrigin; }
    public void setAppPublicOrigin(String appPublicOrigin) { this.appPublicOrigin = appPublicOrigin; }
    public Demo getDemo() { return demo; }
    public RateLimit getRateLimit() { return rateLimit; }

    public static class Demo {
        private int ttlHours = 24;
        private Duration cleanupInterval = Duration.ofMinutes(15);
        private int maxApplications = 100;
        private int maxTasks = 500;
        private int maxInterviews = 200;

        public int getTtlHours() { return ttlHours; }
        public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
        public Duration getCleanupInterval() { return cleanupInterval; }
        public void setCleanupInterval(Duration cleanupInterval) { this.cleanupInterval = cleanupInterval; }
        public int getMaxApplications() { return maxApplications; }
        public void setMaxApplications(int maxApplications) { this.maxApplications = maxApplications; }
        public int getMaxTasks() { return maxTasks; }
        public void setMaxTasks(int maxTasks) { this.maxTasks = maxTasks; }
        public int getMaxInterviews() { return maxInterviews; }
        public void setMaxInterviews(int maxInterviews) { this.maxInterviews = maxInterviews; }
    }

    public static class RateLimit {
        private int demoPerHour = 5;
        private int loginPerWindow = 10;
        private Duration loginWindow = Duration.ofMinutes(15);
        private int registerPerHour = 10;

        public int getDemoPerHour() { return demoPerHour; }
        public void setDemoPerHour(int demoPerHour) { this.demoPerHour = demoPerHour; }
        public int getLoginPerWindow() { return loginPerWindow; }
        public void setLoginPerWindow(int loginPerWindow) { this.loginPerWindow = loginPerWindow; }
        public Duration getLoginWindow() { return loginWindow; }
        public void setLoginWindow(Duration loginWindow) { this.loginWindow = loginWindow; }
        public int getRegisterPerHour() { return registerPerHour; }
        public void setRegisterPerHour(int registerPerHour) { this.registerPerHour = registerPerHour; }
    }
}

