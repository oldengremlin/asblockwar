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
import net.ukrcom.asblockwar.Config;
import org.slf4j.Logger;

/**
 *
 * @author olden
 */
public class retrieveMntBy {

    private final Config config;
    private final Logger logger;
    private StringBuilder sb;

    private final String mntBy;

    private Connection conn;

    public retrieveMntBy(String mntBy) {
        this.config = net.ukrcom.asblockwar.ASBlockWar.config;
        this.logger = net.ukrcom.asblockwar.ASBlockWar.LOGGER;
        this.sb = new StringBuilder();

        this.mntBy = mntBy;

        try (Connection connection = DriverManager.getConnection(this.config.getWhoisLiteLocalURI());) {
            this.conn = connection;

            this.loadMntBy();

        } catch (SQLException ex) {
            this.logger.error("Помилка при отриманні Organisation", ex);
        }
    }

    public String get() {
        logger.debug("retrieveMntBy({}).get(): {}", this.mntBy, this.sb.toString());
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
            this.logger.error("Помилка при отриманні aut-num для Organisation", ex);
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
            this.logger.error("Помилка при отриманні aut-num/as-set для MntBy", ex);
        }
    }
}
