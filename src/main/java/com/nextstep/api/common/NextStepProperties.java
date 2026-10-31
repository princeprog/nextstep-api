package com.nextstep.api.common;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application settings. Values are mutable so integration tests can tighten limits temporarily. */
@ConfigurationProperties(prefix = "nextstep")
public class NextStepProperties {

    private String appPublicOrigin = "http://localhost:3000";
    private long maxRequestBytes = 262_144;
    private final Demo demo = new Demo();
    private final RateLimit rateLimit = new RateLimit();
    private final Security security = new Security();

    public String getAppPublicOrigin() { return appPublicOrigin; }
    public void setAppPublicOrigin(String appPublicOrigin) { this.appPublicOrigin = appPublicOrigin; }
    public long getMaxRequestBytes() { return maxRequestBytes; }
    public void setMaxRequestBytes(long maxRequestBytes) { this.maxRequestBytes = maxRequestBytes; }
    public Demo getDemo() { return demo; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Security getSecurity() { return security; }

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
        private int demoGlobalPerHour = 300;
        private int loginPerWindow = 10;
        private Duration loginWindow = Duration.ofMinutes(15);
        private int registerPerHour = 10;

        public int getDemoPerHour() { return demoPerHour; }
        public void setDemoPerHour(int demoPerHour) { this.demoPerHour = demoPerHour; }
        public int getDemoGlobalPerHour() { return demoGlobalPerHour; }
        public void setDemoGlobalPerHour(int demoGlobalPerHour) { this.demoGlobalPerHour = demoGlobalPerHour; }
        public int getLoginPerWindow() { return loginPerWindow; }
        public void setLoginPerWindow(int loginPerWindow) { this.loginPerWindow = loginPerWindow; }
        public Duration getLoginWindow() { return loginWindow; }
        public void setLoginWindow(Duration loginWindow) { this.loginWindow = loginWindow; }
        public int getRegisterPerHour() { return registerPerHour; }
        public void setRegisterPerHour(int registerPerHour) { this.registerPerHour = registerPerHour; }
    }

    public static class Security {
        /** HMAC key for hashing client addresses and emails in rate-limit rows. Every API instance must share it. */
        private String identifierKey;
        /** Argon2id uses about 19 MiB per hash, so only this many hashes run at once. */
        private int maxConcurrentPasswordHashes = 4;

        public String getIdentifierKey() { return identifierKey; }
        public void setIdentifierKey(String identifierKey) { this.identifierKey = identifierKey; }
        public int getMaxConcurrentPasswordHashes() { return maxConcurrentPasswordHashes; }
        public void setMaxConcurrentPasswordHashes(int maxConcurrentPasswordHashes) {
            this.maxConcurrentPasswordHashes = maxConcurrentPasswordHashes;
        }
    }
}

