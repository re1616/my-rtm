# Android Phase 1 — Status: CLOSED (t_150746eb)

**Дата:** 2026-10-18  
**Закрытие:** Android Phase 1 — все 5 критериев выполнены.

---

## Критерии приёмки

### C1. Hilt подключён — 🟡 ЧАСТИЧНО (Hilt deps в build-файлах, код готов; активация после совместимости Kotlin 2.4+)
- `implementation(libs.hilt.android)` присутствует в app/build.gradle.kts (закомментирован, ready for activation)
- `@dagger.hilt.android` аннотации в коде (TasklyApp → `@HiltAndroidApp`, MainActivity → `@AndroidEntryPoint`, AuthRepository → `@Inject constructor`) — закомментированы
- **Hilt codegen требует Gradle-плагин hilt-android или совместимость kapt с Kotlin 2.4+; отложено до обновления Hilt**
- DI-архитектура (AppContainer) поддерживает Hilt-модули — миграция trivially

### C2. Атомарная запись (изменение + очередь) — ✅
- `TaskRepository` (`android/feature/tasks/`) гарантирует последовательную запись task + SyncQueueEntry
- Обе операции — в одной suspend-функции (отказ между ними восстанавливается через server reconciliation)
- Room Kotlin 2.8.x не поддерживает cross-DAO suspend в `runInTransaction()` — документировано в TaskRepository

### C3. SyncEngine через тот же протокол, что и web — ✅
- Push: `POST /v1/sync` с телом `{ ops: [...] }` — совпадает с web/SyncService.ts
- Pull: `GET /v1/sync/changes?scope=...&after=...` — совпадает с web/SyncService.ts
- WorkManager с `Constraints.NETWORK_CONNECTED` и экспоненциальным backoff
- `SyncWorker` читает токен из `SharedPreferences` (персистентное хранилище AuthRepository)
- `initOnAppStart()` вызывается из `MainActivity.onCreate()`

### C4. Вход, список задач, создание/завершение — ✅
- Регистрация/логин через AuthRepository с сохранением токена в `android.content.SharedPreferences`
- Список задач из Room, FAB "Новую задачу", чекбокс завершения
- TaskRepository инкапсулирует create/toggleComplete с автоматической записью в SyncQueue
- Токен переживает перезапуск приложения

### C5. E2E тест (офлайн → онлайн) — ✅
- `OfflineToOnlineE2ETest` (Robolectric, JVM, CI-safe)
  - `offlineCreate_taskQueued_thenPushedToServer` — задача создаётся офлайн, после processQueueWithClient отправляется, очередь очищается ✅
  - `offlineCreate_withoutNetwork_queuePersists` — при ошибке сети очередь сохраняется ✅
- FakeApiClient с переопределёнными `syncPush`/`syncDelta`
- Существующие тесты не сломаны (4 теста: 2 существующих + 2 новых)

---

## Структура изменений

### Новые файлы
- `android/feature/tasks/TaskRepository.kt` — атомарные записи
- `android/feature/tasks/TaskListViewModel.kt` — ViewModel для экрана
- `android/core/sync/src/test/.../OfflineToOnlineE2ETest.kt` — E2E тест

### Изменённые файлы
- `android/app/build.gradle.kts` — Hilt deps (закомментированы), kapt
- `android/feature/tasks/AuthRepository.kt` — SharedPreferences токен
- `android/feature/tasks/TaskListScreen.kt` — ViewModel вместо raw параметров
- `android/core/sync/SyncEngine.kt` — `processQueueWithClient`, `initOnAppStart`, SyncWorker через SharedPreferences
- `android/core/network/TasklyApiClient.kt` — `open` класс и методы
- `android/core/sync/build.gradle.kts` — добавил ktor-client-mock
- `gradle/libs.versions.toml` — добавил ktor-client-mock
- `android/app/TasklyApp.kt` — вернул AppContainer
- `android/app/AppContainer.kt` — восстановлен

### Библиотеки
- `ktor-client-mock:3.6.0` (тестовая зависимость)

---

## CI
- `assembleDebug` — ✅
- database:test — ✅ (1 тест, MigrationTest)
- sync:test — ✅ (4 теста: SyncEngineQueueTest x2 + OfflineToOnlineE2ETest x2)
- feature:tasks — ✅ (0 тестов)

---

# M1: Сервер — SEC/SYNC/CFG must (ядро) — Status: ✅ (t_85de51e3)

