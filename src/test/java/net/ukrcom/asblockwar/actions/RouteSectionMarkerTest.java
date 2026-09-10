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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.ukrcom.asblockwar.ASBlockWar;
import net.ukrcom.asblockwar.Config;
import net.ukrcom.asblockwar.retrieveretrieve.RpslCache;
import net.ukrcom.asblockwar.retrieveretrieve.RpslDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Позначки ✓/✖ у таблицях маршрутів blackbgp.
 * <p>
 * Дані відтворюють реальний випадок зі звіту за 2026-09-10: мережу
 * {@code 45.12.71.0/24} анонсують два origin — {@code AS197309}
 * (RS-Media LLC, RU, у списку блокування) і {@code AS216039}
 * (AntiDDoS-pw / EdgeSec Technologies Limited, GB, не блокується).
 * Мережа блокується цілком; позначка стосується саме AS.
 */
class RouteSectionMarkerTest {

    private static final String PREFIX = "45.12.71.0/24";

    private static final Map<String, String> AGGRESSORS = Map.of("AS197309",
            "aut-num:        AS197309\n"
            + "country:        RU\n"
            + "org-name:       RS-Media LLC\n");

    @TempDir
    static Path tempDir;

    /** Ставиться перед кожним тестом: {@code ASBlockWar.config} глобальний,
     * і сусідній тестовий клас у тій самій JVM перебив би його своєю БД. */
    private static String url;

    @BeforeAll
    static void setUpDatabase() throws SQLException, java.io.IOException {
        url = "jdbc:sqlite:" + tempDir.resolve("routes-test.db");

        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE rpsl (id INTEGER PRIMARY KEY, key TEXT,"
                    + " value TEXT COLLATE NOCASE, block TEXT)");
            st.execute("CREATE TABLE asn (asn INTEGER PRIMARY KEY, country TEXT, name TEXT)");

            // Співанонсувальна AS: у списку блокування її немає, і в STORE/AS/
            // теж — лише в самій БД
            st.execute("INSERT INTO asn (asn,country,name) VALUES (216039,'GB','AntiDDoS-pw')");
            st.execute("INSERT INTO rpsl (key,value,block) VALUES ('aut-num','AS216039',"
                    + "'aut-num:        AS216039" + nl() + "as-name:        AntiDDoS-pw"
                    + nl() + "org:            ORG-ASL78-RIPE" + nl() + "')");
            st.execute("INSERT INTO rpsl (key,value,block) VALUES ('organisation','ORG-ASL78-RIPE',"
                    + "'organisation:   ORG-ASL78-RIPE" + nl()
                    + "org-name:       EdgeSec Technologies Limited" + nl()
                    + "country:        GB" + nl() + "')");
        }
    }

    /** Перенесення рядка всередині SQL-літерала SQLite. */
    private static String nl() {
        return "' || char(10) || '";
    }

    @BeforeEach
    void resetState() throws java.io.IOException {
        ASBlockWar.config = new Config(new String[]{"--whois-uri", url});
        // Пул тримає з'єднання до БД попереднього тестового класу
        RpslDb.closeAll();
        RpslCache.clearAll();
        ASBlockWar.lastRouteOrigins = Map.of(PREFIX, List.of("AS197309", "AS216039"));
        ASBlockWar.lastBlackbgpChanges = new BlackbgpChanges(
                Set.of(), Set.of(PREFIX), Map.of(), Set.of(PREFIX), Map.of());
    }

    private static List<String> rows(String html) {
        Matcher m = Pattern.compile("<tr class=\"row-bgp-(?:add|del)\">(.*?)</tr>").matcher(html);
        return m.results().map(r -> r.group(1)).toList();
    }

    @Test
    @DisplayName("Блокована AS — зелений ✓, мережа звичайним кольором")
    void blockedAsnGetsTick() {
        List<String> rows = rows(EmailReportSender.buildRouteSection(true, AGGRESSORS));

        assertEquals(2, rows.size(), "по рядку на кожен origin");
        String blocked = rows.get(0);
        assertTrue(blocked.contains("AS<b>197309</b>"));
        assertTrue(blocked.contains("mark-yes"), "AS у списку блокування — ✓");
        assertFalse(blocked.contains("prefix-excluded"),
                "мережа в рядку блокованої AS не виділяється");
    }

    @Test
    @DisplayName("Співанонсувальна AS — червоний ✖ і виділена мережа")
    void coOriginGetsCross() {
        String coOrigin = rows(EmailReportSender.buildRouteSection(true, AGGRESSORS)).get(1);

        assertTrue(coOrigin.contains("AS<b>216039</b>"));
        assertTrue(coOrigin.contains("mark-no"), "AS не блокується — ✖");
        assertTrue(coOrigin.contains("<span class=\"prefix-excluded\">" + PREFIX + "</span>"),
                "мережа має бути виділена кольором, а не прихована");
    }

    @Test
    @DisplayName("Мережа повторюється в кожному рядку, а не лише в першому")
    void prefixIsRepeatedInEveryRow() {
        rows(EmailReportSender.buildRouteSection(true, AGGRESSORS))
                .forEach(row -> assertTrue(row.contains(PREFIX),
                        "порожня комірка читалася як «маршрут без адреси»: " + row));
    }

    @Test
    @DisplayName("Країна й організація співанонсувальної AS підтягуються з БД")
    void coOriginDetailsComeFromDatabase() {
        String coOrigin = rows(EmailReportSender.buildRouteSection(true, AGGRESSORS)).get(1);

        assertTrue(coOrigin.contains("GB"), "країна має бути заповнена");
        assertTrue(coOrigin.contains("EdgeSec Technologies Limited"),
                "org-name має підтягуватися, бо в STORE/AS/ такої AS немає");
    }

    @Test
    @DisplayName("Легенда пояснює, що позначка стосується AS, а не мережі")
    void legendExplainsTheMark() {
        String html = EmailReportSender.buildRouteSection(true, AGGRESSORS);
        assertTrue(html.contains("class=\"legend\""), "легенда має бути в таблиці");
    }
}
