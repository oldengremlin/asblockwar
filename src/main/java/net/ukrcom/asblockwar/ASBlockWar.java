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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.ukrcom.asblockwar.ui.ASBlockWarApp;
import net.ukrcom.asblockwar.serviceStructures.ASN;
import net.ukrcom.asblockwar.serviceStructures.SuspiciousAS;
import net.ukrcom.asblockwar.actions.BatchRunner;
import net.ukrcom.asblockwar.actions.DiscoverAggressor;
import net.ukrcom.asblockwar.actions.DiscoveryResult;
import net.ukrcom.asblockwar.actions.FileUtils;
import net.ukrcom.asblockwar.actions.FilterAggressor;
import net.ukrcom.asblockwar.actions.ForceBlockActions;
import net.ukrcom.asblockwar.actions.MakeAggressor;
import net.ukrcom.asblockwar.actions.Reporter;
import net.ukrcom.asblockwar.actions.StoreActions;
import net.ukrcom.asblockwar.graph.GraphBuilder;
import net.ukrcom.asblockwar.graph.GraphExporter;

/**
 * Головна точка входу та ядро обробки ASBlockWar.
 * <p>
 * Реалізує повний конвеєр блокування ворожих автономних систем:
 * збір даних → фільтрація → виявлення суміжних AS → генерація конфігурацій Juniper і blackbgp.
 * Підтримує CLI-режим і GUI-режим (JavaFX).
 *
 * @author olden
 */
public class ASBlockWar {

    public static final Logger LOGGER = LoggerFactory.getLogger(ASBlockWar.class);
    public static final int MAX_CONCURRENT_DB_QUERIES = 20;
    public static Config config;
    public static String listFile;
    public static String listMntbyFile;

    public static volatile UIProgressCallback uiCallback;

    // Скомпільований патерн для використання з find() — ініціалізується у main() з config.getAggressorPattern()
    public static Pattern AGGRESSOR_COMPILED;

    public static Map<String, ASN> resourcesForVerification = new ConcurrentHashMap<>();

    // AS, що збігаються з AggressorPattern але не входять до BlockCountry
    public static Map<String, SuspiciousAS> suspiciousAsnResources = new ConcurrentHashMap<>();

    // Останній результат обробки — заблоковані ASN → RPSL-блок; доступний для GUI
    public static volatile Map<String, String> lastAggressorAsnResources = new ConcurrentHashMap<>();

    /**
     * Точка входу програми.
     * <p>
     * У GUI-режимі ({@code --gui}) запускає JavaFX-додаток; інакше виконує {@link #runProcessing()}.
     *
     * @param args аргументи командного рядка, передаються у {@link Config}
     * @throws InterruptedException якщо основний потік перервано
     */
    public static void main(String[] args) throws InterruptedException {
        silenceJavafxStartupWarning();
        try {
            config = new Config(args);
            AGGRESSOR_COMPILED = Pattern.compile(config.getAggressorPattern());

            if (config.isGui()) {
                Application.launch(ASBlockWarApp.class, args);
                return;
            }

            runProcessing();

        } catch (IOException ex) {
            LOGGER.error("Помилка вводу-виводу: ", ex);
        } catch (RuntimeException ex) {
            LOGGER.error("Непередбачена помилка: ", ex);
        }
    }

    private static void silenceJavafxStartupWarning() {
        java.io.PrintStream orig = System.err;
        System.setErr(new java.io.PrintStream(orig, true) {
            @Override
            public void write(byte[] b, int off, int len) {
                if (!new String(b, off, len).contains("Unsupported JavaFX configuration")) {
                    super.write(b, off, len);
                }
            }
        });
    }

    /**
     * Головний конвеєр обробки ворожих ASN.
     * <p>
     * Послідовність кроків:
     * <ol>
     *   <li>Читання AS-списку та MNT-BY-списку з файлів</li>
     *   <li>Фільтрація за {@link #AGGRESSOR_COMPILED}</li>
     *   <li>Крос-перевірка через записи MNT-BY та AS-SET</li>
     *   <li>Виявлення суміжних ворожих AS через AS-SET import/export</li>
     *   <li>Збереження конфігурацій: Juniper WAR, blackbgp, list.txt</li>
     *   <li>Запис детальних файлів у STORE/</li>
     *   <li>Фінальний звіт</li>
     * </ol>
     *
     * @throws IOException якщо виникла помилка читання або запису файлів
     * @throws InterruptedException якщо потік перервано під час очікування
     */
    private static String formatDuration(long nanos) {
        long ms   = nanos / 1_000_000L;
        long secs = ms / 1000;
        long min  = secs / 60;
        return min > 0
                ? String.format("%d хв %02d.%03d с", min, secs % 60, ms % 1000)
                : String.format("%d.%03d с", secs, ms % 1000);
    }

