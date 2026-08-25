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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.ukrcom.asblockwar.ASBlockWar;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveMntnerFull;
import net.ukrcom.asblockwar.serviceStructures.Action;
import net.ukrcom.asblockwar.serviceStructures.ASN;
import net.ukrcom.asblockwar.serviceStructures.SuspiciousAS;

/**
 * Фільтрація карти ворожих ASN за країною та RPSL-патерном агресора.
 */
@Slf4j
public class FilterAggressor {

    private static final Pattern COUNTRY_PATTERN
            = Pattern.compile("(?im)^country:\\s*([A-Z]{2,3})\\b");

    private FilterAggressor() {
    }

    /**
     * Будує множину кодів країн з поточної конфігурації {@code BlockCountry}.
     *
     * @return незмінна множина кодів країн у верхньому регістрі
     */
    public static Set<String> blockedCountries() {
        return ASBlockWar.config.getBlockCountry().stream()
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    /**
     * Повертає {@code true}, якщо RPSL-блок містить {@code country:} з кодом зі списку {@code blocked}.
     * Належність до BlockCountry є єдиним критерієм блокування AS.
     * AS, що збігаються з {@link ASBlockWar#AGGRESSOR_COMPILED} але не входять до BlockCountry,
     * фіксуються окремо як підозрілі ({@link ASBlockWar#suspiciousAsnResources}).
     *
     * @param rpsl    повний RPSL-блок AS
     * @param blocked множина кодів країн для блокування (результат {@link #blockedCountries()})
     * @return {@code true} якщо AS слід заблокувати
     */
    public static boolean isAggressor(String rpsl, Set<String> blocked) {
        Matcher m = COUNTRY_PATTERN.matcher(rpsl);
        while (m.find()) {
            String country = m.group(1).toUpperCase();
            if (blocked.contains(country)) {
                log.debug("isAggressor: country={} — у BlockCountry → заблоковано", country);
                return true;
            }
            log.debug("isAggressor: country={} — не в BlockCountry", country);
        }
        log.debug("isAggressor: country відсутній або не входить до BlockCountry  → пропущено\n{}", rpsl);
        return false;
    }

    private static String extractCountry(String rpsl) {
        Matcher m = COUNTRY_PATTERN.matcher(rpsl);
        String country = m.find() ? m.group(1).toUpperCase() : "?";
        log.debug("extractCountry: {}", country);
        return country;
    }

    private static String matchedAggressorLine(String rpsl) {
        log.debug("matchedAggressorLine аналізує:\n{}", rpsl);
        Matcher m = ASBlockWar.AGGRESSOR_COMPILED.matcher(rpsl);
        // m.group() — увесь збіг: користувацький патерн не зобов'язаний мати групу захоплення
        String match = m.find() ? m.group().trim() : null;
        log.debug("matchedAggressorLine результат: {}", match != null ? match : "збігів немає");
        return match;
    }

    /**
     * Збагачує RPSL-блок для перевірки на підозрілу AS: до aut-num + org-блоку
     * додає mntner- та role-блоки всіх не-RIPE mnt-by/mnt-ref записів.
     * Це дозволяє {@link #matchedAggressorLine} знаходити збіги AggressorPattern
     * у даних mntner (адреса, телефон, назва організації), які відсутні в
     * GDPR-санованих RPSL-об'єктах.
     * Семафор {@code dbLimit} захищає від неконтрольованого паралельного доступу до БД,
     * оскільки цей метод викликається з паралельного потоку.
     *
     * @param rpsl вихідний RPSL-блок (synthetic header + aut-num + org)
     * @param dbLimit семафор для обмеження паралельних запитів до БД
     * @return розширений блок з доданими mntner/role-блоками
     */
    private static String enrichForSuspiciousCheck(String rpsl, Semaphore dbLimit) {
        StringBuilder enriched = new StringBuilder(rpsl);
        rpsl.lines()
                // matches() вимагає збігу ВСЬОГО рядка, а блоки беруться з БД дослівно —
                // «mnt-by:   FOO-MNT » з кінцевим пробілом мовчки не проходив, і mntner
                // не підтягувався до перевірки на підозрілу AS
                .filter(l -> l.matches("(?i)^mnt-(by|ref):\\s*\\S+\\s*"))
                .map(l -> l.replaceFirst("(?i)^mnt-(?:by|ref):\\s*", "").trim())
                .filter(v -> !v.isEmpty() && !DiscoverAggressor.SERVICE_MNT.matcher(v).matches())
                .distinct()
                .forEach(mnt -> {
                    try {
                        dbLimit.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        String block = new retrieveMntnerFull(mnt).get();
                        if (!block.isEmpty()) {
                            enriched.append("\n").append(block);
                        }
                    } finally {
                        dbLimit.release();
                    }
                });
        return enriched.toString();
    }

    /**
     * Фільтрує карту ASN за критерієм BlockCountry (country з RPSL-блоку).
     * <p>
     * AS, яких country відсутня або не входить до {@code blocked}:
     * <ul>
     *   <li>якщо збігаються з {@link ASBlockWar#AGGRESSOR_COMPILED} — додаються до
     *       {@link ASBlockWar#suspiciousAsnResources} для фінального звіту;</li>
     *   <li>в обох випадках реєструються у {@link ASBlockWar#resourcesForVerification}
     *       з дією {@link Action#remove}.</li>
     * </ul>
     *
     * @param aggressorAsnResources вхідна карта {@code ASN → RPSL-блок}
     * @return нова карта, що містить тільки підтверджені ворожі ASN
     */
    public static Map<String, String> filterAggressorAsnResources(Map<String, String> aggressorAsnResources) {
        Set<String> blocked = blockedCountries();
        Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);
        Map<String, String> confirmed = new ConcurrentHashMap<>();

        // Раніше тут був parallelStream, тобто ForkJoinPool.commonPool. Це давало дві
        // проблеми. По-перше, enrichForSuspiciousCheck ставить прапорець переривання
        // на поточному потоці, а FJ не скидає його між задачами — воркер лишався
        // «перерваним» назавжди, і будь-який наступний блокуючий виклик у JVM на цьому
        // воркері одразу кидав InterruptedException. По-друге, паралелізм commonPool
        // це NCPU-1, тож семафор на 20 не діяв узагалі, а на 1-2 ядрах увесь етап
        // серіалізувався. Віртуальні потоки знімають обидві проблеми.
        try (ExecutorService executor = VirtualExecutor.create("filter")) {
            aggressorAsnResources.forEach((asn, rpsl) -> executor.execute(() -> {
                if (isAggressor(rpsl, blocked)) {
                    confirmed.put(asn, rpsl);
                    return;
                }
                String matched = matchedAggressorLine(enrichForSuspiciousCheck(rpsl, dbLimit));
                if (matched != null) {
                    log.warn("Не в BlockCountry, але AggressorPattern збігається: {}", asn);
                    ASBlockWar.suspiciousAsnResources.put(
                            asn,
                            new SuspiciousAS(asn, extractCountry(rpsl), matched, rpsl)
                    );
                } else {
                    log.warn("Вилучено (country не в блокованих, pattern не збігається): {}", asn);
                }
                ASBlockWar.resourcesForVerification.put(
                        asn,
                        new ASN(Action.remove, asn, rpsl)
                );
            }));
        }
        return confirmed;
    }
}
