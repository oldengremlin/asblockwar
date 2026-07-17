# Внутрішня кухня ASBlockWar

Цей документ описує архітектуру, ключові патерни і конвенції проєкту для тих,
хто хоче розібратися в коді або зробити внесок.

---

## Вимоги до середовища

| Інструмент | Версія |
|---|---|
| Java (JDK) | 25+ |
| Maven | 3.6+ |
| whois-lite-local DB | — |

Проєкт компілюється прапорцем `--release 25` і активно використовує
**virtual threads** (Java 21+), записи (`record`), текстові блоки та
pattern-matching.

---

## Збірка

```bash
# Fat-JAR (для запуску через java -jar)
mvn clean package -DskipTests

# Native app-image (через jpackage)
mvn clean verify -DskipTests
```

Ім'я артефакту: `ASBlockWar-<version>-<buildNumber>.jar`.
Номер збірки генерується `buildnumber-maven-plugin` (лічильник у `buildNumber.properties`).

### Залежності

| Група | Артефакт | Роль |
|---|---|---|
| `org.xerial` | `sqlite-jdbc` | Доступ до whois-lite-local SQLite DB |
| `org.projectlombok` | `lombok` | `@Slf4j`, `@Getter`, `@Setter` |
| `org.slf4j` / `ch.qos.logback` | slf4j-api / logback-classic | Логування |
| `org.openjfx` | `javafx-fxml`, `javafx-web` | GUI (JavaFX 27-ea) |

> **Правило версій JavaFX**: всі `javafx-*` модулі мають бути однієї версії
> (`27-ea+6` наразі). При додаванні нового модуля (наприклад `javafx-media`)
> обов'язково виставляти ту саму версію.

---

## Структура пакетів

```
net.ukrcom.asblockwar/
├── ASBlockWar.java                   # точка входу + оркестратор пайплайну
├── AsnRegexBuilder.java              # trie-компресор ASN-регексу
├── Config.java                       # конфігурація (файл + CLI аргументи)
├── UIProgressCallback.java           # інтерфейс зворотного зв'язку GUI↔обробка
│
├── actions/                          # бізнес-логіка (без DB-доступу напряму)
│   ├── BatchRunner.java              # запуск AfterCommand-скрипту
│   ├── BlackbgpChanges.java          # record: результат diff blackbgp
│   ├── DiscoverAggressor.java        # пошук кооперуючих ворожих AS
│   ├── DiscoveryResult.java          # record: mntBy + asSets після discovery
│   ├── FileUtils.java                # atomic write, ensureStoreDir, readFileEntries
│   ├── FilterAggressor.java          # isAggressor(), фільтрація мапи
│   ├── ForceBlockActions.java        # ForceASBlock bypass-логіка
│   ├── MakeAggressor.java            # початкове заповнення мапи агресорів
│   ├── NetworkUtils.java             # CIDR-утиліти, компаратори, blackbgpCmd()
│   ├── Reporter.java                 # фінальна таблиця змін
│   ├── RpslUtils.java                # rpslField() — парсинг RPSL-поля з блоку
│   └── StoreActions.java            # всі операції запису на диск
│
├── retrieveretrieve/                 # DB-доступ (кожен клас = один запит/набір)
│   ├── retrieveAllRouteOrigins.java  # bulk route→origins (для storeNetworkFiles)
│   ├── retrieveAsSet.java            # один as-set RPSL блок
│   ├── retrieveAsSetMembers.java     # рекурсивне розкриття членів as-set
│   ├── retrieveAutNumFull.java       # повний aut-num + org блок
│   ├── retrieveBlackbgpPrefixes.java # поточний стан blackbgp через SSH
│   ├── retrieveImportExportAsSets.java # AS-SET з import/export полів aut-num
│   ├── retrieveMntBy.java            # aut-num/as-set блоки за maintainer
│   ├── retrieveMntnerFull.java       # mntner + role блоки
│   ├── retrieveOrganisation.java     # синтетичний блок ASN (з кешем)
│   ├── retrieveRouteFull.java        # route/route6 блок за CIDR
│   ├── retrieveRouteOriginFull.java  # всі route/route6 за origin AS
│   ├── retrieveRouteOriginPrefixes.java # список префіксів за origin AS
│   └── retrieveRouteOrigins.java    # список origin AS за CIDR
│
├── graph/                            # граф залежностей RPSL-об'єктів
│   ├── EdgeRelation.java             # enum: MNT_BY / MNT_REF / ORG / MEMBER_OF / PEER
│   ├── GraphBuilder.java             # будує граф з blocked/suspicious/cleared мап
│   ├── GraphEdge.java                # record: (source, target, relation)
│   ├── GraphExporter.java            # генерує HTML з template.html + JSON-даними
│   ├── GraphNode.java                # record: (id, type, status, label, details)
│   ├── NodeStatus.java               # enum: BLOCKED(4) > SUSPICIOUS(3) > CLEAR(2) > UNKNOWN(1)
│   └── NodeType.java                 # enum: ASN / AS_SET / MNTNER / ORGANISATION
│
├── serviceStructures/
│   ├── Action.java                   # enum: add / remove / modify
│   ├── ASN.java                      # record: (Action, asn, data)
│   └── SuspiciousAS.java             # record: (asn, country, matchedLine) — підозрілі AS
│
└── ui/
    ├── ASBlockWarApp.java            # JavaFX Application (GUI entry point)
    ├── DependencyGraphController.java # вікно графа залежностей (WebView + D3.js)
    ├── FilePickerController.java     # кастомний file/dir picker (без GTK)
    ├── GuiLogAppender.java           # Logback AppenderBase → Consumer<String>
    ├── MainWindowsController.java    # головне вікно
    ├── PropertiesController.java     # діалог налаштувань
    ├── RunProgressController.java    # діалог прогресу запуску
    └── WhoisInfoController.java      # RPSL-переглядач
```

