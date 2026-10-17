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
**Альтернативы:** По одной карте на AC (слишком много), две карты (слишком крупные).