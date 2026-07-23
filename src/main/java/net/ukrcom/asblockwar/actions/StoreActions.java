/*
 * Copyright 2026 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ukrcom.asblockwar.actions;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.ukrcom.asblockwar.ASBlockWar;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.AsnRegexBuilder;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAllRouteOrigins;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAsSet;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAutNumFull;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveMntBy;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveMntnerFull;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveRouteOriginFull;

/**
 * Методи збереження всіх ресурсів ASBlockWar на диск.
 * <p>
 * Охоплює запис файлів WAR1/WAR2, blackbgp, list.txt, AS.list, maintainers.list,
 * networks.list, а також детальних RPSL-файлів у STORE/.
 */
@Slf4j
public class StoreActions {

    private StoreActions() {
    }

    /**
     * Зберігає виявлені MNT-BY ідентифікатори у файл {@code list_mntby}.
     * <p>
     * Об'єднує наявні записи з новими, фільтрує службові мантейнери RIPE
     * (шаблон {@code RIPE-.*}), сортує і записує атомарно.
     *
     * @param discovered набір щойно виявлених MNT-BY ідентифікаторів
     * @throws IOException якщо виникла помилка читання або запису файлу
     */
    public static void storeMntByResources(Set<String> discovered) throws IOException {
        log.debug("storeMntByResources: знайдено мантейнерів (до фільтрації): {}", discovered);

        Path path = Path.of(ASBlockWar.listMntbyFile);
        Set<String> existing = FileUtils.readFileEntries(path);

        List<String> merged = Stream.concat(existing.stream(), discovered.stream())
                .map(String::toUpperCase)
                .filter(m -> !DiscoverAggressor.SERVICE_MNT.matcher(m).matches())
                .distinct()
                .sorted()
                .toList();

        if (merged.isEmpty()) {
            log.info("storeMntByResources: список мантейнерів порожній");
            return;
        }

        FileUtils.writeStoreFile(path, String.join("\n", merged) + "\n");
        log.info("storeMntByResources: записано {} мантейнерів до {}", merged.size(), ASBlockWar.listMntbyFile);
    }

    /**
     * Зберігає виявлені AS-SET ідентифікатори у файл списку AS-SET.
     * <p>
     * Об'єднує наявні записи з новими, нормалізує до верхнього регістру,
     * видаляє завершальні крапки з комою, сортує і записує атомарно.
     *
     * @param discovered набір щойно виявлених AS-SET ідентифікаторів
     * @throws IOException якщо виникла помилка читання або запису файлу
     */
    public static void storeListAsSet(Set<String> discovered) throws IOException {
        log.debug("storeListAsSet: знайдено AS-SET: {}", discovered);

        String listAssetFile = ASBlockWar.config.getListAssetFile();
        Path path = Path.of(listAssetFile);
        Set<String> existing = FileUtils.readFileEntries(path);

        List<String> merged = Stream.concat(existing.stream(), discovered.stream())
                .map(String::toUpperCase)
                .map(s -> s.endsWith(";") ? s.substring(0, s.length() - 1) : s)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .toList();

        if (merged.isEmpty()) {
            log.info("storeListAsSet: список AS-SET порожній");
            return;
        }

        FileUtils.writeStoreFile(path, String.join("\n", merged) + "\n");
        log.info("storeListAsSet: записано {} AS-SET до {}", merged.size(), listAssetFile);
    }