Подвоєна назва пакету `retrieveretrieve` — навмисна (artifact назви проєкту).

---

## Пайплайн обробки (14 кроків)

`ASBlockWar.runProcessing()` викликається і в CLI-, і в GUI-режимі.
Кроки виконуються послідовно, окрім пар 9a/9b та 12a–12d, які йдуть паралельно
через virtual threads.

| Крок | Метод | Що робить |
|---|---|---|
| 1 | `MakeAggressor.makeAggressorAsnResources()` | Читає `list.txt`, завантажує org-блоки з DB |
| 2 | `FilterAggressor.filterAggressorAsnResources()` | Відсіює записи, що не відповідають патерну |
| 3 | `ForceBlockActions.applyForceAsBlock()` | Додає примусові ASN з `ForceASBlock` |
| 4 | `MakeAggressor.makeAggressorAssetAndMntbyResources()` | Завантажує mntner/as-set блоки |
| 5 | `MakeAggressor.makeAggressorResources()` | З блоків mntner/as-set витягує нові ASN |
| 6 | `DiscoverAggressor.discoverCooperatingAsnResources()` | Розкриває import/export AS-SET |
| 7 | Repeat filter (крок 2 ще раз) | Фільтрує щойно знайдені ASN |
| 8 | `StoreActions.storeAggressorAsnResources()` | Бекап + запис нового `list.txt` |
| 9a | `StoreActions.storeWarResources()` | Генерує Juniper regex WAR-файл |
| 9b | `StoreActions.storeBlackbgpResources()` | Генерує blackbgp diff-файл |
| 10 | `StoreActions.storeMntByResources()` | Оновлює `list.mnt-by.txt` |
| 11 | `StoreActions.storeListAsSet()` | Оновлює `list.as-set.txt` |
| 12a | `StoreActions.storeDetails()` | Записує STORE/AS/, STORE/MNT/, STORE/AS-SET/ тощо |
| 12b | `StoreActions.storeAsList()` | Записує STORE/AS.list |
| 12c | `StoreActions.storeMaintainersList()` | Записує STORE/maintainers.list |
| 12d | `StoreActions.storeNetworkFiles()` | Записує STORE/networks.list та STORE/NET/ |
| 13 | `BatchRunner.runBatchCommand()` | Запускає AfterCommand-скрипт (якщо `-b`) |
| 14 | `Reporter.report()` | Виводить фінальну таблицю змін + таблицю підозрілих AS |

---

## Ключові архітектурні патерни

### 1. Virtual threads + Semaphore

Вся конкурентність побудована на `Executors.newVirtualThreadPerTaskExecutor()`.
Throttling запитів до SQLite — через `Semaphore(MAX_CONCURRENT_DB_QUERIES = 20)`,
а не через розмір пулу:

```java
try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<?>> futures = items.stream()
        .map(item -> exec.submit(() -> {
            semaphore.acquire();
            try { /* DB-запит */ }
            finally { semaphore.release(); }
        }))
        .toList();
    for (var f : futures) f.get();
}
```

