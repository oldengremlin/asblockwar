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
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.ukrcom.asblockwar.Config;
import org.slf4j.Logger;

/**
 * Витягує AS-SET-и з полів import/export/mp-import/mp-export RPSL-блоку aut-num.
 * Шукає конструкції виду "accept AS-..." (ознака AS-SET, а не AS-номеру).
 *
 * @author olden
 */
public class retrieveImportExportAsSets {

    // Зупиняємось на пробілі, комі, дужці, крапці з комою — все це роздільники в RPSL-фільтрах
    private static final Pattern ACCEPT_AS_SET = Pattern.compile(
            "\\baccept\\s+(AS-[^\\s,{};]+)", Pattern.CASE_INSENSITIVE);

    private final Config config;
    private final Logger logger;
    private final String autNum;
    private final Set<String> asSets = new HashSet<>();

    public retrieveImportExportAsSets(String autNum) {
        this.config = net.ukrcom.asblockwar.ASBlockWar.config;
        this.logger = net.ukrcom.asblockwar.ASBlockWar.LOGGER;
        this.autNum = autNum;

        try (Connection conn = DriverManager.getConnection(this.config.getWhoisLiteLocalURI());
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT block FROM rpsl WHERE key='aut-num' AND value=?")) {
            stmt.setString(1, autNum);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                parse(rs.getString("block"));
            }
        } catch (SQLException ex) {
            this.logger.error("Помилка при читанні import/export для {}", autNum, ex);
        }
    }

    private void parse(String block) {
        boolean inImportExport = false;
        for (String line : block.split("\n")) {
            if (line.matches("(?i)^(import|export|mp-import|mp-export):.*")) {
                inImportExport = true;
            } else if (line.matches("^\\s+.*")) {
                // продовження рядка RFC 2622 — залишаємо стан
            } else {
                inImportExport = false;
            }

            if (inImportExport) {
                Matcher m = ACCEPT_AS_SET.matcher(line);
                while (m.find()) {
                    asSets.add(m.group(1).toUpperCase());
                }
            }
        }
    }

    public Set<String> get() {
        logger.debug("retrieveImportExportAsSets({}).get(): {}", autNum, asSets);
        return Set.copyOf(asSets);
    }
}
