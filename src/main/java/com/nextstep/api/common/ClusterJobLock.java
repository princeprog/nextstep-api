package com.nextstep.api.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs scheduled work on one API instance at a time. The work runs in a single transaction that holds a PostgreSQL
 * advisory lock; another instance that tries at the same moment skips its turn instead of waiting or doing the work twice.
 */
@Component
public class ClusterJobLock {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ClusterJobLock(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Returns false, without running the work, when another instance holds the lock for this job. */
    public boolean runExclusively(String job, Runnable work) {
        Boolean ran = transactions.execute(status -> {
            Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                    Boolean.class, lockName(job));
            if (!Boolean.TRUE.equals(locked)) {
                return false;
            }
            work.run();
            return true;
        });
        return Boolean.TRUE.equals(ran);
    }

    public static String lockName(String job) {
        return "nextstep:" + job;
    }
}
