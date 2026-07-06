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
 * Витягує AS-номери з members: поля RPSL as-set блоку.
 * При recursionDepth > 0 рекурсивно заходить у вкладені AS-SET-и.
 *
 * @author olden
 */
public class retrieveAsSetMembers {

    private static final Pattern AS_NUMBER = Pattern.compile(
            "\\bAS(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AS_SET_NAME = Pattern.compile(
            "\\b(AS-[^\\s,{}]+)\\b", Pattern.CASE_INSENSITIVE);

    private final Config config;
    private final Logger logger;
    private final String asSet;
    private final int recursionDepth;
    private final Set<String> members = new HashSet<>();

    public retrieveAsSetMembers(String asSet, int recursionDepth) {
        this.config = net.ukrcom.asblockwar.ASBlockWar.config;
        this.logger = net.ukrcom.asblockwar.ASBlockWar.LOGGER;
        this.asSet = asSet;
        this.recursionDepth = recursionDepth;

        try (Connection conn = DriverManager.getConnection(this.config.getWhoisLiteLocalURI())) {
            Set<String> visited = new HashSet<>();
            collect(conn, asSet, recursionDepth, visited);
        } catch (SQLException ex) {
            this.logger.error("Помилка при читанні as-set {}", asSet, ex);
        }
    }

    private void collect(Connection conn, String setName, int depth, Set<String> visited) throws SQLException {
        if (!visited.add(setName.toUpperCase())) {
            return;
        }

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT block FROM rpsl WHERE key='as-set' AND value=?")) {
            stmt.setString(1, setName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                parseBlock(conn, rs.getString("block"), depth, visited);
            }
        }
    }

    private void parseBlock(Connection conn, String block, int depth, Set<String> visited) throws SQLException {
        boolean inMembers = false;
        for (String line : block.split("\n")) {
            if (line.matches("(?i)^members:.*")) {
                inMembers = true;
            } else if (line.matches("^\\s+.*")) {
                // RFC 2622 continuation line — keep state
            } else {
                inMembers = false;
            }

            if (!inMembers) {
                continue;
            }

            Matcher mNum = AS_NUMBER.matcher(line);
            while (mNum.find()) {
                members.add("AS" + mNum.group(1));
            }

            if (depth > 0) {
                Matcher mSet = AS_SET_NAME.matcher(line);
                while (mSet.find()) {
                    collect(conn, mSet.group(1), depth - 1, visited);
                }
            }
        }
    }

    public Set<String> get() {
        logger.debug("retrieveAsSetMembers({}, depth={}).get(): {} members", asSet, recursionDepth, members.size());
        return Set.copyOf(members);
    }
}
