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
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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
@Command(
        name = "ASBlockWar",
        sortOptions = false,
        usageHelpWidth = 100,
        description = "Automated maintenance of hostile autonomous system block lists."
)
@Slf4j
@Getter
@Setter
public class Config {

    private final Properties properties;

    public static final String DEFAULT_AGGRESSOR_PATTERN
            = "(?im)^(org-name:.*(Kaspersky|Qrator).*|country:.*ru|phone:[^+]*\\+7.*|address:.*(mos[ck]ow|russ?ia).*|abuse-mailbox:.*\\.ru)$";

    // -----------------------------------------------------------------------
    // CLI options — Picocli sets these in phase 1 (before properties are read)
    // -----------------------------------------------------------------------
    @Option(names = {"-h", "--help"}, usageHelp = true,
            description = "Show this help and exit")
    private boolean helpRequested;

    @Option(names = "--config", paramLabel = "<path>",
            description = "Configuration file path")
    private String configPath;

    @Option(names = "--list-file", paramLabel = "<path>",
            description = "ASN list file  (default: list.txt)")
    private String listFileOverride;

    @Option(names = "--list-mnt", paramLabel = "<path>",
            description = "mnt-by handles file  (default: list.mnt-by.txt)")
    private String listMntbyFileOverride;

    @Option(names = "--list-asset", paramLabel = "<path>",
            description = "AS-SET list file  (default: list.as-set.txt)")
    private String listAssetFileOverride;

    @Option(names = "--whois-uri", paramLabel = "<uri>",
            description = "whois-lite-local JDBC URI  (default: jdbc:sqlite:whoislitelocal.db)")
    private String whoisLiteLocalURIOverride;

    @Option(names = "--store-dir", paramLabel = "<path>",
            description = "Output store directory  (default: ./STORE)")
    private String storeDirOverride;

    @Option(names = "--war-file", paramLabel = "<path>",
            description = "Juniper WAR output file  (default: war.juniper.txt)")
    private String warFileOverride;

    @Option(names = "--blackbgp-file", paramLabel = "<path>",
            description = "Blackbgp commands output file  (default: war.blackbgp.txt)")
    private String blackbgpFileOverride;

    @Option(names = "--get-blackhole", paramLabel = "<cmd>",
            description = "Command to read IPv4 blackbgp routes%n"
            + "  (default: ssh blackbgp \"sudo ip r l t blackbgp\")")
    private String getBlackholeOverride;

    @Option(names = "--get-blackhole6", paramLabel = "<cmd>",
            description = "Command to read IPv6 blackbgp routes%n"
            + "  (default: ssh blackbgp \"sudo ip -6 r l t blackbgp\")")
    private String getBlackholeIpv6Override;

    @Option(names = {"--ipv6", "-6"},
            description = "Include IPv6 routes in blackbgp output  (default: enabled)")
    private Boolean ipv6Flag;

    @Option(names = {"--no-ipv6", "-no6"},
            description = "Disable IPv6 routes in blackbgp output")
    private Boolean noIpv6Flag;

    @Option(names = "--block-country", paramLabel = "<CC,...>",
            description = "Country codes to block, comma-separated  (default: RU)")
    private String blockCountryOverride;

    @Option(names = "--force-as", paramLabel = "<AS,...>",
            description = "ASNs to force-block regardless of country/pattern filters")
    private String forceAsBlockOverride;

    @Option(names = "--force-net", paramLabel = "<pfx,...>",
            description = "Prefixes to force into the blackbgp target  (blackhole only)")
    private String forceNetBlockOverride;

    @Option(names = "--aggressor-pattern", paramLabel = "<rx>",
            description = "Regex to match aggressor RPSL blocks  (overrides config file)")
    private String aggressorPatternOverride;

    @Option(names = "--recursive-asset", arity = "0..1", fallbackValue = "1", paramLabel = "<depth>",
            description = "Recurse into nested AS-SETs  (default depth when flag is bare: 1)")
    private Integer recursiveAssetOverride;

    @Option(names = {"-b", "--batch"},
            description = "Run AfterCommand script after processing")
    private boolean batchMode;

    @Option(names = "--after-command", paramLabel = "<path>",
            description = "Script to run in batch mode%n"
            + "  (default: after.sh on Unix, after.cmd on Windows)")
    private String afterCommandOverride;

    @Option(names = {"-g", "--gui"},
            description = "Launch graphical user interface")
    private boolean gui;

