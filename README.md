# NextStep API

Spring Boot REST API for **NextStep**, a portfolio job-application tracker. It owns identity, sessions, CSRF protection,
ownership checks, validation, business rules (statuses, archive, time zones, due states), search, the dashboard, and the
temporary demo workspace. Data is stored in PostgreSQL.

> Portfolio MVP. Email verification, password reset, hosting, backup and log retention, and a production privacy policy
> are still open, so this API is not ready to hold real users' job searches. Nothing is deployed.

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
| `LOGIN_RATE_LIMIT_PER_WINDOW` | Sign-in attempts per address per 15 minutes; also failed attempts per email | `10` |

Rate limits live in PostgreSQL (`rate_limit_counters`), so every API instance enforces the same limits. Rows hold only
keyed HMAC-SHA256 digests of client addresses and emails, never the values. For an email, only failed sign-ins count, so an
owner's own sign-ins never lock them out. Client addresses come from `X-Forwarded-For` only when the immediate peer is a
trusted proxy (private, loopback, or link-local addresses by default; override the pattern with
`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`).

### Production profile

`SPRING_PROFILES_ACTIVE=production` binds `0.0.0.0`, always sets the Secure flag on the session cookie and names it
`__Host-NEXTSTEP_SESSION` (bound to the app's HTTPS origin), and writes ECS JSON logs to standard output. It refuses to start
when `APP_PUBLIC_ORIGIN` is not a public https origin, `IDENTIFIER_HASH_KEY` is missing or shorter than 32 characters,
`DATABASE_PASSWORD` is empty, or rate limits are set to local test values. The error names the setting, never its value.

### Health checks

- `GET /api/v1/health` is public and returns `{"status":"UP"}`, or 503 with `{"status":"DOWN"}` when the database is
  unreachable. It is on the API path so an uptime monitor can test the whole chain through the app's origin.
- `/actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness` are for the host's probes. The app does
  not forward them, so they are reachable only on the API's own address.

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
.\mvnw.cmd verify                       # compiles, runs all 57 tests, builds target\nextstep-api-0.0.1-SNAPSHOT.jar
.\mvnw.cmd test "-Dtest=TaskTimeRulesTest"
.\scripts\db-backup-smoke.ps1           # local pg_dump/pg_restore round trip with row-count comparison (fictional data only)
```

Tests start their own temporary PostgreSQL cluster (random free port on 127.0.0.1, generated password) and delete only
that cluster's temporary directory when the JVM exits. They cover sign-in and sessions, CSRF, cookie attributes and
security headers under the production profile, rate limits (including limits shared between two running API instances),
ownership isolation, validation and PATCH semantics, input limits, archive/restore, search and pagination, time-zone and
daylight-saving rules, interview windows, demo isolation, expiry, cleanup and its cross-instance lock, and the OpenAPI
contract.

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

## Project layout

```
src/main/java/com/nextstep/api/
  common/        errors, validation, request limits, shared rate limiting, job locks, health, time rules, clock
  identity/      accounts, Spring Security, sessions, password hashing, auth and profile endpoints
  applications/  application records, status, archive, search
  tasks/         tasks and due states
  interviews/    interview records
  dashboard/     dashboard aggregates
  demo/          demo seeding and expired-demo cleanup
src/main/resources/db/migration/   Flyway migrations (schema, Spring Session tables, rate-limit counters)
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