    /**
     * Зберігає актуальний список ворожих ASN у файл {@code list.txt}.
     * <p>
     * Перед записом створює резервну копію з позначкою часу в імені.
     * Запис виконується атомарно через тимчасовий файл і {@link FileLock}.
     * Список відсортовано за числовим значенням ASN, по одному номеру на рядок (без префіксу «AS»).
     *
     * @param aggressorAsnResources карта {@code ASN → RPSL-блок}
     * @throws IOException якщо виникла помилка запису або блокування файлу
     */
    public static void storeAggressorAsnResources(Map<String, String> aggressorAsnResources) throws IOException {
        if (ASBlockWar.config.isDryRun()) {
            log.debug("DRY-RUN: skip backup + skip write → {}", ASBlockWar.listFile);
            return;
        }
        Path source = Path.of(ASBlockWar.listFile);
        Path lockPath = source.resolveSibling(source.getFileName() + ".lock");

        // Визначаємо директорію для резервних копій (з конфігурації або поруч із list.txt)
        String backupDirStr = ASBlockWar.config.getListFileBackupDir();
        Path backupDir = (backupDirStr != null && !backupDirStr.isBlank())
                ? Path.of(backupDirStr)
                : source.toAbsolutePath().getParent();

        // Обчислюємо новий вміст до входу в секцію з блокуванням
        String newContent = aggressorAsnResources.keySet().stream()
                .sorted(Comparator.comparingLong(asn -> Long.parseLong(asn.substring(2))))
                .map(asn -> asn.substring(2))
                .collect(Collectors.joining("\n", "", "\n"));

        try {
            try (FileChannel lc = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock fl = lc.lock()) {

                // Порівнюємо з останнім бекапом — пропускаємо якщо вміст не змінився
                Optional<Path> latestBackup = findLatestBackup(source, backupDir);
                if (latestBackup.isPresent() && Files.readString(latestBackup.get()).equals(newContent)) {
                    log.info("list.txt не змінився ({} AS) — вміст збігається з останнім бекапом, пропущено",
                            aggressorAsnResources.size());
                    return;
                }

                // Резервна копія: list.txt → list.2026-04-12T13:29:06+03:00.txt
                if (Files.exists(source)) {
                    Files.createDirectories(backupDir);
                    String filename = source.getFileName().toString();
                    int dotIdx = filename.lastIndexOf('.');
                    String timestamp = ZonedDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"));
                    String backupFilename = dotIdx >= 0
                                            ? filename.substring(0, dotIdx) + "." + timestamp + filename.substring(dotIdx)
                                            : filename + "." + timestamp;
                    Path backup = backupDir.resolve(backupFilename);
                    // Files.copy замість move — безпечно між різними файловими системами
                    Files.copy(source, backup);
                    log.info("Резервна копія: {}", backup);
                }

                // Записуємо відсортований список (тільки числа, по одному на рядок)
                Path tmp = source.resolveSibling(source.getFileName() + ".tmp");
                Files.writeString(tmp, newContent);
                try {
                    Files.move(tmp, source, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, source, StandardCopyOption.REPLACE_EXISTING);
                }
                log.info("Збережено {} AS у {}", aggressorAsnResources.size(), source);
            }
        } finally {
            Files.deleteIfExists(lockPath);
        }
    }

