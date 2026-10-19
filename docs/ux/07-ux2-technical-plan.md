# UX-2. Технический план: layout, навигация, дизайн-система

Статус: **готов к реализации**. Основан на утверждённых решениях UX-1
(`02-information-architecture.md`, раздел 0) и дизайн-системе
(`04-design-system.md`).

План описывает **первый этап** рефакторинга. Массовая реализация начинается
после подтверждения этого документа. Backend API, схема БД, пайплайн, LLM и
Support Assistant не изменяются.

---

## 1. Цель и границы

### 1.1. Цель UX-2

Дать всем страницам единую оболочку и навигацию, локальные ассеты и
адаптивную базу, не меняя поведение backend и не ломая HTMX/CSRF/формы.

Результат этапа:

- единый Thymeleaf layout и переиспользуемые fragments;
- desktop sidebar + mobile drawer;
- локальные CSS/JS/HTMX (без CDN);
- `<meta name="viewport">` на всех страницах;
- дизайн-токены и базовые компоненты;
- active navigation и breadcrumbs;
- доступность (skip-link, landmarks, labels, focus, aria-current);
- единая обработка ошибок и flash-сообщений;
- проверенные все существующие страницы, кроме Support (вне scope).

### 1.2. Явно вне scope

- Изменение backend API, БД, миграций, пайплайна.
- Переработка бизнес-логики `/library`, `/add`, `/jobs` (только тонкие
  view-контроллеры поверх существующих сервисов).
- Редизайн Support Assistant, его шаблонов, API и тестов.
- Исправление `GET /support/lecture/{id}` (техдолг D2).
- Поддержка MP3/M4A/WAV (D1).
- Глобальный RAG-поиск / «База знаний» (D3).
- WYSIWYG, SPA, CSS-фреймворк, npm-сборка.
- Полная переработка редактора (этап 5); в UX-2 — только shell и стили.

### 1.3. Утверждённая навигация

**Основное:** Обзор `/`, Библиотека `/library`, Добавить материал `/add`,
Задачи обработки `/jobs`, Поиск видео `/discover`.

**Контекст материала** (внутри `/lectures/{id}`): Транскрипция, Перевод,
Конспект, Слайды, Редактор, Экспорт, Похожие материалы.

**Инструменты разработчика** (`<details>`, свёрнуто): Developer Assistant
`/assistant`, Code Review `/code-review`, File Agent `/file-agent`.

**Система** (`<details>`, свёрнуто): Настройки `/settings`, Диагностика
`/diagnostics`, Бенчмарки `/benchmarks`. `/mcp` — технический маршрут без
пункта меню, пока нет реального UI-сценария.

Support Assistant (`/support`) в навигации отсутствует. Прямой URL работает.

---

## 2. Инвентаризация текущего состояния (факты)

Проверено в репозитории на момент UX-1:

| Факт | Следствие для плана |
|------|---------------------|
| 15 шаблонов + 7 fragments; в каждом из 15 — свой `<style>` | нужен общий CSS и построчный перенос, а не «выключить стили» |
| В `resources` нет каталога `static/` | создать `static/css`, `static/js`, `static/vendor`; Boot отдаёт их без конфигурации |
| `<meta name="viewport">` отсутствует во всех шаблонах | добавить в layout; мобильные проверки станут осмысленными |
| HTMX 1.9.12 грузится с `unpkg.com` в 7 шаблонах | вендорить локально, версия и атрибуты не меняются |
| Нет Thymeleaf Layout Dialect | layout строить на `th:replace`/`th:insert` без новой зависимости |
| Нет Spring Security; CSRF реализован вручную (`AdminAccessService`, `EditorAccessService`, `SupportAccessService`) | не менять имена hidden-полей и заголовков; не добавлять глобальный CSRF-фильтр |
| `AdminSecurityFilter` защищает `/settings`, `/api/providers`, `/api/llm/tasks`, `/code-review`, `/api/code-review`, `/file-agent`, `/api/file-agent`, `/diagnostics`, `/api/diagnostics` | при переносе шаблонов не менять пути и методы; GET без доступа редиректит на `/settings/login` |
| `FriendlyErrorController` отдаёт HTML-ошибку + `X-Correlation-Id` | error-страница входит в упрощённый shell; stack trace не показывать |
| `RuntimePagesWebTest` проверяет строки `Локальный файл`, `Видео по ссылке`, `UPLOADED`, `Язык и перевод`, `Похожих лекций пока нет` | сохранить их в DOM или обновить тесты эквивалентно (см. §15) |
| `smoke-ui.py` и `e2e/ui-smoke.spec.mjs` проверяют `/support` | не удалять страницу; smoke оставить |
| `JobStatus` не содержит `PENDING`; `PENDING` — строка `StageView`, когда checkpoint отсутствует | маппинг статусов делать для двух enum/строк, не менять backend |
| `PipelineStage.title()` уже человекочитаемый, `StageView.state` — сырой enum | на первом уровне показывать подпись, сырой enum — в «Детали» и `data-*` |
| `PipelineJobRepository.findAll()` и `LectureLibraryService.search(...)` уже есть | `/jobs` и `/library` не требуют новых запросов/схемы |
| `settings.html:47` собирает подпись конкатенацией `displayName + (cloud ? …)` | заменить на явную подпись провайдера + тест |
| `settings.html:173` содержит устаревшее «reviewer ещё не реализован» | заменить на фактическое описание Code Review |

