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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.ukrcom.asblockwar.retrieveretrieve.retrieveOrganisation;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAsSet;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveMntBy;
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
    public static String AGGRESSOR_PATTERN = "(?im)^(org-name:.*(Kaspersky|Qrator)|country:.*ru|address:.*(moscow|russia)|abuse-mailbox:.*\\.ru$)";

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
            storeAggressorAsnResources(aggressorAsnResources);

            LOGGER.info("Фільтрацію завершено. Залишилось: {}, Вилучено: {}", aggressorAsnResources.size(), resourcesForVerification.size());

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
                    if (entry.getValue().matches("(?s).*" + AGGRESSOR_PATTERN + ".*")) {
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
                    if (aggressorAsnResources.containsKey(asn)) {
                        if (!block.equals(aggressorAsnResources.get(asn))) {
                            resourcesForVerification.put(
                                    asn,
                                    new ASN(Action.modify, asn, block)
                            );
                            LOGGER.debug("Змінено ASN: {}", asn);
                        }
                    } else {
                        resourcesForVerification.put(
                                asn,
                                new ASN(Action.add, asn, block)
                        );
                        aggressorAsnResources.put(asn, block);
                        LOGGER.debug("Знайдено новий ASN: {}", asn);
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

    private static void storeAggressorAsnResources(Map<String, String> aggressorAsnResources) {

    }
}
