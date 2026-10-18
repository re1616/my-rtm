# Decisions

Журнал архитектурных решений проекта Taskly (my-rtm). Формат: короткий ADR —
контекст, решение, альтернативы. Заполняется архитектором в каждом раунде.

## Round 1 — Skeleton & build infrastructure

### ADR-1.1: Gradle wrapper вместо system gradle
**Контекст:** На хосте нет установленного Gradle.  
**Решение:** Использовать Gradle Wrapper (8.10.2), скопирован в `gradle/wrapper/`.  
**Альтернативы:** `apt install gradle`, SDKMAN. Выбран wrapper для воспроизводимости.

### ADR-1.2: Единый settings.gradle.kts с тремя модулями
**Контекст:** Нужна структура Gradle-проекта.  
**Решение:** Root + `:shared` (KMP) + `:server` (Ktor) + `:android` (shared-зависимость, JVM-тесты).  
**Альтернативы:** include только `:shared` и `:server`, вынести `:android` в отдельный репозиторий.  
Выбрано: android в этом репозитории, т.к. зависит от `:shared` и будет расширяться в следующих прогонах.

### ADR-1.3: Версионный каталог (libs.versions.toml)
**Контекст:** Нужно управлять версиями зависимостей.  
**Решение:** `gradle/libs.versions.toml` — единая точка контроля версий.  
**Альтернативы:** inline-версии в каждом build.gradle.kts. Выбран каталог для консистентности.

### ADR-1.4: Flyway + jOOQ для сервера
**Контекст:** Спецификация требует Flyway (миграции) и jOOQ (типобезопасный SQL).  
**Решение:** Плагины Flyway и jOOQ подключены, ждут реальных схем.  
**Альтернативы:** Exposed (легче, но SPEC фиксирует jOOQ). Решение следует SPEC.

### ADR-1.5: Web-модуль на Vite + Vitest
**Контекст:** Спецификация требует React 19/TS/Vite.  
**Решение:** Vite 6 как сборщик, Vitest как тест-раннер, jsdom как окружение.  
**Альтернативы:** Webpack (медленнее), Jest (сложнее интеграция с Vite).

### ADR-1.6: PWA через vite-plugin-pwa (Workbox)
**Контекст:** Веб-SPA должна работать офлайн (SPEC F-6).  
**Решение:** VitePWA-плагин с Workbox для кэширования shell.  
**Альтернативы:** Ручная настройка Service Worker, Workbox CLI.

### ADR-1.7: AGENTS.md заблокирован защитой агент-инструкций
**Контекст:** Карта требует создания AGENTS.md (реальные команды, правила SPEC).
**Решение:** Запись заблокирована защитой `agent-instruction`. Команды и правила зафиксированы в этой записи и будут переданы coder-картам через их `body`.
**Альтернативы:** Попытка записи через terminal/execute_code (запрещено защитой).
**Статус:** Команды фиксируются ниже и копируются в body каждой coder-карты.

### Команды для coder-карт (Round 1)

#### Shared (KMP)
- ./gradlew :shared:allTests
- ./gradlew :shared:jvmTest
- ./gradlew :shared:jsTest

#### Server (Ktor)
- ./gradlew :server:build
- ./gradlew :server:integrationTest
- ./gradlew :server:run
- ./gradlew :server:test
- curl -s localhost:8080/health

#### Web (React SPA)
- cd web && npm install
- npm run build
- npm test
- npm run dev

#### Android (каркас)
- ./gradlew :android:build
- ./gradlew :android:test

### ADR-1.8: Планировка coder-карт по слоям
**Контекст:** 8 AC нужно распределить между coder-картами.  
**Решение:** Три карты: (1) shared-модуль (модели, Smart Add, запросы, повторы), (2) сервер (REST API, БД, синхронизация, аутентификация), (3) веб (UI, PWA, офлайн). Android-каркас в shared-карте.  
|**Альтернативы:** По одной карте на AC (слишком много), две карты (слишком крупные).

## Round 2 — Fix AC-8: Wire SmartAdd into server REST API

### ADR-2.1: Server-side SmartAdd via shared KMP SmartAddParser
**Контекст:** `POST /v1/tasks` принимает `smartAdd: true` в DTO, но не вызывает парсер. SPEC AC-8 требует, чтобы строка с `^`, `#`, `!` распарсилась на сервере в структурированные поля (due, priority, list, tags).
**Решение:** В `TaskService.create()` при `request.smartAdd == true` вызывать `SmartAddParser` из shared-модуля. Парсер уже развёрнут в KMP shared (JVM target) и доступен серверу через зависимость `:shared`.
**Дополнительно:** Список имён пользователя (`listNames`, `tagNames`) загружается из БД перед парсингом для правильного приоритета списка над тегом (SPEC F-2).

