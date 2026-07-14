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
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.serviceStructures.Action;
import net.ukrcom.asblockwar.serviceStructures.ASN;
import net.ukrcom.asblockwar.serviceStructures.SuspiciousAS;

/**
 * Формує та виводить фінальний звіт про зміни поточного запуску ASBlockWar.
 */
@Slf4j
public class Reporter {

    private Reporter() {
    }

    /**
     * Виводить фінальний звіт про зміни поточного запуску.
     * <p>
     * Формує і логує таблицю у три колонки: вилучені / додані / модифіковані ASN
     * з {@link ASBlockWar#resourcesForVerification}, відсортовані за числовим значенням ASN.
     *
     * @param aggressorAsnResources фінальна карта {@code ASN → RPSL-блок}
     */
    public static void report(Map<String, String> aggressorAsnResources) {
        log.info("Роботу завершено. Всього ASN: {}", aggressorAsnResources.size());

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

            log.info("");
            log.info(String.format(FMT, "Вилучено", "Додано", "Модифіковано"));
            log.info(String.format(FMT, removed.size(), added.size(), modified.size()));
            log.info(SEP);

            int rows = Math.max(removed.size(), Math.max(added.size(), modified.size()));
            for (int i = 0; i < rows; i++) {
                String r = i < removed.size() ? removed.get(i).asn() : "";
                String a = i < added.size() ? added.get(i).asn() : "";
                String m = i < modified.size() ? modified.get(i).asn() : "";
                log.info(String.format(FMT, r, a, m));
            }
        }

        List<SuspiciousAS> suspicious = ASBlockWar.suspiciousAsnResources.values().stream()
                .sorted(Comparator.comparingLong(s -> Long.parseLong(s.asn().substring(2))))
                .toList();

        if (!suspicious.isEmpty()) {
            final String H_ASN     = "ASN";
            final String H_COUNTRY = "Країна";
            final String H_MATCH   = "Збіг з AggressorPattern";

            int colAsn     = Math.max(H_ASN.length(),
                    suspicious.stream().mapToInt(s -> s.asn().length()).max().orElse(0));
            int colCountry = Math.max(H_COUNTRY.length(),
                    suspicious.stream().mapToInt(s -> s.country().length()).max().orElse(0));
            int colMatch   = Math.max(H_MATCH.length(),
                    suspicious.stream().mapToInt(s -> s.matchedLine().length()).max().orElse(0));

            String fmt = "%-" + colAsn + "s │ %-" + colCountry + "s │ %-" + colMatch + "s";
            String sep = "━".repeat(colAsn + 1)
                    + "┿"
                    + "━".repeat(colCountry + 2)
                    + "┿"
                    + "━".repeat(colMatch + 1);

            log.info("");
            log.info("Підозрілі AS поза BlockCountry — збіг з AggressorPattern ({}):", suspicious.size());
            log.info(String.format(fmt, H_ASN, H_COUNTRY, H_MATCH));
            log.info(sep);
            for (SuspiciousAS s : suspicious) {
                log.info(String.format(fmt, s.asn(), s.country(), s.matchedLine()));
            }
        }
    }
}