    public static void runProcessing() throws IOException, InterruptedException {
        long t0 = System.nanoTime();
        listFile = config.getListFile();
        listMntbyFile = config.getListMntbyFile();
        resourcesForVerification = new ConcurrentHashMap<>();
        suspiciousAsnResources = new ConcurrentHashMap<>();

        LOGGER.info("listFile: " + listFile);
        LOGGER.info("listMntbyFile: " + listMntbyFile);

        Map<String, String> aggressorAsnResources = MakeAggressor.makeAggressorAsnResources();
        Map<String, String> aggressorMntbyResources = MakeAggressor.makeAggressorAssetAndMntbyResources();

        LOGGER.info("Всі потоки завершили роботу. Результатів: " + aggressorAsnResources.size());
        LOGGER.info("Починаємо фільтрацію...");

        aggressorAsnResources = FilterAggressor.filterAggressorAsnResources(
                MakeAggressor.makeAggressorResources(
                        aggressorMntbyResources,
                        FilterAggressor.filterAggressorAsnResources(aggressorAsnResources)
                )
        );

        // Примусово заблоковані AS — обходять country + AGGRESSOR_PATTERN фільтри
        ForceBlockActions.applyForceAsBlock(aggressorAsnResources);

        DiscoveryResult discovery = DiscoverAggressor.discoverCooperatingAsnResources(aggressorAsnResources);
        StoreActions.storeMntByResources(discovery.mntBy());

        Set<String> allDiscoveredAsSets = new HashSet<>(discovery.asSets());
        Arrays.stream(MakeAggressor.blockedAsSet).forEach(allDiscoveredAsSets::add);
        StoreActions.storeListAsSet(allDiscoveredAsSets);

        Set<String> effectivePrefixes = StoreActions.storeResources(aggressorAsnResources);

        Set<String> allMntBy = FileUtils.readFileEntries(Path.of(listMntbyFile));
        Set<String> allAsSets = FileUtils.readFileEntries(Path.of(config.getListAssetFile()));

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            final var fa = aggressorAsnResources;
            final var fm = allMntBy;
            final var fc = effectivePrefixes;
            var detailsTask = exec.submit(() -> {
                StoreActions.storeDetails(fa, fm, allAsSets);
                return null;
            });
            var asListTask = exec.submit(() -> {
                StoreActions.storeAsList(fa);
                return null;
            });
            var mntListTask = exec.submit(() -> {
                StoreActions.storeMaintainersList(fm);
                return null;
            });
            var netTask = exec.submit(() -> {
                StoreActions.storeNetworkFiles(fc);
                return null;
            });
            for (var task : List.of(detailsTask, asListTask, mntListTask, netTask)) {
                try {
                    task.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException ioe) {
                        throw ioe;
                    }
                    throw new RuntimeException(e.getCause());
                }
            }
        }

        Reporter.report(aggressorAsnResources);

        lastAggressorAsnResources = aggressorAsnResources;

        if (config.isDependencyGraph()) {
            try {
                GraphBuilder graph = GraphBuilder.build(
                        aggressorAsnResources,
                        suspiciousAsnResources,
                        resourcesForVerification,
                        allMntBy,
                        allAsSets);
                GraphExporter.export(graph, config.getDependencyGraphPath());
            } catch (IOException e) {
                LOGGER.warn("Не вдалося згенерувати граф залежностей: {}", e.getMessage());
            }
        }

        LOGGER.info("Готово за {}.", formatDuration(System.nanoTime() - t0));
        BatchRunner.runBatchCommand();
        if (config.isBatchMode()) {
            LOGGER.info("Загальний час: {}.", formatDuration(System.nanoTime() - t0));
        }
    }

}
