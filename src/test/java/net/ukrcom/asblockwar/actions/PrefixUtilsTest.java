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

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тести канонізації префіксів — вона визначає, чи маршрут вважається «тим самим»
 * при звірці стану роутера з RPSL-базою.
 */
class PrefixUtilsTest {

    @ParameterizedTest(name = "{0} ≡ {1}")
    @CsvSource({
        "2001:db8:0::/48,      2001:db8::/48",
        "2001:DB8::/32,        2001:db8::/32",
        "2001:0db8:0000::/40,  2001:db8::/40",
        "::1/128,              0:0:0:0:0:0:0:1/128",
        "192.0.2.0/24,         192.0.2.0/24"
    })
    @DisplayName("Різні записи однієї мережі дають однаковий ключ")
    void equivalentFormsShareKey(String a, String b) {
        assertEquals(PrefixUtils.canonical(a.trim()), PrefixUtils.canonical(b.trim()));
    }

    @ParameterizedTest(name = "{0} ≠ {1}")
    @CsvSource({
        "2001:db8::/48,   2001:db8::/32",
        "192.0.2.0/24,    192.0.3.0/24",
        "192.0.2.0/24,    192.0.2.0/25"
    })
    @DisplayName("Різні мережі не злипаються")
    void distinctNetworksDiffer(String a, String b) {
        assertNotEquals(PrefixUtils.canonical(a.trim()), PrefixUtils.canonical(b.trim()));
    }

    @Test
    @DisplayName("byCanonical зберігає вихідну форму запису")
    void byCanonicalKeepsOriginal() {
        Map<String, String> map = PrefixUtils.byCanonical(Set.of("2001:DB8:0::/48"));
        assertTrue(map.containsValue("2001:DB8:0::/48"),
                "у команди роутера має йти вихідна форма, не внутрішній ключ");
    }

    @Test
    @DisplayName("Однакова мережа в різних записах не дає одночасно delete і replace")
    void noChurnForEquivalentForms() {
        Set<String> current = new LinkedHashSet<>(Set.of("2001:db8:0::/48"));   // з роутера
        Set<String> target = new LinkedHashSet<>(Set.of("2001:db8::/48"));      // з RPSL

        Map<String, String> currentByKey = PrefixUtils.byCanonical(current);
        Map<String, String> targetByKey = PrefixUtils.byCanonical(target);

        boolean wouldDelete = currentByKey.keySet().stream().anyMatch(k -> !targetByKey.containsKey(k));
        boolean wouldAdd = targetByKey.keySet().stream().anyMatch(k -> !currentByKey.containsKey(k));

        assertEquals(false, wouldDelete, "маршрут не має видалятися");
        assertEquals(false, wouldAdd, "маршрут не має додаватися повторно");
    }

    @Test
    @DisplayName("Нерозбірливий префікс не кидає виняток")
    void malformedPrefixIsTolerated() {
        assertEquals("не-адреса/24", PrefixUtils.canonical("не-адреса/24"));
        assertEquals("192.0.2.0", PrefixUtils.canonical("192.0.2.0"));
    }
}
