package net.ukrcom.asblockwar;

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

    public static void main(String[] args) {
        try {
            Config config = new Config(args);
            String listFile = config.getListFile();
            String whoisLiteLocalURI = config.getWhoisLiteLocalURI();
            LOGGER.info("listFile: " + listFile);
            LOGGER.info("whoisLiteLocalURI: " + whoisLiteLocalURI);

            Map<String, String> results = new ConcurrentHashMap<>();

            // 1. Створюємо Executor на Virtual Threads (Java 21+)
            // Він буде створювати новий легкий потік на кожне завдання.
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

                // 2. Семафор — наш "контролер трафіку" для SQLite
                Semaphore dbLimit = new Semaphore(MAX_CONCURRENT_DB_QUERIES);

                try (Stream<String> lines = Files.lines(Path.of(listFile))) {
                    lines
                            .filter(line -> !line.matches("^\\s*[#;].*"))
                            .filter(line -> line.matches("^[1-9]\\d*$"))
                            .map(str -> "AS" + str)
                            .forEach(asNumber -> executor.submit(() -> {
                        try {
                            // Чекаємо дозволу на вхід до БД
                            dbLimit.acquire();
                            String result = stub(asNumber);
                            results.put(asNumber, result);
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

            LOGGER.info("Всі потоки завершили роботу. Результатів: " + results.size());
            results.entrySet().stream().parallel()
                    .sorted((e1, e2) -> {
                        // Витягуємо тільки цифри з рядка "AS12345"
                        Integer id1 = Integer.valueOf(e1.getKey().replaceAll("\\D", ""));
                        Integer id2 = Integer.valueOf(e2.getKey().replaceAll("\\D", ""));
                        return id1.compareTo(id2);
                    })
                    .forEachOrdered(entry -> {
                        LOGGER.info("Ключ: {}, Значення: {}", entry.getKey(), entry.getValue());
                    });

        } catch (IOException ex) {
            LOGGER.error("Помилка вводу-виводу: ", ex);
        } catch (Exception ex) {
            LOGGER.error("Непередбачена помилка: ", ex);
        }
    }

    public static String stub(String asNumber) {
        return "SQLite Data for " + asNumber + " [Virtual Thread: " + Thread.currentThread().toString() + "]";
    }

}
