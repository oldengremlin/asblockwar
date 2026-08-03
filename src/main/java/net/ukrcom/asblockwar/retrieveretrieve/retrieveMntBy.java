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

    private static final RpslCache cache = RpslCache.create();

    private StringBuilder sb;

    private final String mntBy;

    /**
     * Відкриває з'єднання з БД і завантажує усі RPSL-блоки, обслуговувані вказаним мантейнером.
     *
     * @param mntBy назва mnt-by (наприклад, {@code "MNTNER-UA"})
     */
    public retrieveMntBy(String mntBy) {
        this.sb = new StringBuilder();
        this.mntBy = mntBy;

        String cached = cache.get(mntBy);
        if (cached != null) {
            this.sb.append(cached);
            return;
        }

        try (Connection conn = RpslDb.open()) {
            for (String value : RpslDb.fetchColumn(conn,
                    "SELECT value FROM rpsl_mntby WHERE key IN ('aut-num', 'as-set') AND mntby = ?",
                    mntBy, "value")) {
                this.sb.append(RpslDb.fetchBlocks(conn, value, "aut-num", "as-set"));
                this.sb.append("\n");
            }
            // Кешуємо ЛИШЕ успішний результат — див. retrieveAsSet
            cache.put(mntBy, this.sb.toString());
        } catch (SQLException ex) {
            log.error("Помилка при отриманні MntBy {}", mntBy, ex);
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

}