---

## 3. Архитектура layout

### 3.1. Решение: fragments без Layout Dialect

Новую зависимость `thymeleaf-layout-dialect` **не добавляем**: она не входит в
текущий стек и требует проверки совместимости с Boot 4. Используем штатные
fragment expressions.

`templates/fragments/layout.html` содержит независимые фрагменты:

| Фрагмент | Параметры | Назначение |
|----------|-----------|------------|
| `head(title)` | заголовок | charset, viewport, title, CSS, локальный HTMX |
| `appbar(active, title)` | active, title | mobile-кнопка меню, логотип, «Добавить», admin-меню |
| `sidebar(active)` | active | группы навигации, active/aria-current |
| `breadcrumbs(trail)` | список `Breadcrumb` | хлебные крошки |
| `flash()` | — | сообщения из `message`/`error` |
| `scripts()` | — | `app.js`, закрытие body |
| `plainHead(title)` / `plainShell(...)` | — | `/settings/login`, `/error` без sidebar |

Страница подключает оболочку явно:

```html
<head th:replace="~{fragments/layout :: head('Библиотека')}"></head>
<body>
<a class="skip-link" href="#main">К содержимому</a>
<header th:replace="~{fragments/layout :: appbar('library', 'Библиотека')}"></header>
<div class="app-shell">
  <aside th:replace="~{fragments/layout :: sidebar('library')}"></aside>
  <main id="main" class="content">
    <nav th:replace="~{fragments/layout :: breadcrumbs(${breadcrumbs})}"></nav>
    <!-- содержимое страницы -->
  </main>
</div>
<div th:replace="~{fragments/layout :: scripts}"></div>
</body>
```

Почему так:

- нет передачи больших DOM-фрагментов как параметров (самый хрупкий режим
  Thymeleaf);
- каждую страницу можно мигрировать отдельно и проверять рендер;
- active задаётся литералом в шаблоне, без `#request` (в Thymeleaf 3.1 его нет)
  и без изменений контроллеров;
- fragments можно покрыть `@WebMvcTest`/MockMvc.

`layout.html` не содержит бизнес-логики и не знает о моделях страниц.

### 3.2. Breadcrumbs

`com.lectureprocessor.web.ui.Breadcrumb` — простой record `(String label, String href)`.
Только для страниц с глубокой иерархией:

- `/lectures/{id}` → `Библиотека / {название}`;
- `/lectures/{id}/editor` → `Библиотека / {название} / Редактор`;
- `/discover` → `Поиск видео`;
- `/benchmarks`, `/diagnostics`, `/settings` → `Система / …`.

Для остальных страниц breadcrumb не выводится или состоит из одного уровня.
Никаких новых API; список формируется в контроллере/шаблоне.

### 3.3. Active navigation

- `sidebar(active)` принимает одно из: `overview`, `library`, `add`, `jobs`,
  `discover`, `assistant`, `code-review`, `file-agent`, `settings`,
  `diagnostics`, `benchmarks`.
- Активный пункт: `class="nav-link is-active"`,
  `th:attr="aria-current=${active == 'library'} ? 'page' : null"`.
- Для карточки/редактора active = `library` (контекст материала — вкладки
  внутри страницы, не глобальные пункты).
- Группы «Инструменты разработчика» и «Система» — `<details>`/`<summary>`:
  работают без JS, доступны с клавиатуры. Если active внутри группы —
  `open` выставляется сервером.
- Состояние раскрытия можно запоминать в `localStorage` в `app.js`
  (enhancement, не обязательное условие).

### 3.4. Мобильная навигация

- `<1024px`: sidebar скрыт, `appbar` содержит кнопку
  `aria-controls="app-sidebar"`, `aria-expanded="false"`.
- `app.js`: toggle `is-open`, `aria-expanded`, overlay, закрытие по `Esc`,
  возврат фокуса на кнопку, блокировка прокрутки body.
