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
    private final Mail mail = new Mail();
    private final Accounts accounts = new Accounts();
    private final Passwords passwords = new Passwords();

    public String getAppPublicOrigin() { return appPublicOrigin; }
    public void setAppPublicOrigin(String appPublicOrigin) { this.appPublicOrigin = appPublicOrigin; }
    public long getMaxRequestBytes() { return maxRequestBytes; }
    public void setMaxRequestBytes(long maxRequestBytes) { this.maxRequestBytes = maxRequestBytes; }
    public Demo getDemo() { return demo; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Security getSecurity() { return security; }
    public Mail getMail() { return mail; }
    public Accounts getAccounts() { return accounts; }
    public Passwords getPasswords() { return passwords; }

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
        private int loginFailuresPerEmailPerWindow = 50;
        private Duration loginWindow = Duration.ofMinutes(15);
        private int registerPerHour = 10;
        private int registerPerEmailPerHour = 5;
        private int emailRequestsPerHour = 10;
        private int emailRequestsPerEmailPerHour = 3;
        private int tokenAttemptsPerWindow = 30;
        private int passwordChecksPerWindow = 5;
        private int exportsPerHour = 10;

        public int getDemoPerHour() { return demoPerHour; }
        public void setDemoPerHour(int demoPerHour) { this.demoPerHour = demoPerHour; }
        public int getDemoGlobalPerHour() { return demoGlobalPerHour; }
        public void setDemoGlobalPerHour(int demoGlobalPerHour) { this.demoGlobalPerHour = demoGlobalPerHour; }
        /** Failed sign-ins per client address per window. */
        public int getLoginPerWindow() { return loginPerWindow; }
        public void setLoginPerWindow(int loginPerWindow) { this.loginPerWindow = loginPerWindow; }
        /**
         * Failed sign-ins per email per window, from any address. Much higher than the per-address limit, so one
         * attacker can't lock an owner out, while guessing from many addresses is still slowed down.
         */
        public int getLoginFailuresPerEmailPerWindow() { return loginFailuresPerEmailPerWindow; }
        public void setLoginFailuresPerEmailPerWindow(int value) { this.loginFailuresPerEmailPerWindow = value; }
        public Duration getLoginWindow() { return loginWindow; }
        public void setLoginWindow(Duration loginWindow) { this.loginWindow = loginWindow; }
        public int getRegisterPerHour() { return registerPerHour; }
        public void setRegisterPerHour(int registerPerHour) { this.registerPerHour = registerPerHour; }
        public int getRegisterPerEmailPerHour() { return registerPerEmailPerHour; }
        public void setRegisterPerEmailPerHour(int value) { this.registerPerEmailPerHour = value; }
        /** Verification resends and password-reset requests per client address per hour. */
        public int getEmailRequestsPerHour() { return emailRequestsPerHour; }
        public void setEmailRequestsPerHour(int value) { this.emailRequestsPerHour = value; }
        /** Verification resends and password-reset requests per email address per hour, against email bombing. */
        public int getEmailRequestsPerEmailPerHour() { return emailRequestsPerEmailPerHour; }
        public void setEmailRequestsPerEmailPerHour(int value) { this.emailRequestsPerEmailPerHour = value; }
        /** Link confirmations (verification and reset) per client address per sign-in window. */
        public int getTokenAttemptsPerWindow() { return tokenAttemptsPerWindow; }
        public void setTokenAttemptsPerWindow(int value) { this.tokenAttemptsPerWindow = value; }
        /** Wrong current passwords (password change, account deletion) per account per sign-in window. */
        public int getPasswordChecksPerWindow() { return passwordChecksPerWindow; }
        public void setPasswordChecksPerWindow(int value) { this.passwordChecksPerWindow = value; }
        public int getExportsPerHour() { return exportsPerHour; }
        public void setExportsPerHour(int value) { this.exportsPerHour = value; }
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

    public static class Mail {
        /** log (local development: write emails to the log) or smtp (any provider's SMTP service). */
        private String mode = "log";
        private String from = "NextStep <no-reply@localhost>";
        /**
         * Emails sent across all accounts per hour; the rest wait in the outbox. Requests that queue an email
         * (registration, resends, and reset requests) are capped at the same rate, so the queue can't grow without end.
         */
        private int globalPerHour = 200;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public int getGlobalPerHour() { return globalPerHour; }
        public void setGlobalPerHour(int globalPerHour) { this.globalPerHour = globalPerHour; }
    }

    public static class Accounts {
        private Duration verificationTtl = Duration.ofHours(24);
        private Duration resetTtl = Duration.ofMinutes(30);
        /** Accounts never verified within this time are deleted. */
        private Duration unverifiedTtl = Duration.ofDays(7);
        /** Must be longer than the backup retention, so deletions can be re-applied after any restore. */
        private Duration deletionLedgerRetention = Duration.ofDays(35);
        /** Records one normal account may hold, so one account can't fill the database or make its export huge. */
        private int maxApplications = 2_000;
        private int maxTasks = 10_000;
        private int maxInterviews = 2_000;

        public Duration getVerificationTtl() { return verificationTtl; }
        public void setVerificationTtl(Duration verificationTtl) { this.verificationTtl = verificationTtl; }
        public Duration getResetTtl() { return resetTtl; }
        public void setResetTtl(Duration resetTtl) { this.resetTtl = resetTtl; }
        public Duration getUnverifiedTtl() { return unverifiedTtl; }
        public void setUnverifiedTtl(Duration unverifiedTtl) { this.unverifiedTtl = unverifiedTtl; }
        public Duration getDeletionLedgerRetention() { return deletionLedgerRetention; }
        public void setDeletionLedgerRetention(Duration value) { this.deletionLedgerRetention = value; }
        public int getMaxApplications() { return maxApplications; }
        public void setMaxApplications(int maxApplications) { this.maxApplications = maxApplications; }
        public int getMaxTasks() { return maxTasks; }
        public void setMaxTasks(int maxTasks) { this.maxTasks = maxTasks; }
        public int getMaxInterviews() { return maxInterviews; }
        public void setMaxInterviews(int maxInterviews) { this.maxInterviews = maxInterviews; }
    }

    public static class Passwords {
        /** Reject passwords found in Have I Been Pwned's Pwned Passwords (k-anonymity range API). */
        private boolean breachCheck = true;
        private String breachCheckUrl = "https://api.pwnedpasswords.com/range/";
        private Duration breachCheckTimeout = Duration.ofSeconds(3);

        public boolean isBreachCheck() { return breachCheck; }
        public void setBreachCheck(boolean breachCheck) { this.breachCheck = breachCheck; }
        public String getBreachCheckUrl() { return breachCheckUrl; }
        public void setBreachCheckUrl(String breachCheckUrl) { this.breachCheckUrl = breachCheckUrl; }
        public Duration getBreachCheckTimeout() { return breachCheckTimeout; }
        public void setBreachCheckTimeout(Duration breachCheckTimeout) { this.breachCheckTimeout = breachCheckTimeout; }
    }
}

