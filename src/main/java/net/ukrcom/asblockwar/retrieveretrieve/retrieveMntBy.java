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
import net.ukrcom.asblockwar.Config;

/**
 * Витягує всі aut-num та as-set RPSL-блоки, що обслуговуються заданим mnt-by дескриптором.
 *
 * <p>Пошук виконується через таблицю {@code rpsl_mntby}, потім завантажуються
 * відповідні RPSL-блоки з таблиці {@code rpsl}.
 *
 * @author olden
 */
@Slf4j
public class retrieveMntBy {

    private final Config config;
    private StringBuilder sb;

    private final String mntBy;

    private Connection conn;

    /**
     * Відкриває з'єднання з БД і завантажує усі RPSL-блоки, обслуговувані вказаним мантейнером.
     *
     * @param mntBy назва mnt-by (наприклад, {@code "MNTNER-UA"})
     */
    public retrieveMntBy(String mntBy) {
        this.config = net.ukrcom.asblockwar.ASBlockWar.config;
        this.sb = new StringBuilder();

        this.mntBy = mntBy;

        try (Connection connection = DriverManager.getConnection(this.config.getWhoisLiteLocalURI());) {
            this.conn = connection;

            this.loadMntBy();

        } catch (SQLException ex) {
            log.error("Помилка при отриманні Organisation", ex);
        }
    }

    /**
     * Повертає конкатенований текст усіх RPSL-блоків, обслуговуваних вказаним мантейнером.
     *
     * @return рядок з RPSL-блоками, розділеними порожніми рядками;
     *         порожній рядок, якщо мантейнер не знайдено або трапилася помилка
     */
    public String get() {
        log.debug("retrieveMntBy({}).get(): {}", this.mntBy, this.sb.toString());
        return this.sb.toString();
    }

    private void loadMntBy() {
        try (PreparedStatement selectStmt = this.conn.prepareStatement(
                "SELECT value FROM rpsl_mntby WHERE key IN (\"aut-num\", \"as-set\") AND mntby = ?"
        );) {

            selectStmt.setString(1, this.mntBy);
            ResultSet rs = selectStmt.executeQuery();
            while (rs.next()) {
                getMntByBlock(rs.getString("value"));
                this.sb.append("\n");
            }

        } catch (SQLException ex) {
            log.error("Помилка при отриманні aut-num для Organisation", ex);
        }
    }

    private void getMntByBlock(String mntByValue) {
        try (PreparedStatement selectStmt = this.conn.prepareStatement(
                "SELECT block FROM rpsl WHERE key IN (\"aut-num\", \"as-set\") AND value=?"
        );) {

            selectStmt.setString(1, mntByValue);
            ResultSet rs = selectStmt.executeQuery();
            while (rs.next()) {
                this.sb.append(rs.getString("block"));
                this.sb.append("\n");
            }

        } catch (SQLException ex) {
            log.error("Помилка при отриманні aut-num/as-set для MntBy", ex);
        }
    }
}
