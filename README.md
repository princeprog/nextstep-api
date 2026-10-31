# NextStep API

Spring Boot REST API for **NextStep**, a portfolio job-application tracker. It owns identity, sessions, CSRF protection,
ownership checks, validation, business rules (statuses, archive, time zones, due states), search, the dashboard, and the
temporary demo workspace. Data is stored in PostgreSQL.

> Portfolio MVP. Account recovery, email verification, hosting, retention, and a production privacy policy are
> undecided, so this API is not ready to hold real users' job searches.

## Related projects

| Repository | Role |
| --- | --- |
| [nextstep-app](https://github.com/princeprog/nextstep-app) | Next.js web app (shadcn/ui, TanStack Query, Zustand). Calls this API on the same origin at `/api/v1`. |
| [nextstep-api](https://github.com/princeprog/nextstep-api) | This API. |
| [nextstep](https://github.com/princeprog/nextstep) | Separate static marketing website. It has no runtime dependency on this API. |

## Stack

- Java 25, Spring Boot 4.1.2 (Spring Framework 7, Spring Security 7.1), Maven Wrapper
- Spring Web MVC, Spring Data JPA (Hibernate 7.4), JdbcTemplate for search and dashboard queries
- PostgreSQL 17+ (developed on 18.1), Flyway 12 migrations, Spring Session JDBC
- Argon2id password hashing (Bouncy Castle)
- Tests: JUnit 6 integration tests over real HTTP against a disposable PostgreSQL cluster

## Prerequisites

- JDK 25 (`JAVA_HOME` set, or a JDK under `%LOCALAPPDATA%\Programs\Java\jdk-25*` for the scripts)
- PostgreSQL binaries installed locally (`initdb`, `pg_ctl`, `psql`). Docker is not used.
  The scripts find them through `NEXTSTEP_PG_BIN`, then `PATH`, then the newest `C:\Program Files\PostgreSQL\<version>\bin`.

## Local setup (Windows PowerShell)

```powershell
# One time: create a dedicated cluster on 127.0.0.1:55432 under %LOCALAPPDATA%NextSteppgdata-dev.
# Generated credentials are written to %LOCALAPPDATA%NextStepdev-db.json (outside the repository).
.scriptsdb-dev.ps1 init

.scriptsdb-dev.ps1 start     # later sessions
.scriptsdb-dev.ps1 status
.scriptsun-api.ps1          # http://127.0.0.1:8080, applies Flyway migrations on start
.scriptsdb-dev.ps1 stop
```

The development cluster never touches other PostgreSQL services on the machine. `run-api.ps1 -RelaxedLimits` raises the
demo, registration, and login rate limits for local end-to-end test runs; never use it for a shared deployment.

## Configuration

All values have local defaults in `src/main/resources/application.properties`. Secrets come from the environment only.

| Variable | Purpose | Default |
| --- | --- | --- |
| `DATABASE_URL` | JDBC URL | `jdbc:postgresql://127.0.0.1:55432/nextstep` |
| `DATABASE_USER` / `DATABASE_PASSWORD` | Database credentials (set by `run-api.ps1`) | `nextstep` / empty |
| `SESSION_TIMEOUT` | Inactivity timeout for normal sessions | `30m` |
| `COOKIE_SECURE` | Mark the session cookie Secure (enable behind HTTPS) | `false` |
| `SERVER_ADDRESS` / `PORT` | Bind address and port | `127.0.0.1` / `8080` |
| `APP_PUBLIC_ORIGIN` | Public origin of the web app | `http://localhost:3000` |
| `DEMO_TTL_HOURS` | Demo lifetime | `24` |
| `DEMO_CLEANUP_INTERVAL` | Expired-demo cleanup interval (ISO-8601) | `PT15M` |
| `DEMO_RATE_LIMIT_PER_HOUR` | Demo starts per client address per hour | `5` |
| `REGISTER_RATE_LIMIT_PER_HOUR` | Registrations per client address per hour | `10` |
| `LOGIN_RATE_LIMIT_PER_WINDOW` | Login attempts per address and per email per 15 minutes | `10` |

Rate limits are kept in memory, which suits one API instance. Client addresses come from `X-Forwarded-For` only when the
immediate peer is a trusted (private or loopback) proxy.

## API

The contract is [`openapi/openapi.yaml`](openapi/openapi.yaml) (OpenAPI 3.1), also served at `GET /api/v1/openapi.yaml`.
A contract test fails if documented and implemented endpoints differ. The web app generates its TypeScript types from it
(`pnpm api:types` in nextstep-app).

Key behavior:

- Sessions are opaque HttpOnly cookies (`NEXTSTEP_SESSION`, SameSite=Lax) stored in PostgreSQL, so they survive restarts.
  Sign-in rotates the session ID and the CSRF token.
- Every unsafe request, including register, login, demo, and logout, needs the `X-CSRF-TOKEN` header from `GET /api/v1/auth/csrf`.
- Owner identity always comes from the session. Another account's record returns 404.
- Errors use `{ code, message, fieldErrors?, requestId }`. Stack traces and request bodies are never returned or logged.
- Times: date-only tasks are overdue after their local day ends; timed tasks only after their exact instant. Local times
  that don't exist or happen twice because of daylight saving are rejected.

## Build and test

```powershell
.mvnw.cmd verify            # compiles, runs all integration tests, builds target
extstep-api-0.0.1-SNAPSHOT.jar
.mvnw.cmd test -Dtest=TaskTimeRulesTest
.scriptsdb-backup-smoke.ps1  # local pg_dump/pg_restore round trip with row-count comparison (fictional data only)
```

Tests start their own temporary PostgreSQL cluster (random free port on 127.0.0.1, generated password) and delete only
that cluster's temporary directory when the JVM exits. They cover sign-in and sessions, CSRF, cookie attributes, rate
limits, ownership isolation, validation and PATCH semantics, archive/restore, search and pagination, time-zone and
daylight-saving rules, interview windows, demo isolation, expiry, and cleanup, and the OpenAPI contract.

**Windows note:** on some machines the JDK can't create the Unix-domain socket NIO uses internally when its file lives
under the user profile ("Unable to establish loopback connection"). A Maven profile that activates on Windows points
`jdk.net.unixdomain.tmpdir` at `%PUBLIC%` for tests and `spring-boot:run`. Override it with `-Dnextstep.socket.tmpdir=<folder>`.

## Project layout

```
src/main/java/com/nextstep/api/
  common/        errors, validation, pagination, rate limiting, time rules, clock
  identity/      accounts, Spring Security, sessions, auth and profile endpoints
  applications/  application records, status, archive, search
  tasks/         tasks and due states
  interviews/    interview records
  dashboard/     dashboard aggregates
  demo/          demo seeding and expired-demo cleanup
src/main/resources/db/migration/   Flyway migrations (schema and Spring Session tables)
openapi/openapi.yaml               published contract
scripts/                           local database, run, and backup scripts
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

