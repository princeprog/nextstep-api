package com.nextstep.api.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.nextstep.api.common.ClusterJobLock;
import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;

class DemoCleanupLockTest extends IntegrationTestSupport {

    @Autowired
    DemoCleanupJob cleanupJob;

    @Autowired
    DataSource dataSource;

    @Test
    void cleanupSkipsWhileAnotherInstanceIsRunningIt() throws Exception {
        TestBrowser visitor = browser();
        visitor.refreshCsrf();
        TestBrowser.Response demo = visitor.post("/api/v1/auth/demo", null);
        assertThat(demo.status()).isEqualTo(201);
        String demoId = demo.text("id");
        clock.advance(Duration.ofHours(25));

        try (Connection otherInstance = dataSource.getConnection()) {
            otherInstance.setAutoCommit(false);
            try (PreparedStatement lock = otherInstance.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                lock.setString(1, ClusterJobLock.lockName("demo-cleanup"));
                lock.execute();
            }
            cleanupJob.run();
            assertThat(accountExists(demoId)).as("skipped while the lock is held elsewhere").isTrue();
            otherInstance.rollback();
        }

        cleanupJob.run();
        assertThat(accountExists(demoId)).as("removed once the lock is free").isFalse();
    }

    private boolean accountExists(String id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = CAST(? AS uuid)", Integer.class, id);
        return count != null && count == 1;
    }
}
