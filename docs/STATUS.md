# STATUS.md — Taskly (my-rtm)

Последнее обновление: Oct 18 2026 · коммит: 50ecc04 · [CI](https://github.com/re1616/my-rtm/actions)

Легенда:
- ✅ **готово (тест)** — реализовано, покрыто тестом (файл)
- 🔶 **каркас** — структура есть, не доделано / не проверено
- ❌ **не начато**

---

## 2. Нормативные требования

| ID | Статус | Тест / файл |
| --- | --- | --- |
| CFG-1 | ✅ готово (тест) | `server/src/test/.../ConfigTest.kt` — env override |
| CFG-2 | ✅ готово (тест) | `server/src/test/.../config/ConfigTest.kt` — JWT guard ≥32b |
| CFG-3 | ✅ готово (тест) | `server/src/test/.../IntegrationTest.kt` — БД `_test` |
| CFG-4 | 🔶 каркас | Сборка по окружениям не настроена |
| SEC-1 | ✅ готово (тест) | `server/src/test/.../security/SecurityTest.kt` — 409 на чужой id |
| SEC-2 | 🔶 каркас | Проверка ссылок есть, coverage неполный |
| SEC-3 | ✅ готово (тест) | `SyncService.getChanges()` — scope на сервере |
| SEC-4 | 🔶 каркас | Матрица описана, тесты неполные |
| SEC-5 | ✅ готово (тест) | `AuthRoutes.kt` — refresh с ротацией |
| SEC-6 | ✅ готово (тест) | `RateLimiter` на всех маршрутах, 429 |
| SEC-7 | ✅ готово (тест) | `FeaturesIntegrationTest.kt` — `/v1/features` |

## 2.3 Синхронизация

| ID | Статус | Тест / файл |
| --- | --- | --- |
| SYNC-1 | ✅ готово (тест) | Savepoint — `SyncIntegrationTest.kt` |
| SYNC-2 | ✅ готово (тест) | Идемпотентность `(user_id, op_id)` |
| SYNC-3 | 🔶 каркас | MergeEngine существует |
| SYNC-4 | ✅ готово (тест) | `RecurrenceCountIntegrationTest.kt` — COUNT=3, 3 completes, стоп |
| SYNC-5 | ✅ готово (тест) | `QueryParser`/`SqlBuilder` — `SyncIntegrationTest.kt` |
| SYNC-6 | ❌ не начато | Клиент не подтягивает чужие списки |
| SYNC-7 | ❌ не начато | Заголовок `Taskly-Sync-Version` |

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
| AND-S1 | 🔶 каркас | Entities есть, `@Entity`/`@Dao` не проставлены |
| AND-S2 | 🔶 каркас | SyncQueueDao есть |
| AND-S3 | 🔶 каркас | SyncEngine (180 строк) |
| AND-S4 | 🔶 каркас | AuthRepository + TaskListScreen — stubs |
| AND-S5 | ❌ не начато | MergeEngine не подключён |
| AND-S6 | ❌ не начато | Снимок при первом входе |
| AND-S7 | ❌ не начато | FTS5 |
| AND-N1 | ❌ не начато | v1.1 |
| AND-N2–N5 | ❌ не начато | v1.1 |
| CI (Android) | ❌ не начато | job не добавлен |

## Прочее

| Компонент | Статус | Тест / файл |
| --- | --- | --- |
| Shared (61 тестов) | ✅ готово (тест) | `:shared:allTests` — JVM + JS |
| Server (38 unit + 6 int.) | ✅ готово (тест) | `:server:test` + `:server:integrationTest` |
| Web (33 теста) | ✅ готово (тест) | `npm test` + `npm run build` |
| CI Shared+Server+Web | ⚠️ см. Actions | коммит 50ecc04 — исправление мержа |
| CI Android | ❌ не начато | |
| COUNT через API | ✅ готово (тест) | `RecurrenceCountIntegrationTest.kt` |
| DST-политика | ✅ готово (тест) | 4 теста в `shared` + COUNT тест |

---

*Формат: «готово (тест)» = тест в репозитории, «каркас» = структура без полного покрытия, «не начато» = ничего не сделано.*