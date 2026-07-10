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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.ukrcom.asblockwar.ASBlockWar;
import net.ukrcom.asblockwar.serviceStructures.Action;
import net.ukrcom.asblockwar.serviceStructures.ASN;

/**
 * Формує та виводить фінальний звіт про зміни поточного запуску ASBlockWar.
 */
public class Reporter {

    private Reporter() {}

    /**
     * Виводить фінальний звіт про зміни поточного запуску.
     * <p>
     * Формує і логує таблицю у три колонки: вилучені / додані / модифіковані ASN
     * з {@link ASBlockWar#resourcesForVerification}, відсортовані за числовим значенням ASN.
     *
     * @param aggressorAsnResources фінальна карта {@code ASN → RPSL-блок}
     */
    public static void report(Map<String, String> aggressorAsnResources) {
        ASBlockWar.LOGGER.info("Роботу завершено. Всього ASN: {}", aggressorAsnResources.size());

        Comparator<ASN> byAsn = Comparator.comparingLong(a -> Long.parseLong(a.asn().substring(2)));

        List<ASN> removed = ASBlockWar.resourcesForVerification.values().stream()
                .filter(a -> a.action() == Action.remove)
                .sorted(byAsn)
                .toList();
        List<ASN> added = ASBlockWar.resourcesForVerification.values().stream()
                .filter(a -> a.action() == Action.add)
                .sorted(byAsn)
                .toList();
        List<ASN> modified = ASBlockWar.resourcesForVerification.values().stream()
                .filter(a -> a.action() == Action.modify)
                .sorted(byAsn)
                .toList();

        if (!removed.isEmpty() || !added.isEmpty() || !modified.isEmpty()) {
            // "AS4294967295" = 12 chars = "Модифіковано" = 12 chars
            final int COL = 12;
            final String FMT = "%-" + COL + "s │ %-" + COL + "s │ %-" + COL + "s";
            final String SEP
                    = "━".repeat(COL + 1)
                            .concat("┿")
                            .concat("━".repeat(COL + 2))
                            .concat("┿")
                            .concat("━".repeat(COL + 1));

            ASBlockWar.LOGGER.info("");
            ASBlockWar.LOGGER.info(String.format(FMT, "Вилучено", "Додано", "Модифіковано"));
            ASBlockWar.LOGGER.info(String.format(FMT, removed.size(), added.size(), modified.size()));
            ASBlockWar.LOGGER.info(SEP);

            int rows = Math.max(removed.size(), Math.max(added.size(), modified.size()));
            for (int i = 0; i < rows; i++) {
                String r = i < removed.size() ? removed.get(i).asn() : "";
                String a = i < added.size() ? added.get(i).asn() : "";
                String m = i < modified.size() ? modified.get(i).asn() : "";
                ASBlockWar.LOGGER.info(String.format(FMT, r, a, m));
            }
        }
    }
}
