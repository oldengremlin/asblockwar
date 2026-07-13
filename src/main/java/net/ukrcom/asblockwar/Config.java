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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Зберігає та надає доступ до конфігурації програми ASBlockWar.
 *
 * <p>Параметри завантажуються з файлу {@code asblockwar.properties}
 * (спочатку з поточного каталогу, потім з classpath) або з файлу,
 * вказаного аргументом {@code --config=<шлях>}. Значення CLI-аргументів
 * мають вищий пріоритет і перекривають файлові налаштування.
 *
 * @author olden
 */
@Slf4j
@Getter
@Setter
public class Config {

    private final String[] args;
    private final Properties properties;

    public static final String DEFAULT_AGGRESSOR_PATTERN =
            "(?im)^(org-name:.*(Kaspersky|Qrator).*|country:.*ru|phone:[^+]*\\+7.*|address:.*(mos[ck]ow|russ?ia).*|abuse-mailbox:.*\\.ru)$";

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
    private String afterCommandOverride;
    private String blockCountryOverride;
    private String forceAsBlockOverride;
    private String forceNetBlockOverride;
    private String aggressorPatternOverride;

    private String listFile;
    private String listMntbyFile;
    private String listAssetFile;
    private String whoisLiteLocalURI;
    private String storeDir;
    private String warFile;
    private String blackbgpFile;
    private String getBlackhole;
    private String getBlackholeIpv6;
    private String afterCommand;
    private List<String> blockCountry;
    private List<String> forceAsBlock;
    private List<String> forceNetBlock;
    private String aggressorPattern;
    private boolean blackbgpIpv6 = true;
    private boolean blackbgpIpv6Explicit = false;
    private boolean batchMode = false;
    private boolean gui = false;
    // -1 = flag absent (no recursion into sub-AS-SETs); >=0 = recursion depth
    private int recursiveAsset = -1;

    /**
     * Ініціалізує конфігурацію: розбирає CLI-аргументи, завантажує
     * properties-файл і зводить усі значення з пріоритетом CLI над файлом.
     *
     * @param args масив аргументів командного рядка (може бути {@code null})
     * @throws IOException якщо файл конфігурації, вказаний через {@code --config=}, не може бути прочитаний
     */
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
        this.afterCommand = this.afterCommandOverride != null
                            ? this.afterCommandOverride
                            : this.properties.getProperty("AfterCommand", defaultAfterCommand()).trim();
        this.blockCountry = parseList(this.blockCountryOverride != null
                            ? this.blockCountryOverride
                            : this.properties.getProperty("BlockCountry", "RU"));
        this.forceAsBlock = parseList(this.forceAsBlockOverride != null
                            ? this.forceAsBlockOverride
                            : this.properties.getProperty("ForceASBlock", ""))
                            .stream()
                            .map(s -> { String u = s.toUpperCase(); return u.startsWith("AS") ? u : "AS" + u; })
                            .collect(Collectors.toCollection(ArrayList::new));
        this.forceNetBlock = parseList(this.forceNetBlockOverride != null
                             ? this.forceNetBlockOverride
                             : this.properties.getProperty("ForceNETBlock", ""));
        this.aggressorPattern = this.aggressorPatternOverride != null
                                ? this.aggressorPatternOverride
                                : this.properties.getProperty("AggressorPattern", DEFAULT_AGGRESSOR_PATTERN).trim();

