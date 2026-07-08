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

/**
 * Повертає список origin-ASN для заданого prefix (route/route6).
 *
 * Містить EXISTS-фільтр по таблиці rpsl — повертаємо лише ті origins,
 * для яких існує актуальний RPSL-блок. Orphan-записи з rpsl_origin (без
 * відповідного блоку в rpsl) ігноруються: маршрут, у якого немає живого
 * блоку, є застарілим і має бути видалений з blackbgp незалежно від того,
 * чи є orphan-посилання на ворожий AS.
 *
 * Це забезпечує симетрію з retrieveRouteOriginPrefixes, який будує
 * targetPrefixes з тим самим EXISTS-фільтром.
 *
 * @author olden
 */
public class retrieveRouteOrigins {

    private static final String SQL =
            "SELECT DISTINCT o.origin FROM rpsl_origin o"
            + " WHERE o.route=?"
            + " AND EXISTS ("
            + "   SELECT 1 FROM rpsl r"
            + "   WHERE r.key IN ('route','route6') AND r.value=o.route"
            + " )"
            + " ORDER BY o.origin";

    private final List<String> origins = new ArrayList<>();

    public retrieveRouteOrigins(String route) {
        try (Connection conn = DriverManager.getConnection(
                net.ukrcom.asblockwar.ASBlockWar.config.getWhoisLiteLocalURI())) {
            try (PreparedStatement stmt = conn.prepareStatement(SQL)) {
                stmt.setString(1, route);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    origins.add(rs.getString("origin").toUpperCase());
                }
            }
        } catch (SQLException ex) {
            net.ukrcom.asblockwar.ASBlockWar.LOGGER
                    .error("Помилка при отриманні origins для {}", route, ex);
        }
    }

    public List<String> get() {
        return origins;
    }
}