- Без JS: навигация остаётся доступной через `<noscript>`-ссылку на карту
  разделов (`/` + основные ссылки) либо sidebar показывается статически
  (`:target`/CSS fallback не обязателен).
- Sidebar в DOM один; на desktop — sticky, на mobile — off-canvas.
- Кнопки ≥ 44×44 px, tab-порядок: skip-link → меню → контент.

---

## 4. Файлы и структура

### 4.1. Новые файлы

```
backend/src/main/resources/
├── static/
│   ├── css/
│   │   ├── tokens.css
│   │   ├── base.css
│   │   ├── components.css
│   │   └── pages.css
│   ├── js/
│   │   └── app.js
│   └── vendor/
│       └── htmx.min.js        # 1.9.12, локальная копия
└── templates/
    ├── fragments/
    │   ├── layout.html
    │   ├── nav.html           # опционально: только если sidebar станет >150 строк
    │   ├── flash.html
    │   ├── breadcrumbs.html   # опционально; может жить в layout.html
    │   ├── status-badge.html
    │   ├── job-progress.html
    │   ├── empty-state.html
    │   ├── tabs.html
    │   ├── lecture-card.html
    │   └── form-errors.html
    ├── library.html
    ├── add.html
    └── jobs.html
```

Java:

```
com/lectureprocessor/web/
├── LibraryWebController.java     # GET /library
├── AddMaterialWebController.java # GET /add
├── JobsWebController.java        # GET /jobs
└── ui/Breadcrumb.java            # presentation record
```

### 4.2. Изменяемые файлы

| Файл | Что меняется |
|------|--------------|
| `index.html`, `lecture.html`, `discover.html`, `editor.html`, `assistant.html`, `code-review.html`, `code-review-detail.html`, `file-agent.html`, `benchmarks.html`, `diagnostics.html`, `settings.html`, `settings-login.html`, `error.html`, `chat.html` | shell, viewport, общий CSS/JS, удаление inline `<style>`; бизнес-разметка/имена полей сохраняются |
| `fragments/status.html` | человеческие подписи + `data-status`, прогресс без ложных 100% |
| `fragments/benchmark-*.html`, `assistant-answer.html`, `chat.html` | стили/разметка в общих компонентах, HTMX-атрибуты без изменений |
| `LectureWebController.java` | только если нужно добавить `breadcrumbs` в модель для карточки; `/` остаётся |
| `LectureEditorWebController.java` | `breadcrumbs` для редактора (только модель) |
| `DiagnosticsController`, `BenchmarkController`, `ProviderSettingsController` | `active`/`breadcrumbs` можно не менять: active задаётся шаблоном |
| `code-review.html` + `settings.html` | исправление ложных/устаревших подписей |
| `README.md` | раздел UI: запуск, структура CSS/JS, скриншоты не обязательны |
| `AGENTS.md` | команды/находки UX-2 (кратко, без секретов) |

`support.html`, `support.html`-fragments, `SupportWebController`, support API,
тесты Support — **не изменяются**.

### 4.3. Что не переносим в UX-2

- JS редактора (остаётся inline до этапа 5).
- Логику dirty-state/409/истории/экспорта (этап 5).
- Переверстку настроек на вкладки (этап 8); в UX-2 — только shell и подписи.
- File Agent без `meta refresh` (этап 7).
- Discovery-карточки (этап 6).

---

## 5. Дизайн-токены и CSS

### 5.1. Порядок подключения

```html
<link rel="stylesheet" href="/css/tokens.css">
<link rel="stylesheet" href="/css/base.css">
<link rel="stylesheet" href="/css/components.css">
<link rel="stylesheet" href="/css/pages.css">
```

Один `<link>` на файл, без inline-стилей. `pages.css` — только то, что
действительно уникально для страницы (editor grid, benchmark tables,
settings cards, code diff).

### 5.2. tokens.css

Взять значения из `04-design-system.md`:

- цвет: `--color-bg`, `--color-surface`, `--color-text`, `--color-primary`,
  success/warning/danger/info + soft-варианты, `--color-focus`;
- типографика: `--font-sans`, `--font-mono`, `--text-xs … --text-3xl`;
- отступы `--space-1 … --space-8`, радиусы, тени, `--sidebar-width: 240px`,
  `--topbar-height: 64px`;
- `--transition-fast`, `@media (prefers-reduced-motion: reduce)` — отключение
  анимаций.

### 5.3. base.css

