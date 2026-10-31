-- NextStep initial schema. All identifiers are server-generated UUIDs; timestamps are UTC instants.

CREATE TABLE accounts (
    id              UUID PRIMARY KEY,
    kind            VARCHAR(10)  NOT NULL CHECK (kind IN ('NORMAL', 'DEMO')),
    display_name    VARCHAR(80)  NOT NULL CHECK (length(btrim(display_name)) BETWEEN 1 AND 80),
    email           VARCHAR(254),
    password_hash   VARCHAR(255),
    time_zone       VARCHAR(64)  NOT NULL,
    demo_expires_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT accounts_kind_fields CHECK (
        (kind = 'NORMAL' AND email IS NOT NULL AND password_hash IS NOT NULL AND demo_expires_at IS NULL)
        OR (kind = 'DEMO' AND email IS NULL AND password_hash IS NULL AND demo_expires_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX accounts_normal_email_uq ON accounts (email) WHERE kind = 'NORMAL';
CREATE INDEX accounts_demo_expiry_ix ON accounts (demo_expires_at) WHERE kind = 'DEMO';

CREATE TABLE applications (
    id               UUID PRIMARY KEY,
    owner_id         UUID           NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    company          VARCHAR(160)   NOT NULL CHECK (length(btrim(company)) >= 1),
    job_title        VARCHAR(160)   NOT NULL CHECK (length(btrim(job_title)) >= 1),
    status           VARCHAR(12)    NOT NULL CHECK (status IN ('SAVED', 'APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN')),
    job_url          VARCHAR(2048),
    location         VARCHAR(160),
    work_arrangement VARCHAR(10)    CHECK (work_arrangement IN ('REMOTE', 'HYBRID', 'ONSITE')),
    employment_type  VARCHAR(12)    CHECK (employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERNSHIP', 'OTHER')),
    salary_min       NUMERIC(12, 2) CHECK (salary_min >= 0),
    salary_max       NUMERIC(12, 2) CHECK (salary_max >= 0),
    salary_currency  VARCHAR(3),
    salary_period    VARCHAR(5)     CHECK (salary_period IN ('HOUR', 'MONTH', 'YEAR')),
    applied_date     DATE,
    contact_name     VARCHAR(160),
    contact_email    VARCHAR(254),
    notes            VARCHAR(10000),
    archived_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    CONSTRAINT applications_id_owner_uq UNIQUE (id, owner_id),
    CONSTRAINT applications_salary_order CHECK (salary_min IS NULL OR salary_max IS NULL OR salary_min <= salary_max),
    CONSTRAINT applications_salary_meta CHECK (
        (salary_min IS NULL AND salary_max IS NULL)
        OR (salary_currency IS NOT NULL AND salary_period IS NOT NULL)
    ),
    CONSTRAINT applications_applied_date CHECK (status <> 'APPLIED' OR applied_date IS NOT NULL)
);

CREATE INDEX applications_owner_archive_updated_ix ON applications (owner_id, archived_at, updated_at DESC, id);
CREATE INDEX applications_owner_status_ix ON applications (owner_id, status);

CREATE TABLE tasks (
    id             UUID PRIMARY KEY,
    owner_id       UUID          NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    application_id UUID          NOT NULL,
    title          VARCHAR(200)  NOT NULL CHECK (length(btrim(title)) >= 1),
    note           VARCHAR(2000),
    due_date       DATE          NOT NULL,
    due_time       TIME(0),
    time_zone      VARCHAR(64)   NOT NULL,
    due_at         TIMESTAMPTZ,
    -- Effective deadline for ordering: the due instant, or the start of the next local day for date-only tasks.
    deadline_at    TIMESTAMPTZ   NOT NULL,
    status         VARCHAR(10)   NOT NULL CHECK (status IN ('OPEN', 'COMPLETED')),
    completed_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT tasks_application_owner_fk FOREIGN KEY (application_id, owner_id)
        REFERENCES applications (id, owner_id) ON DELETE CASCADE,
    CONSTRAINT tasks_due_at_consistent CHECK ((due_time IS NULL) = (due_at IS NULL)),
    CONSTRAINT tasks_completion CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE INDEX tasks_owner_application_ix ON tasks (owner_id, application_id);
CREATE INDEX tasks_owner_status_deadline_ix ON tasks (owner_id, status, deadline_at, id);
CREATE INDEX tasks_application_open_deadline_ix ON tasks (application_id, deadline_at) WHERE status = 'OPEN';

CREATE TABLE interviews (
    id               UUID PRIMARY KEY,
    owner_id         UUID          NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    application_id   UUID          NOT NULL,
    type             VARCHAR(14)   NOT NULL CHECK (type IN ('PHONE_SCREEN', 'TECHNICAL', 'BEHAVIORAL', 'FINAL', 'OTHER')),
    starts_at        TIMESTAMPTZ   NOT NULL,
    time_zone        VARCHAR(64)   NOT NULL,
    duration_minutes INTEGER       CHECK (duration_minutes BETWEEN 1 AND 1440),
    location         VARCHAR(160),
    meeting_url      VARCHAR(2048),
    interviewer_name VARCHAR(160),
    notes            VARCHAR(10000),
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT interviews_application_owner_fk FOREIGN KEY (application_id, owner_id)
        REFERENCES applications (id, owner_id) ON DELETE CASCADE
);

CREATE INDEX interviews_owner_application_ix ON interviews (owner_id, application_id);
CREATE INDEX interviews_owner_starts_ix ON interviews (owner_id, starts_at, id);

-- Spring Session JDBC schema (copied from spring-session-jdbc schema-postgresql.sql)
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);

