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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private String listFileOverride;
    private String listMntbyFileOverride;
    private String listAssetFileOverride;
    private String whoisLiteLocalURIOverride;
    private String storeDirOverride;
    private String warFileOverride;
    private String blackbgpFileOverride;
    private String getBlackholeOverride;
    private String getBlackholeIpv6Override;
    private String listFile;
    private String listMntbyFile;
    private String listAssetFile;
    private String whoisLiteLocalURI;
    private String storeDir;
    private String warFile;
    private String blackbgpFile;
    private String getBlackhole;
    private String getBlackholeIpv6;
    private boolean blackbgpIpv6 = false;
    private boolean gui = false;
    // -1 = flag absent (no recursion into sub-AS-SETs); >=0 = recursion depth
    private int recursiveAsset = -1;

    public Config(String[] args) throws IOException {
        this.properties = new Properties();
        this.args = args;
        this.parseArgs();
        this.loadProperties();

        this.listFile = this.listFileOverride != null
                        ? this.listFileOverride
                        : this.properties.getProperty("ListFile", "list.txt").trim();
        this.listMntbyFile = this.listMntbyFileOverride != null
                             ? this.listMntbyFileOverride
                             : this.properties.getProperty("ListMntbyFile", "list.mnt-by.txt").trim();
        this.listAssetFile = this.listAssetFileOverride != null
                             ? this.listAssetFileOverride
                             : this.properties.getProperty("ListAssetFile", "list.as-set.txt").trim();
        this.whoisLiteLocalURI = this.whoisLiteLocalURIOverride != null
                                 ? this.whoisLiteLocalURIOverride
                                 : this.properties.getProperty("WhoisLiteLocalURI", "jdbc:sqlite:whoislitelocal.db").trim();
        this.storeDir = this.storeDirOverride != null
                        ? this.storeDirOverride
                        : this.properties.getProperty("StoreDir", "./STORE").trim();
        this.warFile = this.warFileOverride != null
                       ? this.warFileOverride
                       : this.properties.getProperty("WarFile", "war.juniper.txt").trim();
        this.blackbgpFile = this.blackbgpFileOverride != null
                            ? this.blackbgpFileOverride
                            : this.properties.getProperty("BlackbgpFile", "war.blackbgp.txt").trim();
        this.getBlackhole = this.getBlackholeOverride != null
                            ? this.getBlackholeOverride
                            : this.properties.getProperty("GetBlackhole",
                        "ssh blackbgp \"sudo ip r l t blackbgp\"").trim();
        this.getBlackholeIpv6 = this.getBlackholeIpv6Override != null
                                ? this.getBlackholeIpv6Override
                                : this.properties.getProperty("GetBlackholeIpv6",
                        "ssh blackbgp \"sudo ip -6 r l t blackbgp\"").trim();

        // CLI flag wins; fall back to properties file value
        if (!this.blackbgpIpv6) {
            this.blackbgpIpv6 = Boolean.parseBoolean(
                    this.properties.getProperty("BlackbgpIpv6", "false").trim());
        }
        if (this.recursiveAsset < 0) {
            String ra = this.properties.getProperty("RecursiveAsset");
            if (ra != null) {
                try {
                    this.recursiveAsset = Integer.parseInt(ra.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    /**
     * Persists current config values to disk.
     * Saves to the path it was loaded from, or to {@code asblockwar.properties}
     * in the current working directory if no external file was used.
     */
    public void save() throws IOException {
        String savePath = this.configPath != null ? this.configPath : "asblockwar.properties";

        Properties p = new Properties();
        p.setProperty("ListFile",          this.listFile);
        p.setProperty("ListMntbyFile",     this.listMntbyFile);
        p.setProperty("ListAssetFile",     this.listAssetFile);
        p.setProperty("WhoisLiteLocalURI", this.whoisLiteLocalURI);
        p.setProperty("StoreDir",          this.storeDir);
        p.setProperty("WarFile",           this.warFile);
        p.setProperty("BlackbgpFile",      this.blackbgpFile);
        p.setProperty("GetBlackhole",      this.getBlackhole);
        p.setProperty("GetBlackholeIpv6",  this.getBlackholeIpv6);
        p.setProperty("BlackbgpIpv6",      String.valueOf(this.blackbgpIpv6));
        if (this.recursiveAsset >= 0) {
            p.setProperty("RecursiveAsset", String.valueOf(this.recursiveAsset));
        }

        try (OutputStream out = Files.newOutputStream(Path.of(savePath))) {
            p.store(out, "ASBlockWar configuration");
        }
        this.configPath = savePath;
        LOGGER.info("Конфігурацію збережено до {}", savePath);
    }

    private void parseArgs() {
        if (this.args == null) {
            return;
        }
        for (String arg : this.args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                printHelp();
                System.exit(0);
            } else if (arg.startsWith("--config=")) {
                this.configPath = arg.substring("--config=".length()).trim();
            } else if (arg.startsWith("--list-file=")) {
                this.listFileOverride = arg.substring("--list-file=".length()).trim();
            } else if (arg.startsWith("--list-mnt=")) {
                this.listMntbyFileOverride = arg.substring("--list-mnt=".length()).trim();
            } else if (arg.startsWith("--list-asset=")) {
                this.listAssetFileOverride = arg.substring("--list-asset=".length()).trim();
            } else if (arg.startsWith("--whois-uri=")) {
                this.whoisLiteLocalURIOverride = arg.substring("--whois-uri=".length()).trim();
            } else if (arg.startsWith("--store-dir=")) {
                this.storeDirOverride = arg.substring("--store-dir=".length()).trim();
            } else if (arg.startsWith("--war-file=")) {
                this.warFileOverride = arg.substring("--war-file=".length()).trim();
            } else if (arg.startsWith("--blackbgp-file=")) {
                this.blackbgpFileOverride = arg.substring("--blackbgp-file=".length()).trim();
            } else if (arg.startsWith("--get-blackhole=")) {
                this.getBlackholeOverride = arg.substring("--get-blackhole=".length()).trim();
            } else if (arg.startsWith("--get-blackhole6=")) {
                this.getBlackholeIpv6Override = arg.substring("--get-blackhole6=".length()).trim();
            } else if (arg.equals("--gui") || arg.equals("-g")) {
                this.gui = true;
            } else if (arg.equals("--ipv6") || arg.equals("-6")) {
                this.blackbgpIpv6 = true;
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

    private static void printHelp() {
        System.out.println("Usage: ASBlockWar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --config=<path>           Configuration file (overrides built-in asblockwar.properties)");
        System.out.println("  --list-file=<path>        ASN list file                  (default: list.txt)");
        System.out.println("  --list-mnt=<path>         mnt-by handles file            (default: list.mnt-by.txt)");
        System.out.println("  --list-asset=<path>       AS-SET list file               (default: list.as-set.txt)");
        System.out.println("  --whois-uri=<uri>         whois-lite-local JDBC URI      (default: jdbc:sqlite:whoislitelocal.db)");
        System.out.println("  --store-dir=<path>        Output store directory         (default: ./STORE)");
        System.out.println("  --war-file=<path>         Juniper WAR output file        (default: war.juniper.txt)");
        System.out.println("  --blackbgp-file=<path>    Blackbgp commands output file  (default: war.blackbgp.txt)");
        System.out.println("  --get-blackhole=<cmd>     Command to read IPv4 blackbgp routes");
        System.out.println("                            (default: ssh blackbgp \"sudo ip r l t blackbgp\")");
        System.out.println("  --get-blackhole6=<cmd>    Command to read IPv6 blackbgp routes");
        System.out.println("                            (default: ssh blackbgp \"sudo ip -6 r l t blackbgp\")");
        System.out.println("  -6, --ipv6                Include IPv6 routes in blackbgp output");
        System.out.println("  --recursive-asset[=N]     Recurse into nested AS-SETs    (default depth: 1)");
        System.out.println("  -g, --gui                 Launch graphical user interface");
        System.out.println("  -h, --help                Show this help and exit");
    }

    private void loadProperties() throws IOException {
        if (this.configPath != null) {
            // Explicit --config=<path>
            try (InputStream input = new FileInputStream(this.configPath)) {
                this.properties.load(input);
            } catch (IOException e) {
                LOGGER.warn("Не можу завантажити конфігураційний файл: " + this.configPath, e);
                throw new IOException("Не можу завантажити конфігураційний файл: " + this.configPath, e);
            }
        } else {
            // CWD file takes precedence over classpath resource (GUI saves here)
            Path cwdConfig = Path.of("asblockwar.properties");
            if (Files.exists(cwdConfig)) {
                LOGGER.info("Завантажую конфігурацію з {}", cwdConfig.toAbsolutePath());
                try (InputStream input = Files.newInputStream(cwdConfig)) {
                    this.properties.load(input);
                }
                this.configPath = cwdConfig.toAbsolutePath().toString();
            } else {
                try (InputStream input = getClass().getClassLoader().getResourceAsStream("asblockwar.properties")) {
                    if (input == null) {
                        LOGGER.info("Конфігураційний файл asblockwar.properties не знайдено, використовуються значення за замовчуванням.");
                        return;
                    }
                    this.properties.load(input);
                }
            }
        }
    }

    public String getListFile() {
        return this.listFile;
    }

    public String getListMntbyFile() {
        return this.listMntbyFile;
    }

    public String getListAssetFile() {
        return this.listAssetFile;
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

    public String getWarFile() {
        return this.warFile;
    }

    public String getBlackbgpFile() {
        return this.blackbgpFile;
    }

    public String getGetBlackhole() {
        return this.getBlackhole;
    }

    public String getGetBlackholeIpv6() {
        return this.getBlackholeIpv6;
    }

    public boolean isBlackbgpIpv6() {
        return this.blackbgpIpv6;
    }

    public boolean isGui() {
        return this.gui;
    }

    public void setListFile(String v)           { this.listFile = v; }
    public void setListMntbyFile(String v)      { this.listMntbyFile = v; }
    public void setListAssetFile(String v)      { this.listAssetFile = v; }
    public void setWhoisLiteLocalURI(String v)  { this.whoisLiteLocalURI = v; }
    public void setStoreDir(String v)           { this.storeDir = v; }
    public void setWarFile(String v)            { this.warFile = v; }
    public void setBlackbgpFile(String v)       { this.blackbgpFile = v; }
    public void setGetBlackhole(String v)       { this.getBlackhole = v; }
    public void setGetBlackholeIpv6(String v)   { this.getBlackholeIpv6 = v; }
    public void setBlackbgpIpv6(boolean v)      { this.blackbgpIpv6 = v; }
    public void setRecursiveAsset(int v)        { this.recursiveAsset = v; }
}
