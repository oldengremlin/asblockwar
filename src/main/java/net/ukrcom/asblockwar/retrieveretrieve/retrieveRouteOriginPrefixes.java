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

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Повертає список prefix-рядків (route/route6) для заданого origin AS.
 * Легший аналог retrieveRouteOriginFull — без зайвого RPSL-тексту.
 *
 * Тільки маршрути, для яких існує фактичний RPSL-блок у таблиці rpsl
 * (ідентично поведінці retrieveRouteOriginFull — сирітські записи з
 * rpsl_origin без відповідного блоку у rpsl не потрапляють у результат).
 *
 * @author olden
 */
@Slf4j
public class retrieveRouteOriginPrefixes {

    private static final String SQL
            = "SELECT o.route FROM rpsl_origin o"
            + " WHERE o.origin=?"
            + " AND EXISTS ("
            + "   SELECT 1 FROM rpsl r"
            + "   WHERE r.key IN ('route','route6') AND r.value=o.route"
            + " )"
            + " ORDER BY o.route";

    private final List<String> prefixes = new ArrayList<>();

    /**
     * Відкриває з'єднання з БД і завантажує список префіксів для вказаного origin AS.
     * Фільтрує сирітські записи: повертаються лише ті маршрути, для яких існує
     * актуальний RPSL-блок у таблиці {@code rpsl}.
     *
     * @param origin позначення автономної системи у форматі {@code "AS12345"}
     */
    public retrieveRouteOriginPrefixes(String origin) {
        try (Connection conn = DriverManager.getConnection(
                net.ukrcom.asblockwar.ASBlockWar.config.getWhoisLiteLocalURI())) {
            try (PreparedStatement stmt = conn.prepareStatement(SQL)) {
                stmt.setString(1, origin);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    prefixes.add(rs.getString("route"));
                }
            }
        } catch (SQLException ex) {
            log.error("Помилка при отриманні prefixes для {}", origin, ex);
        }
    }

    /**
     * Повертає список CIDR-префіксів (route/route6) для вказаного origin AS.
     *
     * @return список CIDR-рядків, відсортованих за маршрутом;
     *         порожній список, якщо маршрутів не знайдено або трапилася помилка
     */
    public List<String> get() {
        return prefixes;
    }
}