    /**
     * Знаходить найновішу резервну копію файлу {@code source} у директорії {@code backupDir}.
     * Шукає файли з іменем виду {@code base.TIMESTAMP.ext} (де {@code base.ext} — оригінальне ім'я).
     *
     * @return Optional з шляхом до останнього бекапу, або empty якщо бекапів немає
     */
    private static Optional<Path> findLatestBackup(Path source, Path backupDir) throws IOException {
        String base = source.getFileName().toString();
        int dot = base.lastIndexOf('.');
        String prefix = dot >= 0 ? base.substring(0, dot) + "." : base + ".";
        String suffix = dot >= 0 ? base.substring(dot) : "";
        if (!Files.isDirectory(backupDir)) {
            return Optional.empty();
        }
        try (var stream = Files.list(backupDir)) {
            return stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(suffix) && !name.equals(base);
                    })
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        }
    }

    /**
     * Оркеструє фінальне збереження всіх трьох ключових ресурсів.
     * <p>
     * Спочатку аналізує зміни у blackbgp ({@link DiscoverAggressor#discoverBlackbgpChanges}),
     * зливає будь-які нові ворожі ASN у {@code aggressorAsnResources},
     * а потім паралельно виконує:
     * <ul>
     *   <li>{@link #storeWarResources} — Juniper WAR1/WAR2 regex</li>
     *   <li>{@link #storeAggressorAsnResources} — оновлений list.txt</li>
     *   <li>{@link #storeBlackbgpResources} — команди blackbgp</li>
     * </ul>
     * Усі три методи отримують фінальний, повний набір ворожих ASN.
     *
     * @param aggressorAsnResources карта {@code ASN → RPSL-блок}; може бути розширена новими ворогами
     * @return ефективний набір prefix'ів після застосування змін blackbgp
     * @throws IOException якщо будь-який з методів запису завершився помилкою
     */
    public static Set<String> storeResources(Map<String, String> aggressorAsnResources) throws IOException {
        // аналіз: виявляємо зміни у blackbgp і можливі нові ворожі AS
        BlackbgpChanges changes = DiscoverAggressor.discoverBlackbgpChanges(aggressorAsnResources);

        Map<String, String> newEnemies = changes.newEnemies();
        if (!newEnemies.isEmpty()) {
            aggressorAsnResources.putAll(newEnemies);
            log.info("Виявлено {} нових ворожих ASN під час перевірки видалення: {}",
                    newEnemies.size(), newEnemies.keySet());
        }

        // усі три store паралельно — map вже фінальний, changes вже обчислено
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            var warTask = exec.submit(() -> {
                storeWarResources(aggressorAsnResources);
                return null;
            });
            var asnTask = exec.submit(() -> {
                storeAggressorAsnResources(aggressorAsnResources);
                return null;
            });
            var bgpTask = exec.submit(() -> {
                storeBlackbgpResources(changes);
                return null;
            });
            for (var task : List.of(warTask, asnTask, bgpTask)) {
                try {
                    task.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException ioe) {
                        throw ioe;
                    }
                    throw new RuntimeException(e.getCause());
                }
            }
        }

        return changes.effectivePrefixes();
    }

    /**
     * Генерує Juniper-конфігурацію WAR1 і WAR2 та записує її у файл.
     * <p>
     * Будує trie-оптимізований регулярний вираз з усіх ASN через {@link AsnRegexBuilder}
     * і формує два рядки:
     * <ul>
     *   <li>{@code WAR1} — AS у середині шляху: {@code ".* REGEX .*"}</li>
     *   <li>{@code WAR2} — AS у кінці шляху (origin AS): {@code ".* REGEX$"}</li>
     * </ul>
     *
     * @param aggressorAsnResources карта {@code ASN → RPSL-блок}
     * @throws IOException якщо виникла помилка запису файлу
     */
    public static void storeWarResources(Map<String, String> aggressorAsnResources) throws IOException {
        AsnRegexBuilder builder = new AsnRegexBuilder();
        aggressorAsnResources.keySet().stream()
                .mapToLong(asn -> Long.parseLong(asn.substring(2)))
                .sorted()
                .forEach(builder::add);

        String regex = builder.build();

        int rawLen = aggressorAsnResources.keySet().stream()
                .mapToInt(asn -> asn.length() - 2)
                .sum() + Math.max(0, aggressorAsnResources.size() - 1);

        // WAR1 і WAR2 містять однаковий regex, лише обрамлення різне:
        //   WAR1  ".* REGEX .*"  — AS зустрічається в середині шляху
        //   WAR2  ".* REGEX$"   — AS знаходиться в кінці шляху (origin AS)
        String war1 = "set policy-options as-path WAR1 \".* " + regex + " .*\"";
        String war2 = "set policy-options as-path WAR2 \".* " + regex + "$\"";

        Path path = Path.of(ASBlockWar.config.getWarFile());
        FileUtils.writeStoreFile(path, war1 + "\n" + war2 + "\n");

        log.info("storeWarResources: WAR1+WAR2 записано у {} ({} ASN, {} → {} chars, -{} %)",
                ASBlockWar.config.getWarFile(), aggressorAsnResources.size(),
                rawLen, regex.length(), rawLen > 0 ? (rawLen - regex.length()) * 100 / rawLen : 0);
    }

    /**
     * Записує команди blackbgp у файл на основі заздалегідь обчислених змін.
     * <p>
     * Кожен рядок — це команда {@code ip r d bl PREFIX t blackbgp} (видалення)
     * або {@code ip r r bl PREFIX t blackbgp} (заміна), відсортовані за {@link NetworkUtils#CIDR_ORDER}.
     *
     * @param changes обчислені зміни blackbgp з {@link DiscoverAggressor#discoverBlackbgpChanges}
     * @throws IOException якщо виникла помилка запису файлу
     */
    public static void storeBlackbgpResources(BlackbgpChanges changes) throws IOException {
        String content = Stream.concat(
                changes.toDelete().stream().sorted(NetworkUtils.CIDR_ORDER).map(p -> NetworkUtils.blackbgpCmd("d", p)),
                changes.toReplace().stream().sorted(NetworkUtils.CIDR_ORDER).map(p -> NetworkUtils.blackbgpCmd("r", p))
        ).collect(Collectors.joining("\n", "", "\n"));

        Path path = Path.of(ASBlockWar.config.getBlackbgpFile());
        FileUtils.writeStoreFile(path, content);

        log.info("storeBlackbgpResources: {} delete + {} replace → {}",
                changes.toDelete().size(), changes.toReplace().size(), ASBlockWar.config.getBlackbgpFile());
    }

    /**
     * Записує людиночитаний перелік ворожих ASN у {@code STORE/AS.list}.
     * <p>
     * Кожен рядок містить числовий номер AS та, за наявності, назву організації й адресу
     * у форматі {@code ASN     org-name, address}.
     *
     * @param aggressorAsnResources карта {@code ASN → RPSL-блок}
     * @throws IOException якщо виникла помилка запису файлу
     */
    public static void storeAsList(Map<String, String> aggressorAsnResources) throws IOException {
        Path base = Path.of(ASBlockWar.config.getStoreDir());
        FileUtils.ensureStoreDir(base);

        String content = aggressorAsnResources.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> Long.parseLong(e.getKey().substring(2))))
                .map(e -> {
                    long asn = Long.parseLong(e.getKey().substring(2));
                    String block = e.getValue();
                    String orgName = RpslUtils.rpslField(block, "org-name");
                    String address = RpslUtils.rpslField(block, "address");
                    String info = orgName.isEmpty() ? ""
                                  : address.isEmpty() ? orgName
                                    : orgName + ", " + address;
                    return info.isEmpty()
                           ? Long.toString(asn)
                           : String.format("%-8d%s", asn, info);
                })
                .collect(Collectors.joining("\n", "", "\n"));

        FileUtils.writeStoreFile(base.resolve("AS.list"), content);
        log.info("storeAsList: записано {} AS до AS.list", aggressorAsnResources.size());
    }

    /**
     * Записує перелік мантейнерів з описом у {@code STORE/maintainers.list}.
     * <p>
     * Для кожного MNT-BY ідентифікатора завантажує повний RPSL-блок мантейнера і витягує
     * поля {@code role} та {@code address}. Результат відсортовано за іменем мантейнера.
     *
     * @param allMntBy повний набір MNT-BY ідентифікаторів
     * @throws IOException якщо виникла помилка запису файлу
     */
    public static void storeMaintainersList(Set<String> allMntBy) throws IOException {
        Path base = Path.of(ASBlockWar.config.getStoreDir());
        FileUtils.ensureStoreDir(base);

        Map<String, String> infoByMnt = new ConcurrentHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);
            allMntBy.forEach(mnt -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    try {
                        String block = new retrieveMntnerFull(mnt).get();
                        String role = RpslUtils.rpslField(block, "role");
                        String address = RpslUtils.rpslField(block, "address");
                        String info = role.isEmpty() ? ""
                                      : address.isEmpty() ? role
                                        : role + ", " + address;
                        infoByMnt.put(mnt, info);
                    } finally {
                        dbLimit.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        String content = allMntBy.stream()
                .sorted()
                .map(mnt -> {
                    String info = infoByMnt.getOrDefault(mnt, "");
                    return info.isEmpty() ? mnt : String.format("%-36s%s", mnt, info);
                })
                .collect(Collectors.joining("\n", "", "\n"));

        FileUtils.writeStoreFile(base.resolve("maintainers.list"), content);
        log.info("storeMaintainersList: записано {} мантейнерів до maintainers.list", allMntBy.size());
    }

    /**
     * Записує мережеві файли для ефективного набору prefix'ів blackbgp.
     * <p>
     * Генерує:
     * <ul>
     *   <li>{@code STORE/networks.list} — рядки {@code prefix  as1, as2, ...}</li>
     *   <li>{@code STORE/NET/{addr.prefix}.txt} — один файл на prefix з переліком origin-AS</li>
     * </ul>
     * Використовує один bulk-запит до БД для отримання всіх origin-AS.
     *
     * @param effectivePrefixes набір активних prefix'ів після застосування змін blackbgp
     * @throws IOException якщо виникла помилка запису файлу
     */
    public static void storeNetworkFiles(Set<String> effectivePrefixes) throws IOException {
        if (effectivePrefixes.isEmpty()) {
            log.info("storeNetworkFiles: effectivePrefixes пустий, пропускаємо");
            return;
        }
        Path base = Path.of(ASBlockWar.config.getStoreDir());
        Path dirNet = base.resolve("NET");
        FileUtils.ensureStoreDir(dirNet);

        // 1. Один bulk-запит замість N індивідуальних з'єднань
        log.info("storeNetworkFiles: читаємо origins з БД (bulk)...");
        Map<String, List<String>> allOrigins = new retrieveAllRouteOrigins().get();
        log.info("storeNetworkFiles: отримано origins для {} маршрутів з БД", allOrigins.size());

        // 2. Відбираємо тільки ті префікси, що є в effectivePrefixes
        List<Map.Entry<String, List<String>>> sorted = effectivePrefixes.stream()
                .map(p -> Map.entry(p, allOrigins.getOrDefault(p, List.of())))
                .sorted(Map.Entry.comparingByKey(NetworkUtils.NETWORK_ADDR_ORDER))
                .toList();

        // 3. Записуємо STORE/networks.list
        String networksList = sorted.stream()
                .map(e -> {
                    String asns = e.getValue().stream()
                            .map(String::toLowerCase)
                            .collect(Collectors.joining(", "));
                    return e.getKey() + "\t" + asns;
                })
                .collect(Collectors.joining("\n", "", "\n"));
        FileUtils.writeStoreFile(base.resolve("networks.list"), networksList);
        log.info("storeNetworkFiles: networks.list записано ({} рядків)", sorted.size());

        // 4. Записуємо STORE/NET/{addr.prefix}.txt
        int count = 0;
        for (Map.Entry<String, List<String>> e : sorted) {
            String filename = e.getKey().replace('/', '.') + ".txt";
            String content = e.getValue().stream()
                    .map(o -> String.format("%-16s%s", "origin:", o.toLowerCase()))
                    .collect(Collectors.joining("\n", "", "\n"));
            FileUtils.writeStoreFile(dirNet.resolve(filename), content);
            if (++count % 10000 == 0) {
                log.info("storeNetworkFiles: NET/ {}/{}", count, sorted.size());
            }
        }

        log.info("storeNetworkFiles: завершено — {} файлів у NET/", count);
    }

    /**
     * Записує детальні RPSL-файли для всіх ворожих ASN, мантейнерів та AS-SET.
     * <p>
     * Паралельно формує:
     * <ul>
     *   <li>{@code STORE/AS/{asn}.txt} — повний RPSL aut-num</li>
     *   <li>{@code STORE/AS-NET/{asn}.txt} — повний RPSL route-origin</li>
     *   <li>{@code STORE/MNT/{mnt}.txt} — повний RPSL mntner</li>
     *   <li>{@code STORE/MNT-SET-AS/{mnt}.txt} — RPSL мантейнера разом з AS</li>
     *   <li>{@code STORE/AS-SET/{asset}.txt} — повний RPSL as-set</li>
     * </ul>
     *
     * @param aggressorAsnResources карта {@code ASN → RPSL-блок}
     * @param allMntBy повний набір MNT-BY ідентифікаторів
     * @param allAsSets повний набір AS-SET ідентифікаторів
     * @throws IOException якщо виникла помилка запису будь-якого файлу
     */
    public static void storeDetails(Map<String, String> aggressorAsnResources, Set<String> allMntBy, Set<String> allAsSets) throws IOException {
        Path base = Path.of(ASBlockWar.config.getStoreDir());
        Path dirAS = base.resolve("AS");
        Path dirMNT = base.resolve("MNT");
        Path dirMNTSETAS = base.resolve("MNT-SET-AS");
        Path dirASSet = base.resolve("AS-SET");
        Path dirASNet = base.resolve("AS-NET");

        FileUtils.ensureStoreDir(dirAS);
        FileUtils.ensureStoreDir(dirMNT);
        FileUtils.ensureStoreDir(dirMNTSETAS);
        FileUtils.ensureStoreDir(dirASSet);
        FileUtils.ensureStoreDir(dirASNet);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);

            // STORE/AS/{asn}.txt and STORE/AS-NET/{asn}.txt
            aggressorAsnResources.keySet().forEach(asn -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    try {
                        FileUtils.writeStoreFile(dirAS.resolve(asn.substring(2) + ".txt"), new retrieveAutNumFull(asn).get());
                    } finally {
                        dbLimit.release();
                    }
                    dbLimit.acquire();
                    try {
                        // AS-BLOCK-WAR reads cache as {number}.txt (without "AS" prefix)
                        FileUtils.writeStoreFile(dirASNet.resolve(asn.substring(2) + ".txt"), new retrieveRouteOriginFull(asn).get());
                    } finally {
                        dbLimit.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    log.error("storeDetails: помилка запису для {}", asn, e);
                }
            }));

            // STORE/MNT/{mnt}.txt and STORE/MNT-SET-AS/{mnt}.txt
            allMntBy.forEach(mnt -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    try {
                        FileUtils.writeStoreFile(dirMNT.resolve(mnt + ".txt"), new retrieveMntnerFull(mnt).get());
                    } finally {
                        dbLimit.release();
                    }
                    dbLimit.acquire();
                    try {
                        FileUtils.writeStoreFile(dirMNTSETAS.resolve(mnt + ".txt"), new retrieveMntBy(mnt).get());
                    } finally {
                        dbLimit.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    log.error("storeDetails: помилка запису для мантейнера {}", mnt, e);
                }
            }));

            // STORE/AS-SET/{asset}.txt
            allAsSets.forEach(asSet -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    try {
                        FileUtils.writeStoreFile(dirASSet.resolve(asSet + ".txt"), new retrieveAsSet(asSet).get());
                    } finally {
                        dbLimit.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    log.error("storeDetails: помилка запису для AS-SET {}", asSet, e);
                }
            }));
        }

        log.info("storeDetails: завершено (AS={}, MNT={}, AS-SET={})",
                aggressorAsnResources.size(), allMntBy.size(), allAsSets.size());
    }
}
