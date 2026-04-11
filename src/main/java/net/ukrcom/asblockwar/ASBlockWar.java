package net.ukrcom.asblockwar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author olden
 */
public class ASBlockWar {

    public static final Logger LOGGER = LoggerFactory.getLogger(ASBlockWar.class);
    public static final int THEREAD_POOLS = 10;

    public static void main(String[] args) {
        try {
            Config config = new Config(args);
            String listFile = config.getListFile();
            String whoisLiteLocalURI = config.getWhoisLiteLocalURI();
            LOGGER.info("listFile: " + listFile);
            LOGGER.info("whoisLiteLocalURI: " + whoisLiteLocalURI);

            ExecutorService executor = Executors.newFixedThreadPool(THEREAD_POOLS);

            Map<String, String> results = new ConcurrentHashMap<>();

            try (Stream<String> lines = Files.lines(Path.of(listFile))) {
                lines
                        .filter(line -> !line.matches("^\\s*[#;].*")) // Пропускаємо коментарі
                        .filter(line -> line.matches("^[1-9]\\d*$")) // Залишаємо тільки числа (номер AS)
                        .map(str -> "AS" + str)
                        .forEach(asNumber -> {
                            // Кожен номер AS відправляємо в пул потоків
                            executor.submit(() -> {
                                String result = stub(asNumber); // Виконуємо роботу
                                results.put(asNumber, result); // Зберігаємо в безпечну мапу
                            });
                        });
            } catch (IOException e) {
                LOGGER.error("Помилка читання файлу", e);
            }

            executor.shutdown();
            if (executor.awaitTermination(1, TimeUnit.HOURS)) {
                LOGGER.info("Всі потоки завершили роботу. Результатів: " + results.size());
                results.entrySet().stream()
                        .sorted((e1, e2) -> {
                            // Витягуємо тільки цифри з рядка "AS12345"
                            Integer id1 = Integer.valueOf(e1.getKey().replaceAll("\\D", ""));
                            Integer id2 = Integer.valueOf(e2.getKey().replaceAll("\\D", ""));
                            return id1.compareTo(id2);
                        })
                        .forEach(entry -> {
                            LOGGER.info("Ключ: {}, Значення: {}", entry.getKey(), entry.getValue());
                        });
            }

        } catch (IOException ex) {
            LOGGER.error("Помилка: " + ex);
        } catch (InterruptedException ex) {
            LOGGER.error("Помилка awaitTermination: " + ex);
        }
    }

    public static String stub(String asNumber) {
        return "Data for " + asNumber + ",потік " + Thread.currentThread().getName();
    }

}