SQLite підтримує паралельні читання (`WAL` / `PRAGMA journal_mode`).
Записи атомарні через `FileLock` (див. нижче).

### 2. Retrieve-класи: один клас — один JDBC-з'єднання

Кожен клас у `retrieveretrieve/` відкриває `DriverManager.getConnection()`
у конструкторі і закриває його в `try-with-resources`. Результат повертається
через `get()`. Не статичні методи — кожен виклик = новий об'єкт.

Виняток — `retrieveOrganisation`: має статичний `ConcurrentHashMap<String,String>`
кеш на весь час роботи процесу (ASN-блоки не змінюються під час одного запуску).

### 3. Атомарний запис файлів

Усі записи на диск проходять через `FileUtils.writeStoreFile()`:

```
.lock файл (FileLock)
  └─ запис у .tmp файл
       └─ ATOMIC_MOVE → цільовий файл
            └─ release lock
```

Fallback на `REPLACE_EXISTING` якщо FS не підтримує атомарне переміщення.

### 4. `resourcesForVerification` і `suspiciousAsnResources` — журнали

`ASBlockWar.resourcesForVerification` (`ConcurrentHashMap<String, ASN>`) — журнал
змін ASN (add/remove/modify), наповнюється протягом всього пайплайну, читається
один раз у `Reporter.report()`. Запис через `put()`.

`ASBlockWar.suspiciousAsnResources` (`ConcurrentHashMap<String, SuspiciousAS>`) —
AS, що збігаються з `AggressorPattern`, але не входять до `BlockCountry`. Наповнюється
у `FilterAggressor.filterAggressorAsnResources()`. Читається у `Reporter.report()`
для виводу окремої таблиці підозрілих AS.

`ASBlockWar.lastAggressorAsnResources` (`volatile Map<String, String>`) — знімок
заблокованих ASN → RPSL після завершення `runProcessing()`. Зберігається для доступу
з GUI-кнопки «Dependency» без повторного запуску обробки. На старті — порожня мапа.

Всі три колекції скидаються / перезаписуються на початку кожного `runProcessing()`.

### 5. Критерій блокування AS

```java
// FilterAggressor.isAggressor()
return isCountryBlocked(rpsl, blocked);   // country: з RPSL-блоку ∈ BlockCountry
```

`BlockCountry` — єдиний критерій блокування. `AggressorPattern` (`AGGRESSOR_COMPILED`)
більше не впливає на рішення про блокування: він використовується лише для виявлення
підозрілих AS поза `BlockCountry` (звіт у кінці пайплайну).

`AGGRESSOR_COMPILED` — нефінальне статичне поле (компілюється після завантаження
конфігу, і може бути перекомпільоване через діалог Properties з валідацією).
`ForceASBlock` обходить `isAggressor()` повністю.

### 6. GUI ↔ обробка: `UIProgressCallback`

`ASBlockWar.uiCallback` — `volatile` статичне поле, `null` в CLI-режимі.
Всі виклики захищені `if (cb != null)` або `if (ASBlockWar.uiCallback != null)`.

В GUI-режимі `RunProgressController` встановлює callback перед стартом Task,
знімає після завершення. Throttling підсвічування — 100 мс між викликами
через `AtomicLong` + `compareAndSet`.

### 7. Логування в GUI (GuiLogAppender)

`GuiLogAppender` динамічно прикріплюється до кореневого логера Logback
на початку `startProcessing()` і відкріплюється після завершення:

```java
LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
appender = new GuiLogAppender(this::appendLine);
appender.setContext(lc); appender.start();
lc.getLogger(ROOT_LOGGER_NAME).addAppender(appender);
// ... після завершення:
lc.getLogger(ROOT_LOGGER_NAME).detachAppender(appender);
appender.stop();
```

Це означає, що будь-яка бібліотека з `INFO+` логами теж з'являтиметься у
вікні прогресу.

---

## Система конфігурації (`Config.java`)

Пріоритет джерел (вищий вище):

```
CLI-аргументи (--option=value)
  └─ asblockwar.properties (шлях через --config= або CWD)
       └─ вбудований classpath-ресурс asblockwar.properties
            └─ жорсткі дефолти в коді
```

### CLI-парсинг через Picocli

Клас анотований `@Command`, кожна опція — `@Option`. Picocli замінює
весь ручний `parseArgs()`: auto-генерує `--help`, перевіряє типи,
підтримує `--option=value` і `arity = "0..1"` для опцій без обов'язкового
значення.

