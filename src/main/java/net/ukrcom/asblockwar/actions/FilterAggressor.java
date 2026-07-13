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

import java.util.Arrays;
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
     * Фільтрує карту ASN: спочатку обов'язкова перевірка країни (BlockCountry),
     * потім перевірка {@link ASBlockWar#AGGRESSOR_COMPILED}.
     * <p>
     * Відфільтровані ASN реєструються у {@link ASBlockWar#resourcesForVerification} з дією {@link Action#remove}.
     *
     * @param aggressorAsnResources вхідна карта {@code ASN → RPSL-блок}
     * @return нова карта, що містить тільки підтверджені ворожі ASN
     */
    public static Map<String, String> filterAggressorAsnResources(Map<String, String> aggressorAsnResources) {
        Set<String> blocked = Arrays.stream(ASBlockWar.config.getBlockCountry().split(","))
                .map(s -> s.trim().toUpperCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        return aggressorAsnResources.entrySet().parallelStream()
                .filter(entry -> {
                    if (!isCountryBlocked(entry.getValue(), blocked)) {
                        ASBlockWar.resourcesForVerification.put(
                                entry.getKey(),
                                new ASN(Action.remove, entry.getKey(), entry.getValue())
                        );
                        log.warn("Вилучено (country не в блокованих {}): {}", blocked, entry.getKey());
                        return false;
                    }
                    if (ASBlockWar.AGGRESSOR_COMPILED.matcher(entry.getValue()).find()) {
                        return true;
                    }
                    ASBlockWar.resourcesForVerification.put(
                            entry.getKey(),
                            new ASN(Action.remove, entry.getKey(), entry.getValue())
                    );
                    log.warn("Вилучено елемент: {}", entry.getKey());
                    return false;
                })
                .collect(Collectors.toConcurrentMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }
}
