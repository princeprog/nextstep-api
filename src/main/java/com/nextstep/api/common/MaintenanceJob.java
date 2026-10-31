package com.nextstep.api.common;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes expired rate-limit counters. Runs on one instance at a time. */
@Component
public class MaintenanceJob {

    private final ClusterJobLock lock;
    private final RateLimiter rateLimiter;

    public MaintenanceJob(ClusterJobLock lock, RateLimiter rateLimiter) {
        this.lock = lock;
        this.rateLimiter = rateLimiter;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${nextstep.maintenance-interval:PT15M}")
    public void run() {
        lock.runExclusively("maintenance", () -> rateLimiter.deleteExpired());
    }
}
