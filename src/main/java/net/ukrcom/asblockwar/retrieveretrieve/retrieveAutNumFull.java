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
import java.util.HashSet;
import java.util.Set;
import net.ukrcom.asblockwar.Config;

/**
 * Витягує повний RPSL-блок aut-num: aut-num блок + синтетичне резюме з таблиці asn
 * (поля, що вже є в блоці, не дублюються) + блок organisation: якщо є org: посилання.
 * Відповідає виводу whois-lite-local -ran {as}.
 *
 * @author olden
 */
@Slf4j
public class retrieveAutNumFull {

    private final Config config;
    private final String autNum;
    private final StringBuilder sb = new StringBuilder();

    /**
     * Відкриває з'єднання з БД і завантажує повний RPSL-блок для вказаного aut-num.
     *
     * @param autNum позначення автономної системи у форматі {@code "AS12345"}
     */
    public retrieveAutNumFull(String autNum) {
        this.config = net.ukrcom.asblockwar.ASBlockWar.config;
        this.autNum = autNum;

        try (Connection conn = DriverManager.getConnection(this.config.getWhoisLiteLocalURI())) {
            loadAutNum(conn);
        } catch (SQLException ex) {
            log.error("Помилка при отриманні AutNumFull {}", autNum, ex);
        }
    }

    private void loadAutNum(Connection conn) throws SQLException {
        String autNumBlock = null;

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT block FROM rpsl WHERE key='aut-num' AND value=?")) {
            stmt.setString(1, this.autNum);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                autNumBlock = rs.getString("block");
                sb.append(autNumBlock);
                sb.append("\n");
            }
        }

        if (autNumBlock == null) {
            return;
        }

        appendAsnSummary(conn, autNumBlock);

        for (String line : autNumBlock.split("\n")) {
            String[] parts = line.split("\\s+", 2);
            if (parts.length == 2 && parts[0].trim().equals("org:")) {
                appendOrg(conn, parts[1].trim());
            }
        }
    }

    private void appendAsnSummary(Connection conn, String autNumBlock) throws SQLException {
        String asNum = this.autNum.replaceFirst("(?i)^AS", "");
        int asn;
        try {
            asn = Integer.parseInt(asNum);
        } catch (NumberFormatException ignored) {
            return;
        }

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT country, name FROM asn WHERE asn=?")) {
            stmt.setInt(1, asn);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Set<String> blockLines = new HashSet<>();
                autNumBlock.lines().filter(l -> !l.isBlank()).forEach(blockLines::add);

                String[] summaryLines = {
                    "as-num:         " + this.autNum.toUpperCase(),
                    "country:        " + rs.getString("country"),
                    "as-name:        " + rs.getString("name")
                };

                StringBuilder summary = new StringBuilder();
                boolean anyNew = false;
                for (String sl : summaryLines) {
                    if (!blockLines.contains(sl)) {
                        summary.append(sl).append("\n");
                        anyNew = true;
                    }
                }
                if (anyNew) {
                    sb.append(summary);
                    sb.append("\n");
                }
            }
        }
    }

    private void appendOrg(Connection conn, String org) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT block FROM rpsl WHERE key='organisation' AND value=?")) {
            stmt.setString(1, org);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                sb.append(rs.getString("block"));
                sb.append("\n");
            }
        }
    }

    /**
     * Повертає повний текст RPSL, зібраний для вказаного aut-num.
     *
     * @return конкатенований RPSL-текст (aut-num блок + ASN-резюме + org-блок),
     *         або порожній рядок, якщо AS не знайдено
     */
    public String get() {
        log.debug("retrieveAutNumFull({}).get(): {} chars", autNum, sb.length());
        return sb.toString();
    }
}
