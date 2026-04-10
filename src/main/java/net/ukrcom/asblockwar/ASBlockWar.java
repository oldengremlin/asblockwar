package net.ukrcom.asblockwar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

            Files.lines(Path.of(listFile)).forEach(str -> {
                LOGGER.info("AS" + str);
            });

        } catch (IOException ex) {
            LOGGER.error("Помилка: " + ex);
        }
    }
}
