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
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.ukrcom.asblockwar.ui.ASBlockWarApp;

import net.ukrcom.asblockwar.retrieveretrieve.retrieveOrganisation;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAsSet;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveMntBy;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveImportExportAsSets;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAsSetMembers;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAutNumFull;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveMntnerFull;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAllRouteOrigins;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveBlackbgpPrefixes;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveRouteOriginFull;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveRouteOriginPrefixes;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveRouteOrigins;
import net.ukrcom.asblockwar.serviceStructures.Action;
import net.ukrcom.asblockwar.serviceStructures.ASN;

/**
 * Головна точка входу та ядро обробки ASBlockWar.
 * <p>
 * Реалізує повний конвеєр блокування ворожих автономних систем:
 * збір даних → фільтрація → виявлення суміжних AS → генерація конфігурацій Juniper і blackbgp.
 * Підтримує CLI-режим і GUI-режим (JavaFX).
 *
 * @author olden
 */
public class ASBlockWar {

    public static final Logger LOGGER = LoggerFactory.getLogger(ASBlockWar.class);
    public static final int MAX_CONCURRENT_DB_QUERIES = 20;
    public static Config config;
    public static String listFile;
    public static String listMntbyFile;

    /**
     * Зворотній виклик для оновлення GUI під час обробки.
     * У CLI-режимі {@link ASBlockWar#uiCallback} дорівнює {@code null}.
     */
    public interface UIProgressCallback {

        /** Викликається перед запитом RPSL-блоку для ASN.
         * @param asn номер автономної системи у форматі {@code "ASNnnn"}, для якої запитується RPSL-блок */
        void onAsnProcessing(String asn);

        /** Викликається перед запитом RPSL-блоку для AS-SET.
         * @param asSet назва набору AS-SET у форматі RPSL, наприклад {@code "AS-EXAMPLE"}, для якого запитується RPSL-блок */
        void onAsSetProcessing(String asSet);