### ADR-2.2: Маппинг SmartAddResult → TaskRecord на сервере
**Контекст:** `SmartAddParser.parse()` возвращает `SmartAddResult` с типами из KMP shared (kotlinx.datetime.Instant, Priority, ParsedRepeat и т.д.). Сервер хранит поля в jOOQ-таблицах с java.time.OffsetDateTime, Integer для priority.
**Решение:** Конверсия типов:
- **Даты:** `ParsedDue.instant` → `kotlinx.datetime.Instant.toJavaInstant()` → `java.time.Instant.atZone(zoneId).toOffsetDateTime()`
- **Priority:** `Priority.level` → `Int?` (null = NONE)
- **Список:** найденный по `listName` из `lists` пользователя; если не найден — Inbox
- **Теги:** найденные по `tags` из `tags` пользователя, линкуются через `task_tags`
- **Repeat:** `ParsedRepeat.rrule` → `rrule`; `ParsedRepeat.mode.name.lowercase()` → `repeatMode`
- **Время пользователя:** загружается из `users.tz` (дефолт `Europe/Moscow`)
- **Сегодня:** `Clock.System.todayIn(tz)` → `kotlinx.datetime.LocalDate`
- **Оценка:** `estimateMin` передаётся напрямую
- **Заметка:** `note` сохраняется как отдельная запись в `notes` (поле вне scope AC-8, может быть опущено)
**Альтернативы:** Сериализация/десериализация через JSON (избыточно, медленнее). Прямой вызов из JVM — самый чистый путь, т.к. shared-модуль собран на JVM target.
## Round 3 — Security fixes and wiring the shared module into server and web

### ADR-3.1: Scopes and ownership are decided by the server
**Context:** sync `create` did `ON CONFLICT (id) DO UPDATE SET owner_id = <caller>` — any user could take over any task by id; `scope` came from the client and was trusted for writes, `/v1/sync/changes` and WebSocket subscriptions.
**Decision:** the server derives the only allowed scope (`user:<id>`) and rejects others (403 / `forbidden`). An existing id that belongs to someone else is never re-assigned. Tasks may reference only the caller's own lists. Shared-list scopes (`list:<id>`) will be added together with `list_members` permissions.

### ADR-3.2: One savepoint per sync op, per-user idempotency
**Context:** the whole batch ran in one transaction while catching per-op exceptions; after the first SQL error Postgres aborts the transaction, so the rest failed and "ok" ops were rolled back. `processed_ops.op_id` was a global key.
**Decision:** each op runs in a nested transaction (savepoint); the key is `(user_id, op_id)` (V2 migration); WebSocket signals are published after commit.

### ADR-3.3: Domain logic only from `shared`
**Context:** the server had its own query parser and a naive `+1 day/week` recurrence; the web had a TS copy of the Smart Add parser — contrary to ARCH 3.1.
**Decision:** server uses `QueryParser`/`SqlBuilder`/`SqlRenderer`, `RRule`/`Recurrence`, `MergeEngine`; web uses the Kotlin/JS library build (`:shared:webLibrary`, imported as `@taskly/shared` through a small `@JsExport` facade that returns JSON).

### ADR-3.4: Completing a repeating task
**Decision:** row lock (`FOR UPDATE`), idempotent (an already completed task never produces a second instance), next date from `Recurrence.nextInstanceDate` in the user's zone with the local time kept, tags and notes copied, all in one transaction with change_log entries. REST and sync use the same code path.

### ADR-3.5: Web sync engine
**Decision:** every repository mutation writes the entity and its op in one Dexie transaction; the queue is ordered by `seq`; an op leaves the queue only after the server acknowledged it; network errors keep ops pending, rejections are retried (max 5); pull by cursor skips entities with unsent local edits.

### ADR-3.6: Rate limiting
**Decision:** Ktor plugin over the existing Redis token bucket: per access token (or IP) for the API, a small per-IP bucket for `/v1/auth/*`; 429 + `Retry-After`; fail-open if Redis is unavailable.

### ADR-3.7: CI as the source of truth for acceptance
**Decision:** GitHub Actions runs shared (JVM+JS), server unit + integration tests on real PostgreSQL 16/Redis 7, web tests/build, and a web E2E test against a running server. STATUS.md refers to CI runs instead of hand-written claims.
