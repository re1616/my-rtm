# Taskly — Round 1 Acceptance Status

## Acceptance Criteria Results

| AC | Description | Result | Evidence |
|----|------------|--------|----------|
| AC-1 | `./gradlew :shared:allTests` green (JVM + JS) | **PASS** | BUILD SUCCESSFUL, JVM 46/JS 46 tests UP-TO-DATE |
| AC-2 | Smart Add >=300 inputs, 100% pass, identical JVM+JS | **PASS** | SmartAddCorpusTest: 598 cases (RU+EN+combinations), corpusTotalAtLeast300 check, token position & priority-over-tag tests |
| AC-3 | `./gradlew :server:integrationTest` green + health | **PASS** | BUILD SUCCESSFUL (integration tests), `curl localhost:8080/health` → 200 |
| AC-4 | Idempotency: repeat op_id doesn't create duplicate | **PASS** | IntegrationTest: `sync operation idempotency` + manual curl (SYNC1 version=1, SYNC2 version=null) |
| AC-5 | Recurrence: every/after instances + DST green | **PASS** | RecurrenceTest: RRULE parse/render, every/after modes, DST Spring Forward (Berlin), Moscow no-DST |
| AC-6 | Smart list >=3 operators: AST ≡ SQL predicate | **PASS** | QueryEquivalenceTest: 200+ expressions, `list:Работа AND (priority:1 OR dueBefore:"3 days")` verified |
| AC-7 | `npm run build` + `npm test` green | **PASS** | tsc + Vite production build + PWA SW; 33/33 tests (3 suites) |
| AC-8 | E2E smoke: POST smart_add=true → task in list with deadline | **FAIL** | Task created but smartAdd parsing NOT wired on server REST API — `^завтра #Work !1` stored as raw name, due/priority/tags null |

## Git Status
Working tree clean on main.

## Build & Test Summary

| Module | Command | Result |
|--------|---------|--------|
| Shared KMP (JVM) | `./gradlew :shared:jvmTest` | 46 tests green |
| Shared KMP (JS) | `./gradlew :shared:jsTest` | 46 tests green |
| Shared KMP (all) | `./gradlew :shared:allTests` | BUILD SUCCESSFUL |
| Server | `./gradlew :server:integrationTest` | BUILD SUCCESSFUL |
| Web SPA | `npm test` | 33/33 passed (3 suites) |
| Web SPA | `npm run build` | tsc + Vite production + PWA SW |

## Failure Analysis: AC-8

**Root cause**: `POST /v1/tasks` accepts `smartAdd: Boolean` in `CreateTaskRequest` DTO, but `TaskService.create()` never calls the shared KMP `SmartAddParser`. The raw `name` is stored as-is.

**Required fix**: In `TaskService.create()`, when `request.smartAdd == true`, invoke `SmartAddParser` from shared module, extract structured fields (due, priority, list, tags, repeat, estimate, location, note), populate them on the task record.

## Known Technical Debt
- Deprecated `kotlinx.datetime.Instant` typealias usage in shared module (34 warnings)
- Deprecated `monthNumber`/`dayOfMonth` in recurrence code (migrate to `month`/`day`)
- 1 shadowed extension in `SyncService.kt` (`.data()` vs JSONB.data())
- npm audit: 6 vulnerabilities (3 moderate, 1 high, 2 critical — likely transitive deps)