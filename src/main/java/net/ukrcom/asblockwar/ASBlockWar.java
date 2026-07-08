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
package net.ukrcom.asblockwar;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.ukrcom.asblockwar.retrieveretrieve.retrieveOrganisation;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAsSet;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveMntBy;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveImportExportAsSets;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAsSetMembers;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAutNumFull;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveMntnerFull;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveBlackbgpPrefixes;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveRouteOriginFull;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveRouteOriginPrefixes;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveRouteOrigins;
import net.ukrcom.asblockwar.serviceStructures.Action;
import net.ukrcom.asblockwar.serviceStructures.ASN;

/**
 *
 * @author olden
 */
public class ASBlockWar {

    public static final Logger LOGGER = LoggerFactory.getLogger(ASBlockWar.class);
    public static final int MAX_CONCURRENT_DB_QUERIES = 20;
    public static Config config;
    public static String listFile;
    public static String listMntbyFile;

    // (?i) робить пошук регістронезалежним
    // MULTILINE (?m) дозволяє ^ та $ працювати з кожним рядком у багаторядковому значенні
    // $ зовні групи — всі альтернативи прив'язані до кінця рядка
    // .* після ключових слів — дозволяє довільний текст після збігу в тому ж рядку
    public static String AGGRESSOR_PATTERN = "(?im)^(org-name:.*(Kaspersky|Qrator).*|country:.*ru|address:.*(mos[ck]ow|russ?ia).*|abuse-mailbox:.*\\.ru)$";
    // Скомпільований патерн для використання з find() — без (?s), щоб .* не перетинав рядки
    public static final Pattern AGGRESSOR_COMPILED = Pattern.compile(AGGRESSOR_PATTERN);

    private static final String[] blockedAsSet = {
        "AS-MAILRU",
        "AS-VKONTAKTE",
        "AS-VK",
        "AS-YANDEX",
        "AS-MAILRU",
        "AS-M100",
        "AS-VK",
        "AS-VKONTAKTE"
    };

    // Створюємо мапу для вилучених елементів
    public static Map<String, ASN> resourcesForVerification = new ConcurrentHashMap<>();

    private record DiscoveryResult(Set<String> mntBy, Set<String> asSets) {}
    private record BlackbgpResult(Map<String, String> newEnemies, Set<String> effectivePrefixes) {}

    public static void main(String[] args) throws InterruptedException {
        try {
            config = new Config(args);

            listFile = config.getListFile();
            listMntbyFile = config.getListMntbyFile();

            LOGGER.info("listFile: " + listFile);
            LOGGER.info("whoisLiteLocalURI: " + listMntbyFile);

            Map<String, String> aggressorAsnResources = makeAggressorAsnResources();
            Map<String, String> aggressorMntbyResources = makeAggressorAssetAndMntbyResources();

            LOGGER.info("Всі потоки завершили роботу. Результатів: " + aggressorAsnResources.size());
            LOGGER.info("Починаємо фільтрацію...");

            aggressorAsnResources = filterAggressorAsnResources(
                    makeAggressorResources(
                            aggressorMntbyResources,
                            filterAggressorAsnResources(aggressorAsnResources)
                    )
            );

            DiscoveryResult discovery = discoverCooperatingAsnResources(aggressorAsnResources);
            storeMntByResources(discovery.mntBy());

            Set<String> allDiscoveredAsSets = new HashSet<>(discovery.asSets());
            Arrays.stream(blockedAsSet).forEach(allDiscoveredAsSets::add);
            storeListAsSet(allDiscoveredAsSets);

            final var finalResources = aggressorAsnResources;
            BlackbgpResult bgpOutcome;
            try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                var warTask = exec.submit(() -> { storeWarResources(finalResources); return null; });
                var bgpTask = exec.submit(() -> storeBlackbgpResources(finalResources));
                try { warTask.get(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException ioe) throw ioe;
                    throw new RuntimeException(e.getCause());
                }
                BlackbgpResult bgpResult = null;
                try { bgpResult = bgpTask.get(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException ioe) throw ioe;
                    throw new RuntimeException(e.getCause());
                }
                bgpOutcome = bgpResult != null ? bgpResult : new BlackbgpResult(Map.of(), Set.of());
            }

            Map<String, String> newEnemies = bgpOutcome.newEnemies();
            Set<String> effectivePrefixes = bgpOutcome.effectivePrefixes();

            if (!newEnemies.isEmpty()) {
                aggressorAsnResources.putAll(newEnemies);
                storeWarResources(aggressorAsnResources);
                LOGGER.info("Виявлено {} нових ворожих ASN під час перевірки видалення: {}",
                        newEnemies.size(), newEnemies.keySet());
            }

            // Записуємо остаточний список AS (з урахуванням нових ворогів)
            storeAggressorAsnResources(aggressorAsnResources);

            Set<String> allMntBy = readFileEntries(Path.of(listMntbyFile));
            Set<String> allAsSets = readFileEntries(Path.of(config.getListAssetFile()));

            try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                final var fa = aggressorAsnResources;
                final var fm = allMntBy;
                final var fc = effectivePrefixes;
                var detailsTask = exec.submit(() -> { storeDetails(fa, fm, allAsSets); return null; });
                var asListTask  = exec.submit(() -> { storeAsList(fa); return null; });
                var mntListTask = exec.submit(() -> { storeMaintainersList(fm); return null; });
                var netTask     = exec.submit(() -> { storeNetworkFiles(fc); return null; });
                for (var task : List.of(detailsTask, asListTask, mntListTask, netTask)) {
                    try { task.get(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    catch (ExecutionException e) {
                        if (e.getCause() instanceof IOException ioe) throw ioe;
                        throw new RuntimeException(e.getCause());
                    }
                }
            }

            report(aggressorAsnResources);

        } catch (IOException ex) {
            LOGGER.error("Помилка вводу-виводу: ", ex);
        } catch (Exception ex) {
            LOGGER.error("Непередбачена помилка: ", ex);
        }

        LOGGER.info("Готово!");
    }