- минимальный reset (`box-sizing`, margins, images);
- `body` 15–16 px, line-height, фон;
- `:focus-visible` ring `var(--color-focus)`;
- `.app-shell` (grid: sidebar + content), `.content` (max-width 1280, padding);
- `.skip-link`, `.visually-hidden`;
- `h1..h4`, `p`, `a`, `code`, `hr`;
- `@media (min-width: 1024px)` sticky sidebar; `<1024px` off-canvas;
- `@media (max-width: 640px)` уменьшенные отступы;
- таблицы: `.table-scroll { overflow-x: auto; }` — скролл внутри контейнера,
  не у `body`.

### 5.4. components.css

Обязательный минимум (по `04-design-system.md`):

| Компонент | Классы/правила | Где используется |
|-----------|----------------|------------------|
| Кнопки | `.btn`, `.btn-primary`, `.btn-secondary`, `.btn-ghost`, `.btn-danger`, `[disabled]` | все страницы |
| Формы | `.field`, `.field label`, `.field-help`, `.field-error`, `input/select/textarea` | upload, settings, support, editor |
| Карточки | `.card`, `.card-header`, `.card-body`, `.card-actions` | главная, библиотека, jobs, настройки |
| Таблицы | `.table`, `.table-scroll`, `th[scope=col]` | jobs, diagnostics, benchmarks, code review |
| Бейджи статусов | `.badge`, `.badge--success/warning/danger/info/neutral` | статусы job/этапов/переводов |
| Прогресс | `.progress`, `progress` | карточка, jobs |
| Уведомления | `.alert`, `.alert--info/success/warning/danger` | flash, ошибки |
| Табы | `.tabs`, `[role=tab]`, `[aria-selected=true]` | карточка, редактор |
| Пустое состояние | `.empty-state` | библиотека, jobs, discovery |
| Breadcrumbs | `.breadcrumbs` | внутренние страницы |
| Группы nav | `.nav-group`, `summary` | sidebar |
| Диалоги/confirm | `.dialog`, `data-confirm` | удаление, approval |

Не вводить компоненты, которые используются на одной странице; такие правила —
в `pages.css`.

---

## 6. JavaScript и HTMX

### 6.1. app.js

Минимальный, без зависимостей:

1. Mobile drawer: toggle, overlay, `Esc`, focus return, `aria-expanded`.
2. `<details>` групп: сохранение `open` в `localStorage` (опционально).
3. `data-confirm`: `confirm()` для опасных действий (удаление лекции/ключа).
4. Flash: автоскрытие success-сообщений (не ошибок), `aria-live` остаётся.
5. Никаких глобальных перехватов форм и ссылок; никакого `hx-boost`.

### 6.2. Локальный HTMX

- Скопировать ровно `htmx.org@1.9.12` в `static/vendor/htmx.min.js`.
- Удалить `<script src="https://unpkg.com/...">` из 7 шаблонов.
- Подключать один раз в `layout :: head` (или `scripts`), с `defer`.
- Зафиксировать версию и SHA-256 в `README`/коммите; сохранить license header.
- Если на момент реализации сети нет — **остановить этап** и сообщить, а не
  оставлять CDN.

### 6.3. Инвентарь HTMX (сохранить без изменения атрибутов)

| Шаблон | Атрибуты | Что делает |
|--------|----------|------------|
| `index.html` | `hx-get=/lectures/preview`, `hx-target=#preview` | превью URL |
| `fragments/status.html` | `hx-get=/lectures/{id}/status`, `hx-trigger=every 3s`, `hx-swap=outerHTML` | live-статус |
| `chat.html` | `hx-post=/lectures/{id}/chat`, `hx-target=#answer` | вопрос по лекции |
| `assistant.html` | `hx-post=/assistant/help`, `hx-target=#answer` | ответ ассистента |
| `benchmarks.html` | `hx-get=/benchmarks/history` (`load, every 5s`), `hx-get=/benchmarks/compare` | история/сравнение |
| `support.html` | `hx-post=/support/ask`, `hx-swap-oob` в fragment | не трогаем (вне scope) |

Правила:

- Переносить HTMX-элементы в layout можно только целиком, с теми же `id`,
  `hx-*`, `name`, `value`.
- Не добавлять `hx-boost`, не менять `hx-swap` без необходимости.
- `<noscript>`: сообщение «Часть действий требует JavaScript» на страницах с
  HTMX; формы, у которых есть обычный `action`, продолжают работать.
- Для `index.html` preview: сохранить `hx-get`; `onsubmit="return false"`
  заменить на `action="/lectures/preview"` + `method="get"` и понятный
  `<noscript>` (без JS откроется fragment — это осознанное ограничение,
  зафиксировать в README).

### 6.4. CSRF и формы: инварианты

Не менять:

