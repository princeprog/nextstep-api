-- Email verification, password reset, transactional email, and a record of deleted accounts.

-- Accounts created before verification existed keep working: their email counts as verified, so the cleanup of
-- never-verified accounts can't remove them.
ALTER TABLE accounts ADD COLUMN email_verified_at TIMESTAMPTZ;
UPDATE accounts SET email_verified_at = created_at WHERE kind = 'NORMAL';
CREATE INDEX accounts_unverified_created_ix ON accounts (created_at) WHERE kind = 'NORMAL' AND email_verified_at IS NULL;

-- Single-use, expiring links. Only a SHA-256 hash of each token is stored.
CREATE TABLE account_tokens (
    id         UUID        PRIMARY KEY,
    account_id UUID        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    purpose    VARCHAR(20) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    token_hash CHAR(64)    NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    CONSTRAINT account_tokens_hash_uq UNIQUE (token_hash)
);
CREATE INDEX account_tokens_account_ix ON account_tokens (account_id, purpose);
CREATE INDEX account_tokens_expiry_ix ON account_tokens (expires_at);

-- Emails waiting to be sent. Links are created when an email is sent, so no usable token is ever stored here.
CREATE TABLE email_outbox (
    id              UUID        PRIMARY KEY,
    kind            VARCHAR(20) NOT NULL CHECK (kind IN ('VERIFY_EMAIL', 'ACCOUNT_EXISTS', 'RESET_PASSWORD',
                                                         'PASSWORD_CHANGED', 'ACCOUNT_DELETED')),
    account_id      UUID        REFERENCES accounts (id) ON DELETE CASCADE,
    -- Only for ACCOUNT_DELETED, which is sent after the account row is gone.
    recipient       VARCHAR(254),
    created_at      TIMESTAMPTZ NOT NULL,
    attempts        INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error      VARCHAR(200),
    CONSTRAINT email_outbox_target CHECK ((account_id IS NULL) <> (recipient IS NULL))
);
CREATE INDEX email_outbox_due_ix ON email_outbox (next_attempt_at);

-- Accounts deleted on request or after never being verified. Holds only the random account ID, and is kept longer
-- than any backup, so deletions can be applied again after a backup is restored.
CREATE TABLE account_deletions (
    account_id UUID        PRIMARY KEY,
    deleted_at TIMESTAMPTZ NOT NULL,
    reason     VARCHAR(20) NOT NULL CHECK (reason IN ('REQUESTED', 'UNVERIFIED'))
);
CREATE INDEX account_deletions_deleted_ix ON account_deletions (deleted_at);

-- After restoring a backup: import any newer account_deletions rows, then run this to delete those accounts again.
CREATE FUNCTION nextstep_reapply_deletions() RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE
    removed INTEGER;
BEGIN
    DELETE FROM spring_session WHERE principal_name IN (SELECT account_id::text FROM account_deletions);
    DELETE FROM accounts WHERE id IN (SELECT account_id FROM account_deletions);
    GET DIAGNOSTICS removed = ROW_COUNT;
    RETURN removed;
END;
$$;
