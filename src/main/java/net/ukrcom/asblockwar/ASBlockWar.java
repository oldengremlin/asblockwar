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

import net.ukrcom.asblockwar.retrieveretrieve.retrieveOrganisation;
import net.ukrcom.asblockwar.serviceStructures.Action;
import net.ukrcom.asblockwar.serviceStructures.ASN;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public static void main(String[] args) throws InterruptedException {
        try {
            config = new Config(args);

            listFile = config.getListFile();
            listMntbyFile = config.getListMntbyFile();

            LOGGER.info("listFile: " + listFile);
            LOGGER.info("whoisLiteLocalURI: " + listMntbyFile);

            Map<String, String> aggressorAsnResources = makeAggressorAsnResources();
            Map<String, String> aggressorMntbyResources = makeAggressorMntbyResources();

            // Створюємо мапу для вилучених елементів
            Map<String, ASN> resourcesForVerification = new ConcurrentHashMap<>();

            LOGGER.info("Всі потоки завершили роботу. Результатів: " + aggressorAsnResources.size());
            LOGGER.info("Починаємо фільтрацію...");

            aggressorAsnResources.entrySet().parallelStream()
                    .peek(entry -> {
                        // Якщо значення НЕ відповідає паттерну — додаємо в мапу вилучених
                        if (!entry.getValue().matches("(?s).*" + AGGRESSOR_PATTERN + ".*")) {
                            // Тут нюанс: matches має перевіряти весь текст, 
                            // тому додаємо .* навколо для пошуку всередині блоку
                            resourcesForVerification.put(
                                    entry.getKey(),
                                    new ASN(Action.remove, entry.getKey(), entry.getValue())
                            );
                            LOGGER.warn("Вилучено елемент: {}", entry.getKey());
                        }
                    })
                    .filter(entry -> entry.getValue().matches("(?s).*" + AGGRESSOR_PATTERN + ".*"))
                    .sorted((e1, e2) -> {
                        // Витягуємо тільки цифри з рядка "AS12345"
                        Integer id1 = Integer.valueOf(e1.getKey().replaceAll("\\D", ""));
                        Integer id2 = Integer.valueOf(e2.getKey().replaceAll("\\D", ""));
                        return id1.compareTo(id2);
                    })
                    .forEachOrdered(entry -> {
                        LOGGER.debug("Ключ: {}, Значення: {}", entry.getKey(), entry.getValue());
                    });

            LOGGER.info("Фільтрацію завершено. Залишилось: {}, Вилучено: {}", aggressorAsnResources.size() - resourcesForVerification.size(), resourcesForVerification.size());

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
                        LOGGER.debug("Asn: " + asNumber);
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

    private static Map<String, String> makeAggressorMntbyResources() {
        Map<String, String> aggressorMntbyResources = new ConcurrentHashMap<>();

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
                        LOGGER.debug("Mntby: " + mntBy);
//                        String result = new retrieveMntby(mntBy).get();
//                        aggressorMntbyResources.put(mntBy, result);
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

}