- имена hidden-полей `_csrf` (settings/support/editor);
- заголовки `X-Admin-Token`, `X-Editor-Csrf`, `X-Support-Csrf`;
- session-атрибуты `lp.editor.csrf`, `lp.support.csrf`;
- `AdminSecurityFilter` и `AdminAccessService`;
- `enctype="multipart/form-data"` у загрузки;
- `name`/`value` всех select/checkbox (backend читает их напрямую).

Проверить после каждого шаблона:

1. В форме остался `_csrf`, если он был.
2. `th:action`/`action` не изменился.
3. Кнопки submit не получили `type=button` случайно.
4. HTMX-форма по-прежнему отправляет скрытые поля (hidden внутри `<form>`).
5. Ошибка CSRF (403) показывает страницу/сообщение, а не пустой ответ.

---

## 7. Новые UI-маршруты

### 7.1. `/library`

```java
@GetMapping("/library")
public String library(
    @RequestParam(name="q", required=false) String q,
    @RequestParam(name="language", required=false) String language,
    @RequestParam(name="status", required=false) String status,
    @RequestParam(name="hasTranslation", required=false) Boolean hasTranslation,
    @RequestParam(name="sort", defaultValue="date-desc") String sort,
    Model model) { ... }
```

- `model.items = library.search(q, language, status, hasTranslation, sort)`.
- `model.statuses = JobStatus.values()`, `model.languages` — из существующего
  источника (как на текущей `/`).
- Шаблон `library.html`: фильтры + карточки/таблица + пустое состояние.
- Сохранить query-контракт: `q`, `language`, `status`, `hasTranslation`,
  `sort`.

### 7.2. `/add`

- GET-only страница `add.html`; **не** дублирует POST-логику.
- Формы отправляют в существующие endpoint'ы:
  `POST /lectures`, `POST /lectures/from-url`, `GET /lectures/preview`,
  `POST /discover/import`.
- Модель: `qualities` (`MediaQuality`), `statuses`, языки/режимы перевода —
  те же значения, что сейчас в `index.html`.
- Три источника: файл / ссылка / найденное видео; advanced-блок (`<details>`)
  с качеством/аудио/очисткой; модель — только ссылкой в `/settings`.
- `/discover` получает кнопку «Добавить материал» → `/add`.

### 7.3. `/jobs`

- GET-only; read-only агрегатор.
- Данные:
  - `PipelineJobRepository.findAll()` (уже есть), ограничить разумным лимитом
    (например, 100 последних) или разделить «активные / недавние»;
  - `LectureRepository.findAll()`/`findById` для названий и статусов;
  - для активных/требующих внимания — `JobStatusService.view(id)`.
- Действия — только существующие POST:
  `/lectures/{id}/cancel`, `/lectures/{id}/process`, `/lectures/{id}/resume`.
- Никаких новых мутаций и новых таблиц.
- N+1 допустим для лимита; при необходимости один запрос jobs + map лекций.

### 7.4. Главная `/`

- Сохраняет короткий блок «Добавить материал» с теми же подписями
  «Локальный файл»/«Видео по ссылке» (до миграции тестов) и ссылки на `/add`,
  `/library`, `/jobs`.
- Таблица библиотеки убирается на `/library`, но query-фильтры не теряются.
- Блоки: «Требует внимания», «Сейчас обрабатывается», «Последние материалы».
- Ошибки/partial не скрываются.

---

## 8. Статусы и подписи

### 8.1. Единый компонент

`fragments/status-badge.html`:

```html
<span class="badge" th:fragment="badge(status)"
      th:attr="data-status=${status}"
      th:switch="${status}">
  <span th:case="'COMPLETED'">Готово</span>
  <span th:case="'PARTIAL'">Готово частично</span>
  <span th:case="'INTERRUPTED'">Прервано</span>
  <span th:case="'FAILED'">Ошибка</span>
  <span th:case="'CANCELLED'">Отменено</span>
  <span th:case="'UPLOADED'">В очереди</span>
  <span th:case="'DOWNLOADING'">Скачивание</span>
  <span th:case="'VALIDATING'">Проверка файла</span>
  <span th:case="'EXTRACTING_AUDIO'">Подготовка аудио</span>
  <span th:case="'TRANSCRIBING'">Распознавание речи</span>
  <span th:case="'TRANSLATING'">Перевод</span>
  <span th:case="'PROCESSING_TEXT'">Составление конспекта</span>
  <span th:case="'EXTRACTING_SLIDES'">Извлечение слайдов</span>
  <span th:case="'EXPORTING'">Сохранение документов</span>
  <span th:case="'RUNNING'">Выполняется</span>
  <span th:case="'PENDING'">Ожидает</span>
  <span th:case="'INVALIDATED'">Нужно пересчитать</span>
  <span th:case="*" th:text="${status}">Неизвестный статус</span>
</span>
```

