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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.ukrcom.asblockwar.Config;

/**
 * Витягує синтетичне резюме ASN (з таблиці {@code asn}) та organisation-блок RPSL,
 * на який посилається поле {@code org:} у aut-num-блоці.
 *
 * <p>Результати кешуються в статичній {@link ConcurrentHashMap} для уникнення
 * повторних запитів до БД при обробці кількох AS одного власника.
 *
 * @author olden
 */
@Slf4j
public class retrieveOrganisation {

    private final static Map<String, String> cache = new ConcurrentHashMap<>();

    private final Config config;
    private StringBuilder sb;

    private final String autNum;
    private String autNumBlock;

    private Connection conn;

    /**
     * Відкриває з'єднання з БД (якщо результат ще не закешовано) і завантажує
     * ASN-резюме та пов'язаний organisation-блок для вказаного aut-num.
     *
     * @param autNum позначення автономної системи у форматі {@code "AS12345"}
     */
    public retrieveOrganisation(String autNum) {
        this.config = net.ukrcom.asblockwar.ASBlockWar.config;
        this.autNum = autNum;

        if (cache.containsKey(autNum)) {
            log.debug("retrieveOrganisation({}) — cache hit", autNum);
            return;
        }

        this.sb = new StringBuilder();
        try (Connection connection = DriverManager.getConnection(this.config.getWhoisLiteLocalURI())) {
            this.conn = connection;
            this.loadAsn();
            this.loadOrg();
        } catch (SQLException ex) {
            log.error("Помилка при отриманні Organisation", ex);
        }
        cache.put(autNum, this.sb.toString());
    }

    /**
     * Повертає закешований текст RPSL для вказаного aut-num (ASN-резюме + org-блок).
     *
     * @return рядок RPSL-тексту, або порожній рядок, якщо AS не знайдено
     */
    public String get() {
        String cached = cache.get(this.autNum);
        if (cached != null) {
            log.debug("retrieveOrganisation({}).get(): [cache]", this.autNum);
            return cached;
        }
        // fallback: значення не потрапило в cache (наприклад, помилка SQL)
        String result = this.sb != null ? this.sb.toString() : "";
        cache.put(this.autNum, result);
        log.debug("retrieveOrganisation({}).get(): {}", this.autNum, result);
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
            log.error("Помилка при отриманні aut-num для Organisation", ex);
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
            log.error("Помилка при отриманні organisation для Organisation", ex);
        }
        return retVal.toString();
    }

    /**
     * Завантажує синтетичне резюме ASN (country, name) з таблиці {@code asn}
     * і форматує його як RPSL-подібний текст.
     *
     * @param as позначення автономної системи (наприклад, {@code "AS12345"})
     * @return рядок у форматі {@code "as-num: ... country: ... as-name: ...\n"},
     *         або порожній рядок, якщо ASN відсутній у таблиці
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
            log.error("Помилка при отриманні asn для Organisation", ex);
        }
        return retVal.toString();
    }

}