    private static Map<String, String> makeAggressorAsnResources() {
        Map<String, String> aggressorAsnResources = new ConcurrentHashMap<>();

        // 1. Створюємо Executor на Virtual Threads (Java 21+)
        // Він буде створювати новий легкий потік на кожне завдання.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // 2. Семафор — наш "контролер трафіку" для SQLite
            Semaphore dbLimit = new Semaphore(MAX_CONCURRENT_DB_QUERIES);

            try (Stream<String> lines = Files.lines(Path.of(listFile)).parallel()) {
                lines
                        .filter(line -> !line.matches("^\\s*[#;].*"))
                        .filter(line -> line.matches("^[1-9]\\d*$"))
                        .map(str -> "AS" + str)
                        .forEach(asNumber -> executor.submit(() -> {
                    try {
                        // Чекаємо дозволу на вхід до БД
                        dbLimit.acquire();
                        String result = new retrieveOrganisation(asNumber).get();
                        aggressorAsnResources.put(asNumber, result);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        // Обов'язково звільняємо місце для наступного потоку
                        dbLimit.release();
                    }
                }));
            } catch (IOException e) {
                LOGGER.error("Помилка читання файлу", e);
            }

            // В try-with-resources executor.close() викличеться автоматично,
            // що дочекається завершення всіх віртуальних потоків.
        }

