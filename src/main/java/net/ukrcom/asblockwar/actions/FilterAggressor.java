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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.ukrcom.asblockwar.ASBlockWar;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.serviceStructures.Action;
import net.ukrcom.asblockwar.serviceStructures.ASN;

/**
 * Фільтрація карти ворожих ASN за країною та RPSL-патерном агресора.
 */
@Slf4j
public class FilterAggressor {

    private static final Pattern COUNTRY_PATTERN =
            Pattern.compile("(?im)^country:\\s*([A-Z]{2,3})\\b");

    private FilterAggressor() {}

    private static boolean isCountryBlocked(String rpsl, Set<String> blocked) {
        Matcher m = COUNTRY_PATTERN.matcher(rpsl);
        while (m.find()) {
            if (blocked.contains(m.group(1).toUpperCase())) {
                return true;
            }
        }
        return false;
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
     * Повертає {@code true}, якщо RPSL-блок одночасно:
     * <ol>
     *   <li>містить {@code country:} з кодом зі списку {@code blocked}</li>
     *   <li>відповідає {@link ASBlockWar#AGGRESSOR_COMPILED} (налаштовується через {@code AggressorPattern})</li>
     * </ol>
     * Використовується як єдина точка прийняття рішення «ворог чи ні».
     *
     * @param rpsl    повний RPSL-блок AS
     * @param blocked множина кодів країн для блокування (результат {@link #blockedCountries()})
     * @return {@code true} якщо AS слід заблокувати
     */
    public static boolean isAggressor(String rpsl, Set<String> blocked) {
        return isCountryBlocked(rpsl, blocked)
                && ASBlockWar.AGGRESSOR_COMPILED.matcher(rpsl).find();
    }

    /**
     * Фільтрує карту ASN: спочатку обов'язкова перевірка країни (BlockCountry),
     * потім перевірка {@link ASBlockWar#AGGRESSOR_COMPILED} (патерн з {@code AggressorPattern}).
     * <p>
     * Відфільтровані ASN реєструються у {@link ASBlockWar#resourcesForVerification} з дією {@link Action#remove}.
     *
     * @param aggressorAsnResources вхідна карта {@code ASN → RPSL-блок}
     * @return нова карта, що містить тільки підтверджені ворожі ASN
     */
    public static Map<String, String> filterAggressorAsnResources(Map<String, String> aggressorAsnResources) {
        Set<String> blocked = blockedCountries();

        return aggressorAsnResources.entrySet().parallelStream()
                .filter(entry -> {
                    if (isAggressor(entry.getValue(), blocked)) {
                        return true;
                    }
                    if (!isCountryBlocked(entry.getValue(), blocked)) {
                        log.warn("Вилучено (country не в блокованих {}): {}", blocked, entry.getKey());
                    } else {
                        log.warn("Вилучено елемент (pattern не збігається): {}", entry.getKey());
                    }
                    ASBlockWar.resourcesForVerification.put(
                            entry.getKey(),
                            new ASN(Action.remove, entry.getKey(), entry.getValue())
                    );
                    return false;
                })
                .collect(Collectors.toConcurrentMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }
}
