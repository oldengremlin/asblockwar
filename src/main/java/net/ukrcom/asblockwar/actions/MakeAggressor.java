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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.ukrcom.asblockwar.ASBlockWar;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.UIProgressCallback;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAsSet;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveMntBy;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveOrganisation;
import net.ukrcom.asblockwar.serviceStructures.Action;
import net.ukrcom.asblockwar.serviceStructures.ASN;

/**
 * Методи для збору та розширення карти ворожих ASN із файлів та RPSL-блоків.
 * <p>
 * Містить логіку завантаження організаційних блоків, обробки AS-SET/MNT-BY записів
 * та крос-перевірки через мантейнерів.
 */
@Slf4j
public class MakeAggressor {

    private MakeAggressor() {
    }

    public static final String[] blockedAsSet = {
        "AS-MAILRU",
        "AS-VKONTAKTE",
        "AS-VK",
        "AS-YANDEX",
        "AS-M100"
    };

    /**
     * Читає список ASN з {@code list.txt} і завантажує RPSL-блок організації для кожного.
     * <p>
     * Пропускає рядки-коментарі (починаються з {@code #} або {@code ;}).
     * Паралельне виконання обмежується семафором {@link ASBlockWar#MAX_CONCURRENT_DB_QUERIES}.
     *
     * @return карта {@code ASN → RPSL-блок} для всіх ASN з файлу
     */
    public static Map<String, String> makeAggressorAsnResources() {
        Map<String, String> aggressorAsnResources = new ConcurrentHashMap<>();

        // 1. Створюємо Executor на Virtual Threads (Java 21+)
        // Він буде створювати новий легкий потік на кожне завдання.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // 2. Семафор — наш "контролер трафіку" для SQLite
            Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);

