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

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import net.ukrcom.asblockwar.ASBlockWar;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveBlackbgpPrefixes;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAsSetMembers;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveImportExportAsSets;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveInetnumCountry;
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


    /**
     * Країна, яку заявляє origin-ASN маршруту — та сама, через яку маршрут
     * і пройшов повз блокування. Потрібна лише для читабельного «DE (RU)» у звіті.
     *
     * @param origins               origin-ASN маршруту
     * @param aggressorAsnResources поточна карта ворогів (може містити блок ASN)
     * @return код країни або {@code "?"}, якщо визначити не вдалося
     */
    private static String originCountry(List<String> origins, Map<String, String> aggressorAsnResources) {
        for (String origin : origins) {
            String block = aggressorAsnResources.get(origin);
            if (block == null || block.isBlank()) {
                block = new retrieveOrganisation(origin).get();
            }
            String country = RpslUtils.rpslField(block, "country");
            if (!country.isEmpty()) {
                return country.toUpperCase();
            }
        }
        return "?";
    }

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

        try (ExecutorService executor = VirtualExecutor.create("discover")) {
            Semaphore dbLimit = ASBlockWar.DB_LIMIT;

            aggressorAsnResources.keySet().stream()
                    .forEach(asn -> executor.execute(() -> {
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
    public static BlackbgpChanges discoverBlackbgpChanges(Map<String, String> aggressorAsnResources)
            throws IOException {
        boolean ipv6 = ASBlockWar.config.isBlackbgpIpv6();

        // 1. Поточний стан таблиці blackbgp (через SSH)
        Set<String> currentPrefixes = new retrieveBlackbgpPrefixes(ipv6).get();
        log.info("discoverBlackbgpChanges: поточних маршрутів у blackbgp: {}", currentPrefixes.size());

        // 2. Цільовий набір prefixes з БД (тільки IPv4 якщо не передано -6)
        Set<String> targetPrefixes = ConcurrentHashMap.newKeySet();
        AtomicInteger dbFailures = new AtomicInteger();
        // Скасування прогону — не збій БД: рахуємо окремо, щоб не рапортувати
        // «недоступна БД» там, де користувач просто натиснув «зупинити»
        AtomicBoolean interrupted = new AtomicBoolean();
        try (ExecutorService executor = VirtualExecutor.create("discover")) {
            Semaphore dbLimit = ASBlockWar.DB_LIMIT;
            aggressorAsnResources.keySet().forEach(asn -> executor.execute(() -> {
                try {
                    dbLimit.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted.set(true);
                    return;
                }
                try {
                    retrieveRouteOriginPrefixes retriever = new retrieveRouteOriginPrefixes(asn);
                    if (retriever.isFailed()) {
                        dbFailures.incrementAndGet();
                        return;
                    }
                    retriever.get().stream()
                            .filter(p -> ipv6 || !p.contains(":"))
                            // Поля route:/route6: пишуть оператори ворожих AS, а звідси
                            // значення потрапляє у war.blackbgp.txt, який виконується
                            // як shell-скрипт. Це межа довіри, не косметика.
                            .filter(p -> NetworkUtils.isValidPrefix(p, "RPSL route " + asn))
                            .forEach(targetPrefixes::add);
                } finally {
                    dbLimit.release();
                }
            }));
        }

        // Часткова помилка так само небезпечна, як повна: недоотримані префікси
        // потраплять у toDelete і знімуть блокування з мереж, які лишились ворожими.
        if (interrupted.get()) {
            throw new IOException("discoverBlackbgpChanges: обробку перервано — "
                    + "генерацію diff скасовано");
        }
        if (dbFailures.get() > 0) {
            throw new IOException("discoverBlackbgpChanges: " + dbFailures.get() + " з "
                    + aggressorAsnResources.size() + " запитів префіксів завершились помилкою — "
                    + "генерацію diff скасовано, щоб не зняти блокування з ворожих мереж");
        }

        // ForceNETBlock: примусово додаємо до цілі незалежно від БД
        // Нормалізуємо: хост без префіксу → /32 (IPv4) або /128 (IPv6)
        ASBlockWar.config.getForceNetBlock().stream()
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .map(p -> p.contains("/") ? p : (p.contains(":") ? p + "/128" : p + "/32"))
                .filter(p -> ipv6 || !p.contains(":"))
                // Значення потрапляє прямо в команду роутера, тож одрук у конфізі
                // («10.0.0.0/8 ; reboot», «300.1.2.3/24») не повинен пройти далі
                .filter(p -> NetworkUtils.isValidPrefix(p, "ForceNetBlock"))
                .forEach(targetPrefixes::add);

        // Запобіжник: порожня ціль при непорожньому поточному стані означає збій БД
        // (retrieveRouteOriginPrefixes ковтає SQLException і повертає порожній список),
        // а не «ворожих маршрутів немає». Без цієї перевірки згенерувався б diff,
        // що знімає ВЕСЬ blackhole.
        if (targetPrefixes.isEmpty() && !currentPrefixes.isEmpty()) {
            throw new IOException("discoverBlackbgpChanges: цільовий набір префіксів порожній "
                    + "при " + currentPrefixes.size() + " поточних у blackbgp — ймовірно недоступна БД; "
                    + "генерацію diff скасовано, щоб не зняти блокування");
        }

        // 3. Диф: видалити = поточні - цільові; додати = цільові - поточні.
        // Порівнюємо канонічні форми, а не сирі рядки: маршрут з роутера і з RPSL
        // може бути записаний по-різному (2001:db8:0::/48 vs 2001:db8::/48) і тоді
        // щоразу потрапляв би одночасно в обидва набори. У командах при цьому
        // лишається вихідна форма — з роутера для видалення, з БД для додавання.
        Map<String, String> currentByKey = PrefixUtils.byCanonical(currentPrefixes);
        Map<String, String> targetByKey = PrefixUtils.byCanonical(targetPrefixes);

        Set<String> toDelete = ConcurrentHashMap.newKeySet();
        currentByKey.forEach((key, original) -> {
            if (!targetByKey.containsKey(key)) {
                toDelete.add(original);
            }
        });

        Set<String> toReplace = new HashSet<>();
        targetByKey.forEach((key, original) -> {
            if (!currentByKey.containsKey(key)) {
                toReplace.add(original);
            }
        });

        // 4. Перевірка маршрутів на видалення: чи не належать вони ворогу?
        Set<String> blocked = FilterAggressor.blockedCountries();
        Map<String, String> newEnemies = new ConcurrentHashMap<>();
        // префікс → "DE (RU)": origin-ASN чистий, але покривний inetnum блокований
        Map<String, String> maskedPrefixes = new ConcurrentHashMap<>();
        if (!toDelete.isEmpty()) {
            try (ExecutorService executor = VirtualExecutor.create("discover")) {
                Semaphore dbLimit = ASBlockWar.DB_LIMIT;
                toDelete.forEach(prefix -> executor.execute(() -> {
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

                        // Перевірка 3: маршрут переоформлено під ASN «чистої» країни,
                        // але покривний inetnum/inet6num належить блокованій?
                        // RPSL не несе country на route:, тож origin-перевірка вище
                        // цього не бачить — власник живе в окремому ланцюжку
                        // inetnum: → org: → organisation:
                        dbLimit.acquire();
                        List<String> inetnumCountries;
                        try {
                            inetnumCountries = new retrieveInetnumCountry(prefix).get();
                        } finally {
                            dbLimit.release();
                        }
                        for (String country : inetnumCountries) {
                            if (blocked.contains(country)) {
                                String originCountry = originCountry(origins, aggressorAsnResources);
                                log.warn("discoverBlackbgpChanges: {} замасковано — origin {} ({}), "
                                        + "але покривний inetnum належить {} — видалення скасовано",
                                        prefix, origins, originCountry, country);
                                maskedPrefixes.put(prefix, originCountry + " (" + country + ")");
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

        if (!maskedPrefixes.isEmpty()) {
            log.warn("discoverBlackbgpChanges: {} маршрутів замасковано під «чисті» ASN — блокування збережено",
                    maskedPrefixes.size());
        }

        return new BlackbgpChanges(toDelete, toReplace, newEnemies, effectivePrefixes, maskedPrefixes);
    }
}
