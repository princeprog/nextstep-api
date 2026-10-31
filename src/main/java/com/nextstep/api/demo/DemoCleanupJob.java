package com.nextstep.api.demo;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes expired demo workspaces at startup and then on the configured interval (default 15 minutes). */
@Component
public class DemoCleanupJob {

    private final DemoService demoService;

    public DemoCleanupJob(DemoService demoService) {
        this.demoService = demoService;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${nextstep.demo.cleanup-interval:PT15M}")
    public void run() {
        demoService.cleanupExpired();
    }
}