            try (Stream<String> lines = Files.lines(Path.of(ASBlockWar.listFile)).parallel()) {
                lines
                        .filter(line -> !line.matches("^\\s*[#;].*"))
                        .filter(line -> line.matches("^[1-9]\\d*$"))
                        .map(str -> "AS" + str)
                        .forEach(asNumber -> executor.submit(() -> {
                    UIProgressCallback cb = ASBlockWar.uiCallback;
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
                log.error("Помилка читання файлу", e);
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
    public static Map<String, String> makeAggressorAssetAndMntbyResources() {
        Map<String, String> aggressorMntbyResources = new ConcurrentHashMap<>();
        ASBlockWar.asSetResources.clear();
        ASBlockWar.mntnerResources.clear();

        // 1. Створюємо Executor на Virtual Threads (Java 21+)
        // Він буде створювати новий легкий потік на кожне завдання.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // 2. Семафор — наш "контролер трафіку" для SQLite
            Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);

            Set<String> fileAsSets;
            try {
                fileAsSets = FileUtils.readFileEntries(Path.of(ASBlockWar.config.getListAssetFile()));
            } catch (IOException e) {
                log.error("Помилка читання {}", ASBlockWar.config.getListAssetFile(), e);
                fileAsSets = Set.of();
            }

            Stream.concat(Arrays.stream(blockedAsSet), fileAsSets.stream())
                    .distinct()
                    .forEach(asSet -> executor.submit(() -> {
                UIProgressCallback cb = ASBlockWar.uiCallback;
                if (cb != null) {
                    cb.onAsSetProcessing(asSet);
                }
                try {
                    dbLimit.acquire();
                    String result = new retrieveAsSet(asSet).get();
                    if (!result.isBlank()) {
                        aggressorMntbyResources.put(asSet, result);
                        ASBlockWar.asSetResources.put(asSet, result);
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
            Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);

            try (Stream<String> lines = Files.lines(Path.of(ASBlockWar.listMntbyFile)).parallel()) {
                lines
                        .filter(line -> !line.matches("^\\s*[#;].*"))
                        .forEach(mntBy -> executor.submit(() -> {
                    UIProgressCallback cb = ASBlockWar.uiCallback;
                    if (cb != null) {
                        cb.onMntByProcessing(mntBy);
                    }
                    try {
                        // Чекаємо дозволу на вхід до БД
                        dbLimit.acquire();
                        String result = new retrieveMntBy(mntBy).get();
                        aggressorMntbyResources.put(mntBy, result);
                        ASBlockWar.mntnerResources.put(mntBy, result);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        // Обов'язково звільняємо місце для наступного потоку
                        dbLimit.release();
                    }
                }));
            } catch (IOException e) {
                log.error("Помилка читання файлу", e);
            }

            // В try-with-resources executor.close() викличеться автоматично,
            // що дочекається завершення всіх віртуальних потоків.
        }

        return aggressorMntbyResources;
    }

    /**
     * Розширює список ворожих ASN даними з RPSL-блоків MNT-BY та AS-SET.
     * <p>
     * Перебирає поля {@code members} та {@code aut-num} у RPSL-блоках мантейнерів.
     * Для кожного знайденого ASN перевіряє відповідність {@link ASBlockWar#AGGRESSOR_COMPILED}:
     * новий ворожий ASN додається, модифікований — оновлюється, той що більше не відповідає — видаляється.
     * Зміни реєструються у {@link ASBlockWar#resourcesForVerification}.
     *
     * @param aggressorMntbyResources карта {@code MNT-BY/AS-SET → RPSL-блок}
     * @param aggressorAsnResources карта {@code ASN → RPSL-блок}, яка модифікується на місці
     * @return та сама карта {@code aggressorAsnResources} після оновлення
     */
    public static Map<String, String> makeAggressorResources(Map<String, String> aggressorMntbyResources, Map<String, String> aggressorAsnResources) {
        Set<String> blocked = FilterAggressor.blockedCountries();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);

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
                    if (FilterAggressor.isAggressor(block, blocked)) {
                        if (aggressorAsnResources.containsKey(asn)) {
                            if (!block.equals(aggressorAsnResources.get(asn))) {
                                ASBlockWar.resourcesForVerification.put(
                                        asn,
                                        new ASN(Action.modify, asn, block)
                                );
                                aggressorAsnResources.put(asn, block);
                                log.debug("Змінено ASN: {}", asn);
                            }
                        } else {
                            ASBlockWar.resourcesForVerification.put(
                                    asn,
                                    new ASN(Action.add, asn, block)
                            );
                            aggressorAsnResources.put(asn, block);
                            log.debug("Додано новий ASN: {}:\n{}\n", asn, block);
                        }
                    } else {
                        if (aggressorAsnResources.containsKey(asn)) {
                            ASBlockWar.resourcesForVerification.put(
                                    asn,
                                    new ASN(Action.remove, asn, aggressorAsnResources.get(asn))
                            );
                            aggressorAsnResources.remove(asn);
                            log.debug("Видалено ASN: {}", asn);
                        } else {
                            log.debug("Знайдено ASN: {}", asn);
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

    private static final Pattern MEMBERS_PAT = Pattern.compile("(?m)^members:\\s*(.+)$");

    /**
     * Завантажує RPSL для AS-SET-записів у map, що мають порожній RPSL (один прохід).
     */
    public static void fetchMissingAsSetRpsl(Map<String, String> asSetMap) {
        Set<String> toFetch = asSetMap.entrySet().parallelStream()
                .filter(e -> e.getValue() == null || e.getValue().isBlank())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (toFetch.isEmpty()) return;
        log.info("Завантаження RPSL для {} AS-SET (прямий доступ)...", toFetch.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);
            toFetch.forEach(asSet -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    String rpsl = new retrieveAsSet(asSet).get();
                    String r = rpsl != null ? rpsl : "";
                    asSetMap.put(asSet, r);
                    if (!r.isBlank()) ASBlockWar.asSetResources.put(asSet, r);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    asSetMap.putIfAbsent(asSet, "");
                } finally {
                    dbLimit.release();
                }
            }));
        }
    }

    /**
     * BFS-розширення: знаходить AS-SET імена у полях members: існуючих записів,
     * яких ще немає в map, і завантажує їх RPSL. Повторює до стабілізації.
     */
    public static void expandAsSetMap(Map<String, String> asSetMap) {
        boolean found;
        do {
            Set<String> toFetch = ConcurrentHashMap.newKeySet();
            asSetMap.entrySet().parallelStream().forEach(entry -> {
                String rpsl = entry.getValue();
                if (rpsl == null || rpsl.isBlank()) return;
                Matcher m = MEMBERS_PAT.matcher(rpsl);
                while (m.find()) {
                    for (String token : m.group(1).split("[,\\s]+")) {
                        String t = token.trim().replaceAll(";$", "").toUpperCase();
                        if (!t.isEmpty()
                                && (t.startsWith("AS-") || t.startsWith("RS-") || t.startsWith("FLTR-"))
                                && !asSetMap.containsKey(t)) {
                            toFetch.add(t);
                        }
                    }
                }
            });
            found = !toFetch.isEmpty();
            if (found) {
                log.info("BFS AS-SET: завантаження {} нових записів...", toFetch.size());
                try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);
                    toFetch.forEach(asSet -> executor.submit(() -> {
                        try {
                            dbLimit.acquire();
                            String rpsl = new retrieveAsSet(asSet).get();
                            String r = rpsl != null ? rpsl : "";
                            asSetMap.put(asSet, r);
                            if (!r.isBlank()) ASBlockWar.asSetResources.put(asSet, r);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            asSetMap.put(asSet, "");
                        } finally {
                            dbLimit.release();
                        }
                    }));
                }
            }
        } while (found);
    }

    /**
     * Завантажує RPSL для ASN-членів AS-SET-ів, яких ще немає у графі (blocked/suspicious/cleared).
     * Повертає map asn → rpsl лише для ASN з непорожнім результатом.
     */
    public static Map<String, String> fetchMemberAsnRpsl(Map<String, String> asSetMap, Set<String> alreadyKnown) {
        Set<String> toFetch = ConcurrentHashMap.newKeySet();
        asSetMap.entrySet().parallelStream().forEach(entry -> {
            String rpsl = entry.getValue();
            if (rpsl == null || rpsl.isBlank()) return;
            Matcher m = MEMBERS_PAT.matcher(rpsl);
            while (m.find()) {
                for (String token : m.group(1).split("[,\\s]+")) {
                    String t = token.trim().replaceAll(";$", "").toUpperCase();
                    if (t.matches("AS\\d+") && !alreadyKnown.contains(t)) {
                        toFetch.add(t);
                    }
                }
            }
        });
        Map<String, String> result = new ConcurrentHashMap<>();
        if (toFetch.isEmpty()) return result;
        log.info("Завантаження RPSL для {} ASN-членів AS-SET...", toFetch.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);
            toFetch.forEach(asn -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    String rpsl = new retrieveOrganisation(asn).get();
                    if (rpsl != null && !rpsl.isBlank()) result.put(asn, rpsl);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    dbLimit.release();
                }
            }));
        }
        return result;
    }
}