Правила:

- Сырой статус всегда остаётся в `data-status` (нужен для тестов, отладки и
  «Деталей»). Не скрывать реальные ошибки/неизвестные значения.
- Тон (`success/warning/danger/neutral`) задаётся классом рядом с badge; цвет
  не единственный носитель смысла.
- `COMPLETED` + `PENDING` в терминальном состоянии: `PENDING` показывать как
  «Не выполнялось»/«Пропущено» в «Деталях», не как ошибку. Backend не менять.
- Прогресс не показывать 100% до терминального статуса.
- В `fragments/status.html` сохранить `data-status` на контейнере, чтобы
  `RuntimePagesWebTest` (`containsString("UPLOADED")`) оставался зелёным без
  ослабления проверки.

### 8.2. Разрешённые исправления подписей

| Место | Сейчас | Стало | Условие |
|-------|--------|-------|---------|
| `settings.html` легенда провайдера | `displayName + (cloud ? ' — cloud' : ' — local')`, для Ollama видно «Ollama (local) — cloud» | явная подпись без конкатенации: «Ollama (локальная)», «DeepSeek (облако)»; флаг `cloud` не выводить текстом рядом с уже локализованным именем | исходный флаг/строку проверить; UI-тест «Ollama — локальная» |
| `settings.html:173` | «Сам reviewer ещё не реализован» | фактическое описание: Code Review использует выбранную модель, webhook/polling настроены | reviewer реализован (Day 32) |
| статусы | сырые `PENDING/COMPLETED/...` на первом уровне | человеческие подписи + сырой статус в «Деталях»/`data-status` | не скрывать FAILED/PARTIAL/INTERRUPTED |

Не «исправлять» данные, которые действительно противоречат: если в БД
`COMPLETED` и `PENDING`, UI объясняет, а не маскирует.

---

## 9. Обработка ошибок и пустые состояния

1. `FriendlyErrorController` остаётся источником HTML-ошибок; `error.html`
   переводится на `plainShell` (без sidebar), сохраняет `status`, `message`,
   `path`, `correlationId`.
2. 403 от `AdminSecurityFilter` при GET редиректит на `/settings/login` —
   поведение не менять; `/settings/login` получает упрощённый shell.
3. Flash: `message` → `.alert--success` (`role=status`), `error` →
   `.alert--danger` (`role=alert`); не автоскрывать ошибки.
4. Ошибки форм — рядом с полем, если контроллер возвращает модель; для
   redirect-flash — сверху.
5. HTMX-ошибки: не заменять всю страницу; для неудачных запросов сохранять
   введённые значения. Глобальный `htmx:responseError` — только понятное
   сообщение, без stack trace.
6. Пустые состояния: библиотека, jobs, discovery, benchmarks, похожие — текст
   + primary-действие.
7. Partial/Interrupted/Failed — баннер с причиной и действием (этап 4), в
   UX-2 только компонент и тексты, без изменения логики.
8. Не показывать stack trace, токены, полные пути; correlation id — можно.

---

## 10. Доступность (acceptance-level)

- `lang="ru"`, `<meta name="viewport" content="width=device-width, initial-scale=1">`.
- Skip-link первым focusable элементом.
- Landmarks: `header`, `nav`, `main#main`, `aside`; один `h1` на страницу.
- Все поля имеют `<label for>` или `aria-label`; ошибки связаны
  `aria-describedby`.
- Все интерактивы доступны с клавиатуры; `:focus-visible` не убирать.
- `aria-current="page"` у активного пункта; `aria-expanded` у drawer/groups.
- Статусы — текст + `data-status`, не только цвет.
- Таблицы: `<caption>` (визуально скрытый допустим), `th[scope]`.
- `prefers-reduced-motion`.
- Минимальная цель 44×44 px на mobile.
- Контраст: текст ≥ 4.5:1, крупный ≥ 3:1 (проверить токены).

---

## 11. Постраничный план миграции

Порядок обязателен: сначала каркас, затем страницы, затем проверки.

