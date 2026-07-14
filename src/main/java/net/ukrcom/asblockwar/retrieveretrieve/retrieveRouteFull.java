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

/**
 * Витягує RPSL route/route6 блок за точною адресою мережі.
 *
 * @author olden
 */
@Slf4j
public class retrieveRouteFull {

    private final String prefix;
    private final StringBuilder sb = new StringBuilder();

    /**
     * Відкриває з'єднання з БД і завантажує RPSL-блок(и) для вказаного prefix.
     *
     * @param prefix CIDR-рядок, наприклад {@code "45.140.6.0/32"}
     */
    public retrieveRouteFull(String prefix) {
        this.prefix = prefix;
        try (Connection conn = DriverManager.getConnection(
                net.ukrcom.asblockwar.ASBlockWar.config.getWhoisLiteLocalURI())) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT block FROM rpsl WHERE key LIKE 'route%' AND value=?")) {
                stmt.setString(1, prefix);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    sb.append(rs.getString("block")).append("\n");
                }
            }
        } catch (SQLException ex) {
            log.error("Помилка при отриманні route block для {}", prefix, ex);
        }
    }

    /**
     * Повертає конкатенований текст RPSL-блоків для вказаного prefix.
     *
     * @return рядок RPSL-тексту, або порожній рядок, якщо маршрут не знайдено
     */
    public String get() {
        log.debug("retrieveRouteFull({}).get(): {} chars", prefix, sb.length());
        return sb.toString();
    }
}
