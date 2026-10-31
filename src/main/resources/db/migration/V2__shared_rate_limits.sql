-- Rate-limit counters shared by every API instance (they used to live in each instance's memory).
-- key_hash is a keyed HMAC-SHA256 of the client address or email, so no raw addresses or emails are stored.
-- Rows expire two windows after they start and are removed by the maintenance job.
CREATE TABLE rate_limit_counters (
    bucket       VARCHAR(40) NOT NULL,
    key_hash     CHAR(64)    NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    hits         INTEGER     NOT NULL CHECK (hits >= 0),
    expires_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT rate_limit_counters_pk PRIMARY KEY (bucket, key_hash, window_start)
);

CREATE INDEX rate_limit_counters_expiry_ix ON rate_limit_counters (expires_at);