| # | Страница | active | Особые риски |
|---|----------|--------|--------------|
| 1 | `fragments/layout.html` + CSS/JS + vendor | — | базовая проверка на `index` |
| 2 | `index.html` | `overview` | 4 формы; `hx-get` preview; тесты |
| 3 | `lecture.html` | `library` | 6 форм; live status; удаление Support-ссылки |
| 4 | `discover.html` | `discover` | 2 формы; fallback-панель |
| 5 | `library.html` (новая) | `library` | query-параметры, пустое состояние |
| 6 | `add.html` (новая) | `add` | 3 источника, POST не меняются |
| 7 | `jobs.html` (новая) | `jobs` | N+1, лимит, действия только POST |
| 8 | `editor.html` | `library` | 614 строк, inline JS/данные; только shell/CSS; Support-ссылка |
| 9 | `assistant.html` | `assistant` | HTMX, `<details>` группа |
| 10 | `code-review.html`, `code-review-detail.html` | `code-review` | admin-фильтр, подписи |
| 11 | `file-agent.html` | `file-agent` | 5 форм, `meta refresh` не трогать в UX-2 |
| 12 | `benchmarks.html` | `benchmarks` | 2 формы, HTMX history/compare |
| 13 | `diagnostics.html` | `diagnostics` | admin-фильтр, таблица |
| 14 | `settings.html` | `settings` | 12 форм, CSRF, исправления подписей |
| 15 | `settings-login.html`, `error.html`, `chat.html` | `settings`/— | упрощённый shell |
| — | `support.html` | — | **не мигрировать**; прямой URL и smoke остаются |

После каждой страницы:

```bash
./mvnw -pl backend test -Dtest='com.lectureprocessor.web.**'
python scripts/smoke-ui.py --base-url http://127.0.0.1:18095
```

---

## 12. Проверка всех существующих страниц

Матрица минимум:

| Проверка | Метод |
|----------|-------|
| Все GET-страницы отдают 200 (кроме защищённых без доступа) | smoke + MockMvc |
| Активный пункт и breadcrumbs корректны | Playwright/ручной чек-лист |
| Нет горизонтального скролла на 390 px | Playwright `scrollWidth <= innerWidth` |
| HTMX работает без `unpkg.com` | offline-route в Playwright |
| CSRF: сохранение настроек/языка/тикета | существующие тесты + ручной POST |
| Формы не потеряли `name`/`value`/`enctype` | diff шаблонов + тесты |
| Ошибка 404/500/403 не показывает stack trace | MockMvc |
| Клавиатурный проход | ручной чек-лист |
| Download-ссылки не изменились | smoke по артефактам |
| `/support` по прямому URL работает | smoke (существующий) |
| Support отсутствует в nav/карточке/редакторе | Playwright assert |