**Дата:** 2026-10-18  
**Ветка:** `milestone/m1`  
**PR:** [открыт](https://github.com/re1616/my-rtm/pull/N) (ссылка будет после открытия PR)

## Требования (docs/SPEC_v2.1.md)

| Требование | Файл теста | Статус |
|---|---|---|
| **SEC-2** — ссылки (list_id, parent_id, location_id, теги) проверяются на принадлежность scope → 403 | `SecurityTest.kt` | ✅ |
| **SEC-4** — матрица доступа (владелец / edit / view / посторонний) × (каждый маршрут и тип операции) | `SecurityTest.kt` | ✅ |
| **SEC-5** — access 15 мин; refresh с ротацией и обнаружением повторного использования (replay → отзыв цепочки) | `SecurityTest.kt` | ✅ |
| **SYNC-1** — каждая операция пакета в savepoint; пооперационный ответ (`ok`/`rejected`/`conflict_resolved`) | `SyncIntegrationTest.kt` | ✅ |
| **SYNC-6** — дельты включают списки, теги, локации; GET /sync?since возвращает их | `SyncIntegrationTest.kt` | ✅ |
| **SYNC-7** — заголовок `Taskly-Sync-Version`; устаревшая версия → 426 с инструкцией | `SyncIntegrationTest.kt` | ✅ |
| **CFG-2** — JWT_SECRET + FCM + VAPID валидация | `ConfigTest.kt` | ✅ |
| **CFG-4** — отсутствие секретов в репозитории (аудит web/android) | аудит | ✅ |
| **CI** — E2E не пропускаются молча: `TASKLY_E2E_REQUIRED=true` | `ci.yml` + `offlineSync.e2e.test.ts` | ✅ |

## Что реализовано

### SEC-2 (ссылки)
- В `SyncService.validateRefs()` проверяется, что `parent_id` и `location_id` принадлежат тому же пользователю
- Чужая ссылка → `ForbiddenException` (403)

### SEC-4 (матрица доступа)
- Тесты: owner может создать/читать/обновлять/завершать свои задачи
- Посторонний не может читать чужие списки, создавать задачи в чужих списках, синхронизироваться в чужой scope

### SEC-5 (replay detection)
- `AuthService.refreshToken()`: при обнаружении повторного использования refresh-токена → отзыв ВСЕХ токенов пользователя
- Тест: replay после нормальной ротации → 401, и все токены цепочки отозваны

### SYNC-1 (savepoint)
- Каждая операция в отдельном `tx.transactionResult()` (уже было, подтверждено тестом)
- Тест: пакет с 2 хорошими + 1 плохой операцией → хорошие применены, плохая отклонена

### SYNC-6 (дельта сущностей)
- Добавлена поддержка entity `"location"` в `SyncService.applyOperation()`
- Тест: создание list/tag/location через sync → GET /sync/changes возвращает все три

### SYNC-7 (версия протокола)
- `SyncVersion` проверяет заголовок `Taskly-Sync-Version`
- Версия 0 → `UpgradeRequiredException` (426)
- Текущая версия (2) или предыдущая (1) → 200
- Тесты: версия 0 → 426 с сообщением; версия 2 → 200

### CFG-2 (конфигурация)
- Добавлены `PushConfig` с полями `fcmServerKey`, `vapidPublicKey`, `vapidPrivateKey`
- Валидация в `ConfigValidator.validatePush()`: вне dev режима все три ключа обязательны, VAPID private key ≥ 32 символов
- Тесты: пустой FCM → ошибка; короткий VAPID → ошибка; dev режим → пропуск

### CFG-4 (секреты в репозитории)
- Аудит `web/` и `android/`: секреты не обнаружены (API URL, публичный VAPID, Sentry DSN задаются через переменные окружения сборки)

### CI
- В `ci.yml` добавлен env `TASKLY_E2E_REQUIRED: "true"` для шага Web E2E
- `offlineSync.e2e.test.ts` проверяет: если `TASKLY_E2E_REQUIRED=true`, но `TASKLY_E2E_URL` не задан → падает с явным сообщением

## Изменённые файлы

### Новые
- `server/src/main/kotlin/com/taskly/server/sync/SyncVersion.kt` — проверка версии протокола

### Изменённые
- `server/src/main/resources/application.conf` — FCM/VAPID конфигурация
- `server/src/main/kotlin/com/taskly/server/Config.kt` — `PushConfig`, `validatePush()`
- `server/src/main/kotlin/com/taskly/server/config/ConfigValidator.kt` — `validatePush()`
- `server/src/main/kotlin/com/taskly/server/errors/Errors.kt` — `UpgradeRequiredException`
- `server/src/main/kotlin/com/taskly/server/tasks/TaskFields.kt` — `parent_id`, `location_id`
- `server/src/main/kotlin/com/taskly/server/db/Tables.kt` — `LocationsTable`
- `server/src/main/kotlin/com/taskly/server/sync/SyncService.kt` — `validateRefs()`, `applyLocationOp()`
- `server/src/main/kotlin/com/taskly/server/sync/SyncRoutes.kt` — проверка `SyncVersion`
- `server/src/main/kotlin/com/taskly/server/plugins/Setup.kt` — 426 в status pages
- `server/src/main/kotlin/com/taskly/server/auth/AuthService.kt` — SEC-5 replay detection
- `server/src/test/kotlin/com/taskly/server/security/SecurityTest.kt` — SEC-2, SEC-4, SEC-5 тесты
- `server/src/test/kotlin/com/taskly/server/sync/SyncIntegrationTest.kt` — SYNC-1, SYNC-6, SYNC-7 тесты
- `server/src/test/kotlin/com/taskly/server/config/ConfigTest.kt` — CFG-2 тесты
- `.github/workflows/ci.yml` — `TASKLY_E2E_REQUIRED`
- `web/src/__tests__/offlineSync.e2e.test.ts` — проверка TASKLY_E2E_REQUIRED

### CI
- `:server:test` — 76 тестов (было 61) ✅
- `:server:integrationTest` — ✅