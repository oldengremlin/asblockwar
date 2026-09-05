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
import net.ukrcom.asblockwar.ASBlockWar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Збереження замаскованих origin-ASN у {@code STORE/AS/}.
 * <p>
 * Дані відтворюють реальний {@code 94.228.167.0/24}: {@code aut-num AS203273}
 * — {@code NetCrafters OU}, {@code country: EE}, тобто сам по собі цілком
 * легітимний. Ворожа країна є лише в покривному {@code inetnum}, тож без неї
 * файл у {@code STORE/} не пояснював би, за що AS заблоковано.
 */
class MaskedEvidenceTest {

    private static final String AUT_NUM = """
            aut-num:        AS203273
            as-name:        NetCraftersOU
            org:            ORG-NO63-RIPE
            """;

    private static final String EVIDENCE = """
            inetnum:        94.228.167.0 - 94.228.167.255
            netname:        NetCrafters
            country:        RU
            """;

    private static MaskedRoute netCrafters() {
        return new MaskedRoute("AS203273", "RU, EE", "NetCrafters OU", EVIDENCE);
    }

    @AfterEach
    void clearState() {
        ASBlockWar.lastBlackbgpChanges = null;
    }

    @Test
    @DisplayName("Секція з покривним inetnum додається в кінець, aut-num не змінюється")
    void evidenceIsAppendedAfterAutNum() {
        String out = StoreActions.withMaskedEvidence(AUT_NUM,
                Map.of("94.228.167.0/24", netCrafters()));

        assertTrue(out.startsWith(AUT_NUM), "aut-num має лишитися першим і незмінним");
        assertTrue(out.indexOf("inetnum:") > out.indexOf("aut-num:"),
                "секція inetnum має йти після aut-num");
        assertTrue(out.contains("94.228.167.0/24 — RU, EE"),
                "має бути видно, який саме префікс і який ланцюг країн");
        assertTrue(out.contains("country:        RU"),
                "має бути видно джерело ворожої країни");
    }

    @Test
    @DisplayName("Рядки пояснення закоментовані «%» — розбір RPSL їх не зачепить")
    void explanationLinesAreComments() {
        String out = StoreActions.withMaskedEvidence(AUT_NUM,
                Map.of("94.228.167.0/24", netCrafters()));

        out.lines()
                .filter(l -> l.contains("ASBlockWar") || l.contains("94.228.167.0/24 —"))
                .forEach(l -> assertTrue(l.startsWith("%"),
                        "рядок пояснення має починатися з «%»: " + l));
    }

    @Test
    @DisplayName("Кілька маршрутів одного ASN — кожен зі своїм inetnum")
    void severalRoutesOfOneAsn() {
        String out = StoreActions.withMaskedEvidence(AUT_NUM, new java.util.TreeMap<>(Map.of(
                "84.54.55.0/24", new MaskedRoute("AS197719", "RU", "Rusich-TVN LLC",
                        "inetnum:        84.54.55.0 - 84.54.55.255\ncountry:        RU\n"),
                "109.71.158.0/24", new MaskedRoute("AS197719", "RU", "Rusich-TVN LLC",
                        "inetnum:        109.71.158.0 - 109.71.158.255\ncountry:        RU\n"))));

        assertTrue(out.contains("84.54.55.0 - 84.54.55.255"));
        assertTrue(out.contains("109.71.158.0 - 109.71.158.255"));
    }

    @Test
    @DisplayName("Групування: origin без route: і вже ворожий origin пропускаються")
    void groupingSkipsUnknownAndAlreadyHostileOrigins() {
        ASBlockWar.lastBlackbgpChanges = new BlackbgpChanges(
                Set.of(), Set.of(), Map.of(), Set.of(),
                Map.of(
                        "94.228.167.0/24", netCrafters(),
                        // route: прибрано з RIPE — origin невідомий
                        "45.82.152.0/24", new MaskedRoute("", "RU", "", EVIDENCE),
                        // вже у списку ворогів — його пише головний цикл
                        "1.2.3.0/24", new MaskedRoute("AS64500", "RU", "Enemy", EVIDENCE)));

        Map<String, Map<String, MaskedRoute>> byOrigin =
                StoreActions.maskedByOrigin(Map.of("AS64500", "aut-num: AS64500\n"));

        assertEquals(Set.of("AS203273"), byOrigin.keySet());
        assertEquals(Set.of("94.228.167.0/24"), byOrigin.get("AS203273").keySet());
    }

    @Test
    @DisplayName("Без результатів звірки blackbgp групування порожнє")
    void noBlackbgpChanges() {
        assertTrue(StoreActions.maskedByOrigin(Map.of()).isEmpty());
    }

    @Test
    @DisplayName("Порожній aut-num не залишає файл без пояснення")
    void missingAutNumStillCarriesEvidence() {
        String out = StoreActions.withMaskedEvidence("",
                Map.of("94.228.167.0/24", netCrafters()));

        assertFalse(out.isBlank(), "файл має містити хоча б секцію inetnum");
        assertTrue(out.contains("inetnum:"));
    }
}
