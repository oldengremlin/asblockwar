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
import java.util.stream.Collectors;
import net.ukrcom.asblockwar.ASBlockWar;
import net.ukrcom.asblockwar.serviceStructures.Action;
import net.ukrcom.asblockwar.serviceStructures.ASN;

/**
 * Фільтрація карти ворожих ASN за RPSL-патерном агресора.
 */
public class FilterAggressor {

    private FilterAggressor() {}

    /**
     * Фільтрує карту ASN, залишаючи лише ті, чий RPSL-блок відповідає {@link ASBlockWar#AGGRESSOR_COMPILED}.
     * <p>
     * Відфільтровані ASN реєструються у {@link ASBlockWar#resourcesForVerification} з дією {@link Action#remove}.
     *
     * @param aggressorAsnResources вхідна карта {@code ASN → RPSL-блок}
     * @return нова карта, що містить тільки підтверджені ворожі ASN
     */
    public static Map<String, String> filterAggressorAsnResources(Map<String, String> aggressorAsnResources) {
        return aggressorAsnResources.entrySet().parallelStream()
                .filter(entry -> {
                    if (ASBlockWar.AGGRESSOR_COMPILED.matcher(entry.getValue()).find()) {
                        return true;
                    }
                    ASBlockWar.resourcesForVerification.put(
                            entry.getKey(),
                            new ASN(Action.remove, entry.getKey(), entry.getValue())
                    );
                    ASBlockWar.LOGGER.warn("Вилучено елемент: {}", entry.getKey());
                    return false;
                })
                .collect(Collectors.toConcurrentMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }
}