Трифазна ініціалізація у конструкторі:

1. `new CommandLine(this).parseArgs(args)` — Picocli встановлює всі
   `*Override`-поля і `configPath` (потрібен до завантаження файлу)
2. `loadProperties()` — читає файл з щойно встановленого `configPath`
3. Ternary-резолюція кожного поля: `override != null ? override : properties.getProperty(...)`

Кожна властивість має пару полів:
- `private String fieldName` — resolved значення (Lombok генерує getter/setter)
- `@Option(...) private String fieldNameOverride` — Picocli встановлює з CLI;
  `null` коли флаг відсутній

Спеціальні випадки:
- `Boolean ipv6Flag` / `Boolean noIpv6Flag` (boxed) — `null` коли відсутні;
  дозволяють виявити, чи задано `--ipv6`/`--no-ipv6` явно
- `Integer recursiveAssetOverride` з `arity = "0..1", fallbackValue = "1"` —
  `--recursive-asset` без значення дає 1, `--recursive-asset=3` дає 3, відсутність дає null
- `boolean batchMode` / `boolean gui` — Picocli встановлює `true` при наявності флагу;
  `if (!batchMode)` у фазі 3 безпечно звертається до properties-файлу

`DEFAULT_AGGRESSOR_PATTERN` — публічна константа, щоб Properties-контролер міг
показати дефолт або скинути до нього.

`save()` записує поточний стан у той самий файл, з якого завантажувалися.

### Додавання нової CLI-опції

Для простих рядкових/булевих параметрів (4 кроки замість колишніх 4+):

1. Додати запис у `OPT_TO_PROP`: `Map.entry("--new-opt", "NewPropKey")`
2. Додати `@Option(names = "--new-opt", defaultValue = "...", ...) private String newOpt;`
   — це одночасно CLI-поле і resolved-значення (окремого `*Override` поля не потрібно)
3. Додати `p.setProperty("NewPropKey", this.newOpt)` у `save()`
4. Якщо є UI — додати поле у `PropertiesDialog.fxml` та `PropertiesController`

Для параметрів-списків (`List<String>`): додати `String newOptOverride` (кроки 1–3)
та resolved `List<String> newOpt`, яке заповнюється через `parseList(newOptOverride)` у конструкторі.

Help генерується автоматично з анотацій — `printHelp()` більше не існує.

---

## GUI-архітектура (JavaFX / FXML)

### Вікна

| FXML | Controller | Режим |
|---|---|---|
| `MainWindows.fxml` | `MainWindowsController` | Головне вікно програми |
| `DependencyGraphView.fxml` | `DependencyGraphController` | Немодальне вікно з WebView (D3.js граф) |
| `PropertiesDialog.fxml` | `PropertiesController` | Модальний діалог (`showAndWait`) |
| `RunProgressDialog.fxml` | `RunProgressController` | Модальний діалог (`showAndWait`) |
| `WhoisInfoDialog.fxml` | `WhoisInfoController` | Модальний діалог (`showAndWait`) |
| `FilePickerDialog.fxml` | `FilePickerController` | Модальний діалог (`showAndWait`) |

**Правило**: кожне вікно — окремий FXML-файл. Не розміщувати окремі вікна
як вкладені контейнери в одному файлі.

Всі FXML посилаються на `@../styles/mainwindows.css`.

### `FilePickerController` — кастомний файловий менеджер

Стандартний `FileChooser` на деяких Linux-дистрибутивах падає (GTK-конфлікт).
`FilePickerController` реалізує власний picker через `TreeView<Path>` (дерево
директорій) + `ListView<Path>` (вміст директорії). Lazy-expansion: вузол
дерева розкривається тільки при першому відкритті.

### `WebView` у RunProgressDialog (з v3.3.16)

Лог рендериться у вбудованому WebKit (`WebView` / `WebEngine`).
JS-функція `appendLine(text, isErr)` додає `<div>` з `textContent` (XSS-safe).
Скрипти, що надходять до `Worker.State.SUCCEEDED`, ставляться у чергу
`pendingScripts` (Queue на FX-треді, без потреби у синхронізації).

Усі виклики `engine.executeScript()` — тільки з FX-треду, через `Platform.runLater`.

---

## AsnRegexBuilder — trie-компресор

Перед записом `war.juniper.txt` усі ASN стискаються у компактний регекс:

```
AS219407, AS219413, AS219445 → AS219(4(07|13|45))
```

