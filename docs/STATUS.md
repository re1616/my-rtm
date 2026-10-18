# Taskly — Round 1 Production Core Acceptance Status

Дата: 2026-10-18

## Acceptance Criteria Results

| # | AC | Команда | Результат | Доказательство |
|---|----|---------|-----------|---------------|
| AC-1 | Shared KMP allTests (JVM + JS) | `./gradlew :shared:allTests` | **PASS** | BUILD SUCCESSFUL. JVM: 61 tests (SyncProtocol 12, QueryEquivalence 6, SmartAddCorpus 5, Platform 1, Recurrence 37). JS: idem (61). 0 failures, 0 errors. |
| AC-2 | Server build | `./gradlew :server:build` | **PASS** | BUILD SUCCESSFUL (7s) |
| AC-3 | Server unit tests | `./gradlew :server:test` | **PASS** | BUILD SUCCESSFUL. 38 tests: FeaturesIntegration 8, Config 9, SyncIntegration 7, Security 7, Integration 6, Health 1. 0 failures. |
| AC-4 | Server integration tests | `./gradlew :server:integrationTest` | **PASS** | BUILD SUCCESSFUL (3s). 6 integration tests (health, auth, CRUD, idempotency, smart add, recurrence). 0 failures. |
| AC-5 | Server health endpoint | `./gradlew :server:run` + `curl /health` | **PASS** | HTTP 200. Body: `{"status":"ok","service":"taskly"}` |
| AC-6 | Web tests | `npm test` (web/) | **PASS** | Vitest 2.1.9: 3 suites, 33/33 tests passed (smartadd 18, taskRepository 14, placeholder 1). Duration 4.25s. |
| AC-7 | Web production build | `npm run build` (web/) | **PASS** | tsc + Vite 6.4.4 production build. JS 363 KiB (114 KiB gzip), CSS 16 KiB. PWA SW generated (6 precache entries). |
| AC-8 | Android assembleDebug | `./gradlew :android:app:assembleDebug` | **PASS** | BUILD SUCCESSFUL (5s). 134 actionable tasks, 133 UP-TO-DATE. |
| AC-9 | Android unit tests | `./gradlew :android:app:test` | **PASS** | BUILD SUCCESSFUL (34s). 182 actionable tasks. |

## Build & Test Summary

| Модуль | Команда | Результат |
|--------|---------|-----------|
| Shared KMP (JVM) | `./gradlew :shared:jvmTest` | 61 tests green |
| Shared KMP (JS) | `./gradlew :shared:jsTest` | 61 tests green |
| Shared KMP (all) | `./gradlew :shared:allTests` | BUILD SUCCESSFUL |
| Server (unit) | `./gradlew :server:test` | 38 tests green |
| Server (integration) | `./gradlew :server:integrationTest` | 6 tests green |
| Server (health) | `curl localhost:8080/health` | HTTP 200 |
| Web (dev) | `npm test` | 33/33 passed |
| Web (prod) | `npm run build` | tsc + Vite + PWA SW |
| Android (assemble) | `./gradlew :android:app:assembleDebug` | BUILD SUCCESSFUL |
| Android (test) | `./gradlew :android:app:test` | BUILD SUCCESSFUL |

## Итого: **10/10 AC PASS**

## Что реализовано в Round 1

- **shared**: Recurrence с COUNT в RRULE, DST-политика, ~20 тестовых кейсов. SmartAddCorpus 300+ входов, эквивалентность JVM/JS.
- **server**: CFG-1..CFG-4 (конфигурация), SEC-1..SEC-3, SEC-6 (rate limiting), SYNC-1 (savepoints), SYNC-4 (повторы через shared Recurrence), SEC-7 (/v1/features).
- **web**: Scaffold — сайдбар, список задач, навигация. Smart Add UI, настройки, онбординг. WEB-S1..S4 (офлайн-очередь, синхронизация, вход/регистрация).
- **android**: 6 модулей (app, core:database, core:network, core:sync, core:designsystem, feature:tasks). Ручной DI, in-memory DAO. Room-аннотации, Hilt, KSP — подготовлено.

## Known Technical Debt

- Deprecated Kotlin stdlib warnings в shared
- npm audit: transitive vulnerabilities
- Android — каркас без реального UI (Phase 1 задача)
- jOOQ кодогенерация требует реальных миграций Flyway
- SEC-4, SEC-5, SYNC-2, SYNC-3, SYNC-5, SYNC-6, SYNC-7 — тесты coverage

## Git Status

Working tree clean on main. Ветки 5 coder-карт слиты и удалены.