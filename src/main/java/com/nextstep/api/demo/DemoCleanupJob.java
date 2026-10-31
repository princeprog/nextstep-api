package com.nextstep.api.demo;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.nextstep.api.common.ClusterJobLock;

/**
 * Removes expired demo workspaces at startup and then on the configured interval (default 15 minutes). Only one API
 * instance runs it at a time, and each run removes at most {@link DemoService#CLEANUP_BATCH} demos.
 */
@Component
public class DemoCleanupJob {

    private final DemoService demoService;
    private final ClusterJobLock lock;

    public DemoCleanupJob(DemoService demoService, ClusterJobLock lock) {
        this.demoService = demoService;
        this.lock = lock;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${nextstep.demo.cleanup-interval:PT15M}")
    public void run() {
        lock.runExclusively("demo-cleanup", demoService::cleanupExpired);
    }
}
