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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.ukrcom.asblockwar.ASBlockWar;

/**
 * Спільний доступ до бази whois-lite-local.
 * <p>
 * До появи цього класу кожен з 13 {@code retrieve*}-класів відкривав з'єднання
 * власноруч через {@code DriverManager.getConnection(uri)} без жодних параметрів,
 * а запит {@code SELECT block FROM rpsl WHERE key=? AND value=?} був продубльований
 * у восьми місцях у трьох різних варіантах написання. Централізація дає:
 * <ul>
 *   <li>{@code busy_timeout} — десятки віртуальних потоків читають БД паралельно,
 *       і без нього конкурентний {@code SQLITE_BUSY} відразу давав помилку;</li>
 *   <li>режим read-only — застосунок лише читає whois-lite-local, тож випадковий
 *       запис має бути неможливим;</li>
 *   <li>єдине місце для зміни SQL при зміні схеми БД.</li>
 * </ul>
 */
public final class RpslDb {

    /** Скільки чекати зняття блокування БД, перш ніж повернути SQLITE_BUSY. */
    private static final String BUSY_TIMEOUT_MS = "15000";

    private RpslDb() {
    }

    /**
     * Відкриває з'єднання з whois-lite-local у режимі лише для читання.
     *
     * @return нове з'єднання; викликач зобов'язаний закрити його
     * @throws SQLException якщо БД недоступна
     */
    public static Connection open() throws SQLException {
        Properties props = new Properties();
        props.setProperty("busy_timeout", BUSY_TIMEOUT_MS);
        props.setProperty("open_mode", "1");   // SQLITE_OPEN_READONLY
        return DriverManager.getConnection(ASBlockWar.config.getWhoisLiteLocalURI(), props);
    }

    /**
     * Зчитує та конкатенує всі RPSL-блоки з таблиці {@code rpsl} за значенням
     * і одним чи кількома ключами.
     *
     * @param conn  відкрите з'єднання
     * @param value значення поля {@code value} (ASN, назва AS-SET, mntner тощо)
     * @param keys  один або кілька ключів RPSL ({@code aut-num}, {@code as-set}, …)
     * @return конкатенація знайдених блоків; порожній рядок, якщо нічого не знайдено
     * @throws SQLException якщо запит не вдався — <b>навмисно не глушиться</b>,
     *         інакше збій БД неможливо відрізнити від «запису немає»
     */
    public static String fetchBlocks(Connection conn, String value, String... keys) throws SQLException {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT block FROM rpsl WHERE key IN (" + placeholders(keys.length) + ") AND value=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int i = 1;
            for (String key : keys) {
                stmt.setString(i++, key);
            }
            stmt.setString(i, value);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    sb.append(rs.getString("block"));
                }
            }
        }
        return sb.toString();
    }

    /**
     * Варіант {@link #fetchBlocks} для ключів за шаблоном ({@code route%} —
     * і {@code route}, і {@code route6}).
     *
     * @param conn       відкрите з'єднання
     * @param value      значення поля {@code value}
     * @param keyPattern LIKE-шаблон ключа
     * @return конкатенація знайдених блоків
     * @throws SQLException якщо запит не вдався
     */
    public static String fetchBlocksLike(Connection conn, String value, String keyPattern) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT block FROM rpsl WHERE key LIKE ? AND value=?")) {
            stmt.setString(1, keyPattern);
            stmt.setString(2, value);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    sb.append(rs.getString("block"));
                }
            }
        }
        return sb.toString();
    }

    /**
     * Зчитує один текстовий стовпець як список значень.
     *
     * @param conn   відкрите з'єднання
     * @param sql    запит з рівно одним параметром
     * @param param  значення параметра
     * @param column ім'я стовпця результату
     * @return список значень у порядку повернення БД
     * @throws SQLException якщо запит не вдався
     */
    public static List<String> fetchColumn(Connection conn, String sql, String param, String column)
            throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, param);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(column));
                }
            }
        }
        return out;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
