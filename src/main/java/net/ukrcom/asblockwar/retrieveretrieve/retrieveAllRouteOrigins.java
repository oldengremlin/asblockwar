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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Одним запитом повертає всю таблицю route→origins з rpsl_origin
 * (тільки маршрути, для яких є живий RPSL-блок у rpsl).
 *
 * Використовується в storeNetworkFiles замість 62K+ індивідуальних
 * retrieveRouteOrigins-запитів.
 *
 * @author olden
 */
@Slf4j
public class retrieveAllRouteOrigins {

    private static final String SQL
            = "SELECT o.route, o.origin FROM rpsl_origin o"
            + " WHERE EXISTS ("
            + "   SELECT 1 FROM rpsl r"
            + "   WHERE r.key IN ('route','route6') AND r.value=o.route"
            + " )"
            + " ORDER BY o.route, o.origin";

    private final Map<String, List<String>> origins = new HashMap<>();

    /**
     * Виконує SQL-запит до бази whois-lite-local і завантажує відображення
     * route → список origin-ASN. Записи без відповідного RPSL-блоку фільтруються.
     */
    public retrieveAllRouteOrigins() {
        try (Connection conn = DriverManager.getConnection(
                net.ukrcom.asblockwar.ASBlockWar.config.getWhoisLiteLocalURI());
             PreparedStatement stmt = conn.prepareStatement(SQL)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                origins.computeIfAbsent(rs.getString("route"), k -> new ArrayList<>())
                        .add(rs.getString("origin").toUpperCase());
            }
        } catch (SQLException ex) {
            log.error("retrieveAllRouteOrigins: помилка при читанні rpsl_origin", ex);
        }
    }

    /**
     * Повертає завантажене відображення route → список origin-ASN.
     *
     * @return незмінна (або змінна HashMap) карта {@code route → List<origin>};
     *         може бути порожньою при помилці SQL
     */
    public Map<String, List<String>> get() {
        return origins;
    }
}
