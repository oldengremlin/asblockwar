/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.asblockwar;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 *
 * @author olden
 */
public class Config {

    private final String[] args;
    private final Properties properties;

    private String configPath;
    private final String listFile;
    private final boolean progressSleep;
    private final int progressPublish;
    private final String whoisLiteLocalFile;

    public Config(String[] args) throws IOException {
        this.properties = new Properties();
        this.args = args;
        parseArgs();
        loadProperties();

        this.listFile = this.properties.getProperty("ListFile", "list.txt").trim();
        this.progressSleep = Boolean.parseBoolean(properties.getProperty("ProgressSleep", "true"));
        this.progressPublish = Integer.parseInt(properties.getProperty("ProgressPublish", "100"));
        this.whoisLiteLocalFile = properties.getProperty("WhoisLiteLocalFile", "whoislitelocal.db").trim();
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
        if (configPath != null) {
            // Load from specified file path
            try (InputStream input = new FileInputStream(configPath)) {
                properties.load(input);
            } catch (IOException e) {
                throw new IOException("Не можу завантажити конфігураційний файл: " + configPath, e);
            }
        } else {
            // Load from default resource
            try (InputStream input = getClass().getClassLoader().getResourceAsStream("asblockwar.properties")) {
                if (input == null) {
                    throw new IOException("Конфігураційний файл за-замовчуванням asblockwar.properties не доступний. ");
                }
                properties.load(input);
            }
        }
    }

    public String getListFile() {
        return this.listFile;
    }

    public boolean getProgressSleep() {
        return this.progressSleep;
    }

    public int getProgressPublish() {
        return this.progressPublish;
    }

    public String getWhoisLiteLocalFile() {
        return this.whoisLiteLocalFile;
    }
}
