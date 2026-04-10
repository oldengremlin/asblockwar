/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.asblockwar;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import static net.ukrcom.asblockwar.ASBlockWar.LOGGER;

/**
 *
 * @author olden
 */
public class Config {

    private final String[] args;
    private final Properties properties;

    private String configPath;
    private final String listFile;
    private final String whoisLiteLocalURI;

    public Config(String[] args) throws IOException {
        this.properties = new Properties();
        this.args = args;
        this.parseArgs();
        this.loadProperties();

        this.listFile = this.properties.getProperty("ListFile", "list.txt").trim();
        this.whoisLiteLocalURI = properties.getProperty("WhoisLiteLocalURI", "jdbc:sqlite:whoislitelocal.db").trim();

    }

    private void parseArgs() {
        if (this.args == null) {
            return;
        }
        for (String arg : this.args) {
            if (arg.startsWith("--config=")) {
                this.configPath = arg.substring("--config=".length()).trim();
            }
        }
    }

    private void loadProperties() throws IOException {
        if (this.configPath != null) {
            // Load from specified file path
            try (InputStream input = new FileInputStream(this.configPath)) {
                this.properties.load(input);
            } catch (IOException e) {
                LOGGER.warn("Не можу завантажити конфігураційний файл: " + this.configPath, e);
                throw new IOException("Не можу завантажити конфігураційний файл: " + this.configPath, e);
            }
        } else {
            // Load from default resource
            try (InputStream input = getClass().getClassLoader().getResourceAsStream("asblockwar.properties")) {
                if (input == null) {
                    LOGGER.warn("Конфігураційний файл за-замовчуванням asblockwar.properties не доступний.");
                    throw new IOException("Конфігураційний файл за-замовчуванням asblockwar.properties не доступний.");
                }
                this.properties.load(input);
            }
        }
    }

    public String getListFile() {
        return this.listFile;
    }

    public String getWhoisLiteLocalURI() {
        return this.whoisLiteLocalURI;
    }
}