        return aggressorAsnResources;
    }

    private static Map<String, String> makeAggressorAssetAndMntbyResources() {
        Map<String, String> aggressorMntbyResources = new ConcurrentHashMap<>();

        // 1. Створюємо Executor на Virtual Threads (Java 21+)
        // Він буде створювати новий легкий потік на кожне завдання.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // 2. Семафор — наш "контролер трафіку" для SQLite
            Semaphore dbLimit = new Semaphore(MAX_CONCURRENT_DB_QUERIES);

            Stream.of(blockedAsSet).parallel()
                    .forEach(asSet -> executor.submit(() -> {
                try {
                    // Чекаємо дозволу на вхід до БД
                    dbLimit.acquire();
                    String result = new retrieveAsSet(asSet).get();
                    aggressorMntbyResources.put(asSet, result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    // Обов'язково звільняємо місце для наступного потоку
                    dbLimit.release();
                }
            }));
        }

        // 1. Створюємо Executor на Virtual Threads (Java 21+)
        // Він буде створювати новий легкий потік на кожне завдання.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // 2. Семафор — наш "контролер трафіку" для SQLite
            Semaphore dbLimit = new Semaphore(MAX_CONCURRENT_DB_QUERIES);

            try (Stream<String> lines = Files.lines(Path.of(listMntbyFile)).parallel()) {
                lines
                        .filter(line -> !line.matches("^\\s*[#;].*"))
                        .forEach(mntBy -> executor.submit(() -> {
                    try {
                        // Чекаємо дозволу на вхід до БД
                        dbLimit.acquire();
                        String result = new retrieveMntBy(mntBy).get();
                        aggressorMntbyResources.put(mntBy, result);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        // Обов'язково звільняємо місце для наступного потоку
                        dbLimit.release();
                    }
                }));
            } catch (IOException e) {
                LOGGER.error("Помилка читання файлу", e);
            }

            // В try-with-resources executor.close() викличеться автоматично,
            // що дочекається завершення всіх віртуальних потоків.
        }

        return aggressorMntbyResources;
    }

    private static Map<String, String> filterAggressorAsnResources(Map<String, String> aggressorAsnResources) {
        return aggressorAsnResources.entrySet().parallelStream()
                .filter(entry -> {
                    if (AGGRESSOR_COMPILED.matcher(entry.getValue()).find()) {
                        return true;
                    }
                    resourcesForVerification.put(
                            entry.getKey(),
                            new ASN(Action.remove, entry.getKey(), entry.getValue())
                    );
                    LOGGER.warn("Вилучено елемент: {}", entry.getKey());
                    return false;
                })
                .collect(Collectors.toConcurrentMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }

    private static Map<String, String> makeAggressorResources(Map<String, String> aggressorMntbyResources, Map<String, String> aggressorAsnResources) {

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(MAX_CONCURRENT_DB_QUERIES);

            aggressorMntbyResources.values().parallelStream()
                    .flatMap(block -> Arrays.stream(block.split("\n")))
                    .filter(line -> line.matches("^(members|aut-num):.*"))
                    .map(line -> line.split("\\s+", 2))
                    .filter(parts -> parts.length == 2)
                    .map(parts -> parts[1].trim())
                    .filter(asn -> asn.matches("^AS\\d+$"))
                    .distinct()
                    .forEach(asn -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    String block = new retrieveOrganisation(asn).get();
                    if (AGGRESSOR_COMPILED.matcher(block).find()) {
                        if (aggressorAsnResources.containsKey(asn)) {
                            if (!block.equals(aggressorAsnResources.get(asn))) {
                                resourcesForVerification.put(
                                        asn,
                                        new ASN(Action.modify, asn, block)
                                );
                                aggressorAsnResources.put(asn, block);
                                LOGGER.debug("Змінено ASN: {}", asn);
                            }
                        } else {
                            resourcesForVerification.put(
                                    asn,
                                    new ASN(Action.add, asn, block)
                            );
                            aggressorAsnResources.put(asn, block);
                            LOGGER.debug("Додано новий ASN: {}:\n{}\n", asn, block);
                        }
                    } else {
                        if (aggressorAsnResources.containsKey(asn)) {
                            resourcesForVerification.put(
                                    asn,
                                    new ASN(Action.remove, asn, aggressorAsnResources.get(asn))
                            );
                            aggressorAsnResources.remove(asn);
                            LOGGER.debug("Видалено ASN: {}", asn);
                        } else {
                            LOGGER.debug("Знайдено ASN: {}", asn);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    dbLimit.release();
                }
            }));
        }

        return aggressorAsnResources;
    }

    private static DiscoveryResult discoverCooperatingAsnResources(Map<String, String> aggressorAsnResources) {
        int depth = Math.max(config.getRecursiveAsset(), 0);
        Set<String> discoveredMntBy = ConcurrentHashMap.newKeySet();
        Set<String> discoveredAsSets = ConcurrentHashMap.newKeySet();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(MAX_CONCURRENT_DB_QUERIES);

            aggressorAsnResources.keySet().parallelStream()
                    .forEach(asn -> executor.submit(() -> {
                try {
                    Set<String> asSets;
                    dbLimit.acquire();
                    try {
                        asSets = new retrieveImportExportAsSets(asn).get();
                    } finally {
                        dbLimit.release();
                    }

                    for (String asSet : asSets) {
                        discoveredAsSets.add(asSet);

                        Set<String> memberAsns;
                        dbLimit.acquire();
                        try {
                            memberAsns = new retrieveAsSetMembers(asSet, depth).get();
                        } finally {
                            dbLimit.release();
                        }

                        for (String memberAsn : memberAsns) {
                            if (aggressorAsnResources.containsKey(memberAsn)) {
                                continue;
                            }
                            dbLimit.acquire();
                            try {
                                String block = new retrieveOrganisation(memberAsn).get();
                                if (AGGRESSOR_COMPILED.matcher(block).find()) {
                                    LOGGER.debug("discoverCooperating: {} -> {} -> {}", asn, asSet, memberAsn);
                                    resourcesForVerification.put(memberAsn, new ASN(Action.add, memberAsn, block));
                                    aggressorAsnResources.put(memberAsn, block);
                                    block.lines()
                                            .filter(l -> l.matches("(?i)^mnt-by:.*"))
                                            .map(l -> l.replaceFirst("(?i)^mnt-by:\\s*", "").trim())
                                            .filter(v -> !v.isEmpty())
                                            .forEach(v -> discoveredMntBy.add(v.toUpperCase()));
                                }
                            } finally {
                                dbLimit.release();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        return new DiscoveryResult(discoveredMntBy, discoveredAsSets);
    }

    // Службові мантейнери RIPE, які присутні в будь-якому записі — не є ознакою належності до агресора
    private static final Pattern SERVICE_MNT = Pattern.compile("^RIPE-.+", Pattern.CASE_INSENSITIVE);

    private static void storeMntByResources(Set<String> discovered) throws IOException {
        LOGGER.debug("storeMntByResources: знайдено мантейнерів (до фільтрації): {}", discovered);

        List<String> filtered = discovered.stream()
                .filter(m -> !SERVICE_MNT.matcher(m).matches())
                .sorted()
                .toList();

        LOGGER.debug("storeMntByResources: після фільтрації службових: {}", filtered);

        if (filtered.isEmpty()) {
            LOGGER.info("storeMntByResources: після фільтрації мантейнерів не залишилось");
            return;
        }

        Path path = Path.of(listMntbyFile);
        Path lockPath = path.resolveSibling(path.getFileName() + ".lock");

        try {
            try (FileChannel lc = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock fl = lc.lock()) {

                Set<String> existing = new HashSet<>();
                if (Files.exists(path)) {
                    Files.lines(path)
                            .map(String::trim)
                            .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith(";"))
                            .map(String::toUpperCase)
                            .forEach(existing::add);
                }

                List<String> newEntries = filtered.stream()
                        .filter(m -> !existing.contains(m.toUpperCase()))
                        .toList();

                newEntries.forEach(m -> LOGGER.debug("storeMntByResources: новий мантейнер: {}", m));

                if (newEntries.isEmpty()) {
                    LOGGER.info("storeMntByResources: нових мантейнерів не знайдено");
                    return;
                }

                Files.writeString(path,
                        String.join("\n", newEntries) + "\n",
                        StandardOpenOption.APPEND, StandardOpenOption.CREATE);

                LOGGER.info("storeMntByResources: додано {} нових мантейнерів до {}", newEntries.size(), listMntbyFile);
            }
        } finally {
            Files.deleteIfExists(lockPath);
        }
    }

    private static void storeListAsSet(Set<String> discovered) throws IOException {
        LOGGER.debug("storeListAsSet: знайдено AS-SET: {}", discovered);

        List<String> sorted = discovered.stream().sorted().toList();

        String listAssetFile = config.getListAssetFile();
        Path path = Path.of(listAssetFile);
        Path lockPath = path.resolveSibling(path.getFileName() + ".lock");

        try {
            try (FileChannel lc = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock fl = lc.lock()) {

                Set<String> existing = new HashSet<>();
                if (Files.exists(path)) {
                    Files.lines(path)
                            .map(String::trim)
                            .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith(";"))
                            .map(String::toUpperCase)
                            .forEach(existing::add);
                }

                List<String> newEntries = sorted.stream()
                        .filter(s -> !existing.contains(s.toUpperCase()))
                        .toList();

                newEntries.forEach(s -> LOGGER.debug("storeListAsSet: новий AS-SET: {}", s));

                if (newEntries.isEmpty()) {
                    LOGGER.info("storeListAsSet: нових AS-SET не знайдено");
                    return;
                }

                Files.writeString(path,
                        String.join("\n", newEntries) + "\n",
                        StandardOpenOption.APPEND, StandardOpenOption.CREATE);

                LOGGER.info("storeListAsSet: додано {} нових AS-SET до {}", newEntries.size(), listAssetFile);
            }
        } finally {
            Files.deleteIfExists(lockPath);
        }
    }

    private static void storeAggressorAsnResources(Map<String, String> aggressorAsnResources) throws IOException {
        Path source = Path.of(listFile);
        Path lockPath = source.resolveSibling(source.getFileName() + ".lock");

        try {
            try (FileChannel lc = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock fl = lc.lock()) {

                // Резервна копія: list.txt → list.2026-04-12T13:29:06+03:00.txt
                String filename = source.getFileName().toString();
                int dotIdx = filename.lastIndexOf('.');
                String timestamp = ZonedDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"));
                String backupFilename = dotIdx >= 0
                        ? filename.substring(0, dotIdx) + "." + timestamp + filename.substring(dotIdx)
                        : filename + "." + timestamp;
                Path backup = source.resolveSibling(backupFilename);
                Files.move(source, backup);
                LOGGER.info("Резервна копія: {}", backup);

                // Записуємо відсортований список (тільки числа, по одному на рядок)
                String content = aggressorAsnResources.keySet().stream()
                        .sorted(Comparator.comparingLong(asn -> Long.parseLong(asn.substring(2))))
                        .map(asn -> asn.substring(2))
                        .collect(Collectors.joining("\n", "", "\n"));
                Files.writeString(source, content);
                LOGGER.info("Збережено {} AS у {}", aggressorAsnResources.size(), source);
            }
        } finally {
            Files.deleteIfExists(lockPath);
        }
    }

    private static void storeWarResources(Map<String, String> aggressorAsnResources) throws IOException {
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

        Path path = Path.of(config.getWarFile());
        Files.writeString(path, war1 + "\n" + war2 + "\n");

        LOGGER.info("storeWarResources: WAR1+WAR2 записано у {} ({} ASN, {} → {} chars, -{} %)",
                config.getWarFile(), aggressorAsnResources.size(),
                rawLen, regex.length(), rawLen > 0 ? (rawLen - regex.length()) * 100 / rawLen : 0);
    }

    private static BlackbgpResult storeBlackbgpResources(Map<String, String> aggressorAsnResources) throws IOException {
        boolean ipv6 = config.isBlackbgpIpv6();

        // 1. Поточний стан таблиці blackbgp (через SSH)
        Set<String> currentPrefixes = new retrieveBlackbgpPrefixes(ipv6).get();
        LOGGER.info("storeBlackbgpResources: поточних маршрутів у blackbgp: {}", currentPrefixes.size());

        // 2. Цільовий набір prefixes з БД (тільки IPv4 якщо не передано -6)
        Set<String> targetPrefixes = ConcurrentHashMap.newKeySet();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(MAX_CONCURRENT_DB_QUERIES);
            aggressorAsnResources.keySet().forEach(asn -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    try {
                        new retrieveRouteOriginPrefixes(asn).get().stream()
                                .filter(p -> ipv6 || !p.contains(":"))
                                .forEach(targetPrefixes::add);
                    } finally {
                        dbLimit.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // 3. Диф: видалити = поточні - цільові; додати = цільові - поточні
        Set<String> toDelete = ConcurrentHashMap.newKeySet();
        toDelete.addAll(currentPrefixes);
        toDelete.removeAll(targetPrefixes);

        Set<String> toReplace = new HashSet<>(targetPrefixes);
        toReplace.removeAll(currentPrefixes);

        // 4. Перевірка маршрутів на видалення: чи не належать вони ворогу?
        Map<String, String> newEnemies = new ConcurrentHashMap<>();
        if (!toDelete.isEmpty()) {
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Semaphore dbLimit = new Semaphore(MAX_CONCURRENT_DB_QUERIES);
                toDelete.forEach(prefix -> executor.submit(() -> {
                    try {
                        List<String> origins;
                        dbLimit.acquire();
                        try {
                            origins = new retrieveRouteOrigins(prefix).get();
                        } finally {
                            dbLimit.release();
                        }

                        // Перевірка 1: вже відома ворожа AS?
                        for (String origin : origins) {
                            if (aggressorAsnResources.containsKey(origin)) {
                                LOGGER.warn("storeBlackbgpResources: {} належить ворожій {} — видалення скасовано",
                                        prefix, origin);
                                toDelete.remove(prefix);
                                return;
                            }
                        }

                        // Перевірка 2: нова ворожа AS?
                        for (String origin : origins) {
                            dbLimit.acquire();
                            String block;
                            try {
                                block = new retrieveOrganisation(origin).get();
                            } finally {
                                dbLimit.release();
                            }
                            if (AGGRESSOR_COMPILED.matcher(block).find()) {
                                LOGGER.warn("storeBlackbgpResources: {} — нова ворожа AS {} — додано до списку, видалення скасовано",
                                        prefix, origin);
                                newEnemies.put(origin, block);
                                toDelete.remove(prefix);
                                return;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
        }

        // 5. Записуємо лише зміни
        String content = Stream.concat(
                toDelete.stream().sorted(CIDR_ORDER).map(p -> blackbgpCmd("d", p)),
                toReplace.stream().sorted(CIDR_ORDER).map(p -> blackbgpCmd("r", p))
        ).collect(Collectors.joining("\n", "", "\n"));

        Path path = Path.of(config.getBlackbgpFile());
        Files.writeString(path, content);

        LOGGER.info("storeBlackbgpResources: {} delete + {} replace (current={}, target={}, newEnemies={}) → {}",
                toDelete.size(), toReplace.size(),
                currentPrefixes.size(), targetPrefixes.size(), newEnemies.size(), config.getBlackbgpFile());

        // Ефективний стан після застосування war.blackbgp.txt:
        // ті що скасували видалення лишаються, нові додаються
        Set<String> effectivePrefixes = new HashSet<>(currentPrefixes);
        effectivePrefixes.removeAll(toDelete);
        effectivePrefixes.addAll(toReplace);

        return new BlackbgpResult(newEnemies, effectivePrefixes);
    }

    private static String rpslField(String block, String key) {
        if (block == null || block.isEmpty()) return "";
        String prefix = key + ":";
        return block.lines()
                .filter(l -> l.startsWith(prefix))
                .map(l -> l.substring(prefix.length()).trim())
                .findFirst()
                .orElse("");
    }

    private static void storeAsList(Map<String, String> aggressorAsnResources) throws IOException {
        Path base = Path.of(config.getStoreDir());
        ensureStoreDir(base);

        String content = aggressorAsnResources.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> Long.parseLong(e.getKey().substring(2))))
                .map(e -> {
                    long asn = Long.parseLong(e.getKey().substring(2));
                    String block = e.getValue();
                    String orgName = rpslField(block, "org-name");
                    String address = rpslField(block, "address");
                    String info = orgName.isEmpty() ? ""
                            : address.isEmpty() ? orgName
                            : orgName + ", " + address;
                    return info.isEmpty()
                            ? Long.toString(asn)
                            : String.format("%-8d%s", asn, info);
                })
                .collect(Collectors.joining("\n", "", "\n"));

        writeStoreFile(base.resolve("AS.list"), content);
        LOGGER.info("storeAsList: записано {} AS до AS.list", aggressorAsnResources.size());
    }

    private static void storeMaintainersList(Set<String> allMntBy) throws IOException {
        Path base = Path.of(config.getStoreDir());
        ensureStoreDir(base);

        Map<String, String> infoByMnt = new ConcurrentHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(MAX_CONCURRENT_DB_QUERIES);
            allMntBy.forEach(mnt -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    try {
                        String block = new retrieveMntnerFull(mnt).get();
                        String role    = rpslField(block, "role");
                        String address = rpslField(block, "address");
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

        writeStoreFile(base.resolve("maintainers.list"), content);
        LOGGER.info("storeMaintainersList: записано {} мантейнерів до maintainers.list", allMntBy.size());
    }

    private static void storeNetworkFiles(Set<String> currentPrefixes) throws IOException {
        if (currentPrefixes.isEmpty()) {
            LOGGER.info("storeNetworkFiles: currentPrefixes пустий, пропускаємо");
            return;
        }
        Path base = Path.of(config.getStoreDir());
        Path dirNet = base.resolve("NET");
        ensureStoreDir(dirNet);

        // 1. Паралельно отримуємо origins для кожного prefix
        Map<String, List<String>> originsByPrefix = new ConcurrentHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(MAX_CONCURRENT_DB_QUERIES);
            currentPrefixes.forEach(prefix -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    try {
                        originsByPrefix.put(prefix, new retrieveRouteOrigins(prefix).get());
                    } finally {
                        dbLimit.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // 2. Записуємо STORE/networks.list (відсортовано за CIDR)
        String networksList = originsByPrefix.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(CIDR_ORDER))
                .map(e -> {
                    String asns = e.getValue().stream()
                            .map(String::toLowerCase)
                            .collect(Collectors.joining(", "));
                    return String.format("%-19s%s", e.getKey(), asns);
                })
                .collect(Collectors.joining("\n", "", "\n"));
        writeStoreFile(base.resolve("networks.list"), networksList);

        // 3. Паралельно записуємо STORE/NET/{addr.prefix}.txt
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            originsByPrefix.forEach((prefix, origins) -> executor.submit(() -> {
                try {
                    String filename = prefix.replace('/', '.') + ".txt";
                    String content = origins.stream()
                            .map(o -> String.format("%-16s%s", "origin:", o.toLowerCase()))
                            .collect(Collectors.joining("\n", "", "\n"));
                    writeStoreFile(dirNet.resolve(filename), content);
                } catch (IOException e) {
                    LOGGER.error("storeNetworkFiles: помилка запису для {}", prefix, e);
                }
            }));
        }

        LOGGER.info("storeNetworkFiles: {} мереж → networks.list + NET/", currentPrefixes.size());
    }

    private static String blackbgpCmd(String verb, String prefix) {
        boolean isIpv6 = prefix.contains(":");
        return "sudo ip " + (isIpv6 ? "-6 " : "") + "r " + verb + " bl " + prefix + " t blackbgp";
    }

    // CIDR comparator: IPv4 before IPv6; within each family — prefix length desc, then address asc
    private static final Comparator<String> CIDR_ORDER = (a, b) -> {
        boolean aV6 = a.contains(":");
        boolean bV6 = b.contains(":");
        if (aV6 != bV6) {
            return aV6 ? 1 : -1;
        }
        int la = cidrLen(a), lb = cidrLen(b);
        if (la != lb) {
            return lb - la; // descending: more specific first
        }
        if (!aV6) {
            return Long.compare(ipv4ToLong(cidrAddr(a)), ipv4ToLong(cidrAddr(b)));
        }
        return cidrAddr(a).compareTo(cidrAddr(b));
    };

    private static int cidrLen(String cidr) {
        int i = cidr.lastIndexOf('/');
        try { return i >= 0 ? Integer.parseInt(cidr.substring(i + 1)) : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    private static String cidrAddr(String cidr) {
        int i = cidr.lastIndexOf('/');
        return i >= 0 ? cidr.substring(0, i) : cidr;
    }

    private static long ipv4ToLong(String addr) {
        String[] p = addr.split("\\.", -1);
        if (p.length != 4) return 0;
        try {
            return (Long.parseLong(p[0]) << 24) | (Long.parseLong(p[1]) << 16)
                    | (Long.parseLong(p[2]) << 8) | Long.parseLong(p[3]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Set<String> readFileEntries(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Set.of();
        }
        try (Stream<String> lines = Files.lines(path)) {
            return lines
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith(";"))
                    .collect(Collectors.toSet());
        }
    }

    private static void ensureStoreDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-x---"));
        } catch (UnsupportedOperationException ignored) {}
    }

    private static void writeStoreFile(Path file, String content) throws IOException {
        if (content == null || content.isBlank()) {
            return;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void storeDetails(Map<String, String> aggressorAsnResources, Set<String> allMntBy, Set<String> allAsSets) throws IOException {
        Path base = Path.of(config.getStoreDir());
        Path dirAS = base.resolve("AS");
        Path dirMNT = base.resolve("MNT");
        Path dirMNTSETAS = base.resolve("MNT-SET-AS");
        Path dirASSet = base.resolve("AS-SET");
        Path dirASNet = base.resolve("AS-NET");

        ensureStoreDir(dirAS);
        ensureStoreDir(dirMNT);
        ensureStoreDir(dirMNTSETAS);
        ensureStoreDir(dirASSet);
        ensureStoreDir(dirASNet);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(MAX_CONCURRENT_DB_QUERIES);

            // STORE/AS/{asn}.txt and STORE/AS-NET/{asn}.txt
            aggressorAsnResources.keySet().forEach(asn -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    try {
                        writeStoreFile(dirAS.resolve(asn.substring(2) + ".txt"), new retrieveAutNumFull(asn).get());
                    } finally {
                        dbLimit.release();
                    }
                    dbLimit.acquire();
                    try {
                        // AS-BLOCK-WAR reads cache as {number}.txt (without "AS" prefix)
                        writeStoreFile(dirASNet.resolve(asn.substring(2) + ".txt"), new retrieveRouteOriginFull(asn).get());
                    } finally {
                        dbLimit.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    LOGGER.error("storeDetails: помилка запису для {}", asn, e);
                }
            }));

            // STORE/MNT/{mnt}.txt and STORE/MNT-SET-AS/{mnt}.txt
            allMntBy.forEach(mnt -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    try {
                        writeStoreFile(dirMNT.resolve(mnt + ".txt"), new retrieveMntnerFull(mnt).get());
                    } finally {
                        dbLimit.release();
                    }
                    dbLimit.acquire();
                    try {
                        writeStoreFile(dirMNTSETAS.resolve(mnt + ".txt"), new retrieveMntBy(mnt).get());
                    } finally {
                        dbLimit.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    LOGGER.error("storeDetails: помилка запису для мантейнера {}", mnt, e);
                }
            }));

            // STORE/AS-SET/{asset}.txt
            allAsSets.forEach(asSet -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    try {
                        writeStoreFile(dirASSet.resolve(asSet + ".txt"), new retrieveAsSet(asSet).get());
                    } finally {
                        dbLimit.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    LOGGER.error("storeDetails: помилка запису для AS-SET {}", asSet, e);
                }
            }));
        }

        LOGGER.info("storeDetails: завершено (AS={}, MNT={}, AS-SET={})",
                aggressorAsnResources.size(), allMntBy.size(), allAsSets.size());
    }

    private static void report(Map<String, String> aggressorAsnResources) {
        LOGGER.info("Роботу завершено. Всього ASN: {}", aggressorAsnResources.size());

        Comparator<ASN> byAsn = Comparator.comparingLong(a -> Long.parseLong(a.asn().substring(2)));

        List<ASN> removed = resourcesForVerification.values().stream()
                .filter(a -> a.action() == Action.remove)
                .sorted(byAsn)
                .toList();
        List<ASN> added = resourcesForVerification.values().stream()
                .filter(a -> a.action() == Action.add)
                .sorted(byAsn)
                .toList();
        List<ASN> modified = resourcesForVerification.values().stream()
                .filter(a -> a.action() == Action.modify)
                .sorted(byAsn)
                .toList();

        if (removed.size() > 0 || added.size() > 0 || modified.size() > 0) {
            // "AS4294967295" = 12 chars = "Модифіковано" = 12 chars
            final int COL = 12;
            final String FMT = "%-" + COL + "s │ %-" + COL + "s │ %-" + COL + "s";
            final String SEP
                    = "━".repeat(COL + 1)
                            .concat("┿")
                            .concat("━".repeat(COL + 2))
                            .concat("┿")
                            .concat("━".repeat(COL + 1));

            LOGGER.info("");
            LOGGER.info(String.format(FMT, "Вилучено", "Додано", "Модифіковано"));
            LOGGER.info(String.format(FMT, removed.size(), added.size(), modified.size()));
            LOGGER.info(SEP);

            int rows = Math.max(removed.size(), Math.max(added.size(), modified.size()));
            for (int i = 0; i < rows; i++) {
                String r = i < removed.size() ? removed.get(i).asn() : "";
                String a = i < added.size() ? added.get(i).asn() : "";
                String m = i < modified.size() ? modified.get(i).asn() : "";
                LOGGER.info(String.format(FMT, r, a, m));
            }
        }
    }

}
