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
import java.sql.SQLException;
import net.ukrcom.asblockwar.Config;

/**
 * Витягує всі route/route6 RPSL-блоки для заданого origin AS.
 * Відповідає виводу whois-lite-local -rro {as}.
 *
 * @author olden
 */
@Slf4j
public class retrieveRouteOriginFull {

    private final Config config;
    private final String origin;
    private final StringBuilder sb = new StringBuilder();

    /**
     * Відкриває з'єднання з БД і завантажує усі route/route6 RPSL-блоки
     * для вказаного origin AS.
     *
     * @param origin позначення автономної системи у форматі {@code "AS12345"}
     */
    public retrieveRouteOriginFull(String origin) {
        this.config = net.ukrcom.asblockwar.ASBlockWar.config;
        this.origin = origin;

        try (Connection conn = RpslDb.open()) {
            loadRoutes(conn);
        } catch (SQLException ex) {
            log.error("Помилка при отриманні RouteOriginFull {}", origin, ex);
        }
    }

    private void loadRoutes(Connection conn) throws SQLException {
        // Один JOIN замість «список маршрутів + запит на кожен»: для AS із сотнями
        // маршрутів це була сотня round-trip'ів до SQLite на кожен ASN
        for (String block : RpslDb.fetchColumn(conn,
                "SELECT r.block FROM rpsl_origin o"
                + " JOIN rpsl r ON r.value = o.route AND r.key IN ('route', 'route6')"
                + " WHERE o.origin = ? ORDER BY o.route", this.origin, "block")) {
            sb.append(block).append("\n");
        }
    }

    /**
     * Повертає конкатенований текст усіх route/route6 RPSL-блоків для вказаного origin.
     *
     * @return рядок RPSL-тексту, або порожній рядок, якщо маршрутів не знайдено
     */
    public String get() {
        log.debug("retrieveRouteOriginFull({}).get(): {} chars", origin, sb.length());
        return sb.toString();
    }
}
