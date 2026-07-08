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
    private String storeDirOverride;
    private final String listFile;
    private final String listMntbyFile;
    private final String whoisLiteLocalURI;
    private final String storeDir;
    // -1 = flag absent (no recursion into sub-AS-SETs); >=0 = recursion depth
    private int recursiveAsset = -1;

    public Config(String[] args) throws IOException {
        this.properties = new Properties();
        this.args = args;
        this.parseArgs();
        this.loadProperties();

        this.listFile = this.properties.getProperty("ListFile", "list.txt").trim();
        this.listMntbyFile = this.properties.getProperty("ListMntbyFile", "list.mnt-by.txt").trim();
        this.whoisLiteLocalURI = properties.getProperty("WhoisLiteLocalURI", "jdbc:sqlite:whoislitelocal.db").trim();
        this.storeDir = this.storeDirOverride != null
                ? this.storeDirOverride
                : this.properties.getProperty("StoreDir", "./STORE").trim();

    }

    private void parseArgs() {
        if (this.args == null) {
            return;
        }
        for (String arg : this.args) {
            if (arg.startsWith("--config=")) {
                this.configPath = arg.substring("--config=".length()).trim();
            } else if (arg.startsWith("--store-dir=")) {
                this.storeDirOverride = arg.substring("--store-dir=".length()).trim();
            } else if (arg.equals("--recursive-asset")) {
                this.recursiveAsset = 1;
            } else if (arg.startsWith("--recursive-asset=")) {
                String val = arg.substring("--recursive-asset=".length()).trim();
                try {
                    this.recursiveAsset = Integer.parseInt(val);
                } catch (NumberFormatException e) {
                    this.recursiveAsset = 1;
                }
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
            // Load from default resource, якщо він є; інакше — значення за замовчуванням для всіх властивостей
            try (InputStream input = getClass().getClassLoader().getResourceAsStream("asblockwar.properties")) {
                if (input == null) {
                    LOGGER.info("Конфігураційний файл asblockwar.properties не знайдено, використовуються значення за замовчуванням.");
                    return;
                }
                this.properties.load(input);
            }
        }
    }

    public String getListFile() {
        return this.listFile;
    }

    public String getListMntbyFile() {
        return this.listMntbyFile;
    }

    public String getWhoisLiteLocalURI() {
        return this.whoisLiteLocalURI;
    }

    public int getRecursiveAsset() {
        return this.recursiveAsset;
    }

    public String getStoreDir() {
        return this.storeDir;
    }
}
