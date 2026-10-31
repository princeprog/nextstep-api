# AGENTS.md — nextstep-api

Guidance for coding agents working on the NextStep Spring Boot API.

## Read the project context first

Before changing behavior, read the relevant notes in the owner's Obsidian vault, starting with **Agent Handoff**,
**Business Rules**, **Data Model**, and **API Contract**. When notes disagree, follow newer explicit user decisions, then
the Decision Log, Product Requirements, feature notes, and supporting notes, in that order. Record significant
implementation decisions in the vault's Decision Log without claiming results you haven't verified.

## Obsidian links

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

## Architecture boundaries

- This API is the source of truth for rules: ownership, validation, statuses, archive, time zones, due states, search,
  counts, and demo expiry. The web app formats and displays; it must not reimplement these rules.
- Derive the owner from the session (`@AuthenticationPrincipal AccountPrincipal`). Never read an owner ID from a request.
  Load records with `findByIdAndOwnerId` or owner-scoped SQL; another account's ID returns 404.
- Archived applications and their tasks and interviews are read-only. Mutations must return 409 `APPLICATION_ARCHIVED`.
- Use `TimeRules` and the injected `Clock` for every time decision. Keep `DueState` (Java) and the SQL in `TaskQueries` in agreement.
- Request bodies are read through `JsonInput`, which keeps an omitted field distinct from an explicit null and rejects
  control characters. Use the `Validators` helpers so single-line fields stay single-line. Validate the merged record.
- Rate limits go through `RateLimiter` (shared through PostgreSQL); never add in-memory counters. Keys are hashed by
  `IdentifierHasher`, so never store raw client addresses or emails.
- Scheduled work must run through `ClusterJobLock` so only one API instance runs it at a time, and should work in bounded batches.
- Schema changes go in a new Flyway migration (`V3__...sql` next); never edit, rename, or delete one that is on `main`
  (CI enforces it). Follow "Releasing a migration" in the README.
- Endpoint changes must update `openapi/openapi.yaml` (`OpenApiContractTest` enforces it). Then regenerate app types with
  `pnpm api:types` in nextstep-app.
- Don't log request bodies, passwords, session or CSRF values, email addresses, client addresses, notes, or contact details.
- Keep CSRF on for all unsafe endpoints, including register, login, demo, and logout.
- New settings that are only safe locally must be covered by `ProductionSettingsCheck`.

## Verification

```powershell
.\mvnw.cmd verify    # must pass before you finish
```

Add or update integration tests in `src/test/java` (extend `IntegrationTestSupport`, use `TestBrowser` and `MutableClock`) for
any rule you change, especially ownership, sessions and CSRF, archive behavior, rate limits, and time boundaries. CI also
runs the dependency and secret scans described in the README.

## Don'ts

- No production deployment, external services, or real personal data without an explicit request from the owner.
- Don't claim production readiness: email verification, password reset, hosting, retention, and the privacy policy are
  still open decisions.
