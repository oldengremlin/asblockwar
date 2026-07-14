# Changelog

Всі помітні зміни в проєкті документуються тут.
Формат базується на [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [3.4.1] — 2026-07-14

### Рефакторинг
- **`FilterAggressor`**: видалено зайвий приватний метод `isCountryBlocked()` — його тіло
  перенесено безпосередньо до `isAggressor()`. Метод `isAggressor()` тепер є єдиною точкою
  перевірки, без проміжного alias-делегування. Внутрішній виклик у `filterAggressorAsnResources()`
  оновлено відповідно. Жодних поведінкових змін.

---

## [3.4.0] — 2026-07-14

### Змінено
- **Критерій блокування AS**: `isAggressor()` спрощено — єдиним критерієм залишився
  `BlockCountry` (перевірка `country:` у RPSL-блоці). Попередня AND-логіка
  (AggressorPattern **і** BlockCountry) давала хибнонегативні результати: AS з `country:RU`,
  чий RPSL не містив ключових слів патерну, помилково не блокувалися.

### Додано
- **Звіт підозрілих AS** — новий розділ у фінальному звіті (`Reporter`):
  таблиця AS, що **не входять** до BlockCountry, але **збігаються** з AggressorPattern.
  Призначення: виявити потенційно ворожі AS, зареєстровані поза блокованою країною
  (наприклад, з `+7`-телефоном або адресою в Москві при `country: DE`).
  Колонки: `ASN | Країна | Збіг з AggressorPattern`; ширина колонок підлаштовується
  під максимальну довжину даних у кожній колонці.
- **`SuspiciousAS`** — новий record у `serviceStructures`
  (`asn`, `country`, `matchedLine`).
- **`ASBlockWar.suspiciousAsnResources`** — потокобезпечна колекція підозрілих AS,
  що скидається на початку кожного запуску `runProcessing()`.

---

## [3.3.22] — 2026-07-14

### Виправлено
- **WARNING: Unsupported JavaFX configuration** — попередній підхід (встановлення рівня JUL-логера
  `com.sun.javafx.application.PlatformImpl` у `SEVERE`) не спрацьовував, бо PlatformImpl
  може використовувати логер з іншим іменем.
  Замінено на перехоплення `System.err` на рівні байтів: `System.setErr()` встановлює
  обгортку `PrintStream`, яка відфільтровує запис, що містить рядок
  `"Unsupported JavaFX configuration"`. В Java 9+ `ConsoleHandler` динамічно делегує
  до поточного `System.err`, тому обгортка перехоплює вивід незалежно від того,
  коли ініціалізовано `ConsoleHandler`.

---

## [3.3.21] — 2026-07-14

### Виправлено
- **JVM WARNING: A restricted method / java.lang.System::load / Use --enable-native-access**:
  додано `Enable-Native-Access: ALL-UNNAMED` до маніфесту fat jar (для запуску через `java -jar`)
  та `--enable-native-access=ALL-UNNAMED` до `<javaOptions>` jpackage-конфігурації
  (для APP_IMAGE-запуску). Попередження про `NativeLibLoader` більше не з'являються.
- **JUL WARNING: Unsupported JavaFX configuration: classes were loaded from 'unnamed module'**:
  `com.sun.javafx.application.PlatformImpl` JUL-логер пригнічено до рівня `SEVERE`
  на початку `main()`. Попередження зникає не залежно від режиму запуску.

---

## [3.3.20] — 2026-07-14

### Виправлено
- **CSS WebView**: `@media (prefers-color-scheme: …)` не підтримується в embedded WebKit JavaFX,
  тому жоден з двох блоків не активувався і клас `.err` залишався без кольору.
  Виправлено: світла тема вказана безпосередньо в `body` та `.err` (фолбек),
  темна — залишена в `@media (prefers-color-scheme: dark)` (активується там, де підтримується).
  Тепер рядки stderr AfterCommand завжди підсвічуються червоним.

---

## [3.3.19] — 2026-07-14

### Виправлено
- **CSS**: WebView `#logView` отримав рамку (`-fx-border-color: derive(-fx-base, -20%)`, 1 px)
  та тінь (`dropshadow`) — узгоджено зі стилем решти контролів.
- Видалено мертвий CSS-селектор `#logScroll` (від видаленого `ScrollPane`).

---

## [3.3.18] — 2026-07-14

### Змінено
- **Picocli `IDefaultProvider`**: properties-файл тепер є другим рівнем пріоритету
  в ланцюгу CLI → properties → `@Option(defaultValue=...)`. Фазу 3 конструктора
  (13 тернарних виразів) замінено на `OPT_TO_PROP` мапу і метод `propertyDefault()`.
- 11 пар `fieldOverride`/`field` (String) об'єднано в одне поле: Picocli-анотоване
  поле стало фінальним resolved-значенням (наприклад `listFileOverride` + `listFile` → `listFile`).
- `batchMode` тепер також проходить через IDefaultProvider (`BatchMode` з properties).
- `recursiveAsset` резолвиться з `recursiveAssetFlag` одним рядком замість блоку перевірок.

---

## [3.3.17] — 2026-07-14

### Змінено
- CLI-парсер замінено на **Picocli**: `parseArgs()` і `printHelp()` видалено,
  кожна CLI-опція тепер оголошена анотацією `@Option` на відповідному полі `Config`.
- `--help` / `-h` генерується автоматично з анотацій (більше не може розійтися з реальним набором опцій).
- `--recursive-asset[=N]` використовує `arity="0..1"` / `fallbackValue="1"` — без значення = глибина 1.
- Додано залежність `picocli 4.7.6` та annotation processor `picocli-codegen`.

---

## [3.3.16] — 2026-07-14

### Додано
- **WebView** у RunProgressDialog замість `TextFlow`+`ScrollPane`: лог тепер
  підтримує виділення тексту мишею та копіювання через Ctrl+C.
- Автоматична темна/світла тема логу через CSS `prefers-color-scheme` (не потребує налаштування).
- Рядки зі `stderr` (AfterCommand) виводяться червоним, `stdout` — стандартним кольором теми.

### Залежності
- Додано `org.openjfx:javafx-web:27-ea+6`.

---

## [3.3.15] — 2026-07-14

### Змінено
- `BatchRunner`: дубльований код для stdout/stderr-потоків замінено приватним методом
  `pipeStream(InputStream, UIProgressCallback, boolean)`.

---

## [3.3.14] — 2026-07-13

### Виправлено
- **Семантика `isAggressor()`**: виправлено порядок умов — `AGGRESSOR_COMPILED` тепер
  перевіряється **першим** (широкий фільтр), `BlockCountry` — **другим** (уточнення).
  Попередній порядок міг призводити до пропуску агресорів з нетиповими country-полями.
- Повідомлення у логах `filterAggressorAsnResources()` приведено у відповідність до нової семантики.

---

## [3.3.13] — 2026-07-13

### Додано
- **Configurable `AggressorPattern`**: патерн агресора виноситься у Properties-діалог
  (поле `TextArea`) з валідацією regex перед збереженням.
- CLI-опція `--aggressor-pattern=<rx>` для перевизначення патерну без редагування файлу.
- Властивість `AggressorPattern` у `asblockwar.properties`.
- Правило `phone:[^+]*\+7.*` у дефолтному патерні (виявляє російські номери +7).

---

## [3.3.12] — 2026-07-13

### Додано
- `BlockCountry`, `ForceASBlock`, `ForceNETBlock` стали **редагованими списками** (`ListView`)
  у діалозі Properties з кнопками Add / Remove.

### Виправлено
- Записи `ForceASBlock` нормалізуються до формату `AS<number>` при завантаженні конфігу
  (приймаються `209671`, `as209671`, `AS209671` — зберігаються як `AS209671`).
- Адреси `ForceNETBlock` без маски нормалізуються до CIDR (додається `/32` або `/128`).

---

## [3.3.11] — 2026-07-13

### Виправлено
- Нормалізація записів `ForceASBlock` до верхнього регістру з префіксом `AS`.
- Нормалізація хост-адрес `ForceNETBlock` до CIDR-нотації.

---

## [3.3.10] — 2026-07-13

### Додано
- **`ForceASBlock`**: список ASN для примусового блокування, що обходять country/pattern-фільтри.
  Призначений для AS, чиї org-блоки відсутні або не відповідають патерну, але блокування необхідне.
- **`ForceNETBlock`**: список мережних префіксів для примусового додавання до blackbgp
  незалежно від результатів фільтрації.

---

## [3.3.9] — 2026-07-13

### Додано
- **Обов'язковий country-фільтр** (`BlockCountry`, за замовчуванням `RU`):
  country перевіряється як **уточнення** після AGGRESSOR_PATTERN.
- Поле `BlockCountry` у діалозі Properties.
- Вкладка **List Prefixes** у головному вікні з активними blackhole-маршрутами;
  подвійний клік відкриває RPSL-блок маршруту.

### Виправлено
- Country-перевірка застосовується послідовно в усіх шляхах discovery (включно з
  `discoverCooperatingAsnResources`).

---

## [3.3.8] — 2026-07-13

### Додано
- Поле `BlockCountry` у діалозі Properties (без підтримки списку — лише текстове поле).

---

## [3.3.7] — 2026-07-13

### Додано
- CLI-прапор `--no-ipv6` / `-no6` для вимкнення IPv6-маршрутів у blackbgp-виводі.

### Виправлено
- IPv6-маршрути увімкнені за замовчуванням (раніше потрібен був явний `--ipv6`).

---

## [3.3.6] — 2026-07-13

### Змінено
- IPv6 blackhole-маршрути увімкнені за замовчуванням (властивість `BlackbgpIpv6=true`).
- `networks.list`: формат змінено на tab-separated (`prefix\torigin`).

---

## [3.3.5] — 2026-07-12

### Додано
- CSS-клас `.round-button` (кругла кнопка 26×26 px) — застосований до кнопки очищення фільтру.

---

## [3.3.4] — 2026-07-12

### Додано
- Кнопка `×` поруч із полем фільтру акордеону для швидкого очищення.

---

## [3.3.3] — 2026-07-12

### Додано
- **Поле фільтру** над акордеоном головного вікна: миттєва фільтрація активної вкладки,
  значення зберігається при перемиканні між вкладками.
- Вкладка **List Prefixes** з переліком активних blackhole-префіксів.
- Подвійний клік на List Prefixes відкриває RPSL-блок `route`/`route6`.

---

## [3.3.2] — 2026-07-12

### Виправлено
- Виявлення `/32`-хост-маршрутів у виводі `ip route list` (записи без маски).
- Виявлення `/128`-хост-маршрутів у виводі `ip -6 route list`.

---

## [3.3.1] — 2026-07-10

### Змінено
- Вкладка **List AS** розгортається за замовчуванням при запуску.
- 5px заокруглені кути для всіх UI-контролів через спільний CSS.
- Фокус переноситься на вкладку List AS при старті.
- CSS-стилі перенесено до FXML-файлів; `UiUtils` видалено.

---

## [3.3.0] — 2026-07-10

### Додано
- **Пакетний режим** (`-b` / `--batch`): автоматичний запуск `AfterCommand`-скрипту
  після завершення обробки.
- Властивості `BatchMode` і `AfterCommand` у `asblockwar.properties`.
- В GUI-режимі stdout/stderr `AfterCommand` стрімуються у вікно Run Progress.

### Змінено
- Рефакторинг: `@Slf4j`, `@Getter`, `@Setter` через Lombok на всіх класах.
- `UIProgressCallback` і допоміжні методи винесено в окремі файли.

---

## [3.2.0] — 2026-07-09

### Додано
- JavaDoc на всіх публічних методах Java-класів.

---

## [3.1.0] — 2026-07-09

### Додано
- **Збереження конфігурації**: діалог Properties записує зміни у `asblockwar.properties`
  через `Config.save()`.

---

## [3.0.0] — 2026-07-09

### Додано
- **Графічний інтерфейс** (`-g` / `--gui`) на базі JavaFX.
- Головне вікно з акордеоном (вкладки List MNT-BY, List AS-SET, List AS),
  панелями Juniper WAR і blackbgp, кнопками Run та Properties.
- Діалог **Run Progress** з живим логом (GuiLogAppender) та індикатором прогресу.
- Діалог **Properties** для редагування конфігурації.
- Діалог **WhoisInfo**: подвійний клік на елементі списку відкриває RPSL-блок з локальної DB.
- **Live highlighting**: під час обробки активний ASN/AS-SET/MNT-BY підсвічується у відповідній вкладці.
- Кастомний **FilePickerController** (чистий JavaFX, без GTK `FileChooser`).

### Залежності
- Додано `org.openjfx:javafx-fxml:27-ea+6`.

---

## [2.0.0] — 2026-07-08

### Додано
- Структура виходу **STORE/**:
  - `AS.list` — перелік ASN з org-name та address
  - `maintainers.list` — перелік MNT-BY із role та contacts
  - `networks.list` — маршрути та їхні origin-ASN
  - `NET/<addr.prefix>.txt` — по одному файлу на префікс
  - `AS/<number>.txt`, `AS-NET/<number>.txt`, `MNT/<name>.txt`, `MNT-SET-AS/<name>.txt`, `AS-SET/<name>.txt`
- **AsnRegexBuilder**: trie-компресор ASN-послідовностей у компактний Juniper-regex.
- Вивід **`war.juniper.txt`** (WAR1 + WAR2 пари).
- Вивід **`war.blackbgp.txt`**: diff-генерація команд `ip r add/del ... t blackbgp`
  на основі SSH-зчитування поточного стану blackbgp.
- Перевірка префіксів перед видаленням з blackbgp (захист від хибного видалення).
- **`FileLock`** на всі файлові операції (атомарний запис).
- Вхідний файл `list.as-set.txt`.
- `storeMntByResources`: фільтрація SERVICE_MNT (RIPE-*) перед записом.
- CLI-опції: `--list-file=`, `--list-mnt=`, `--list-asset=`, `--whois-uri=`, `--store-dir=`,
  `--get-blackhole=`, `--get-blackhole6=`, `--war-file=`, `--blackbgp-file=`, `--help`.

### Змінено
- Паралельний запис `storeWarResources` + `storeBlackbgpResources` через virtual threads.
- Назви файлів у `STORE/AS/` та `STORE/AS-NET/` — голий номер без префіксу `AS`.

---

## [1.x] — 2026-04-10 — 2026-07-07

Перший активний цикл розробки після початкового коміту. Версія позначалась
як `1.x` до введення STORE-виходу.

### Ядро обробки (квітень 2026)
- `makeAggressorResources()`: пошук нових ASN з блоків mntby/as-set за `AGGRESSOR_PATTERN`.
- `filterAggressorAsnResources()`: фільтрація мапи за скомпільованим Pattern.
- `storeAggressorAsnResources()`: backup (`list.YYYY-MM-DDThh:mm:ss+HH:mm.txt`) + запис `list.txt`.
- Кеш `ConcurrentHashMap` у `retrieveOrganisation` для уникнення повторних DB-запитів.
- Фінальний звіт у три колонки: **Вилучено | Додано | Модифіковано**.
- Виправлення `AGGRESSOR_PATTERN`: `$` зовні групи, `.*` після ключових слів,
  замінено `String.matches()` на `Pattern.find()`.
- Паралелізм через `FixedThreadPool` (пізніше замінено на virtual threads).
- Логування через Logback (logback.xml з ротацією файлу).

### Discovery та DB (червень — липень 2026)
- `discoverCooperatingAsnResources()`: розкриття import/export AS-SET для пошуку
  кооперуючих ворожих AS.
- Виправлення SQL-запиту `retrieveMntBy` (переплутані колонки у `rpsl_mntby`).
- Перший `README.md` та документація `jpackage`-збірки.
- Конфігураційний файл `asblockwar.properties` зроблено опціональним.

---

## [pre-alpha] — 2025-07-28

- Початковий коміт: скелет проєкту (Maven POM, базова структура пакетів).
- Перші retrieve-класи та структури `ASN`, `Action`.

---

*Changelog ведеться починаючи з версії 3.3.17. Більш ранні записи відновлено з git-логу.*
