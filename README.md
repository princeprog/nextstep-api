# NextStep API

Spring Boot REST API for **NextStep**, a portfolio job-application tracker. It owns identity, sessions, CSRF protection,
ownership checks, validation, business rules (statuses, archive, time zones, due states), search, the dashboard, and the
temporary demo workspace. Data is stored in PostgreSQL.

> Portfolio MVP. Email verification, password reset and change, data export, and recorded account deletion are built.
> Hosting, the email provider, backup and log retention, and the final privacy policy are still open, so this API is not
> yet serving real users. Nothing is deployed.

## Related projects

| Repository | Role |
| --- | --- |
| [nextstep-app](https://github.com/princeprog/nextstep-app) | Next.js web app (shadcn/ui, TanStack Query, Zustand). Calls this API on the same origin at `/api/v1`. |
| [nextstep-api](https://github.com/princeprog/nextstep-api) | This API. |
| [nextstep](https://github.com/princeprog/nextstep) | Separate static marketing website. It has no runtime dependency on this API. |

## Stack

- Java 25, Spring Boot 4.1.2 (Spring Framework 7, Spring Security 7.1), Maven Wrapper
- Spring Web MVC, Spring Data JPA (Hibernate 7.4), JdbcTemplate for search, dashboard, and rate-limit queries
- PostgreSQL 17+ (developed on 18.1), Flyway 12 migrations, Spring Session JDBC
- Argon2id password hashing (Bouncy Castle 1.86)
- Tests: JUnit 6 integration tests over real HTTP against a disposable PostgreSQL cluster

## Prerequisites

- JDK 25 (`JAVA_HOME` set, or a JDK under `%LOCALAPPDATA%\Programs\Java\jdk-25*` for the scripts)
- PostgreSQL binaries installed locally (`initdb`, `pg_ctl`, `psql`). Docker is not used.
  The scripts find them through `NEXTSTEP_PG_BIN`, then `PATH`, then the newest `C:\Program Files\PostgreSQL\<version>\bin`.

## Local setup (Windows PowerShell)

```powershell
# One time: create a dedicated cluster on 127.0.0.1:55432 under %LOCALAPPDATA%\NextStep\pgdata-dev.
# Generated credentials are written to %LOCALAPPDATA%\NextStep\dev-db.json (outside the repository).
.\scripts\db-dev.ps1 init

.\scripts\db-dev.ps1 start     # later sessions
.\scripts\db-dev.ps1 status
.\scripts\run-api.ps1          # http://127.0.0.1:8080, applies Flyway migrations on start
.\scripts\db-dev.ps1 stop
```

The development cluster never touches other PostgreSQL services on the machine. `run-api.ps1 -RelaxedLimits` raises the
demo, registration, and login rate limits for local end-to-end test runs; never use it for a shared deployment (the
production profile refuses to start with those values).

## Configuration

All values have local defaults in `src/main/resources/application.properties`. Secrets come from the environment only.

| Variable | Purpose | Default |
| --- | --- | --- |
| `DATABASE_URL` | JDBC URL | `jdbc:postgresql://127.0.0.1:55432/nextstep` |
| `DATABASE_USER` / `DATABASE_PASSWORD` | Database credentials (set by `run-api.ps1`) | `nextstep` / empty |
| `SPRING_PROFILES_ACTIVE` | `production` turns on the production profile described below | none |
| `SESSION_TIMEOUT` | Inactivity timeout for normal sessions | `30m` |
| `COOKIE_SECURE` | Mark the session cookie Secure (the production profile always does) | `false` |
| `SERVER_ADDRESS` / `PORT` | Bind address and port | `127.0.0.1` / `8080` (production: `0.0.0.0`) |
| `APP_PUBLIC_ORIGIN` | Public origin of the web app. Reserved for links in emails; must be a public https origin in production | `http://localhost:3000` |
| `MAIL_MODE` | `log` writes emails, including their links, to the log (local only); `smtp` sends through any provider. Production requires `smtp` | `log` |
| `MAIL_FROM` | Sender, for example `NextStep <no-reply@your-domain>` | `NextStep <no-reply@localhost>` |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | The provider's SMTP service (STARTTLS required by default) | empty / `587` / empty / empty |
| `MAIL_SMTP_AUTH` / `MAIL_STARTTLS` | Turn off only for a relay on this machine, such as Mailpit; production refuses plain SMTP to any other host | `true` / `true` |
| `EMAIL_GLOBAL_LIMIT_PER_HOUR` | Emails sent across all accounts per hour; the rest wait in the outbox. Requests that queue email (registration, resends, reset requests) share the same hourly cap, and get 429 beyond it. Set it within the email provider's plan | `200` |
| `PASSWORD_BREACH_CHECK` | Reject new passwords found in Have I Been Pwned (k-anonymity; allowed if the service is down) | `true` |
| `UNVERIFIED_ACCOUNT_TTL` | Accounts never verified within this time are deleted | `P7D` |
| `DELETION_LEDGER_RETENTION` | How long deleted account IDs are kept for re-applying deletions after a restore; keep it longer than the backup retention | `P35D` |
| `MAX_APPLICATIONS_PER_ACCOUNT` / `MAX_TASKS_PER_ACCOUNT` / `MAX_INTERVIEWS_PER_ACCOUNT` | Records one normal account may hold; past them, creating one answers 409 `ACCOUNT_LIMIT_REACHED` | `2000` / `10000` / `2000` |
| `IDENTIFIER_HASH_KEY` | Key for hashing client addresses and emails in rate-limit rows. Required in production (32+ characters) and shared by every instance | random per process |
| `MAX_CONCURRENT_PASSWORD_HASHES` | Argon2id hashes allowed at once (about 19 MiB each); others wait up to 10 s, then get 503 `BUSY` | `4` |
| `MAX_REQUEST_BYTES` | Largest accepted request body | `262144` |
| `FLYWAY_ENABLED` | Apply pending migrations at startup | `true` |
| `DEMO_TTL_HOURS` | Demo lifetime | `24` |
| `DEMO_CLEANUP_INTERVAL` | Expired-demo cleanup interval (ISO-8601) | `PT15M` |
| `MAINTENANCE_INTERVAL` | Expired rate-limit counter cleanup interval | `PT15M` |
| `DEMO_RATE_LIMIT_PER_HOUR` | Demo starts per client address per hour | `5` |
| `DEMO_GLOBAL_RATE_LIMIT_PER_HOUR` | Demo starts across all visitors per hour | `300` |
| `REGISTER_RATE_LIMIT_PER_HOUR` | Registrations per client address per hour | `10` |
| `REGISTER_EMAIL_RATE_LIMIT_PER_HOUR` | Registrations per email address per hour | `5` |
| `EMAIL_REQUEST_RATE_LIMIT_PER_HOUR` / `EMAIL_REQUEST_EMAIL_RATE_LIMIT_PER_HOUR` | Verification resends and reset requests, per client address / per email address | `10` / `3` |
| `LINK_RATE_LIMIT_PER_WINDOW` | Verification and reset link submissions per client address per 15 minutes | `30` |
| `PASSWORD_CHECK_RATE_LIMIT_PER_WINDOW` | Wrong current passwords (password change, deletion) per account per 15 minutes | `5` |
| `EXPORT_RATE_LIMIT_PER_HOUR` | Data exports per account per hour | `10` |
| `LOGIN_RATE_LIMIT_PER_WINDOW` | Failed sign-ins per client address per 15 minutes | `10` |
| `LOGIN_EMAIL_RATE_LIMIT_PER_WINDOW` | Failed sign-ins per email per 15 minutes, from any address | `50` |

Rate limits live in PostgreSQL (`rate_limit_counters`), so every API instance enforces the same limits. Rows hold only
keyed HMAC-SHA256 digests of client addresses and emails, never the values. Each attempt is counted before the password is
checked, so simultaneous requests can't slip past a limit, and a successful attempt takes its count back: only failures
use up an allowance. The per-email sign-in allowance is five times the per-address one, so one attacker can't lock an
owner out, and a password reset clears it. IPv6 clients share one limit per /64 network. Client addresses come from
`X-Forwarded-For` only when the immediate peer is a trusted proxy (private, loopback, or link-local addresses by default;
override the pattern with `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`).

The app forwards `/api/v1` requests with the browser's headers unchanged and adds no `X-Forwarded-For` of its own, so the
address the API sees is whatever the proxy in front of the app puts there. That proxy must replace the header with the
connecting client's address, or append that address to it (hosting providers' edges and Caddy do), and `next start` must
never be reachable directly. Otherwise a client could send its own `X-Forwarded-For` and get a fresh allowance on every
request. The staging test in nextstep-app checks this (see "Deploy" below).

### Production profile

`SPRING_PROFILES_ACTIVE=production` binds `0.0.0.0`, always sets the Secure flag on the session cookie and names it
`__Host-NEXTSTEP_SESSION` (bound to the app's HTTPS origin), and writes ECS JSON logs to standard output. It refuses to start
when `APP_PUBLIC_ORIGIN` is not a public https origin, `IDENTIFIER_HASH_KEY` is missing or shorter than 32 characters,
`DATABASE_PASSWORD` is empty, `MAIL_MODE` isn't `smtp`, `MAIL_HOST` or a real `MAIL_FROM` is missing, SMTP without STARTTLS or
authentication goes to a host other than this machine, or any rate limit is set to a local test value. The error names the
setting, never its value.

## Accounts and email

- **Registration** answers `202` with the same body whether or not the email already has an account, so it never reveals
  which addresses are registered. It starts no session. A new account is unverified until its emailed link is used; an
  existing confirmed account doesn't change, and its owner gets an "account already exists" email instead. An account that
  is still unconfirmed takes the newest registration's name, password, and time zone, and gets a new link.
- **Unconfirmed accounts can't sign in.** The answer is the same `401 INVALID_CREDENTIALS` as a wrong password; otherwise
  registering an address and signing in with the chosen password would show whether the address already had an account.
  As a second line of defense, `EmailVerificationGate` limits any session of an unconfirmed account to `GET` and
  `DELETE /me`, `GET /me/export`, and the auth endpoints (`403 EMAIL_NOT_VERIFIED` elsewhere). Accounts never verified are
  deleted after `UNVERIFIED_ACCOUNT_TTL`. Accounts that existed before verification was added count as verified.
- **Email addresses** must be bare addresses in the form browsers accept for `type="email"`, with a dot in the domain.
  Names, comments, quotes, and angle brackets are refused, and the SMTP sender also refuses any recipient with a display name.
- **Links** are 32 random bytes, single-use, and expire (24 hours to verify, 30 minutes to reset). Only a SHA-256 hash is
  stored, and the token travels in the URL fragment (`/verify-email#token=…`, `/reset-password#token=…`), so it never
  reaches server or proxy logs. Only the newest delivered link of a kind works: older links are retired once the email with
  the new one has been sent, so a failed send never breaks the link already in the inbox.
- **Password reset** answers the same for unknown emails and is limited per address and per email. Confirming checks the
  link before the new password, signs the account out everywhere, verifies the email (retiring unused confirmation links),
  and clears the email's failed sign-ins. **Password change** re-checks the current password, ends every other session,
  and rotates this one. New passwords need 12–128 characters and must not appear in Pwned Passwords; the lookup must finish,
  body included, within 3 seconds and stay under 256 KiB, or the password is allowed.
- **Export**: `GET /me/export` downloads every record the account owns as JSON (`nextstep-export/1`).
- **Deletion** needs the password. It deletes the account, its records, and its sessions; emails a notice; and records the
  account ID in `account_deletions`, so the deletion can be applied again after an older backup is restored.
- **Email delivery** goes through the `email_outbox` table: sent right after the request commits on the outbox's own
  thread, retried with backoff (up to 6 attempts), capped per hour, and claimed with `SKIP LOCKED` so instances never
  double-send. A run stops at the first failed send, so a provider outage costs one timeout per run. Requests that queue
  email share the same hourly cap, so the queue can't outgrow an hour of sending. Links are created at send time, so the
  outbox never holds a usable token. Emails never contain text a user typed.
- **Record caps**: a normal account holds at most 2,000 applications, 10,000 tasks, and 2,000 interviews (settings above),
  which also bounds the size of its export.
- **Locally**, `MAIL_MODE=log` prints each email and its link to the API log. To receive real SMTP mail locally, run
  [Mailpit](https://mailpit.axllent.org/) (`mailpit --listen 127.0.0.1:8025 --smtp 127.0.0.1:1025`) and start the API with
  `.\scripts\run-api.ps1 -Mailpit`; the inbox is at http://127.0.0.1:8025.

### Health checks

- `GET /api/v1/health` is public and returns `{"status":"UP"}`, or 503 with `{"status":"DOWN"}` when the database is
  unreachable. It is on the API path so an uptime monitor can test the whole chain through the app's origin.
- `/actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness` are for the host's probes. The app does
  not forward them, so they are reachable only on the API's own address.

### Container image

`Dockerfile` builds an image any container host can run. A JDK 25 stage packages the jar, and the JRE 25 runtime stage runs it
as a non-root user (uid 10001) with `SPRING_PROFILES_ACTIVE=production`, so it refuses to start until the production settings
above are set. The JVM uses up to 75% of the container's memory limit and exits on running out, so the host restarts it.
Base images are pinned by digest, and Dependabot proposes updates.

```bash
docker build -t nextstep-api .
docker run -p 127.0.0.1:8080:8080 \
  -e DATABASE_URL=jdbc:postgresql://<db host>:5432/nextstep -e DATABASE_USER=<user> -e DATABASE_PASSWORD=<secret> \
  -e APP_PUBLIC_ORIGIN=https://<app origin> -e IDENTIFIER_HASH_KEY=<32+ random characters> \
  -e MAIL_MODE=smtp -e MAIL_HOST=<smtp host> -e MAIL_USERNAME=<user> -e MAIL_PASSWORD=<secret> \
  -e "MAIL_FROM=NextStep <no-reply@<your domain>>" \
  nextstep-api
```

The image listens on `PORT` (8080); point the host's probes at `/actuator/health/liveness` and `/actuator/health/readiness`.
Keep the API on a private network that only the app (and the host's probes) can reach: it is never called by browsers
directly, and ports Docker publishes skip the host's firewall, so publish on 127.0.0.1 or a private network only.
Keep secrets in the host's secret manager, never in the image or the repository. CI builds the image, starts it with
production settings against PostgreSQL 18, and checks readiness, `GET /api/v1/health`, and that it doesn't run as root.

## API

The contract is [`openapi/openapi.yaml`](openapi/openapi.yaml) (OpenAPI 3.1), also served at `GET /api/v1/openapi.yaml`.
A contract test fails if documented and implemented endpoints differ. The web app generates its TypeScript types from it
(`pnpm api:types` in nextstep-app).

Key behavior:

- Sessions are opaque HttpOnly cookies (SameSite=Lax) stored in PostgreSQL, so they survive restarts. Sign-in rotates the
  session ID and the CSRF token. If a stored session can't be read after an upgrade, the browser is signed out instead of
  getting errors.
- Every unsafe request, including register, login, demo, and logout, needs the `X-CSRF-TOKEN` header from `GET /api/v1/auth/csrf`.
- Owner identity always comes from the session. Another account's record returns 404.
- Errors use `{ code, message, fieldErrors?, requestId }`. Stack traces and request bodies are never returned or logged.
- Text with control characters (NUL and similar) is a 422 field error; single-line fields reject line breaks; request
  bodies over 256 KiB get 413 `PAYLOAD_TOO_LARGE`.
- Times: date-only tasks are overdue after their local day ends; timed tasks only after their exact instant. Local times
  that don't exist or happen twice because of daylight saving are rejected. Time zones must be IANA zones PostgreSQL
  also knows (the legacy `SystemV/*` IDs are rejected).
- Scheduled jobs (expired-demo cleanup, rate-limit counter cleanup) take a PostgreSQL advisory lock, so with several API
  instances only one runs each job at a time. Demo cleanup removes at most 500 demos per run.

## Build and test

```powershell
.\mvnw.cmd verify                       # compiles, runs all 99 tests, builds target\nextstep-api-0.0.1-SNAPSHOT.jar
.\mvnw.cmd test "-Dtest=TaskTimeRulesTest"
.\scripts\db-backup-smoke.ps1           # backup and restore drill on a quiet local database (fictional data only)
```

Tests start their own temporary PostgreSQL cluster (random free port on 127.0.0.1, generated password) and delete only
that cluster's temporary directory when the JVM exits. They cover sign-in and sessions, CSRF, cookie attributes and
security headers under the production profile, registration that reveals nothing, email verification and password reset
link lifecycles, session invalidation after a reset or password change, the breached-password check, data export,
deletion and its re-application after a restore, email retries, rate limits (including limits shared between two running API instances),
ownership isolation, validation and PATCH semantics, input limits, archive/restore, search and pagination, time-zone and
daylight-saving rules, interview windows, demo isolation, expiry, cleanup and its cross-instance lock, and the OpenAPI
contract.

The backup drill (`db-backup-smoke.ps1`) dumps a database, restores it into a scratch database, compares every table's
row count and content checksum, re-applies a recorded deletion in the restored copy, and drops the scratch database.

**Windows note:** on some machines the JDK can't create the Unix-domain socket NIO uses internally when its file lives
under the user profile ("Unable to establish loopback connection"). A Maven profile that activates on Windows points
`jdk.net.unixdomain.tmpdir` at `%PUBLIC%` for tests and `spring-boot:run`. Override it with `-Dnextstep.socket.tmpdir=<folder>`.

## Continuous integration and scanning

GitHub Actions run on every push and pull request:

- `.github/workflows/ci.yml`: `./mvnw -B verify` on JDK 25 with PostgreSQL 18 binaries, plus a check that fails when a
  Flyway migration already on the branch is edited, renamed, or deleted.
- `.github/workflows/security.yml` (also weekly): OSV-Scanner over the resolved runtime dependency list (a CycloneDX file,
  because `pom.xml` alone misses versions managed by the Spring Boot parent) and a gitleaks scan of the full git history.
  Both tools are downloaded at pinned versions and checked against pinned SHA-256 hashes.
- Dependabot opens weekly update pull requests for Maven dependencies and GitHub Actions.

The same scans locally (tools from their GitHub releases):

```powershell
.\mvnw.cmd -q org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom "-DoutputFormat=json" "-DoutputName=bom" "-DincludeTestScope=false"
osv-scanner scan source -L target/bom.json
gitleaks git . --redact
```

## Releasing a migration

- Add a new `V<n>__description.sql`; never edit, rename, or delete a migration once it is on `main` (CI enforces this).
- Keep changes backward compatible across one release (expand, then contract): add nullable columns or new tables first,
  ship code that works with both shapes, and drop old columns in a later release.
- Start statements that take strong locks with `SET lock_timeout = '5s';` so a busy table makes the migration fail fast
  instead of blocking traffic. Build large indexes with `CREATE INDEX CONCURRENTLY` in a migration of their own, with
  Flyway's `executeInTransaction=false` script setting.
- Take a backup first and run the migration against a restored copy. Pending migrations apply when the API starts;
  Flyway's lock makes concurrent instances wait for the first one. Recover from a bad migration by shipping a new forward
  migration, or by restoring the backup.
- Rehearse the release on the restored copy: point a staging API (nextstep-app's `scripts\staging.ps1`, or the container
  image) at it, let Flyway apply the migration, and run the checks for the release.
- When a release changes behavior that older instances would get wrong, it can't run alongside them, so stop the old
  instances before the first new one starts, accepting a short outage. V3 is such a release: an instance from before V3
  would let unconfirmed accounts in and create accounts that never get a confirmation link. Say so in the commit message
  and the release notes whenever a migration needs this.
- To run migrations as a separate release step instead of at startup, run one instance with `FLYWAY_ENABLED=true` as
  a one-off job, then start the regular instances with `FLYWAY_ENABLED=false`.

## Operations runbooks

These steps don't depend on a particular host; the host's own console replaces the shell commands where it offers them.

**Deploy**

1. Confirm CI is green on `main`.
2. If the release contains a migration, take a backup first (host snapshot, or `pg_dump --format=custom --no-owner`).
3. Deploy the API with `SPRING_PROFILES_ACTIVE=production` and secrets from the host's secret manager. Start one instance
   first when there is a migration; the others wait on Flyway's lock.
4. Check `/actuator/health/readiness` on the API and `GET /api/v1/health` through the app's origin.
5. Deploy the app (built with `API_ORIGIN` pointing at the API, run with `APP_HTTPS=true`), then sign in, open the
   dashboard, and sign out.
6. After the first deploy on a new host, confirm per-address limits see the real client address. From nextstep-app, run
   `$env:STAGING_URL = "https://<app origin>"; pnpm test:staging -g "per-address"`. It sends sign-in attempts that
   each claim a different `X-Forwarded-For` address and expects the limit to stop them, so it also uses up your own
   address's sign-in allowance for 15 minutes.

**Monitor**

- Uptime: an external monitor requests `https://<app origin>/api/v1/health` every minute and alerts after two failures
  in a row. One request covers TLS, the proxy, the app, the API, and the database. A second check requests the website's
  home page.
- Host probes: liveness `/actuator/health/liveness` and readiness `/actuator/health/readiness` on the API's own port.
- Log alerts: any `ERROR` entry (for example `Unexpected error on …` or `Gave up on a … email after 6 attempts`), and the
  warning `The hourly email limit is reached`, which means sign-up and reset emails are being delayed.
- The monitoring provider is still to be chosen; these checks need only HTTP probes and log-based alerts.

**Roll back**

- Redeploy the previous build of the app or API. Migrations stay in place, and the expand-then-contract rule means the
  previous version still works with the newer schema.
- For a migration that damaged data, ship a forward fix, or restore a backup as below.

**Restore a backup**

1. If the current database is still reachable, save its deletion records first:
   `\copy (SELECT account_id, deleted_at, reason FROM account_deletions) TO 'deletions.csv' CSV` in psql. If it isn't, take
   the `Deleted account <id>` lines from the API logs for the time since the backup.
2. Restore the backup into a new database (the host's restore, or `pg_restore --no-owner --exit-on-error`).
3. Load the saved records into the restored database:
   `CREATE TEMP TABLE d (LIKE account_deletions); \copy d FROM 'deletions.csv' CSV`, then
   `INSERT INTO account_deletions SELECT * FROM d ON CONFLICT DO NOTHING;`.
4. Run `SELECT nextstep_reapply_deletions();`, which deletes every recorded account again, with its records and sessions.
5. Point the API at the restored database and check health. Sessions created after the backup are gone, so people sign
   in again.
6. Rehearse steps 2–4 with `.\scripts\db-backup-smoke.ps1`.

**Rotate secrets**

- `DATABASE_PASSWORD`: `ALTER ROLE … PASSWORD …`, update the secret, and restart instances one at a time.
- `IDENTIFIER_HASH_KEY`: update it for every instance and restart them together. Existing rate-limit counters stop
  matching, so limits start fresh; nothing else depends on it.
- `MAIL_PASSWORD`: create a new SMTP credential at the provider, update the secret, restart, then revoke the old one.
- To sign everyone out (for example after a suspected leak), run `TRUNCATE spring_session CASCADE;`.

## Project layout

```
src/main/java/com/nextstep/api/
  common/        errors, validation, request limits, shared rate limiting, job locks, health, time rules, clock
  identity/      accounts, Spring Security, sessions, passwords, verification and reset links, account email outbox,
                 export, deletion records, auth and profile endpoints
  mail/          email sending (log mode and SMTP)
  applications/  application records, status, archive, search
  tasks/         tasks and due states
  interviews/    interview records
  dashboard/     dashboard aggregates
  demo/          demo seeding and expired-demo cleanup
src/main/resources/db/migration/   Flyway migrations (schema, Spring Session tables, rate-limit counters, link tokens,
                                   email outbox, deletion records)
openapi/openapi.yaml               published contract
scripts/                           local database, run, and backup scripts
.github/                           CI, security scanning, Dependabot
```

## Project context (Obsidian)

Product requirements, rules, and decisions live in the owner's local Obsidian vault. **These links point to a local vault on the owner's machine. They won't open for anyone without access to it**; on this machine, an agent can read the Markdown files directly from the absolute paths.

- Project home: [obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FHome](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FHome)
- Vault folder: `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker`

| Note | Obsidian link | File path |
| --- | --- | --- |
| Home | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FHome) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Home.md` |
| Agent Handoff | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FAgent%20Handoff) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Agent Handoff.md` |
| Business Rules | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FProduct%2FBusiness%20Rules) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Product\Business Rules.md` |
| Data Model | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FArchitecture%2FData%20Model) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Architecture\Data Model.md` |
| API Contract | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FArchitecture%2FAPI%20Contract) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Architecture\API Contract.md` |
| System Map | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FArchitecture%2FSystem%20Map) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Architecture\System Map.md` |
| Security and Privacy | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FOperations%2FSecurity%20and%20Privacy) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Operations\Security and Privacy.md` |
| Environments and Deployment | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FOperations%2FEnvironments%20and%20Deployment) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Operations\Environments and Deployment.md` |
| Testing Strategy | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FQuality%2FTesting%20Strategy) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Quality\Testing Strategy.md` |
| Decision Log | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FDecisions%2FDecision%20Log) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Decisions\Decision Log.md` |
| Account and Profile | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FProduct%2FFeatures%2FAccount%20and%20Profile) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Product\Features\Account and Profile.md` |
| Applications | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FProduct%2FFeatures%2FApplications) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Product\Features\Applications.md` |
| Tasks and Follow-ups | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FProduct%2FFeatures%2FTasks%20and%20Follow-ups) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Product\Features\Tasks and Follow-ups.md` |
| Interviews | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FProduct%2FFeatures%2FInterviews) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Product\Features\Interviews.md` |
| Dashboard | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FProduct%2FFeatures%2FDashboard) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Product\Features\Dashboard.md` |
| Demo Workspace | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FProduct%2FFeatures%2FDemo%20Workspace) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Product\Features\Demo Workspace.md` |
| Search, Filters and Sorting | [Open in Obsidian](obsidian://open?vault=Obsidian%20Vault&file=Projects%2FJob%20Application%20Tracker%2FProduct%2FFeatures%2FSearch%20Filters%20and%20Sorting) | `C:\Users\Al Prince\Documents\Obsidian Vault\Projects\Job Application Tracker\Product\Features\Search Filters and Sorting.md` |