Алгоритм: цифри кожного ASN вставляються у trie; `toRegex()` рекурсивно
серіалізує вузли, колапсуючи однобуквені альтернативи у символьні класи `[abc]`.
Особливий випадок: Juniper відхиляє `(|x)`, тому одиночний необов'язковий
альтернатив виводиться як `(x)?`.

Два форми у `war.juniper.txt`:
- `WAR1`: `".* <REGEX> .*"` — матчить AS у будь-якому місці AS-path
- `WAR2`: `".* <REGEX>$"` — матчить AS тільки в кінці AS-path

---

## Конвенції коду

### Версіонування

- Кожен коміт **обов'язково** піднімає версію у `pom.xml`.
- Виправлення помилок і косметичні зміни → PATCH (`x.y.Z`).
- Новий функціонал (нова CLI-опція, нове вікно, нова підсистема) → MINOR (`x.Y.0`).
- Формат: `x.y.z` — три цифри без суфіксів.

### Назви комітів

```
feat: короткий опис нової функції
fix: короткий опис виправлення
docs: зміни тільки в документації
refactor: рефакторинг без зміни поведінки
```

### Синхронізація документації

Кожна зміна, що впливає на поведінку або API проєкту, потребує оновлення
**трьох файлів одночасно** (у тому самому коміті):

| Файл | Що оновлювати |
|---|---|
| `README.md` | Версійний рядок (`Поточна версія — X.Y.Z`) |
| `docs/CHANGELOG.md` | Новий розділ `## [X.Y.Z] — YYYY-MM-DD` з описом змін |
| `docs/CONTRIBUTING.md` | Якщо змінився архітектурний патерн або конвенція |

Документація, що розійшлася з кодом, гірша за відсутню.

### Retrieve-класи

При додаванні нового retrieve-класу:
1. Помістити у пакет `retrieveretrieve`.
2. Конструктор відкриває JDBC-з'єднання, виконує запит, зберігає результат у полі.
3. Метод `get()` повертає незмінний результат.
4. `@Slf4j` — обов'язково.
5. Якщо клас викликається паралельно — враховувати `semaphore.acquire()` у caller.

### Actions-класи

- Лише статичні методи (або private конструктор).
- Не виконують прямий DB-доступ — делегують до retrieve-класів.
- Null-check `ASBlockWar.uiCallback` перед кожним callback-викликом.

### UI-контролери

- Кожен controller `implements Initializable` і анотований `@Slf4j`.
- `initialize()` лише ініціалізує UI-стан; жодних важких операцій (DB, диск).
- Важкі операції (DB, диск) — тільки у virtual thread або `javafx.concurrent.Task`.
- Оновлення UI після Task — через `Platform.runLater()` або `task.setOnSucceeded()`.

---

## Логування

| Рівень | Де |
|---|---|
| `DEBUG` | тільки у файл `logs/asblockwar.log` (ротація: 10 МБ / 30 днів) |
| `INFO` | консоль + файл + GUI (через `GuiLogAppender`) |
| `WARN`, `ERROR` | консоль + файл + GUI |

`WARN` консольно виводиться тому що `logback.xml` фільтрує лише рівні нижче INFO.
Конфігурація Logback — у `src/main/resources/logback.xml` (вбудовується у JAR).

---

## Типова схема додавання нової функції

1. **Нова CLI-опція**: додати `@Option`-поле у `Config`; якщо потрібен запис у
   `asblockwar.properties` — додати запис в `OPT_TO_PROP` і рядок у `save()`.
   Для опцій-прапорців типу `--dependency-graph` (arity `0..1`) достатньо одного поля;
   `isDependencyGraph()` реалізується вручну.
2. **Новий крок обробки**: додати статичний метод у відповідний `actions/`-клас,
   викликати з `ASBlockWar.runProcessing()`, додати retrieve-клас якщо потрібен
   новий DB-запит.
3. **Нове вікно**: окремий `Controller.java` + окремий FXML + статичний factory-метод
   `show(Stage owner)`. Немодальне вікно — `stage.show()` (не `showAndWait()`).
4. **Новий тип виводу**: новий метод у `StoreActions`, запустити паралельно
   з іншими `store*` в кроці 12 якщо незалежний.
5. **Нова підсистема (пакет)**: власний пакет з чистими record/enum типами даних,
   builder-класом і exporter/renderer. Дані для GUI зберігати у `volatile` статичному
   полі `ASBlockWar.*` — без повторного запуску pipeline у GUI-режимі.