        /** Викликається перед запитом RPSL-блоку для MNT-BY.
         * @param mntBy ідентифікатор мантейнера RPSL, наприклад {@code "EXAMPLE-MNT"}, для якого запитується RPSL-блок */
        void onMntByProcessing(String mntBy);
    }
    public static volatile UIProgressCallback uiCallback;

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
        "AS-M100"
    };

    // Створюємо мапу для вилучених елементів
    public static Map<String, ASN> resourcesForVerification = new ConcurrentHashMap<>();

    /** Результат виявлення суміжних ворожих AS: знайдені MNT-BY та AS-SET ідентифікатори. */
    private record DiscoveryResult(Set<String> mntBy, Set<String> asSets) {

    }

    /** Обчислені зміни для blackbgp та нові ворожі ASN, виявлені під час перевірки маршрутів. */
    private record BlackbgpChanges(
            Set<String> toDelete,
            Set<String> toReplace,
            Map<String, String> newEnemies,
            Set<String> effectivePrefixes) {

    }

    /**
     * Точка входу програми.
     * <p>
     * У GUI-режимі ({@code --gui}) запускає JavaFX-додаток; інакше виконує {@link #runProcessing()}.
     *
     * @param args аргументи командного рядка, передаються у {@link Config}
     * @throws InterruptedException якщо основний потік перервано
     */
    public static void main(String[] args) throws InterruptedException {
        try {
            config = new Config(args);

            if (config.isGui()) {
                Application.launch(ASBlockWarApp.class, args);
                return;
            }

            runProcessing();

        } catch (IOException ex) {
            LOGGER.error("Помилка вводу-виводу: ", ex);
        } catch (RuntimeException ex) {
            LOGGER.error("Непередбачена помилка: ", ex);
        }
    }

    /**
     * Головний конвеєр обробки ворожих ASN.
     * <p>
     * Послідовність кроків:
     * <ol>
     *   <li>Читання AS-списку та MNT-BY-списку з файлів</li>
     *   <li>Фільтрація за {@link #AGGRESSOR_COMPILED}</li>
     *   <li>Крос-перевірка через записи MNT-BY та AS-SET</li>
     *   <li>Виявлення суміжних ворожих AS через AS-SET import/export</li>
     *   <li>Збереження конфігурацій: Juniper WAR, blackbgp, list.txt</li>
     *   <li>Запис детальних файлів у STORE/</li>
     *   <li>Фінальний звіт</li>
     * </ol>
     *
     * @throws IOException якщо виникла помилка читання або запису файлів
     * @throws InterruptedException якщо потік перервано під час очікування
     */
    public static void runProcessing() throws IOException, InterruptedException {
        listFile = config.getListFile();
        listMntbyFile = config.getListMntbyFile();
        resourcesForVerification = new ConcurrentHashMap<>();

        LOGGER.info("listFile: " + listFile);
        LOGGER.info("listMntbyFile: " + listMntbyFile);

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

        Set<String> effectivePrefixes = storeResources(aggressorAsnResources);

        Set<String> allMntBy = readFileEntries(Path.of(listMntbyFile));
        Set<String> allAsSets = readFileEntries(Path.of(config.getListAssetFile()));

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            final var fa = aggressorAsnResources;
            final var fm = allMntBy;
            final var fc = effectivePrefixes;
            var detailsTask = exec.submit(() -> {
                storeDetails(fa, fm, allAsSets);
                return null;
            });
            var asListTask = exec.submit(() -> {
                storeAsList(fa);
                return null;
            });
            var mntListTask = exec.submit(() -> {
                storeMaintainersList(fm);
                return null;
            });
            var netTask = exec.submit(() -> {
                storeNetworkFiles(fc);
                return null;
            });
            for (var task : List.of(detailsTask, asListTask, mntListTask, netTask)) {
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

        report(aggressorAsnResources);
        LOGGER.info("Готово!");
    }

    /**
     * Читає список ASN з {@code list.txt} і завантажує RPSL-блок організації для кожного.
     * <p>
     * Пропускає рядки-коментарі (починаються з {@code #} або {@code ;}).
     * Паралельне виконання обмежується семафором {@link #MAX_CONCURRENT_DB_QUERIES}.
     *
     * @return карта {@code ASN → RPSL-блок} для всіх ASN з файлу
     */
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
                    UIProgressCallback cb = uiCallback;
                    if (cb != null) {
                        cb.onAsnProcessing(asNumber);
                    }
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

    /**
     * Завантажує RPSL-блоки для AS-SET та MNT-BY записів.
     * <p>
     * Об'єднує захардкоджений список {@link #blockedAsSet} з переліком зі збереженого файлу AS-SET,
     * а також читає файл MNT-BY. Для кожного запису отримує повний RPSL-блок із локальної БД.
     *
     * @return суміщена карта {@code ідентифікатор → RPSL-блок} для AS-SET та MNT-BY
     */
    private static Map<String, String> makeAggressorAssetAndMntbyResources() {
        Map<String, String> aggressorMntbyResources = new ConcurrentHashMap<>();

        // 1. Створюємо Executor на Virtual Threads (Java 21+)
        // Він буде створювати новий легкий потік на кожне завдання.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // 2. Семафор — наш "контролер трафіку" для SQLite
            Semaphore dbLimit = new Semaphore(MAX_CONCURRENT_DB_QUERIES);

            Set<String> fileAsSets;
            try {
                fileAsSets = readFileEntries(Path.of(config.getListAssetFile()));
            } catch (IOException e) {
                LOGGER.error("Помилка читання {}", config.getListAssetFile(), e);
                fileAsSets = Set.of();
            }

            Stream.concat(Arrays.stream(blockedAsSet), fileAsSets.stream())
                    .distinct()
                    .forEach(asSet -> executor.submit(() -> {
                UIProgressCallback cb = uiCallback;
                if (cb != null) {
                    cb.onAsSetProcessing(asSet);
                }
                try {
                    dbLimit.acquire();
                    String result = new retrieveAsSet(asSet).get();
                    if (!result.isBlank()) {
                        aggressorMntbyResources.put(asSet, result);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
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
                    UIProgressCallback cb = uiCallback;
                    if (cb != null) {
                        cb.onMntByProcessing(mntBy);
                    }
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

    /**
     * Фільтрує карту ASN, залишаючи лише ті, чий RPSL-блок відповідає {@link #AGGRESSOR_COMPILED}.
     * <p>
     * Відфільтровані ASN реєструються у {@link #resourcesForVerification} з дією {@link Action#remove}.
     *
     * @param aggressorAsnResources вхідна карта {@code ASN → RPSL-блок}
     * @return нова карта, що містить тільки підтверджені ворожі ASN
     */
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

    /**
     * Розширює список ворожих ASN даними з RPSL-блоків MNT-BY та AS-SET.
     * <p>
     * Перебирає поля {@code members} та {@code aut-num} у RPSL-блоках мантейнерів.
     * Для кожного знайденого ASN перевіряє відповідність {@link #AGGRESSOR_COMPILED}:
     * новий ворожий ASN додається, модифікований — оновлюється, той що більше не відповідає — видаляється.
     * Зміни реєструються у {@link #resourcesForVerification}.
     *
     * @param aggressorMntbyResources карта {@code MNT-BY/AS-SET → RPSL-блок}
     * @param aggressorAsnResources карта {@code ASN → RPSL-блок}, яка модифікується на місці
     * @return та сама карта {@code aggressorAsnResources} після оновлення
     */
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

    /**
     * Виявляє ворожі ASN через мережі import/export AS-SET вже відомих агресорів.
     * <p>
     * Для кожного вже відомого ворожого ASN знаходить AS-SET, що імпортуються/експортуються,
     * витягує їхніх членів (з глибиною рекурсії {@code config.recursiveAsset}),
     * і перевіряє кожного члена на відповідність {@link #AGGRESSOR_COMPILED}.
     * Нові ворожі ASN додаються до {@code aggressorAsnResources} та {@link #resourcesForVerification}.
     *
     * @param aggressorAsnResources карта {@code ASN → RPSL-блок}; модифікується на місці
     * @return результат з виявленими MNT-BY та AS-SET для подальшого збереження
     */
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

    /**
     * Зберігає виявлені MNT-BY ідентифікатори у файл {@code list_mntby}.
     * <p>
     * Об'єднує наявні записи з новими, фільтрує службові мантейнери RIPE
     * (шаблон {@code RIPE-.*}), сортує і записує атомарно.
     *
     * @param discovered набір щойно виявлених MNT-BY ідентифікаторів
     * @throws IOException якщо виникла помилка читання або запису файлу
     */
    private static void storeMntByResources(Set<String> discovered) throws IOException {
        LOGGER.debug("storeMntByResources: знайдено мантейнерів (до фільтрації): {}", discovered);

        Path path = Path.of(listMntbyFile);
        Set<String> existing = readFileEntries(path);

        List<String> merged = Stream.concat(existing.stream(), discovered.stream())
                .map(String::toUpperCase)
                .filter(m -> !SERVICE_MNT.matcher(m).matches())
                .distinct()
                .sorted()
                .toList();

        if (merged.isEmpty()) {
            LOGGER.info("storeMntByResources: список мантейнерів порожній");
            return;
        }

        writeStoreFile(path, String.join("\n", merged) + "\n");
        LOGGER.info("storeMntByResources: записано {} мантейнерів до {}", merged.size(), listMntbyFile);
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
    private static void storeListAsSet(Set<String> discovered) throws IOException {
        LOGGER.debug("storeListAsSet: знайдено AS-SET: {}", discovered);

        String listAssetFile = config.getListAssetFile();
        Path path = Path.of(listAssetFile);
        Set<String> existing = readFileEntries(path);

        List<String> merged = Stream.concat(existing.stream(), discovered.stream())
                .map(String::toUpperCase)
                .map(s -> s.endsWith(";") ? s.substring(0, s.length() - 1) : s)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .toList();

        if (merged.isEmpty()) {
            LOGGER.info("storeListAsSet: список AS-SET порожній");
            return;
        }

        writeStoreFile(path, String.join("\n", merged) + "\n");
        LOGGER.info("storeListAsSet: записано {} AS-SET до {}", merged.size(), listAssetFile);
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
                Path tmp = source.resolveSibling(source.getFileName() + ".tmp");
                Files.writeString(tmp, content);
                try {
                    Files.move(tmp, source, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, source, StandardCopyOption.REPLACE_EXISTING);
                }
                LOGGER.info("Збережено {} AS у {}", aggressorAsnResources.size(), source);
            }
        } finally {
            Files.deleteIfExists(lockPath);
        }
    }

    /**
     * Оркеструє фінальне збереження всіх трьох ключових ресурсів.
     * <p>
     * Спочатку аналізує зміни у blackbgp ({@link #discoverBlackbgpChanges}),
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
    private static Set<String> storeResources(Map<String, String> aggressorAsnResources) throws IOException {
        // аналіз: виявляємо зміни у blackbgp і можливі нові ворожі AS
        BlackbgpChanges changes = discoverBlackbgpChanges(aggressorAsnResources);

        Map<String, String> newEnemies = changes.newEnemies();
        if (!newEnemies.isEmpty()) {
            aggressorAsnResources.putAll(newEnemies);
            LOGGER.info("Виявлено {} нових ворожих ASN під час перевірки видалення: {}",
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
        writeStoreFile(path, war1 + "\n" + war2 + "\n");

        LOGGER.info("storeWarResources: WAR1+WAR2 записано у {} ({} ASN, {} → {} chars, -{} %)",
                config.getWarFile(), aggressorAsnResources.size(),
                rawLen, regex.length(), rawLen > 0 ? (rawLen - regex.length()) * 100 / rawLen : 0);
    }

    /**
     * Обчислює необхідні зміни для таблиці маршрутизації blackbgp.
     * <p>
     * Алгоритм:
     * <ol>
     *   <li>Читає поточний стан blackbgp через SSH</li>
     *   <li>Формує цільовий набір prefix'ів з локальної БД для всіх ворожих ASN</li>
     *   <li>Обчислює {@code toDelete} = поточні − цільові; {@code toReplace} = цільові − поточні</li>
     *   <li>Для кожного prefix у {@code toDelete} перевіряє origin-AS:
     *       якщо належить вже відомій або щойно виявленій ворожій AS — скасовує видалення</li>
     * </ol>
     * Не виконує жодного запису на диск.
     *
     * @param aggressorAsnResources карта {@code ASN → RPSL-блок}
     * @return обчислені зміни разом з набором нових ворожих ASN
     */
    private static BlackbgpChanges discoverBlackbgpChanges(Map<String, String> aggressorAsnResources) {
        boolean ipv6 = config.isBlackbgpIpv6();

        // 1. Поточний стан таблиці blackbgp (через SSH)
        Set<String> currentPrefixes = new retrieveBlackbgpPrefixes(ipv6).get();
        LOGGER.info("discoverBlackbgpChanges: поточних маршрутів у blackbgp: {}", currentPrefixes.size());

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
                                LOGGER.warn("discoverBlackbgpChanges: {} належить ворожій {} — видалення скасовано",
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
                                LOGGER.warn("discoverBlackbgpChanges: {} — нова ворожа AS {} — додано до списку, видалення скасовано",
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

        // Ефективний стан після застосування war.blackbgp.txt:
        // ті що скасували видалення лишаються, нові додаються
        Set<String> effectivePrefixes = new HashSet<>(currentPrefixes);
        effectivePrefixes.removeAll(toDelete);
        effectivePrefixes.addAll(toReplace);

        LOGGER.info("discoverBlackbgpChanges: {} delete + {} replace (current={}, target={}, newEnemies={})",
                toDelete.size(), toReplace.size(),
                currentPrefixes.size(), targetPrefixes.size(), newEnemies.size());

        return new BlackbgpChanges(toDelete, toReplace, newEnemies, effectivePrefixes);
    }

    /**
     * Записує команди blackbgp у файл на основі заздалегідь обчислених змін.
     * <p>
     * Кожен рядок — це команда {@code ip r d bl PREFIX t blackbgp} (видалення)
     * або {@code ip r r bl PREFIX t blackbgp} (заміна), відсортовані за {@link #CIDR_ORDER}.
     *
     * @param changes обчислені зміни blackbgp з {@link #discoverBlackbgpChanges}
     * @throws IOException якщо виникла помилка запису файлу
     */
    private static void storeBlackbgpResources(BlackbgpChanges changes) throws IOException {
        String content = Stream.concat(
                changes.toDelete().stream().sorted(CIDR_ORDER).map(p -> blackbgpCmd("d", p)),
                changes.toReplace().stream().sorted(CIDR_ORDER).map(p -> blackbgpCmd("r", p))
        ).collect(Collectors.joining("\n", "", "\n"));

        Path path = Path.of(config.getBlackbgpFile());
        writeStoreFile(path, content);

        LOGGER.info("storeBlackbgpResources: {} delete + {} replace → {}",
                changes.toDelete().size(), changes.toReplace().size(), config.getBlackbgpFile());
    }

    /**
     * Витягує перше значення вказаного поля RPSL з текстового блоку.
     * <p>
     * Наприклад, для {@code key = "org-name"} і блоку {@code "org-name: Example Corp\n..."}
     * поверне {@code "Example Corp"}.
     *
     * @param block текстовий RPSL-блок
     * @param key назва поля (без двокрапки)
     * @return перше значення поля або порожній рядок, якщо поле відсутнє
     */
    private static String rpslField(String block, String key) {
        if (block == null || block.isEmpty()) {
            return "";
        }
        String prefix = key + ":";
        return block.lines()
                .filter(l -> l.startsWith(prefix))
                .map(l -> l.substring(prefix.length()).trim())
                .findFirst()
                .orElse("");
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

    /**
     * Записує перелік мантейнерів з описом у {@code STORE/maintainers.list}.
     * <p>
     * Для кожного MNT-BY ідентифікатора завантажує повний RPSL-блок мантейнера і витягує
     * поля {@code role} та {@code address}. Результат відсортовано за іменем мантейнера.
     *
     * @param allMntBy повний набір MNT-BY ідентифікаторів
     * @throws IOException якщо виникла помилка запису файлу
     */
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
                        String role = rpslField(block, "role");
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
    private static void storeNetworkFiles(Set<String> effectivePrefixes) throws IOException {
        if (effectivePrefixes.isEmpty()) {
            LOGGER.info("storeNetworkFiles: effectivePrefixes пустий, пропускаємо");
            return;
        }
        Path base = Path.of(config.getStoreDir());
        Path dirNet = base.resolve("NET");
        ensureStoreDir(dirNet);

        // 1. Один bulk-запит замість N індивідуальних з'єднань
        LOGGER.info("storeNetworkFiles: читаємо origins з БД (bulk)...");
        Map<String, List<String>> allOrigins = new retrieveAllRouteOrigins().get();
        LOGGER.info("storeNetworkFiles: отримано origins для {} маршрутів з БД", allOrigins.size());

        // 2. Відбираємо тільки ті префікси, що є в effectivePrefixes
        List<Map.Entry<String, List<String>>> sorted = effectivePrefixes.stream()
                .map(p -> Map.entry(p, allOrigins.getOrDefault(p, List.of())))
                .sorted(Map.Entry.comparingByKey(NETWORK_ADDR_ORDER))
                .toList();

        // 3. Записуємо STORE/networks.list
        String networksList = sorted.stream()
                .map(e -> {
                    String asns = e.getValue().stream()
                            .map(String::toLowerCase)
                            .collect(Collectors.joining(", "));
                    return String.format("%-19s%s", e.getKey(), asns);
                })
                .collect(Collectors.joining("\n", "", "\n"));
        writeStoreFile(base.resolve("networks.list"), networksList);
        LOGGER.info("storeNetworkFiles: networks.list записано ({} рядків)", sorted.size());

        // 4. Записуємо STORE/NET/{addr.prefix}.txt
        int count = 0;
        for (Map.Entry<String, List<String>> e : sorted) {
            String filename = e.getKey().replace('/', '.') + ".txt";
            String content = e.getValue().stream()
                    .map(o -> String.format("%-16s%s", "origin:", o.toLowerCase()))
                    .collect(Collectors.joining("\n", "", "\n"));
            writeStoreFile(dirNet.resolve(filename), content);
            if (++count % 10000 == 0) {
                LOGGER.info("storeNetworkFiles: NET/ {}/{}", count, sorted.size());
            }
        }

        LOGGER.info("storeNetworkFiles: завершено — {} файлів у NET/", count);
    }

    /**
     * Формує рядок команди для управління маршрутом у таблиці blackbgp.
     * <p>
     * Для IPv6-prefix додає прапор {@code -6}.
     * Синтаксис: {@code sudo ip [-6] r VERB bl PREFIX t blackbgp}.
     *
     * @param verb дієслово команди: {@code "r"} (replace/add) або {@code "d"} (delete)
     * @param prefix мережевий prefix у нотації CIDR
     * @return готовий рядок команди
     */
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

    /**
     * Повертає довжину маски з CIDR-нотації.
     *
     * @param cidr рядок у нотації CIDR, наприклад {@code "192.168.0.0/24"}
     * @return числова довжина маски або {@code 0}, якщо рядок не містить {@code /}
     */
    private static int cidrLen(String cidr) {
        int i = cidr.lastIndexOf('/');
        try {
            return i >= 0 ? Integer.parseInt(cidr.substring(i + 1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Повертає адресну частину з CIDR-нотації (без маски).
     *
     * @param cidr рядок у нотації CIDR, наприклад {@code "192.168.0.0/24"}
     * @return адреса, наприклад {@code "192.168.0.0"}
     */
    private static String cidrAddr(String cidr) {
        int i = cidr.lastIndexOf('/');
        return i >= 0 ? cidr.substring(0, i) : cidr;
    }

    /**
     * Перетворює IPv4-адресу у 32-розрядне ціле для числового порівняння.
     *
     * @param addr IPv4-адреса у крапково-десятковому форматі
     * @return числове представлення адреси або {@code 0} при помилці парсингу
     */
    private static long ipv4ToLong(String addr) {
        String[] p = addr.split("\\.", -1);
        if (p.length != 4) {
            return 0;
        }
        try {
            return (Long.parseLong(p[0]) << 24) | (Long.parseLong(p[1]) << 16)
                    | (Long.parseLong(p[2]) << 8) | Long.parseLong(p[3]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // networks.list order: IPv4 before IPv6; within each family — address asc, then mask asc (1..32)
    private static final Comparator<String> NETWORK_ADDR_ORDER = (a, b) -> {
        boolean aV6 = a.contains(":");
        boolean bV6 = b.contains(":");
        if (aV6 != bV6) {
            return aV6 ? 1 : -1;
        }
        int addrCmp = aV6
                      ? cidrAddr(a).compareTo(cidrAddr(b))
                      : Long.compare(ipv4ToLong(cidrAddr(a)), ipv4ToLong(cidrAddr(b)));
        if (addrCmp != 0) {
            return addrCmp;
        }
        return Integer.compare(cidrLen(a), cidrLen(b));
    };

    /**
     * Читає непорожні рядки файлу, пропускаючи коментарі ({@code #}, {@code ;}).
     *
     * @param path шлях до файлу
     * @return {@link Set} рядків або порожня множина, якщо файл не існує
     * @throws IOException якщо виникла помилка читання файлу
     */
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

    /**
     * Створює директорію (включно з батьківськими) та встановлює права {@code rwxr-x---}.
     * <p>
     * Якщо файлова система не підтримує POSIX-права, виняток тихо ігнорується.
     *
     * @param dir шлях до директорії
     * @throws IOException якщо директорію не вдалося створити
     */
    private static void ensureStoreDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-x---"));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    /**
     * Атомарно записує вміст у файл через тимчасовий файл і файлове блокування.
     * <p>
     * Якщо {@code content} порожній або {@code null} — нічого не робить.
     * Використовує {@link FileLock} для захисту від паралельного запису та
     * {@link StandardCopyOption#ATOMIC_MOVE} для безпечної заміни файлу.
     *
     * @param file шлях до цільового файлу
     * @param content вміст для запису
     * @throws IOException якщо виникла помилка запису або переміщення файлу
     */
    private static void writeStoreFile(Path file, String content) throws IOException {
        if (content == null || content.isBlank()) {
            return;
        }
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (FileChannel lc = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock fl = lc.lock()) {
                Files.writeString(tmp, content);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            Files.deleteIfExists(lockPath);
            Files.deleteIfExists(tmp);
        }
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

    /**
     * Виводить фінальний звіт про зміни поточного запуску.
     * <p>
     * Формує і логує таблицю у три колонки: вилучені / додані / модифіковані ASN
     * з {@link #resourcesForVerification}, відсортовані за числовим значенням ASN.
     *
     * @param aggressorAsnResources фінальна карта {@code ASN → RPSL-блок}
     */
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
