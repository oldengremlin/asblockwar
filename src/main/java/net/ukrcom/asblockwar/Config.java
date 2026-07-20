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
import java.util.Map;
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
 * <p>Ланцюг пріоритетів для кожного параметру (від вищого до нижчого):
 * CLI-аргумент → {@code asblockwar.properties} (файл або classpath) → {@code @Option(defaultValue)}.
 * Ланцюг реалізований через Picocli {@code IDefaultProvider} ({@link #propertyDefault}).
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

    /**
     * Відповідність між іменем CLI-опції та ключем у {@code asblockwar.properties}.
     * Використовується в {@link #propertyDefault} для реалізації {@code IDefaultProvider}.
     */
    private static final Map<String, String> OPT_TO_PROP = Map.ofEntries(
            Map.entry("--list-file", "ListFile"),
            Map.entry("--list-mnt", "ListMntbyFile"),
            Map.entry("--list-asset", "ListAssetFile"),
            Map.entry("--whois-uri", "WhoisLiteLocalURI"),
            Map.entry("--store-dir", "StoreDir"),
            Map.entry("--war-file", "WarFile"),
            Map.entry("--blackbgp-file", "BlackbgpFile"),
            Map.entry("--get-blackhole", "GetBlackhole"),
            Map.entry("--get-blackhole6", "GetBlackholeIpv6"),
            Map.entry("--after-command", "AfterCommand"),
            Map.entry("--block-country", "BlockCountry"),
            Map.entry("--force-as", "ForceASBlock"),
            Map.entry("--force-net", "ForceNETBlock"),
            Map.entry("--aggressor-pattern", "AggressorPattern"),
            Map.entry("--recursive-asset", "RecursiveAsset"),
            Map.entry("--batch", "BatchMode"),
            Map.entry("--dependency-graph", "DependencyGraph")
    );

    // -----------------------------------------------------------------------
    // CLI options  (Picocli заповнює: CLI → propertyDefault → @Option defaultValue)
    // String/boolean/Integer поля — вже є фінальними resolved-значеннями після парсингу
    // -----------------------------------------------------------------------
    @Option(names = {"-h", "--help"}, usageHelp = true,
            description = "Show this help and exit")
    private boolean helpRequested;

    @Option(names = "--config", paramLabel = "<path>",
            description = "Configuration file path")
    private String configPath;

    @Option(names = "--list-file", paramLabel = "<path>",
            defaultValue = "list.txt",
            description = "ASN list file  (default: list.txt)")
    private String listFile;

    @Option(names = "--list-mnt", paramLabel = "<path>",
            defaultValue = "list.mnt-by.txt",
            description = "mnt-by handles file  (default: list.mnt-by.txt)")
    private String listMntbyFile;

    @Option(names = "--list-asset", paramLabel = "<path>",
            defaultValue = "list.as-set.txt",
            description = "AS-SET list file  (default: list.as-set.txt)")
    private String listAssetFile;

    @Option(names = "--whois-uri", paramLabel = "<uri>",
            defaultValue = "jdbc:sqlite:whoislitelocal.db",
            description = "whois-lite-local JDBC URI  (default: jdbc:sqlite:whoislitelocal.db)")
    private String whoisLiteLocalURI;

    @Option(names = "--store-dir", paramLabel = "<path>",
            defaultValue = "./STORE",
            description = "Output store directory  (default: ./STORE)")
    private String storeDir;

    @Option(names = "--war-file", paramLabel = "<path>",
            defaultValue = "war.juniper.txt",
            description = "Juniper WAR output file  (default: war.juniper.txt)")
    private String warFile;

    @Option(names = "--blackbgp-file", paramLabel = "<path>",
            defaultValue = "war.blackbgp.txt",
            description = "Blackbgp commands output file  (default: war.blackbgp.txt)")
    private String blackbgpFile;

    @Option(names = "--get-blackhole", paramLabel = "<cmd>",
            defaultValue = "ssh blackbgp \"sudo ip r l t blackbgp\"",
            description = "Command to read IPv4 blackbgp routes%n"
            + "  (default: ssh blackbgp \"sudo ip r l t blackbgp\")")
    private String getBlackhole;

    @Option(names = "--get-blackhole6", paramLabel = "<cmd>",
            defaultValue = "ssh blackbgp \"sudo ip -6 r l t blackbgp\"",
            description = "Command to read IPv6 blackbgp routes%n"
            + "  (default: ssh blackbgp \"sudo ip -6 r l t blackbgp\")")
    private String getBlackholeIpv6;

    @Option(names = {"--ipv6", "-6"},
            description = "Include IPv6 routes in blackbgp output  (default: enabled)")
    private Boolean ipv6Flag;

    @Option(names = {"--no-ipv6", "-no6"},
            description = "Disable IPv6 routes in blackbgp output")
    private Boolean noIpv6Flag;

    // Comma-separated raw strings; resolved to List<String> fields below
    @Option(names = "--block-country", paramLabel = "<CC,...>",
            defaultValue = "RU",
            description = "Country codes to block, comma-separated  (default: RU)")
    private String blockCountryOverride;

    @Option(names = "--force-as", paramLabel = "<AS,...>",
            defaultValue = "",
            description = "ASNs to force-block regardless of country/pattern filters")
    private String forceAsBlockOverride;

    @Option(names = "--force-net", paramLabel = "<pfx,...>",
            defaultValue = "",
            description = "Prefixes to force into the blackbgp target  (blackhole only)")
    private String forceNetBlockOverride;

    @Option(names = "--aggressor-pattern", paramLabel = "<rx>",
            defaultValue = DEFAULT_AGGRESSOR_PATTERN,
            description = "Regex to match aggressor RPSL blocks  (overrides config file)")
    private String aggressorPattern;

    @Option(names = "--recursive-asset", arity = "0..1", fallbackValue = "1",
            paramLabel = "<depth>",
            description = "Recurse into nested AS-SETs  (default depth when flag is bare: 1)")
    private Integer recursiveAssetFlag;

    @Option(names = {"-b", "--batch"},
            description = "Run AfterCommand script after processing")
    private boolean batchMode;

    @Option(names = "--after-command", paramLabel = "<path>",
            description = "Script to run in batch mode%n"
            + "  (default: after.sh on Unix, after.cmd on Windows)")
    private String afterCommand;

    @Option(names = {"-g", "--gui"},
            description = "Launch graphical user interface")
    private boolean gui;

    @Option(names = {"-dg", "--dependency-graph"}, arity = "0..1",
            fallbackValue = "dependency-graph.html",
            defaultValue = "dependency-graph.html",
            paramLabel = "<path>",
            description = "Generate dependency graph HTML%n"
            + "  (default: dependency-graph.html; empty string disables)")
    private String dependencyGraphPath;

    @Option(names = "--primary-enemy", paramLabel = "<items,...>",
            defaultValue = "",
            description = "AS-SETs to ADD to PrimaryEnemyResources (additive, does not replace config file value)")
    private String primaryEnemyOverride;

    // -----------------------------------------------------------------------
    // Resolved list fields and special-case values — set in the constructor
    // -----------------------------------------------------------------------
    private List<String> blockCountry;
    private List<String> forceAsBlock;
    private List<String> forceNetBlock;
    private List<String> primaryEnemyResources;
    private boolean blackbgpIpv6 = true;
    private boolean blackbgpIpv6Explicit = false;
    // -1 = flag absent (no recursion into sub-AS-SETs); >=0 = recursion depth
    private int recursiveAsset = -1;
    // Чи використовувати sfdp для pre-computed layout графа залежностей
    private boolean useSfdp = true;
    // Чи включати вузли зі статусом UNKNOWN до графа залежностей (за замовчуванням false)
    private boolean dependencyWithUnknown = false;

    /**
     * Ініціалізує конфігурацію: розбирає CLI-аргументи, завантажує
     * properties-файл і зводить усі значення з пріоритетом CLI над файлом.
     *
     * @param args масив аргументів командного рядка (може бути {@code null})
     * @throws IOException якщо файл конфігурації, вказаний через {@code --config=}, не може бути прочитаний
     */
    public Config(String[] args) throws IOException {
        this.properties = new Properties();

        // Bootstrap: extract --config= so loadProperties() knows the path before full parse
        if (args != null) {
            for (String arg : args) {
                if (arg.startsWith("--config=")) {
                    this.configPath = arg.substring("--config=".length()).trim();
                    break;
                }
            }
        }
        loadProperties();

        // Full parse: CLI → propertyDefault() → @Option(defaultValue=...)
        CommandLine cmd = new CommandLine(this)
                .setDefaultValueProvider(this::propertyDefault);
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

        // Resolve list fields from comma-separated strings (already filled by Picocli)
        this.blockCountry = parseList(blockCountryOverride);
        this.forceAsBlock = parseList(forceAsBlockOverride).stream()
                .map(s -> {
                    String u = s.toUpperCase();
                    return u.startsWith("AS") ? u : "AS" + u;
                })
                .collect(Collectors.toCollection(ArrayList::new));
        this.forceNetBlock = parseList(forceNetBlockOverride);

        // PrimaryEnemyResources: load from property, then ADD items from CLI (add semantics)
        // Голі числа (наприклад, "2848") нормалізуються до "AS2848"
        this.primaryEnemyResources = parseList(
                properties.getProperty("PrimaryEnemyResources",
                        "AS-MAILRU,AS-VKONTAKTE,AS-VK,AS-YANDEX,AS-M100")).stream()
                .map(String::toUpperCase)
                .map(s -> s.matches("\\d+") ? "AS" + s : s)
                .collect(Collectors.toCollection(ArrayList::new));
        parseList(primaryEnemyOverride).stream()
                .map(String::toUpperCase)
                .map(s -> s.matches("\\d+") ? "AS" + s : s)
                .filter(item -> !this.primaryEnemyResources.contains(item))
                .forEach(this.primaryEnemyResources::add);

        // Resolve ipv6: CLI flags override the BlackbgpIpv6 property
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

        this.useSfdp = Boolean.parseBoolean(
                properties.getProperty("UseSfdp", "true").trim());
        this.dependencyWithUnknown = Boolean.parseBoolean(
                properties.getProperty("DependencyWithUnknown", "false").trim());

        // Resolve recursiveAsset: null flag = absent
        this.recursiveAsset = recursiveAssetFlag != null ? recursiveAssetFlag : -1;
    }

    /**
     * Реалізація Picocli {@code IDefaultProvider}: повертає значення з
     * {@code asblockwar.properties} як дефолт для опцій, що відсутні в CLI.
     * Для {@code --after-command} повертає платформозалежний дефолт, якщо
     * властивість відсутня в файлі.
     *
     * @param argSpec специфікація опції, для якої запитується дефолт
     * @return значення з properties-файлу, або платформозалежний дефолт, або {@code null}
     */
    private String propertyDefault(CommandLine.Model.ArgSpec argSpec) {
        if (!(argSpec instanceof CommandLine.Model.OptionSpec opt)) {
            return null;
        }
        String propKey = OPT_TO_PROP.get(opt.longestName());
        if (propKey == null) {
            return null;
        }
        String val = properties.getProperty(propKey);
        if (val == null && "--after-command".equals(opt.longestName())) {
            return defaultAfterCommand();
        }
        return val;
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
        p.setProperty("DependencyGraph",
                this.dependencyGraphPath != null ? this.dependencyGraphPath : "");
        p.setProperty("UseSfdp", String.valueOf(this.useSfdp));
        p.setProperty("DependencyWithUnknown", String.valueOf(this.dependencyWithUnknown));
        p.setProperty("PrimaryEnemyResources", joinList(this.primaryEnemyResources));

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

    /** Повертає {@code true}, якщо генерація графа увімкнена (шлях не порожній). */
    public boolean isDependencyGraph() {
        return dependencyGraphPath != null && !dependencyGraphPath.isBlank();
    }

    private static String defaultAfterCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
               ? "after.cmd" : "after.sh";
    }
}
