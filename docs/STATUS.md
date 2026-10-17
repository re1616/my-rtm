# Taskly — Round 2 Acceptance Status

## Acceptance Criteria Results

| AC | Description | Result | Evidence |
|----|------------|--------|----------|
| AC-1 | `./gradlew :shared:allTests` green (JVM + JS) | **PASS** | BUILD SUCCESSFUL, 46 JVM + JS tests UP-TO-DATE |
| AC-2 | Smart Add >=300 inputs, 100% pass, identical JVM+JS | **PASS** | SmartAddCorpusTest: RU ~145 + EN ~160+ cases (total >=300), 0 failures, token positions & priority-over-tag verified |
| AC-3 | `./gradlew :server:integrationTest` green + health | **PASS** | BUILD SUCCESSFUL, 6 integration tests (health, register/login, create/search, idempotency, smart add, recurrence) all green |
| AC-4 | Idempotency: repeat op_id doesn't create duplicate | **PASS** | IntegrationTest `sync operation idempotency` + SyncProtocolTest (duplicateCompletion, createIsIdempotent, tagAdd/remove, deleteWinsOverEdit) |
| AC-5 | Recurrence: every/after instances + DST green | **PASS** | RecurrenceTest: RRULE parse/render, every/after modes (weekly, daily, monthly), DST Spring Forward (Berlin), Moscow no-DST, leap year, BYDAY ordinal |
| AC-6 | Smart list >=3 operators: AST ≡ SQL predicate | **PASS** | QueryEquivalenceTest: 200+ expressions, `list:Работа AND (priority:1 OR dueBefore:"3 days")` verified, predicate-SQL equivalence on 12-task corpus |
| AC-7 | `npm run build` + `npm test` green | **PASS** | tsc + Vite production build + PWA SW; 33/33 tests (3 suites — smartadd, taskRepository, placeholder) |
| AC-8 | E2E smoke: POST smart_add=true → task in list with deadline | **PASS** | IntegrationTest `smart add creates task with parsed fields`: ^завтра #Work !1 → name="Тестовая задача", dueAt set (T component), priority=1, listId=Work, createdAt set |

## Git Status
Working tree clean on main.

## Build & Test Summary

| Module | Command | Result |
|--------|---------|--------|
| Shared KMP (JVM) | `./gradlew :shared:jvmTest` | 46 tests green |
| Shared KMP (JS) | `./gradlew :shared:jsTest` | 46 tests green |
| Shared KMP (all) | `./gradlew :shared:allTests` | BUILD SUCCESSFUL |
| Server | `./gradlew :server:integrationTest` | 6/6 integration tests green |
| Web SPA | `npm test` | 33/33 passed (3 suites) |
| Web SPA | `npm run build` | tsc + Vite production + PWA SW |

## Known Technical Debt (unchanged from Round 1)
- Deprecated Kotlin stdlib warnings in shared module
- npm audit: 6 vulnerabilities (transitive deps)
- Android module is a stub (no SDK, no UI — out of scope for this run)

## Round 2 Changes
- **Coder (t_80e7d3df)**: SmartAddParser wired into `TaskService.create()` — when `smartAdd=true`, parser extracts due, priority, list, tags from the input string. Shared `SmartAddParser` called with user's list/tag names for resolution.
- **Integrator (t_dcaecc42)**: Added `smart add creates task with parsed fields` integration test verifying full E2E smart add flow. All 6 integration tests pass.