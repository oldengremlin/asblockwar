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
public class retrieveAsSet {

    private final Config config;
    private final Logger logger;
    private StringBuilder sb;

    private final String asSet;

    private Connection conn;

    public retrieveAsSet(String asSet) {
        this.config = net.ukrcom.asblockwar.ASBlockWar.config;
        this.logger = net.ukrcom.asblockwar.ASBlockWar.LOGGER;
        this.sb = new StringBuilder();

        this.asSet = asSet;

        try (Connection connection = DriverManager.getConnection(this.config.getWhoisLiteLocalURI());) {
            this.conn = connection;

            this.loadAsSet();

        } catch (SQLException ex) {
            this.logger.error("Помилка при отриманні AsSet", ex);
        }
    }

    public String get() {
        logger.debug("retrieveAsSet({}).get(): {}", this.asSet, this.sb.toString());
        return this.sb.toString();
    }

    private void loadAsSet() {
        try (PreparedStatement selectStmt = this.conn.prepareStatement(
                "SELECT block FROM rpsl WHERE key=? AND value=?"
        );) {

            selectStmt.setString(1, "as-set");
            selectStmt.setString(2, this.asSet);
            ResultSet rs = selectStmt.executeQuery();
            while (rs.next()) {
                String asSetBlock = rs.getString("block");
                this.sb.append(asSetBlock);
            }

        } catch (SQLException ex) {
            this.logger.error("Помилка при отриманні as-set для AsSet", ex);
        }
    }
}
