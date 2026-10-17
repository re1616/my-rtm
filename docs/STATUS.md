# Taskly — Round 1 Integration Status

## Build & Test Summary

| Module | Command | Result |
|--------|---------|--------|
| Shared KMP (JVM) | `./gradlew :shared:jvmTest` | 46 tests green |
| Shared KMP (JS) | `./gradlew :shared:jsTest` | 46 tests green |
| Shared KMP (all) | `./gradlew :shared:allTests` | BUILD SUCCESSFUL |
| Server | `./gradlew :server:build` | BUILD SUCCESSFUL (incl. tests) |
| Web SPA | `npm test` | 33/33 passed (3 suites) |
| Web SPA | `npm run build` | tsc + Vite production bundle + PWA SW |

## Acceptance Criteria

| AC | Description | Status |
|----|-------------|--------|
| AC-1 | `./gradlew :shared:allTests` green (JVM + JS) | OK |
| AC-2 | Smart Add >=300 inputs, 100% pass, identical on JVM + JS | OK (46 tests, 598 corpus cases) |
| AC-3 | `./gradlew :server:build` green | OK |
| AC-4 | Idempotent retry doesn't create duplicate | OK (sync protocol tests) |
| AC-5 | Recurrence every/after creates next instance; DST green | OK (RecurrenceTest, 271 cases) |
| AC-6 | Smart list >=3 operators: same AST and SQL | OK (QueryEquivalenceTest, 452 cases) |
| AC-7 | `npm run build` green; `npm test` green | OK |
| AC-8 | git status clean; STATUS.md filled | OK |

## Merge Log (3 branches)

1. `wt/t_a19568cf` — shared KMP (domain, Smart Add, queries, recurrence, sync)
2. `wt/t_a3d87d0f` — server (Ktor API, DB, auth, sync, WebSocket)
3. `wt/t_595ccd29` — web SPA (React 19, PWA, offline, Smart Add UI)

All merges clean, no conflicts. Worktrees and branches removed.

## Child Task

- `t_bd3d9830` — QA/Review Round 1 (pending)

## Known Technical Debt

- Deprecated `kotlinx.datetime.Instant` typealias usage in shared module (34 warnings)
- Deprecated `monthNumber`/`dayOfMonth` in recurrence code (migrate to `month`/`day`)
- 1 shadowed extension in `SyncService.kt` (`.data()` vs JSONB.data())
- npm audit: 6 vulnerabilities (3 moderate, 1 high, 2 critical — likely transitive deps)