Изолированный запуск:

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
LECTURE_STORAGE_ROOT='C:/Projects/lecture-processor/target/ux2-e2e' \
ASSISTANT_AUTO_INDEX=false CODE_REVIEW_ENABLED=false FILE_AGENT_ENABLED=false \
SERVER_PORT=18095 \
./mvnw -pl backend spring-boot:run
```

---

## 13. Тесты

### 13.1. Сохранить без изменений

- Все тесты Support (`com.lectureprocessor.support.**`) — поведение backend не
  меняется.
- `CodeReview*`, `FileAgent*`, `Assistant*`, `Editor*`, `Provider*`,
  `McpControllerTest`.

### 13.2. Обновить осознанно

| Тест | Причина | Действие |
|------|---------|----------|
| `RuntimePagesWebTest.libraryPageRenders` | формы переносятся на `/add` | проверять `/` (вход) и `/add` (поля); строки «Локальный файл»/«Видео по ссылке» остаются на `/add` |
| `RuntimePagesWebTest.statusPageRenders` | подпись стала человеческой | `containsString("UPLOADED")` продолжает работать через `data-status`; дополнительно проверить «В очереди» |
| `scripts/smoke-ui.py` PAGES | появились `/library`, `/add`, `/jobs` | добавить новые страницы; `/support` оставить |
| `e2e/ui-smoke.spec.mjs` | новый shell | добавить проверки nav; `/support` оставить |

### 13.3. Добавить

- `WebLayoutRenderTest` (MockMvc): каждая страница содержит viewport, sidebar,
  `aria-current`, один `<main id="main">`, нет `unpkg.com`.
- `workspacePagesWebTest`: `/library`, `/add`, `/jobs` рендерятся с моками
  сервисов; query-параметры пробрасываются.
- `e2e/ux/navigation.spec.mjs`: desktop/mobile, active, Support отсутствует,
  группы `<details>`.
- `e2e/ux/layout.spec.mjs`: offline HTMX, no horizontal scroll, skip-link,
  keyboard.
- `e2e/ux/csrf.spec.mjs` (или расширение существующих): сохранение формы
  настроек с CSRF.

### 13.4. Definition of Done UX-2

1. Все 14 мигрированных страниц + 3 новые используют общий shell.
2. Support не входит в nav/breadcrumbs/карточку/редактор; `/support` — 200.
3. Нет inline `<style>` в мигрированных шаблонах.
4. HTMX загружается локально; `window.htmx` определён без сети.
5. `viewport` на всех страницах; 390 px без горизонтального скролла.
6. Все существующие формы/CSRF/HTMX-сценарии работают.
7. Active navigation + breadcrumbs + skip-link + focus.
8. `./mvnw verify` зелёный; smoke и Playwright зелёные.
9. Support-тесты не изменены; остальные тесты обновлены эквивалентно.
10. Документы `README`/`AGENTS` обновлены; отложенные задачи D1–D4 не начаты.

---

## 14. Риски и митигация

| Риск | Влияние | Митигация |
|------|---------|-----------|
| Thymeleaf fragments ломают рендер | высокое | без Layout Dialect; одна страница за раз; MockMvc после каждой |
| Потеря hidden `_csrf`/`name` в 12 формах settings | высокое | diff по `name=`, тест сохранения, не менять контроллеры |
| HTMX-атрибуты теряются при переносе | высокое | таблица §6.3 как чек-лист; smoke сценариев |
| CDN недоступен при вендоринге | среднее | остановить этап и сообщить; не оставлять CDN |
| `RuntimePagesWebTest` ломается из-за переноса форм | среднее | обновить тест вместе с `/add`, не ослаблять проверки |
| Долгий редактор (614 строк, inline JS) | высокое | только shell/CSS; JS не трогать до этапа 5 |
| Support случайно «редизайнится» | среднее | файл в списке «не изменять»; smoke `/support` |
| Мобильный drawer ломает фокус | среднее | aria/focus return, keyboard E2E |
| `settings` защищён admin-фильтром | среднее | не менять пути; проверять localhost/admin-token |
| E2E задевает пользовательские данные | высокое | отдельный `LECTURE_STORAGE_ROOT` |

---

## 15. Порядок работ (батчи)

**Batch 0 — подготовка (S).** Зафиксировать инвентарь форм/HTMX, получить
локальный `htmx.min.js` 1.9.12, создать `static/` и пустые CSS/JS.

**Batch 1 — каркас (M).** `layout.html`, `tokens/base/components/pages`,
`app.js`, мигрировать `index.html`; прогнать тесты и smoke. Это gate: если
каркас нестабилен, дальше не идти.

**Batch 2 — навигация и новые страницы (M).** Sidebar/appbar/breadcrumbs,
`/library`, `/add`, `/jobs`, ссылки; обновить smoke/Playwright.

**Batch 3 — контекст материала (M).** `lecture.html`, `editor.html`,
`status-badge`/`job-progress`; убрать Support-ссылки; исправить подписи
статусов.

**Batch 4 — остальные пользовательские страницы (M).** `discover.html`,
`chat.html`, `error.html`, `settings-login.html`.

**Batch 5 — админ/AI (M).** `assistant`, `code-review*`, `file-agent`,
`benchmarks`, `diagnostics`, `settings`; исправить Ollama/Code Review.

**Batch 6 — проверки и документация (S).** Offline HTMX, 390 px, клавиатура,
`./mvnw verify`, README/AGENTS, финальный отчёт.

Support не входит ни в один батч.

---

## 15a. Известные проблемы после UX-2 (для следующих этапов)

1. **Противоречивые статусы.** `lecture.status` может быть `CANCELLED`, а
   `pipeline_job`/checkpoint — `RUNNING` (и наоборот). В UI статус лекции и
   статусы этапов показываются раздельно; сырой статус сохранён в
   `data-status`. UX-2 не выбирает «главный» статус и не скрывает
   противоречие. Исправление — этап 4 (единый источник правды прогресса).
2. **Сырые технические значения в деталях.** `StageView.state` отображается
   человеческой подписью, но в таблице остаются технические времена/ошибки;
   глубокая переработка карточки — этап 4.
3. **Support вне shell.** `/support` сохраняет старый дизайн; это осознанное
   решение UX-1, а не регрессия.

---

## 16. Открытые вопросы (не блокируют старт)

1. **MCP/API в навигации.** Решение UX-1: только если реально нужен
   UI-переход. По умолчанию не добавляем; вернуться после Batch 5.
2. **Источник `htmx.min.js`.** Нужен доступ к npm/unpkg на машине разработчика
   один раз; в runtime сеть не требуется. Если доступа нет — эскалация.
3. **Точная причина подписи «Ollama (local) — cloud».** Проверить
   `ProviderDescriptor.cloud`/`displayName` в живом окружении; исправление —
   явная подпись + тест, без изменения backend-контракта.
