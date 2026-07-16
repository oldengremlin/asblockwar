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
package net.ukrcom.asblockwar.actions;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import net.ukrcom.asblockwar.ASBlockWar;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveBlackbgpPrefixes;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAsSetMembers;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveImportExportAsSets;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveOrganisation;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveRouteOriginPrefixes;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveRouteOrigins;
import net.ukrcom.asblockwar.serviceStructures.Action;
import net.ukrcom.asblockwar.serviceStructures.ASN;

/**
 * Виявлення нових ворожих ASN через мережі import/export та зміни blackbgp.
 * <p>
 * Містить логіку виявлення суміжних AS через AS-SET та обчислення delta
 * для таблиці маршрутизації blackbgp.
 */
@Slf4j
public class DiscoverAggressor {

    private DiscoverAggressor() {
    }

    public static final Pattern SERVICE_MNT = Pattern.compile("^RIPE-.+", Pattern.CASE_INSENSITIVE);

    private static void addMntBy(String block, Set<String> target) {
        block.lines()
                .filter(l -> l.matches("(?i)^mnt-by:.*"))
                .map(l -> l.replaceFirst("(?i)^mnt-by:\\s*", "").trim())
                .filter(v -> !v.isEmpty())
                .forEach(v -> target.add(v.toUpperCase()));
    }

    /**
     * Виявляє ворожі ASN через мережі import/export вже відомих агресорів.
     * <p>
     * Для кожного вже відомого ворожого ASN виконується два паралельних потоки пошуку:
     * <ol>
     *   <li><b>AS-SET-потік</b>: знаходить AS-SET з {@code accept}-конструкцій, рекурсивно
     *       розгортає їхніх членів (глибина {@code config.recursiveAsset}) і перевіряє кожного
     *       члена через {@link FilterAggressor#isAggressor}.</li>
     *   <li><b>Прямий ASN-потік</b>: витягує всі прямі ASN ({@code AS\d+}) з будь-якої частини
     *       import/export рядків ({@code from}, {@code to}, {@code accept}, {@code announce})
     *       і перевіряє кожен через {@link FilterAggressor#isAggressor}.</li>
     * </ol>
     * Нові ворожі ASN додаються до {@code aggressorAsnResources} та {@link ASBlockWar#resourcesForVerification}.
     * <p>
     * Кожен кандидат перевіряється не більше одного разу: {@code seenAsns} (thread-safe Set)
     * передзаповнюється вже відомими ворожими ASN і атомарно поповнюється перед кожним
     * зверненням до БД — повторні запити по тих самих ASN виключені.
     *
     * @param aggressorAsnResources карта {@code ASN → RPSL-блок}; модифікується на місці
     * @return результат з виявленими MNT-BY та AS-SET для подальшого збереження
     */
    public static DiscoveryResult discoverCooperatingAsnResources(Map<String, String> aggressorAsnResources) {
        int depth = Math.max(ASBlockWar.config.getRecursiveAsset(), 0);
        Set<String> discoveredMntBy  = ConcurrentHashMap.newKeySet();
        Set<String> discoveredAsSets = ConcurrentHashMap.newKeySet();
        Set<String> blocked          = FilterAggressor.blockedCountries();
        Set<String> seenAsns         = ConcurrentHashMap.newKeySet();
        seenAsns.addAll(aggressorAsnResources.keySet());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);

