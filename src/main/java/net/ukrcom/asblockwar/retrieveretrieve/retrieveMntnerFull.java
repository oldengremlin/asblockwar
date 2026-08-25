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
 * Витягує повний RPSL-блок mntner: блок мантейнера + role-блоки пов'язаних контактів.
 * Відповідає виводу whois-lite-local -rm {mnt}.
 *
 * @author olden
 */
@Slf4j
public class retrieveMntnerFull {

    private final Config config;
    private final String mntner;
    private final StringBuilder sb = new StringBuilder();

    /**
     * Відкриває з'єднання з БД і завантажує RPSL-блок mntner разом
     * із пов'язаними role-блоками контактів.
     *
     * @param mntner назва мантейнера (наприклад, {@code "MNTNER-UA"})
     */
    public retrieveMntnerFull(String mntner) {
        this.config = net.ukrcom.asblockwar.ASBlockWar.config;
        this.mntner = mntner;

        try (Connection conn = RpslDb.open()) {
            loadMntner(conn);
        } catch (SQLException ex) {
            log.error("Помилка при отриманні MntnerFull {}", mntner, ex);
        }
    }

    private void loadMntner(Connection conn) throws SQLException {
        sb.append(RpslDb.fetchBlocks(conn, this.mntner, "mntner")).append("\n");
        // Один JOIN замість «список ролей + запит на кожну» — див. retrieveRouteOriginFull
        for (String block : RpslDb.fetchColumn(conn,
                "SELECT r.block FROM rpsl_mntby m"
                + " JOIN rpsl r ON r.value = m.value AND r.key = 'role'"
                + " WHERE m.key = 'role' AND m.mntby = ?", this.mntner, "block")) {
            sb.append(block).append("\n");
        }
    }

    /**
     * Повертає повний текст RPSL для вказаного mntner:
     * mntner-блок плюс role-блоки пов'язаних контактів.
     *
     * @return конкатенований RPSL-текст, або порожній рядок, якщо мантейнер не знайдено
     */
    public String get() {
        log.debug("retrieveMntnerFull({}).get(): {} chars", mntner, sb.length());
        return sb.toString();
    }
}
