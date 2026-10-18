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

## Round 1 — Production core (SPEC v2.1)

### ADR-3.1: Android — multi-module scaffold без Hilt/Room/KSP в main
**Контекст:** SPEC v2.1 требует 17 Gradle-модулей, Room, Hilt, KSP. Для Phase 1 (weeks 1–6) нужен каркас AND-S1…S4: Room-схема, очередь, sync-движок, первый экран.
**Решение:** На main зафиксировано 6 модулей (`:app`, `:core:database`, `:core:network`, `:core:sync`, `:core:designsystem`, `:feature:tasks`) с ручным DI и in-memory DAO-заглушками. Room + Hilt + KSP будут внедрены в кодер-картах.
**Альтернативы:** Добавить KSP/Hilt в main (не удалось собрать из-за несовместимости KSP 2.3.x с AGP 8.5.x при KGP 2.4.21). Решение отложить — чище и не блокирует карты.
**Статус:** ✅ Scaffold на main собран (`./gradlew :android:app:assembleDebug`).

### ADR-3.2: Android network — Ktor CIO вместо OkHttp
**Контекст:** OkHttp 5.x (.android) требует compileSdk ≥ 37, что недоступно. Ktor Client OkHttp engine тянет OkHttp 5.
**Решение:** Использовать `ktor-client-cio` (чистый Kotlin) для Android. На JVM-сервере остаётся Ktor Netty.
**Альтернативы:** OkHttp 4.x (устарел); кровавый `forceVersion` в Gradle. CIO — чище.
**Статус:** ✅

### ADR-3.3: Kotlin compilerOptions DSL вместо kotlinOptions
**Контекст:** KGP 2.4.21 депрекейтит `kotlinOptions.jvmTarget`. Для Android модулей требуется `compilerOptions.jvmTarget.set(JvmTarget.JVM_17)`.
**Решение:** Использовать `kotlin { compilerOptions { jvmTarget.set(...) } }` во всех Android build.gradle.kts.
**Альтернативы:** Игнорировать deprecation (ведёт к ошибке компиляции).
**Статус:** ✅

### ADR-3.4: Карты Round 1 — 5 coder + 1 integration + 1 acceptance
**Контекст:** 6 групп AC: shared COUNT+DST (1), server config + security + sync (2), server feature flags (3), Android AND-S1…S4 (4), web stubs (5).
**Решение:** 5 coder-карт с изолированными наборами файлов + карта интеграции + карта приёмки. Зависимости: server-core → shared; server-tests → server-core. Остальные параллельны.
**Карты:**
1. `shared-recurrence` — COUNT в RRULE, DST-политика, ~20 тестовых кейсов. Файлы: `shared/src/commonMain/.../recurrence/`, `shared/src/commonTest/.../recurrence/`
2. `server-core` — CFG-1…CFG-4, savepoints в SyncService (SYNC-1), DST+COUNT в TaskService.complete(), SEC-1…3 проверки, rate limiting (SEC-6). Файлы: `server/src/main/kotlin/.../{Config.kt,sync/*,tasks/*,ratelimit/*}`
3. `server-features-tests` — SEC-7 эндпоинт `/v1/features`, SEC-1…7 тесты, SYNC-1…7 тесты. Файлы: `server/src/main/kotlin/.../features/*`, `server/src/test/.../*`
4. `android-scaffold` — Room-аннотации, Hilt DI, KSP, AND-S1…S4 (Room схема, очередь, sync-движок, экран списка). Файлы: `android/**/*.kt`, `android/**/build.gradle.kts`
5. `web-features` — WEB-F1…F5, F8, F9, F11 страницы/прототипы. Файлы: `web/src/**/*.tsx`, `web/src/**/*.ts`
**Статус:** ✅

### ADR-3.5: Использовать существующий Recurrence из shared для complete() на сервере
**Контекст:** TaskService.complete() содержит примитивный `calculateNextDueDate()` без поддержки COUNT, BYDAY, UNTIL, DST. SPEC v2.1 требует общего Recurrence из shared.
**Решение:** Заменить `calculateNextDueDate()` на вызов `Recurrence.nextOccurrence()` из shared-модуля. Сервер передаёт due_at, rrule, repeat_mode и часовой пояс пользователя.
**Альтернативы:** Дублировать общую логику (нарушает SPEC).
**Статус:** ⏳ Будет в coder-карте server-core.

### Команды для coder-карт (Round 1 production)

#### Shared
- `./gradlew :shared:allTests`

#### Server
- `./gradlew :server:build`
- `./gradlew :server:test`
- `./gradlew :server:integrationTest`
- `./gradlew :server:run`
- `curl -s localhost:8080/health`

#### Web
- `cd web && npm install && npx playwright install chromium --with-deps 2>/dev/null`
- `npm run build`
- `npm test`

#### Android
- `./gradlew :android:app:assembleDebug`
- `./gradlew :android:app:test`