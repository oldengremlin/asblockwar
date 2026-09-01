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
package net.ukrcom.asblockwar.retrieveretrieve;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import net.ukrcom.asblockwar.ASBlockWar;
import net.ukrcom.asblockwar.Config;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тести пошуку країни через покривні {@code inetnum}/{@code inet6num}.
 * <p>
 * Дані відтворюють реальний випадок {@code 5.231.231.0/24}: маршрут
 * переоформили під ASN німецького хостера, сам {@code inetnum} заявляє
 * {@code country: FI}, а організація, на яку він посилається — {@code country: RU}.
 * <p>
 * Схема {@code rpsl_net} — актуальна, без {@code masklen}: довжина маски
 * належить окремому CIDR-блоку, а не об'єкту, тож whois-lite-local її прибрав,
 * а пошук ведеться точним збігом за обома межами блоку.
 */
class RetrieveInetnumCountryTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUpDatabase() throws SQLException, java.io.IOException {
        Path db = tempDir.resolve("inetnum-test.db");
        String url = "jdbc:sqlite:" + db;

        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE rpsl (id INTEGER PRIMARY KEY, key TEXT,"
                    + " value TEXT COLLATE NOCASE, block TEXT)");
            st.execute("CREATE TABLE rpsl_net (id INTEGER PRIMARY KEY, key TEXT,"
                    + " value TEXT COLLATE NOCASE, version INTEGER,"
                    + " firstip TEXT, lastip TEXT)");

            // Замаскована мережа: inetnum каже FI, організація — RU
            insertNet(st, "inetnum", "5.231.231.0 - 5.231.231.255", 4,
                    "5.231.231.0", "5.231.231.255");
            insertRpsl(st, "inetnum", "5.231.231.0 - 5.231.231.255",
                    "inetnum:        5.231.231.0 - 5.231.231.255\n"
                    + "netname:        Morni-Network\n"
                    + "country:        FI\n"
                    + "org:            ORG-MN228-RIPE\n");
            insertRpsl(st, "organisation", "ORG-MN228-RIPE",
                    "organisation:   ORG-MN228-RIPE\n"
                    + "org-name:       Morni Network\n"
                    + "country:        RU\n");

            // Ширший покривний блок хостера — чесний DE
            insertNet(st, "inetnum", "5.230.0.0 - 5.231.255.255", 4,
                    "5.230.0.0", "5.231.255.255");
            insertRpsl(st, "inetnum", "5.230.0.0 - 5.231.255.255",
                    "inetnum:        5.230.0.0 - 5.231.255.255\n"
                    + "country:        DE\n"
                    + "org:            ORG-GG3-RIPE\n");
            insertRpsl(st, "organisation", "ORG-GG3-RIPE",
                    "organisation:   ORG-GG3-RIPE\n"
                    + "org-name:       GHOSTnet GmbH\n"
                    + "country:        DE\n");

            // Заглушка RIPE на весь адресний простір — покриває будь-яку адресу
            insertNet(st, "inetnum", "0.0.0.0 - 255.255.255.255", 4,
                    "0.0.0.0", "255.255.255.255");
            insertRpsl(st, "inetnum", "0.0.0.0 - 255.255.255.255",
                    "inetnum:        0.0.0.0 - 255.255.255.255\n"
                    + "netname:        IANA-BLK\n"
                    + "country:        EU\n");
        }

        ASBlockWar.config = new Config(new String[]{"--whois-uri", url});
    }

    @BeforeEach
    void clearCache() {
        // Клас кешує за префіксом, а тести звертаються до тих самих
        RpslCache.clearAll();
    }

    private static void insertNet(Statement st, String key, String value, int version,
            String firstip, String lastip) throws SQLException {
        st.execute(String.format(
                "INSERT INTO rpsl_net (key,value,version,firstip,lastip)"
                + " VALUES ('%s','%s',%d,'%s','%s')",
                key, value, version, ipv4(firstip), ipv4(lastip)));
    }

    private static void insertRpsl(Statement st, String key, String value, String block)
            throws SQLException {
        st.execute(String.format("INSERT INTO rpsl (key,value,block) VALUES ('%s','%s','%s')",
                key, value, block.replace("'", "''")));
    }

    /** Десяткове подання IPv4, доповнене нулями до 40 символів — формат whois-lite-local. */
    private static String ipv4(String addr) {
        String[] parts = addr.split("\\.");
        long value = 0;
        for (String part : parts) {
            value = (value << 8) | Integer.parseInt(part);
        }
        String decimal = Long.toString(value);
        return "0".repeat(40 - decimal.length()) + decimal;
    }

    @Test
    @DisplayName("Замаскована мережа: RU знаходиться через organisation, попри country: FI")
    void maskedNetworkExposesRealCountry() {
        List<String> countries = new retrieveInetnumCountry("5.231.231.0/24").get();

        // Найточніший покривний об'єкт: його country, потім країна його org
        assertEquals(List.of("FI", "RU"), countries);
        assertTrue(countries.contains("RU"),
                "RU має знайтися через organisation, на яку посилається inetnum");
    }

    @Test
    @DisplayName("Береться лише найточніший покривний об'єкт — DE ширшого блоку не домішується")
    void onlyMostSpecificObjectIsUsed() {
        assertFalse(new retrieveInetnumCountry("5.231.231.0/24").get().contains("DE"),
                "країна ширшого покривного inetnum не має потрапляти до результату");

        RpslCache.clearAll();
        // Адреса поза точнішим блоком — тепер найточнішим стає сам блок хостера
        assertEquals(List.of("DE"), new retrieveInetnumCountry("5.230.7.0/24").get());
    }

    @Test
    @DisplayName("Перевірка спрацьовує і для окремої адреси всередині мережі")
    void singleAddressInsideNetwork() {
        assertEquals(List.of("FI", "RU"),
                new retrieveInetnumCountry("5.231.231.7/32").get());
    }

    @Test
    @DisplayName("Адреса, яку покриває лише заглушка 0.0.0.0/0 — результат порожній")
    void wholeAddressSpacePlaceholderIsIgnored() {
        assertEquals(List.of(), new retrieveInetnumCountry("8.8.8.0/24").get(),
                "«весь інтернет» не є відповіддю про приналежність мережі");
    }

    @Test
    @DisplayName("Рішення про блокування: замаскована попадається, чиста — ні")
    void blockingDecision() {
        Set<String> blocked = Set.of("RU");

        assertTrue(new retrieveInetnumCountry("5.231.231.0/24").get().stream()
                .anyMatch(blocked::contains), "замаскована мережа має лишитися заблокованою");

        RpslCache.clearAll();
        assertFalse(new retrieveInetnumCountry("5.230.7.0/24").get().stream()
                .anyMatch(blocked::contains), "чесна мережа хостера не блокується");
    }

    @Test
    @DisplayName("Некоректний префікс не кидає виняток")
    void malformedPrefixIsTolerated() {
        assertEquals(List.of(), new retrieveInetnumCountry("не-адреса/24").get());
    }
}