    // -----------------------------------------------------------------------
    // Resolved configuration  (CLI > properties file > built-in defaults)
    // -----------------------------------------------------------------------
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

        // Phase 1: parse CLI args (sets configPath and all *Override fields)
        CommandLine cmd = new CommandLine(this);
        try {
            cmd.parseArgs(args != null ? args : new String[0]);
        } catch (CommandLine.ParameterException ex) {
            System.err.println(ex.getMessage());
            System.err.println("Run with --help for usage.");
            System.exit(1);
        }
        if (cmd.isUsageHelpRequested()) {
            cmd.usage(System.out);
            System.exit(0);
        }

        // Phase 2: load properties file (configPath already set by phase 1)
        this.loadProperties();

        // Phase 3: resolve each field (CLI override wins, then properties, then default)
        this.listFile = listFileOverride != null
                        ? listFileOverride
                        : properties.getProperty("ListFile", "list.txt").trim();
        this.listMntbyFile = listMntbyFileOverride != null
                             ? listMntbyFileOverride
                             : properties.getProperty("ListMntbyFile", "list.mnt-by.txt").trim();
        this.listAssetFile = listAssetFileOverride != null
                             ? listAssetFileOverride
                             : properties.getProperty("ListAssetFile", "list.as-set.txt").trim();
        this.whoisLiteLocalURI = whoisLiteLocalURIOverride != null
                                 ? whoisLiteLocalURIOverride
                                 : properties.getProperty("WhoisLiteLocalURI", "jdbc:sqlite:whoislitelocal.db").trim();
        this.storeDir = storeDirOverride != null
                        ? storeDirOverride
                        : properties.getProperty("StoreDir", "./STORE").trim();
        this.warFile = warFileOverride != null
                       ? warFileOverride
                       : properties.getProperty("WarFile", "war.juniper.txt").trim();
        this.blackbgpFile = blackbgpFileOverride != null
                            ? blackbgpFileOverride
                            : properties.getProperty("BlackbgpFile", "war.blackbgp.txt").trim();
        this.getBlackhole = getBlackholeOverride != null
                            ? getBlackholeOverride
                            : properties.getProperty("GetBlackhole",
                        "ssh blackbgp \"sudo ip r l t blackbgp\"").trim();
        this.getBlackholeIpv6 = getBlackholeIpv6Override != null
                                ? getBlackholeIpv6Override
                                : properties.getProperty("GetBlackholeIpv6",
                        "ssh blackbgp \"sudo ip -6 r l t blackbgp\"").trim();
        this.afterCommand = afterCommandOverride != null
                            ? afterCommandOverride
                            : properties.getProperty("AfterCommand", defaultAfterCommand()).trim();
        this.blockCountry = parseList(blockCountryOverride != null
                                      ? blockCountryOverride
                                      : properties.getProperty("BlockCountry", "RU"));
        this.forceAsBlock = parseList(forceAsBlockOverride != null
                                      ? forceAsBlockOverride
                                      : properties.getProperty("ForceASBlock", ""))
                .stream()
                .map(s -> {
                    String u = s.toUpperCase();
                    return u.startsWith("AS") ? u : "AS" + u;
                })
                .collect(Collectors.toCollection(ArrayList::new));
        this.forceNetBlock = parseList(forceNetBlockOverride != null
                                       ? forceNetBlockOverride
                                       : properties.getProperty("ForceNETBlock", ""));
        this.aggressorPattern = aggressorPatternOverride != null
                                ? aggressorPatternOverride
                                : properties.getProperty("AggressorPattern", DEFAULT_AGGRESSOR_PATTERN).trim();

        if (Boolean.TRUE.equals(ipv6Flag)) {
            this.blackbgpIpv6 = true;
            this.blackbgpIpv6Explicit = true;
        } else if (Boolean.TRUE.equals(noIpv6Flag)) {
            this.blackbgpIpv6 = false;
            this.blackbgpIpv6Explicit = true;
        }
        if (!this.blackbgpIpv6Explicit) {
            this.blackbgpIpv6 = Boolean.parseBoolean(
                    properties.getProperty("BlackbgpIpv6", "true").trim());
        }

        if (!this.batchMode) {
            this.batchMode = Boolean.parseBoolean(
                    properties.getProperty("BatchMode", "false").trim());
        }

        if (recursiveAssetOverride != null) {
            this.recursiveAsset = recursiveAssetOverride;
        } else {
            String ra = properties.getProperty("RecursiveAsset");
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