        // CLI flags win; fall back to properties file values
        if (!this.blackbgpIpv6Explicit) {
            this.blackbgpIpv6 = Boolean.parseBoolean(
                    this.properties.getProperty("BlackbgpIpv6", "true").trim());
        }
        if (!this.batchMode) {
            this.batchMode = Boolean.parseBoolean(
                    this.properties.getProperty("BatchMode", "false").trim());
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
     *
     * @throws IOException якщо не вдалося записати файл конфігурації
     */
    public void save() throws IOException {
        String savePath = this.configPath != null ? this.configPath : "asblockwar.properties";

        Properties p = new Properties();
        p.setProperty("ListFile", this.listFile);
        p.setProperty("ListMntbyFile", this.listMntbyFile);
        p.setProperty("ListAssetFile", this.listAssetFile);
        p.setProperty("WhoisLiteLocalURI", this.whoisLiteLocalURI);
        p.setProperty("StoreDir", this.storeDir);
        p.setProperty("WarFile", this.warFile);
        p.setProperty("BlackbgpFile", this.blackbgpFile);
        p.setProperty("GetBlackhole", this.getBlackhole);
        p.setProperty("GetBlackholeIpv6", this.getBlackholeIpv6);
        p.setProperty("BlackbgpIpv6", String.valueOf(this.blackbgpIpv6));
        p.setProperty("BatchMode", String.valueOf(this.batchMode));
        p.setProperty("AfterCommand", this.afterCommand);
        p.setProperty("BlockCountry", joinList(this.blockCountry));
        p.setProperty("ForceASBlock", joinList(this.forceAsBlock));
        p.setProperty("ForceNETBlock", joinList(this.forceNetBlock));
        p.setProperty("AggressorPattern", this.aggressorPattern);
        if (this.recursiveAsset >= 0) {
            p.setProperty("RecursiveAsset", String.valueOf(this.recursiveAsset));
        }

        try (OutputStream out = Files.newOutputStream(Path.of(savePath))) {
            p.store(out, "ASBlockWar configuration");
        }
        this.configPath = savePath;
        log.info("Конфігурацію збережено до {}", savePath);
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
            } else if (arg.equals("--batch") || arg.equals("-b")) {
                this.batchMode = true;
            } else if (arg.startsWith("--after-command=")) {
                this.afterCommandOverride = arg.substring("--after-command=".length()).trim();
            } else if (arg.startsWith("--block-country=")) {
                this.blockCountryOverride = arg.substring("--block-country=".length()).trim();
            } else if (arg.startsWith("--force-as=")) {
                this.forceAsBlockOverride = arg.substring("--force-as=".length()).trim();
            } else if (arg.startsWith("--force-net=")) {
                this.forceNetBlockOverride = arg.substring("--force-net=".length()).trim();
            } else if (arg.startsWith("--aggressor-pattern=")) {
                this.aggressorPatternOverride = arg.substring("--aggressor-pattern=".length()).trim();
            } else if (arg.equals("--ipv6") || arg.equals("-6")) {
                this.blackbgpIpv6 = true;
                this.blackbgpIpv6Explicit = true;
            } else if (arg.equals("--no-ipv6") || arg.equals("-no6")) {
                this.blackbgpIpv6 = false;
                this.blackbgpIpv6Explicit = true;
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
        System.out.println("  -6, --ipv6                Include IPv6 routes in blackbgp output (default: enabled)");
        System.out.println("  -no6, --no-ipv6           Disable IPv6 routes in blackbgp output");
        System.out.println("  --block-country=<CC,...>  Country codes to block, comma-separated (default: RU)");
        System.out.println("  --force-as=<AS,...>       ASNs to force-block regardless of country filter");
        System.out.println("  --force-net=<pfx,...>     Prefixes to force into blackbgp target (blackhole only)");
        System.out.println("  --aggressor-pattern=<rx>  Regex to match aggressor RPSL blocks (overrides config)");
        System.out.println("  --recursive-asset[=N]     Recurse into nested AS-SETs    (default depth: 1)");
        System.out.println("  -b, --batch               Run AfterCommand script after processing");
        System.out.println("  --after-command=<path>    Script to run in batch mode");
        System.out.println("                            (default: after.sh on Unix, after.cmd on Windows)");
        System.out.println("  -g, --gui                 Launch graphical user interface");
        System.out.println("  -h, --help                Show this help and exit");
    }

    private void loadProperties() throws IOException {
        if (this.configPath != null) {
            try (InputStream input = new FileInputStream(this.configPath)) {
                this.properties.load(input);
            } catch (IOException e) {
                log.warn("Не можу завантажити конфігураційний файл: " + this.configPath, e);
                throw new IOException("Не можу завантажити конфігураційний файл: " + this.configPath, e);
            }
        } else {
            Path cwdConfig = Path.of("asblockwar.properties");
            if (Files.exists(cwdConfig)) {
                log.info("Завантажую конфігурацію з {}", cwdConfig.toAbsolutePath());
                try (InputStream input = Files.newInputStream(cwdConfig)) {
                    this.properties.load(input);
                }
                this.configPath = cwdConfig.toAbsolutePath().toString();
            } else {
                try (InputStream input = getClass().getClassLoader().getResourceAsStream("asblockwar.properties")) {
                    if (input == null) {
                        log.info("Конфігураційний файл asblockwar.properties не знайдено, використовуються значення за замовчуванням.");
                        return;
                    }
                    this.properties.load(input);
                }
            }
        }
    }

    private static List<String> parseList(String s) {
        if (s == null || s.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static String joinList(List<String> list) {
        return list == null ? "" : String.join(",", list);
    }

    private static String defaultAfterCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
               ? "after.cmd" : "after.sh";
    }
}
