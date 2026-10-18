# Taskly — Round 3 Status

Acceptance is checked by CI (`.github/workflows/ci.yml`) on real PostgreSQL 16 and Redis 7,
not by hand. Reference run for this round:
https://github.com/re1616/my-rtm/actions/runs/39104941139 — all jobs green.

## Acceptance criteria

| AC | Description | Result | Evidence (CI) |
|----|-------------|--------|---------------|
| AC-1 | `./gradlew :shared:allTests` green (JVM + JS) | **PASS** | job *Shared + Server*, step *Shared tests* |
| AC-2 | Smart Add corpus ≥300, 100 %, same on JVM and JS | **PASS** | `SmartAddCorpusTest` on both targets; the web now runs the same Kotlin/JS parser (`web/src/__tests__/smartadd.test.ts` uses corpus cases) |
| AC-3 | `:server:integrationTest` green + health | **PASS** | 6 + 15 integration tests (`IntegrationTest`, `IntegrationTestSync`); server started with `:server:run` and `/health` polled before the web E2E |
| AC-4 | Repeated `op_id` does not duplicate | **PASS** | `sync operation idempotency`, `one failing op does not abort the batch`, `op ids are scoped per user` |
| AC-5 | Recurrence every/after + DST | **PASS** | server now uses the shared `Recurrence`: `every mode keeps schedule and local time…` (BYDAY, tags copied, no duplicate on second complete), `after mode shifts from completion date`, `series ends at UNTIL`, `sync complete creates next instance once`; DST cases in `RecurrenceTest` |
| AC-6 | Smart list ≥3 operators: client predicate ≡ API | **PASS** | API uses shared `QueryParser`/`SqlBuilder`: `API evaluates the shared query language (AC-6)` with `list:Работа AND (priority:1 OR dueBefore:"3 days")`; `SqlRendererTest` pins operator precedence |
| AC-7 | Web build + tests, incl. offline → online → visible in API | **PASS** | job *Web SPA* (tsc, Vite build, Vitest); `offlineSync.test.ts` (fake API) and `offlineSync.e2e.test.ts` against the running server (also verifies the server-created next instance is pulled back) |
| AC-8 | E2E smoke: `smart_add=true` → task in list with due date | **PASS** | `smart add creates task with parsed fields` |

### What was wrong before this round
Round 2 reported 8/8 PASS, but several criteria were met only inside the `shared` library:
the API used its own query parser (no AND/OR/parentheses) and a `+1 day/week` recurrence, the
web used a TypeScript copy of the parser and a sync loop that marked ops as sent without sending
them, and no offline→API test existed. Integration tests also could not run as configured
(`pg_trgm` missing; env overrides ignored, so they targeted the main `taskly` database).

## Fixed in round 3
**Security**
- `JWT_SECRET` and all `PG*`/`REDIS_*` env vars were ignored (HOCON order) → production signed tokens with the public dev secret. Fixed; the server refuses the dev secret outside `TASKLY_ENV=dev`.
- Sync `create` re-assigned an existing task/list/tag to the caller (takeover by id). Fixed.
- Client-chosen scopes for ops, `/v1/sync/changes`, WebSocket. Now server-derived; foreign scope → 403.
- Tasks could be put into another user's list. Fixed.
- Rate limiter was never applied. Now per token / per IP, stricter for `/v1/auth/*`, 429 + Retry-After.
- 401/403/404/409 instead of 400/500; 5xx no longer leak exception messages; WebSocket Redis subscriptions are released on close.

**Correctness**
- Shared `SqlRenderer` dropped parentheses (`a AND (b OR c)` → `a AND b OR c`); `postponed` column name; missing `locations`/`list_members` tables.
- Sync batch: one failing op aborted the transaction and rolled back ops reported as ok → savepoint per op; `processed_ops` keyed per user.
- Sync: JSON `null` became the string `"null"`; versions were fake; LWW via shared `MergeEngine`; sync `complete` now creates the next instance; REST mutations are written to `change_log`; WebSocket signals after commit.
- Web: app crashed on every start after the first (system lists seed); UI did not re-render after adding a task; `#List` ignored; un-complete impossible.

## Known gaps (not in this round)
- RRULE `COUNT` is not enforced (UNTIL is).
- Shared lists / `list:<id>` scopes, contacts, reminders — per SPEC non-goals.
- Web pull applies task changes only; lists created on another device are not pulled yet, so their tasks show under "Все задачи".
- MiniSearch worker is not wired into the UI; list virtualization (>200) not implemented.
- Android module is still a stub; npm audit reports transitive dev-dependency advisories.

## Commands
| Area | Command |
|------|---------|
| Shared | `./gradlew :shared:allTests` |
| Server | `./gradlew :server:test :server:integrationTest` (needs PG with `pg_trgm`, `pgcrypto`; DB name must end with `_test`) |
| Server run | `./gradlew :server:run` (set `TASKLY_ENV=production` + `JWT_SECRET` in real deployments) |
| Web | `cd web && npm run shared && npm ci && npm test && npm run build` |
| Web E2E | `TASKLY_E2E_URL=http://localhost:8080 npm run test:e2e` |
