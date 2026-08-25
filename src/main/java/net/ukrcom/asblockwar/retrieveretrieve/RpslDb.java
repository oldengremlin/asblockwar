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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public final class RpslDb {

    /** Скільки чекати зняття блокування БД, перш ніж повернути SQLITE_BUSY. */
    private static final String BUSY_TIMEOUT_MS = "15000";

    /**
     * Пул відкритих з'єднань.
     * <p>
     * За прогін створювалося ~30 000 з'єднань: кожен {@code new retrieve*()} відкривав
     * власне. {@code ThreadLocal} тут не допоміг би — executor створює віртуальний потік
     * на кожну задачу, тож потік і задача це те саме. Розмір пулу дорівнює межі
     * паралельних запитів ({@code MAX_CONCURRENT_DB_QUERIES}): семафор {@code dbLimit}
     * і так не пускає до БД більше потоків, тож більше з'єднань одночасно не потрібно.
     */
    private static final BlockingQueue<Connection> POOL
            = new ArrayBlockingQueue<>(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);

    private RpslDb() {
    }

    /**
     * Видає з'єднання з whois-lite-local у режимі лише для читання.
     * <p>
     * Повертає обгортку, чий {@code close()} віддає з'єднання назад у пул замість
     * фактичного закриття — тож звичний {@code try (Connection c = RpslDb.open())}
     * у викликачів лишається без змін.
     *
     * @return з'єднання; викликач зобов'язаний закрити його (try-with-resources)
     * @throws SQLException якщо БД недоступна
     */
    public static Connection open() throws SQLException {
        Connection pooled = POOL.poll();
        final Connection real = pooled != null ? pooled : createConnection();
        return (Connection) Proxy.newProxyInstance(
                RpslDb.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        // Пул повний (або прогін завершено) — закриваємо по-справжньому
                        if (!POOL.offer(real)) {
                            real.close();
                        }
                        return null;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static Connection createConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("busy_timeout", BUSY_TIMEOUT_MS);
        props.setProperty("open_mode", "1");   // SQLITE_OPEN_READONLY
        return DriverManager.getConnection(ASBlockWar.config.getWhoisLiteLocalURI(), props);
    }

    /**
     * Закриває всі з'єднання пулу.
     * <p>
     * Викликається на початку прогону: у GUI можна запускати обробку кілька разів,
     * і без цього дескриптори попереднього прогону накопичувалися б. Заразом
     * підхоплюється зміна {@code WhoisLiteLocalURI} у налаштуваннях.
     */
    public static void closeAll() {
        Connection c;
        while ((c = POOL.poll()) != null) {
            try {
                c.close();
            } catch (SQLException e) {
                log.debug("RpslDb: не вдалося закрити з'єднання пулу: {}", e.getMessage());
            }
        }
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
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT block FROM rpsl WHERE key IN (" + placeholders(keys.length) + ") AND value=?")) {
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
