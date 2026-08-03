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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

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

    private final static RpslCache cache = RpslCache.create();

    private StringBuilder sb;

    private final String autNum;

    /**
     * Відкриває з'єднання з БД (якщо результат ще не закешовано) і завантажує
     * ASN-резюме та пов'язаний organisation-блок для вказаного aut-num.
     *
     * @param autNum позначення автономної системи у форматі {@code "AS12345"}
     */
    public retrieveOrganisation(String autNum) {
        this.autNum = autNum;

        if (cache.get(autNum) != null) {
            log.debug("retrieveOrganisation({}) — cache hit", autNum);
            return;
        }

        this.sb = new StringBuilder();
        try (Connection conn = RpslDb.open()) {
            String block = RpslDb.fetchBlocks(conn, autNum, "aut-num");
            if (!block.isEmpty()) {
                this.sb.append(asnSummary(conn, autNum));
                this.sb.append(block).append("\n");
                this.sb.append(orgBlocks(conn, block));
            }
            // Кешуємо ЛИШЕ успішний результат — див. retrieveAsSet
            cache.put(autNum, this.sb.toString());
        } catch (SQLException ex) {
            log.error("Помилка при отриманні Organisation {}", autNum, ex);
        }
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
        // fallback: значення не потрапило в cache (наприклад, помилка SQL).
        // Кеш тут НЕ оновлюємо — інакше збій зафіксувався б назавжди.
        String result = this.sb != null ? this.sb.toString() : "";
        log.debug("retrieveOrganisation({}).get(): {}", this.autNum, result);
        return result;
    }

    /** Витягує organisation-блоки, на які посилається {@code org:} у aut-num. */
    private static String orgBlocks(Connection conn, String autNumBlock) throws SQLException {
        StringBuilder out = new StringBuilder();
        for (String line : autNumBlock.lines().toList()) {
            String[] parts = line.split("\\s+", 2);
            if (parts.length == 2 && parts[0].trim().equals("org:")) {
                String block = RpslDb.fetchBlocks(conn, parts[1].trim(), "organisation");
                if (!block.isEmpty()) {
                    out.append(block).append("\n");
                }
            }
        }
        return out.toString();
    }

    /**
     * Формує синтетичне резюме ASN (country, name) з таблиці {@code asn}
     * у вигляді RPSL-подібного тексту.
     *
     * @param conn відкрите з'єднання
     * @param as   позначення автономної системи (наприклад, {@code "AS12345"})
     * @return рядок {@code "as-num: ... country: ... as-name: ...\n"},
     *         або порожній рядок, якщо ASN відсутній у таблиці чи не є числом
     * @throws SQLException якщо запит не вдався
     */
    static String asnSummary(Connection conn, String as) throws SQLException {
        int asn;
        try {
            asn = Integer.parseInt(as.replaceFirst("^[Aa][Ss]", ""));
        } catch (NumberFormatException e) {
            log.warn("retrieveOrganisation: некоректне позначення ASN «{}»", as);
            return "";
        }
        StringBuilder retVal = new StringBuilder();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT country, name FROM asn WHERE asn=?")) {
            stmt.setInt(1, asn);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    retVal.append("as-num:         ").append(as.toUpperCase())
                          .append("\ncountry:        ").append(rs.getString("country"))
                          .append("\nas-name:        ").append(rs.getString("name"))
                          .append("\n");
                }
            }
        }
        return retVal.toString();
    }
}
