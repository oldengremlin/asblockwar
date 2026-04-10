package net.ukrcom.asblockwar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author olden
 */
public class ASBlockWar {

    public static final Logger LOGGER = LoggerFactory.getLogger(ASBlockWar.class);

    public static void main(String[] args) {
        try {
            Config config = new Config(args);
            String listFile = config.getListFile();
            String whoisLiteLocalURI = config.getWhoisLiteLocalURI();
            LOGGER.info("listFile: " + listFile);
            LOGGER.info("whoisLiteLocalURI: " + whoisLiteLocalURI);

            try (Stream<String> lines = Files.lines(Path.of(listFile))) {
                lines
                        .map(str -> "AS" + str)
                        .forEach(str -> {
                            LOGGER.info(str);
                        });
            } catch (IOException e) {
                LOGGER.error("Помилка читання файлу", e);
            }

        } catch (IOException ex) {
            LOGGER.error("Помилка: " + ex);
        }
    }
}