            aggressorAsnResources.keySet().parallelStream()
                    .forEach(asn -> executor.submit(() -> {
                try {
                    retrieveImportExportAsSets retriever;
                    dbLimit.acquire();
                    try {
                        retriever = new retrieveImportExportAsSets(asn);
                    } finally {
                        dbLimit.release();
                    }
                    Set<String> asSets    = retriever.get();
                    Set<String> directAsns = retriever.getAsns();

                    // AS-SET-потік: рекурсивне розгортання членів
                    for (String asSet : asSets) {
                        discoveredAsSets.add(asSet);

                        Set<String> memberAsns;
                        dbLimit.acquire();
                        try {
                            memberAsns = new retrieveAsSetMembers(asSet, depth).get();
                        } finally {
                            dbLimit.release();
                        }

                        for (String memberAsn : memberAsns) {
                            if (!seenAsns.add(memberAsn)) {
                                continue;
                            }
                            dbLimit.acquire();
                            try {
                                String block = new retrieveOrganisation(memberAsn).get();
                                if (FilterAggressor.isAggressor(block, blocked)) {
                                    log.debug("discoverCooperating(as-set): {} -> {} -> {}", asn, asSet, memberAsn);
                                    ASBlockWar.resourcesForVerification.put(memberAsn, new ASN(Action.add, memberAsn, block));
                                    aggressorAsnResources.put(memberAsn, block);
                                    addMntBy(block, discoveredMntBy);
                                }
                            } finally {
                                dbLimit.release();
                            }
                        }
                    }

                    // Прямий ASN-потік: from/to/accept/announce з import/export рядків
                    for (String directAsn : directAsns) {
                        if (!seenAsns.add(directAsn)) {
                            continue;
                        }
                        dbLimit.acquire();
                        try {
                            String block = new retrieveOrganisation(directAsn).get();
                            if (FilterAggressor.isAggressor(block, blocked)) {
                                log.debug("discoverCooperating(direct): {} -> {}", asn, directAsn);
                                ASBlockWar.resourcesForVerification.put(directAsn, new ASN(Action.add, directAsn, block));
                                aggressorAsnResources.put(directAsn, block);
                                addMntBy(block, discoveredMntBy);
                            }
                        } finally {
                            dbLimit.release();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        return new DiscoveryResult(discoveredMntBy, discoveredAsSets);
    }

    /**
     * Обчислює необхідні зміни для таблиці маршрутизації blackbgp.
     * <p>
     * Алгоритм:
     * <ol>
     *   <li>Читає поточний стан blackbgp через SSH</li>
     *   <li>Формує цільовий набір prefix'ів з локальної БД для всіх ворожих ASN</li>
     *   <li>Обчислює {@code toDelete} = поточні − цільові; {@code toReplace} = цільові − поточні</li>
     *   <li>Для кожного prefix у {@code toDelete} перевіряє origin-AS:
     *       якщо належить вже відомій або щойно виявленій ворожій AS — скасовує видалення</li>
     * </ol>
     * Не виконує жодного запису на диск.
     *
     * @param aggressorAsnResources карта {@code ASN → RPSL-блок}
     * @return обчислені зміни разом з набором нових ворожих ASN
     */
    public static BlackbgpChanges discoverBlackbgpChanges(Map<String, String> aggressorAsnResources) {
        boolean ipv6 = ASBlockWar.config.isBlackbgpIpv6();

        // 1. Поточний стан таблиці blackbgp (через SSH)
        Set<String> currentPrefixes = new retrieveBlackbgpPrefixes(ipv6).get();
        log.info("discoverBlackbgpChanges: поточних маршрутів у blackbgp: {}", currentPrefixes.size());

        // 2. Цільовий набір prefixes з БД (тільки IPv4 якщо не передано -6)
        Set<String> targetPrefixes = ConcurrentHashMap.newKeySet();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);
            aggressorAsnResources.keySet().forEach(asn -> executor.submit(() -> {
                try {
                    dbLimit.acquire();
                    try {
                        new retrieveRouteOriginPrefixes(asn).get().stream()
                                .filter(p -> ipv6 || !p.contains(":"))
                                .forEach(targetPrefixes::add);
                    } finally {
                        dbLimit.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // ForceNETBlock: примусово додаємо до цілі незалежно від БД
        // Нормалізуємо: хост без префіксу → /32 (IPv4) або /128 (IPv6)
        ASBlockWar.config.getForceNetBlock().stream()
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .map(p -> p.contains("/") ? p : (p.contains(":") ? p + "/128" : p + "/32"))
                .filter(p -> ipv6 || !p.contains(":"))
                .forEach(targetPrefixes::add);

        // 3. Диф: видалити = поточні - цільові; додати = цільові - поточні
        Set<String> toDelete = ConcurrentHashMap.newKeySet();
        toDelete.addAll(currentPrefixes);
        toDelete.removeAll(targetPrefixes);

        Set<String> toReplace = new HashSet<>(targetPrefixes);
        toReplace.removeAll(currentPrefixes);

        // 4. Перевірка маршрутів на видалення: чи не належать вони ворогу?
        Set<String> blocked = FilterAggressor.blockedCountries();
        Map<String, String> newEnemies = new ConcurrentHashMap<>();
        if (!toDelete.isEmpty()) {
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);
                toDelete.forEach(prefix -> executor.submit(() -> {
                    try {
                        List<String> origins;
                        dbLimit.acquire();
                        try {
                            origins = new retrieveRouteOrigins(prefix).get();
                        } finally {
                            dbLimit.release();
                        }

                        // Перевірка 1: вже відома ворожа AS?
                        for (String origin : origins) {
                            if (aggressorAsnResources.containsKey(origin)) {
                                log.warn("discoverBlackbgpChanges: {} належить ворожій {} — видалення скасовано",
                                        prefix, origin);
                                toDelete.remove(prefix);
                                return;
                            }
                        }

                        // Перевірка 2: нова ворожа AS?
                        for (String origin : origins) {
                            dbLimit.acquire();
                            String block;
                            try {
                                block = new retrieveOrganisation(origin).get();
                            } finally {
                                dbLimit.release();
                            }
                            if (FilterAggressor.isAggressor(block, blocked)) {
                                log.warn("discoverBlackbgpChanges: {} — нова ворожа AS {} — додано до списку, видалення скасовано",
                                        prefix, origin);
                                newEnemies.put(origin, block);
                                toDelete.remove(prefix);
                                return;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
        }

        // Ефективний стан після застосування war.blackbgp.txt:
        // ті що скасували видалення лишаються, нові додаються
        Set<String> effectivePrefixes = new HashSet<>(currentPrefixes);
        effectivePrefixes.removeAll(toDelete);
        effectivePrefixes.addAll(toReplace);

        log.info("discoverBlackbgpChanges: {} delete + {} replace (current={}, target={}, newEnemies={})",
                toDelete.size(), toReplace.size(),
                currentPrefixes.size(), targetPrefixes.size(), newEnemies.size());

        return new BlackbgpChanges(toDelete, toReplace, newEnemies, effectivePrefixes);
    }
}
