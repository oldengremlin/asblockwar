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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.ukrcom.asblockwar.Config;
import org.slf4j.Logger;

/**
 *
 * @author olden
 */
public class retrieveOrganisation {

    private final static Map<String, String> cache = new ConcurrentHashMap<>();

    private final Config config;
    private final Logger logger;
    private StringBuilder sb;

    private final String autNum;
    private String autNumBlock;

    private Connection conn;

    /**
     *
     * @param autNum
     */
    public retrieveOrganisation(String autNum) {
        this.config = net.ukrcom.asblockwar.ASBlockWar.config;
        this.logger = net.ukrcom.asblockwar.ASBlockWar.LOGGER;
        this.autNum = autNum;

        if (cache.containsKey(autNum)) {
            logger.debug("retrieveOrganisation({}) — cache hit", autNum);
            return;
        }

        this.sb = new StringBuilder();
        try (Connection connection = DriverManager.getConnection(this.config.getWhoisLiteLocalURI())) {
            this.conn = connection;
            this.loadAsn();
            this.loadOrg();
        } catch (SQLException ex) {
            this.logger.error("Помилка при отриманні Organisation", ex);
        }
        cache.put(autNum, this.sb.toString());
    }

    /**
     *
     * @return
     */
    public String get() {
        String cached = cache.get(this.autNum);
        if (cached != null) {
            logger.debug("retrieveOrganisation({}).get(): [cache]", this.autNum);
            return cached;
        }
        // fallback: значення не потрапило в cache (наприклад, помилка SQL)
        String result = this.sb != null ? this.sb.toString() : "";
        cache.put(this.autNum, result);
        logger.debug("retrieveOrganisation({}).get(): {}", this.autNum, result);
        return result;
    }

    private void loadAsn() {
        try (PreparedStatement selectStmt = this.conn.prepareStatement(
                "SELECT block FROM rpsl WHERE key=? AND value=?"
        );) {

            selectStmt.setString(1, "aut-num");
            selectStmt.setString(2, this.autNum);
            ResultSet rs = selectStmt.executeQuery();
            while (rs.next()) {
                this.autNumBlock = rs.getString("block");
                this.sb.append(getAsn(this.autNum));
            }

        } catch (SQLException ex) {
            this.logger.error("Помилка при отриманні aut-num для Organisation", ex);
        }
    }

    private void loadOrg() {
        if (autNumBlock != null) {
            autNumBlock.lines().forEach(line -> {
                String[] parts = line.split("\\s+", 2);
                if (!(parts.length < 2)) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    if (key.equals("org:")) {
                        this.sb.append(getOrg(value));
                    }
                }
            });
        }
    }

    private String getOrg(String org) {
        StringBuilder retVal = new StringBuilder();

        try (PreparedStatement selectStmt = this.conn.prepareStatement(
                "SELECT block FROM rpsl WHERE key=? AND value=?"
        );) {

            selectStmt.setString(1, "organisation");
            selectStmt.setString(2, org);
            ResultSet rs = selectStmt.executeQuery();
            if (rs.next()) {
                retVal.append(rs.getString("block"));
                retVal.append("\n");
            }

        } catch (SQLException ex) {
            this.logger.error("Помилка при отриманні organisation для Organisation", ex);
        }
        return retVal.toString();
    }

    /**
     *
     * @param as
     * @return
     */
    protected String getAsn(String as) {
        StringBuilder retVal = new StringBuilder();
        String asNum = as.replaceFirst("^[Aa][Ss]", "");
        Integer asn = Integer.valueOf(asNum);

        try (PreparedStatement selectStmt = this.conn.prepareStatement(
                "SELECT country, name FROM asn WHERE asn=?"
        );) {
            selectStmt.setInt(1, asn);
            ResultSet rs = selectStmt.executeQuery();
            while (rs.next()) {
                retVal.append("as-num:         ");
                retVal.append(as.toUpperCase());
                retVal.append("\ncountry:        ");
                retVal.append(rs.getString("country"));
                retVal.append("\nas-name:        ");
                retVal.append(rs.getString("name"));
                retVal.append("\n");
            }
        } catch (SQLException ex) {
            this.logger.error("Помилка при отриманні asn для Organisation", ex);
        }
        return retVal.toString();
    }

}
