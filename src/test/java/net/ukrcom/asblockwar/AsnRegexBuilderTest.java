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
package net.ukrcom.asblockwar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Тести trie-оптимізатора regex для Juniper as-path.
 * <p>
 * Ключова властивість: згенерований regex має бути валідним і матчити
 * <b>рівно</b> той набір ASN, який до нього додали. Найнебезпечніший клас
 * дефектів — набори, де один ASN є префіксом іншого ({@code 219} і {@code 21907}):
 * саме там втрачалися дужки і {@code ?} прив'язувався до останнього символу.
 */
class AsnRegexBuilderTest {

    /** Компілює побудований regex у якорений патерн; падає з поясненням, якщо він невалідний. */
    private static Pattern compile(List<Long> asns) {
        AsnRegexBuilder builder = new AsnRegexBuilder();
        asns.forEach(builder::add);
        String regex = builder.build();
        try {
            return Pattern.compile("^(" + regex + ")$");
        } catch (PatternSyntaxException e) {
            return fail("Невалідний regex для " + asns + ": «" + regex + "» — " + e.getDescription());
        }
    }

    /** Перевіряє, що regex матчить усі задані ASN і жоден сторонній у діапазоні 1..30000. */
    private static void assertMatchesExactly(List<Long> asns) {
        Pattern p = compile(asns);
        Set<Long> expected = new HashSet<>(asns);
        for (long asn : asns) {
            assertTrue(p.matcher(Long.toString(asn)).matches(),
                    () -> "ASN " + asn + " з набору " + asns + " не матчиться");
        }
        for (long v = 1; v <= 30_000; v++) {
            if (!expected.contains(v)) {
                long candidate = v;
                assertFalse(p.matcher(Long.toString(v)).matches(),
                        () -> "Сторонній ASN " + candidate + " хибно матчиться набором " + asns);
            }
        }
    }

    @Test
    @DisplayName("Приклад із Javadoc стискається до очікуваного вигляду")
    void javadocExampleStaysOptimal() {
        AsnRegexBuilder b = new AsnRegexBuilder();
        List.of(219407L, 219413L, 219445L, 219470L, 219529L).forEach(b::add);
        assertEquals("219(4(07|13|45|70)|529)", b.build());
    }

    @Test
    @DisplayName("Порожній trie дає порожній regex")
    void emptyBuilderYieldsEmptyRegex() {
        assertEquals("", new AsnRegexBuilder().build());
    }

    @ParameterizedTest(name = "префіксний набір: {0}")
    @CsvSource({
        "219,21907",
        "287,287558",
        "8012,80126",
        "3,30",
        "1,10"
    })
    @DisplayName("ASN, що є префіксом іншого, не губиться і не тягне сторонніх")
    void prefixPairsMatchExactly(long shorter, long longer) {
        assertMatchesExactly(List.of(shorter, longer));
    }

    @Test
    @DisplayName("Ланцюжок вкладених префіксів (1,10,100,1000) дає валідний regex")
    void nestedPrefixChainIsValid() {
        assertMatchesExactly(List.of(1L, 10L, 100L, 1000L));
    }

    @Test
    @DisplayName("Три рівні префіксів (8012,80126,801260)")
    void tripleNestedPrefixes() {
        assertMatchesExactly(List.of(8012L, 80126L, 801260L));
    }

    @Test
    @DisplayName("Одиночний ASN")
    void singleAsn() {
        assertMatchesExactly(List.of(64512L));
    }

    @Test
    @DisplayName("Брутфорс: 500 префіксно-насичених наборів матчать рівно свій вміст")
    void randomPrefixSaturatedSetsMatchExactly() {
        Random rnd = new Random(20260803L);
        for (int iteration = 0; iteration < 500; iteration++) {
            long seed = 1 + rnd.nextInt(3000);
            Set<Long> set = new TreeSet<>();
            set.add(seed);
            for (int k = 0; k < 6; k++) {
                long v = switch (rnd.nextInt(3)) {
                    case 0 -> seed * 10 + rnd.nextInt(10);   // подовжений префікс
                    case 1 -> seed / 10;                     // обрізаний префікс
                    default -> 1 + rnd.nextInt(30_000);      // випадковий сусід
                };
                if (v >= 1) {
                    set.add(v);
                }
            }
            assertMatchesExactly(new ArrayList<>(set));
        }
    }
}
