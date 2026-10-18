# STATUS.md — Taskly (my-rtm)

Последнее обновление: Oct 18 2026 · коммит: 5dec473 · [CI](https://github.com/re1616/my-rtm/actions/runs/39138324468)

Легенда:
- ✅ **готово (тест)** — реализовано, покрыто тестом (файл)
- 🔶 **каркас** — структура есть, не доделано / не проверено
- ❌ **не начато**

---

## 2. Нормативные требования

| ID | Статус | Тест / файл |
| --- | --- | --- |
| CFG-1 | ✅ готово (тест) | `server/src/test/.../ConfigTest.kt` — env override |
| CFG-2 | ✅ готово (тест) | `config/ConfigTest.kt` — JWT ≥32 байт, не dev-секрет; ключи FCM/VAPID обязательны вне dev |
| CFG-3 | ✅ готово (тест) | `server/src/test/.../IntegrationTest.kt` — БД `_test` |
| CFG-4 | 🔶 каркас | Ручной аудит web/android без секретов; автоматического сканера (gitleaks) в CI нет, сборка по окружениям не настроена |
| SEC-1 | ✅ готово (тест) | `server/src/test/.../security/SecurityTest.kt` — 409 на чужой id |
| SEC-2 | ✅ готово (тест) | `SecurityTest` — чужие `list_id`/`parent_id`/`location_id` в sync → 403, некорректный id → отклонение. REST не принимает `parent_id`/`location_id` |
| SEC-3 | ✅ готово (тест) | `SyncService.getChanges()` — scope на сервере |
| SEC-4 | 🔶 частично | `SecurityTest` — владелец vs посторонний по задачам, спискам, sync. Роли «участник edit/view» появятся с общими списками (v1.1) — полной матрицы пока нет |
| SEC-5 | ✅ готово (тест) | `SecurityTest` — ротация, повтор → отзыв всей цепочки, параллельные запросы одним токеном → не более одной ротации |
| SEC-6 | ✅ готово (тест) | `RateLimiter` на всех маршрутах, 429 |
| SEC-7 | ✅ готово (тест) | `FeaturesIntegrationTest.kt` — `/v1/features` |

## 2.3 Синхронизация

| ID | Статус | Тест / файл |
| --- | --- | --- |
| SYNC-1 | 🔶 частично | Savepoint на операцию и результаты `ok`/`rejected` — `SyncIntegrationTest`. Статус `conflict_resolved` не возвращается |
| SYNC-2 | ✅ готово (тест) | Идемпотентность `(user_id, op_id)` |
| SYNC-3 | 🔶 каркас | MergeEngine существует |
| SYNC-4 | ✅ готово (тест) | `RecurrenceCountIntegrationTest.kt` — COUNT=3, 3 completes, стоп |
| SYNC-5 | ✅ готово (тест) | `QueryParser`/`SqlBuilder` — `SyncIntegrationTest.kt` |
| SYNC-6 | 🔶 частично | Сервер: дельты списков, тегов, локаций — `SyncIntegrationTest`. Клиенты: Android применяет задачи и списки, теги и локации — нет; веб — проверить в M2 |
| SYNC-7 | 🔶 частично | Сервер: `Taskly-Sync-Version`, 426 на неподдерживаемую — `SyncIntegrationTest`. Клиенты заголовок пока не шлют (без заголовка = текущая версия) |

## 3. Веб

| ID | Статус | Тест / файл |
| --- | --- | --- |
| WEB-F1 | ✅ готово (тест) | SmartAdd 61 тест (`@taskly/shared`) |
| WEB-F2 | 🔶 каркас | Трёхпанельный каркас частично |
| WEB-F3 | 🔶 каркас | Карточка — stubs |
| WEB-F4 | ❌ не начато | Массовые операции |
| WEB-F5 | 🔶 каркас | Умные списки — scaffold |
| WEB-F6 | ❌ не начато | v1.1 |
| WEB-F7 | ❌ не начато | v1.1 |
| WEB-F8 | ❌ не начато | **Перенесено в фазу 2** |
| WEB-F9 | 🔶 каркас | Настройки — scaffold |
| WEB-F10 | ❌ не начато | v1.1 |
| WEB-F11 | 🔶 каркас | Онбординг — scaffold |
| WEB-S1 | ✅ готово (тест) | Очередь + транзакция Dexie |
| WEB-S2 | ✅ готово (тест) | Ack, эксп. задержка |
| WEB-S3 | ✅ готово (тест) | Вход / регистрация |
| WEB-S4 | ✅ готово (тест) | Дельта всех scope |
| WEB-S5 | ✅ готово (тест) | Web Locks + BroadcastChannel |
| WEB-S6 | ✅ готово (тест) | Индикатор синхронизации |
| WEB-S7 | ✅ готово (тест) | Миграции Dexie |
| WEB-S8 | ✅ готово (тест) | Service Worker стратегия |

## 4. Android

| ID | Статус | Тест / файл |
| --- | --- | --- |
| AND-S1 Room-схема и миграции | ✅ готово (тест) | `core/database/.../MigrationTest.kt` |
| AND-S2 Изменение + операция в одной транзакции | ✅ готово (тест) | `TaskRepository` (`db.withTransaction`), `OfflineSyncTest` |
| AND-S3 Отправка очереди | ✅ готово (тест) / 🔶 | Отправка по порядку, удаление только после ack, сохранение при обрыве — `OfflineSyncTest`, `RealServerSyncE2ETest`. Запуск через WorkManager при появлении сети реализован, автотестом не покрыт |
| AND-S4 Получение изменений | 🔶 каркас | Получение дельт по курсору — тест есть; WebSocket и FCM не сделаны, есть периодическая синхронизация раз в 6 ч |
| AND-S5 Слияние конфликтов | ❌ не начато | MergeEngine не подключён |
| AND-S6 Снимок при первом входе | ❌ не начато | Сейчас первый вход = полная дельта с курсора 0 |
| AND-S7 Офлайн-поиск FTS5 | ❌ не начато | |
| AND-N1…N5 | ❌ не начато | v1.1 |
| Hilt (DI) | ✅ готово (сборка) | Граф проверяется при `assembleDebug`; `@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel`, `di/AppModule.kt` |
| Вход и список задач | 🔶 | Экран и ViewModel работают через Room и sync; UI-тестов нет |
| **E2E офлайн → сервер** | ✅ готово (тест) | `RealServerSyncE2ETest` против запущенного сервера в CI (job «Shared + Server»); CI падает, если тест пропущен |
| CI (Android) | ✅ | `assembleDebug`, unit-тесты Room и sync, E2E с сервером |

**Фаза 1 Android закрыта** по критериям: Hilt, атомарная запись, SyncEngine с сервером, вход и список, E2E офлайн → сервер в CI.

## Прочее

| Компонент | Статус | Тест / файл |
| --- | --- | --- |
| Shared (61 тестов) | ✅ готово (тест) | `:shared:allTests` — JVM + JS |
| Server (38 unit + 6 int.) | ✅ готово (тест) | `:server:test` + `:server:integrationTest` |
| Web (33 теста) | ✅ готово (тест) | `npm test` + `npm run build` |
| CI Shared+Server+Web | ✅ | зелёный на `main` |
| CI Android | ✅ | см. раздел 4 |
| COUNT через API | ✅ готово (тест) | `RecurrenceCountIntegrationTest.kt` |
| DST-политика | ✅ готово (тест) | 4 теста в `shared` + COUNT тест |

---

*Формат: «готово (тест)» = тест в репозитории, «каркас» = структура без полного покрытия, «не начато» = ничего не сделано.*