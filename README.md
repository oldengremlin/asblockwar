# ASBlockWar

Утиліта для автоматичного супроводу списку ворожих автономних систем (AS), що підлягають блокуванню.

Зчитує поточний перелік ASN, звіряє їх з локальною копією бази RPSL ([whois-lite-local](https://github.com/oldengremlin/whois-lite-local)), знаходить нові ASN через mnt-by/as-set зв'язки та AS-SET-и з import/export-політик, фільтрує за патерном агресора й оновлює список на диску. Додатково звіряє поточний стан blackhole-маршрутизації (blackbgp) через SSH і генерує diff-команди. Після виконання виводить звіт про зміни.

---

## Вимоги

| Компонент | Версія |
|---|---|
| Java | 21+ |
| Maven | 3.6+ |
| [whois-lite-local](https://github.com/oldengremlin/whois-lite-local) | актуальна база `whoislitelocal.db` |

База `whoislitelocal.db` має бути заповнена утилітою `whois-lite-local` до запуску ASBlockWar. Дивись [DATABASE.md](https://github.com/oldengremlin/whois-lite-local/blob/main/docs/DATABASE.md) за структурою схеми.

---

## Збірка

Є два варіанти збірки.

### Варіант 1: fat-JAR (`mvn clean package`)

```bash
mvn clean package
```

Збирається fat-JAR з усіма залежностями (через maven-shade-plugin):

```
target/ASBlockWar-2.0.0-<buildNumber>.jar
```

Запуск потребує встановленої JRE 21+ на цільовій машині:

```bash
java -jar target/ASBlockWar-2.0.0-00000001.jar [параметри]
```

### Варіант 2: native app image (`mvn clean verify`)

```bash
mvn clean verify
```

Окрім fat-JAR, збирається автономний образ застосунку (через jpackage-maven-plugin, тип `APP_IMAGE`) — директорія зі збудованим JRE та власним лаунчером:

```
target/ASBlockWar/
```

Запуск не потребує встановленої JRE:

```bash
target/ASBlockWar/bin/ASBlockWar [параметри]
```

> Якщо Maven не може завантажити залежності через проблеми з IPv6:
> ```bash
> MAVEN_OPTS="-Djava.net.preferIPv4Stack=true" mvn clean verify
> ```

---

## Конфігурація

Конфігураційний файл не є обов'язковим. Якщо він не заданий і не вбудований у JAR, використовуються значення за замовчуванням для всіх властивостей:

```properties
ListFile=list.txt
ListMntbyFile=list.mnt-by.txt
ListAssetFile=list.as-set.txt
WhoisLiteLocalURI=jdbc:sqlite:whoislitelocal.db
StoreDir=./STORE
WarFile=war.juniper.txt
BlackbgpFile=war.blackbgp.txt
GetBlackhole=ssh blackbgp "sudo ip r l t blackbgp"
GetBlackholeIpv6=ssh blackbgp "sudo ip -6 r l t blackbgp"
```

За потреби перед збіркою можна створити файл `src/main/resources/asblockwar.properties` на основі зразка нижче — він вбудовується у JAR при `mvn package` і завантажується з classpath.

```properties
# Шлях до файлу зі списком ASN (по одному числу на рядок)
ListFile=list.txt

# Шлях до файлу зі списком mnt-by хендлів
ListMntbyFile=list.mnt-by.txt

# Шлях до файлу зі списком AS-SET-ів
ListAssetFile=list.as-set.txt

# JDBC URI до бази даних whois-lite-local
WhoisLiteLocalURI=jdbc:sqlite:/path/to/whoislitelocal.db

# Директорія для зберігання RPSL-деталей (aut-num, mntner, routes, as-set)
StoreDir=./STORE

# Шлях до файлу Juniper WAR-конфігурації (виходить з storeWarResources)
WarFile=war.juniper.txt

# Шлях до файлу з diff-командами blackbgp (виходить з storeBlackbgpResources)
BlackbgpFile=war.blackbgp.txt

# Команда читання поточного стану таблиці blackbgp (IPv4)
GetBlackhole=ssh blackbgp "sudo ip r l t blackbgp"

# Команда читання поточного стану таблиці blackbgp (IPv6)
GetBlackholeIpv6=ssh blackbgp "sudo ip -6 r l t blackbgp"
```

Альтернативно — зовнішній конфіг через аргумент `--config=`:

```bash
java -jar ASBlockWar-2.0.0-00000001.jar --config=/etc/asblockwar/asblockwar.properties
```

---

## Вхідні файли

### `list.txt` — список ASN для блокування

По одному числу на рядок. Рядки, що починаються з `#` або `;`, ігноруються.

```
# Список ворожих AS
12389
25159
208398
```

### `list.mnt-by.txt` — список mnt-by хендлів

Кожен хендл — ідентифікатор мейнтейнера RIPE. Рядки-коментарі ігноруються.

```
# Мейнтейнери
ROSNIIROS-MNT
RIPE-NCC-RPSL-MNT-RU
```

---

## Запуск

```bash
java -jar target/ASBlockWar-2.0.0-00000001.jar [параметри]
```

### Параметри командного рядка

| Параметр | Опис |
|---|---|
| `--config=<шлях>` | Зовнішній конфігураційний файл (за замовчуванням — вбудований) |
| `--list-file=<шлях>` | Файл зі списком ASN (за замовчуванням: `list.txt`) |
| `--list-mnt=<шлях>` | Файл зі списком mnt-by хендлів (за замовчуванням: `list.mnt-by.txt`) |
| `--list-asset=<шлях>` | Файл зі списком AS-SET-ів (за замовчуванням: `list.as-set.txt`) |
| `--whois-uri=<uri>` | JDBC URI до бази whois-lite-local (за замовчуванням: `jdbc:sqlite:whoislitelocal.db`) |
| `--store-dir=<шлях>` | Директорія для STORE-файлів (за замовчуванням: `./STORE`) |
| `--war-file=<шлях>` | Вихідний файл Juniper WAR (за замовчуванням: `war.juniper.txt`) |
| `--blackbgp-file=<шлях>` | Вихідний файл diff-команд blackbgp (за замовчуванням: `war.blackbgp.txt`) |
| `--get-blackhole=<cmd>` | Команда читання поточного стану blackbgp, IPv4 |
| `--get-blackhole6=<cmd>` | Команда читання поточного стану blackbgp, IPv6 |
| `-6`, `--ipv6` | Враховувати також IPv6-маршрути в blackbgp-звірці |
| `--recursive-asset` | Рекурсивно заходити у вкладені AS-SET-и (глибина 1) |
| `--recursive-asset=N` | Рекурсія до глибини N |
| `-h`, `--help` | Вивести довідку та вийти |

---

## Алгоритм роботи

```mermaid
flowchart TD
    Start([Старт])

    Start --> M1
    Start --> M2

    M1["[1] makeAggressorAsnResources\nlist.txt → RPSL з DB для кожного ASN\nvirtual threads + semaphore"]
    M2["[2] makeAggressorAssetAndMntbyResources\nlist.mnt-by.txt + blockedAsSet\nas-set / mnt-by → RPSL блоки"]

    M1 --> F1["[3] filterAggressorAsnResources ①\nASN без ознак агресора → вилучити\n→ resourcesForVerification Action.remove"]

    F1 --> MR
    M2 --> MR

    MR["[4] makeAggressorResources\nASN з mntby-блоків → AGGRESSOR_PATTERN\nadd / modify / remove"]

    MR --> F2["[5] filterAggressorAsnResources ②\nфінальна фільтрація"]

    F2 --> DC["[6] discoverCooperatingAsnResources\nimport/export → AS-SET → members\nворожі → add до списку + зібрати mnt-by: + AS-SET"]

    DC --> SM["[7] storeMntByResources + storeListAsSet\nдодати нові мантейнери до list.mnt-by.txt\nдодати нові AS-SET до list.as-set.txt"]

    SM --> WR
    SM --> BG

    WR["[8a] storeWarResources\ntrie-оптимізований regex\nset policy-options as-path WAR1/WAR2"]
    BG["[8b] storeBlackbgpResources\nSSH: поточний стан blackbgp\nDB: цільові prefixes ворожих ASN\ndiff → war.blackbgp.txt"]

    WR --> NE
    BG --> NE

    NE{"[9] Нові вороги\nвиявлені при перевірці\nвидалення з blackbgp?"}
    NE -- так --> WR2["storeWarResources (повторно)\nз урахуванням нових ворогів"]
    NE -- ні --> ST
    WR2 --> ST

    ST["[10] storeAggressorAsnResources\nbackup list.txt → list.TIMESTAMP.txt\nзапис відсортованого списку ASN"]

    ST --> SD
    ST --> AL
    ST --> ML
    ST --> NF

    SD["[11a] storeDetails\nSTORE/AS, STORE/MNT, STORE/MNT-SET-AS\nSTORE/AS-SET, STORE/AS-NET"]
    AL["[11b] storeAsList\nSTORE/AS.list"]
    ML["[11c] storeMaintainersList\nSTORE/maintainers.list"]
    NF["[11d] storeNetworkFiles\nSTORE/networks.list + STORE/NET/"]

    SD --> RP
    AL --> RP
    ML --> RP
    NF --> RP

    RP["[12] report\nтаблиця: Вилучено / Додано / Модифіковано"]

    RP --> End([Кінець])

    classDef input   fill:#dbeafe,stroke:#3b82f6,color:#1e3a5f
    classDef filter  fill:#fef3c7,stroke:#f59e0b,color:#78350f
    classDef process fill:#dcfce7,stroke:#22c55e,color:#14532d
    classDef output  fill:#f3e8ff,stroke:#a855f7,color:#581c87

    class M1,M2 input
    class F1,F2,NE filter
    class MR,DC process
    class SM,WR,BG,WR2,ST,SD,AL,ML,NF,RP output
```

**Легенда:**
🔵 синій — вхідні дані (M1, M2) &nbsp;
🟡 жовтий — фільтри (F1, F2, NE) &nbsp;
🟢 зелений — обробка (MR, DC) &nbsp;
🟣 фіолетовий — вивід (SM, WR, BG, WR2, ST, SD, AL, ML, NF, RP)

Кроки `[8a]`/`[8b]` та `[11a]`–`[11d]` виконуються паралельно (virtual threads).

### Патерн агресора

ASN вважається ворожим, якщо його RPSL-блок містить хоча б один з рядків:

| Атрибут | Критерій |
|---|---|
| `org-name:` | містить `Kaspersky` або `Qrator` |
| `country:` | містить `ru` |
| `address:` | містить `moscow`, `moskow`, `russia`, `rusia` тощо |
| `abuse-mailbox:` | закінчується на `.ru` |

### Вбудовані AS-SET-и (blockedAsSet)

Завжди перевіряються незалежно від `list.mnt-by.txt`:

- `AS-MAILRU`, `AS-VK`, `AS-VKONTAKTE`, `AS-YANDEX`, `AS-M100`

---

## Вихідні файли

### Оновлений `list.txt`

Після успішного виконання `list.txt` замінюється відфільтрованим, чисельно відсортованим списком:

```
12389
25159
208398
```

### Резервна копія

Перед перезаписом поточний файл зберігається поряд:

```
list.2026-07-06T22:15:00+03:00.txt
```

### Оновлений `list.mnt-by.txt`

Нові мантейнери, знайдені через import/export AS-SET-ів, дописуються в кінець файлу (без дублювання, службові `RIPE-*` виключаються).

### Оновлений `list.as-set.txt`

AS-SET-и, виявлені при обході import/export-політик ворожих ASN, дописуються в кінець файлу (без дублювання). Також завжди включаються вбудовані AS-SET-и (`AS-MAILRU`, `AS-VK`, `AS-YANDEX` тощо).

### Директорія `STORE/`

Детальні RPSL-дані зберігаються у підкаталогах з правами `0750`. Кожен файл записується атомарно (через тимчасовий файл + перейменування):

```
STORE/
├── AS/
│   └── 12345.txt          # aut-num блок + резюме з asn-таблиці + org блок
├── MNT/
│   └── EXAMPLE-MNT.txt    # mntner блок + пов'язані role-блоки
├── MNT-SET-AS/
│   └── EXAMPLE-MNT.txt    # aut-num / as-set об'єкти, що обслуговуються мантейнером
├── AS-SET/
│   └── AS-EXAMPLE.txt     # as-set блок
├── AS-NET/
│   └── 12345.txt          # всі route / route6 блоки для AS
├── NET/
│   └── 1.2.3.0.24.txt     # список origin-ASN для конкретного prefix
├── AS.list                # зведений список: ASN + org-name, address
├── maintainers.list       # зведений список: mnt-by + role, address
└── networks.list          # зведений список: prefix + origin-ASN (усі, через bulk-запит)
```

Вміст файлів відповідає виводу `whois-lite-local`:

| Директорія/файл | Еквівалент wll | Опис |
|---|---|---|
| `STORE/AS/` | `-ran {as}` | aut-num + org |
| `STORE/MNT/` | `-rm {mnt}` | mntner + role |
| `STORE/MNT-SET-AS/` | `-rmb {mnt}` | aut-num/as-set під мантейнером |
| `STORE/AS-SET/` | `-ras {asset}` | as-set |
| `STORE/AS-NET/` | `-rro {as}` | route/route6 |
| `STORE/NET/{prefix}.txt` | — | `origin:` для кожного ASN, що анонсує prefix |
| `STORE/AS.list` | — | компактна таблиця ASN → org-name, address |
| `STORE/maintainers.list` | — | компактна таблиця mnt-by → role, address |
| `STORE/networks.list` | — | компактна таблиця prefix → список origin-ASN |

`AS.list`, `maintainers.list` і `networks.list` формуються паралельно зі `STORE/*` та не перезаписуються, якщо вміст порожній.

`networks.list` відсортований за адресою мережі (за зростанням), а при однаковій
адресі — за довжиною маски (за зростанням, від `/1` до `/32`); IPv4-мережі йдуть
перед IPv6. Це окреме сортування від `war.blackbgp.txt`, де найспецифічніші
маски (найбільша довжина) йдуть першими.

### `war.juniper.txt` — Juniper as-path конфігурація

Після завершення генерується файл з командами Juniper для фільтрації за AS-шляхом.
Regex оптимізовано через trie-стиснення — спільні числові префікси факторизуються:

```
set policy-options as-path WAR1 ".* 1(2389|3414|...) .*"
set policy-options as-path WAR2 ".* 1(2389|3414|...)$"
```

WAR1 і WAR2 містять **однаковий** оптимізований regex, але з різним обрамленням:

| Запис | Патерн | Значення |
|---|---|---|
| WAR1 | `.* REGEX .*` | AS зустрічається в середині AS-шляху |
| WAR2 | `.* REGEX$` | AS знаходиться в кінці шляху (origin AS) |

Разом WAR1 + WAR2 покривають усі позиції ворожого AS у шляху.
Juniper реалізує DFA, тому довжина regex і кількість альтернатив не впливають
на швидкість обробки.

**Приклад стиснення:** `219407|219413|219445|219470|219529`
→ `219(4(07|13|45|70)|529)` (42 → 22 символи, -48 %)

Якщо під час звірки blackbgp (див. нижче) виявляються нові ворожі ASN,
`war.juniper.txt` перегенеровується вдруге — вже з їх урахуванням.

### `war.blackbgp.txt` — diff-команди для blackhole-маршрутизації

Порівнює поточний стан таблиці маршрутизації `blackbgp` (читається по SSH командою
з `GetBlackhole`/`GetBlackholeIpv6`) з цільовим набором prefixes ворожих ASN (з БД
whois-lite-local). Записуються лише **зміни** — команди видалення застарілих
маршрутів і додавання/оновлення нових:

```
sudo ip r d bl 1.2.3.0/24 t blackbgp
sudo ip -6 r d bl 2001:db8::/32 t blackbgp
sudo ip r r bl 5.6.7.0/24 t blackbgp
```

IPv6-маршрути враховуються лише з прапорцем `-6`/`--ipv6`; без нього diff і файл
обмежуються IPv4.

Перед видаленням кожен маршрут-кандидат перевіряється двічі:

1. Чи належить він уже відомій ворожій AS? Якщо так — видалення скасовується.
2. Чи належить він AS, яка виявилась ворожою саме зараз (нова, ще не в списку)?
   Якщо так — AS додається до `aggressorAsnResources` і `list.txt`, видалення
   скасовується, а `war.juniper.txt` перегенеровується з її урахуванням.

Це запобігає випадковому розблокуванню маршруту ворожого ASN лише тому, що він
тимчасово випав із проміжного стану обробки.

---

## Логування

| Потік | Рівні | Призначення |
|---|---|---|
| Консоль | `INFO`, `ERROR` | Прогрес виконання |
| `logs/jAS12593Backup.log` | `DEBUG` і вище | Детальний лог з ротацією (10 МБ / 30 днів) |

Зміни (вилучено / додано / модифіковано) виводяться у вигляді таблиці в `INFO`:

```
Вилучено     │ Додано      │ Модифіковано
3            │ 7           │ 1
━━━━━━━━━━━━━┿━━━━━━━━━━━━━┿━━━━━━━━━━━━━
AS1234       │ AS5678      │ AS9012
AS2345       │ AS6789      │
             │ AS7890      │
```

---

## Паралелізм

Утиліта використовує Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) для паралельних запитів до БД. Кількість одночасних з'єднань обмежена семафором (`MAX_CONCURRENT_DB_QUERIES = 20`).

Незалежні етапи виводу (`storeWarResources` / `storeBlackbgpResources`, а також
`storeDetails` / `storeAsList` / `storeMaintainersList` / `storeNetworkFiles`)
запускаються одночасно окремими задачами executor-а, а не послідовно.

---

## Зв'язані проекти

- [whois-lite-local](https://github.com/oldengremlin/whois-lite-local) — локальна RPSL-база даних (SQLite), яку використовує ASBlockWar як джерело даних.

---

## Ліцензія

Apache License 2.0 — див. [LICENSE](LICENSE).
